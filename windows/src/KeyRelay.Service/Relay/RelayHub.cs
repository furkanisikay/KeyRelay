using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>Serves tablet connections: TLS 1.3, PAIR and HELLO within 5 s, then one active session at a time.</summary>
/// <remarks>
/// Its Information lines are security events and also go to the Application event log (Program.cs), so the ones an
/// unauthenticated peer can cause go through <see cref="LogBudget"/>.
/// </remarks>
internal sealed partial class RelayHub(RelayContext context, ILogger<RelayHub> logger)
{
    private const int MAX_DEVICE_NAME = 64;
    private const int SECURITY_LOG_PER_MINUTE = 20;
    // Bytes of the device id shown in the log: enough to tell devices apart, too few to stand for the id.
    private const int LOGGED_ID_BYTES = 4;
    private const string FALLBACK_DEVICE_NAME = "Android";
    private readonly Lock _gate = new();
    // Connections between accept and a verified HELLO: [0] direct, [1] loopback (Funnel).
    private readonly int[] _pendingHandshakes = new int[2];
    private readonly LogBudget _securityLog = new(context.Time, SECURITY_LOG_PER_MINUTE);
    private RelaySession? _active;
    private LastSession _last = new(0, null, false);
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

    /// <summary>Gets the newest verified session, kept after it ends so the tray can report even a short one.</summary>
    internal LastSession Last
    {
        get { lock (_gate) return _last; }
    }

    /// <summary>Serves one accepted socket until it closes. Never throws.</summary>
    internal async Task HandleAsync(Socket socket, CancellationToken cancellationToken)
    {
        var address = (socket.RemoteEndPoint as IPEndPoint)?.Address;
        var remote = address?.ToString() ?? "?";
        var viaLoopback = IsLoopback(address);
        var slot = viaLoopback ? 1 : 0;
        // Disposed here too: SslStream owns the socket only once it exists.
        using var _ = socket;
        if (Interlocked.Increment(ref _pendingHandshakes[slot]) > context.MaxPendingHandshakes)
        {
            Interlocked.Decrement(ref _pendingHandshakes[slot]);
            if (SecurityLogAllowed()) LogBusy(remote);
            return;
        }
        var handshaking = true;
        try
        {
            socket.NoDelay = true;
            var ssl = new SslStream(new NetworkStream(socket, ownsSocket: true));
            await using (ssl.ConfigureAwait(false))
            {
                var reader = new FrameReader(ssl, FrameChannel.Tls);
                var device = await HandshakeAsync(ssl, reader, remote, viaLoopback, cancellationToken).ConfigureAwait(false);
                // The session holds no slot: only one is active and it would keep its slot for hours.
                EndHandshake();
                if (device is not null) await RunSessionAsync(new RelaySession(ssl, reader, device, context), remote, viaLoopback, cancellationToken).ConfigureAwait(false);
            }
        }
        catch (Exception ex) when (ex is IOException or AuthenticationException or ProtocolException or OperationCanceledException or SocketException)
        {
            if (!handshaking || cancellationToken.IsCancellationRequested) LogConnectionClosed(ex, remote);
            else if (SecurityLogAllowed()) LogHandshakeFailed(remote, ex is OperationCanceledException ? "timeout" : ex.Message);
        }
        catch (Exception ex)
        {
            LogConnectionFailed(ex, remote);
        }
        finally
        {
            EndHandshake();
        }

        void EndHandshake()
        {
            if (!handshaking) return;
            handshaking = false;
            Interlocked.Decrement(ref _pendingHandshakes[slot]);
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

    private async Task<DeviceRecord?> HandshakeAsync(SslStream ssl, FrameReader reader, string remote, bool viaLoopback, CancellationToken cancellationToken)
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
            if (!await PairAsync(ssl, frame.Value.Payload, remote, viaLoopback, hello.Token).ConfigureAwait(false)) return null;
            frame = await reader.ReadAsync(hello.Token).ConfigureAwait(false);
        }
        return frame?.Type == FrameType.Hello ? await HelloAsync(ssl, frame.Value.Payload, remote, hello.Token).ConfigureAwait(false) : null;
    }

