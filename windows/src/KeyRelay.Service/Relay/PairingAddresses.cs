using System.Buffers.Text;
using System.Globalization;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace KeyRelay.Service.Relay;

/// <summary>Chooses the addresses put into the pairing QR and builds the keyrelay:// link (docs/protocol.md).</summary>
internal static class PairingAddresses
{
    /// <summary>An IPv4 address of an up interface.</summary>
    /// <param name="Address">The address.</param>
    /// <param name="HasGateway">The interface has a real IPv4 default gateway (not 0.0.0.0).</param>
    /// <param name="DnsSuffix">Connection DNS suffix; on the Tailscale interface it is the tailnet domain.</param>
    internal sealed record InterfaceAddress(IPAddress Address, bool HasGateway, string DnsSuffix);

    /// <summary>Tailscale IP and MagicDNS name, then LAN addresses of interfaces with a gateway, then the Funnel address.</summary>
    /// <remarks>
    /// Interfaces without a gateway (host-only virtual adapters, some VPNs) would cost the tablet a 2 s timeout each.
    /// Funnel goes last: the tablet pairs over the first address that answers and the service refuses PAIR from Funnel
    /// (loopback). Later HELLOs are not slowed for long, the tablet moves the address that answered to the front.
    /// </remarks>
    internal static List<string> Order(IEnumerable<InterfaceAddress> interfaces, string funnelAddress, string hostName)
    {
        var all = interfaces.ToList();
        var result = new List<string>();
        foreach (var tailscale in all.Where(i => IsTailscale(i.Address)))
        {
            result.Add(tailscale.Address.ToString());
            if (tailscale.DnsSuffix.Length > 0) result.Add($"{hostName.ToLowerInvariant()}.{tailscale.DnsSuffix}");
        }
        result.AddRange(all.Where(i => i.HasGateway && !IsTailscale(i.Address)).Select(i => i.Address.ToString()));
        if (funnelAddress.Length > 0) result.Add(funnelAddress);
        return [.. result.Distinct(StringComparer.OrdinalIgnoreCase)];
    }

    /// <summary>Reads the IPv4 addresses of up, non-loopback interfaces.</summary>
    internal static List<InterfaceAddress> Current()
    {
        var result = new List<InterfaceAddress>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up || nic.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            var properties = nic.GetIPProperties();
            var hasGateway = properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
            result.AddRange(properties.UnicastAddresses
                .Where(u => u.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(u => new InterfaceAddress(u.Address, hasGateway, properties.DnsSuffix)));
        }
        return result;
    }

    /// <summary>keyrelay://pair?v=1&amp;n=..&amp;a=..&amp;p=..&amp;fp=..&amp;c=.. ; only the PC name needs escaping.</summary>
    internal static string BuildUri(string pcName, IReadOnlyList<string> addresses, int port, string fingerprint, ReadOnlySpan<byte> code)
    {
        var encodedCode = Base64Url.EncodeToString(code);
        return string.Create(CultureInfo.InvariantCulture,
            $"keyrelay://pair?v=1&n={Uri.EscapeDataString(pcName)}&a={string.Join(',', addresses)}&p={port}&fp={fingerprint}&c={encodedCode}");
    }

    // Tailscale hands out addresses from the CGNAT range 100.64.0.0/10.
    private static bool IsTailscale(IPAddress address)
    {
        var bytes = address.GetAddressBytes();
        return bytes.Length == 4 && bytes[0] == 100 && (bytes[1] & 0xC0) == 64;
    }
}
