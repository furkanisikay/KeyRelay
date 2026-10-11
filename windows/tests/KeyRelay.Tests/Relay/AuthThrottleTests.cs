using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class AuthThrottleTests
{
    private const string KEY = "127.0.0.1|pair";
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    [TestMethod]
    public void Failed_Once_BlocksForOneSecond()
    {
        var throttle = new AuthThrottle(_time);

        throttle.Failed(KEY);
        _time.Advance(TimeSpan.FromMilliseconds(999));
        var during = throttle.IsBlocked(KEY);
        _time.Advance(TimeSpan.FromMilliseconds(1));
        var after = throttle.IsBlocked(KEY);

        Assert.IsTrue(during);
        Assert.IsFalse(after);
    }

    [TestMethod]
    public void Failed_ThreeTimes_BlocksForFourSeconds()
    {
        var throttle = new AuthThrottle(_time);

        for (var i = 0; i < 3; i++) throttle.Failed(KEY);
        _time.Advance(TimeSpan.FromSeconds(3.9));
        var during = throttle.IsBlocked(KEY);
        _time.Advance(TimeSpan.FromSeconds(0.1));
        var after = throttle.IsBlocked(KEY);

        Assert.IsTrue(during);
        Assert.IsFalse(after);
    }

    [TestMethod]
    public void Failed_ManyTimes_CapsAtSixtySeconds()
    {
        var throttle = new AuthThrottle(_time);

        for (var i = 0; i < 50; i++) throttle.Failed(KEY);
        _time.Advance(TimeSpan.FromSeconds(59.9));
        var during = throttle.IsBlocked(KEY);
        _time.Advance(TimeSpan.FromSeconds(0.1));
        var after = throttle.IsBlocked(KEY);

        Assert.IsTrue(during);
        Assert.IsFalse(after);
    }

    [TestMethod]
    public void Succeeded_AfterFailures_Unblocks()
    {
        var throttle = new AuthThrottle(_time);
        throttle.Failed(KEY);

        throttle.Succeeded(KEY);

        Assert.IsFalse(throttle.IsBlocked(KEY));
    }

    [TestMethod]
    public void Failed_OneKey_DoesNotBlockAnother()
    {
        var throttle = new AuthThrottle(_time);

        throttle.Failed(KEY);

        Assert.IsFalse(throttle.IsBlocked("127.0.0.1|00112233"));
    }
}
