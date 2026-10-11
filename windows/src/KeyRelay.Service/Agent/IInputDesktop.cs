namespace KeyRelay.Service.Agent;

/// <summary>The Windows calls the agent needs, behind an interface so the writability rule can be tested without a desktop.</summary>
internal interface IInputDesktop
{
    /// <summary>Gets the name of the current input desktop (Default, Winlogon, Screen-saver).</summary>
    /// <returns>The name, or an empty string when the input desktop cannot be opened.</returns>
    string InputDesktopName();

    /// <summary>Moves the calling thread to the current input desktop.</summary>
    /// <param name="force">Reopen and switch even when the thread already seems to be there.</param>
    /// <returns>true when the calling thread is on the current input desktop afterwards.</returns>
    bool AttachToInputDesktop(bool force);

    /// <summary>Calls SendInput with exactly one keyboard event.</summary>
    /// <returns>true when Windows accepted the event.</returns>
    bool SendKey(ushort scancode, uint flags);

    /// <summary>Presses and releases a media key as a virtual key through SendInput.</summary>
    /// <returns>Whether SendInput took both events, and its results for the service log (never a typed key).</returns>
    (bool Ok, string Detail) Media(MediaKey key);

    /// <summary>Locks the workstation (an injected Win+L is ignored by Windows).</summary>
    bool LockWorkStation();
}

/// <summary>Media keys of a tablet keyboard (evdev 113-115, 163-166).</summary>
internal enum MediaKey
{
    None = 0,
    Mute,
    VolumeDown,
    VolumeUp,
    NextTrack,
    PlayPause,
    PreviousTrack,
    Stop,
}
