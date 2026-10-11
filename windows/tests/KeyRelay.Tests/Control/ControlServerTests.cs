using System.Buffers.Text;
using System.IO.Pipes;
using System.Text;
using KeyRelay.Protocol;
using KeyRelay.Protocol.Control;
using KeyRelay.Service.Control;
using KeyRelay.Service.Session;
using KeyRelay.Tests.TestSupport;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.Control;

[TestClass]
public sealed class ControlServerTests
{
    [TestMethod]
    public async Task Status_ServiceRunning_ReportsAgentAndNoDevice()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS));

        Assert.IsTrue(response.Ok);
        Assert.IsTrue(response.Status!.AgentConnected);
        Assert.IsNull(response.Status.ConnectedDevice);
        Assert.AreEqual(0, response.Status.DeviceCount);
        Assert.HasCount(60, response.Status.LatencyPerSecondMicros);
    }

    [TestMethod]
    public async Task PairStart_ReturnsLinkWithFingerprintCodeAndExpiry()
    {
        await using var fixture = await Fixture.StartAsync();

        var pairing = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.PAIR_START))).Pairing!;
        var status = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS))).Status!;

        Assert.IsTrue(pairing.Link.StartsWith("keyrelay://pair?v=1&n=", StringComparison.Ordinal));
        Assert.IsTrue(pairing.Link.Contains($"&fp={fixture.Harness.Fingerprint}&c=", StringComparison.Ordinal));
        Assert.AreEqual(fixture.Harness.Time.Now + TimeSpan.FromMinutes(5), pairing.ExpiresAt);
        // Derived from the QR "fp", as the tablet does.
        Assert.AreEqual(PairingIdentity.IdentityCode(Base64Url.DecodeFromChars(fixture.Harness.Fingerprint)), pairing.IdentityCode);
        Assert.IsTrue(status.PairingActive);
    }

    [TestMethod]
    public async Task Revoke_PairedDevice_RemovesAndDropsSession()
    {
        await using var fixture = await Fixture.StartAsync();
        var device = await fixture.Harness.PairAsync();
        await using var tablet = await fixture.Harness.HelloAsync(device);
        await Eventually.TrueAsync(() => fixture.Harness.Hub.ActiveDeviceId is not null, "session did not become active");

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: Convert.ToHexStringLower(device.Id)));
        var devices = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices!;

        Assert.IsTrue(response.Ok);
        Assert.IsTrue(await tablet.IsClosedAsync());
        Assert.IsEmpty(devices);
    }

    [TestMethod]
    public async Task Revoke_UnknownDevice_FailsWithUnknownDevice()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: "00"));

        Assert.IsFalse(response.Ok);
        Assert.AreEqual(ControlErrors.UNKNOWN_DEVICE, response.Error);
    }

    [TestMethod]
    public async Task Devices_ConnectedTablet_IsMarkedConnected()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync("Galaxy"));
        await Eventually.TrueAsync(() => fixture.Harness.Hub.ActiveDeviceId is not null, "session did not become active");

        var device = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices!.Single();

        Assert.AreEqual("Galaxy", device.Name);
        Assert.IsTrue(device.Connected);
    }

    [TestMethod]
    public async Task SetFunnel_InvalidPort_FailsAndKeepsSetting()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: "pc.tail.ts.net:8080"));
        var network = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.NETWORK))).Network!;

        Assert.AreEqual(ControlErrors.INVALID_FUNNEL, response.Error);
        Assert.AreEqual("", network.FunnelAddress);
    }

    [TestMethod]
    public async Task SetFunnel_Valid_IsFirstAddressOfNextPairing()
    {
        await using var fixture = await Fixture.StartAsync();

        await fixture.Client.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: "pc.tail.ts.net:8443"));
        var pairing = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.PAIR_START))).Pairing!;

        Assert.AreEqual("pc.tail.ts.net:8443", pairing.Addresses[0]);
        Assert.IsTrue(pairing.Link.Contains("&a=pc.tail.ts.net:8443", StringComparison.Ordinal));
    }

    [TestMethod]
    public async Task UnknownOp_FailsWithUnknownOp()
    {
        await using var fixture = await Fixture.StartAsync();

        var response = await fixture.Client.SendAsync(new ControlRequest("format-disk"));

        Assert.AreEqual(ControlErrors.UNKNOWN_OP, response.Error);
    }

    [TestMethod]
    public async Task Logs_BufferedLine_IsReturned()
    {
        await using var fixture = await Fixture.StartAsync();
        fixture.Logs.Add("12:00:00 INFO Test: hello");

        var logs = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.LOGS))).Logs!;

        Assert.Contains("12:00:00 INFO Test: hello", logs);
    }

    [TestMethod]
    public async Task Logs_500LongLines_FitsMessageLimitAndKeepsNewest()
    {
        await using var fixture = await Fixture.StartAsync();
        for (var i = 0; i < LogBuffer.CAPACITY; i++) fixture.Logs.Add($"{i:D3} {new string('x', 300)}");

        var logs = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.LOGS))).Logs!;

        Assert.IsLessThan(ControlChannel.MAX_MESSAGE_BYTES, logs.Sum(Encoding.UTF8.GetByteCount));
        Assert.StartsWith("499 ", logs[^1]);
    }

    [TestMethod]
    public async Task Logs_LineWithLoneSurrogate_IsAnswered()
    {
        await using var fixture = await Fixture.StartAsync();
        fixture.Logs.Add("12:00:00 INFO RelayHub: Connected: Tab\uD83D");

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.LOGS));

        Assert.IsTrue(response.Ok);
        Assert.HasCount(1, response.Logs!);
    }

    [TestMethod]
    public async Task Connect_ClientsCloseAtOnce_ServerKeepsServing()
    {
        await using var fixture = await Fixture.StartAsync();

        // Many quick connections so one lands between pipe creation and WaitForConnectionAsync (ERROR_NO_DATA).
        await Task.WhenAll(Enumerable.Range(0, 4).Select(_ => Task.Run(async () =>
        {
            for (var i = 0; i < 200; i++)
            {
                await using var raw = new NamedPipeClientStream(".", fixture.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
                await raw.ConnectAsync(5000);
            }
        })));

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS));

        Assert.IsTrue(response.Ok);
    }

    [TestMethod]
    public async Task Message_LongerThan64KB_ServerDropsClient()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var raw = new NamedPipeClientStream(".", fixture.PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
        await raw.ConnectAsync(5000);

        bool dropped;
        try
        {
            await raw.WriteAsync(new byte[ControlChannel.MAX_MESSAGE_BYTES + 100]);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            dropped = await raw.ReadAsync(new byte[1], timeout.Token) == 0;
        }
        catch (IOException)
        {
            // The server closed the pipe while the rest of the message was still being written.
            dropped = true;
        }

        Assert.IsTrue(dropped);
        Assert.IsTrue((await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS))).Ok);
    }

    [TestMethod]
    public async Task Status_TabletPings_HeartbeatsCountThem()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync());
        var before = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS))).Status!.Heartbeats;

        await tablet.SendAsync(Frames.Ping(1));
        await tablet.SendAsync(Frames.Ping(2));

        await Eventually.TrueAsync(() => fixture.Harness.Hub.ActivePings == 2, "pings were not counted");
        var after = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS))).Status!.Heartbeats;
        Assert.AreEqual(0, before);
        Assert.AreEqual(2, after);
    }

    [TestMethod]
    public async Task PairStart_ClientInOtherSession_RejectedAndNoCodeOpened()
    {
        await using var fixture = await Fixture.StartAsync();
        fixture.Harness.KeySession = RelayHarness.OwnSession + 1;

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.PAIR_START));
        var status = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.STATUS))).Status!;

        Assert.IsFalse(response.Ok);
        Assert.AreEqual(ControlErrors.WRONG_SESSION, response.Error);
        Assert.IsNull(response.Pairing);
        Assert.IsFalse(status.PairingActive);
    }

    [TestMethod]
    public async Task Revoke_ClientInOtherSession_RejectedAndDeviceKept()
    {
        await using var fixture = await Fixture.StartAsync();
        var device = await fixture.Harness.PairAsync();
        fixture.Harness.KeySession = RelayHarness.OwnSession + 1;

        var response = await fixture.Client.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: Convert.ToHexStringLower(device.Id)));
        var funnel = await fixture.Client.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: "pc.tailnet.ts.net:8443"));
        var devices = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices!;

        Assert.AreEqual(ControlErrors.WRONG_SESSION, response.Error);
        Assert.AreEqual(ControlErrors.WRONG_SESSION, funnel.Error);
        Assert.HasCount(1, devices);
        Assert.AreEqual("", fixture.Harness.Settings.Load().FunnelAddress);
    }

    [TestMethod]
    public async Task RecentKeys_NotRequested_KeysTypedBeforeAreNotKept()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync());

        await TypeAsync(fixture, tablet, 30, 31);
        var keys = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys!;

        Assert.IsEmpty(keys);
    }

    [TestMethod]
    public async Task RecentKeys_Requested_ReturnsPressedKeyNamesInOrder()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync());
        await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS));

        await TypeAsync(fixture, tablet, 29, 30, 15);
        var keys = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys!;

        CollectionAssert.AreEqual(new[] { "Ctrl", "A", "Tab" }, keys.ToArray());
    }

    [TestMethod]
    public async Task RecentKeysStop_AfterTyping_ForgetsAndStopsCollecting()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync());
        await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS));
        await TypeAsync(fixture, tablet, 30);

        var stop = await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS_STOP));
        await TypeAsync(fixture, tablet, 31);
        var keys = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys!;

        Assert.IsTrue(stop.Ok);
        Assert.IsEmpty(keys);
    }

    [TestMethod]
    public async Task RecentKeys_ClientInOtherSession_GetsNothingAndStartsNothing()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync());
        fixture.Harness.KeySession = RelayHarness.OwnSession + 1;

        var asked = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys!;
        await TypeAsync(fixture, tablet, 30, 31);
        fixture.Harness.KeySession = RelayHarness.OwnSession;
        var later = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys!;

        Assert.IsEmpty(asked);
        Assert.IsEmpty(later, "the denied request must not have started collection");
    }

    [TestMethod]
    public async Task RecentKeys_TabletSendsLock_StripIsEmptied()
    {
        await using var fixture = await Fixture.StartAsync();
        await using var tablet = await fixture.Harness.HelloAsync(await fixture.Harness.PairAsync());
        await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS));
        await TypeAsync(fixture, tablet, 30, 31);

        await tablet.SendAsync(Frames.LockFrame.ToArray());
        await fixture.Harness.AgentPipe!.ReadAsync();
        var keys = (await fixture.Client.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys!;

        Assert.IsEmpty(keys);
    }

    // Press and release each key; the fake agent must read them, the service gives up on an unread write after 1 s.
    private static async Task TypeAsync(Fixture fixture, TabletClient tablet, params ushort[] codes)
    {
        foreach (var code in codes)
        {
            foreach (var value in (byte[])[1, 0])
            {
                await tablet.SendAsync(Frames.Key(code, value));
                await fixture.Harness.AgentPipe!.ReadAsync();
            }
        }
        // The session notes a key after forwarding it; one more release (never kept) proves the last press was noted.
        await tablet.SendAsync(Frames.Key(codes[^1], 0));
        await fixture.Harness.AgentPipe!.ReadAsync();
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SessionSignal _sessions = new();
        private readonly AgentSupervisor _supervisor;

        private Fixture(RelayHarness harness)
        {
            Harness = harness;
            _supervisor = new AgentSupervisor(harness.Agent, _sessions, NullLogger<AgentSupervisor>.Instance);
            var context = new ControlContext(harness.Devices, harness.Pairing, harness.Hub, harness.Agent, harness.Latency, harness.Recent, harness.Settings, Logs, _supervisor, harness.Certificate)
            {
                KeySession = () => harness.KeySession,
            };
            Server = new ControlServer(new ControlOptions(PipeName, TestPipes.CurrentUser()), new ControlHandler(context, NullLogger<ControlHandler>.Instance), NullLogger<ControlServer>.Instance);
            Client = new ControlClient(PipeName);
        }

        internal string PipeName { get; } = TestPipes.Name();

        internal RelayHarness Harness { get; }

        internal LogBuffer Logs { get; } = new();

        internal ControlServer Server { get; }

        internal ControlClient Client { get; }

        internal static async Task<Fixture> StartAsync()
        {
            var fixture = new Fixture(await RelayHarness.StartAsync());
            await fixture.Server.StartAsync(CancellationToken.None);
            return fixture;
        }

        public async ValueTask DisposeAsync()
        {
            await Client.DisposeAsync();
            await Server.StopAsync(CancellationToken.None);
            Server.Dispose();
            _supervisor.Dispose();
            _sessions.Dispose();
            Logs.Dispose();
            await Harness.DisposeAsync();
        }
    }
}
