using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class User32
{
    internal const uint INPUT_KEYBOARD = 1;
    internal const uint KEYEVENTF_EXTENDEDKEY = 0x0001;
    internal const uint KEYEVENTF_KEYUP = 0x0002;
    internal const uint KEYEVENTF_SCANCODE = 0x0008;
    // Media keys as virtual keys (WinUser.h).
    internal const ushort VK_VOLUME_MUTE = 0xAD;
    internal const ushort VK_VOLUME_DOWN = 0xAE;
    internal const ushort VK_VOLUME_UP = 0xAF;
    internal const ushort VK_MEDIA_NEXT_TRACK = 0xB0;
    internal const ushort VK_MEDIA_PREV_TRACK = 0xB1;
    internal const ushort VK_MEDIA_STOP = 0xB2;
    internal const ushort VK_MEDIA_PLAY_PAUSE = 0xB3;

    [StructLayout(LayoutKind.Sequential)]
    internal struct KeyboardInput
    {
        public ushort Vk;
        public ushort Scan;
        public uint Flags;
        public uint Time;
        public nint ExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct MouseInput
    {
        public int Dx;
        public int Dy;
        public uint MouseData;
        public uint Flags;
        public uint Time;
        public nint ExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    internal struct InputUnion
    {
        [FieldOffset(0)] public MouseInput Mouse;
        [FieldOffset(0)] public KeyboardInput Keyboard;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct Input
    {
        public uint Type;
        public InputUnion Union;
    }

    [LibraryImport("user32.dll", SetLastError = true)]
    internal static unsafe partial uint SendInput(uint count, Input* inputs, int size);

    internal const uint GENERIC_ALL = 0x10000000;
    internal const int UOI_NAME = 2;
    internal const uint DESKTOP_READOBJECTS = 0x0001;
    private const int NAME_CHARS = 64;

    [LibraryImport("user32.dll", SetLastError = true)]
    internal static partial nint OpenInputDesktop(uint flags, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint access);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool SetThreadDesktop(nint desktop);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool CloseDesktop(nint desktop);

    [LibraryImport("user32.dll", SetLastError = true, EntryPoint = "GetUserObjectInformationW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static unsafe partial bool GetUserObjectInformation(nint obj, int index, char* buffer, uint length, out uint needed);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool LockWorkStation();

    internal static unsafe string DesktopName(nint desktop)
    {
        char* buffer = stackalloc char[NAME_CHARS];
        return GetUserObjectInformation(desktop, UOI_NAME, buffer, NAME_CHARS * sizeof(char), out _) ? new string(buffer) : "?";
    }

    /// <summary>Compares a desktop's name without allocating; runs before every injected key.</summary>
    internal static unsafe bool DesktopNameEquals(nint desktop, string name)
    {
        char* buffer = stackalloc char[NAME_CHARS];
        return GetUserObjectInformation(desktop, UOI_NAME, buffer, NAME_CHARS * sizeof(char), out _)
            && MemoryMarshal.CreateReadOnlySpanFromNullTerminated(buffer).SequenceEqual(name);
    }
}
