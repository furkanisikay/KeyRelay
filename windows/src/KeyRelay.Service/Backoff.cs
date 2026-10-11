namespace KeyRelay.Service;

/// <summary>Exponential back-off: zero, then first, 2 x first, 4 x first ... capped at max.</summary>
internal static class Backoff
{
    /// <param name="failures">Consecutive failures; 0 means no wait.</param>
    /// <param name="first">Wait after the first failure.</param>
    /// <param name="max">Upper limit.</param>
    internal static TimeSpan Delay(int failures, TimeSpan first, TimeSpan max) =>
        failures <= 0 ? TimeSpan.Zero : TimeSpan.FromTicks(Math.Min(max.Ticks, first.Ticks << Math.Min(failures - 1, 20)));
}
