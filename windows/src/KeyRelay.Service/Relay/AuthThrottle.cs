namespace KeyRelay.Service.Relay;

/// <summary>
/// Slows failed PAIR and HELLO attempts per key (source address plus device id, or "pair"): 1 s, 2 s, 4 s ... up to 60 s.
/// Funnel traffic arrives from loopback, so the device id keeps one attacker from blocking a real tablet.
/// </summary>
internal sealed class AuthThrottle(TimeProvider time)
{
    private const int MAX_ENTRIES = 1024;
    private static readonly TimeSpan FIRST = TimeSpan.FromSeconds(1);
    private static readonly TimeSpan MAX = TimeSpan.FromSeconds(60);
    private readonly Lock _gate = new();
    private readonly Dictionary<string, (int Failures, DateTimeOffset Until)> _entries = [];

    internal bool IsBlocked(string key)
    {
        lock (_gate) return _entries.TryGetValue(key, out var entry) && time.GetUtcNow() < entry.Until;
    }

    internal void Failed(string key)
    {
        lock (_gate)
        {
            if (_entries.Count >= MAX_ENTRIES) Prune();
            var failures = _entries.TryGetValue(key, out var entry) ? entry.Failures + 1 : 1;
            _entries[key] = (failures, time.GetUtcNow() + Backoff.Delay(failures, FIRST, MAX));
        }
    }

    internal void Succeeded(string key)
    {
        lock (_gate) _entries.Remove(key);
    }

    // Keeps memory bounded when many addresses fail; expired entries go first.
    private void Prune()
    {
        var now = time.GetUtcNow();
        foreach (var key in _entries.Where(e => e.Value.Until <= now).Select(e => e.Key).ToList()) _entries.Remove(key);
        if (_entries.Count >= MAX_ENTRIES) _entries.Clear();
    }
}
