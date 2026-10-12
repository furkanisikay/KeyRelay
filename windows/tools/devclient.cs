#:project ../src/KeyRelay.Protocol/KeyRelay.Protocol.csproj
#:property TargetFramework=net10.0
#:property PublishAot=false
#:property TreatWarningsAsErrors=false

using System.Buffers.Text;
using System.Globalization;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;
using System.Web;
using KeyRelay.Protocol;
using KeyRelay.Protocol.Control;

// Developer client for checking the Windows side without the Android app (P3 device steps).
// The paired identity is kept in devclient.json in the current directory.
const string STATE_FILE = "devclient.json";
var json = new JsonSerializerOptions { WriteIndented = true };
var command = args.Length > 0 ? args[0] : "";
return command switch
{
    "status" => await ControlAsync(ControlOps.STATUS, r => JsonSerializer.Serialize(r.Status, json)),
    "pair-uri" => await ControlAsync(ControlOps.PAIR_START, r => r.Pairing!.Link),
    // Read-only; the last 50 lines are enough to read in a console.
    "logs" => await ControlAsync(ControlOps.LOGS, r => string.Join(Environment.NewLine, r.Logs!.TakeLast(50))),
    "pair" when args.Length >= 2 => await PairAsync(args[1], args.Length >= 3 ? args[2] : null),
    "hello" => await SessionAsync(_ => Task.CompletedTask),
    "type" when args.Length == 2 => await SessionAsync(ssl => TypeAsync(ssl, args[1])),
    "key" when args.Length == 2 => await SessionAsync(ssl => TapAsync(ssl, ushort.Parse(args[1], CultureInfo.InvariantCulture))),
    "sas" => await SessionAsync(ssl => ssl.WriteAsync(Frames.Empty(FrameType.Sas)).AsTask()),
    "lock" => await SessionAsync(ssl => ssl.WriteAsync(Frames.Empty(FrameType.Lock)).AsTask()),
    "bench" when args.Length == 2 => await SessionAsync(ssl => BenchAsync(ssl, int.Parse(args[1], CultureInfo.InvariantCulture))),
    "watch" when args.Length == 2 => await SessionAsync(ssl => WatchAsync(ssl, int.Parse(args[1], CultureInfo.InvariantCulture))),
    "latency" => await ControlAsync(ControlOps.LATENCY_BREAKDOWN, r => Breakdown(r.LatencyBreakdown!)),
    "latency-bench" when args.Length == 2 => await LatencyBenchAsync(int.Parse(args[1], CultureInfo.InvariantCulture)),
    "heartbeats" when args.Length == 2 => await HeartbeatsAsync(int.Parse(args[1], CultureInfo.InvariantCulture)),
    _ => Usage(),
};

// Reads STATUS once a second and prints every change of the connected tablet's heartbeat count (what the tray sees).
static async Task<int> HeartbeatsAsync(int seconds)
{
    long? last = null;
    for (var i = 0; i < seconds; i++)
    {
        await using (var client = new ControlClient(ControlClient.PIPE_NAME))
        {
            var status = (await client.SendAsync(new ControlRequest(ControlOps.STATUS))).Status;
            if (status?.Heartbeats != last || i == 0)
                Console.WriteLine($"{DateTime.Now:HH:mm:ss} device={status?.ConnectedDevice ?? "-"} heartbeats={status?.Heartbeats}");
            last = status?.Heartbeats;
        }
        await Task.Delay(TimeSpan.FromSeconds(1));
    }
    return 0;
}

async Task<int> ControlAsync(string op, Func<ControlResponse, string> show)
{
    await using var client = new ControlClient(ControlClient.PIPE_NAME);
    var response = await client.SendAsync(new ControlRequest(op));
    Console.WriteLine(response.Ok ? show(response) : $"error: {response.Error}");
    return response.Ok ? 0 : 1;
}

async Task<int> PairAsync(string link, string? addressOverride)
{
    var query = HttpUtility.ParseQueryString(new Uri(link).Query);
    var port = query["p"]!;
    string[] addresses = addressOverride is not null
        ? [addressOverride]
        : [.. query["a"]!.Split(',').Select(a => a.Contains(':', StringComparison.Ordinal) ? a : $"{a}:{port}")];
    var fingerprint = query["fp"]!;
    var code = Base64Url.DecodeFromChars(query["c"]);
    foreach (var address in addresses)
    {
        try
        {
            using var ssl = await ConnectAsync(address, fingerprint);
            var reader = new FrameReader(ssl, FrameChannel.Tls);
            await ssl.WriteAsync(Frames.Pair(code, "devclient"));
            var answer = await reader.ReadAsync(CancellationToken.None) ?? throw new IOException("closed");
            if (answer.Type != FrameType.Paired)
            {
                Console.WriteLine($"rejected, reason {answer.Payload.Span[0]}");
                return 1;
            }
            var id = Convert.ToHexStringLower(answer.Payload.Span[..Frames.ID_SIZE]);
            var token = Convert.ToHexStringLower(answer.Payload.Span[Frames.ID_SIZE..]);
            File.WriteAllText(STATE_FILE, JsonSerializer.Serialize(new State(address, fingerprint, id, token), json));
            Console.WriteLine($"paired via {address}, device id {id}");
            return 0;
        }
        catch (Exception ex) when (ex is SocketException or IOException or AuthenticationException or OperationCanceledException)
        {
            Console.WriteLine($"{address}: {ex.Message}");
        }
    }
    return 1;
}

