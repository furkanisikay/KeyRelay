using System.ServiceProcess;

namespace KeyRelay.Service.Control;

/// <summary>
/// The last key presses for the tray's key strip. Kept in memory only, and only while the tray keeps asking:
/// RECENT_KEYS renews a short lease; RECENT_KEYS_STOP, the lease running out, LOCK and SAS clear everything. Never written to disk or log.
/// Keys typed on another desktop (lock screen, UAC) are never kept and wipe the earlier ones; a new session starts empty.
/// </summary>
/// <param name="inputDesktop">Name of the desktop the agent types into (AgentLink.DesktopName).</param>
/// <param name="keySession">Session the agent types into (AgentSupervisor.ActiveSession).</param>
internal sealed class RecentKeys(TimeProvider time, Func<string> inputDesktop, Func<uint?> keySession) : IDisposable
{
    internal const int CAPACITY = 8;
    // The user's own desktop; Winlogon (lock screen, credential prompts) and others are never shown.
    private const string USER_DESKTOP = "Default";
    // KEY value as in Linux evdev: 1 press (releases and auto-repeats are not shown).
    private const int KEY_PRESS = 1;
    // The tray asks every second while the strip is on; a tray that stopped asking (closed, crashed) ends collection.
    private static readonly TimeSpan LEASE = TimeSpan.FromSeconds(5);
    // The agent polls the input desktop every 500 ms; 1 s covers that delay plus the pipe, so keys typed on a new desktop before its report are dropped.
    private static readonly TimeSpan DESKTOP_REPORT_DELAY = TimeSpan.FromSeconds(1);
    private readonly Lock _gate = new();
    // Times are TimeProvider timestamps (monotonic): a wall-clock change cannot stretch the lease or keep the last second.
    private readonly Queue<(string Name, long Stamp)> _keys = new(CAPACITY + 1);
    private ITimer? _expiry;
    // Set while a lease runs; read without the lock on the key path.
    private volatile bool _collecting;
    private long _leaseStart;
    // Session of the tray that asked last; keys never cross to another session's tray.
    private uint? _session;

    /// <summary>Notes one evdev key event; a single read when nobody asked for keys.</summary>
    internal void Add(ushort code, int value)
    {
        if (value != KEY_PRESS || !_collecting) return;
        lock (_gate)
        {
            if (LeaseLeft() <= TimeSpan.Zero)
            {
                Clear();
                return;
            }
            if (keySession() != _session)
            {
                // Switched user: nothing typed here may reach the tray that asked in the other session.
                Clear();
                return;
            }
            if (inputDesktop() != USER_DESKTOP)
            {
                _keys.Clear();
                return;
            }
            _keys.Enqueue((Name(code), time.GetTimestamp()));
            if (_keys.Count > CAPACITY) _keys.Dequeue();
        }
    }

    /// <summary>The agent reported a new input desktop: keys of the last second may have been typed on it already.</summary>
    internal void OnDesktopChanged()
    {
        lock (_gate)
        {
            var kept = _keys.Where(key => time.GetElapsedTime(key.Stamp) > DESKTOP_REPORT_DELAY).ToList();
            _keys.Clear();
            foreach (var key in kept) _keys.Enqueue(key);
        }
    }

    /// <summary>Starts or renews collection and returns the keys so far, oldest first.</summary>
    /// <param name="session">Session of the asking pipe client; ControlHandler has checked it is the session keys are typed into.</param>
    internal List<string> Take(uint session)
    {
        lock (_gate)
        {
            if (inputDesktop() != USER_DESKTOP)
            {
                // Locked or on a credential prompt: the strip stays empty and nothing earlier survives.
                Clear();
                return [];
            }
            if (LeaseLeft() <= TimeSpan.Zero || session != _session) _keys.Clear();
            _session = session;
            _leaseStart = time.GetTimestamp();
            _collecting = true;
            _expiry ??= time.CreateTimer(_ => Expire(), null, Timeout.InfiniteTimeSpan, Timeout.InfiniteTimeSpan);
            _expiry.Change(LEASE, Timeout.InfiniteTimeSpan);
            return [.. _keys.Select(key => key.Name)];
        }
    }

    /// <summary>Windows reported a session change; locking (Win+L, lock curtain still on Default) or leaving the session stops collection.</summary>
    internal void OnSessionChange(SessionChangeReason reason)
    {
        if (reason is SessionChangeReason.SessionLock or SessionChangeReason.SessionLogoff
            or SessionChangeReason.ConsoleDisconnect or SessionChangeReason.RemoteDisconnect) Stop();
    }

    /// <summary>Stops collection and forgets every key (strip off, tablet sent LOCK or SAS).</summary>
    internal void Stop()
    {
        lock (_gate) Clear();
    }

    public void Dispose()
    {
        lock (_gate)
        {
            Clear();
            _expiry?.Dispose();
        }
    }

    // The lease ends on time even if no key or request comes after it.
    private void Expire()
    {
        lock (_gate)
        {
            var left = LeaseLeft();
            if (left <= TimeSpan.Zero) Clear();
            // Fired early (timer and clock tick differently): wait for the rest.
            else _expiry?.Change(left, Timeout.InfiniteTimeSpan);
        }
    }

    private TimeSpan LeaseLeft() => _collecting ? LEASE - time.GetElapsedTime(_leaseStart) : TimeSpan.Zero;

    private void Clear()
    {
        _keys.Clear();
        _collecting = false;
    }

    /// <summary>Letters, digits and named keys; other keys (punctuation, layout dependent) show as a dot.</summary>
    internal static string Name(ushort code) => code switch
    {
        >= 2 and <= 10 => ((char)('1' + code - 2)).ToString(),
        11 or 82 => "0",
        >= 16 and <= 25 => "QWERTYUIOP"[code - 16].ToString(),
        >= 30 and <= 38 => "ASDFGHJKL"[code - 30].ToString(),
        >= 44 and <= 50 => "ZXCVBNM"[code - 44].ToString(),
        >= 59 and <= 68 => $"F{code - 58}",
        87 => "F11",
        88 => "F12",
        >= 71 and <= 73 => ((char)('7' + code - 71)).ToString(),
        >= 75 and <= 77 => ((char)('4' + code - 75)).ToString(),
        >= 79 and <= 81 => ((char)('1' + code - 79)).ToString(),
        1 => "Esc",
        14 => "⌫",
        15 => "Tab",
        28 or 96 => "⏎",
        29 or 97 => "Ctrl",
        42 or 54 => "⇧",
        56 => "Alt",
        57 => "Space",
        58 => "Caps",
        99 => "PrtSc",
        100 => "AltGr",
        102 => "Home",
        103 => "↑",
        104 => "PgUp",
        105 => "←",
        106 => "→",
        107 => "End",
        108 => "↓",
        109 => "PgDn",
        110 => "Ins",
        111 => "Del",
        125 or 126 => "Win",
        127 => "Menu",
        _ => "•",
    };
}
