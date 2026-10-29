namespace KeyRelay.Protocol;

/// <summary>Which types each link accepts and what payload each type must carry (docs/protocol.md).</summary>
internal static class FrameRules
{
    /// <exception cref="ProtocolException">UnknownType or BadPayload.</exception>
    internal static FrameType Validate(FrameChannel channel, byte type, ReadOnlySpan<byte> payload)
    {
        var frameType = (FrameType)type;
        if (!IsAllowed(channel, frameType)) throw new ProtocolException(ProtocolError.UnknownType);
        if (!IsPayloadValid(frameType, payload)) throw new ProtocolException(ProtocolError.BadPayload);
        return frameType;
    }

    private static bool IsAllowed(FrameChannel channel, FrameType type) => channel == FrameChannel.Tls
        ? type is FrameType.Pair or FrameType.Paired or FrameType.Hello or FrameType.Welcome or FrameType.Reject
            or FrameType.Key or FrameType.State or FrameType.Sas or FrameType.Lock or FrameType.Ping or FrameType.Pong
        : type is FrameType.Key or FrameType.Lock or FrameType.ReleaseAll or FrameType.Desktop or FrameType.KeyDone or FrameType.AgentLog;

    private static bool IsPayloadValid(FrameType type, ReadOnlySpan<byte> payload) => type switch
    {
        FrameType.Pair => payload.Length >= 1 + Frames.CODE_SIZE,
        FrameType.Paired => payload.Length == Frames.ID_SIZE + Frames.TOKEN_SIZE,
        FrameType.Hello => payload.Length == 1 + Frames.ID_SIZE + Frames.TOKEN_SIZE,
        FrameType.Welcome => true,
        FrameType.Reject => payload.Length == 1 && payload[0] is >= 1 and <= 4,
        FrameType.Key => payload.Length == 3 && payload[2] <= 2,
        FrameType.State => payload.Length == 1,
        FrameType.Sas or FrameType.Lock or FrameType.ReleaseAll => payload.IsEmpty,
        FrameType.Ping or FrameType.Pong => payload.Length == sizeof(uint),
        FrameType.Desktop or FrameType.AgentLog => payload.Length >= 1 && payload[0] <= 1,
        FrameType.KeyDone => payload.Length == 3 * sizeof(long),
        _ => false,
    };
}
