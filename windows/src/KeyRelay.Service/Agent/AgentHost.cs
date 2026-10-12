using System.Diagnostics;
using System.Globalization;
using System.IO.Pipes;
using System.Security.Principal;
using KeyRelay.Protocol;
using KeyRelay.Service.Native;

namespace KeyRelay.Service.Agent;

/// <summary>The --agent mode: runs as SYSTEM in the console session and injects the keys the service forwards.</summary>
/// <param name="pipeName">Agent pipe name.</param>
/// <param name="servicePid">Process id the pipe server must have; anything else is a squatter.</param>
/// <param name="desktop">user32 access.</param>
internal sealed class AgentHost(string pipeName, int servicePid, IInputDesktop desktop) : IDisposable
{
    internal const string PIPE_NAME = "KeyRelay.Agent";
    internal const int EXIT_OK = 0;
    internal const int EXIT_BAD_ARGUMENTS = 2;
    internal const int EXIT_UNTRUSTED_SERVER = 3;
    internal const int EXIT_PROTOCOL = 4;
    internal const int EXIT_NO_SERVICE = 5;
    private const int CONNECT_TIMEOUT_MS = 5000;
    private static readonly TimeSpan WATCH_INTERVAL = TimeSpan.FromMilliseconds(500);

    // Overlapped I/O lets the watcher thread write DESKTOP while the main thread is blocked in Read.
    // Anonymous: the server is only verified by pid after connecting, so it must not receive an impersonation token.
    private readonly NamedPipeClientStream _pipe =
        new(".", pipeName, PipeDirection.InOut, PipeOptions.Asynchronous, TokenImpersonationLevel.Anonymous);
    private readonly DirectPipe _direct = new();
    private readonly Lock _writeGate = new();
    private readonly byte[] _keyDone = new byte[Frames.KEY_DONE_SIZE];
    private readonly ManualResetEventSlim _stop = new();

    /// <summary>Entry point for "KeyRelay.Service.exe --agent &lt;service pid&gt;".</summary>
    /// <returns>An EXIT_* code; the service logs it.</returns>
    internal static int RunAgent(string[] args)
    {
        if (args.Length != 2 || !int.TryParse(args[1], NumberStyles.None, CultureInfo.InvariantCulture, out var pid)) return EXIT_BAD_ARGUMENTS;
        using var host = new AgentHost(PIPE_NAME, pid, new NativeInputDesktop());
        // Run reads and injects on this thread; it sleeps in the pipe read between keys, and above-normal priority
        // shortens its wake-up when the PC is busy.
        Thread.CurrentThread.Priority = ThreadPriority.AboveNormal;
        return host.Run();
    }

    /// <summary>Serves the pipe until the service closes it.</summary>
    /// <returns>An EXIT_* code.</returns>
    internal int Run()
    {
        try
        {
            _pipe.Connect(CONNECT_TIMEOUT_MS);
        }
        catch (Exception ex) when (ex is TimeoutException or IOException or UnauthorizedAccessException)
        {
            return EXIT_NO_SERVICE;
        }
        if (!Kernel32.GetNamedPipeServerProcessId(_pipe.SafePipeHandle, out var serverPid) || serverPid != (uint)servicePid)
            return EXIT_UNTRUSTED_SERVER;
        var injector = new KeyInjector(desktop, WriteDesktop, WriteLog);
        injector.ReleaseModifiers();
        var watcher = new Thread(() => Watch(injector)) { IsBackground = true, Name = "desktop-watch" };
        watcher.Start();
        try
        {
            return ReadLoop(injector);
        }
        catch (ProtocolException)
        {
            return EXIT_PROTOCOL;
        }
        catch (IOException)
        {
            return EXIT_OK;
        }
        finally
        {
            _stop.Set();
            watcher.Join(WATCH_INTERVAL * 4);
        }
    }

    private int ReadLoop(KeyInjector injector)
    {
        // Reads wait on this thread, not through the thread pool: one thread wake less per key.
        var reader = new FrameReader(_direct.Reader(_pipe.SafePipeHandle), FrameChannel.Pipe);
        while (reader.Read() is { } frame)
        {
            switch (frame.Type)
            {
                case FrameType.Key:
                    var read = Stopwatch.GetTimestamp();
                    var (code, value) = Frames.ReadKey(frame.Payload.Span);
                    var attached = injector.Inject(code, value);
                    WriteKeyDone(new KeyDoneTimes(read, attached == 0 ? read : attached, Stopwatch.GetTimestamp()));
                    break;
                case FrameType.Lock:
                    desktop.LockWorkStation();
                    break;
                case FrameType.ReleaseAll:
                    injector.ReleaseAll();
                    break;
            }
        }
        return EXIT_OK;
    }

    private void Watch(KeyInjector injector)
    {
        try
        {
            while (!_stop.Wait(WATCH_INTERVAL)) injector.Watch();
        }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException)
        {
            // The pipe closed; the read loop ends on its own.
        }
    }

    private void WriteKeyDone(KeyDoneTimes times)
    {
        lock (_writeGate)
        {
            Frames.WriteKeyDone(_keyDone, times);
            _direct.Write(_pipe.SafePipeHandle, _keyDone, Timeout.InfiniteTimeSpan);
        }
    }

    private void WriteDesktop(bool writable, string name) => Write(Frames.Desktop(writable, name));

    private void WriteLog(bool warning, string line) => Write(Frames.AgentLog(warning, line));

    private void Write(byte[] frame)
    {
        lock (_writeGate) _direct.Write(_pipe.SafePipeHandle, frame, Timeout.InfiniteTimeSpan);
    }

    public void Dispose()
    {
        _pipe.Dispose();
        _direct.Dispose();
        _stop.Dispose();
    }
}
