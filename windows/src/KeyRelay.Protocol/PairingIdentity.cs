namespace KeyRelay.Protocol;

/// <summary>The short PC identity code both screens show during pairing so the user can compare them (docs/protocol.md).</summary>
public static class PairingIdentity
{
    private const int SPKI_SHA256_SIZE = 32;
    private const string BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    // 5 bytes = 40 bits = 8 base32 characters, shown as XXXX-XXXX.
    private const int CODE_BYTES = 5;
    private const int CODE_CHARS = 8;
    private const int BITS_PER_CHAR = 5;

    /// <summary>Gets the identity code: unpadded base32 (RFC 4648) of the first 5 bytes, as XXXX-XXXX.</summary>
    /// <param name="spkiSha256">SHA-256 of the certificate's SubjectPublicKeyInfo (the decoded QR "fp").</param>
    /// <returns>The code, for example TYIM-LHQO.</returns>
    /// <exception cref="ArgumentException">The input is not 32 bytes.</exception>
    public static string IdentityCode(ReadOnlySpan<byte> spkiSha256)
    {
        if (spkiSha256.Length != SPKI_SHA256_SIZE) throw new ArgumentException("Expected a 32 byte SPKI SHA-256.", nameof(spkiSha256));
        ulong bits = 0;
        foreach (var b in spkiSha256[..CODE_BYTES]) bits = bits << 8 | b;
        Span<char> code = stackalloc char[CODE_CHARS + 1];
        for (int i = 0, c = 0; i < CODE_CHARS; i++)
        {
            if (i == CODE_CHARS / 2) code[c++] = '-';
            code[c++] = BASE32[(int)(bits >> ((CODE_CHARS - 1 - i) * BITS_PER_CHAR)) & 0x1F];
        }
        return new string(code);
    }
}
