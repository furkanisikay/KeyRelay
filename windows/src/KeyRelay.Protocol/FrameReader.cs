using System.Buffers.Binary;

namespace KeyRelay.Protocol;

/// <summary>Reads length-prefixed frames into one reusable buffer, so reading allocates nothing per frame.</summary>
/// <param name="stream">TLS stream or named pipe.</param>
/// <param name="channel">Decides which frame types are accepted.</param>
public sealed class FrameReader(Stream stream, FrameChannel channel)
{
    private readonly byte[] _buffer = new byte[Frame.MAX_SIZE];

    /// <summary>Blocks until the next frame arrives.</summary>
    /// <returns>The frame, or null when the stream ended cleanly between frames.</returns>
    /// <exception cref="ProtocolException">The frame is malformed or the stream ended inside it.</exception>
    public Frame? Read()
    {
        var got = stream.Read(_buffer, 0, Frame.HEADER_SIZE);
        if (got == 0) return null;
        Fill(got, Frame.HEADER_SIZE);
        var length = CheckedLength();
        Fill(Frame.HEADER_SIZE, Frame.HEADER_SIZE + length);
        return Decode(length);
    }

    /// <summary>Waits for the next frame.</summary>
    /// <param name="cancellationToken">Cancels the wait (idle timeouts, shutdown).</param>
    /// <returns>The frame, or null when the stream ended cleanly between frames.</returns>
    /// <exception cref="ProtocolException">The frame is malformed or the stream ended inside it.</exception>
    public async ValueTask<Frame?> ReadAsync(CancellationToken cancellationToken)
    {
        var got = await stream.ReadAsync(_buffer.AsMemory(0, Frame.HEADER_SIZE), cancellationToken).ConfigureAwait(false);
        if (got == 0) return null;
        await FillAsync(got, Frame.HEADER_SIZE, cancellationToken).ConfigureAwait(false);
        var length = CheckedLength();
        await FillAsync(Frame.HEADER_SIZE, Frame.HEADER_SIZE + length, cancellationToken).ConfigureAwait(false);
        return Decode(length);
    }

    private void Fill(int from, int to)
    {
        try
        {
            stream.ReadExactly(_buffer, from, to - from);
        }
        catch (EndOfStreamException)
        {
            throw new ProtocolException(ProtocolError.Truncated);
        }
    }

    private async ValueTask FillAsync(int from, int to, CancellationToken cancellationToken)
    {
        try
        {
            await stream.ReadExactlyAsync(_buffer.AsMemory(from, to - from), cancellationToken).ConfigureAwait(false);
        }
        catch (EndOfStreamException)
        {
            throw new ProtocolException(ProtocolError.Truncated);
        }
    }

    private int CheckedLength()
    {
        int length = BinaryPrimitives.ReadUInt16BigEndian(_buffer);
        if (length == 0) throw new ProtocolException(ProtocolError.Empty);
        if (length > Frame.MAX_LENGTH) throw new ProtocolException(ProtocolError.TooLarge);
        return length;
    }

    private Frame Decode(int length)
    {
        var payload = _buffer.AsMemory(Frame.HEADER_SIZE + 1, length - 1);
        var type = FrameRules.Validate(channel, _buffer[Frame.HEADER_SIZE], payload.Span);
        return new Frame(type, payload, _buffer.AsMemory(0, Frame.HEADER_SIZE + length));
    }
}
