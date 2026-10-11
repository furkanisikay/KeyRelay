using System.Buffers.Text;
using KeyRelay.Protocol;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class PairingIdentityTests
{
    [TestMethod]
    public void IdentityCode_KnownFingerprint_MatchesVector()
    {
        // Shared vector in docs/protocol.md; Android must produce the same code.
        var fingerprint = Base64Url.DecodeFromChars("nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA");

        Assert.AreEqual("TYIM-LHQO", PairingIdentity.IdentityCode(fingerprint));
    }

    [TestMethod]
    [DataRow(0)]
    [DataRow(5)]
    [DataRow(31)]
    [DataRow(33)]
    public void IdentityCode_WrongLength_Throws(int length)
    {
        Assert.ThrowsExactly<ArgumentException>(() => PairingIdentity.IdentityCode(new byte[length]));
    }
}
