using System.ComponentModel;
using System.Text;
using KeyRelay.Service.Native;
using Microsoft.Win32.SafeHandles;

namespace KeyRelay.Service.Session;

/// <summary>Starts a process as SYSTEM inside an interactive session, so it can reach that session's desktops.</summary>
internal static class SessionLauncher
{
    /// <exception cref="Win32Exception">A token or process call failed (early in boot the session may not be ready yet).</exception>
    internal static AgentProcess Launch(uint sessionId, string commandLine)
    {
        // GetCurrentProcess returns a pseudo handle that needs no closing.
        if (!Advapi32.OpenProcessToken(Kernel32.GetCurrentProcess(), Advapi32.TOKEN_ALL_ACCESS, out var token))
            throw new Win32Exception();
        nint primary = 0;
        try
        {
            if (!Advapi32.DuplicateTokenEx(token, Advapi32.MAXIMUM_ALLOWED, 0, Advapi32.SECURITY_IMPERSONATION, Advapi32.TOKEN_PRIMARY, out primary))
                throw new Win32Exception();
            if (!Advapi32.SetTokenInformation(primary, Advapi32.TOKEN_SESSION_ID, ref sessionId, sizeof(uint)))
                throw new Win32Exception();
            var startup = new Advapi32.StartupInfo { Cb = System.Runtime.InteropServices.Marshal.SizeOf<Advapi32.StartupInfo>(), Desktop = @"winsta0\default" };
            if (!Advapi32.CreateProcessAsUser(primary, null, new StringBuilder(commandLine), 0, 0, false,
                    Advapi32.CREATE_NO_WINDOW | Advapi32.CREATE_UNICODE_ENVIRONMENT, 0, null, ref startup, out var info))
                throw new Win32Exception();
            Kernel32.CloseHandle(info.Thread);
            return new AgentProcess(new SafeProcessHandle(info.Process, ownsHandle: true), info.ProcessId);
        }
        finally
        {
            if (primary != 0) Kernel32.CloseHandle(primary);
            Kernel32.CloseHandle(token);
        }
    }
}
