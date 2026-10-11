using System.Security.Cryptography;
using KeyRelay.Protocol;

namespace KeyRelay.Service.Relay;

/// <summary>The single pairing code shown in the QR: 16 random bytes, valid 5 minutes, usable once.</summary>
internal sealed class PairingCodes(TimeProvider time)
{
    private static readonly TimeSpan LIFETIME = TimeSpan.FromMinutes(5);
    private readonly Lock _gate = new();
    private byte[]? _code;
    private DateTimeOffset _expiresAt;

    /// <summary>Gets when the active code expires, or null when no code is active.</summary>
    internal DateTimeOffset? ActiveUntil
    {
        get
        {
            lock (_gate) return _code is not null && time.GetUtcNow() < _expiresAt ? _expiresAt : null;
        }
    }

    /// <summary>Creates a new code; the previous one stops working.</summary>
    internal (byte[] Code, DateTimeOffset ExpiresAt) Start()
    {
        var code = RandomNumberGenerator.GetBytes(Frames.CODE_SIZE);
        lock (_gate)
        {
            _code = code;
            _expiresAt = time.GetUtcNow() + LIFETIME;
            return ((byte[])code.Clone(), _expiresAt);
        }
    }

    internal void Stop()
    {
        lock (_gate) _code = null;
    }

    /// <summary>Uses up the code if it matches and has not expired; compared in constant time.</summary>
    internal bool TryConsume(ReadOnlySpan<byte> code)
    {
        lock (_gate)
        {
            if (_code is null || time.GetUtcNow() >= _expiresAt || !CryptographicOperations.FixedTimeEquals(_code, code)) return false;
            _code = null;
            return true;
        }
    }
}
