using KeyRelay.Service;
using KeyRelay.Service.Agent;
using KeyRelay.Service.Control;
using KeyRelay.Service.Native;
using KeyRelay.Service.Relay;
using KeyRelay.Service.Session;
using KeyRelay.Service.Settings;
using KeyRelay.Service.Setup;
using Microsoft.Extensions.Hosting.WindowsServices;

if (args.Length > 0 && args[0] == "--agent") return AgentHost.RunAgent(args);
if (args.Length > 0 && args[0] is "install" or "uninstall") return await Installer.RunAsync(args).ConfigureAwait(false);

var data = ServicePaths.DATA_DIRECTORY;
// A recreated folder must not inherit ProgramData's ACL.
Installer.SecureDataDirectory(data);
using var logs = new LogBuffer();
var builder = Host.CreateApplicationBuilder(args);
builder.Logging.AddProvider(logs);
var services = builder.Services;
services.AddWindowsService(options => options.ServiceName = Installer.SERVICE_NAME);
if (WindowsServiceHelpers.IsWindowsService()) services.AddSingleton<IHostLifetime, SessionAwareServiceLifetime>();
services.AddSingleton(TimeProvider.System);
services.AddSingleton(logs);
services.AddSingleton<SessionSignal>();
services.AddSingleton<LatencyStats>();
services.AddSingleton(provider =>
{
    var link = provider.GetRequiredService<AgentLink>();
    var supervisor = provider.GetRequiredService<AgentSupervisor>();
    var recent = new RecentKeys(provider.GetRequiredService<TimeProvider>(), () => link.DesktopName, () => supervisor.ActiveSession);
    link.DesktopChanged += _ => recent.OnDesktopChanged();
    return recent;
});
services.AddSingleton(AgentLinkOptions.Production());
services.AddSingleton<AgentLink>();
services.AddSingleton<AgentSupervisor>();
services.AddHostedService(provider => provider.GetRequiredService<AgentSupervisor>());
services.AddSingleton(new SettingsStore(data));
services.AddSingleton(provider => new DeviceRegistry(data, provider.GetRequiredService<TimeProvider>(), provider.GetRequiredService<ILogger<DeviceRegistry>>()));
services.AddSingleton<PairingCodes>();
services.AddSingleton<AuthThrottle>();
services.AddSingleton(_ => CertificateStore.LoadOrCreate(data));
services.AddSingleton(new SendSas(() => Sas.SendSAS(false)));
services.AddSingleton(provider => ActivatorUtilities.CreateInstance<RelayContext>(provider, provider.GetRequiredService<ILogger<RelayHub>>()));
services.AddSingleton<RelayHub>();
services.AddHostedService<RelayListener>();
services.AddSingleton(ControlOptions.Production());
services.AddSingleton<ControlContext>();
services.AddSingleton<ControlHandler>();
services.AddHostedService<ControlServer>();
await builder.Build().RunAsync().ConfigureAwait(false);
return 0;
