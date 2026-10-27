namespace KeyRelay.Service.Relay;

/// <summary>
/// Lets through at most a fixed number of log lines per minute. Rejections from Funnel are unbounded (every random
/// device id is a fresh throttle key), so without it an attacker could flush the Application event log.
/// </summary>
internal sealed class LogBudget(TimeProvider time, int perMinute)
{
    private static readonly TimeSpan WINDOW = TimeSpan.FromMinutes(1);
    private readonly Lock _gate = new();
    private DateTimeOffset _windowStart = DateTimeOffset.MinValue;
    private int _used;
    private int _suppressed;

    /// <summary>True when the line may be logged.</summary>
    /// <param name="suppressed">Lines dropped since the last one let through; log it with this line.</param>
    internal bool TryTake(out int suppressed)
    {
        lock (_gate)
        {
            var now = time.GetUtcNow();
            if (now - _windowStart >= WINDOW)
            {
                _windowStart = now;
                _used = 0;
            }
            if (_used >= perMinute)
            {
                _suppressed++;
                suppressed = 0;
                return false;
            }
            _used++;
            suppressed = _suppressed;
            _suppressed = 0;
            return true;
        }
    }
}
