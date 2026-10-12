using System.Diagnostics;
using KeyRelay.Protocol;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class ForwardingTests
{
    [TestMethod]
    public async Task Key_FromTablet_ArrivesAtAgentUnchanged()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());
        var key = Frames.Key(30, 1);

        await tablet.SendAsync(key);

        CollectionAssert.AreEqual(key, await harness.AgentPipe!.ReadAsync());
    }

    [TestMethod]
    public async Task Key_HundredInARow_ArriveInOrder()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());
        var agent = harness.AgentPipe!;
        // Reading starts first: the pipe has no buffer and the service gives up on a write after 1 s.
        var received = Task.Run(async () =>
        {
            var frames = new List<byte[]>();
            for (var i = 0; i < 100; i++) frames.Add(await agent.ReadAsync());
            return frames;
        });

        for (var i = 0; i < 100; i++) await tablet.SendAsync(Frames.Key((ushort)(1 + i % 80), (byte)(i % 2)));

        var got = await received;
        for (var i = 0; i < 100; i++)
            CollectionAssert.AreEqual(Frames.Key((ushort)(1 + i % 80), (byte)(i % 2)), got[i], $"key {i}");
    }

    [TestMethod]
    public async Task Replace_OldKeyStillWriting_ReleaseAllReachesAgentBeforeNewKey()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var first = await harness.HelloAsync(device);
        // The agent does not read yet, so the first session's KEY write waits on the unbuffered pipe (up to 1 s).
        await first.SendAsync(Frames.Key(30, 1));
        await Task.Delay(100);
        await using var second = await harness.HelloAsync(device);
        await second.SendAsync(Frames.Key(31, 1));
        await Task.Delay(200);

        CollectionAssert.AreEqual(Frames.Key(30, 1), await harness.AgentPipe!.ReadAsync(), "first session key");
        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await harness.AgentPipe.ReadAsync(), "release of the first session");
        CollectionAssert.AreEqual(Frames.Key(31, 1), await harness.AgentPipe.ReadAsync(), "second session key");
    }

    [TestMethod]
    public async Task Key_AgentAnswersKeyDone_RecordsLatency()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Key(30, 1));
        await harness.AgentPipe!.ReadAsync();
        var now = Stopwatch.GetTimestamp();
        await harness.AgentPipe.SendAsync(Frames.KeyDone(new KeyDoneTimes(now, now, now)));

        await Eventually.TrueAsync(() => harness.Latency.Snapshot().P99Micros > 0, "latency was not recorded");
    }

    [TestMethod]
    public async Task Hello_AgentReady_FirstStateIsReady()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        var state = await tablet.ExpectAsync(FrameType.State);

        Assert.AreEqual((byte)1, state.Payload[0]);
    }

    [TestMethod]
    public async Task Desktop_NotWritableThenWritable_SendsStateZeroThenOne()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());
        await tablet.ExpectAsync(FrameType.State);

        await harness.AgentPipe!.SendAsync(Frames.Desktop(false, "Winlogon"));
        var notReady = await tablet.ExpectAsync(FrameType.State);
        await harness.AgentPipe.SendAsync(Frames.Desktop(true, "Default"));
        var ready = await tablet.ExpectAsync(FrameType.State);

        Assert.AreEqual((byte)0, notReady.Payload[0]);
        Assert.AreEqual((byte)1, ready.Payload[0]);
    }

    [TestMethod]
    public async Task Hello_NoAgent_StateNotReadyAndKeysDoNotBreakSession()
    {
        await using var harness = await RelayHarness.StartAsync(connectAgent: false);
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        var state = await tablet.ExpectAsync(FrameType.State);
        await tablet.SendAsync(Frames.Key(30, 1));
        await tablet.SendAsync(Frames.Ping(3));
        var pong = await tablet.ExpectAsync(FrameType.Pong);

        Assert.AreEqual((byte)0, state.Payload[0]);
        Assert.AreEqual(3u, Frames.ReadSeq(pong.Payload));
    }

    [TestMethod]
    public async Task Sas_FromTablet_CallsSendSas()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Empty(FrameType.Sas));

        await Eventually.TrueAsync(() => harness.SasCalls == 1, "SendSAS was not called");
    }

    [TestMethod]
    public async Task Lock_FromTablet_IsForwardedToAgent()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Empty(FrameType.Lock));

        CollectionAssert.AreEqual(Frames.LockFrame.ToArray(), await harness.AgentPipe!.ReadAsync());
    }

    [TestMethod]
    public async Task Disconnect_ActiveTablet_ReleasesAllKeysAtAgent()
    {
        await using var harness = await RelayHarness.StartAsync();
        var tablet = await harness.HelloAsync(await harness.PairAsync());
        await tablet.SendAsync(Frames.Key(56, 1));
        await harness.AgentPipe!.ReadAsync();

        await tablet.DisposeAsync();

        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await harness.AgentPipe.ReadAsync());
    }

    [TestMethod]
    public async Task Session_HelloAgainAfterWelcome_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var tablet = await harness.HelloAsync(device);

        await tablet.SendAsync(Frames.Hello(device.Id, device.Token));

        Assert.IsTrue(await tablet.IsClosedAsync());
    }
}
