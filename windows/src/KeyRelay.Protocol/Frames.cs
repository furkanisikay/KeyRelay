using System.Buffers.Binary;
using System.Text;

namespace KeyRelay.Protocol;

/// <summary>Builds and reads frame payloads. byte[] builders are for rare frames; Write* fill caller buffers on hot paths.</summary>
public static class Frames
{
    /// <summary>Protocol version carried by PAIR and HELLO.</summary>
    public const byte VERSION = 1;

    /// <summary>Size of a device id.</summary>
    public const int ID_SIZE = 16;

    /// <summary>Size of a device token.</summary>
    public const int TOKEN_SIZE = 32;

    /// <summary>Size of a pairing code.</summary>
    public const int CODE_SIZE = 16;

    /// <summary>Size of a whole KEY_DONE frame.</summary>
    public const int KEY_DONE_SIZE = Frame.HEADER_SIZE + 1 + 3 * sizeof(long);

    private const int KEY_SIZE = Frame.HEADER_SIZE + 1 + 3;

    /// <summary>Gets a LOCK frame.</summary>
    public static ReadOnlyMemory<byte> LockFrame { get; } = Empty(FrameType.Lock);

    /// <summary>Gets a RELEASE_ALL frame.</summary>
    public static ReadOnlyMemory<byte> ReleaseAllFrame { get; } = Empty(FrameType.ReleaseAll);

    public static byte[] Pair(ReadOnlySpan<byte> code, string deviceName) =>
        Build(FrameType.Pair, [VERSION, .. code, .. Encoding.UTF8.GetBytes(deviceName)]);

    public static byte[] Paired(ReadOnlySpan<byte> deviceId, ReadOnlySpan<byte> token) => Build(FrameType.Paired, [.. deviceId, .. token]);

    public static byte[] Hello(ReadOnlySpan<byte> deviceId, ReadOnlySpan<byte> token) => Build(FrameType.Hello, [VERSION, .. deviceId, .. token]);

    public static byte[] Welcome(string pcName) => Build(FrameType.Welcome, Encoding.UTF8.GetBytes(pcName));

    public static byte[] Reject(RejectReason reason) => Build(FrameType.Reject, [(byte)reason]);

    public static byte[] Key(ushort code, byte value)
    {
        var frame = new byte[KEY_SIZE];
        BinaryPrimitives.WriteUInt16BigEndian(frame, KEY_SIZE - Frame.HEADER_SIZE);
        frame[Frame.HEADER_SIZE] = (byte)FrameType.Key;
        BinaryPrimitives.WriteUInt16BigEndian(frame.AsSpan(Frame.HEADER_SIZE + 1), code);
        frame[KEY_SIZE - 1] = value;
        return frame;
    }

    public static byte[] State(bool ready) => Build(FrameType.State, [ready ? (byte)1 : (byte)0]);

    /// <summary>Builds a frame without payload (SAS, LOCK, RELEASE_ALL).</summary>
    public static byte[] Empty(FrameType type) => Build(type, []);

    public static byte[] Ping(uint sequence) => Build(FrameType.Ping, BigEndian(sequence));

    public static byte[] Pong(uint sequence) => Build(FrameType.Pong, BigEndian(sequence));

    public static byte[] Desktop(bool writable, string name) =>
        Build(FrameType.Desktop, [writable ? (byte)1 : (byte)0, .. Encoding.UTF8.GetBytes(name)]);

    /// <summary>Builds an AGENT_LOG frame: one diagnostic line of the agent for the service log; never a key value.</summary>
    public static byte[] AgentLog(bool warning, string text) =>
        Build(FrameType.AgentLog, [warning ? (byte)1 : (byte)0, .. Encoding.UTF8.GetBytes(text)]);

    public static byte[] KeyDone(KeyDoneTimes times)
    {
        var frame = new byte[KEY_DONE_SIZE];
        WriteKeyDone(frame, times);
        return frame;
    }

    /// <summary>Writes a KEY_DONE frame into a caller buffer of KEY_DONE_SIZE bytes.</summary>
    public static void WriteKeyDone(Span<byte> destination, KeyDoneTimes times)
    {
        BinaryPrimitives.WriteUInt16BigEndian(destination, KEY_DONE_SIZE - Frame.HEADER_SIZE);
        destination[Frame.HEADER_SIZE] = (byte)FrameType.KeyDone;
        var payload = destination[(Frame.HEADER_SIZE + 1)..];
        BinaryPrimitives.WriteInt64BigEndian(payload, times.Read);
        BinaryPrimitives.WriteInt64BigEndian(payload[sizeof(long)..], times.Attached);
        BinaryPrimitives.WriteInt64BigEndian(payload[(2 * sizeof(long))..], times.Done);
    }

    public static (ushort Code, byte Value) ReadKey(ReadOnlySpan<byte> payload) => (BinaryPrimitives.ReadUInt16BigEndian(payload), payload[2]);

    public static uint ReadSeq(ReadOnlySpan<byte> payload) => BinaryPrimitives.ReadUInt32BigEndian(payload);

    public static KeyDoneTimes ReadKeyDone(ReadOnlySpan<byte> payload) => new(
        BinaryPrimitives.ReadInt64BigEndian(payload),
        BinaryPrimitives.ReadInt64BigEndian(payload[sizeof(long)..]),
        BinaryPrimitives.ReadInt64BigEndian(payload[(2 * sizeof(long))..]));

    public static (bool Writable, string Name) ReadDesktop(ReadOnlySpan<byte> payload) => (payload[0] == 1, Encoding.UTF8.GetString(payload[1..]));

    public static (bool Warning, string Text) ReadAgentLog(ReadOnlySpan<byte> payload) => (payload[0] == 1, Encoding.UTF8.GetString(payload[1..]));

    private static byte[] BigEndian(uint value)
    {
        var bytes = new byte[sizeof(uint)];
        BinaryPrimitives.WriteUInt32BigEndian(bytes, value);
        return bytes;
    }

    private static byte[] Build(FrameType type, ReadOnlySpan<byte> payload)
    {
        if (payload.Length >= Frame.MAX_LENGTH) throw new ArgumentOutOfRangeException(nameof(payload));
        var frame = new byte[Frame.HEADER_SIZE + 1 + payload.Length];
        BinaryPrimitives.WriteUInt16BigEndian(frame, (ushort)(1 + payload.Length));
        frame[Frame.HEADER_SIZE] = (byte)type;
        payload.CopyTo(frame.AsSpan(Frame.HEADER_SIZE + 1));
        return frame;
    }
}

/// <summary>QueryPerformanceCounter stamps the agent takes for one KEY (one counter for all processes).</summary>
/// <param name="Read">The KEY frame was read off the pipe.</param>
/// <param name="Attached">The thread is on the input desktop, right before SendInput.</param>
/// <param name="Done">SendInput returned.</param>
public readonly record struct KeyDoneTimes(long Read, long Attached, long Done);
