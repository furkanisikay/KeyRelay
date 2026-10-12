using KeyRelay.Service.Native;
using Microsoft.Win32.SafeHandles;

namespace KeyRelay.Service.Session;

/// <summary>An agent started by the service, held by handle: exit is seen without polling and a reused pid cannot fool us.</summary>
internal sealed class AgentProcess : IDisposable
{
    private static readonly TimeSpan EXIT_WAIT = TimeSpan.FromSeconds(5);
    private readonly SafeProcessHandle _handle;

    internal AgentProcess(SafeProcessHandle handle, int id)
    {
        _handle = handle;
        Id = id;
        Exited = Task.Factory.StartNew(() => Kernel32.WaitForSingleObject(_handle, Kernel32.INFINITE),
            CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
    }

    internal int Id { get; }

    /// <summary>Gets a task that completes when the process exits.</summary>
    internal Task Exited { get; }

    /// <summary>Gets the exit code, or null while running or when it cannot be read.</summary>
    internal uint? ExitCode => Kernel32.GetExitCodeProcess(_handle, out var code) && code != Kernel32.STILL_ACTIVE ? code : null;

    /// <summary>Terminates the agent if it still runs and waits until it has exited.</summary>
    /// <exception cref="TimeoutException">The process did not exit within 5 s.</exception>
    internal async Task StopAsync()
    {
        if (!Exited.IsCompleted) Kernel32.TerminateProcess(_handle, 1);
        await Exited.WaitAsync(EXIT_WAIT).ConfigureAwait(false);
    }

    public void Dispose() => _handle.Dispose();
}
