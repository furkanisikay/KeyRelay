using KeyRelay.Service.Control;
using Microsoft.Extensions.Logging;

namespace KeyRelay.Tests.Control;

[TestClass]
public sealed class LogBufferTests
{
    [TestMethod]
    public void Snapshot_MoreThan500Lines_KeepsNewest500InOrder()
    {
        using var buffer = new LogBuffer();

        for (var i = 0; i < 600; i++) buffer.Add($"line {i}");
        var lines = buffer.Snapshot();

        Assert.HasCount(500, lines);
        Assert.AreEqual("line 100", lines[0]);
        Assert.AreEqual("line 599", lines[^1]);
    }

    [TestMethod]
    public void Logger_Information_IsBufferedWithShortCategory()
    {
        using var buffer = new LogBuffer();
        var logger = buffer.CreateLogger("KeyRelay.Service.Relay.RelayHub");

        logger.Log(LogLevel.Information, new EventId(1), "Connected: Tab", null, (state, _) => state);

        Assert.IsTrue(buffer.Snapshot().Single().EndsWith("INFO RelayHub: Connected: Tab", StringComparison.Ordinal));
    }

    [TestMethod]
    public void Logger_Debug_IsNotBuffered()
    {
        using var buffer = new LogBuffer();
        var logger = buffer.CreateLogger("KeyRelay.Service.Relay.RelayHub");

        logger.Log(LogLevel.Debug, new EventId(1), "noise", null, (state, _) => state);

        Assert.IsEmpty(buffer.Snapshot());
    }
}
