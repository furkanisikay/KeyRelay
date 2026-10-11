using System.Globalization;
using System.Text.Json;

namespace KeyRelay.Service.Settings;

/// <summary>Service settings in ProgramData\KeyRelay\settings.json; the defaults live here and nowhere else.</summary>
internal sealed record ServiceSettings
{
    private static readonly int[] FUNNEL_PORTS = [443, 8443, 10000];

    /// <summary>TCP port of the TLS listener.</summary>
    public int Port { get; init; } = 47111;

    /// <summary>Seconds of silence after which the service closes a tablet connection (protocol idleTimeout).</summary>
    public int IdleTimeoutSeconds { get; init; } = 20;

    /// <summary>Tailscale Funnel address (host:port) put first into the pairing QR; empty when Funnel is not used.</summary>
    public string FunnelAddress { get; init; } = "";

    /// <summary>Checks a Funnel address: empty, or DNS name and a port Funnel can expose (443, 8443, 10000).</summary>
    internal static bool IsValidFunnelAddress(string address)
    {
        if (address.Length == 0) return true;
        var colon = address.LastIndexOf(':');
        return colon > 0
            && Uri.CheckHostName(address[..colon]) == UriHostNameType.Dns
            && int.TryParse(address.AsSpan(colon + 1), NumberStyles.None, CultureInfo.InvariantCulture, out var port)
            && FUNNEL_PORTS.Contains(port);
    }
}

/// <summary>Reads and writes settings.json; a missing or unreadable file means defaults.</summary>
internal sealed class SettingsStore(string directory)
{
    private readonly string _path = Path.Combine(directory, "settings.json");
    private readonly Lock _gate = new();

    internal ServiceSettings Load()
    {
        var defaults = new ServiceSettings();
        ServiceSettings? stored;
        try
        {
            lock (_gate) stored = JsonFile.Read<ServiceSettings>(_path);
        }
        catch (JsonException)
        {
            return defaults;
        }
        if (stored is null) return defaults;
        // A hand-edited file must not stop the service: broken syntax and a bad port (throws outside the bind retry)
        // fall back to defaults, a zero or negative timeout would close every session the moment it opens.
        if (stored.Port is <= 0 or > 65535) stored = stored with { Port = defaults.Port };
        if (stored.FunnelAddress is null || !ServiceSettings.IsValidFunnelAddress(stored.FunnelAddress)) stored = stored with { FunnelAddress = defaults.FunnelAddress };
        return stored.IdleTimeoutSeconds > 0 ? stored : stored with { IdleTimeoutSeconds = defaults.IdleTimeoutSeconds };
    }

    internal void Save(ServiceSettings settings)
    {
        lock (_gate) JsonFile.Write(_path, settings);
    }
}
