namespace KeyRelay.Service;

internal static class ServicePaths
{
    /// <summary>ProgramData\KeyRelay: certificate, devices, settings and install record (SYSTEM and Administrators only after install).</summary>
    internal static readonly string DATA_DIRECTORY =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "KeyRelay");
}
