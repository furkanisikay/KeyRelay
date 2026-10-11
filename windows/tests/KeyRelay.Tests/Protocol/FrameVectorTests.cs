using System.Text;
using System.Text.Json;
using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class FrameVectorTests
{
    private static readonly JsonElement VECTORS =
        JsonDocument.Parse(File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "frames.json"))).RootElement;

    [TestMethod]
    public void Read_ValidVectorsOnTheirChannels_DecodeToExpectedFields()
    {
        var checkedCount = 0;
        foreach (var vector in VECTORS.GetProperty("valid").EnumerateArray())
        {
            var name = vector.GetProperty("name").GetString()!;
            foreach (var channel in vector.GetProperty("channels").EnumerateArray())
            {
                var frame = Decode(vector.GetProperty("hex").GetString()!, ParseChannel(channel.GetString()!));

                Assert.AreEqual(ParseType(vector.GetProperty("type").GetString()!), frame.Type, name);
                AssertFields(frame, vector.GetProperty("fields"), name);
                checkedCount++;
            }
        }
        Assert.IsTrue(checkedCount > 20, "no vectors were checked");
    }

    [TestMethod]
    public void Builders_ValidVectors_ProduceSameBytes()
    {
        foreach (var vector in VECTORS.GetProperty("valid").EnumerateArray())
        {
            var name = vector.GetProperty("name").GetString()!;

            var built = Build(ParseType(vector.GetProperty("type").GetString()!), vector.GetProperty("fields"));

            Assert.AreEqual(vector.GetProperty("hex").GetString(), Convert.ToHexStringLower(built), name);
        }
    }

    [TestMethod]
    public void Read_InvalidVectors_FailWithExpectedErrorOnBothChannels()
    {
        foreach (var vector in VECTORS.GetProperty("invalid").EnumerateArray())
        {
            var name = vector.GetProperty("name").GetString()!;
            var expected = Enum.Parse<ProtocolError>(vector.GetProperty("error").GetString()!.Replace("_", "", StringComparison.Ordinal), ignoreCase: true);
            foreach (var channel in new[] { FrameChannel.Tls, FrameChannel.Pipe })
            {
                var hex = vector.GetProperty("hex").GetString()!;

                var error = Assert.ThrowsExactly<ProtocolException>(() => Decode(hex, channel), name).Error;

                Assert.AreEqual(expected, error, $"{name} on {channel}");
            }
        }
    }

    internal static Frame Decode(string hex, FrameChannel channel) =>
        new FrameReader(new MemoryStream(Convert.FromHexString(hex)), channel).Read()!.Value;

    private static FrameChannel ParseChannel(string channel) => channel == "tls" ? FrameChannel.Tls : FrameChannel.Pipe;

    private static FrameType ParseType(string type) =>
        Enum.Parse<FrameType>(type.Replace("_", "", StringComparison.Ordinal), ignoreCase: true);

    private static string Hex(ReadOnlySpan<byte> bytes) => Convert.ToHexStringLower(bytes);

    private static void AssertFields(Frame frame, JsonElement fields, string name)
    {
        var p = frame.Payload.Span;
        switch (frame.Type)
        {
            case FrameType.Key:
                var (code, value) = Frames.ReadKey(p);
                Assert.AreEqual(fields.GetProperty("code").GetInt32(), code, name);
                Assert.AreEqual(fields.GetProperty("value").GetInt32(), value, name);
                break;
            case FrameType.Hello:
                Assert.AreEqual(fields.GetProperty("version").GetInt32(), p[0], name);
                Assert.AreEqual(fields.GetProperty("deviceId").GetString(), Hex(p.Slice(1, Frames.ID_SIZE)), name);
                Assert.AreEqual(fields.GetProperty("token").GetString(), Hex(p.Slice(1 + Frames.ID_SIZE, Frames.TOKEN_SIZE)), name);
                break;
            case FrameType.Pair:
                Assert.AreEqual(fields.GetProperty("version").GetInt32(), p[0], name);
                Assert.AreEqual(fields.GetProperty("code").GetString(), Hex(p.Slice(1, Frames.CODE_SIZE)), name);
                Assert.AreEqual(fields.GetProperty("deviceName").GetString(), Encoding.UTF8.GetString(p[(1 + Frames.CODE_SIZE)..]), name);
                break;
            case FrameType.Paired:
                Assert.AreEqual(fields.GetProperty("deviceId").GetString(), Hex(p[..Frames.ID_SIZE]), name);
                Assert.AreEqual(fields.GetProperty("token").GetString(), Hex(p[Frames.ID_SIZE..]), name);
                break;
            case FrameType.Welcome:
                Assert.AreEqual(fields.GetProperty("pcName").GetString(), Encoding.UTF8.GetString(p), name);
                break;
            case FrameType.Reject:
                Assert.AreEqual(fields.GetProperty("reason").GetInt32(), p[0], name);
                break;
            case FrameType.State:
                Assert.AreEqual(fields.GetProperty("ready").GetBoolean(), p[0] == 1, name);
                break;
            case FrameType.Ping or FrameType.Pong:
                Assert.AreEqual(fields.GetProperty("seq").GetUInt32(), Frames.ReadSeq(p), name);
                break;
            case FrameType.Desktop:
                var (writable, desktop) = Frames.ReadDesktop(p);
                Assert.AreEqual(fields.GetProperty("writable").GetBoolean(), writable, name);
                Assert.AreEqual(fields.GetProperty("name").GetString(), desktop, name);
                break;
            case FrameType.KeyDone:
                Assert.AreEqual(AgentTimes(fields), Frames.ReadKeyDone(p), name);
                break;
            case FrameType.AgentLog:
                Assert.AreEqual((fields.GetProperty("warning").GetBoolean(), fields.GetProperty("text").GetString()), Frames.ReadAgentLog(p), name);
                break;
            default:
                Assert.IsTrue(p.IsEmpty, name);
                break;
        }
    }

    private static byte[] Build(FrameType type, JsonElement f) => type switch
    {
        FrameType.Key => Frames.Key((ushort)f.GetProperty("code").GetInt32(), (byte)f.GetProperty("value").GetInt32()),
        FrameType.Hello => Frames.Hello(Convert.FromHexString(f.GetProperty("deviceId").GetString()!), Convert.FromHexString(f.GetProperty("token").GetString()!)),
        FrameType.Pair => Frames.Pair(Convert.FromHexString(f.GetProperty("code").GetString()!), f.GetProperty("deviceName").GetString()!),
        FrameType.Paired => Frames.Paired(Convert.FromHexString(f.GetProperty("deviceId").GetString()!), Convert.FromHexString(f.GetProperty("token").GetString()!)),
        FrameType.Welcome => Frames.Welcome(f.GetProperty("pcName").GetString()!),
        FrameType.Reject => Frames.Reject((RejectReason)f.GetProperty("reason").GetInt32()),
        FrameType.State => Frames.State(f.GetProperty("ready").GetBoolean()),
        FrameType.Ping => Frames.Ping(f.GetProperty("seq").GetUInt32()),
        FrameType.Pong => Frames.Pong(f.GetProperty("seq").GetUInt32()),
        FrameType.Desktop => Frames.Desktop(f.GetProperty("writable").GetBoolean(), f.GetProperty("name").GetString()!),
        FrameType.KeyDone => Frames.KeyDone(AgentTimes(f)),
        FrameType.AgentLog => Frames.AgentLog(f.GetProperty("warning").GetBoolean(), f.GetProperty("text").GetString()!),
        _ => Frames.Empty(type),
    };

    private static KeyDoneTimes AgentTimes(JsonElement f) =>
        new(f.GetProperty("read").GetInt64(), f.GetProperty("attached").GetInt64(), f.GetProperty("done").GetInt64());
}
