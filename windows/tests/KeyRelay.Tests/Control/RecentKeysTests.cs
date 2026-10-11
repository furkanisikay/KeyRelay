using System.ServiceProcess;
using KeyRelay.Service.Control;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Control;

[TestClass]
public sealed class RecentKeysTests
{
    private const int PRESS = 1;
    private const uint SESSION = 1;
    private const uint OTHER_SESSION = 2;
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 9, 10, 0, 0, TimeSpan.Zero));
    private string _desktop = "Default";
    private uint? _keySession = SESSION;

    [TestMethod]
    public void Add_NeverRequested_KeepsNothing()
    {
        using var recent = Create();

        recent.Add(30, PRESS);

        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void Take_TenPressesAfterRequest_ReturnsLastEightInOrder()
    {
        using var recent = Create();
        recent.Take(SESSION);

        for (ushort code = 2; code <= 11; code++) recent.Add(code, PRESS);

        CollectionAssert.AreEqual(new[] { "3", "4", "5", "6", "7", "8", "9", "0" }, recent.Take(SESSION).ToArray());
    }

    [TestMethod]
    public void Add_ReleaseAndAutoRepeat_AreNotKept()
    {
        using var recent = Create();
        recent.Take(SESSION);

        recent.Add(30, 0);
        recent.Add(30, 2);

        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void Stop_AfterPresses_ForgetsAndStopsCollecting()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        recent.Stop();
        recent.Stop();
        recent.Add(31, PRESS);

        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void Add_TrayStoppedAsking_LeaseEndsAndKeysAreForgotten()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        _time.Advance(TimeSpan.FromSeconds(6));
        recent.Add(31, PRESS);

        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void Take_EverySecond_KeepsCollecting()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        for (var i = 0; i < 10; i++)
        {
            _time.Advance(TimeSpan.FromSeconds(1));
            recent.Take(SESSION);
        }
        recent.Add(31, PRESS);

        CollectionAssert.AreEqual(new[] { "A", "S" }, recent.Take(SESSION).ToArray());
    }

    [TestMethod]
    public void Take_AnotherSessionAsksAfterSwitch_StartsEmpty()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        _keySession = OTHER_SESSION;

        Assert.IsEmpty(recent.Take(OTHER_SESSION));
    }

    [TestMethod]
    [DataRow(SessionChangeReason.SessionLock)]
    [DataRow(SessionChangeReason.SessionLogoff)]
    [DataRow(SessionChangeReason.ConsoleDisconnect)]
    [DataRow(SessionChangeReason.RemoteDisconnect)]
    public void OnSessionChange_LockOrLeave_ForgetsAndStopsCollecting(SessionChangeReason reason)
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        recent.OnSessionChange(reason);
        // Digits typed on the lock curtain, which is still the Default desktop.
        recent.Add(2, PRESS);

        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void OnSessionChange_Unlock_KeepsKeys()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        recent.OnSessionChange(SessionChangeReason.SessionUnlock);

        CollectionAssert.AreEqual(new[] { "A" }, recent.Take(SESSION).ToArray());
    }

    [TestMethod]
    public void Add_LockScreen_WipesEarlierKeysAndKeepsNone()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        _desktop = "Winlogon";
        recent.Add(2, PRESS);
        recent.Add(3, PRESS);
        _desktop = "Default";

        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void Add_UserSwitched_ForgetsAndNewSessionStartsEmpty()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        _keySession = OTHER_SESSION;
        recent.Add(31, PRESS);

        Assert.IsEmpty(recent.Take(OTHER_SESSION));
        recent.Add(32, PRESS);
        CollectionAssert.AreEqual(new[] { "D" }, recent.Take(OTHER_SESSION).ToArray());
    }

    [TestMethod]
    public void Take_WhileLocked_ReturnsNothingAndForgetsEarlierKeys()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);

        _desktop = "Winlogon";
        var locked = recent.Take(SESSION);
        _desktop = "Default";

        Assert.IsEmpty(locked);
        Assert.IsEmpty(recent.Take(SESSION));
    }

    [TestMethod]
    public void OnDesktopChanged_KeysOfTheLastSecond_AreDropped()
    {
        using var recent = Create();
        recent.Take(SESSION);
        recent.Add(30, PRESS);
        _time.Advance(TimeSpan.FromSeconds(1.5));
        // Typed on the lock screen before the agent's 500 ms poll reported it.
        recent.Add(2, PRESS);
        _time.Advance(TimeSpan.FromSeconds(0.3));

        recent.OnDesktopChanged();
        recent.OnDesktopChanged();

        CollectionAssert.AreEqual(new[] { "A" }, recent.Take(SESSION).ToArray());
    }

    [TestMethod]
    [DataRow((ushort)1, "Esc")]
    [DataRow((ushort)16, "Q")]
    [DataRow((ushort)50, "M")]
    [DataRow((ushort)11, "0")]
    [DataRow((ushort)59, "F1")]
    [DataRow((ushort)88, "F12")]
    [DataRow((ushort)97, "Ctrl")]
    [DataRow((ushort)125, "Win")]
    [DataRow((ushort)39, "•", DisplayName = "punctuation shows as a dot")]
    public void Name_Code_IsShortKeyName(ushort code, string expected)
    {
        Assert.AreEqual(expected, RecentKeys.Name(code));
    }

    private RecentKeys Create() => new(_time, () => _desktop, () => _keySession);
}
