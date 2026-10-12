using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace KeyRelay.Service.Native;

/// <summary>
/// Overlapped I/O on an async pipe handle that the calling thread waits for itself. .NET completes every overlapped
/// pipe call through the thread pool's completion port, one extra thread wake per call; an event with its low bit set
/// keeps the completion off the port. On the key path (service write, agent read) this halved delivery to the agent in
/// a local test (1000 frames 15 ms apart: p50 123 to 57 us, p99 about 260 to about 150 us).
/// One read and one write may run at the same time; callers serialize their writes.
/// </summary>
internal sealed unsafe class DirectPipe : IDisposable
{
    private const int ERROR_BROKEN_PIPE = 109;
    private const int ERROR_NO_DATA = 232;
    private const int ERROR_IO_PENDING = 997;
    private readonly ManualResetEvent _readDone = new(false);
    private readonly ManualResetEvent _writeDone = new(false);

    /// <summary>Writes all bytes and waits until the other end has taken them.</summary>
    /// <returns>false when that took longer than the timeout; the write is then cancelled.</returns>
    /// <exception cref="IOException">The pipe is broken or closing.</exception>
    internal bool Write(SafePipeHandle pipe, ReadOnlySpan<byte> data, TimeSpan timeout)
    {
        var overlapped = Overlapped(_writeDone);
        // Holds the handle open until the I/O is over: a close in between would let Windows finish the I/O into this
        // (by then reused) stack frame.
        var added = false;
        pipe.DangerousAddRef(ref added);
        try
        {
            return Write(pipe, data, timeout, &overlapped);
        }
        finally
        {
            if (added) pipe.DangerousRelease();
        }
    }

    /// <returns>Bytes read, at least one; 0 when the other end closed the pipe.</returns>
    /// <exception cref="IOException">The pipe failed.</exception>
    internal int Read(SafePipeHandle pipe, Span<byte> buffer)
    {
        var overlapped = Overlapped(_readDone);
        var added = false;
        pipe.DangerousAddRef(ref added);
        try
        {
            return Read(pipe, buffer, &overlapped);
        }
        finally
        {
            if (added) pipe.DangerousRelease();
        }
    }

    private bool Write(SafePipeHandle pipe, ReadOnlySpan<byte> data, TimeSpan timeout, NativeOverlapped* overlapped)
    {
        fixed (byte* bytes = data)
        {
            if (!Kernel32.WriteFile(pipe, bytes, data.Length, null, overlapped)) ThrowUnlessPending();
            if (!_writeDone.WaitOne(timeout))
            {
                Kernel32.CancelIoEx(pipe, overlapped);
                // The OVERLAPPED lives on the caller's stack frame: wait until Windows is done with it.
                Kernel32.GetOverlappedResult(pipe, overlapped, out _, wait: true);
                return false;
            }
            if (!Kernel32.GetOverlappedResult(pipe, overlapped, out _, wait: false)) throw Error(Marshal.GetLastPInvokeError());
            return true;
        }
    }

    private int Read(SafePipeHandle pipe, Span<byte> buffer, NativeOverlapped* overlapped)
    {
        fixed (byte* bytes = buffer)
        {
            if (!Kernel32.ReadFile(pipe, bytes, buffer.Length, null, overlapped) && Closed(Marshal.GetLastPInvokeError(), pending: true)) return 0;
            if (Kernel32.GetOverlappedResult(pipe, overlapped, out var read, wait: true)) return read;
            var error = Marshal.GetLastPInvokeError();
            return Closed(error, pending: false) ? 0 : throw Error(error);
        }
    }

    /// <summary>A read-only stream over <see cref="Read"/> for FrameReader.</summary>
    internal Stream Reader(SafePipeHandle pipe) => new ReadStream(this, pipe);

    public void Dispose()
    {
        _readDone.Dispose();
        _writeDone.Dispose();
    }

    // Low bit set: the kernel signals the event but does not queue the completion to the port the handle is bound to.
    private static NativeOverlapped Overlapped(ManualResetEvent done) =>
        new() { EventHandle = done.SafeWaitHandle.DangerousGetHandle() | 1 };

    private static void ThrowUnlessPending()
    {
        var error = Marshal.GetLastPInvokeError();
        if (error != ERROR_IO_PENDING) throw Error(error);
    }

    /// <returns>true for "the other end closed"; false for a pending read when <paramref name="pending"/> allows it.</returns>
    /// <exception cref="IOException">Any other error.</exception>
    private static bool Closed(int error, bool pending)
    {
        if (error is ERROR_BROKEN_PIPE or ERROR_NO_DATA) return true;
        if (pending && error == ERROR_IO_PENDING) return false;
        throw Error(error);
    }

    private static IOException Error(int error) => new("Pipe I/O failed.", new Win32Exception(error));

    private sealed class ReadStream(DirectPipe direct, SafePipeHandle pipe) : Stream
    {
        public override bool CanRead => true;

        public override bool CanSeek => false;

        public override bool CanWrite => false;

        public override long Length => throw new NotSupportedException();

        public override long Position
        {
            get => throw new NotSupportedException();
            set => throw new NotSupportedException();
        }

        public override int Read(byte[] buffer, int offset, int count) => direct.Read(pipe, buffer.AsSpan(offset, count));

        public override int Read(Span<byte> buffer) => direct.Read(pipe, buffer);

        public override void Flush()
        {
        }

        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();

        public override void SetLength(long value) => throw new NotSupportedException();

        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
}
