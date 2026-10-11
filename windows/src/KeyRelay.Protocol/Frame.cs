namespace KeyRelay.Protocol;

/// <summary>Frame types of the TLS link (tablet and service) and the pipe link (service and agent).</summary>
public enum FrameType
{
    None = 0,
    Pair = 0x01,
    Paired = 0x02,
    Hello = 0x03,
    Welcome = 0x04,
    Reject = 0x05,
    Key = 0x10,
    State = 0x11,
    Sas = 0x12,
    Lock = 0x13,
    Ping = 0x20,
    Pong = 0x21,
    ReleaseAll = 0x30,
    Desktop = 0x31,
    KeyDone = 0x32,
    AgentLog = 0x33,
}

/// <summary>Which link a reader decodes; each link accepts only its own frame types.</summary>
public enum FrameChannel
{
    Tls,
    Pipe,
}

/// <summary>Why a frame was refused; names match the error values of the shared test vectors.</summary>
public enum ProtocolError
{
    Empty,
    TooLarge,
    UnknownType,
    Truncated,
    BadPayload,
}

/// <summary>Reasons carried by REJECT.</summary>
public enum RejectReason
{
    None = 0,
    Version = 1,
    UnknownDevice = 2,
    InvalidCode = 3,
}

/// <summary>A decoded frame. Payload and Raw point into the reader's buffer and stay valid until the next read.</summary>
/// <param name="Type">Frame type.</param>
/// <param name="Payload">Bytes after the type byte.</param>
/// <param name="Raw">The whole frame as received, used to forward KEY frames unchanged.</param>
public readonly record struct Frame(FrameType Type, ReadOnlyMemory<byte> Payload, ReadOnlyMemory<byte> Raw)
{
    /// <summary>Largest value of the length field (type byte plus payload).</summary>
    public const int MAX_LENGTH = 512;

    /// <summary>Size of the big-endian length field.</summary>
    public const int HEADER_SIZE = 2;

    /// <summary>Largest frame on the wire.</summary>
    public const int MAX_SIZE = HEADER_SIZE + MAX_LENGTH;
}

/// <summary>A frame broke the protocol; the connection must be closed.</summary>
public sealed class ProtocolException : Exception
{
    public ProtocolException()
    {
    }

    public ProtocolException(string message) : base(message)
    {
    }

    public ProtocolException(string message, Exception innerException) : base(message, innerException)
    {
    }

    public ProtocolException(ProtocolError error) : base(error.ToString()) => Error = error;

    /// <summary>Gets what was wrong with the frame.</summary>
    public ProtocolError Error { get; }
}