async Task<int> SessionAsync(Func<SslStream, Task> action)
{
    var state = JsonSerializer.Deserialize<State>(File.ReadAllText(STATE_FILE)) ?? throw new InvalidOperationException("run pair first");
    using var ssl = await ConnectAsync(state.Address, state.Fingerprint);
    var reader = new FrameReader(ssl, FrameChannel.Tls);
    await ssl.WriteAsync(Frames.Hello(Convert.FromHexString(state.Id), Convert.FromHexString(state.Token)));
    var answer = await reader.ReadAsync(CancellationToken.None);
    if (answer?.Type != FrameType.Welcome)
    {
        Console.WriteLine($"rejected: {answer?.Type}, reason {(answer is { Payload.Length: > 0 } a ? a.Payload.Span[0] : 0)}");
        return 1;
    }
    Console.WriteLine($"welcome from {Encoding.UTF8.GetString(answer.Value.Payload.Span)}");
    using var stop = new CancellationTokenSource();
    var printer = PrintFramesAsync(reader, stop.Token);
    await action(ssl);
    await Task.Delay(500);
    await stop.CancelAsync();
    try
    {
        await printer;
    }
    catch (Exception ex) when (ex is OperationCanceledException or IOException)
    {
        // Session over.
    }
    return 0;
}

static async Task PrintFramesAsync(FrameReader reader, CancellationToken cancellationToken)
{
    while (await reader.ReadAsync(cancellationToken) is { } frame)
    {
        var detail = frame.Type == FrameType.State ? $" ready={frame.Payload.Span[0] & 1}" : "";
        Console.WriteLine($"{DateTime.Now:HH:mm:ss.fff} {frame.Type}{detail}");
    }
}

static async Task TypeAsync(SslStream ssl, string digits)
{
    foreach (var digit in digits)
    {
        if (digit is < '0' or > '9') throw new ArgumentException("digits only");
        // Linux KEY_1..KEY_9 are 2..10, KEY_0 is 11.
        await TapAsync(ssl, (ushort)(digit == '0' ? 11 : digit - '0' + 1));
    }
}

static async Task TapAsync(SslStream ssl, ushort code)
{
    await ssl.WriteAsync(Frames.Key(code, 1));
    await Task.Delay(30);
    await ssl.WriteAsync(Frames.Key(code, 0));
    await Task.Delay(30);
}

// Right Shift taps: harmless on any screen; the service measures each key from TLS read to SendInput return.
static async Task BenchAsync(SslStream ssl, int taps)
{
    for (var i = 0; i < taps; i++)
    {
        await ssl.WriteAsync(Frames.Key(54, 1));
        await Task.Delay(5);
        await ssl.WriteAsync(Frames.Key(54, 0));
        await Task.Delay(5);
    }
}

// Finish line 7 breakdown: right Shift taps ~15 ms apart (tablet-like pace), then the service's per-part percentiles.
// The parts can only be read on the PC itself (control pipe); from another machine run "devclient latency" there.
async Task<int> LatencyBenchAsync(int taps)
{
    var sent = await SessionAsync(async ssl =>
    {
        for (var i = 0; i < taps; i++)
        {
            await ssl.WriteAsync(Frames.Key(54, 1));
            await Task.Delay(15);
            await ssl.WriteAsync(Frames.Key(54, 0));
            await Task.Delay(15);
        }
    });
    if (sent != 0) return sent;
    try
    {
        return await ControlAsync(ControlOps.LATENCY_BREAKDOWN, r => Breakdown(r.LatencyBreakdown!));
    }
    catch (Exception ex) when (ex is IOException or TimeoutException or UnauthorizedAccessException)
    {
        Console.WriteLine("control pipe not reachable here; run \"devclient latency\" on the PC within a minute");
        return 0;
    }
}

static string Breakdown(IReadOnlyList<LatencySegment> parts) =>
    "part        samples     p50 us     p99 us     max us" + Environment.NewLine
    + string.Join(Environment.NewLine, parts.Select(p => $"{p.Name,-10}{p.Samples,9}{p.P50Micros,11}{p.P99Micros,11}{p.MaxMicros,11}"));

static async Task WatchAsync(SslStream ssl, int seconds)
{
    for (uint i = 0; i < seconds / 5; i++)
    {
        await ssl.WriteAsync(Frames.Ping(i));
        await Task.Delay(TimeSpan.FromSeconds(5));
    }
}

static async Task<SslStream> ConnectAsync(string address, string fingerprint)
{
    var colon = address.LastIndexOf(':');
    var host = address[..colon];
    var tcp = new TcpClient { NoDelay = true };
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
    await tcp.ConnectAsync(host, int.Parse(address[(colon + 1)..], CultureInfo.InvariantCulture), timeout.Token);
    var ssl = new SslStream(tcp.GetStream(), false, (_, certificate, _, _) => certificate is not null && Fingerprint(certificate) == fingerprint);
    await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions { TargetHost = host, EnabledSslProtocols = SslProtocols.Tls13 }, timeout.Token);
    return ssl;
}

static string Fingerprint(X509Certificate certificate)
{
    using var x509 = X509CertificateLoader.LoadCertificate(certificate.GetRawCertData());
    return Base64Url.EncodeToString(SHA256.HashData(x509.PublicKey.ExportSubjectPublicKeyInfo()));
}

static int Usage()
{
    Console.WriteLine("devclient status | pair-uri | logs | pair <keyrelay link> [host:port] | hello | type <digits> | key <linux code> | sas | lock | bench <taps> | watch <seconds> | latency | latency-bench <taps> | heartbeats <seconds>");
    return 64;
}

internal sealed record State(string Address, string Fingerprint, string Id, string Token);
