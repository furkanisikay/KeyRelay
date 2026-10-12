using System.Diagnostics;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Polls a condition that another thread makes true.</summary>
internal static class Eventually
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);

    internal static async Task TrueAsync(Func<bool> condition, string message)
    {
        var started = Stopwatch.GetTimestamp();
        while (!condition())
        {
            if (Stopwatch.GetElapsedTime(started) > TIMEOUT) Assert.Fail(message);
            await Task.Delay(20);
        }
    }
}
