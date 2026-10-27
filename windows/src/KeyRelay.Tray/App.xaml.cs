using System.Diagnostics.CodeAnalysis;
using H.NotifyIcon;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;

namespace KeyRelay.Tray;

/// <summary>Tray app: one instance per user, a tray icon all the time, the window on demand.</summary>
[SuppressMessage("Design", "CA1001", Justification = "WinUI never disposes Application; ExitApp releases the mutex and tray icon.")]
public sealed partial class App : Application
{
    private const string INSTANCE_NAME = @"Local\KeyRelay.Tray";
    private const string SHOW_EVENT_NAME = @"Local\KeyRelay.Tray.Show";
    private const int SLOW_POLL_AFTER_FAILURES = 3;
    private static readonly TimeSpan POLL_INTERVAL = TimeSpan.FromSeconds(1);
    // While the service is down a slower poll keeps the tray idle; the first success restores 1 s.
    private static readonly TimeSpan SLOW_POLL_INTERVAL = TimeSpan.FromSeconds(5);
    private Mutex? _instance;
    private EventWaitHandle? _showRequests;
    private TaskbarIcon? _trayIcon;
    private MenuFlyoutItem? _trayHeader;
    private DispatcherQueueTimer? _poll;
    private TrayState? _trayState;
    // Session count of the last answered poll; null after a failed one, so a restarted service sets a new baseline.
    private long? _seenSessions;
    private int _failures;
    private bool _polling;
    private bool _exiting;
    private bool _started;

    public App()
    {
        InitializeComponent();
        // The tray runs all day: once started, an error it survives leaves a line in tray.log instead of ending the process (0xC000027B).
        // During start-up it still ends the process: a half-started tray would hold the single-instance mutex with no icon.
        UnhandledException += (_, e) =>
        {
            Ui.Log(e.Exception, "unhandled");
            e.Handled = _started;
        };
        TaskScheduler.UnobservedTaskException += (_, e) =>
        {
            Ui.Log(e.Exception, "unobserved task");
            e.SetObserved();
        };
    }

    internal static new App Current => (App)Application.Current;

    internal ControlClient Control { get; } = new(ControlClient.PIPE_NAME);

    internal MainWindow? Shell { get; private set; }

    /// <summary>Gets the latest service status, or null while the service is unreachable.</summary>
    internal ControlStatus? Status { get; private set; }

    /// <summary>Raised on the UI thread after every poll.</summary>
    internal event Action? StatusChanged;

    /// <summary>Shows and focuses the window (tray click, second launch, Start menu).</summary>
    internal void ShowWindow()
    {
        if (Shell is null) return;
        Shell.AppWindow.Show();
        Shell.Activate();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var arguments = Environment.GetCommandLineArgs();
        if (arguments.Contains("--smoke"))
        {
            RunSmoke();
            return;
        }
        _instance = new Mutex(true, INSTANCE_NAME, out var first);
        _showRequests = new EventWaitHandle(false, EventResetMode.AutoReset, SHOW_EVENT_NAME);
        if (!first)
        {
            _showRequests.Set();
            Exit();
            return;
        }
        Shell = new MainWindow();
        Shell.AppWindow.Closing += (_, closing) =>
        {
            if (_exiting) return;
            closing.Cancel = true;
            // Leaving the current page unloads it, so the pair page withdraws its open code.
            Shell.Open("status");
            Shell.AppWindow.Hide();
        };
        CreateTrayIcon();
        ListenForShowRequests();
        StartPolling();
        if (!arguments.Contains("--background")) ShowWindow();
        _started = true;
    }

    private static async void RunSmoke()
    {
        try
        {
            var scenes = Ui.TrackForgotten();
            // As Shell, so the pairing scene can fly into the Devices menu item; the window is never shown.
            await (Current.Shell = new MainWindow()).OpenEveryPageAsync();
            // Scenes started without awaiting may still run; wait for all, a fault ends up in the catch below.
            while (scenes.Any(scene => !scene.IsCompleted)) await Task.WhenAll([.. scenes]);
            // No ForceCreate: the icon never reaches the notification area, but TaskbarIcon still touches Icon.Handle.
            using var probe = new TaskbarIcon();
            TrayState[] order = [TrayState.Error, TrayState.Waiting, TrayState.Ready, TrayState.Error];
            foreach (var state in order.Concat(order))
                probe.Icon = LoadIcon(state);
            // Anything else logged (unhandled, unobserved) fails the smoke too.
            if (Ui.LoggedErrors > 0) Environment.FailFast($"Smoke logged {Ui.LoggedErrors} errors; see tray.log.");
            Environment.Exit(0);
        }
        catch (Exception ex)
        {
            // Without a shown window WinUI swallows async void exceptions and the process would hang.
            Environment.FailFast("Smoke failed.", ex);
        }
    }

