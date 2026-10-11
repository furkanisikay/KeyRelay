using KeyRelay.Protocol.Control;
using KeyRelay.Tray.Pages;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Graphics;

namespace KeyRelay.Tray;

/// <summary>The window: Mica with a navy tint, brand mark at the top of the menu, left navigation with the five pages of the approved mockup.</summary>
public sealed partial class MainWindow : Window
{
    // Mockup window size in effective pixels.
    private const int WIDTH = 1080;
    private const int HEIGHT = 720;
    private const double DEFAULT_DPI = 96.0;
    // Unreachable, agent missing, waiting for a tablet, ready, typing (key flight), unreachable again (disconnect scene).
    private static readonly ControlStatus?[] SMOKE_STATUSES =
    [
        null,
        new() { AgentConnected = true, InputDesktop = "Winlogon" },
        new() { AgentConnected = true, AgentSession = 1, InputDesktop = "Default", Writable = true, KeysToday = 12 },
        new() { AgentConnected = true, AgentSession = 1, InputDesktop = "Default", Writable = true, Ready = true, ConnectedDevice = "Tablet", LatencyP50Micros = 400, LatencyP99Micros = 1600, LatencyPerSecondMicros = [500, 2500, 900], KeysToday = 12 },
        new() { AgentConnected = true, AgentSession = 1, InputDesktop = "Default", Writable = true, Ready = true, ConnectedDevice = "Tablet", LatencyPerSecondMicros = [500, 2500, 900, 700], KeysToday = 15 },
        new() { AgentConnected = true, AgentSession = 1, InputDesktop = "Default", Writable = false, ConnectedDevice = "Tablet", KeysToday = 15 },
        null,
    ];

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(TitleBar);
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "KeyRelay.ico"));
        var scale = Ui.GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / DEFAULT_DPI;
        AppWindow.Resize(new SizeInt32((int)(WIDTH * scale), (int)(HEIGHT * scale)));
        AppWindow.Changed += (_, _) => UpdateShown();
        VisibilityChanged += (_, _) => UpdateShown();
        Activated += (_, _) => UpdateShown();
        Navigation.SelectedItem = Navigation.MenuItems[0];
    }

    /// <summary>Gets true while the window is visible and not minimized.</summary>
    internal bool IsShown { get; private set; }

    /// <summary>Raised when the window appears or disappears (hide to tray, minimize, restore).</summary>
    internal event Action? ShownChanged;

    private string? _newDeviceId;

    /// <summary>Selects a page by its navigation tag (status, devices, pair, network, logs).</summary>
    internal void Open(string tag) =>
        Navigation.SelectedItem = Navigation.MenuItems.OfType<NavigationViewItem>().First(item => (string)item.Tag == tag);

    /// <summary>Opens Devices after a pairing; the new device enters there with the paired highlight.</summary>
    /// <param name="newDeviceId">The device that just paired, or null when unknown.</param>
    internal void OpenDevices(string? newDeviceId)
    {
        _newDeviceId = newDeviceId;
        Open("devices");
    }

    /// <summary>Gets the device that just paired, once; the Devices page asks when it loads.</summary>
    internal string? TakeNewDevice() => Interlocked.Exchange(ref _newDeviceId, null);

    /// <summary>Opens and draws every page and plays every scene without showing the window; --smoke uses it to catch XAML, missing text and scene errors.</summary>
    /// <exception cref="InvalidOperationException">A page did not open.</exception>
    internal async Task OpenEveryPageAsync()
    {
        var now = DateTimeOffset.Now;
        foreach (var item in Navigation.MenuItems.OfType<NavigationViewItem>())
        {
            if (PageFor((string)item.Tag) is not { } page) continue;
            ContentFrame.Navigate(page);
            if (ContentFrame.Content?.GetType() != page) throw new InvalidOperationException($"Page '{item.Tag}' did not open.");
            switch (ContentFrame.Content)
            {
                case StatusPage status:
                    foreach (var sample in SMOKE_STATUSES) status.Render(sample);
                    status.RenderStrip(["Ctrl", "A", "⏎"]);
                    status.ShowIntro();
                    await status.CloseIntroAsync();
                    break;
                case DevicesPage devices:
                    // Connected, last seen, never seen: the three detail texts; then a failed and a successful revoke.
                    devices.Render([new("1", "Tablet", now, null, true), new("2", "Tablet", now, now, false), new("3", "Tablet", now, null, false)]);
                    await devices.RippleConnectedAsync();
                    await devices.PlayRevokeAsync("2", Task.FromResult<string?>("WrongSession"));
                    await devices.PlayRevokeAsync("2", Task.FromResult<string?>(null));
                    // Arrival after a pairing: rows make room, the new one is highlighted as paired.
                    await devices.ShowNewDeviceAsync([new("1", "Tablet", now, null, true), new("4", "Tablet", now.AddMinutes(1), now, true)], "4");
                    devices.Render([]);
                    devices.Render(null);
                    break;
                case PairPage pair:
                    // Running, success scene, running again, expired scene.
                    var pairing = new PairingInfo("keyrelay://", ["127.0.0.1"], now.AddMinutes(5), "TYIM-LHQO");
                    pair.Show(pairing, await PairPage.QrImageAsync("keyrelay://"));
                    await pair.PlaySuccessAsync();
                    pair.Show(pairing, await PairPage.QrImageAsync("keyrelay://"));
                    await pair.PlayExpiredAsync();
                    break;
                case NetworkPage network:
                    network.Render(null);
                    network.Render(new NetworkInfo(0, "", "", ["127.0.0.1"]));
                    network.Render(new NetworkInfo(47111, "pc.tailnet.ts.net:8443", "Xq3abcdefghijklmnopqrstuvwxyz0123456789abc9fA", ["pc.tailnet.ts.net:8443", "127.0.0.1:47111"]));
                    break;
                case LogsPage logs:
                    logs.Render(["09:41:02 INFO TlsListener: Tablet connected", "09:52:18 WARN AgentLink: Desktop Winlogon", "09:52:20 ERROR ControlServer: x | y", "line"]);
                    break;
            }
        }
    }

    private void UpdateShown()
    {
        var shown = AppWindow.IsVisible && AppWindow.Presenter is not OverlappedPresenter { State: OverlappedPresenterState.Minimized };
        if (shown == IsShown) return;
        IsShown = shown;
        ShownChanged?.Invoke();
    }

    private void OnSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem { Tag: string tag } || PageFor(tag) is not { } page) return;
        NavigationTransitionInfo transition = Ui.AnimationsEnabled ? new EntranceNavigationTransitionInfo() : new SuppressNavigationTransitionInfo();
        ContentFrame.Navigate(page, null, transition);
    }

    private static Type? PageFor(string tag) => tag switch
    {
        "status" => typeof(StatusPage),
        "devices" => typeof(DevicesPage),
        "pair" => typeof(PairPage),
        "network" => typeof(NetworkPage),
        "logs" => typeof(LogsPage),
        _ => null,
    };
}
