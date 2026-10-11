using System.Diagnostics;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Tests.TestSupport;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class AgentLinkTests : IDisposable
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));
    private readonly CancellationTokenSource _stop = new();
    private readonly List<bool> _readyEvents = [];
    private LatencyStats? _latency;
    private AgentLink? _link;
    private string _pipe = "";

    private AgentLink Create(int bufferSize = 0)
    {
        _pipe = TestPipes.Name();
        _latency = new LatencyStats(_time);
        _link = new AgentLink(new AgentLinkOptions(_pipe, TestPipes.CurrentUser(), bufferSize), _latency, NullLogger<AgentLink>.Instance);
        _link.ReadyChanged += ready => { lock (_readyEvents) _readyEvents.Add(ready); };
        return _link;
    }

    // Ready flips before ReadyChanged runs, so a test that saw the flip still has to wait for the event.
    private Task ReadyEventsAsync(params bool[] expected) =>
        Eventually.TrueAsync(() => { lock (_readyEvents) return _readyEvents.SequenceEqual(expected); }, $"ReadyChanged events were not [{string.Join(", ", expected)}]");

    public void Dispose()
    {
        _stop.Cancel();
        _stop.Dispose();
        _link?.Dispose();
    }

    [TestMethod]
    public async Task RunAsync_AgentConnects_BecomesReady()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);

        await using var agent = await FakeAgent.ConnectAsync(_pipe);

        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");
        await ReadyEventsAsync(true);
    }

    [TestMethod]
    public async Task RunAsync_DesktopNotWritable_ReadyFalseWithDesktopName()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await agent.SendAsync(Frames.Desktop(false, "Winlogon"));

        await Eventually.TrueAsync(() => !link.Ready && link.DesktopName == "Winlogon", "not-writable desktop was not applied");
        await ReadyEventsAsync(true, false);
    }

    [TestMethod]
    public async Task RunAsync_DesktopReports_DesktopChangedOnlyWhenTheNameChanges()
    {
        var link = Create();
        var changes = new List<string>();
        link.DesktopChanged += name => { lock (changes) changes.Add(name); };
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await agent.SendAsync(Frames.Desktop(true, "Winlogon"));
        await agent.SendAsync(Frames.Desktop(false, "Winlogon"));
        await agent.SendAsync(Frames.Desktop(true, "Default"));

        await Eventually.TrueAsync(() => { lock (changes) return changes.SequenceEqual(["Winlogon", "Default"]); }, "DesktopChanged was not [Winlogon, Default]");
    }

    [TestMethod]
    public async Task SendAsync_KeyFrame_ArrivesAtAgentUnchanged()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");
        var key = Frames.Key(30, 1);
        // With no pipe buffer a write completes only once the agent reads.
        var received = agent.ReadAsync();

        var sent = await link.SendAsync(key);

        Assert.IsTrue(sent);
        CollectionAssert.AreEqual(key, await received);
    }

    [TestMethod]
    public async Task SendAsync_NoAgent_ReturnsFalse()
    {
        var link = Create();

        var sent = await link.SendAsync(Frames.Key(30, 1));

        Assert.IsFalse(sent);
    }

    [TestMethod]
    public async Task RunAsync_ClientIsNotTheLaunchedAgent_EndsWithoutReady()
    {
        var link = Create();
        var run = link.RunAsync(Environment.ProcessId + 1, _stop.Token);

        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await run.WaitAsync(TIMEOUT);

        Assert.IsFalse(link.Ready);
        lock (_readyEvents) Assert.IsEmpty(_readyEvents);
    }

    [TestMethod]
    public async Task SendAsync_AgentStopsReading_TimesOutAndEndsRun()
    {
        var link = Create(bufferSize: 64);
        var run = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        var sent = true;
        for (var i = 0; i < 100_000 && sent; i++) sent = await link.SendAsync(Frames.Key(30, 1));

        Assert.IsFalse(sent);
        await run.WaitAsync(TIMEOUT);
        Assert.IsFalse(link.Ready);
    }

    [TestMethod]
    public async Task RunAsync_KeyDone_RecordsLatency()
    {
        var link = Create();
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        var received = agent.ReadAsync();
        await link.SendAsync(Frames.Key(30, 1), Stopwatch.GetTimestamp());
        await received;
        var now = Stopwatch.GetTimestamp();
        await agent.SendAsync(Frames.KeyDone(new KeyDoneTimes(now, now, now)));

        await Eventually.TrueAsync(() => _latency!.Snapshot().P99Micros > 0, "latency sample was not recorded");
    }

    [TestMethod]
    public async Task SendAsync_PressRepeatRelease_CountsOneKeyToday()
    {
        var link = Create(bufferSize: 64);
        _ = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await link.SendAsync(Frames.Key(30, 1), Stopwatch.GetTimestamp());
        await link.SendAsync(Frames.Key(30, 2), Stopwatch.GetTimestamp());
        await link.SendAsync(Frames.Key(30, 0), Stopwatch.GetTimestamp());

        Assert.AreEqual(1, _latency!.Snapshot().KeysToday);
    }

    [TestMethod]
    public async Task RunAsync_MalformedFrame_EndsRunNotReady()
    {
        var link = Create();
        var run = link.RunAsync(Environment.ProcessId, _stop.Token);
        await using var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await agent.SendAsync([0, 0]);
        await run.WaitAsync(TIMEOUT);

        Assert.IsFalse(link.Ready);
        await ReadyEventsAsync(true, false);
    }

    [TestMethod]
    public async Task RunAsync_AgentDisconnects_ReadyFalse()
    {
        var link = Create();
        var run = link.RunAsync(Environment.ProcessId, _stop.Token);
        var agent = await FakeAgent.ConnectAsync(_pipe);
        await Eventually.TrueAsync(() => link.Ready, "link did not become ready");

        await agent.DisposeAsync();
        await run.WaitAsync(TIMEOUT);

        Assert.IsFalse(link.Connected);
        lock (_readyEvents) CollectionAssert.AreEqual(new[] { true, false }, _readyEvents);
    }
}
