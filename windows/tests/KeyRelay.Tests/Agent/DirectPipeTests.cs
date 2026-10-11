using System.IO.Pipes;
using KeyRelay.Service.Native;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Agent;

[TestClass]
public sealed class DirectPipeTests
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    // Zero buffers and async handles, as AgentLink and the agent open them.
    private static async Task<(NamedPipeServerStream Server, NamedPipeClientStream Client)> ConnectAsync()
    {
        var name = TestPipes.Name();
        var server = new NamedPipeServerStream(name, PipeDirection.InOut, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous, 0, 0);
        var client = new NamedPipeClientStream(".", name, PipeDirection.InOut, PipeOptions.Asynchronous);
        var waiting = server.WaitForConnectionAsync();
        await client.ConnectAsync((int)TIMEOUT.TotalMilliseconds);
        await waiting;
        return (server, client);
    }

    [TestMethod]
    public async Task Write_ReaderWaiting_DeliversBytesAndReturnsTrue()
    {
        var (server, client) = await ConnectAsync();
        await using (server)
        await using (client)
        {
            using var direct = new DirectPipe();
            var received = new byte[3];
            var reading = Task.Run(() => direct.Reader(client.SafePipeHandle).ReadExactly(received));

            var written = await Task.Run(() => direct.Write(server.SafePipeHandle, [1, 2, 3], TIMEOUT));
            await reading.WaitAsync(TIMEOUT);

            Assert.IsTrue(written);
            CollectionAssert.AreEqual(new byte[] { 1, 2, 3 }, received);
        }
    }

    [TestMethod]
    public async Task Write_NobodyReads_TimesOutAndReturnsFalse()
    {
        var (server, client) = await ConnectAsync();
        await using (server)
        await using (client)
        {
            using var direct = new DirectPipe();

            var written = direct.Write(server.SafePipeHandle, [1, 2, 3], TimeSpan.FromMilliseconds(100));

            Assert.IsFalse(written);
        }
    }

    [TestMethod]
    public async Task Read_OtherEndClosed_ReturnsZero()
    {
        var (server, client) = await ConnectAsync();
        await using (client)
        {
            using var direct = new DirectPipe();
            await server.DisposeAsync();

            var read = direct.Read(client.SafePipeHandle, new byte[3]);

            Assert.AreEqual(0, read);
        }
    }

    [TestMethod]
    public async Task Write_OtherEndClosed_ThrowsIOException()
    {
        var (server, client) = await ConnectAsync();
        await using (server)
        {
            using var direct = new DirectPipe();
            await client.DisposeAsync();

            Assert.ThrowsExactly<IOException>(() => direct.Write(server.SafePipeHandle, [1], TIMEOUT));
        }
    }
}
