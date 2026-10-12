using KeyRelay.Service.Setup;
using KeyRelay.Tests.TestSupport;
using Microsoft.Win32;

namespace KeyRelay.Tests.Setup;

[TestClass]
public sealed class SasPolicyTests : IDisposable
{
    private readonly TempDirectory _directory = new();
    private readonly string _keyPath = $@"Software\KeyRelayTests\{Guid.NewGuid():N}";
    private readonly RegistryKey _policy;

    public SasPolicyTests() => _policy = Registry.CurrentUser.CreateSubKey(_keyPath);

    private string Record => Path.Combine(_directory.Path, "install.json");

    public void Dispose()
    {
        _policy.Dispose();
        Registry.CurrentUser.DeleteSubKeyTree(_keyPath, throwOnMissingSubKey: false);
        _directory.Dispose();
    }

    [TestMethod]
    public void Apply_ValueAbsent_SetsServicesBitAndRestoreDeletesIt()
    {
        SasPolicy.Apply(_policy, Record);
        var applied = _policy.GetValue(SasPolicy.VALUE);
        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(1, applied);
        Assert.IsNull(_policy.GetValue(SasPolicy.VALUE));
        Assert.IsFalse(File.Exists(Record));
    }

    [TestMethod]
    public void Apply_EaseOfAccessOnly_AddsServicesBitAndRestoreReturnsTwo()
    {
        _policy.SetValue(SasPolicy.VALUE, 2, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        var applied = _policy.GetValue(SasPolicy.VALUE);
        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(3, applied);
        Assert.AreEqual(2, _policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Apply_ServicesAlreadyAllowed_ChangesNothingAndRestoreKeepsValue()
    {
        _policy.SetValue(SasPolicy.VALUE, 1, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        SasPolicy.Restore(_policy, Record);

        Assert.IsFalse(File.Exists(Record));
        Assert.AreEqual(1, _policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Apply_ValueZero_RestoreReturnsZero()
    {
        _policy.SetValue(SasPolicy.VALUE, 0, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        var applied = _policy.GetValue(SasPolicy.VALUE);
        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(1, applied);
        Assert.AreEqual(0, _policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Apply_SecondInstallAfterUserReset_KeepsFirstRecord()
    {
        SasPolicy.Apply(_policy, Record);
        _policy.SetValue(SasPolicy.VALUE, 0, RegistryValueKind.DWord);

        SasPolicy.Apply(_policy, Record);
        SasPolicy.Restore(_policy, Record);

        Assert.IsNull(_policy.GetValue(SasPolicy.VALUE));
    }

    [TestMethod]
    public void Restore_WithoutRecord_DoesNothing()
    {
        _policy.SetValue(SasPolicy.VALUE, 0, RegistryValueKind.DWord);

        SasPolicy.Restore(_policy, Record);

        Assert.AreEqual(0, _policy.GetValue(SasPolicy.VALUE));
    }
}
