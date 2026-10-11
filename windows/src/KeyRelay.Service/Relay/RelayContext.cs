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
}
