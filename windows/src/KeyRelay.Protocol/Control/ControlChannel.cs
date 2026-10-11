using System.Text.Json;

namespace KeyRelay.Protocol.Control;

/// <summary>Newline-delimited JSON messages on the control pipe, at most 64 KB each.</summary>
public sealed class ControlChannel(Stream stream)
{
    /// <summary>Largest message including its newline; longer input closes the pipe.</summary>
    public const int MAX_MESSAGE_BYTES = 64 * 1024;
    private const byte NEWLINE = (byte)'\n';
    private static readonly JsonSerializerOptions JSON = new(JsonSerializerDefaults.Web);
    private static readonly byte[] NEWLINE_BYTES = [NEWLINE];
    private readonly byte[] _buffer = new byte[MAX_MESSAGE_BYTES];
    private int _start;
    private int _end;

    /// <returns>The next message, or null when the peer closed the pipe.</returns>
    /// <exception cref="InvalidDataException">A message is longer than 64 KB or empty.</exception>
    public async Task<T?> ReadAsync<T>(CancellationToken cancellationToken) where T : class
    {
        while (true)
        {
            var newline = Array.IndexOf(_buffer, NEWLINE, _start, _end - _start);
            if (newline >= 0)
            {
                var message = JsonSerializer.Deserialize<T>(_buffer.AsSpan(_start, newline - _start), JSON);
                _start = newline + 1;
                return message ?? throw new InvalidDataException("Empty control message.");
            }
            Compact();
            if (_end == _buffer.Length) throw new InvalidDataException("Control message too long.");
            var read = await stream.ReadAsync(_buffer.AsMemory(_end), cancellationToken).ConfigureAwait(false);
            if (read == 0) return null;
            _end += read;
        }
    }

    /// <exception cref="InvalidDataException">The serialized message is longer than 64 KB.</exception>
    public async Task WriteAsync<T>(T message, CancellationToken cancellationToken)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(message, JSON);
        if (bytes.Length >= MAX_MESSAGE_BYTES) throw new InvalidDataException("Control message too long.");
        await stream.WriteAsync(bytes, cancellationToken).ConfigureAwait(false);
        await stream.WriteAsync(NEWLINE_BYTES, cancellationToken).ConfigureAwait(false);
        await stream.FlushAsync(cancellationToken).ConfigureAwait(false);
    }

    private void Compact()
    {
        if (_start == 0) return;
        _buffer.AsSpan(_start, _end - _start).CopyTo(_buffer);
        _end -= _start;
        _start = 0;
    }
}
