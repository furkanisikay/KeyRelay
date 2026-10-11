using System.Buffers;
using System.Diagnostics;
using System.Runtime.InteropServices;
using KeyRelay.Protocol;
using KeyRelay.Protocol.Control;

namespace KeyRelay.Service.Agent;

/// <summary>p50/p99, per-second maxima (oldest first) and today's key count over the last minute.</summary>
internal readonly record struct LatencySnapshot(int P50Micros, int P99Micros, int[] PerSecondMaxMicros, long KeysToday);

/// <summary>
/// Per-key latency from reading KEY off TLS to the agent's SendInput return (finish line 7), over the last minute.
/// The pipe keeps order, so KEY_DONE answers pair with read times first-in first-out.
/// </summary>
internal sealed class LatencyStats(TimeProvider time)
{
    /// <summary>
    /// Parts of each sample. The first four, in order, make up the total (with clamping); "own" is the total without
    /// SendInput, i.e. the delay KeyRelay itself adds (finish line 7), since SendInput time depends on third-party
    /// keyboard hooks (measured p99 about 2 ms with TeamViewer).
    /// </summary>
    internal static readonly string[] PARTS =
    [
        "service",   // TLS read -> pipe write returned (service dispatch and write)
        "pipe",      // pipe write returned -> agent read the frame (pipe and agent wake-up)
        "desktop",   // agent read -> on the input desktop (frame decode, OpenInputDesktop check)
        "sendinput", // on the input desktop -> SendInput returned (a retry on a stale desktop counts here)
        "own",       // TLS read -> right before SendInput (total minus sendinput, per key)
    ];
    private const int PENDING_CAPACITY = 256;
    private const int SAMPLE_CAPACITY = 4096;
    private const int WINDOW_SECONDS = 60;

    private readonly Lock _gate = new();
    private readonly long[] _pending = new long[PENDING_CAPACITY];
    private readonly long[] _pendingWritten = new long[PENDING_CAPACITY];
    private readonly long[] _sampleSecond = new long[SAMPLE_CAPACITY];
    private readonly int[] _sampleMicros = new int[SAMPLE_CAPACITY];
    // Fixed rings next to the totals, filled on every KEY_DONE; read only by the latency-breakdown diagnostic.
    private readonly int[][] _partMicros = [.. PARTS.Select(_ => new int[SAMPLE_CAPACITY])];
    private int _pendingHead;
    private int _pendingCount;
    private int _sampleNext;
    private int _sampleCount;
    private DateOnly _today;
    private long _keysToday;

    /// <summary>Remembers when a KEY frame was read off TLS and counts today's key presses.</summary>
    /// <param name="readTimestamp">Stopwatch timestamp when the KEY frame was read.</param>
    /// <param name="press">true for a press; releases and auto-repeats are not counted.</param>
    internal void Sent(long readTimestamp, bool press)
    {
        var today = DateOnly.FromDateTime(time.GetLocalNow().DateTime);
        lock (_gate)
        {
            if (press)
            {
                if (today != _today)
                {
                    _today = today;
                    _keysToday = 0;
                }
                _keysToday++;
            }
            // Full only when the agent stopped answering; the link then restarts it and calls ResetPending.
            if (_pendingCount == PENDING_CAPACITY) return;
            var slot = (_pendingHead + _pendingCount) % PENDING_CAPACITY;
            _pending[slot] = readTimestamp;
            _pendingWritten[slot] = 0;
            _pendingCount++;
        }
    }

    /// <summary>Notes when the pipe write of the KEY recorded last by Sent returned (writes are serialized).</summary>
    internal void Written(long timestamp)
    {
        lock (_gate)
        {
            if (_pendingCount == 0) return;
            _pendingWritten[(_pendingHead + _pendingCount - 1) % PENDING_CAPACITY] = timestamp;
        }
    }

