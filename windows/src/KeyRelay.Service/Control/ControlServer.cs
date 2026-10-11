using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text.Json;
using KeyRelay.Protocol.Control;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Control;

/// <summary>Where the control pipe lives and who may use it.</summary>
internal sealed record ControlOptions(string PipeName, PipeSecurity Security)
{
    /// <summary>SYSTEM and Administrators get full control; the console user (INTERACTIVE) may read and write, as the tray runs unelevated.</summary>
    internal static ControlOptions Production()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null), PipeAccessRights.FullControl, AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(new SecurityIdentifier(WellKnownSidType.InteractiveSid, null), PipeAccessRights.ReadWrite, AccessControlType.Allow));
        return new ControlOptions(ControlClient.PIPE_NAME, security);
    }
}

/// <summary>Serves the tray app over the control pipe, a few clients at a time.</summary>
internal sealed partial class ControlServer(ControlOptions options, ControlHandler handler, ILogger<ControlServer> logger) : BackgroundService
{
    private const int MAX_INSTANCES = 4;
    private static readonly TimeSpan IDLE_TIMEOUT = TimeSpan.FromSeconds(60);
    private static readonly TimeSpan BUSY_RETRY = TimeSpan.FromSeconds(1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var first = true;
        var nameTaken = false;
        while (!stoppingToken.IsCancellationRequested)
        {
            NamedPipeServerStream pipe;
            try
            {
                pipe = NamedPipeServerStreamAcl.Create(options.PipeName, PipeDirection.InOut, MAX_INSTANCES, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | (first ? PipeOptions.FirstPipeInstance : PipeOptions.None), 0, 0, options.Security);
                first = false;
                nameTaken = false;
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                // All instances busy, or another process squats the name (access denied); never stop the service.
                if (ex is UnauthorizedAccessException && !nameTaken) LogPipeNameTaken(ex);
                else LogPipeUnavailable(ex);
                nameTaken |= ex is UnauthorizedAccessException;
                await Task.Delay(BUSY_RETRY, stoppingToken).ConfigureAwait(false);
                continue;
            }
            try
            {
                await pipe.WaitForConnectionAsync(stoppingToken).ConfigureAwait(false);
            }
            catch (Exception ex) when (!stoppingToken.IsCancellationRequested)
            {
                // A client that connects and closes at once fails the wait (ERROR_NO_DATA); never stop the service.
                await pipe.DisposeAsync().ConfigureAwait(false);
                LogClientDropped(ex);
                continue;
            }
            catch
            {
                await pipe.DisposeAsync().ConfigureAwait(false);
                throw;
            }
            _ = ServeAsync(pipe, stoppingToken);
        }
    }

    private async Task ServeAsync(NamedPipeServerStream pipe, CancellationToken stoppingToken)
    {
        await using (pipe.ConfigureAwait(false))
        {
            var channel = new ControlChannel(pipe);
            // The key strip is served only to a client in the session keys are typed into (RecentKeys).
            uint? session = Kernel32.GetNamedPipeClientSessionId(pipe.SafePipeHandle, out var id) ? id : null;
            try
            {
                while (true)
                {
                    using var idle = CancellationTokenSource.CreateLinkedTokenSource(stoppingToken);
                    idle.CancelAfter(IDLE_TIMEOUT);
                    var request = await channel.ReadAsync<ControlRequest>(idle.Token).ConfigureAwait(false);
                    if (request is null) return;
                    await channel.WriteAsync(Answer(request, session), idle.Token).ConfigureAwait(false);
                }
            }
            catch (Exception ex) when (ex is IOException or OperationCanceledException or InvalidDataException or JsonException)
            {
                LogClientDropped(ex);
            }
            catch (Exception ex)
            {
                LogRequestFailed(ex);
            }
        }
    }

    // A failing handler (e.g. an unreadable data file) is a service problem, not a bad client: logged as an error and
    // answered, so the tray can tell it from "service unreachable" and the reason shows on the Logs page.
    private ControlResponse Answer(ControlRequest request, uint? session)
    {
        try
        {
            return handler.Handle(request, session);
        }
        catch (Exception ex)
        {
            LogRequestFailed(ex);
            return ControlResponse.Fail(ControlErrors.INTERNAL_ERROR);
        }
    }

    [LoggerMessage(Level = LogLevel.Debug, Message = "Control pipe instance unavailable")]
    private partial void LogPipeUnavailable(Exception ex);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Control pipe name is held by another process")]
    private partial void LogPipeNameTaken(Exception ex);

    [LoggerMessage(Level = LogLevel.Debug, Message = "Control client dropped")]
    private partial void LogClientDropped(Exception ex);

    [LoggerMessage(Level = LogLevel.Error, Message = "Control request failed")]
    private partial void LogRequestFailed(Exception ex);
}
