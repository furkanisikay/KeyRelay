using System.Net;
using System.Net.Sockets;
using System.Security.Authentication;
using KeyRelay.Protocol;
using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

/// <summary>Funnel traffic reaches the service from loopback; these run with the service's own loopback rules.</summary>
[TestClass]
public sealed class FunnelHardeningTests
{
    // The service listens dual mode, so Funnel's IPv4 loopback arrives mapped; the harness listens on plain IPv4.
    [TestMethod]
    [DataRow("127.0.0.1", true)]
    [DataRow("::ffff:127.0.0.1", true)]
    [DataRow("::1", true)]
    [DataRow("100.64.0.10", false)]
    [DataRow("::ffff:192.168.1.5", false)]
    public void IsLoopback_Address_TreatsMappedIpv4LikeIpv4(string address, bool loopback)
    {
        Assert.AreEqual(loopback, RelayHub.IsLoopback(IPAddress.Parse(address)));
    }

    [TestMethod]
    public async Task Pair_FromLoopback_RejectsWithoutSpendingCodeOrBlockingPairing()
    {
        await using var harness = await RelayHarness.StartAsync(allowLoopbackPairing: false);
        var (code, _) = harness.Pairing.Start();
        await using var tablet = await harness.ConnectTabletAsync();

        await tablet.SendAsync(Frames.Pair(code, "Tab"));
        var reject = await tablet.ExpectAsync(FrameType.Reject);

        Assert.AreEqual((byte)RejectReason.InvalidCode, reject.Payload[0]);
        Assert.IsTrue(await tablet.IsClosedAsync());
        Assert.IsEmpty(harness.Devices.List());
        Assert.IsFalse(harness.Throttle.IsBlocked("127.0.0.1|pair"));
        Assert.IsTrue(harness.Pairing.TryConsume(code));
    }

    [TestMethod]
    public async Task Hello_FromLoopback_IsWelcomedAndReportedAsFunnel()
    {
        await using var harness = await RelayHarness.StartAsync(allowLoopbackPairing: false);
        var device = harness.Devices.Add("Tab");

        await using var tablet = await harness.HelloAsync(device);

        await Eventually.TrueAsync(() => harness.Hub.Last.Count == 1, "session was not counted");
        Assert.AreEqual("Tab", harness.Hub.Last.Device);
        Assert.IsTrue(harness.Hub.Last.ViaLoopback);
    }

    [TestMethod]
    public async Task Hello_SessionEnded_LastSessionStaysForTheTray()
    {
        await using var harness = await RelayHarness.StartAsync();
        var device = harness.Devices.Add("Tab");

        await using (await harness.HelloAsync(device))
        {
            await Eventually.TrueAsync(() => harness.Hub.ActiveDeviceName == "Tab", "session did not start");
        }
        await Eventually.TrueAsync(() => harness.Hub.ActiveDeviceName is null, "session did not end");

        Assert.AreEqual(1, harness.Hub.Last.Count);
        Assert.AreEqual("Tab", harness.Hub.Last.Device);
    }

    [TestMethod]
    public async Task Connect_BeyondPendingHandshakes_IsClosedAtOnce()
    {
        await using var harness = await RelayHarness.StartAsync(maxPendingHandshakes: 2);
        using var first = await ConnectRawAsync(harness.Port);
        using var second = await ConnectRawAsync(harness.Port);
        using var third = await ConnectRawAsync(harness.Port);

        Assert.IsTrue(await IsClosedAsync(third));
        Assert.IsFalse(await IsClosedAsync(first, TimeSpan.FromMilliseconds(200)));
    }

    [TestMethod]
    public async Task Connect_AfterPendingHandshakesEnd_IsServedAgain()
    {
        await using var harness = await RelayHarness.StartAsync(maxPendingHandshakes: 2);
        var device = harness.Devices.Add("Tab");
        using (await ConnectRawAsync(harness.Port))
        using (await ConnectRawAsync(harness.Port))
        {
            // Slots taken by connections that never start TLS.
        }

        // Closing frees the slots once the hub sees the end of the TLS handshake; retry until it has.
        Assert.AreEqual(FrameType.Welcome, await HelloWithRetryAsync(harness, device));
    }

    [TestMethod]
    public async Task Connect_SessionRunning_HoldsNoHandshakeSlot()
    {
        await using var harness = await RelayHarness.StartAsync(maxPendingHandshakes: 1);
        var device = harness.Devices.Add("Tab");
        await using var active = await harness.HelloAsync(device);

        await using var second = await harness.HelloAsync(device);

        await Eventually.TrueAsync(() => harness.Hub.Last.Count == 2, "second session did not start");
    }

    private static async Task<TcpClient> ConnectRawAsync(int port)
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, port);
        return tcp;
    }

    private static async Task<bool> IsClosedAsync(TcpClient tcp, TimeSpan? wait = null)
    {
        using var timeout = new CancellationTokenSource(wait ?? TimeSpan.FromSeconds(5));
        try
        {
            return await tcp.GetStream().ReadAsync(new byte[1], timeout.Token) == 0;
        }
        catch (IOException)
        {
            return true;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    private static async Task<FrameType> HelloWithRetryAsync(RelayHarness harness, (byte[] Id, byte[] Token) device)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                return await harness.HelloResultAsync(device.Id, device.Token);
            }
            catch (Exception ex) when (attempt < 50 && ex is IOException or AuthenticationException or AssertFailedException)
            {
                await Task.Delay(100);
            }
        }
    }
}
