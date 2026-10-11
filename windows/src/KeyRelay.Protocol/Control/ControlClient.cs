using System.IO.Pipes;
using System.Text.Json;

namespace KeyRelay.Protocol.Control;

/// <summary>Tray side of the control pipe: one request at a time, reconnects after any failure.</summary>
/// <param name="pipeName">Pipe name without the \\.\pipe\ prefix (PIPE_NAME in production).</param>
public sealed class ControlClient(string pipeName) : IAsyncDisposable
{
    public const string PIPE_NAME = "KeyRelay.Control";
    private const int CONNECT_TIMEOUT_MS = 2000;
    private static readonly TimeSpan REQUEST_TIMEOUT = TimeSpan.FromSeconds(3);
    private readonly SemaphoreSlim _gate = new(1, 1);
    private NamedPipeClientStream? _pipe;
    private ControlChannel? _channel;

    /// <summary>Sends a request and waits up to 3 s for the answer.</summary>
    /// <exception cref="IOException">The service is not running or the pipe broke.</exception>
    /// <exception cref="TimeoutException">The service did not answer in time.</exception>
    /// <exception cref="UnauthorizedAccessException">The caller is not an interactive or administrator user.</exception>
    public async Task<ControlResponse> SendAsync(ControlRequest request, CancellationToken cancellationToken = default)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            timeout.CancelAfter(REQUEST_TIMEOUT);
            var channel = await ConnectAsync(timeout.Token).ConfigureAwait(false);
            await channel.WriteAsync(request, timeout.Token).ConfigureAwait(false);
            return await channel.ReadAsync<ControlResponse>(timeout.Token).ConfigureAwait(false)
                ?? throw new IOException("Control pipe closed.");
        }
        catch (Exception ex) when (ex is IOException or OperationCanceledException or InvalidDataException or JsonException or UnauthorizedAccessException or TimeoutException)
        {
            await ResetAsync().ConfigureAwait(false);
            if (ex is OperationCanceledException && !cancellationToken.IsCancellationRequested)
                throw new TimeoutException("Control request timed out.", ex);
            throw;
        }
        finally
        {
            _gate.Release();
        }
    }

    public async ValueTask DisposeAsync()
    {
        await ResetAsync().ConfigureAwait(false);
        _gate.Dispose();
    }

    private async Task<ControlChannel> ConnectAsync(CancellationToken cancellationToken)
    {
        if (_channel is not null) return _channel;
        var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        try
        {
            await pipe.ConnectAsync(CONNECT_TIMEOUT_MS, cancellationToken).ConfigureAwait(false);
        }
        catch
        {
            await pipe.DisposeAsync().ConfigureAwait(false);
            throw;
        }
        _pipe = pipe;
        return _channel = new ControlChannel(pipe);
    }

    private async Task ResetAsync()
    {
        _channel = null;
        if (_pipe is not null) await _pipe.DisposeAsync().ConfigureAwait(false);
        _pipe = null;
    }
}
