using KeyRelay.Service.Session;

namespace KeyRelay.Tests.Session;

[TestClass]
public sealed class SessionSignalTests
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    [TestMethod]
    public void WaitForChangeAsync_SessionAlreadyDifferent_CompletesAtOnce()
    {
        using var signal = new SessionSignal();

        var wait = signal.WaitForChangeAsync(1, () => 2, CancellationToken.None);

        Assert.IsTrue(wait.IsCompletedSuccessfully);
    }

    [TestMethod]
    public async Task WaitForChangeAsync_NotifyWithoutChange_KeepsWaitingUntilSessionChanges()
    {
        using var signal = new SessionSignal();
        uint active = 1;
        var wait = signal.WaitForChangeAsync(1, () => Volatile.Read(ref active), CancellationToken.None);

        signal.Notify();
        signal.Notify();
        await Task.Delay(100);
        var completedEarly = wait.IsCompleted;
        Volatile.Write(ref active, 2);
        signal.Notify();
        await wait.WaitAsync(TIMEOUT);

        Assert.IsFalse(completedEarly);
    }
}
