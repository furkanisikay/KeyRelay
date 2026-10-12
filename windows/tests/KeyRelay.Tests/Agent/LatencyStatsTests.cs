using System.Diagnostics;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class LatencyStatsTests
{
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    private static long Micros(int micros) => micros * Stopwatch.Frequency / 1_000_000;

    // Agent stamps all at the same moment: the whole latency then lies in the service part.
    private static KeyDoneTimes At(int micros) => new(Micros(micros), Micros(micros), Micros(micros));

    [TestMethod]
    public void Breakdown_OneKey_SplitsTotalIntoParts()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0, press: true);
        stats.Written(Micros(100));

        stats.Done(new KeyDoneTimes(Micros(300), Micros(700), Micros(1000)));
        var parts = stats.Breakdown().ToDictionary(segment => segment.Name);

        Assert.AreEqual(1000d, parts["total"].P99Micros, 1d);
        Assert.AreEqual(100d, parts["service"].P99Micros, 1d);
        Assert.AreEqual(200d, parts["pipe"].P99Micros, 1d);
        Assert.AreEqual(400d, parts["desktop"].P99Micros, 1d);
        Assert.AreEqual(300d, parts["sendinput"].P99Micros, 1d);
        Assert.AreEqual(700d, parts["own"].P99Micros, 1d, "total without SendInput");
        Assert.AreEqual(1, parts["total"].Samples);
    }

    [TestMethod]
    public void Breakdown_WriteReturnedAfterAgentRead_PipeIsZeroNotNegative()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0, press: true);
        stats.Written(Micros(500));

        stats.Done(new KeyDoneTimes(Micros(300), Micros(400), Micros(600)));
        var parts = stats.Breakdown().ToDictionary(segment => segment.Name);

        Assert.AreEqual(300d, parts["service"].P99Micros, 1d);
        Assert.AreEqual(0, parts["pipe"].P99Micros);
    }

    [TestMethod]
    public void Breakdown_NoKeys_ReportsEveryPartEmpty()
    {
        var parts = new LatencyStats(_time).Breakdown();

        CollectionAssert.AreEqual(new[] { "total", "service", "pipe", "desktop", "sendinput", "own" }, parts.Select(segment => segment.Name).ToArray());
        Assert.IsTrue(parts.All(segment => segment is { Samples: 0, P99Micros: 0, MaxMicros: 0 }));
    }

    [TestMethod]
    public void Done_HundredSamples_ReportsNearestRankPercentiles()
    {
        var stats = new LatencyStats(_time);
        for (var i = 1; i <= 100; i++)
        {
            stats.Sent(0, press: true);
            stats.Done(At(i));
        }

        var snapshot = stats.Snapshot();

        Assert.AreEqual(50d, snapshot.P50Micros, 1d);
        Assert.AreEqual(99d, snapshot.P99Micros, 1d);
        Assert.AreEqual(100, snapshot.KeysToday);
    }

    [TestMethod]
    public void Snapshot_SampleOlderThanOneMinute_IsExcluded()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0, press: true);
        stats.Done(At(900));
        _time.Advance(TimeSpan.FromSeconds(61));
        stats.Sent(0, press: true);
        stats.Done(At(5));

        var snapshot = stats.Snapshot();

        Assert.AreEqual(5d, snapshot.P99Micros, 1d);
    }

    [TestMethod]
    public void Snapshot_CurrentSecondSample_IsInLastGraphSlot()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0, press: true);
        stats.Done(At(700));

        var perSecond = stats.Snapshot().PerSecondMaxMicros;

        Assert.HasCount(60, perSecond);
        Assert.AreEqual(700d, perSecond[59], 1d);
    }

    [TestMethod]
    public void Done_WithoutPendingKey_IsIgnored()
    {
        var stats = new LatencyStats(_time);

        stats.Done(At(10));

        Assert.AreEqual(0, stats.Snapshot().KeysToday);
    }

    [TestMethod]
    public void ResetPending_UnansweredKey_IsDropped()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0, press: true);

        stats.ResetPending();
        stats.Done(At(10));

        Assert.AreEqual(0, stats.Snapshot().P99Micros);
    }

    [TestMethod]
    public void KeysToday_PressReleaseRepeat_CountsOnePress()
    {
        var stats = new LatencyStats(_time);

        stats.Sent(0, press: true);
        stats.Sent(0, press: false);
        stats.Sent(0, press: false);

        Assert.AreEqual(1, stats.Snapshot().KeysToday);
    }

    [TestMethod]
    public void KeysToday_NextDay_StartsFromZero()
    {
        var stats = new LatencyStats(_time);
        stats.Sent(0, press: true);
        stats.Done(At(10));
        _time.Advance(TimeSpan.FromDays(1));
        stats.Sent(0, press: true);
        stats.Done(At(10));

        Assert.AreEqual(1, stats.Snapshot().KeysToday);
    }
}
