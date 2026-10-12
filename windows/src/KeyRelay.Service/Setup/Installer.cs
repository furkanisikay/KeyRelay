using System.ComponentModel;
using System.Diagnostics;
using System.Globalization;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.ServiceProcess;
using System.Text.Json;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Settings;
using Microsoft.Win32;

namespace KeyRelay.Service.Setup;

/// <summary>"install [--port N]" and "uninstall", run elevated by the setup program. Both are idempotent.</summary>
internal static class Installer
{
    internal const string SERVICE_NAME = "KeyRelay";
    private const string RUN_KEY = @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run";
    private const string FIREWALL_RULE = "KeyRelay";
    private const string RECORD_FILE = "install.json";
    private const int EXIT_OK = 0;
    private const int EXIT_NOT_ADMIN = 1;
    private const int EXIT_FAILED = 2;
    private const int ERROR_SERVICE_DOES_NOT_EXIST = 1060;
    private static readonly TimeSpan TOOL_TIMEOUT = TimeSpan.FromSeconds(30);

    /// <returns>0 done, 1 not elevated, 2 a step failed (details on stderr).</returns>
    internal static async Task<int> RunAsync(string[] args)
    {
        if (!Environment.IsPrivilegedProcess)
        {
            await Console.Error.WriteLineAsync("Run as administrator.").ConfigureAwait(false);
            return EXIT_NOT_ADMIN;
        }
        try
        {
            if (args[0] == "install") await InstallAsync(ParsePort(args)).ConfigureAwait(false);
            else await UninstallAsync().ConfigureAwait(false);
            return EXIT_OK;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or InvalidOperationException
            or Win32Exception or OperationCanceledException or System.ServiceProcess.TimeoutException
            or JsonException or CryptographicException)
        {
            await Console.Error.WriteLineAsync(ex.Message).ConfigureAwait(false);
            return EXIT_FAILED;
        }
    }

    private static async Task InstallAsync(int? port)
    {
        var exe = Environment.ProcessPath!;
        var tray = Path.Combine(Path.GetDirectoryName(exe)!, "Tray", "KeyRelay.Tray.exe");
        if (!File.Exists(tray)) throw new InvalidOperationException($"Tray app not found: {tray} (run the published build).");
        var data = ServicePaths.DATA_DIRECTORY;
        SecureDataDirectory(data);
        CertificateStore.LoadOrCreate(data).Dispose();
        var store = new SettingsStore(data);
        var settings = port is { } p ? store.Load() with { Port = p } : store.Load();
        store.Save(settings);
        using (var policy = Registry.LocalMachine.CreateSubKey(SasPolicy.KEY)) SasPolicy.Apply(policy, Path.Combine(data, RECORD_FILE));
        using (var run = Registry.LocalMachine.CreateSubKey(RUN_KEY)) run.SetValue(SERVICE_NAME, $"\"{tray}\" --background");
        await RegisterServiceAsync(exe).ConfigureAwait(false);
        // "delete" exits 1 when the rule does not exist yet.
        await RunToolAsync("netsh", "advfirewall", "firewall", "delete", "rule", $"name={FIREWALL_RULE}").ConfigureAwait(false);
        await RequireAsync("netsh", "advfirewall", "firewall", "add", "rule", $"name={FIREWALL_RULE}", "dir=in", "action=allow",
            "protocol=TCP", $"localport={settings.Port.ToString(CultureInfo.InvariantCulture)}", "profile=private", $"program={exe}").ConfigureAwait(false);
        RestartService();
    }

    private static async Task UninstallAsync()
    {
        StopServiceIfPresent();
        await RunToolAsync("sc.exe", "delete", SERVICE_NAME).ConfigureAwait(false);
        using (var run = Registry.LocalMachine.OpenSubKey(RUN_KEY, writable: true)) run?.DeleteValue(SERVICE_NAME, throwOnMissingValue: false);
        await RunToolAsync("netsh", "advfirewall", "firewall", "delete", "rule", $"name={FIREWALL_RULE}").ConfigureAwait(false);
        using (var policy = Registry.LocalMachine.CreateSubKey(SasPolicy.KEY)) SasPolicy.Restore(policy, Path.Combine(ServicePaths.DATA_DIRECTORY, RECORD_FILE));
    }

