using System.Runtime.InteropServices;

namespace KeyRelay.Service.Native;

internal static partial class Advapi32
{
    internal const uint TOKEN_ALL_ACCESS = 0xF01FF;
    internal const uint MAXIMUM_ALLOWED = 0x02000000;
    internal const int SECURITY_IMPERSONATION = 2;
    internal const int TOKEN_PRIMARY = 1;
    internal const int TOKEN_SESSION_ID = 12;
    internal const uint CREATE_NO_WINDOW = 0x08000000;
    internal const uint CREATE_UNICODE_ENVIRONMENT = 0x00000400;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    internal struct StartupInfo
    {
        public int Cb;
        public string? Reserved;
        public string? Desktop;
        public string? Title;
        public int X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public short ShowWindow, Reserved2;
        public nint Reserved2Ptr, StdInput, StdOutput, StdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    internal struct ProcessInformation
    {
        public nint Process;
        public nint Thread;
        public int ProcessId;
        public int ThreadId;
    }

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool OpenProcessToken(nint process, uint access, out nint token);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool DuplicateTokenEx(nint token, uint access, nint attributes, int level, int type, out nint newToken);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static partial bool SetTokenInformation(nint token, int infoClass, ref uint info, int length);

    // STARTUPINFO carries strings, which LibraryImport cannot marshal inside a struct.
    // CA1838: lpCommandLine must be writable; called once per agent start, so StringBuilder cost is irrelevant.
#pragma warning disable SYSLIB1054, CA1838
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode, EntryPoint = "CreateProcessAsUserW")]
    [return: MarshalAs(UnmanagedType.Bool)]
    internal static extern bool CreateProcessAsUser(nint token, string? application, System.Text.StringBuilder commandLine,
        nint processAttributes, nint threadAttributes, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles, uint flags,
        nint environment, string? currentDirectory, ref StartupInfo startupInfo, out ProcessInformation processInformation);
#pragma warning restore SYSLIB1054, CA1838
}
