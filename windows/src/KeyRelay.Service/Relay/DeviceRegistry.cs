using System.Security.Cryptography;
using System.Text.Json;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>One paired tablet as stored in devices.json; only the SHA-256 of its token is kept.</summary>
internal sealed record DeviceRecord(string Id, string Name, string TokenHash, DateTimeOffset AddedAt, DateTimeOffset? LastSeenAt);

/// <summary>Paired devices in ProgramData\KeyRelay\devices.json.</summary>
internal sealed partial class DeviceRegistry(string directory, TimeProvider time, ILogger logger)
{
    private readonly string _path = Path.Combine(directory, "devices.json");
    private readonly Lock _gate = new();
    private List<DeviceRecord>? _devices;

    /// <summary>Gets the file an unreadable devices.json was moved to, or null; the list then started empty.</summary>
    internal string? SetAside { get; private set; }

    /// <summary>Registers a device.</summary>
    /// <returns>Its id and token; the token is not stored and cannot be read back.</returns>
    internal (byte[] Id, byte[] Token) Add(string name)
    {
        var id = RandomNumberGenerator.GetBytes(Frames.ID_SIZE);
        var token = RandomNumberGenerator.GetBytes(Frames.TOKEN_SIZE);
        var record = new DeviceRecord(Convert.ToHexStringLower(id), name, Convert.ToBase64String(SHA256.HashData(token)), time.GetUtcNow(), null);
        lock (_gate)
        {
            Devices().Add(record);
            JsonFile.Write(_path, Devices());
        }
        return (id, token);
    }

    /// <summary>Checks HELLO credentials; the token hash is compared in constant time.</summary>
    /// <returns>The device, or null when the id is unknown or the token does not match.</returns>
    internal DeviceRecord? Verify(ReadOnlySpan<byte> id, ReadOnlySpan<byte> token)
    {
        Span<byte> hash = stackalloc byte[SHA256.HashSizeInBytes];
        Span<byte> stored = stackalloc byte[SHA256.HashSizeInBytes];
        SHA256.HashData(token, hash);
        var key = Convert.ToHexStringLower(id);
        DeviceRecord? device;
        lock (_gate) device = Devices().Find(d => d.Id == key);
        return device is not null
            && Convert.TryFromBase64String(device.TokenHash, stored, out var length)
            && length == stored.Length
            && CryptographicOperations.FixedTimeEquals(hash, stored) ? device : null;
    }

    /// <summary>Records that the device connected now.</summary>
    internal void Touch(string id)
    {
        lock (_gate)
        {
            var devices = Devices();
            var index = devices.FindIndex(d => d.Id == id);
            if (index < 0) return;
            devices[index] = devices[index] with { LastSeenAt = time.GetUtcNow() };
            JsonFile.Write(_path, devices);
        }
    }

    /// <returns>false when no device has this id.</returns>
    internal bool Revoke(string id)
    {
        lock (_gate)
        {
            if (Devices().RemoveAll(d => d.Id == id) == 0) return false;
            JsonFile.Write(_path, Devices());
            return true;
        }
    }

    internal List<DeviceRecord> List()
    {
        lock (_gate) return [.. Devices()];
    }

    private List<DeviceRecord> Devices() => _devices ??= Load();

    // An unreadable file (e.g. empty after a power cut) is kept under a new name and the service goes on with no
    // devices: nothing is lost, unknown tablets stay locked out, and pairing again works without manual repair.
    private List<DeviceRecord> Load()
    {
        try
        {
            return JsonFile.Read<List<DeviceRecord>>(_path) ?? [];
        }
        catch (JsonException ex)
        {
            SetAside = $"{_path}.{time.GetUtcNow():yyyyMMddHHmmss}.bak";
            File.Move(_path, SetAside);
            LogSetAside(logger, ex, SetAside);
            return [];
        }
    }

    [LoggerMessage(Level = LogLevel.Warning, Message = "devices.json could not be read; moved to {SetAside}, paired devices must pair again")]
    private static partial void LogSetAside(ILogger logger, Exception ex, string setAside);
}
