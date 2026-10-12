using System.Diagnostics;
using KeyRelay.Protocol;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>
/// Injects key and mouse button events on the input desktop and decides whether that desktop accepts input (spec "Ajan modu").
/// Writable turns false only when an event was rejected, retried on the freshly opened input desktop and rejected again
/// (or the input desktop could not be reopened);
/// it turns true again when the input desktop name differs from the one stored then, or on RELEASE_ALL.
/// </summary>
/// <param name="desktop">user32 access.</param>
/// <param name="report">Called with (writable, desktop name) when either changes.</param>
/// <param name="log">Called with (warning, line) for the service log: the outcome of every media key press.</param>
internal sealed class KeyInjector(IInputDesktop desktop, Action<bool, string> report, Action<bool, string>? log = null)
{
    private const int EXTENDED_BIT = 0x100;
    private const byte KEY_PRESS = 1;
    private const byte AUTO_REPEAT = 2;
    private const string USER_DESKTOP = "Default";
    // evdev mouse buttons (input-event-codes.h) the tablet sends as KEY frames.
    private const ushort BTN_MIDDLE = 0x112;
    private const ushort BTN_SIDE = 0x113;
    private const ushort BTN_EXTRA = 0x114;
    private const ushort BTN_FORWARD = 0x115;
    private const ushort BTN_BACK = 0x116;
    private const ushort BTN_TASK = 0x117;
    // Mouse buttons are held in _held after the 512 scancode slots.
    private const int MOUSE_MIDDLE = 2 * EXTENDED_BIT;
    private const int MOUSE_X1 = MOUSE_MIDDLE + 1;
    private const int MOUSE_X2 = MOUSE_MIDDLE + 2;
    private const int LEFT_WIN = 0x5B | EXTENDED_BIT;
    private const int TAB = 0x0F;
    // Left/right Ctrl, Alt, Shift and Win as set-1 scancodes; EXTENDED_BIT marks E0-prefixed keys.
    private static readonly int[] MODIFIERS =
        [0x1D, 0x1D | EXTENDED_BIT, 0x38, 0x38 | EXTENDED_BIT, 0x2A, 0x36, LEFT_WIN, 0x5C | EXTENDED_BIT];

    private readonly Lock _gate = new();
    private readonly bool[] _held = new bool[MOUSE_X2 + 1];
    private bool _writable = true;
    private string? _stuckDesktop;
    private string? _lastDesktop;
    private long _attached;

    /// <summary>Gets whether the input desktop currently counts as writable.</summary>
    internal bool Writable { get { lock (_gate) return _writable; } }

    /// <summary>
    /// Injects one evdev key event. The tablet helper makes the key repeat (value 2); a repeat counts only while the
    /// key is still held, so a repeat that arrives late, after its release, does not type one more character.
    /// </summary>
    /// <param name="code">evdev key code; mouse buttons (BTN_MIDDLE..BTN_TASK) become mouse input or Task View.</param>
    /// <param name="value">0 release, 1 press, 2 repeat.</param>
    /// <returns>Stopwatch timestamp when the thread was on the input desktop, right before SendInput; 0 when nothing was sent.</returns>
    internal long Inject(ushort code, byte value)
    {
        if (code is BTN_FORWARD or BTN_TASK) return value == KEY_PRESS ? SendTaskView() : 0;
        if (Media(code) is var media and not MediaKey.None) return value == KEY_PRESS ? SendMedia(media) : 0;
        if (!TryKey(code, out var key)) return 0;
        lock (_gate)
        {
            if (value == AUTO_REPEAT && !_held[key]) return 0;
            _held[key] = value != 0;
            Send(key, up: value == 0);
            return _attached;
        }
    }

    /// <summary>Gets the media action of an evdev code, or None.</summary>
    internal static MediaKey Media(ushort code) => code switch
    {
        113 => MediaKey.Mute,
        114 => MediaKey.VolumeDown,
        115 => MediaKey.VolumeUp,
        163 => MediaKey.NextTrack,
        164 => MediaKey.PlayPause,
        165 => MediaKey.PreviousTrack,
        166 => MediaKey.Stop,
        _ => MediaKey.None,
    };

    private static bool TryKey(ushort code, out int key)
    {
        key = code switch
        {
            BTN_MIDDLE => MOUSE_MIDDLE,
            BTN_SIDE or BTN_BACK => MOUSE_X1,
            BTN_EXTRA => MOUSE_X2,
            _ => -1,
        };
        if (key >= 0) return true;
        if (!ScancodeMap.TryGet(code, out var scancode, out var extended)) return false;
        key = scancode | (extended ? EXTENDED_BIT : 0);
        return true;
    }

