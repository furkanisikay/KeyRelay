using System.Security.Authentication;
using System.Text;
using KeyRelay.Protocol;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class HandshakeTests
{
    [TestMethod]
    public async Task Pair_ValidCode_PairsAndSameConnectionHelloIsWelcomed()
    {
        await using var harness = await RelayHarness.StartAsync();
        var (code, _) = harness.Pairing.Start();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Pair(code, "Tab"));
        var paired = await tablet.ExpectAsync(FrameType.Paired);
        await tablet.SendAsync(Frames.Hello(paired.Payload.AsSpan(0, Frames.ID_SIZE), paired.Payload.AsSpan(Frames.ID_SIZE)));
        var welcome = await tablet.ExpectAsync(FrameType.Welcome);

        Assert.AreEqual(Environment.MachineName, Encoding.UTF8.GetString(welcome.Payload));
        Assert.AreEqual("Tab", harness.Devices.List().Single().Name);
    }

    [TestMethod]
    public async Task Pair_NameWithEmojiAtLimit_IsCutBeforeTheSurrogatePair()
    {
        await using var harness = await RelayHarness.StartAsync();

        await harness.PairAsync(new string('a', 63) + "\U0001F600");

        Assert.AreEqual(new string('a', 63), harness.Devices.List().Single().Name);
    }

    [TestMethod]
    public async Task Pair_CodeAtFiveMinutes_RejectsInvalidCodeAndCloses()
    {
        await using var harness = await RelayHarness.StartAsync();
        var (code, _) = harness.Pairing.Start();
        harness.Time.Advance(TimeSpan.FromMinutes(5));
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Pair(code, "Tab"));
        var reject = await tablet.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.InvalidCode, reject.Payload[0]);
        Assert.IsTrue(await tablet.IsClosedAsync());
        Assert.IsEmpty(harness.Devices.List());
    }

    [TestMethod]
    public async Task Pair_CodeUsedTwice_SecondIsRejected()
    {
        await using var harness = await RelayHarness.StartAsync();
        var (code, _) = harness.Pairing.Start();
        await using (var first = await harness.ConnectTabletAsync())
        {
            await first.SendAsync(Frames.Pair(code, "Tab"));
            await first.ExpectAsync(FrameType.Paired);
        }
        await using var second = await harness.ConnectTabletAsync();

        await second.SendAsync(Frames.Pair(code, "Other"));
        var reject = await second.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.InvalidCode, reject.Payload[0]);
        Assert.HasCount(1, harness.Devices.List());
    }

    [TestMethod]
    public async Task Hello_RevokedDevice_RejectsUnknownDevice()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        harness.Devices.Revoke(Convert.ToHexStringLower(device.Id));

        var answer = await harness.HelloResultAsync(device.Id, device.Token);

        Assert.AreEqual(FrameType.Reject, answer);
    }

    [TestMethod]
    public async Task Hello_WrongToken_RejectsAndSlowsDownTheNextAttempt()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        var wrong = (byte[])device.Token.Clone();
        wrong[0] ^= 0xFF;

        var first = await harness.HelloResultAsync(device.Id, wrong);
        // An attempt while blocked counts as a failure too: 2 failures = 2 s.
        var blocked = await harness.HelloResultAsync(device.Id, device.Token);
        harness.Time.Advance(TimeSpan.FromSeconds(2));
        var afterWait = await harness.HelloResultAsync(device.Id, device.Token);

        Assert.AreEqual(FrameType.Reject, first);
        Assert.AreEqual(FrameType.Reject, blocked);
        Assert.AreEqual(FrameType.Welcome, afterWait);
    }

    [TestMethod]
    public async Task Hello_NewerProtocolVersion_RejectsVersion()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        var hello = Frames.Hello(device.Id, device.Token);
        hello[Frame.HEADER_SIZE + 1] = 2;
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(hello);
        var reject = await tablet.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.Version, reject.Payload[0]);
    }

    [TestMethod]
    public async Task Connect_NoHelloWithinTimeout_IsClosed()
    {
        // 2 s, not less: the budget also covers the TLS handshake, which can be slow on a loaded test machine.
        await using var harness = await RelayHarness.StartAsync(helloTimeoutSeconds: 2);
        await using var tablet = await harness.ConnectTabletAsync();

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Connect_FirstFrameIsPing_IsClosedWithoutWelcome()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Ping(1));

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Connect_LengthOver512_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync([0x02, 0x01]);

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Hello_SecondVerifiedClient_ReplacesFirstAndReleasesKeys()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var first = await harness.HelloAsync(device);
        // Read the pipe first: with a zero pipe buffer RELEASE_ALL waits for the reader, and TLS closes after it.
        var release = harness.AgentPipe!.ReadAsync();

        await using var second = await harness.HelloAsync(device);

        Assert.IsTrue(await first.IsClosedAsync());
        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await release);
        await second.SendAsync(Frames.Ping(7));
        await second.ExpectAsync(FrameType.Pong);
    }

    [TestMethod]
    public async Task Hello_UnverifiedSecondClient_KeepsFirstSession()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var first = await harness.HelloAsync(device);
        var wrong = (byte[])device.Token.Clone();
        wrong[0] ^= 0xFF;

        var answer = await harness.HelloResultAsync(device.Id, wrong);
        await first.SendAsync(Frames.Ping(1));
        var pong = await first.ExpectAsync(FrameType.Pong);

        Assert.AreEqual(FrameType.Reject, answer);
        Assert.AreEqual(1u, Frames.ReadSeq(pong.Payload));
    }

    [TestMethod]
    public async Task CloseDevice_ActiveDevice_DropsSessionAtOnce()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = await harness.PairAsync();
        await using var tablet = await harness.HelloAsync(device);
        await Eventually.TrueAsync(() => harness.Hub.ActiveDeviceId is not null, "session did not become active");

        var release = harness.AgentPipe!.ReadAsync();

        harness.Hub.CloseDevice(Convert.ToHexStringLower(device.Id));

        Assert.IsTrue(await tablet.IsClosedAsync());
        CollectionAssert.AreEqual(Frames.ReleaseAllFrame.ToArray(), await release);
    }

    [TestMethod]
    public async Task Session_NoFramesForIdleTimeout_IsClosed()
    {
        await using var harness = await RelayHarness.StartAsync(idleTimeoutSeconds: 1);
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        Assert.IsTrue(await tablet.IsClosedAsync());
    }

    [TestMethod]
    public async Task Session_PingsKeepComingPastIdleTimeout_StaysOpen()
    {
        await using var harness = await RelayHarness.StartAsync(idleTimeoutSeconds: 1);
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        for (var i = 0u; i < 5; i++)
        {
            await Task.Delay(300);
            await tablet.SendAsync(Frames.Ping(i));
            var pong = await tablet.ExpectAsync(FrameType.Pong);
            Assert.AreEqual(i, Frames.ReadSeq(pong.Payload));
        }
    }

    [TestMethod]
    public async Task Session_Ping_AnswersPongWithSameSequence()
    {
        await using var harness = await RelayHarness.StartAsync();
        await using var tablet = await harness.HelloAsync(await harness.PairAsync());

        await tablet.SendAsync(Frames.Ping(16909060));
        var pong = await tablet.ExpectAsync(FrameType.Pong);

        Assert.AreEqual(16909060u, Frames.ReadSeq(pong.Payload));
    }

    [TestMethod]
    public async Task Connect_Tls_NegotiatesTls13()
    {
        await using var harness = await RelayHarness.StartAsync();

        await using var tablet = await harness.ConnectTabletAsync();

        Assert.AreEqual(SslProtocols.Tls13, tablet.Protocol);
    }

    [TestMethod]
    public async Task Connect_Tls12Client_IsRejected()
    {
        await using var harness = await RelayHarness.StartAsync();

        await Assert.ThrowsAsync<AuthenticationException>(() => TabletClient.ConnectAsync(harness.Port, harness.Fingerprint, SslProtocols.Tls12));
    }
}
