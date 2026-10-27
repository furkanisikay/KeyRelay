using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Control;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Settings;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.TestSupport;

/// <summary>A RelayHub on a loopback port with a fake agent on a private pipe and a manual clock.</summary>
internal sealed class RelayHarness : IAsyncDisposable
{
    private readonly TempDirectory _directory = new();
    private readonly Socket _listener = new(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
    private readonly CancellationTokenSource _stop = new();
    private readonly string _agentPipe = TestPipes.Name();
    private Task _agentRun = Task.CompletedTask;
    private int _sasCalls;

    private RelayHarness(int idleTimeoutSeconds, TimeSpan helloTimeout, bool allowLoopbackPairing, int maxPendingHandshakes)
    {
        Settings = new SettingsStore(_directory.Path);
        Settings.Save(new ServiceSettings { IdleTimeoutSeconds = idleTimeoutSeconds });
        Certificate = CertificateStore.LoadOrCreate(_directory.Path);
        Fingerprint = CertificateStore.Fingerprint(Certificate);
        Devices = new DeviceRegistry(_directory.Path, Time, NullLogger.Instance);
        Pairing = new PairingCodes(Time);
        Throttle = new AuthThrottle(Time);
        Latency = new LatencyStats(Time);
        Agent = new AgentLink(new AgentLinkOptions(_agentPipe, TestPipes.CurrentUser()), Latency, NullLogger<AgentLink>.Instance);
        Recent = new RecentKeys(Time, () => Desktop, () => KeySession);
        var context = new RelayContext(Certificate, Devices, Pairing, Throttle, Agent, Recent, () => Interlocked.Increment(ref _sasCalls), Settings, NullLogger.Instance)
        {
            HelloTimeout = helloTimeout,
            // The test tablet connects over loopback, where the service refuses PAIR (it is Funnel there).
            AllowLoopbackPairing = allowLoopbackPairing,
            MaxPendingHandshakes = maxPendingHandshakes,
            Time = Time,
        };
        Hub = new RelayHub(context, NullLogger<RelayHub>.Instance);
        _listener.Bind(new IPEndPoint(IPAddress.Loopback, 0));
        _listener.Listen();
        _ = AcceptLoopAsync();
    }

    internal ManualTime Time { get; } = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    internal SettingsStore Settings { get; }

    internal X509Certificate2 Certificate { get; }

    internal string Fingerprint { get; }

    internal DeviceRegistry Devices { get; }

    internal PairingCodes Pairing { get; }

    internal AuthThrottle Throttle { get; }

    internal LatencyStats Latency { get; }

    internal AgentLink Agent { get; }

    internal RecentKeys Recent { get; }

    /// <summary>Gets or sets the desktop the "agent" types into, as AgentLink.DesktopName reports it.</summary>
    internal string Desktop { get; set; } = "Default";

    /// <summary>Gets or sets the session keys are typed into; by default the test's own, so its pipe client matches.</summary>
    internal uint? KeySession { get; set; } = OwnSession;

    internal static uint OwnSession
    {
        get
        {
            using var process = System.Diagnostics.Process.GetCurrentProcess();
            return (uint)process.SessionId;
        }
    }

    internal RelayHub Hub { get; }

    internal FakeAgent? AgentPipe { get; private set; }

    internal int SasCalls => Volatile.Read(ref _sasCalls);

    internal int Port => ((IPEndPoint)_listener.LocalEndPoint!).Port;

    internal static async Task<RelayHarness> StartAsync(
        bool connectAgent = true, int idleTimeoutSeconds = 20, double helloTimeoutSeconds = 5, bool allowLoopbackPairing = true, int maxPendingHandshakes = 8)
    {
        var harness = new RelayHarness(idleTimeoutSeconds, TimeSpan.FromSeconds(helloTimeoutSeconds), allowLoopbackPairing, maxPendingHandshakes);
        if (connectAgent) await harness.ConnectAgentAsync();
        return harness;
    }

    internal Task<TabletClient> ConnectTabletAsync() => TabletClient.ConnectAsync(Port, Fingerprint);

    /// <summary>Pairs a device over TLS (the connection closes after PAIRED).</summary>
    internal async Task<(byte[] Id, byte[] Token)> PairAsync(string name = "Tab")
    {
        var (code, _) = Pairing.Start();
        await using var tablet = await ConnectTabletAsync();
        await tablet.SendAsync(Frames.Pair(code, name));
        var paired = await tablet.ExpectAsync(FrameType.Paired);
        return (paired.Payload[..Frames.ID_SIZE], paired.Payload[Frames.ID_SIZE..]);
    }

    /// <summary>Connects and authenticates; returns after WELCOME.</summary>
    internal async Task<TabletClient> HelloAsync((byte[] Id, byte[] Token) device)
    {
        var tablet = await ConnectTabletAsync();
        await tablet.SendAsync(Frames.Hello(device.Id, device.Token));
        await tablet.ExpectAsync(FrameType.Welcome);
        return tablet;
    }

    /// <summary>Sends HELLO on a fresh connection and returns the type of the answer.</summary>
    internal async Task<FrameType> HelloResultAsync(byte[] id, byte[] token)
    {
        await using var tablet = await ConnectTabletAsync();
        await tablet.SendAsync(Frames.Hello(id, token));
        return (await tablet.NextAsync()).Type;
    }

    public async ValueTask DisposeAsync()
    {
        await _stop.CancelAsync();
        _listener.Dispose();
        if (AgentPipe is not null) await AgentPipe.DisposeAsync();
        await _agentRun;
        Agent.Dispose();
        Recent.Dispose();
        Certificate.Dispose();
        _stop.Dispose();
        _directory.Dispose();
    }

    private async Task ConnectAgentAsync()
    {
        _agentRun = Agent.RunAsync(Environment.ProcessId, _stop.Token);
        AgentPipe = await FakeAgent.ConnectAsync(_agentPipe);
        await Eventually.TrueAsync(() => Agent.Ready, "agent link did not become ready");
    }

    private async Task AcceptLoopAsync()
    {
        try
        {
            while (true)
            {
                var socket = await _listener.AcceptAsync(_stop.Token);
                _ = Hub.HandleAsync(socket, _stop.Token);
            }
        }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException or SocketException)
        {
            // Harness disposed.
        }
    }
}
