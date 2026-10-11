namespace KeyRelay.Protocol.Control;

/// <summary>Operations of the control pipe (tray app to service).</summary>
public static class ControlOps
{
    public const string STATUS = "status";
    public const string PAIR_START = "pair-start";
    public const string PAIR_STOP = "pair-stop";
    public const string DEVICES = "devices";
    public const string REVOKE = "revoke";
    public const string NETWORK = "network";
    public const string SET_FUNNEL = "set-funnel";
    public const string LOGS = "logs";

    /// <summary>Returns the last key presses and keeps the service collecting them for a few seconds; sent only while the tray's key strip is on.</summary>
    public const string RECENT_KEYS = "recent-keys";

    /// <summary>Stops collecting key presses and forgets them (strip turned off, page left, window hidden).</summary>
    public const string RECENT_KEYS_STOP = "recent-keys-stop";

    /// <summary>Diagnostic, read only: latency of the last minute split into its parts (finish line 7).</summary>
    public const string LATENCY_BREAKDOWN = "latency-breakdown";
}

/// <summary>Error codes in ControlResponse.Error; the tray maps them to user text.</summary>
public static class ControlErrors
{
    public const string UNKNOWN_OP = "unknown_op";
    public const string UNKNOWN_DEVICE = "unknown_device";
    public const string INVALID_FUNNEL = "invalid_funnel_address";

    /// <summary>A command that changes state came from a session other than the one keys are typed into.</summary>
    public const string WRONG_SESSION = "wrong_session";

    /// <summary>The service failed while answering; the reason is in its log.</summary>
    public const string INTERNAL_ERROR = "internal_error";

    /// <summary>On a successful DEVICES answer: devices.json could not be read and was set aside; the list started empty.</summary>
    public const string DEVICES_SET_ASIDE = "devices_set_aside";
}

/// <summary>One request on the control pipe.</summary>
public sealed record ControlRequest(string Op, string? DeviceId = null, string? FunnelAddress = null);

/// <summary>One answer on the control pipe; only the member matching the request is set.</summary>
public sealed record ControlResponse
{
    public bool Ok { get; init; } = true;

    public string? Error { get; init; }

    public ControlStatus? Status { get; init; }

    public PairingInfo? Pairing { get; init; }

    public IReadOnlyList<DeviceInfo>? Devices { get; init; }

    public NetworkInfo? Network { get; init; }

    public IReadOnlyList<string>? Logs { get; init; }

    /// <summary>Gets up to 8 key names (Ctrl, A, F1 ...), oldest first; only in the RECENT_KEYS answer.</summary>
    public IReadOnlyList<string>? RecentKeys { get; init; }

    /// <summary>Gets the total and its parts (service, pipe, desktop, sendinput); only in the LATENCY_BREAKDOWN answer.</summary>
    public IReadOnlyList<LatencySegment>? LatencyBreakdown { get; init; }

    public static ControlResponse Fail(string error) => new() { Ok = false, Error = error };
}

/// <summary>What the tray shows on its status page and in its icon.</summary>
public sealed record ControlStatus
{
    public bool AgentConnected { get; init; }

    public uint? AgentSession { get; init; }

    public string InputDesktop { get; init; } = "";

    public bool Writable { get; init; }

    /// <summary>Gets the STATE bit the tablet sees: agent connected and its desktop writable.</summary>
    public bool Ready { get; init; }

    public string? ConnectedDevice { get; init; }

    /// <summary>Gets how many heartbeats (PINGs) the connected tablet sent on this connection; a change means it is alive.</summary>
    public long Heartbeats { get; init; }

    public int LatencyP50Micros { get; init; }

    public int LatencyP99Micros { get; init; }

    /// <summary>Gets the slowest key of each of the last 60 seconds, oldest first.</summary>
    public IReadOnlyList<int> LatencyPerSecondMicros { get; init; } = [];

    public long KeysToday { get; init; }

    public bool PairingActive { get; init; }

    public int DeviceCount { get; init; }

    /// <summary>Maps the status to the tray icon (spec: connected/ready, waiting, off/error).</summary>
    public TrayState ToTrayState() =>
        !AgentConnected ? TrayState.Error : ConnectedDevice is not null && Ready ? TrayState.Ready : TrayState.Waiting;
}

/// <summary>The three tray icon states.</summary>
public enum TrayState
{
    Error = 0,
    Waiting,
    Ready,
}

public static class TrayStates
{
    /// <summary>A missing status means the service is unreachable.</summary>
    public static TrayState From(ControlStatus? status) => status?.ToTrayState() ?? TrayState.Error;
}

/// <summary>A started pairing: the QR content, its addresses, when the code expires and the PC identity code (<see cref="PairingIdentity"/>).</summary>
public sealed record PairingInfo(string Link, IReadOnlyList<string> Addresses, DateTimeOffset ExpiresAt, string IdentityCode);

public sealed record DeviceInfo(string Id, string Name, DateTimeOffset AddedAt, DateTimeOffset? LastSeenAt, bool Connected);

/// <summary>One part of the key latency over the last minute: sample count, p50, p99 and maximum in microseconds.</summary>
public sealed record LatencySegment(string Name, int Samples, int P50Micros, int P99Micros, int MaxMicros);

public sealed record NetworkInfo(int Port, string FunnelAddress, string Fingerprint, IReadOnlyList<string> Addresses);
