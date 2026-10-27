using System.Net;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using KeyRelay.Protocol;
using KeyRelay.Protocol.Control;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Session;
using KeyRelay.Service.Settings;

namespace KeyRelay.Service.Control;

/// <summary>What the control handler reads and changes.</summary>
internal sealed record ControlContext(
    DeviceRegistry Devices,
    PairingCodes Pairing,
    RelayHub Hub,
    AgentLink Agent,
    LatencyStats Latency,
    RecentKeys Recent,
    SettingsStore Settings,
    LogBuffer Logs,
    AgentSupervisor Supervisor,
    X509Certificate2 Certificate)
{
    /// <summary>Gets the session keys are typed into (the agent's); tests replace it.</summary>
    internal Func<uint?> KeySession { get; init; } = () => Supervisor.ActiveSession;
}

/// <summary>Answers control pipe requests from the tray app.</summary>
internal sealed partial class ControlHandler(ControlContext context, ILogger<ControlHandler> logger)
{
    // Leaves room under ControlChannel.MAX_MESSAGE_BYTES for the rest of the response.
    private const int LOGS_BUDGET_BYTES = 60 * 1024;
    // Commands that change what the tablet can do; INTERACTIVE also covers other users' sessions (fast user switching, RDP).
    private static readonly string[] CHANGES_STATE = [ControlOps.PAIR_START, ControlOps.PAIR_STOP, ControlOps.REVOKE, ControlOps.SET_FUNNEL];

    /// <param name="request">The request.</param>
    /// <param name="clientSession">Windows session of the pipe client, null when unknown; state changes and the key strip need the agent's session.</param>
    internal ControlResponse Handle(ControlRequest request, uint? clientSession)
    {
        var fromKeySession = clientSession is { } session && session == context.KeySession();
        if (!fromKeySession && CHANGES_STATE.Contains(request.Op)) return ControlResponse.Fail(ControlErrors.WRONG_SESSION);
        return request.Op switch
        {
            ControlOps.STATUS => new ControlResponse { Status = Status() },
            ControlOps.PAIR_START => new ControlResponse { Pairing = StartPairing() },
            ControlOps.PAIR_STOP => StopPairing(),
            ControlOps.DEVICES => new ControlResponse { Devices = Devices(), Error = context.Devices.SetAside is null ? null : ControlErrors.DEVICES_SET_ASIDE },
            ControlOps.REVOKE => Revoke(request.DeviceId),
            ControlOps.NETWORK => new ControlResponse { Network = Network() },
            ControlOps.SET_FUNNEL => SetFunnel(request.FunnelAddress ?? ""),
            ControlOps.LOGS => new ControlResponse { Logs = Logs() },
            ControlOps.RECENT_KEYS => new ControlResponse { RecentKeys = fromKeySession ? context.Recent.Take(clientSession!.Value) : [] },
            ControlOps.RECENT_KEYS_STOP => StopRecentKeys(),
            ControlOps.LATENCY_BREAKDOWN => new ControlResponse { LatencyBreakdown = context.Latency.Breakdown() },
            _ => ControlResponse.Fail(ControlErrors.UNKNOWN_OP),
        };
    }

    // The MagicDNS name is the full host name; MachineName is the NetBIOS name, cut at 15 characters.
    private static List<string> Addresses(ServiceSettings settings) =>
        PairingAddresses.Order(PairingAddresses.Current(), settings.FunnelAddress, Dns.GetHostName());

    private ControlStatus Status()
    {
        var latency = context.Latency.Snapshot();
        var last = context.Hub.Last;
        return new ControlStatus
        {
            AgentConnected = context.Agent.Connected,
            AgentSession = context.Supervisor.ActiveSession,
            InputDesktop = context.Agent.DesktopName,
            Writable = context.Agent.Writable,
            Ready = context.Agent.Ready,
            ConnectedDevice = context.Hub.ActiveDeviceName,
            Sessions = last.Count,
            LastSessionDevice = last.Device,
            LastSessionViaFunnel = last.ViaLoopback,
            Heartbeats = context.Hub.ActivePings,
            LatencyP50Micros = latency.P50Micros,
            LatencyP99Micros = latency.P99Micros,
            LatencyPerSecondMicros = latency.PerSecondMaxMicros,
            KeysToday = latency.KeysToday,
            PairingActive = context.Pairing.ActiveUntil is not null,
            DeviceCount = context.Devices.List().Count,
        };
    }

    private PairingInfo StartPairing()
    {
        var settings = context.Settings.Load();
        var addresses = Addresses(settings);
        var (code, expiresAt) = context.Pairing.Start();
        var link = PairingAddresses.BuildUri(Environment.MachineName, addresses, settings.Port, CertificateStore.Fingerprint(context.Certificate), code);
        LogPairingStarted(addresses.Count);
        return new PairingInfo(link, addresses, expiresAt, PairingIdentity.IdentityCode(CertificateStore.SpkiSha256(context.Certificate)));
    }

    private ControlResponse StopPairing()
    {
        context.Pairing.Stop();
        return new ControlResponse();
    }

    private ControlResponse StopRecentKeys()
    {
        context.Recent.Stop();
        return new ControlResponse();
    }

    private List<DeviceInfo> Devices()
    {
        var active = context.Hub.ActiveDeviceId;
        return [.. context.Devices.List().Select(d => new DeviceInfo(d.Id, d.Name, d.AddedAt, d.LastSeenAt, d.Id == active))];
    }

    private ControlResponse Revoke(string? deviceId)
    {
        if (deviceId is null || !context.Devices.Revoke(deviceId)) return ControlResponse.Fail(ControlErrors.UNKNOWN_DEVICE);
        context.Hub.CloseDevice(deviceId);
        LogRevoked(deviceId);
        return new ControlResponse();
    }

    private NetworkInfo Network()
    {
        var settings = context.Settings.Load();
        return new NetworkInfo(settings.Port, settings.FunnelAddress, CertificateStore.Fingerprint(context.Certificate), Addresses(settings));
    }

    private ControlResponse SetFunnel(string address)
    {
        address = address.Trim();
        if (!ServiceSettings.IsValidFunnelAddress(address)) return ControlResponse.Fail(ControlErrors.INVALID_FUNNEL);
        context.Settings.Save(context.Settings.Load() with { FunnelAddress = address });
        LogFunnelSet(address);
        return new ControlResponse();
    }

    /// <summary>The newest lines whose JSON form fits the budget, oldest first.</summary>
    private List<string> Logs()
    {
        var lines = context.Logs.Snapshot();
        var first = lines.Count;
        var bytes = 0;
        while (first > 0 && (bytes += JsonSize(lines[first - 1]) + 3) <= LOGS_BUDGET_BYTES) first--;
        return lines.GetRange(first, lines.Count - first);

        // Measured as JSON: escaped non-ASCII and HTML characters take up to 6 bytes each; 3 more for quotes and comma.
        static int JsonSize(string line)
        {
            try
            {
                return JsonEncodedText.Encode(line).EncodedUtf8Bytes.Length;
            }
            catch (ArgumentException)
            {
                // A lone surrogate: Encode refuses it, the serializer writes it as �.
                return line.Length * 6;
            }
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Pairing started with {Count} addresses")]
    private partial void LogPairingStarted(int count);

    [LoggerMessage(Level = LogLevel.Information, Message = "Device {DeviceId} revoked")]
    private partial void LogRevoked(string deviceId);

    [LoggerMessage(Level = LogLevel.Information, Message = "Funnel address set to '{Address}'")]
    private partial void LogFunnelSet(string address);
}
