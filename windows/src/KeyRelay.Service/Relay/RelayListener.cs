using System.Net;
using System.Net.Sockets;
using KeyRelay.Service.Settings;

namespace KeyRelay.Service.Relay;

/// <summary>Accepts TCP connections on the configured port (IPv4 and IPv6) and hands them to the hub.</summary>
internal sealed partial class RelayListener(RelayHub hub, SettingsStore settings, ILogger<RelayListener> logger) : BackgroundService
{
    private static readonly TimeSpan BIND_RETRY = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan ACCEPT_RETRY = TimeSpan.FromMilliseconds(100);

    // Throwing out of ExecuteAsync stops the host cleanly and SCM recovery only fires on a crash, so bind and accept
    // errors are retried instead.
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var port = settings.Load().Port;
        using var listener = await BindAsync(port, stoppingToken).ConfigureAwait(false);
        LogListening(port);
        while (true)
        {
            try
            {
                _ = hub.HandleAsync(await listener.AcceptAsync(stoppingToken).ConfigureAwait(false), stoppingToken);
            }
            catch (SocketException ex)
            {
                LogAcceptFailed(ex.SocketErrorCode);
                await Task.Delay(ACCEPT_RETRY, stoppingToken).ConfigureAwait(false);
            }
        }
    }

    private async Task<Socket> BindAsync(int port, CancellationToken stoppingToken)
    {
        for (var attempt = 1; ; attempt++)
        {
            // A fresh socket per attempt: one that bound but failed to listen cannot bind again.
            var listener = new Socket(AddressFamily.InterNetworkV6, SocketType.Stream, ProtocolType.Tcp) { DualMode = true };
            try
            {
                listener.Bind(new IPEndPoint(IPAddress.IPv6Any, port));
                listener.Listen();
                return listener;
            }
            catch (SocketException ex)
            {
                listener.Dispose();
                // Port taken (for example by the prototype).
                if (attempt == 1) LogBindFailed(ex, port);
                else LogBindStillFailing(port, ex.SocketErrorCode);
                await Task.Delay(BIND_RETRY, stoppingToken).ConfigureAwait(false);
            }
        }
    }

    [LoggerMessage(Level = LogLevel.Information, Message = "Listening on TCP port {Port}")]
    private partial void LogListening(int port);

    [LoggerMessage(Level = LogLevel.Critical, Message = "Cannot listen on TCP port {Port}")]
    private partial void LogBindFailed(Exception ex, int port);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Still cannot listen on TCP port {Port} ({Error})")]
    private partial void LogBindStillFailing(int port, SocketError error);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Accept failed ({Error})")]
    private partial void LogAcceptFailed(SocketError error);
}
