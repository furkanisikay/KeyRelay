using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>Serves tablet connections: TLS 1.3, PAIR and HELLO within 5 s, then one active session at a time.</summary>
internal sealed partial class RelayHub(RelayContext context, ILogger<RelayHub> logger)
{
    private const int MAX_DEVICE_NAME = 64;
    private const string FALLBACK_DEVICE_NAME = "Android";
    private readonly Lock _gate = new();
    private RelaySession? _active;
    private Task _activeDone = Task.CompletedTask;

    internal string? ActiveDeviceName
    {
        get { lock (_gate) return _active?.Device.Name; }
    }

    internal string? ActiveDeviceId
    {
        get { lock (_gate) return _active?.Device.Id; }
    }

    /// <summary>Gets the heartbeat count (PINGs) of the active session, 0 without one.</summary>
    internal long ActivePings
    {
        get { lock (_gate) return _active?.Pings ?? 0; }
    }

    /// <summary>Serves one accepted socket until it closes. Never throws.</summary>
    internal async Task HandleAsync(Socket socket, CancellationToken cancellationToken)
    {
        var remote = (socket.RemoteEndPoint as IPEndPoint)?.Address.ToString() ?? "?";
        // Disposed here too: SslStream owns the socket only once it exists.
        using var _ = socket;
        try
        {
            socket.NoDelay = true;
            var ssl = new SslStream(new NetworkStream(socket, ownsSocket: true));
            await using (ssl.ConfigureAwait(false))
            {
                var reader = new FrameReader(ssl, FrameChannel.Tls);
                var device = await HandshakeAsync(ssl, reader, remote, cancellationToken).ConfigureAwait(false);
                if (device is not null) await RunSessionAsync(new RelaySession(ssl, reader, device, context), cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is IOException or AuthenticationException or ProtocolException or OperationCanceledException or SocketException)
        {
            LogConnectionClosed(ex, remote);
        }
        catch (Exception ex)
        {
            LogConnectionFailed(ex, remote);
        }
    }

    /// <summary>Drops the active session of a revoked device at once.</summary>
    internal void CloseDevice(string deviceId)
    {
        lock (_gate)
        {
            if (_active?.Device.Id == deviceId) _active.Close();
        }
    }

    private async Task<DeviceRecord?> HandshakeAsync(SslStream ssl, FrameReader reader, string remote, CancellationToken cancellationToken)
    {
        using var hello = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        hello.CancelAfter(context.HelloTimeout);
        var options = new SslServerAuthenticationOptions
        {
            ServerCertificate = context.Certificate,
            EnabledSslProtocols = SslProtocols.Tls13,
            ClientCertificateRequired = false,
        };
        await ssl.AuthenticateAsServerAsync(options, hello.Token).ConfigureAwait(false);
        var frame = await reader.ReadAsync(hello.Token).ConfigureAwait(false);
        if (frame?.Type == FrameType.Pair)
        {
            if (!await PairAsync(ssl, frame.Value.Payload, remote, hello.Token).ConfigureAwait(false)) return null;
            frame = await reader.ReadAsync(hello.Token).ConfigureAwait(false);
        }
        return frame?.Type == FrameType.Hello ? await HelloAsync(ssl, frame.Value.Payload, remote, hello.Token).ConfigureAwait(false) : null;
    }

    private async Task<bool> PairAsync(SslStream ssl, ReadOnlyMemory<byte> payload, string remote, CancellationToken cancellationToken)
    {
        var name = Pair(payload.Span, remote, out var reason);
        if (name is null)
        {
            LogRejected(remote, reason);
            await ssl.WriteAsync(Frames.Reject(reason), cancellationToken).ConfigureAwait(false);
            return false;
        }
        var (id, token) = context.Devices.Add(name);
        await ssl.WriteAsync(Frames.Paired(id, token), cancellationToken).ConfigureAwait(false);
        LogPaired(name, remote);
        return true;
    }

    private string? Pair(ReadOnlySpan<byte> payload, string remote, out RejectReason reason)
    {
        reason = RejectReason.Version;
        if (payload[0] != Frames.VERSION) return null;
        reason = RejectReason.InvalidCode;
        var key = $"{remote}|pair";
        if (context.Throttle.IsBlocked(key) || !context.Pairing.TryConsume(payload.Slice(1, Frames.CODE_SIZE)))
        {
            context.Throttle.Failed(key);
            return null;
        }
        context.Throttle.Succeeded(key);
        return DeviceName(payload[(1 + Frames.CODE_SIZE)..]);
    }

    private async Task<DeviceRecord?> HelloAsync(SslStream ssl, ReadOnlyMemory<byte> payload, string remote, CancellationToken cancellationToken)
    {
        var device = Verify(payload.Span, remote, out var reason);
        if (device is null)
        {
            LogRejected(remote, reason);
            await ssl.WriteAsync(Frames.Reject(reason), cancellationToken).ConfigureAwait(false);
            return null;
        }
        await ssl.WriteAsync(Frames.Welcome(Environment.MachineName), cancellationToken).ConfigureAwait(false);
        return device;
    }

    private DeviceRecord? Verify(ReadOnlySpan<byte> payload, string remote, out RejectReason reason)
    {
        reason = RejectReason.Version;
        if (payload[0] != Frames.VERSION) return null;
        reason = RejectReason.UnknownDevice;
        var id = payload.Slice(1, Frames.ID_SIZE);
        var key = $"{remote}|{Convert.ToHexStringLower(id)}";
        var device = context.Throttle.IsBlocked(key) ? null : context.Devices.Verify(id, payload.Slice(1 + Frames.ID_SIZE, Frames.TOKEN_SIZE));
        if (device is null)
        {
            context.Throttle.Failed(key);
            return null;
        }
        context.Throttle.Succeeded(key);
        context.Devices.Touch(device.Id);
        return device;
    }

    private async Task RunSessionAsync(RelaySession session, CancellationToken cancellationToken)
    {
        RelaySession? replaced;
        Task previous;
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            replaced = _active;
            _active = session;
            previous = _activeDone;
            _activeDone = done.Task;
            // A revoke between Verify and here found no active session to close.
            if (!context.Devices.List().Exists(d => d.Id == session.Device.Id)) session.Close();
        }
        replaced?.Close();
        LogConnected(session.Device.Name);
        try
        {
            // The replaced session's RELEASE_ALL must reach the agent before this session's first key.
            await previous.ConfigureAwait(false);
            await session.RunAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            try
            {
                lock (_gate)
                {
                    if (_active == session) _active = null;
                }
                // Sent after RunAsync so no key of this session can arrive behind it, replaced or not.
                await context.Agent.SendAsync(Frames.ReleaseAllFrame).ConfigureAwait(false);
                session.Dispose();
                LogDisconnected(session.Device.Name);
            }
            finally
            {
                done.SetResult();
            }
        }
    }

    private static string DeviceName(ReadOnlySpan<byte> utf8)
    {
        var name = string.Concat(Encoding.UTF8.GetString(utf8).Where(c => !char.IsControl(c))).Trim();
        if (name.Length == 0) return FALLBACK_DEVICE_NAME;
        var length = Math.Min(name.Length, MAX_DEVICE_NAME);
        // Never split a surrogate pair: a lone surrogate makes JsonEncodedText throw on the tray's log page.
        return name[..(char.IsHighSurrogate(name[length - 1]) ? length - 1 : length)];
    }

    [LoggerMessage(Level = LogLevel.Debug, Message = "Connection from {Remote} closed")]
    private partial void LogConnectionClosed(Exception ex, string remote);

    [LoggerMessage(Level = LogLevel.Error, Message = "Connection from {Remote} failed")]
    private partial void LogConnectionFailed(Exception ex, string remote);

    [LoggerMessage(Level = LogLevel.Information, Message = "Paired {Device} from {Remote}")]
    private partial void LogPaired(string device, string remote);

    [LoggerMessage(Level = LogLevel.Information, Message = "Rejected {Remote}: {Reason}")]
    private partial void LogRejected(string remote, RejectReason reason);

    [LoggerMessage(Level = LogLevel.Information, Message = "Connected: {Device}")]
    private partial void LogConnected(string device);

    [LoggerMessage(Level = LogLevel.Information, Message = "Disconnected: {Device}")]
    private partial void LogDisconnected(string device);
}
