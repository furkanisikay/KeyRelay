using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class PairingCodesTests
{
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    [TestMethod]
    public void TryConsume_ActiveCode_SucceedsOnlyOnce()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();

        var first = codes.TryConsume(code);
        var second = codes.TryConsume(code);

        Assert.IsTrue(first);
        Assert.IsFalse(second);
        Assert.IsNull(codes.ActiveUntil);
    }

    [TestMethod]
    public void TryConsume_JustBeforeFiveMinutes_Succeeds()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();
        _time.Advance(TimeSpan.FromMinutes(5) - TimeSpan.FromSeconds(1));

        Assert.IsTrue(codes.TryConsume(code));
    }

    [TestMethod]
    public void TryConsume_AtFiveMinutes_Fails()
    {
        var codes = new PairingCodes(_time);
        var (code, expiresAt) = codes.Start();
        _time.Advance(TimeSpan.FromMinutes(5));

        Assert.IsFalse(codes.TryConsume(code));
        Assert.AreEqual(_time.Now, expiresAt);
    }

    [TestMethod]
    public void Start_Again_InvalidatesPreviousCode()
    {
        var codes = new PairingCodes(_time);
        var (old, _) = codes.Start();
        var (fresh, _) = codes.Start();

        Assert.IsFalse(codes.TryConsume(old));
        Assert.IsTrue(codes.TryConsume(fresh));
    }

    [TestMethod]
    public void Stop_ActiveCode_InvalidatesIt()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();

        codes.Stop();

        Assert.IsNull(codes.ActiveUntil);
        Assert.IsFalse(codes.TryConsume(code));
    }

    [TestMethod]
    public void TryConsume_WrongCode_KeepsRightCodeUsable()
    {
        var codes = new PairingCodes(_time);
        var (code, _) = codes.Start();

        var wrong = codes.TryConsume(new byte[16]);

        Assert.IsFalse(wrong);
        Assert.IsTrue(codes.TryConsume(code));
    }
}
