using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class KeyInjectorTests
{
    private const ushort KEY_A = 30;
    private const ushort KEY_S = 31;
    private const ushort KEY_VOLUMEUP = 115;
    private const ushort KEY_PLAYPAUSE = 164;
    private const ushort KEY_DELETE = 111;
    private const ushort KEY_RIGHTALT = 100;
    private const ushort KEY_UNKNOWN = 240;
    private const uint DOWN = User32.KEYEVENTF_SCANCODE;
    private const uint UP = User32.KEYEVENTF_SCANCODE | User32.KEYEVENTF_KEYUP;
    private const uint EXTENDED = User32.KEYEVENTF_EXTENDEDKEY;

    private readonly FakeDesktop _desktop = new();
    private readonly List<(bool Writable, string Desktop)> _reports = [];

    private KeyInjector Create() => new(_desktop, (writable, desktop) => _reports.Add((writable, desktop)));

    private KeyInjector CreateStuckOnWinlogon()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.Reject("Winlogon");
        injector.Inject(KEY_A, 1);
        _desktop.ClearSent();
        _reports.Clear();
        return injector;
    }

    [TestMethod]
    public void Inject_KeyDown_SendsOneScancodeEvent()
    {
        var injector = Create();

        injector.Inject(KEY_A, 1);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, DOWN, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    [DataRow((ushort)113, (int)MediaKey.Mute, DisplayName = "mute")]
    [DataRow((ushort)114, (int)MediaKey.VolumeDown, DisplayName = "volume down")]
    [DataRow((ushort)115, (int)MediaKey.VolumeUp, DisplayName = "volume up")]
    [DataRow((ushort)163, (int)MediaKey.NextTrack, DisplayName = "next track")]
    [DataRow((ushort)164, (int)MediaKey.PlayPause, DisplayName = "play/pause")]
    [DataRow((ushort)165, (int)MediaKey.PreviousTrack, DisplayName = "previous track")]
    [DataRow((ushort)166, (int)MediaKey.Stop, DisplayName = "stop")]
    [DataRow(KEY_A, (int)MediaKey.None, DisplayName = "letter is not a media key")]
    public void Media_EvdevCode_IsMediaKey(ushort code, int expected)
    {
        Assert.AreEqual((MediaKey)expected, KeyInjector.Media(code));
    }

    [TestMethod]
    public void Inject_VolumeKey_ActsOnceOnPressAndSendsNoKeyEvents()
    {
        var injector = Create();

        injector.Inject(KEY_VOLUMEUP, 1);
        injector.Inject(KEY_VOLUMEUP, 2);
        injector.Inject(KEY_VOLUMEUP, 0);
        injector.ReleaseAll();

        CollectionAssert.AreEqual(new[] { MediaKey.VolumeUp }, _desktop.MediaSnapshot());
        Assert.HasCount(8, _desktop.SentSnapshot(), "only the modifier releases of RELEASE_ALL");
    }

    [TestMethod]
    public void Inject_VolumeKeyOnLockScreen_StillChangesVolume()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";

        injector.Inject(KEY_VOLUMEUP, 1);

        CollectionAssert.AreEqual(new[] { MediaKey.VolumeUp }, _desktop.MediaSnapshot());
    }

    [TestMethod]
    public void Inject_TrackKeyOnLockScreen_DoesNothing()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";

        var stamp = injector.Inject(KEY_PLAYPAUSE, 1);

        Assert.AreEqual(0, stamp);
        Assert.IsEmpty(_desktop.MediaSnapshot());
    }

    [TestMethod]
    public void Inject_MediaKeyPressReleaseRepeat_LogsOneLinePerPressWithItsOutcome()
    {
        var lines = new List<(bool Warning, string Line)>();
        var injector = new KeyInjector(_desktop, (_, _) => { }, (warning, line) => lines.Add((warning, line)));
        _desktop.MediaResult = (false, "vk=0xAF sent=0 error=5");

        injector.Inject(KEY_VOLUMEUP, 1);
        injector.Inject(KEY_VOLUMEUP, 2);
        injector.Inject(KEY_VOLUMEUP, 0);
        _desktop.Input = "Winlogon";
        injector.Inject(KEY_PLAYPAUSE, 1);
        injector.Inject(KEY_A, 1);

        CollectionAssert.AreEqual(
            new[] { (true, "media=VolumeUp vk=0xAF sent=0 error=5"), (false, "media=PlayPause path=skipped desktop=Winlogon") },
            lines);
    }

    [TestMethod]
    public void Inject_MappedAndUnmappedKey_ReturnsDesktopStampOnlyWhenSent()
    {
        var injector = Create();
        var before = System.Diagnostics.Stopwatch.GetTimestamp();

        var mapped = injector.Inject(KEY_A, 1);
        var unmapped = injector.Inject(KEY_UNKNOWN, 1);

        Assert.IsTrue(mapped >= before, "the stamp is taken while sending");
        Assert.AreEqual(0, unmapped);
    }


    [TestMethod]
    public void Inject_RightAlt_SetsExtendedFlag()
    {
        var injector = Create();

        injector.Inject(KEY_RIGHTALT, 1);

        CollectionAssert.AreEqual(new[] { ((ushort)0x38, DOWN | EXTENDED, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_KeyUp_SetsKeyUpFlag()
    {
        var injector = Create();

        injector.Inject(KEY_A, 0);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, UP, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_RepeatWhileHeld_SendsKeyDownAgain()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);

        injector.Inject(KEY_A, 2);
        injector.Inject(KEY_A, 2);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, DOWN, "Default"), ((ushort)0x1E, DOWN, "Default"), ((ushort)0x1E, DOWN, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public async Task Inject_HeldKeyWithoutRepeatFrames_IsNotRepeatedByTheAgent()
    {
        var injector = Create();

        injector.Inject(KEY_A, 1);
        // Longer than the longest Windows repeat delay setting (about 1 s): an agent-side repeat would have fired.
        await Task.Delay(TimeSpan.FromMilliseconds(1100));

        Assert.HasCount(1, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_RepeatArrivingAfterRelease_IsIgnored()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_A, 0);
        _desktop.ClearSent();

        var stamp = injector.Inject(KEY_A, 2);

        Assert.AreEqual(0, stamp);
        Assert.IsEmpty(_desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_RepeatAfterReleaseAll_IsIgnored()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);
        injector.ReleaseAll();
        _desktop.ClearSent();

        injector.Inject(KEY_A, 2);

        Assert.IsEmpty(_desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_RepeatOfAnotherKey_DoesNotTypeIt()
    {
        var injector = Create();
        injector.Inject(KEY_S, 1);

        injector.Inject(KEY_A, 2);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1F, DOWN, "Default") }, _desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_UnknownCode_SendsNothing()
    {
        var injector = Create();

        injector.Inject(KEY_UNKNOWN, 1);

        Assert.IsEmpty(_desktop.SentSnapshot());
    }

    [TestMethod]
    public void Inject_InputDesktopChanged_SendsOnNewDesktopWithoutRetry()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";

        injector.Inject(KEY_A, 1);

        CollectionAssert.AreEqual(new[] { ((ushort)0x1E, DOWN, "Winlogon") }, _desktop.SentSnapshot());
        Assert.AreEqual(0, _desktop.ForcedAttaches);
        Assert.IsEmpty(_reports);
    }

    [TestMethod]
    public void Inject_FirstAttemptRejected_RetriesOnceAndStaysWritable()
    {
        var injector = Create();
        _desktop.RejectNext(1);

        injector.Inject(KEY_A, 1);

        Assert.HasCount(1, _desktop.SentSnapshot());
        Assert.AreEqual(1, _desktop.ForcedAttaches);
        Assert.IsEmpty(_reports);
        Assert.IsTrue(injector.Writable);
    }

    [TestMethod]
    public void Inject_RejectedTwiceOnCurrentDesktop_ReportsNotWritableOnce()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.Reject("Winlogon");

        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_A, 0);

        CollectionAssert.AreEqual(new[] { (false, "Winlogon") }, _reports);
        Assert.IsFalse(injector.Writable);
    }

    [TestMethod]
    public void Inject_AttachFails_ReportsNotWritable()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.AttachFails = true;

        injector.Inject(KEY_A, 1);

        Assert.IsEmpty(_desktop.SentSnapshot());
        CollectionAssert.AreEqual(new[] { (false, "Winlogon") }, _reports);
        Assert.IsFalse(injector.Writable);
    }

    [TestMethod]
    public void Watch_SameDesktopWhileStuck_StaysNotWritableAndSilent()
    {
        var injector = CreateStuckOnWinlogon();

        injector.Watch();
        injector.Watch();

        Assert.IsEmpty(_reports);
        Assert.IsFalse(injector.Writable);
    }

    [TestMethod]
    public void Watch_DesktopNameChangesAfterStuck_ReleasesModifiersAndReportsWritable()
    {
        var injector = CreateStuckOnWinlogon();
        _desktop.Input = "Default";

        injector.Watch();

        CollectionAssert.AreEqual(new[] { (true, "Default") }, _reports);
        var sent = _desktop.SentSnapshot();
        Assert.HasCount(8, sent);
        Assert.IsTrue(sent.All(s => (s.Flags & User32.KEYEVENTF_KEYUP) != 0));
        Assert.IsTrue(injector.Writable);
    }

    [TestMethod]
    public void Watch_DesktopChangesWhileWritable_ReportsNameAndInjectsNothing()
    {
        var injector = Create();

        injector.Watch();
        _desktop.Input = "Winlogon";
        injector.Watch();

        CollectionAssert.AreEqual(new[] { (true, "Default"), (true, "Winlogon") }, _reports);
        Assert.IsEmpty(_desktop.SentSnapshot());
    }

    [TestMethod]
    public void Watch_RejectingDesktopWhileWritable_NeverReportsNotWritable()
    {
        var injector = Create();
        _desktop.Input = "Winlogon";
        _desktop.Reject("Winlogon");

        injector.Watch();
        injector.Watch();

        Assert.IsTrue(_reports.All(r => r.Writable));
    }

    [TestMethod]
    public void ReleaseAll_HeldKeys_ReleasesThemAndModifiers()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_DELETE, 1);
        _desktop.ClearSent();

        injector.ReleaseAll();
        var first = _desktop.SentSnapshot();
        _desktop.ClearSent();
        injector.ReleaseAll();
        var second = _desktop.SentSnapshot();

        Assert.HasCount(10, first);
        Assert.Contains(((ushort)0x1E, UP, "Default"), first);
        Assert.Contains(((ushort)0x53, UP | EXTENDED, "Default"), first);
        Assert.HasCount(8, second);
    }

    [TestMethod]
    public void ReleaseAll_WhileStuck_ReportsWritable()
    {
        var injector = CreateStuckOnWinlogon();

        injector.ReleaseAll();

        CollectionAssert.AreEqual(new[] { (true, "Winlogon") }, _reports);
        Assert.IsTrue(injector.Writable);
    }

    [TestMethod]
    public void ReleaseModifiers_SendsLeftAndRightCtrlAltShiftWinUp()
    {
        var injector = Create();

        injector.ReleaseModifiers();

        CollectionAssert.AreEqual(
            new[]
            {
                ((ushort)0x1D, UP, "Default"), ((ushort)0x1D, UP | EXTENDED, "Default"),
                ((ushort)0x38, UP, "Default"), ((ushort)0x38, UP | EXTENDED, "Default"),
                ((ushort)0x2A, UP, "Default"), ((ushort)0x36, UP, "Default"),
                ((ushort)0x5B, UP | EXTENDED, "Default"), ((ushort)0x5C, UP | EXTENDED, "Default"),
            },
            _desktop.SentSnapshot());
    }

    [TestMethod]
    public void ReleaseAll_KeyAlreadyReleased_SendsOnlyModifiers()
    {
        var injector = Create();
        injector.Inject(KEY_A, 1);
        injector.Inject(KEY_A, 0);
        _desktop.ClearSent();

        injector.ReleaseAll();

        Assert.HasCount(8, _desktop.SentSnapshot());
    }
}
