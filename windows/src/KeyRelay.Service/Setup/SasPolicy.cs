using Microsoft.Win32;

namespace KeyRelay.Service.Setup;

/// <summary>What install found in SoftwareSASGeneration, so uninstall restores only what KeyRelay changed.</summary>
/// <param name="SasChanged">Install changed the value.</param>
/// <param name="SasPrevious">The value before install; null when it did not exist.</param>
internal sealed record InstallRecord(bool SasChanged, int? SasPrevious);

/// <summary>Lets services call SendSAS (SoftwareSASGeneration bit 1) and undoes that on uninstall.</summary>
internal static class SasPolicy
{
    internal const string KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System";
    internal const string VALUE = "SoftwareSASGeneration";
    private const int SERVICES = 1;

    /// <summary>Sets the services bit if missing; records the original state only the first time.</summary>
    internal static void Apply(RegistryKey policy, string recordPath)
    {
        var current = policy.GetValue(VALUE) as int?;
        if (current is { } value && (value & SERVICES) != 0) return;
        if (JsonFile.Read<InstallRecord>(recordPath) is not { SasChanged: true }) JsonFile.Write(recordPath, new InstallRecord(true, current));
        policy.SetValue(VALUE, (current ?? 0) | SERVICES, RegistryValueKind.DWord);
    }

    /// <summary>Puts back the value install found (deletes it when it was absent).</summary>
    internal static void Restore(RegistryKey policy, string recordPath)
    {
        if (JsonFile.Read<InstallRecord>(recordPath) is not { SasChanged: true } record) return;
        if (record.SasPrevious is { } previous) policy.SetValue(VALUE, previous, RegistryValueKind.DWord);
        else policy.DeleteValue(VALUE, throwOnMissingValue: false);
        File.Delete(recordPath);
    }
}
