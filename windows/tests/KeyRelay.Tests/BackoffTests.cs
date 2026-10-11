using KeyRelay.Service;

namespace KeyRelay.Tests;

[TestClass]
public sealed class BackoffTests
{
    private static readonly TimeSpan FIRST = TimeSpan.FromMilliseconds(250);
    private static readonly TimeSpan MAX = TimeSpan.FromSeconds(5);

    [TestMethod]
    [DataRow(0, 0)]
    [DataRow(1, 250)]
    [DataRow(2, 500)]
    [DataRow(3, 1000)]
    [DataRow(5, 4000)]
    [DataRow(6, 5000)]
    [DataRow(1000, 5000)]
    public void Delay_Failures_DoublesUpToMax(int failures, int expectedMilliseconds)
    {
        var delay = Backoff.Delay(failures, FIRST, MAX);

        Assert.AreEqual(TimeSpan.FromMilliseconds(expectedMilliseconds), delay);
    }
}
