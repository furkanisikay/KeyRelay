using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class ScancodeMapTests
{
    [TestMethod]
    [DataRow((ushort)30, DisplayName = "KEY_A")]
    [DataRow((ushort)15, DisplayName = "KEY_TAB")]
    [DataRow((ushort)56, DisplayName = "KEY_LEFTALT")]
    [DataRow((ushort)23, DisplayName = "Turkish Q dotless i")]
    [DataRow((ushort)26, DisplayName = "Turkish Q g with breve")]
    [DataRow((ushort)27, DisplayName = "Turkish Q u with diaeresis")]
    [DataRow((ushort)39, DisplayName = "Turkish Q s with cedilla")]
    [DataRow((ushort)40, DisplayName = "Turkish Q dotted i")]
    [DataRow((ushort)51, DisplayName = "Turkish Q o with diaeresis")]
    [DataRow((ushort)52, DisplayName = "Turkish Q c with cedilla")]
    [DataRow((ushort)86, DisplayName = "KEY_102ND")]
    [DataRow((ushort)88, DisplayName = "KEY_F12")]
    public void TryGet_DirectRange_ReturnsSameCodeNotExtended(ushort code)
    {
        var found = ScancodeMap.TryGet(code, out var scancode, out var extended);

        Assert.IsTrue(found);
        Assert.AreEqual(code, scancode);
        Assert.IsFalse(extended);
    }

    [TestMethod]
    [DataRow((ushort)100, (ushort)0x38, DisplayName = "KEY_RIGHTALT (AltGr)")]
    [DataRow((ushort)97, (ushort)0x1D, DisplayName = "KEY_RIGHTCTRL")]
    [DataRow((ushort)125, (ushort)0x5B, DisplayName = "KEY_LEFTMETA (Win)")]
    [DataRow((ushort)126, (ushort)0x5C, DisplayName = "KEY_RIGHTMETA")]
    [DataRow((ushort)111, (ushort)0x53, DisplayName = "KEY_DELETE")]
    [DataRow((ushort)99, (ushort)0x37, DisplayName = "KEY_SYSRQ (Print Screen)")]
    [DataRow((ushort)113, (ushort)0x20, DisplayName = "KEY_MUTE")]
    [DataRow((ushort)114, (ushort)0x2E, DisplayName = "KEY_VOLUMEDOWN")]
    [DataRow((ushort)115, (ushort)0x30, DisplayName = "KEY_VOLUMEUP")]
    [DataRow((ushort)163, (ushort)0x19, DisplayName = "KEY_NEXTSONG")]
    [DataRow((ushort)164, (ushort)0x22, DisplayName = "KEY_PLAYPAUSE")]
    [DataRow((ushort)165, (ushort)0x10, DisplayName = "KEY_PREVIOUSSONG")]
    [DataRow((ushort)166, (ushort)0x24, DisplayName = "KEY_STOPCD")]
    public void TryGet_ExtendedKey_ReturnsE0Scancode(ushort code, ushort expected)
    {
        var found = ScancodeMap.TryGet(code, out var scancode, out var extended);

        Assert.IsTrue(found);
        Assert.AreEqual(expected, scancode);
        Assert.IsTrue(extended);
    }

    [TestMethod]
    [DataRow((ushort)0, DisplayName = "KEY_RESERVED")]
    [DataRow((ushort)89, DisplayName = "KEY_RO")]
    [DataRow((ushort)119, DisplayName = "KEY_PAUSE (E1 sequence, not supported)")]
    [DataRow((ushort)240, DisplayName = "KEY_UNKNOWN")]
    [DataRow(ushort.MaxValue, DisplayName = "max")]
    public void TryGet_UnmappedKey_ReturnsFalse(ushort code)
    {
        Assert.IsFalse(ScancodeMap.TryGet(code, out _, out _));
    }
}
