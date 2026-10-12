using System.Globalization;
using System.Runtime.InteropServices;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>IInputDesktop over user32. SetThreadDesktop is per thread, so each thread keeps its own desktop handle.</summary>
internal sealed class NativeInputDesktop : IInputDesktop
{
    [ThreadStatic]
    private static nint _threadDesktop;

    [ThreadStatic]
    private static string? _threadDesktopName;

    public string InputDesktopName()
    {
        var desk = User32.OpenInputDesktop(0, false, User32.DESKTOP_READOBJECTS);
        if (desk == 0) return "";
        try
        {
            return User32.DesktopName(desk);
        }
        finally
        {
            User32.CloseDesktop(desk);
        }
    }

    public bool AttachToInputDesktop(bool force)
    {
        // The check before every key reads only the name, so it asks for read access; full access only to switch.
        if (!force && _threadDesktopName is not null)
        {
            var current = User32.OpenInputDesktop(0, false, User32.DESKTOP_READOBJECTS);
            if (current != 0)
            {
                var same = User32.DesktopNameEquals(current, _threadDesktopName);
                User32.CloseDesktop(current);
                if (same) return true;
            }
        }
        var desk = User32.OpenInputDesktop(0, false, User32.GENERIC_ALL);
        if (desk == 0) return false;
        if (!User32.SetThreadDesktop(desk))
        {
            User32.CloseDesktop(desk);
            return false;
        }
        if (_threadDesktop != 0) User32.CloseDesktop(_threadDesktop);
        _threadDesktop = desk;
        _threadDesktopName = User32.DesktopName(desk);
        return true;
    }

    public unsafe bool SendKey(ushort scancode, uint flags)
    {
        var input = new User32.Input
        {
            Type = User32.INPUT_KEYBOARD,
            Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Scan = scancode, Flags = flags } },
        };
        return User32.SendInput(1, &input, sizeof(User32.Input)) == 1;
    }

    public unsafe bool SendMouse(uint flags, uint data)
    {
        var input = new User32.Input
        {
            Type = User32.INPUT_MOUSE,
            Union = new User32.InputUnion { Mouse = new User32.MouseInput { MouseData = data, Flags = flags } },
        };
        return User32.SendInput(1, &input, sizeof(User32.Input)) == 1;
    }

    public bool LockWorkStation() => User32.LockWorkStation();

    /// <summary>Presses and releases the media key's virtual key (no scancode) on the input desktop; Windows shows its volume/media overlay.</summary>
    public unsafe (bool Ok, string Detail) Media(MediaKey key)
    {
        var vk = VirtualKey(key);
        var attached = AttachToInputDesktop(force: false);
        var inputs = stackalloc User32.Input[2];
        inputs[0] = new User32.Input { Type = User32.INPUT_KEYBOARD, Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Vk = vk, Flags = User32.KEYEVENTF_EXTENDEDKEY } } };
        inputs[1] = new User32.Input { Type = User32.INPUT_KEYBOARD, Union = new User32.InputUnion { Keyboard = new User32.KeyboardInput { Vk = vk, Flags = User32.KEYEVENTF_EXTENDEDKEY | User32.KEYEVENTF_KEYUP } } };
        var sent = User32.SendInput(2, inputs, sizeof(User32.Input));
        var error = sent == 2 ? 0 : Marshal.GetLastPInvokeError();
        return (sent == 2, string.Create(CultureInfo.InvariantCulture, $"vk=0x{vk:X2} desktop={_threadDesktopName} attached={attached} sent={sent} error={error}"));
    }

    private static ushort VirtualKey(MediaKey key) => key switch
    {
        MediaKey.Mute => User32.VK_VOLUME_MUTE,
        MediaKey.VolumeDown => User32.VK_VOLUME_DOWN,
        MediaKey.VolumeUp => User32.VK_VOLUME_UP,
        MediaKey.NextTrack => User32.VK_MEDIA_NEXT_TRACK,
        MediaKey.PreviousTrack => User32.VK_MEDIA_PREV_TRACK,
        MediaKey.Stop => User32.VK_MEDIA_STOP,
        _ => User32.VK_MEDIA_PLAY_PAUSE,
    };
}
