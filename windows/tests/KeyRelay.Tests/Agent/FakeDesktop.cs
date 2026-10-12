using KeyRelay.Service.Agent;

namespace KeyRelay.Tests.Agent;

/// <summary>Input desktop stand-in: SendKey succeeds only on the current input desktop and when that desktop accepts input.</summary>
internal sealed class FakeDesktop : IInputDesktop
{
    private readonly Lock _gate = new();
    private readonly HashSet<string> _rejecting = [];
    private readonly List<(ushort Scancode, uint Flags, string Desktop)> _sent = [];
    private readonly List<MediaKey> _media = [];
    private readonly List<(uint Flags, uint Data, string Desktop)> _mouse = [];
    private string _input = "Default";
    private string _attached = "Default";
    private int _rejectNext;
    private int _forcedAttaches;
    private int _locks;
    private bool _attachFails;

    internal string Input
    {
        get { lock (_gate) return _input; }
        set { lock (_gate) _input = value; }
    }

    internal int ForcedAttaches { get { lock (_gate) return _forcedAttaches; } }

    internal int Locks { get { lock (_gate) return _locks; } }

    /// <summary>Makes AttachToInputDesktop fail and leave the thread on its old desktop.</summary>
    internal bool AttachFails { set { lock (_gate) _attachFails = value; } }

    /// <summary>Makes SendKey fail on this desktop even when the thread is attached to it.</summary>
    internal void Reject(string desktop) { lock (_gate) _rejecting.Add(desktop); }

    /// <summary>Makes the next <paramref name="count"/> SendKey calls fail.</summary>
    internal void RejectNext(int count) { lock (_gate) _rejectNext = count; }

    internal (ushort Scancode, uint Flags, string Desktop)[] SentSnapshot() { lock (_gate) return [.. _sent]; }

    internal void ClearSent() { lock (_gate) _sent.Clear(); }

    public string InputDesktopName() { lock (_gate) return _input; }

    public bool AttachToInputDesktop(bool force)
    {
        lock (_gate)
        {
            if (force) _forcedAttaches++;
            if (_attachFails) return false;
            _attached = _input;
            return true;
        }
    }

    public bool SendKey(ushort scancode, uint flags)
    {
        lock (_gate)
        {
            if (!AcceptsLocked()) return false;
            _sent.Add((scancode, flags, _attached));
            return true;
        }
    }

    public bool SendMouse(uint flags, uint data)
    {
        lock (_gate)
        {
            if (!AcceptsLocked()) return false;
            _mouse.Add((flags, data, _attached));
            return true;
        }
    }

    /// <summary>Mouse button events sent, in order.</summary>
    internal (uint Flags, uint Data, string Desktop)[] MouseSnapshot() { lock (_gate) return [.. _mouse]; }

    private bool AcceptsLocked()
    {
        if (_rejectNext > 0)
        {
            _rejectNext--;
            return false;
        }
        return _attached == _input && !_rejecting.Contains(_attached);
    }

    /// <summary>Gets or sets what the next media calls report.</summary>
    internal (bool Ok, string Detail) MediaResult { get; set; } = (true, "fake");

    public (bool Ok, string Detail) Media(MediaKey key)
    {
        lock (_gate) _media.Add(key);
        return MediaResult;
    }

    /// <summary>Media keys performed, in order.</summary>
    internal MediaKey[] MediaSnapshot() { lock (_gate) return [.. _media]; }

    public bool LockWorkStation()
    {
        lock (_gate) _locks++;
        return true;
    }
}
