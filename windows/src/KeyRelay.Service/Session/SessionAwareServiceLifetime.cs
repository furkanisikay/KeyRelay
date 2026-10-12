using System.ServiceProcess;
using Microsoft.Extensions.Hosting.WindowsServices;
using KeyRelay.Service.Control;
using Microsoft.Extensions.Options;

namespace KeyRelay.Service.Session;

/// <summary>The Windows service lifetime plus session change notifications, which the default lifetime does not request.</summary>
internal sealed class SessionAwareServiceLifetime : WindowsServiceLifetime
{
    private readonly SessionSignal _sessions;
    private readonly RecentKeys _recent;

    public SessionAwareServiceLifetime(IHostEnvironment environment, IHostApplicationLifetime applicationLifetime, ILoggerFactory loggerFactory,
        IOptions<HostOptions> hostOptions, IOptions<WindowsServiceLifetimeOptions> serviceOptions, SessionSignal sessions, RecentKeys recent)
        : base(environment, applicationLifetime, loggerFactory, hostOptions, serviceOptions)
    {
        _sessions = sessions;
        _recent = recent;
        CanHandleSessionChangeEvent = true;
    }

    protected override void OnSessionChange(SessionChangeDescription changeDescription)
    {
        _recent.OnSessionChange(changeDescription.Reason);
        _sessions.Notify();
        base.OnSessionChange(changeDescription);
    }
}
