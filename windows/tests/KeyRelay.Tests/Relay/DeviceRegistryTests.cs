using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;
using Microsoft.Extensions.Logging.Abstractions;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class DeviceRegistryTests : IDisposable
{
    private readonly TempDirectory _directory = new();
    private readonly ManualTime _time = new(new DateTimeOffset(2026, 10, 8, 10, 0, 0, TimeSpan.Zero));

    public void Dispose() => _directory.Dispose();

    private DeviceRegistry Create() => new(_directory.Path, _time, NullLogger.Instance);

    [TestMethod]
    public void Verify_TokenFromAdd_ReturnsDevice()
    {
        var registry = Create();
        var (id, token) = registry.Add("Tab");

        var device = registry.Verify(id, token);

        Assert.AreEqual("Tab", device?.Name);
        Assert.AreEqual(Convert.ToHexStringLower(id), device?.Id);
    }

    [TestMethod]
    public void Verify_OneBitWrongToken_ReturnsNull()
    {
        var registry = Create();
        var (id, token) = registry.Add("Tab");
        token[31] ^= 0x01;

        Assert.IsNull(registry.Verify(id, token));
    }

    [TestMethod]
    public void Verify_UnknownId_ReturnsNull()
    {
        var registry = Create();
        var (_, token) = registry.Add("Tab");

        Assert.IsNull(registry.Verify(new byte[16], token));
    }

    [TestMethod]
    public void Revoke_ThenVerify_ReturnsNull()
    {
        var registry = Create();
        var (id, token) = registry.Add("Tab");

        var revoked = registry.Revoke(Convert.ToHexStringLower(id));

        Assert.IsTrue(revoked);
        Assert.IsNull(registry.Verify(id, token));
        Assert.IsNull(Create().Verify(id, token));
    }

    [TestMethod]
    [DataRow("{ not json", DisplayName = "corrupt")]
    [DataRow("", DisplayName = "empty (power cut)")]
    public void Add_UnreadableFile_KeepsItAsideAndStartsEmpty(string content)
    {
        var path = Path.Combine(_directory.Path, "devices.json");
        File.WriteAllText(path, content);
        var registry = Create();

        registry.Add("Tab");

        Assert.IsNotNull(registry.SetAside);
        Assert.AreEqual(content, File.ReadAllText(registry.SetAside));
        Assert.AreEqual("Tab", Create().List().Single().Name);
    }

    [TestMethod]
    public void List_ReadableFile_SetsNothingAside()
    {
        var registry = Create();
        registry.Add("Tab");

        Assert.HasCount(1, Create().List());
        Assert.IsNull(registry.SetAside);
        Assert.IsEmpty(Directory.GetFiles(_directory.Path, "*.bak"));
    }

    [TestMethod]
    public void Revoke_UnknownId_ReturnsFalse()
    {
        Assert.IsFalse(Create().Revoke("00"));
    }

    [TestMethod]
    public void Add_NewInstanceOnSameDirectory_StillVerifies()
    {
        var (id, token) = Create().Add("Tab");

        var device = Create().Verify(id, token);

        Assert.IsNotNull(device);
    }

    [TestMethod]
    public void Add_File_DoesNotContainTheToken()
    {
        var (_, token) = Create().Add("Tab");

        var text = File.ReadAllText(Path.Combine(_directory.Path, "devices.json"));

        Assert.IsFalse(text.Contains(Convert.ToBase64String(token), StringComparison.Ordinal));
        Assert.IsFalse(text.Contains(Convert.ToHexStringLower(token), StringComparison.Ordinal));
    }

    [TestMethod]
    public void Touch_KnownDevice_SetsLastSeen()
    {
        var registry = Create();
        var (id, _) = registry.Add("Tab");
        _time.Advance(TimeSpan.FromHours(1));

        registry.Touch(Convert.ToHexStringLower(id));

        Assert.AreEqual(_time.Now, registry.List().Single().LastSeenAt);
    }
}
