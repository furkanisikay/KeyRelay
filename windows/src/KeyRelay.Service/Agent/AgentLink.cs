using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.CompilerServices;
using System.Security.AccessControl;
using System.Security.Principal;
using KeyRelay.Protocol;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>Where the agent pipe lives and who may open it.</summary>
/// <param name="PipeName">Pipe name without the \\.\pipe\ prefix.</param>
/// <param name="Security">Pipe ACL.</param>
/// <param name="BufferSize">Pipe buffer size in bytes; 0 makes every write wait until the agent reads it, so a stuck agent hits the write timeout.</param>
internal sealed record AgentLinkOptions(string PipeName, PipeSecurity Security, int BufferSize = 0)
{
    /// <summary>Only SYSTEM may open the production agent pipe.</summary>
    internal static AgentLinkOptions Production()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        return new AgentLinkOptions(AgentHost.PIPE_NAME, security);
    }
}

/// <summary>Service end of the agent pipe. Ready (STATE bit0) is true while the agent is connected and its desktop accepts input.</summary>
internal sealed partial class AgentLink(AgentLinkOptions options, LatencyStats latency, ILogger<AgentLink> logger) : IDisposable
{
    private static readonly TimeSpan CONNECT_TIMEOUT = TimeSpan.FromSeconds(10);
    // agentWriteTimeout (spec: internal constant).
    private static readonly TimeSpan WRITE_TIMEOUT = TimeSpan.FromSeconds(1);
    // KEY value as in Linux evdev: 0 release, 1 press, 2 auto-repeat.
    private const byte KEY_PRESS = 1;

    private readonly SemaphoreSlim _writeGate = new(1, 1);
    private readonly Lock _stateGate = new();
    private readonly DirectPipe _direct = new();
    private NamedPipeServerStream? _pipe;
    private CancellationTokenSource? _run;
    private bool _connected;
    private bool _writable = true;
    private string _desktop = "";

    /// <summary>Raised with the new Ready value whenever it changes; runs on the pipe reader.</summary>
    internal event Action<bool>? ReadyChanged;

    /// <summary>Raised with the new name when the agent reports a different input desktop; runs on the pipe reader.</summary>
    internal event Action<string>? DesktopChanged;

    internal bool Connected { get { lock (_stateGate) return _connected; } }

    internal bool Writable { get { lock (_stateGate) return _writable; } }

    internal bool Ready { get { lock (_stateGate) return _connected && _writable; } }

    internal string DesktopName { get { lock (_stateGate) return _desktop; } }

    /// <summary>Waits for the agent with the given process id and serves it until the pipe breaks, a write times out or the token is cancelled.</summary>
    /// <param name="agentPid">Process id of the agent the supervisor just launched.</param>
    /// <param name="cancellationToken">Stops serving.</param>
    internal async Task RunAsync(int agentPid, CancellationToken cancellationToken)
    {
        using var run = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        NamedPipeServerStream pipe;
        try
        {
            pipe = NamedPipeServerStreamAcl.Create(options.PipeName, PipeDirection.InOut, 1, PipeTransmissionMode.Byte,
                PipeOptions.Asynchronous | PipeOptions.FirstPipeInstance, options.BufferSize, options.BufferSize, options.Security);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            LogPipeUnavailable(ex);
            return;
        }
        await using (pipe.ConfigureAwait(false))
        {
            try
            {
                using (var connect = CancellationTokenSource.CreateLinkedTokenSource(run.Token))
                {
                    connect.CancelAfter(CONNECT_TIMEOUT);
                    await pipe.WaitForConnectionAsync(connect.Token).ConfigureAwait(false);
                }
                if (!Kernel32.GetNamedPipeClientProcessId(pipe.SafePipeHandle, out var clientPid) || clientPid != (uint)agentPid)
                {
                    LogUntrustedClient(clientPid, agentPid);
                    return;
                }
                Attach(pipe, run);
                await ReadLoopAsync(pipe, run.Token).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException)
            {
                LogLinkEnded();
            }
            catch (ProtocolException ex)
            {
                LogAgentProtocolError(ex);
            }
            finally
            {
                Detach();
            }
        }
    }