    private async Task<bool> PairAsync(SslStream ssl, ReadOnlyMemory<byte> payload, string remote, bool viaLoopback, CancellationToken cancellationToken)
    {
        // Checked before the throttle: all Funnel peers share one loopback address, so a failure counted here would
        // let anyone on the internet keep the "pair" key blocked.
        if (viaLoopback && !context.AllowLoopbackPairing)
        {
            if (SecurityLogAllowed()) LogPairFromLoopback(remote);
            // Its own reason so the tablet can say "join the home Wi-Fi" instead of "wrong code"; older tablets show
            // every PAIR reject the same way, so they need no special case.
            await ssl.WriteAsync(Frames.Reject(RejectReason.PairingNotAllowedHere), cancellationToken).ConfigureAwait(false);
            return false;
        }
        var name = Pair(payload.Span, remote, out var reason);
        if (name is null)
        {
            if (SecurityLogAllowed()) LogRejected(remote, reason);
            await ssl.WriteAsync(Frames.Reject(reason), cancellationToken).ConfigureAwait(false);
            return false;
        }
        var (id, token) = context.Devices.Add(name);
        await ssl.WriteAsync(Frames.Paired(id, token), cancellationToken).ConfigureAwait(false);
        LogPaired(name, IdPrefix(id), remote);
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
            if (SecurityLogAllowed()) LogHelloRejected(remote, IdPrefix(payload.Span.Slice(1, Frames.ID_SIZE)), reason);
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

    private async Task RunSessionAsync(RelaySession session, string remote, bool viaLoopback, CancellationToken cancellationToken)
    {
        RelaySession? replaced;
        Task previous;
        var done = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        lock (_gate)
        {
            replaced = _active;
            _active = session;
            _last = new LastSession(_last.Count + 1, session.Device.Name, viaLoopback);
            previous = _activeDone;
            _activeDone = done.Task;
            // A revoke between Verify and here found no active session to close.
            if (!context.Devices.List().Exists(d => d.Id == session.Device.Id)) session.Close();
        }
        replaced?.Close();
        LogConnected(session.Device.Name, session.Device.Id[..(LOGGED_ID_BYTES * 2)], remote, viaLoopback ? "Funnel" : "direct");
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

    // Tailscale Funnel hands connections to the service from loopback; the listener is dual mode, so IPv4 shows up mapped.
    internal static bool IsLoopback(IPAddress? address) =>
        address is not null && IPAddress.IsLoopback(address.IsIPv4MappedToIPv6 ? address.MapToIPv4() : address);

    private static string IdPrefix(ReadOnlySpan<byte> id) => Convert.ToHexStringLower(id[..LOGGED_ID_BYTES]);

    private bool SecurityLogAllowed()
    {
        if (!_securityLog.TryTake(out var suppressed)) return false;
        if (suppressed > 0) LogSuppressed(suppressed);
        return true;
    }

    /// <param name="Count">Sessions verified since the service started; a change means a new connection.</param>
    /// <param name="Device">Name of the device of the newest session.</param>
    /// <param name="ViaLoopback">The newest session came from loopback, that is through Tailscale Funnel.</param>
    internal sealed record LastSession(long Count, string? Device, bool ViaLoopback);

    [LoggerMessage(Level = LogLevel.Debug, Message = "Connection from {Remote} closed")]
    private partial void LogConnectionClosed(Exception ex, string remote);

    [LoggerMessage(Level = LogLevel.Error, Message = "Connection from {Remote} failed")]
    private partial void LogConnectionFailed(Exception ex, string remote);

    [LoggerMessage(Level = LogLevel.Information, Message = "Handshake from {Remote} failed: {Error}")]
    private partial void LogHandshakeFailed(string remote, string error);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Dropped {Remote}: too many connections waiting for HELLO")]
    private partial void LogBusy(string remote);

    [LoggerMessage(Level = LogLevel.Warning, Message = "{Count} more rejected or failed connections were not logged")]
    private partial void LogSuppressed(int count);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Paired {Device} ({Id}…) from {Remote}")]
    private partial void LogPaired(string device, string id, string remote);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Rejected PAIR from {Remote}: pairing through Funnel is not allowed, pair on the LAN or tailnet")]
    private partial void LogPairFromLoopback(string remote);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Rejected PAIR from {Remote}: {Reason}")]
    private partial void LogRejected(string remote, RejectReason reason);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Rejected HELLO from {Remote} for device {Id}…: {Reason}")]
    private partial void LogHelloRejected(string remote, string id, RejectReason reason);

    [LoggerMessage(Level = LogLevel.Information, Message = "Connected: {Device} ({Id}…) from {Remote} via {Path}")]
    private partial void LogConnected(string device, string id, string remote, string path);

    [LoggerMessage(Level = LogLevel.Information, Message = "Disconnected: {Device}")]
    private partial void LogDisconnected(string device);
}
