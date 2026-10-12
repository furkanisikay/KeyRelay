using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;
using KeyRelay.Protocol;
using KeyRelay.Service.Relay;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Stand-in for the tablet: a TLS 1.3 client that accepts only the pinned service key.</summary>
internal sealed class TabletClient : IAsyncDisposable
{
    private static readonly TimeSpan TIMEOUT = TimeSpan.FromSeconds(5);
    private readonly TcpClient _tcp;
    private readonly SslStream _ssl;
    private readonly FrameReader _reader;

    private TabletClient(TcpClient tcp, SslStream ssl)
    {
        _tcp = tcp;
        _ssl = ssl;
        _reader = new FrameReader(ssl, FrameChannel.Tls);
    }

    internal SslProtocols Protocol => _ssl.SslProtocol;

    internal static async Task<TabletClient> ConnectAsync(int port, string fingerprint, SslProtocols protocols = SslProtocols.Tls13)
    {
        var tcp = new TcpClient();
        await tcp.ConnectAsync(IPAddress.Loopback, port);
        var ssl = new SslStream(tcp.GetStream(), false, (_, certificate, _, _) => certificate is not null && Fingerprint(certificate) == fingerprint);
        await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = "keyrelay", EnabledSslProtocols = protocols });
        return new TabletClient(tcp, ssl);
    }

    internal Task SendAsync(byte[] frame) => _ssl.WriteAsync(frame).AsTask();

    /// <summary>Reads the next frame that is not STATE.</summary>
    internal async Task<(FrameType Type, byte[] Payload)> NextAsync()
    {
        while (true)
        {
            var frame = await ReadAsync() ?? throw new AssertFailedException("service closed the connection");
            if (frame.Type != FrameType.State) return frame;
        }
    }

    /// <summary>Reads until a frame of the given type; STATE frames are skipped unless STATE is asked for.</summary>
    internal async Task<(FrameType Type, byte[] Payload)> ExpectAsync(FrameType type)
    {
        while (true)
        {
            var frame = await ReadAsync() ?? throw new AssertFailedException($"service closed the connection while {type} was expected");
            if (frame.Type == type) return frame;
            if (frame.Type != FrameType.State) throw new AssertFailedException($"expected {type}, got {frame.Type}");
        }
    }

    /// <summary>True when the service closes the connection within 5 s (REJECT and STATE before the close are ignored).</summary>
    internal async Task<bool> IsClosedAsync()
    {
        try
        {
            while (await ReadAsync() is { } frame)
            {
                if (frame.Type is not (FrameType.State or FrameType.Reject)) return false;
            }
            return true;
        }
        catch (IOException)
        {
            return true;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    public async ValueTask DisposeAsync()
    {
        await _ssl.DisposeAsync();
        _tcp.Dispose();
    }

    private async Task<(FrameType Type, byte[] Payload)?> ReadAsync()
    {
        using var timeout = new CancellationTokenSource(TIMEOUT);
        var frame = await _reader.ReadAsync(timeout.Token);
        return frame is { } f ? (f.Type, f.Payload.ToArray()) : null;
    }

    private static string Fingerprint(X509Certificate certificate)
    {
        using var x509 = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
        return CertificateStore.Fingerprint(x509);
    }
}
