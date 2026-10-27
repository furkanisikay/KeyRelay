using System.Security.Cryptography.X509Certificates;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Control;
using KeyRelay.Service.Native;
using KeyRelay.Service.Settings;

namespace KeyRelay.Service.Relay;

/// <summary>Collaborators of the tablet side, grouped so RelayHub and RelaySession take one parameter for them.</summary>
internal sealed record RelayContext(
    X509Certificate2 Certificate,
    DeviceRegistry Devices,
    PairingCodes Pairing,
    AuthThrottle Throttle,
    AgentLink Agent,
    RecentKeys Recent,
    SendSas SendSas,
    SettingsStore Settings,
    ILogger Logger)
{
    /// <summary>Time from accept to a verified HELLO, pairing included (spec: 5 s).</summary>
    internal TimeSpan HelloTimeout { get; init; } = TimeSpan.FromSeconds(5);

    /// <summary>
    /// Connections in TLS handshake or awaiting HELLO, counted apart for loopback (Funnel) and direct sources so a
    /// flood through Funnel cannot lock out a tablet on the LAN. Beyond it a connection is closed at once.
    /// </summary>
    internal int MaxPendingHandshakes { get; init; } = 8;

    /// <summary>
    /// Accepts PAIR from loopback. Off in the service: Funnel traffic arrives from loopback, and pairing from the
    /// internet would turn a leaked QR into a new device. Tests connect over loopback and turn it on.
    /// </summary>
    internal bool AllowLoopbackPairing { get; init; }

    /// <summary>Clock of the security log budget.</summary>
    internal TimeProvider Time { get; init; } = TimeProvider.System;
}