    private static async Task RegisterServiceAsync(string exe)
    {
        var exists = await RunToolAsync("sc.exe", "query", SERVICE_NAME).ConfigureAwait(false) != ERROR_SERVICE_DOES_NOT_EXIST;
        await RequireAsync("sc.exe", exists ? "config" : "create", SERVICE_NAME, "binPath=", $"\"{exe}\"", "start=", "auto",
            "obj=", "LocalSystem", "DisplayName=", "KeyRelay").ConfigureAwait(false);
        await RequireAsync("sc.exe", "description", SERVICE_NAME, "Forwards keys from a paired Android tablet, including on the lock screen.").ConfigureAwait(false);
        await RequireAsync("sc.exe", "failure", SERVICE_NAME, "reset=", "86400", "actions=", "restart/5000/restart/5000/restart/5000").ConfigureAwait(false);
        // Also recover when the service stops with an error instead of crashing.
        await RequireAsync("sc.exe", "failureflag", SERVICE_NAME, "1").ConfigureAwait(false);
    }

    /// <summary>Only SYSTEM and Administrators may read the certificate key, device hashes and settings.</summary>
    internal static void SecureDataDirectory(string path)
    {
        // A folder or file created earlier by a standard user keeps that user as owner (implicit WRITE_DAC) and explicit ACEs.
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
        var security = new DirectorySecurity();
        security.SetOwner(admins);
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
        {
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
        }
        var directory = Directory.CreateDirectory(path);
        // SetAccessControl would rewrite the ACL of the link target, outside the data folder.
        if (directory.Attributes.HasFlag(FileAttributes.ReparsePoint)) throw new InvalidOperationException($"{path} is a junction or link; remove it and run again.");
        try
        {
            directory.SetAccessControl(security);
            // The data folder is flat; links inside it are skipped for the same reason.
            foreach (var file in directory.EnumerateFiles("*", SearchOption.TopDirectoryOnly).Where(f => !f.Attributes.HasFlag(FileAttributes.ReparsePoint)))
            {
                var inheritedOnly = new FileSecurity();
                inheritedOnly.SetOwner(admins);
                inheritedOnly.SetAccessRuleProtection(isProtected: false, preserveInheritance: false);
                file.SetAccessControl(inheritedOnly);
            }
        }
        catch (UnauthorizedAccessException ex)
        {
            throw new InvalidOperationException($"Cannot take ownership of {path} or a file in it; check its permissions. {ex.Message}", ex);
        }
    }

    private static void RestartService()
    {
        using var service = new ServiceController(SERVICE_NAME);
        if (service.Status != ServiceControllerStatus.Stopped)
        {
            service.Stop();
            service.WaitForStatus(ServiceControllerStatus.Stopped, TOOL_TIMEOUT);
        }
        service.Start();
        service.WaitForStatus(ServiceControllerStatus.Running, TOOL_TIMEOUT);
    }

    private static void StopServiceIfPresent()
    {
        using var service = new ServiceController(SERVICE_NAME);
        try
        {
            if (service.Status == ServiceControllerStatus.Stopped) return;
            service.Stop();
            service.WaitForStatus(ServiceControllerStatus.Stopped, TOOL_TIMEOUT);
        }
        catch (InvalidOperationException)
        {
            // Not installed.
        }
    }

    private static int? ParsePort(string[] args)
    {
        var index = Array.IndexOf(args, "--port");
        if (index < 0) return null;
        if (index + 1 < args.Length && int.TryParse(args[index + 1], NumberStyles.None, CultureInfo.InvariantCulture, out var port) && port is > 0 and <= 65535)
            return port;
        throw new InvalidOperationException("--port needs a number between 1 and 65535.");
    }

    private static async Task RequireAsync(string file, params string[] arguments)
    {
        var code = await RunToolAsync(file, arguments).ConfigureAwait(false);
        if (code != 0) throw new InvalidOperationException($"{file} {arguments[0]} failed with exit code {code}.");
    }

    /// <returns>The tool's exit code; its output is echoed for the setup log.</returns>
    private static async Task<int> RunToolAsync(string file, params string[] arguments)
    {
        var start = new ProcessStartInfo(file) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        foreach (var argument in arguments) start.ArgumentList.Add(argument);
        using var process = Process.Start(start) ?? throw new InvalidOperationException($"{file} did not start.");
        using var timeout = new CancellationTokenSource(TOOL_TIMEOUT);
        var output = process.StandardOutput.ReadToEndAsync(timeout.Token);
        var errors = process.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await process.WaitForExitAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            process.Kill(entireProcessTree: true);
            throw;
        }
        Console.WriteLine($"{file} {string.Join(' ', arguments)} -> {process.ExitCode} {(await output.ConfigureAwait(false)).Trim()} {(await errors.ConfigureAwait(false)).Trim()}");
        return process.ExitCode;
    }
}
