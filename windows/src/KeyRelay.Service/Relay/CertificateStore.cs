using System.Buffers.Text;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace KeyRelay.Service.Relay;

/// <summary>The service's TLS certificate: ECDSA P-256, stored as PKCS#12 protected with DPAPI LocalMachine.</summary>
internal static class CertificateStore
{
    private const string FILE_NAME = "certificate.bin";
    private const int VALID_YEARS = 20;

    /// <summary>Loads the certificate, creating it on first use.</summary>
    /// <param name="directory">ProgramData\KeyRelay (its ACL limits the file to SYSTEM and Administrators).</param>
    internal static X509Certificate2 LoadOrCreate(string directory)
    {
        var path = Path.Combine(directory, FILE_NAME);
        if (!File.Exists(path))
        {
            using var created = Create();
            // Written aside and moved in: a half-written file would fail every later start.
            var temporary = path + ".tmp";
            File.WriteAllBytes(temporary, ProtectedData.Protect(created.Export(X509ContentType.Pkcs12), null, DataProtectionScope.LocalMachine));
            File.Move(temporary, path, overwrite: true);
        }
        var pkcs12 = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.LocalMachine);
        // SChannel needs the key in a key container; without PersistKeySet it is removed when the certificate is disposed.
        return X509CertificateLoader.LoadPkcs12(pkcs12, null, X509KeyStorageFlags.MachineKeySet);
    }

    /// <summary>SHA-256 of the SubjectPublicKeyInfo, base64url; the tablet pins this value (QR "fp").</summary>
    internal static string Fingerprint(X509Certificate2 certificate) => Base64Url.EncodeToString(SpkiSha256(certificate));

    internal static byte[] SpkiSha256(X509Certificate2 certificate) => SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo());

    private static X509Certificate2 Create()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=KeyRelay", key, HashAlgorithmName.SHA256);
        var now = DateTimeOffset.UtcNow;
        return request.CreateSelfSigned(now.AddDays(-1), now.AddYears(VALID_YEARS));
    }
}
