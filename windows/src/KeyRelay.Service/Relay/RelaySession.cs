using System.Diagnostics;
using System.Net.Security;
using System.Runtime.CompilerServices;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>One verified tablet connection: forwards keys to the agent, reports STATE, answers PING, ends on idle, close or replacement.</summary>
internal sealed partial class RelaySession(SslStream ssl, FrameReader reader, DeviceRecord device, RelayContext context) : IDisposable
{
    // A tablet that does not take a frame within 1 s is gone.
    private static readonly TimeSpan SEND_TIMEOUT = TimeSpan.FromSeconds(1);
    private readonly CancellationTokenSource _closed = new();
    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private long _pings;

    internal DeviceRecord Device => device;

    /// <summary>Gets how many PINGs the tablet sent on this connection (its heartbeat).</summary>
    internal long Pings => Interlocked.Read(ref _pings);

    /// <summary>Serves the tablet until it leaves, stays silent for idleTimeout or Close is called.</summary>
    internal async Task RunAsync(CancellationToken cancellationToken)
    {
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken, _closed.Token);
        var idleTimeout = TimeSpan.FromSeconds(context.Settings.Load().IdleTimeoutSeconds);
        context.Agent.ReadyChanged += OnReadyChanged;
        try
        {
            if (!await WriteAsync(null).ConfigureAwait(false)) return;
            idle.CancelAfter(idleTimeout);
            while (await reader.ReadAsync(idle.Token).ConfigureAwait(false) is { } frame)
            {
                idle.CancelAfter(idleTimeout);
                if (!await HandleAsync(frame).ConfigureAwait(false)) return;
            }
        }
        finally
        {
            context.Agent.ReadyChanged -= OnReadyChanged;
        }
    }

    /// <summary>Ends the session (replaced by a newer connection or device revoked).</summary>
    internal void Close()
    {
        try
        {
            _closed.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // Already ended.
        }
    }

    public void Dispose()
    {
        _closed.Dispose();
        _writeGate.Dispose();
    }

    [AsyncMethodBuilder(typeof(PoolingAsyncValueTaskMethodBuilder<>))]
    private async ValueTask<bool> HandleAsync(Frame frame)
    {
        switch (frame.Type)
        {
            case FrameType.Key:
                // Forwarded even while not ready: key-ups must reach the agent so nothing stays pressed.
                var sent = await context.Agent.SendAsync(frame.Raw, Stopwatch.GetTimestamp()).ConfigureAwait(false);
                var (code, value) = Frames.ReadKey(frame.Payload.Span);
                context.Recent.Add(code, value);
                // Media keys only (no typed text): shows whether the tablet's media key reached the agent.
                if (value == 1 && Agent.KeyInjector.Media(code) is not Agent.MediaKey.None) LogMediaKey(context.Logger, code, sent);
                return true;
            case FrameType.Sas:
                // Lock and SAS lead to a credential screen; the key strip must not carry what is typed there.
                context.Recent.Stop();
                context.SendSas();
                LogSas(context.Logger, device.Name);
                return true;
            case FrameType.Lock:
                context.Recent.Stop();
                await context.Agent.SendAsync(Frames.LockFrame).ConfigureAwait(false);
                LogLock(context.Logger, device.Name);
                return true;
            case FrameType.Ping:
                Interlocked.Increment(ref _pings);
                return await WriteAsync(Frames.Pong(Frames.ReadSeq(frame.Payload.Span))).ConfigureAwait(false);
            default:
                return false;
        }
    }

    private void OnReadyChanged(bool ready) => _ = WriteAsync(null).AsTask();

    /// <summary>Writes one frame; TLS allows one writer at a time. A write slower than 1 s ends the session.</summary>
    /// <param name="frame">The frame, or null for STATE with Ready read inside the gate so a late writer cannot send a stale value.</param>
    private async ValueTask<bool> WriteAsync(byte[]? frame)
    {
        try
        {
            await _writeGate.WaitAsync(_closed.Token).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is OperationCanceledException or ObjectDisposedException)
        {
            return false;
        }
        try
        {
            frame ??= Frames.State(context.Agent.Ready);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(_closed.Token);
            timeout.CancelAfter(SEND_TIMEOUT);
            await ssl.WriteAsync(frame, timeout.Token).ConfigureAwait(false);
            return true;
        }
        catch (Exception ex) when (ex is IOException or OperationCanceledException or ObjectDisposedException)
        {
            Close();
            return false;
        }
        finally
        {
            _writeGate.Release();
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Ctrl+Alt+Del (SAS) from {Device}")]
    private static partial void LogSas(ILogger logger, string device);

    [LoggerMessage(Level = LogLevel.Debug, Message = "Media key {Code} from the tablet, forwarded to the agent={Sent}")]
    private static partial void LogMediaKey(ILogger logger, ushort code, bool sent);

    [LoggerMessage(Level = LogLevel.Information, Message = "Lock from {Device}")]
    private static partial void LogLock(ILogger logger, string device);
}
