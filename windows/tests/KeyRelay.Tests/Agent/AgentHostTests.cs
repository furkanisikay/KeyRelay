using System.IO.Pipes;
using KeyRelay.Protocol;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Native;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class AgentHostTests
{
    private const ushort KEY_A = 30;
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    [TestMethod]
    public async Task Run_KeyFrame_InjectsAndAnswersKeyDone()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);
            var reader = new FrameReader(server, FrameChannel.Pipe);

            await server.WriteAsync(Frames.Key(KEY_A, 1));
            var answer = await ReadSkippingDesktopAsync(reader);

            Assert.AreEqual(FrameType.KeyDone, answer);
            Assert.Contains(((ushort)0x1E, User32.KEYEVENTF_SCANCODE, "Default"), desktop.SentSnapshot());
        }
        Assert.AreEqual(AgentHost.EXIT_OK, await run.WaitAsync(TIMEOUT));
    }

    [TestMethod]
    public async Task Run_ServerIsNotTheService_ExitsWithoutInjecting()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId + 1, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            var exit = await run.WaitAsync(TIMEOUT);

            Assert.AreEqual(AgentHost.EXIT_UNTRUSTED_SERVER, exit);
            Assert.IsEmpty(desktop.SentSnapshot());
        }
    }

    [TestMethod]
    public async Task Run_Start_ReleasesModifiersFirst()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            await Eventually.TrueAsync(() => desktop.SentSnapshot().Length >= 8, "modifiers were not released");

            Assert.IsTrue(desktop.SentSnapshot().Take(8).All(s => (s.Flags & User32.KEYEVENTF_KEYUP) != 0));
        }
        Assert.AreEqual(AgentHost.EXIT_OK, await run.WaitAsync(TIMEOUT));
    }

    [TestMethod]
    public async Task Run_ReleaseAll_ReleasesHeldKey()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);
            var reader = new FrameReader(server, FrameChannel.Pipe);
            await server.WriteAsync(Frames.Key(KEY_A, 1));
            await ReadSkippingDesktopAsync(reader);

            await server.WriteAsync(Frames.ReleaseAllFrame);

            await Eventually.TrueAsync(
                () => desktop.SentSnapshot().Contains(((ushort)0x1E, User32.KEYEVENTF_SCANCODE | User32.KEYEVENTF_KEYUP, "Default")),
                "held key was not released");
        }
        Assert.AreEqual(AgentHost.EXIT_OK, await run.WaitAsync(TIMEOUT));
    }

    [TestMethod]
    public async Task Run_Lock_LocksWorkStation()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            await server.WriteAsync(Frames.LockFrame);

            await Eventually.TrueAsync(() => desktop.Locks == 1, "workstation was not locked");
        }
        Assert.AreEqual(AgentHost.EXIT_OK, await run.WaitAsync(TIMEOUT));
    }

    [TestMethod]
    public async Task Run_InputDesktopChanges_SendsDesktopFrameWithName()
    {
        var (pipe, server) = CreateServer();
        var desktop = new FakeDesktop();
        using var host = new AgentHost(pipe, Environment.ProcessId, desktop);
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);
            var reader = new FrameReader(server, FrameChannel.Pipe);
            desktop.Input = "Winlogon";

            var seen = new List<(bool, string)>();
            using var timeout = new CancellationTokenSource(TIMEOUT);
            while (!seen.Contains((true, "Winlogon")))
            {
                // A closed pipe or the timeout ends the loop with a failure instead of hanging.
                var frame = await reader.ReadAsync(timeout.Token) ?? throw new AssertFailedException("pipe closed");
                if (frame.Type == FrameType.Desktop) seen.Add(Frames.ReadDesktop(frame.Payload.Span));
            }

            Assert.Contains((true, "Winlogon"), seen);
        }
        Assert.AreEqual(AgentHost.EXIT_OK, await run.WaitAsync(TIMEOUT));
    }

    [TestMethod]
    public async Task Run_MalformedFrame_ExitsWithProtocolError()
    {
        var (pipe, server) = CreateServer();
        using var host = new AgentHost(pipe, Environment.ProcessId, new FakeDesktop());
        var run = Start(host);
        await using (server)
        {
            await server.WaitForConnectionAsync().WaitAsync(TIMEOUT);

            await server.WriteAsync(Convert.FromHexString("0201"));

            Assert.AreEqual(AgentHost.EXIT_PROTOCOL, await run.WaitAsync(TIMEOUT));
        }
    }

    private static (string Pipe, NamedPipeServerStream Server) CreateServer()
    {
        var name = $"KeyRelay.Test.{Guid.NewGuid():N}";
        return (name, new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous));
    }

    private static Task<int> Start(AgentHost host) =>
        Task.Factory.StartNew(host.Run, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);

    private static async Task<FrameType> ReadSkippingDesktopAsync(FrameReader reader)
    {
        using var timeout = new CancellationTokenSource(TIMEOUT);
        while (true)
        {
            var frame = await reader.ReadAsync(timeout.Token) ?? throw new AssertFailedException("pipe closed");
            if (frame.Type != FrameType.Desktop) return frame.Type;
        }
    }
}
