namespace KeyRelay.Service.Session;

/// <summary>Wakes the agent supervisor when Windows reports a session change (logon, logoff, console connect or disconnect).</summary>
internal sealed class SessionSignal : IDisposable
{
    private readonly SemaphoreSlim _changed = new(0);

    /// <summary>Called from the service control handler; several notifications before a wait count as one.</summary>
    internal void Notify()
    {
        if (_changed.CurrentCount == 0) _changed.Release();
    }

    /// <summary>Waits until the active console session differs from <paramref name="session"/>.</summary>
    /// <param name="session">Session the caller is serving (NO_ACTIVE_SESSION while none).</param>
    /// <param name="activeSession">Reads the active console session (WTSGetActiveConsoleSessionId).</param>
    /// <param name="cancellationToken">Stops waiting.</param>
    internal async Task WaitForChangeAsync(uint session, Func<uint> activeSession, CancellationToken cancellationToken)
    {
        while (activeSession() == session) await _changed.WaitAsync(cancellationToken).ConfigureAwait(false);
    }

    public void Dispose() => _changed.Dispose();
}
