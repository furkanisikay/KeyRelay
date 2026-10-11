using KeyRelay.Protocol.Control;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class ControlMessagesTests
{
    [TestMethod]
    public async Task RecentKeys_ThroughChannel_KeepsNamesAndOrder()
    {
        using var stream = new MemoryStream();
        var channel = new ControlChannel(stream);

        await channel.WriteAsync(new ControlResponse { RecentKeys = ["Ctrl", "A", "⏎"] }, CancellationToken.None);
        stream.Position = 0;
        var read = await new ControlChannel(stream).ReadAsync<ControlResponse>(CancellationToken.None);

        CollectionAssert.AreEqual(new[] { "Ctrl", "A", "⏎" }, read!.RecentKeys!.ToArray());
    }

    [TestMethod]
    public async Task OtherAnswers_ThroughChannel_HaveNoRecentKeys()
    {
        using var stream = new MemoryStream();
        var channel = new ControlChannel(stream);

        await channel.WriteAsync(new ControlResponse { Status = new ControlStatus() }, CancellationToken.None);
        stream.Position = 0;
        var read = await new ControlChannel(stream).ReadAsync<ControlResponse>(CancellationToken.None);

        Assert.IsNull(read!.RecentKeys);
    }
}
