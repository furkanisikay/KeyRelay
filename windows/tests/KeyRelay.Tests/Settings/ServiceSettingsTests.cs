using KeyRelay.Service.Settings;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Settings;

[TestClass]
public sealed class ServiceSettingsTests : IDisposable
{
    private readonly TempDirectory _directory = new();

    public void Dispose() => _directory.Dispose();

    [TestMethod]
    public void Load_NoFile_ReturnsDefaults()
    {
        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(new ServiceSettings { Port = 47111, IdleTimeoutSeconds = 20, FunnelAddress = "" }, settings);
    }

    [TestMethod]
    public void Save_ThenLoad_RoundTrips()
    {
        var store = new SettingsStore(_directory.Path);
        var saved = new ServiceSettings { Port = 47112, IdleTimeoutSeconds = 30, FunnelAddress = "pc.tail.ts.net:8443" };

        store.Save(saved);

        Assert.AreEqual(saved, new SettingsStore(_directory.Path).Load());
    }

    [TestMethod]
    public void Load_FileWithOnlyPort_KeepsOtherDefaults()
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), """{ "port": 47112 }""");

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(47112, settings.Port);
        Assert.AreEqual(20, settings.IdleTimeoutSeconds);
    }

    [TestMethod]
    [DataRow(0)]
    [DataRow(-5)]
    public void Load_NonPositiveIdleTimeout_FallsBackToDefault(int value)
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), $$"""{ "idleTimeoutSeconds": {{value}} }""");

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(20, settings.IdleTimeoutSeconds);
    }

    [TestMethod]
    [DataRow(0)]
    [DataRow(-1)]
    [DataRow(65536)]
    public void Load_PortOutOfRange_FallsBackToDefault(int value)
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), $$"""{ "port": {{value}} }""");

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(47111, settings.Port);
    }

    [TestMethod]
    [DataRow("null")]
    [DataRow("\"pc.tail.ts.net:8080\"")]
    public void Load_InvalidFunnelAddress_FallsBackToDefault(string json)
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), $$"""{ "funnelAddress": {{json}} }""");

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual("", settings.FunnelAddress);
    }

    [TestMethod]
    [DataRow("{ \"port\": ")]
    [DataRow("not json")]
    public void Load_CorruptFile_ReturnsDefaults(string content)
    {
        File.WriteAllText(Path.Combine(_directory.Path, "settings.json"), content);

        var settings = new SettingsStore(_directory.Path).Load();

        Assert.AreEqual(new ServiceSettings(), settings);
    }

    [TestMethod]
    [DataRow("", true)]
    [DataRow("homepc.tailnet-example.ts.net:8443", true)]
    [DataRow("pc.tail.ts.net:443", true)]
    [DataRow("pc.tail.ts.net:10000", true)]
    [DataRow("pc.tail.ts.net:8080", false)]
    [DataRow("100.64.0.10:443", false)]
    [DataRow("pc.tail.ts.net", false)]
    [DataRow(":443", false)]
    [DataRow("a,b.ts.net:443", false)]
    public void IsValidFunnelAddress_Input_MatchesFunnelRules(string address, bool expected)
    {
        Assert.AreEqual(expected, ServiceSettings.IsValidFunnelAddress(address));
    }
}
