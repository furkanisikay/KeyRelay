using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class FrameReaderTests
{
    [TestMethod]
    public void Read_TwoFramesBackToBack_ReturnsBothThenNull()
    {
        var reader = new FrameReader(new MemoryStream(Convert.FromHexString("000410001e01" + "000113")), FrameChannel.Pipe);

        var first = reader.Read();
        var second = reader.Read();
        var end = reader.Read();

        Assert.AreEqual(FrameType.Key, first!.Value.Type);
        Assert.AreEqual(FrameType.Lock, second!.Value.Type);
        Assert.IsNull(end);
    }

    [TestMethod]
    public void Read_StreamEndsInsideHeader_IsTruncated()
    {
        var reader = new FrameReader(new MemoryStream([0x00]), FrameChannel.Tls);

        var error = Assert.ThrowsExactly<ProtocolException>(() => reader.Read()).Error;

        Assert.AreEqual(ProtocolError.Truncated, error);
    }

    [TestMethod]
    [DataRow("00023102", DisplayName = "DESKTOP value 2")]
    [DataRow("0003320102", DisplayName = "KEY_DONE short")]
    public void Read_PipeBadPayloads_AreBadPayload(string hex)
    {
        var error = Assert.ThrowsExactly<ProtocolException>(() => FrameVectorTests.Decode(hex, FrameChannel.Pipe)).Error;

        Assert.AreEqual(ProtocolError.BadPayload, error);
    }

    [TestMethod]
    public void Read_DesktopWithEmptyName_IsValid()
    {
        var frame = FrameVectorTests.Decode("00023100", FrameChannel.Pipe);

        Assert.AreEqual(FrameType.Desktop, frame.Type);
        Assert.AreEqual((false, ""), Frames.ReadDesktop(frame.Payload.Span));
    }

    [TestMethod]
    [DataRow("000130", FrameChannel.Tls, DisplayName = "RELEASE_ALL on TLS")]
    [DataRow("000112", FrameChannel.Pipe, DisplayName = "SAS on pipe")]
    public void Read_TypeOfOtherChannel_IsUnknownType(string hex, FrameChannel channel)
    {
        var error = Assert.ThrowsExactly<ProtocolException>(() => FrameVectorTests.Decode(hex, channel)).Error;

        Assert.AreEqual(ProtocolError.UnknownType, error);
    }

    [TestMethod]
    public async Task ReadAsync_KeyFrame_MatchesSyncReadAndKeepsRawBytes()
    {
        var bytes = Convert.FromHexString("000410007d00");
        var reader = new FrameReader(new MemoryStream(bytes), FrameChannel.Tls);

        var frame = await reader.ReadAsync(CancellationToken.None);

        Assert.AreEqual(FrameType.Key, frame!.Value.Type);
        CollectionAssert.AreEqual(bytes, frame.Value.Raw.ToArray());
    }

    [TestMethod]
    public void Welcome_NameLongerThanFrameLimit_Throws()
    {
        Assert.ThrowsExactly<ArgumentOutOfRangeException>(() => Frames.Welcome(new string('A', Frame.MAX_LENGTH)));
    }
}