    /// <summary>Writes one frame to the agent within agentWriteTimeout; a timeout ends the link so the supervisor restarts the agent.</summary>
    /// <param name="frame">A complete pipe frame.</param>
    /// <param name="keyReadTimestamp">Stopwatch timestamp when a KEY frame was read off TLS; 0 for other frames.</param>
    /// <returns>false when no agent is connected or the write failed.</returns>
    [AsyncMethodBuilder(typeof(PoolingAsyncValueTaskMethodBuilder<>))]
    internal async ValueTask<bool> SendAsync(ReadOnlyMemory<byte> frame, long keyReadTimestamp = 0)
    {
        NamedPipeServerStream? pipe;
        CancellationTokenSource? run;
        lock (_stateGate)
        {
            pipe = _pipe;
            run = _run;
        }
        if (pipe is null) return false;
        await _writeGate.WaitAsync().ConfigureAwait(false);
        try
        {
            // Recorded before the write: the agent may answer before the write returns.
            if (keyReadTimestamp != 0) latency.Sent(keyReadTimestamp, Frames.ReadKey(frame.Span[(Frame.HEADER_SIZE + 1)..]).Value == KEY_PRESS);
            // Waited for on this thread (DirectPipe): the agent normally takes the frame at once, a stuck one costs at
            // most agentWriteTimeout of this thread, as the awaited write did.
            if (_direct.Write(pipe.SafePipeHandle, frame.Span, WRITE_TIMEOUT))
            {
                if (keyReadTimestamp != 0) latency.Written(Stopwatch.GetTimestamp());
                return true;
            }
            LogWriteTimeout();
            latency.ResetPending();
            Cancel(run);
            return false;
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException or InvalidOperationException)
        {
            // The link is ending; a read time left queued would pair with the next agent's first KEY_DONE.
            latency.ResetPending();
            return false;
        }
        finally
        {
            _writeGate.Release();
        }
    }

    public void Dispose()
    {
        _writeGate.Dispose();
        _direct.Dispose();
    }

    private async Task ReadLoopAsync(NamedPipeServerStream pipe, CancellationToken cancellationToken)
    {
        var reader = new FrameReader(pipe, FrameChannel.Pipe);
        while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false) is { } frame)
        {
            if (frame.Type == FrameType.KeyDone) latency.Done(Frames.ReadKeyDone(frame.Payload.Span));
            else if (frame.Type == FrameType.Desktop) OnDesktop(Frames.ReadDesktop(frame.Payload.Span));
            else if (frame.Type == FrameType.AgentLog) OnAgentLog(Frames.ReadAgentLog(frame.Payload.Span));
        }
    }

    private void OnAgentLog((bool Warning, string Text) line)
    {
        if (line.Warning) LogAgentWarning(line.Text);
        else LogAgentDebug(line.Text);
    }

    private void Attach(NamedPipeServerStream pipe, CancellationTokenSource run)
    {
        latency.ResetPending();
        lock (_stateGate)
        {
            _pipe = pipe;
            _run = run;
            _connected = true;
            _writable = true;
        }
        LogAgentConnected();
        ReadyChanged?.Invoke(true);
    }

    private void Detach()
    {
        bool wasReady;
        lock (_stateGate)
        {
            wasReady = _connected && _writable;
            _pipe = null;
            _run = null;
            _connected = false;
        }
        if (wasReady) ReadyChanged?.Invoke(false);
    }

    private void OnDesktop((bool Writable, string Name) desktop)
    {
        bool before;
        bool after;
        bool moved;
        lock (_stateGate)
        {
            before = _connected && _writable;
            moved = !string.Equals(_desktop, desktop.Name, StringComparison.Ordinal);
            _writable = desktop.Writable;
            _desktop = desktop.Name;
            after = _connected && _writable;
        }
        LogDesktop(desktop.Name, desktop.Writable);
        if (before != after) ReadyChanged?.Invoke(after);
        if (moved) DesktopChanged?.Invoke(desktop.Name);
    }

    private static void Cancel(CancellationTokenSource? run)
    {
        try
        {
            run?.Cancel();
        }
        catch (ObjectDisposedException)
        {
            // The run already ended.
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent connected")]
    private partial void LogAgentConnected();

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent pipe client {ClientPid} is not the launched agent {AgentPid}")]
    private partial void LogUntrustedClient(uint clientPid, int agentPid);

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent link ended")]
    private partial void LogLinkEnded();

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent sent a malformed frame; closing the link")]
    private partial void LogAgentProtocolError(ProtocolException error);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent pipe could not be created")]
    private partial void LogPipeUnavailable(Exception error);

    [LoggerMessage(Level = LogLevel.Information, Message = "Input desktop {Desktop}, writable={Writable}")]
    private partial void LogDesktop(string desktop, bool writable);

    // Routine agent lines (a media key worked) stay out of the Logs page; only warnings reach it.
    [LoggerMessage(Level = LogLevel.Debug, Message = "Agent: {Text}")]
    private partial void LogAgentDebug(string text);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent: {Text}")]
    private partial void LogAgentWarning(string text);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Agent did not take a frame within 1 s; restarting it")]
    private partial void LogWriteTimeout();
}
