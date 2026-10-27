using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class LogBudgetTests
{
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    [TestMethod]
    public void TryTake_BeyondLimit_RefusesForTheRestOfTheMinute()
    {
        var budget = new LogBudget(_time, 2);

        var taken = new[] { budget.TryTake(out _), budget.TryTake(out _), budget.TryTake(out _) };
        _time.Advance(TimeSpan.FromSeconds(59.9));
        var late = budget.TryTake(out _);

        CollectionAssert.AreEqual(new[] { true, true, false }, taken);
        Assert.IsFalse(late);
    }

    [TestMethod]
    public void TryTake_NextMinute_ReportsWhatWasDropped()
    {
        var budget = new LogBudget(_time, 1);
        budget.TryTake(out _);
        for (var i = 0; i < 5; i++) budget.TryTake(out _);

        _time.Advance(TimeSpan.FromMinutes(1));
        var taken = budget.TryTake(out var suppressed);
        budget.TryTake(out _);
        _time.Advance(TimeSpan.FromMinutes(1));
        budget.TryTake(out var afterReport);

        Assert.IsTrue(taken);
        Assert.AreEqual(5, suppressed);
        Assert.AreEqual(1, afterReport);
    }
}
