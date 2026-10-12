using System.Buffers.Text;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Service.Relay;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Relay;

[TestClass]
public sealed class CertificateStoreTests : IDisposable
{
    private readonly TempDirectory _directory = new();

    public void Dispose() => _directory.Dispose();

    [TestMethod]
    public void LoadOrCreate_Twice_ReturnsSameKey()
    {
        using var first = CertificateStore.LoadOrCreate(_directory.Path);
        using var second = CertificateStore.LoadOrCreate(_directory.Path);

        Assert.AreEqual(CertificateStore.Fingerprint(first), CertificateStore.Fingerprint(second));
    }

    [TestMethod]
    public void LoadOrCreate_NewCertificate_IsEcdsaP256WithPrivateKey()
    {
        using var certificate = CertificateStore.LoadOrCreate(_directory.Path);
        using var key = certificate.GetECDsaPublicKey();

        Assert.AreEqual(256, key?.KeySize);
        Assert.IsTrue(certificate.HasPrivateKey);
    }

    [TestMethod]
    public void LoadOrCreate_FileOnDisk_IsNotReadableAsPlainPkcs12()
    {
        using var _ = CertificateStore.LoadOrCreate(_directory.Path);
        var bytes = File.ReadAllBytes(Path.Combine(_directory.Path, "certificate.bin"));

        Assert.Throws<CryptographicException>(() => X509CertificateLoader.LoadPkcs12(bytes, null));
    }

    [TestMethod]
    public void Fingerprint_Certificate_IsBase64UrlSha256OfPublicKeyInfo()
    {
        using var certificate = CertificateStore.LoadOrCreate(_directory.Path);
        var expected = Base64Url.EncodeToString(SHA256.HashData(certificate.PublicKey.ExportSubjectPublicKeyInfo()));

        var fingerprint = CertificateStore.Fingerprint(certificate);

        Assert.AreEqual(expected, fingerprint);
        Assert.AreEqual(43, fingerprint.Length);
    }
}
