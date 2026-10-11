using System.ComponentModel;
using System.Diagnostics;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Session;

/// <summary>Keeps one agent running in the active console session; restarts it with back-off (at most 5 s).</summary>
internal sealed partial class AgentSupervisor(AgentLink link, SessionSignal sessions, ILogger<AgentSupervisor> logger) : BackgroundService
{
    private static readonly TimeSpan FIRST_RETRY = TimeSpan.FromMilliseconds(250);
    private static readonly TimeSpan MAX_RETRY = TimeSpan.FromSeconds(5);
    // A run this long counts as healthy and resets the back-off.
    private static readonly TimeSpan HEALTHY_RUN = TimeSpan.FromSeconds(30);
    private long _activeSession = -1;

    /// <summary>Gets the session the agent runs in, or null while none runs.</summary>
    internal uint? ActiveSession => Interlocked.Read(ref _activeSession) is var session and >= 0 ? (uint)session : null;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var failures = 0;
        while (!stoppingToken.IsCancellationRequested)
        {
            var session = Kernel32.WTSGetActiveConsoleSessionId();
            if (session == Kernel32.NO_ACTIVE_SESSION)
            {
                await sessions.WaitForChangeAsync(session, Kernel32.WTSGetActiveConsoleSessionId, stoppingToken).ConfigureAwait(false);
                continue;
            }
            var started = Stopwatch.GetTimestamp();
            await RunAgentAsync(session, stoppingToken).ConfigureAwait(false);
            failures = Stopwatch.GetElapsedTime(started) >= HEALTHY_RUN ? 0 : failures + 1;
            await Task.Delay(Backoff.Delay(failures, FIRST_RETRY, MAX_RETRY), stoppingToken).ConfigureAwait(false);
        }
    }

    /// <summary>Runs one agent until it exits, its pipe link ends, the console session changes or the service stops. Never throws.</summary>
    private async Task RunAgentAsync(uint session, CancellationToken stoppingToken)
    {
        try
        {
            using var agent = SessionLauncher.Launch(session, $"\"{Environment.ProcessPath}\" --agent {Environment.ProcessId}");
            Interlocked.Exchange(ref _activeSession, session);
            LogAgentStarted(agent.Id, session);
            using var run = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
            var served = link.RunAsync(agent.Id, run.Token);
            var moved = sessions.WaitForChangeAsync(session, Kernel32.WTSGetActiveConsoleSessionId, run.Token);
            var first = await Task.WhenAny(served, agent.Exited, moved).ConfigureAwait(false);
            await run.CancelAsync().ConfigureAwait(false);
            await StopAgentAsync(agent, served).ConfigureAwait(false);
            LogAgentStopped(agent.Id, agent.ExitCode, first == agent.Exited ? "exited" : first == moved ? "session changed" : "pipe link ended");
        }
        catch (Win32Exception ex)
        {
            LogAgentStartFailed(ex, session);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            LogSupervisionFailed(ex, session);
        }
        finally
        {
            Interlocked.Exchange(ref _activeSession, -1);
        }
    }

    private async Task StopAgentAsync(AgentProcess agent, Task served)
    {
        try
        {
            await agent.StopAsync().ConfigureAwait(false);
        }
        catch (TimeoutException)
        {
            LogAgentStuck(agent.Id);
        }
        await served.ConfigureAwait(false);
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent {Pid} started in session {Session}")]
    private partial void LogAgentStarted(int pid, uint session);

    [LoggerMessage(Level = LogLevel.Information, Message = "Agent {Pid} stopped, exit code {ExitCode}, reason: {Reason}")]
    private partial void LogAgentStopped(int pid, uint? exitCode, string reason);

    [LoggerMessage(Level = LogLevel.Error, Message = "Agent start failed in session {Session}")]
    private partial void LogAgentStartFailed(Exception ex, uint session);

    [LoggerMessage(Level = LogLevel.Error, Message = "Agent supervision failed in session {Session}")]
    private partial void LogSupervisionFailed(Exception ex, uint session);

    [LoggerMessage(Level = LogLevel.Error, Message = "Agent {Pid} did not exit after termination")]
    private partial void LogAgentStuck(int pid);
}
