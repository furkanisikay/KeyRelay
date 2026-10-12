namespace KeyRelay.Tests.TestSupport;

/// <summary>
/// TimeProvider whose clocks (wall and monotonic) and timers only move when a test says so; local time is UTC.
/// Advance fires due timers on the calling thread, in due order, including ones they re-arm within the advanced span.
/// </summary>
internal sealed class ManualTime(DateTimeOffset start) : TimeProvider
{
    private readonly Lock _gate = new();
    private readonly List<ManualTimer> _timers = [];

    internal DateTimeOffset Now { get; set; } = start;

    public override TimeZoneInfo LocalTimeZone => TimeZoneInfo.Utc;

    public override DateTimeOffset GetUtcNow() => Now;

    // The monotonic clock moves with Now, in ticks.
    public override long TimestampFrequency => TimeSpan.TicksPerSecond;

    public override long GetTimestamp() => Now.UtcTicks;

    public override ITimer CreateTimer(TimerCallback callback, object? state, TimeSpan dueTime, TimeSpan period)
    {
        var timer = new ManualTimer(this, callback, state);
        lock (_gate) _timers.Add(timer);
        timer.Change(dueTime, period);
        return timer;
    }

    internal void Advance(TimeSpan by)
    {
        var end = Now + by;
        while (NextDue(end) is { } timer)
        {
            Now = timer.Due!.Value;
            timer.Fire();
        }
        Now = end;
    }

    private ManualTimer? NextDue(DateTimeOffset end)
    {
        lock (_gate) return _timers.Where(t => t.Due <= end).MinBy(t => t.Due);
    }

    private sealed class ManualTimer(ManualTime time, TimerCallback callback, object? state) : ITimer
    {
        private TimeSpan _period = Timeout.InfiniteTimeSpan;

        internal DateTimeOffset? Due { get; private set; }

        public bool Change(TimeSpan dueTime, TimeSpan period)
        {
            Due = dueTime == Timeout.InfiniteTimeSpan ? null : time.Now + dueTime;
            _period = period;
            return true;
        }

        internal void Fire()
        {
            Due = _period == Timeout.InfiniteTimeSpan ? null : time.Now + _period;
            callback(state);
        }

        public void Dispose()
        {
            Due = null;
            lock (time._gate) time._timers.Remove(this);
        }

        public ValueTask DisposeAsync()
        {
            Dispose();
            return ValueTask.CompletedTask;
        }
    }
}