    // The mouse's gesture button opens Task View as Win+Tab, pressed and released at once like a media key.
    private long SendTaskView()
    {
        lock (_gate)
        {
            Send(LEFT_WIN, up: false);
            var attached = _attached;
            Send(TAB, up: false);
            Send(TAB, up: true);
            Send(LEFT_WIN, up: true);
            return attached;
        }
    }

    // Press only; nothing is held, so a release or RELEASE_ALL has nothing to undo. Volume is system wide and, as with
    // a PC keyboard, works on the lock screen too; track keys act on the user's desktop only.
    private long SendMedia(MediaKey media)
    {
        lock (_gate)
        {
            var volume = media is MediaKey.Mute or MediaKey.VolumeDown or MediaKey.VolumeUp;
            if (!volume && desktop.InputDesktopName() is var name && name != USER_DESKTOP)
            {
                log?.Invoke(false, $"media={media} path=skipped desktop={name}");
                return 0;
            }
            _attached = Stopwatch.GetTimestamp();
            var (ok, detail) = desktop.Media(media);
            log?.Invoke(!ok, $"media={media} {detail}");
            return _attached;
        }
    }

    /// <summary>Runs every 500 ms: reports desktop name changes and lifts a stuck "not writable" once the name changes.</summary>
    internal void Watch()
    {
        var name = desktop.InputDesktopName();
        lock (_gate)
        {
            var changed = !string.Equals(name, _lastDesktop, StringComparison.Ordinal);
            _lastDesktop = name;
            if (!_writable && !string.Equals(name, _stuckDesktop, StringComparison.Ordinal))
            {
                ReleaseModifiersLocked();
                MakeWritable(name);
            }
            else if (changed && _writable)
            {
                report(true, name);
            }
        }
    }

    /// <summary>Releases every key and mouse button this agent pressed plus all modifiers and lifts a stuck "not writable" (client disconnected).</summary>
    internal void ReleaseAll()
    {
        lock (_gate)
        {
            ReleaseModifiersLocked();
            for (var key = 0; key < _held.Length; key++)
            {
                if (!_held[key]) continue;
                _held[key] = false;
                Send(key, up: true);
            }
            if (!_writable) MakeWritable(desktop.InputDesktopName());
        }
    }

    /// <summary>Sends key-up for left and right Ctrl, Alt, Shift and Win.</summary>
    internal void ReleaseModifiers()
    {
        lock (_gate) ReleaseModifiersLocked();
    }

    private void ReleaseModifiersLocked()
    {
        foreach (var key in MODIFIERS)
        {
            _held[key] = false;
            Send(key, up: true);
        }
    }

    private void Send(int key, bool up)
    {
        desktop.AttachToInputDesktop(force: false);
        // Latency breakdown (finish line 7): the desktop check ends here; a retry below counts as SendInput time.
        _attached = Stopwatch.GetTimestamp();
        if (SendOnce(key, up)) return;
        // SendInput refuses input aimed at a desktop that stopped being the input desktop: reopen it and retry once.
        if ((desktop.AttachToInputDesktop(force: true) && SendOnce(key, up)) || !_writable) return;
        _writable = false;
        _stuckDesktop = desktop.InputDesktopName();
        report(false, _stuckDesktop);
    }

    private bool SendOnce(int key, bool up)
    {
        if (key == MOUSE_MIDDLE) return desktop.SendMouse(up ? User32.MOUSEEVENTF_MIDDLEUP : User32.MOUSEEVENTF_MIDDLEDOWN, 0);
        if (key > MOUSE_MIDDLE)
        {
            return desktop.SendMouse(up ? User32.MOUSEEVENTF_XUP : User32.MOUSEEVENTF_XDOWN, key == MOUSE_X1 ? User32.XBUTTON1 : User32.XBUTTON2);
        }
        var flags = User32.KEYEVENTF_SCANCODE
            | (key >= EXTENDED_BIT ? User32.KEYEVENTF_EXTENDEDKEY : 0)
            | (up ? User32.KEYEVENTF_KEYUP : 0);
        return desktop.SendKey((ushort)(key & 0xFF), flags);
    }

    private void MakeWritable(string name)
    {
        _writable = true;
        _stuckDesktop = null;
        report(true, name);
    }
}