    private void CreateTrayIcon()
    {
        var open = new XamlUICommand();
        open.ExecuteRequested += (_, _) => ShowWindow();
        var exit = new XamlUICommand();
        exit.ExecuteRequested += (_, _) => ExitApp();
        var menu = new MenuFlyout();
        // Mockup flyout: the state as a header line, then open and exit with icons.
        _trayHeader = new MenuFlyoutItem { IsEnabled = false };
        menu.Items.Add(_trayHeader);
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(new MenuFlyoutItem { Text = Ui.Text("TrayOpen"), Command = open, Icon = new FontIcon { Glyph = "" } });
        menu.Items.Add(new MenuFlyoutItem { Text = Ui.Text("TrayExit"), Command = exit, Icon = new FontIcon { Glyph = "" } });
        _trayIcon = new TaskbarIcon { ContextFlyout = menu, LeftClickCommand = open, NoLeftClickDelay = true };
        // false keeps the process out of Efficiency Mode so the 1 s status poll stays on time.
        _trayIcon.ForceCreate(false);
        UpdateTrayIcon();
    }

    // TaskbarIcon disposes the previous Icon on every change, so each change needs a fresh instance.
    private static System.Drawing.Icon LoadIcon(TrayState state) =>
        new(Path.Combine(AppContext.BaseDirectory, "Assets", IconFile(state)));

    private static string IconFile(TrayState state) => state switch
    {
        TrayState.Ready => "tray-ready.ico",
        TrayState.Waiting => "tray-waiting.ico",
        _ => "tray-error.ico",
    };

    private void UpdateTrayIcon()
    {
        var state = TrayStates.From(Status);
        if (_trayIcon is null || state == _trayState) return;
        _trayState = state;
        _trayIcon.Icon = LoadIcon(state);
        _trayIcon.ToolTipText = Ui.StateText(state);
        if (_trayHeader is not null) _trayHeader.Text = Ui.StateText(state);
    }

    private void StartPolling()
    {
        _poll = DispatcherQueue.GetForCurrentThread().CreateTimer();
        _poll.Interval = POLL_INTERVAL;
        _poll.Tick += (_, _) => Ui.Forget(PollAsync(), "poll");
        _poll.Start();
    }

    private async Task PollAsync()
    {
        if (_polling) return;
        _polling = true;
        try
        {
            Status = (await Control.SendAsync(new ControlRequest(ControlOps.STATUS))).Status;
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            Status = null;
        }
        finally
        {
            _polling = false;
        }
        if (_exiting) return;
        _failures = Status is null ? _failures + 1 : 0;
        var interval = _failures >= SLOW_POLL_AFTER_FAILURES ? SLOW_POLL_INTERVAL : POLL_INTERVAL;
        if (_poll is not null && _poll.Interval != interval)
        {
            _poll.Stop();
            _poll.Interval = interval;
            _poll.Start();
        }
        UpdateTrayIcon();
        NotifyNewSession();
        StatusChanged?.Invoke();
    }

    // Every tablet connection is announced, so one made with a stolen token does not go unseen; the session count also
    // catches one that ended between two polls.
    private void NotifyNewSession()
    {
        var status = Status;
        if (status is not null && _seenSessions is { } seen && status.Sessions > seen && status.LastSessionDevice is { } device)
        {
            var message = Ui.Format(status.LastSessionViaFunnel ? "NotifyConnectedFunnel" : "NotifyConnectedDirect", device);
            _trayIcon?.ShowNotification(Ui.Text("NotifyConnectedTitle"), message);
        }
        _seenSessions = status?.Sessions;
    }

    private void ListenForShowRequests()
    {
        var dispatcher = DispatcherQueue.GetForCurrentThread();
        var requests = _showRequests!;
        var listener = new Thread(() =>
        {
            while (requests.WaitOne()) dispatcher.TryEnqueue(ShowWindow);
        })
        { IsBackground = true, Name = "show-requests" };
        listener.Start();
    }

    private void ExitApp()
    {
        _exiting = true;
        _poll?.Stop();
        _trayIcon?.Dispose();
        Shell?.Close();
        _instance?.Dispose();
        Exit();
    }
}
