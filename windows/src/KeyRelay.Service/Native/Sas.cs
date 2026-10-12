using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class Sas
{
    /// <summary>Simulates Ctrl+Alt+Del. Requires the SoftwareSASGeneration policy; asUser false = caller is a service.</summary>
    [LibraryImport("sas.dll")]
    internal static partial void SendSAS([MarshalAs(UnmanagedType.Bool)] bool asUser);
}

/// <summary>Sends Ctrl+Alt+Del to the console session; a delegate so tests do not open the security screen.</summary>
internal delegate void SendSas();
