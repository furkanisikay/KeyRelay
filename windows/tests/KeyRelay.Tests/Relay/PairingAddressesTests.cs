using System.Net;
using KeyRelay.Service.Relay;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class PairingAddressesTests
{
    // Up IPv4 interfaces seen on homepc on 2026-10-08 (ProtonVPN reports gateway 0.0.0.0, VMnet1 none).
    private static readonly PairingAddresses.InterfaceAddress[] HOMEPC =
    [
        new(IPAddress.Parse("10.2.0.2"), false, ""),
        new(IPAddress.Parse("100.64.0.10"), false, "tailnet-example.ts.net"),
        new(IPAddress.Parse("192.168.1.10"), true, "home"),
        new(IPAddress.Parse("192.168.56.1"), false, ""),
    ];

    [TestMethod]
    public void Order_HomepcInterfaces_KeepsTailscaleAndGatewayLanOnlyFunnelLast()
    {
        var addresses = PairingAddresses.Order(HOMEPC, "homepc.tailnet-example.ts.net:8443", "HOMEPC");

        CollectionAssert.AreEqual(
            new[] { "100.64.0.10", "homepc.tailnet-example.ts.net", "192.168.1.10", "homepc.tailnet-example.ts.net:8443" },
            addresses);
    }

    [TestMethod]
    public void Order_NoFunnelNoTailscale_ReturnsGatewayLanOnly()
    {
        PairingAddresses.InterfaceAddress[] interfaces = [new(IPAddress.Parse("192.168.1.10"), true, "home"), new(IPAddress.Parse("192.168.56.1"), false, "")];

        var addresses = PairingAddresses.Order(interfaces, "", "PC");

        CollectionAssert.AreEqual(new[] { "192.168.1.10" }, addresses);
    }

    [TestMethod]
    public void Order_SameAddressOnTwoInterfaces_IsListedOnce()
    {
        PairingAddresses.InterfaceAddress[] interfaces = [new(IPAddress.Parse("192.168.1.10"), true, ""), new(IPAddress.Parse("192.168.1.10"), true, "")];

        Assert.HasCount(1, PairingAddresses.Order(interfaces, "", "PC"));
    }

    [TestMethod]
    public void BuildUri_Values_EncodesNameAndCode()
    {
        byte[] code = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15];

        var uri = PairingAddresses.BuildUri("NH ROG", ["a.ts.net:443", "1.2.3.4"], 47111, "abc", code);

        Assert.AreEqual("keyrelay://pair?v=1&n=NH%20ROG&a=a.ts.net:443,1.2.3.4&p=47111&fp=abc&c=AAECAwQFBgcICQoLDA0ODw", uri);
    }

    [TestMethod]
    public void Current_ThisMachine_HasNoLoopback()
    {
        Assert.IsFalse(PairingAddresses.Current().Any(a => IPAddress.IsLoopback(a.Address)));
    }
}
