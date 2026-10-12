using System.Runtime.InteropServices;
using KeyRelay.Service.Native;

namespace KeyRelay.Tests;

[TestClass]
public sealed class NativeLayoutTests
{
    [TestMethod]
    public void InputStruct_64Bit_MatchesWin32Layout()
    {
        // 64-bit INPUT: DWORD type, 4 bytes padding, then the union; SendInput rejects any other cbSize.
        Assert.AreEqual(40, Marshal.SizeOf<User32.Input>());
        Assert.AreEqual(8, Marshal.OffsetOf<User32.Input>(nameof(User32.Input.Union)).ToInt32());
        Assert.AreEqual(24, Marshal.SizeOf<User32.KeyboardInput>());
    }
}