    /// <summary>Pairs the agent's KEY_DONE with the oldest pending read time and records the sample and its parts.</summary>
    internal void Done(KeyDoneTimes agent)
    {
        var now = time.GetUtcNow();
        lock (_gate)
        {
            if (_pendingCount == 0) return;
            var start = _pending[_pendingHead];
            // The agent can read before the service's write call returns; an unknown or later write time counts as the read.
            var written = _pendingWritten[_pendingHead] is var w and > 0 && w < agent.Read ? w : agent.Read;
            _pendingHead = (_pendingHead + 1) % PENDING_CAPACITY;
            _pendingCount--;
            _sampleSecond[_sampleNext] = now.ToUnixTimeSeconds();
            _sampleMicros[_sampleNext] = Micros(start, agent.Done);
            _partMicros[0][_sampleNext] = Micros(start, written);
            _partMicros[1][_sampleNext] = Micros(written, agent.Read);
            _partMicros[2][_sampleNext] = Micros(agent.Read, agent.Attached);
            _partMicros[3][_sampleNext] = Micros(agent.Attached, agent.Done);
            _partMicros[4][_sampleNext] = Micros(start, agent.Attached);
            _sampleNext = (_sampleNext + 1) % SAMPLE_CAPACITY;
            _sampleCount = Math.Min(_sampleCount + 1, SAMPLE_CAPACITY);
        }
    }

    /// <summary>Forgets read times that will never be answered (agent restarted).</summary>
    internal void ResetPending()
    {
        lock (_gate)
        {
            _pendingHead = 0;
            _pendingCount = 0;
        }
    }

    internal LatencySnapshot Snapshot()
    {
        var nowSecond = time.GetUtcNow().ToUnixTimeSeconds();
        var today = DateOnly.FromDateTime(time.GetLocalNow().DateTime);
        var perSecond = new int[WINDOW_SECONDS];
        // The tray asks every second all day: a pooled buffer instead of a growing list keeps this from feeding the GC
        // that also pauses the key path. Sorted outside the lock so Sent and Done never wait for it.
        var window = ArrayPool<int>.Shared.Rent(SAMPLE_CAPACITY);
        var count = 0;
        long keys;
        lock (_gate)
        {
            for (var i = 0; i < _sampleCount; i++)
            {
                var age = nowSecond - _sampleSecond[i];
                if (age is < 0 or >= WINDOW_SECONDS) continue;
                window[count++] = _sampleMicros[i];
                var slot = WINDOW_SECONDS - 1 - (int)age;
                perSecond[slot] = Math.Max(perSecond[slot], _sampleMicros[i]);
            }
            keys = _today == today ? _keysToday : 0;
        }
        var sorted = window.AsSpan(0, count);
        sorted.Sort();
        var snapshot = new LatencySnapshot(Percentile(sorted, 0.50), Percentile(sorted, 0.99), perSecond, keys);
        ArrayPool<int>.Shared.Return(window);
        return snapshot;
    }

    /// <summary>p50, p99 and maximum of the total and of each part over the last minute (diagnostic; allocates).</summary>
    internal List<LatencySegment> Breakdown()
    {
        var nowSecond = time.GetUtcNow().ToUnixTimeSeconds();
        var columns = new List<int>[PARTS.Length + 1];
        for (var c = 0; c < columns.Length; c++) columns[c] = [];
        lock (_gate)
        {
            for (var i = 0; i < _sampleCount; i++)
            {
                if (nowSecond - _sampleSecond[i] is < 0 or >= WINDOW_SECONDS) continue;
                columns[0].Add(_sampleMicros[i]);
                for (var p = 0; p < PARTS.Length; p++) columns[p + 1].Add(_partMicros[p][i]);
            }
        }
        return [.. columns.Select((values, c) =>
        {
            values.Sort();
            var span = CollectionsMarshal.AsSpan(values);
            return new LatencySegment(c == 0 ? "total" : PARTS[c - 1], values.Count, Percentile(span, 0.50), Percentile(span, 0.99), values.Count == 0 ? 0 : values[^1]);
        })];
    }

    private static int Micros(long from, long to) =>
        (int)Math.Clamp(Stopwatch.GetElapsedTime(from, to).Ticks / TimeSpan.TicksPerMicrosecond, 0, int.MaxValue);

    private static int Percentile(ReadOnlySpan<int> sorted, double fraction) =>
        sorted.Length == 0 ? 0 : sorted[(int)Math.Ceiling(fraction * sorted.Length) - 1];
}
