namespace KeyRelay.Protocol;

/// <summary>Maps Linux evdev key codes to PC set-1 scancodes for SendInput.</summary>
public static class ScancodeMap
{
    // Linux KEY_* codes 1..88 equal set-1 scancodes; the rest below are E0-prefixed keys (prototype kbdrecv.py table).
    private const ushort LAST_DIRECT = 88;

    /// <summary>Looks up the scancode of a Linux key code.</summary>
    /// <param name="code">evdev key code from a KEY frame.</param>
    /// <param name="scancode">Set-1 scancode without the E0 prefix.</param>
    /// <param name="extended">True for E0-prefixed keys (KEYEVENTF_EXTENDEDKEY).</param>
    /// <returns>false for keys without a set-1 equivalent; such keys are dropped.</returns>
    public static bool TryGet(ushort code, out ushort scancode, out bool extended)
    {
        if (code is >= 1 and <= LAST_DIRECT)
        {
            scancode = code;
            extended = false;
            return true;
        }
        scancode = code switch
        {
            96 => 0x1C, // KP enter
            97 => 0x1D, // right Ctrl
            98 => 0x35, // KP slash
            99 => 0x37, // Print Screen
            100 => 0x38, // right Alt (AltGr)
            102 => 0x47, // Home
            103 => 0x48, // Up
            104 => 0x49, // Page Up
            105 => 0x4B, // Left
            106 => 0x4D, // Right
            107 => 0x4F, // End
            108 => 0x50, // Down
            109 => 0x51, // Page Down
            110 => 0x52, // Insert
            111 => 0x53, // Delete
            113 => 0x20, // Mute
            114 => 0x2E, // Volume down
            115 => 0x30, // Volume up
            125 => 0x5B, // left Win
            126 => 0x5C, // right Win
            127 => 0x5D, // Menu
            163 => 0x19, // Next track
            164 => 0x22, // Play/Pause
            165 => 0x10, // Previous track
            166 => 0x24, // Stop
            _ => 0,
        };
        extended = scancode != 0;
        return extended;
    }
}
