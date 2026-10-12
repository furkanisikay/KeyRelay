using System.IO.Pipes;
using KeyRelay.Protocol;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Stand-in for the agent process: the client end of the agent pipe, run inside the test process.</summary>
internal sealed class FakeAgent : IAsyncDisposable
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);
    private readonly NamedPipeClientStream _pipe;
    private readonly FrameReader _reader;

    private FakeAgent(NamedPipeClientStream pipe)
    {
        _pipe = pipe;
        _reader = new FrameReader(pipe, FrameChannel.Pipe);
    }

    internal static async Task<FakeAgent> ConnectAsync(string pipeName)
    {
        var pipe = new NamedPipeClientStream(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync((int)TIMEOUT.TotalMilliseconds);
        return new FakeAgent(pipe);
    }

    /// <summary>Reads the next frame the service sent.</summary>
    /// <returns>The whole frame as bytes.</returns>
    internal async Task<byte[]> ReadAsync()
    {
        using var timeout = new CancellationTokenSource(TIMEOUT);
        var frame = await _reader.ReadAsync(timeout.Token) ?? throw new EndOfStreamException("service closed the agent pipe");
        return frame.Raw.ToArray();
    }

    internal Task SendAsync(byte[] frame) => _pipe.WriteAsync(frame).AsTask();

    public ValueTask DisposeAsync() => _pipe.DisposeAsync();
}
