using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices.WindowsRuntime;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;
using QRCoder;
using Windows.Foundation;
using Windows.Storage.Streams;
using Windows.UI;
using Path = Microsoft.UI.Xaml.Shapes.Path;

namespace KeyRelay.Tray.Pages;

/// <summary>
/// Pair page: QR with a scan band, PC identity code as cells, countdown ring. When the tablet pairs, the QR
/// collapses into the code caps, they wire up, turn green, fold into one device card and fly into Devices;
/// an expired code dims with a red frame.
/// </summary>
public sealed partial class PairPage : Page
{
    /// <summary>Connected animation key from the Devices page's add button to the QR card.</summary>
    internal const string CARD_ANIMATION = "PairCard";
    private const int PIXELS_PER_MODULE = 10;
    private const double EXPIRED_OPACITY = 0.3;
    private const double QR_SIZE = 236;
    private const double CAP_WIDTH = 42;
    private const double CAP_HEIGHT = 54;
    private const double CAP_GAP = 18;
    private const int CAP_COLUMNS = 4;
    private const double CARD_SIZE = 80;
    // Tick path length (about 20 px) in stroke widths (3), rounded up.
    private const double TICK_DASH = 7;
    private const string BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static readonly TimeSpan SCRAMBLE_STEP = TimeSpan.FromMilliseconds(45);
    // Flight of the paired card into its Devices row.
    private static readonly TimeSpan FLIGHT = TimeSpan.FromMilliseconds(500);
    // The expired reason is read before the refresh icon turns; content timing, so it also runs with animations off.
    private static readonly TimeSpan EXPIRED_HOLD = TimeSpan.FromMilliseconds(1800);
    // Mockup ink (#0B111C) on white, RGBA.
    private static readonly byte[] INK = [0x0B, 0x11, 0x1C, 0xFF];
    private static readonly byte[] PAPER = [0xFF, 0xFF, 0xFF, 0xFF];
    private static readonly Color VIOLET = Color.FromArgb(255, 0x8B, 0x5C, 0xF6);
    private static readonly Color GREEN = Color.FromArgb(255, 0x2F, 0xD4, 0x9C);
    private readonly DispatcherQueueTimer _tick;
    private readonly DispatcherQueueTimer _scramble;
    private DateTimeOffset _expiresAt;
    private string _code = "";
    private int _scrambleStep;
    // Device count from the first poll that saw this code open; null until then, so a stale poll cannot read as success.
    private int? _devicesWhileActive;
    private bool _cardAnimated;
    private bool _active;
    // Bumped by every new code and on unload; a scene still playing for an older code stops at its next step.
    private int _shownCode;

    public PairPage()
    {
        InitializeComponent();
        AutomationProperties.SetName(QrImage, Ui.Text("PairQrName"));
        Lead.Text = Ui.Text("PairLead");
        _tick = DispatcherQueue.CreateTimer();
        _tick.Interval = TimeSpan.FromSeconds(1);
        _tick.Tick += (_, _) => Tick();
        _scramble = DispatcherQueue.CreateTimer();
        _scramble.Interval = SCRAMBLE_STEP;
        _scramble.Tick += (_, _) => Scramble();
        // Loaded/Unloaded instead of navigation events: --smoke navigates without loading, so it never opens a code.
        Loaded += (_, _) =>
        {
            if (App.Current.Shell is { } shell) shell.ShownChanged += OnShownChanged;
            Ui.Forget(StartAsync(), "pair start");
        };
        Unloaded += (_, _) =>
        {
            if (App.Current.Shell is { } shell) shell.ShownChanged -= OnShownChanged;
            // A success scene still playing stops at its next step.
            _shownCode++;
            Ui.Forget(StopAsync(), "pair stop");
        };
    }

    /// <summary>Shows a started pairing at rest: QR, identity code (decoded once), addresses, countdown.</summary>
    internal void Show(PairingInfo pairing, ImageSource qr)
    {
        Fx.Children.Clear();
        QrArea.Opacity = 1;
        QrImage.Source = qr;
        QrImage.Opacity = 1;
        QrCard.Opacity = Side.Opacity = 1;
        (QrScale.Rotation, QrScale.ScaleX, QrScale.ScaleY) = (0, 1, 1);
        ExpiredFrame.Opacity = 0;
        ExpiredText.Visibility = Visibility.Collapsed;
        SweepBand.Visibility = Visibility.Visible;
        Lead.Text = Ui.Text("PairLead");
        Lead.Foreground = Ui.Brush("MutedBrush");
        Result.IsOpen = false;
        _expiresAt = pairing.ExpiresAt;
        _code = pairing.IdentityCode;
        _active = true;
        _shownCode++;
        ShowIdentity();
        Addresses.Text = string.Join(Environment.NewLine, pairing.Addresses);
        Tick();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (Ui.AnimationsEnabled && ConnectedAnimationService.GetForCurrentView().GetAnimation(CARD_ANIMATION) is { } animation) _cardAnimated = animation.TryStart(QrCard);
    }

    private void OnShownChanged()
    {
        // Minimized or hidden: the code stays open, but the band and the countdown pause until the window is back.
        if (!_active) return;
        if (Ui.IsShown)
        {
            Tick();
            if (!_active) return;
            _tick.Start();
            if (Ui.AnimationsEnabled) SweepStoryboard.Begin();
        }
        else
        {
            _tick.Stop();
            SweepStoryboard.Stop();
        }
    }

    private async Task StopAsync()
    {
        _active = false;
        _tick.Stop();
        _scramble.Stop();
        SweepStoryboard.Stop();
        // An open code lets anyone who sees it pair; leaving the page withdraws it.
        try
        {
            await App.Current.Control.SendAsync(new ControlRequest(ControlOps.PAIR_STOP));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            // Unreachable service has no open code either.
        }
    }

    private async void OnNewCode(object sender, RoutedEventArgs e) => await StartAsync();

    private void OnClose(object sender, RoutedEventArgs e) => App.Current.Shell?.Open("devices");

    private async Task StartAsync()
    {
        Result.IsOpen = false;
        // The connected animation already brought the card in; only the first code may skip the entrance.
        var cardAnimated = _cardAnimated;
        _cardAnimated = false;
        ControlResponse? response;
        try
        {
            response = await App.Current.Control.SendAsync(new ControlRequest(ControlOps.PAIR_START));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            response = null;
        }
        if (response?.Pairing is not { } pairing)
        {
            // A previous code may still be on screen; it must not look valid.
            Rest();
            QrImage.Opacity = EXPIRED_OPACITY;
            ShowResult(InfoBarSeverity.Error, Ui.FailureKey(response));
            return;
        }
        _devicesWhileActive = null;
        var qr = await QrImageAsync(pairing.Link);
        // Left the page while waiting: Unloaded already sent PAIR_STOP after this PAIR_START.
        if (!IsLoaded) return;
        Show(pairing, qr);
        // Minimized while the code was being fetched: OnShownChanged starts the band and the countdown on restore.
        if (!Ui.IsShown) return;
        _tick.Start();
        if (!Ui.AnimationsEnabled) return;
        if (!cardAnimated) EnterStoryboard.Begin();
        SweepStoryboard.Begin();
    }

    private void Tick()
    {
        var status = App.Current.Status;
        if (status is { PairingActive: true }) _devicesWhileActive ??= status.DeviceCount;
        else if (status is not null && status.DeviceCount > _devicesWhileActive)
        {
            Ui.Forget(PlaySuccessAsync(), "pair scene");
            return;
        }
        var remaining = _expiresAt - DateTimeOffset.Now;
        if (remaining <= TimeSpan.Zero)
        {
            Ui.Forget(PlayExpiredAsync(), "pair scene");
            return;
        }
        Countdown.Value = remaining.TotalSeconds;
        Remaining.Text = Ui.Format("PairRemaining", remaining);
    }

    /// <summary>Stops everything that runs while a code is open; the page then stays still.</summary>
    private void Rest()
    {
        _active = false;
        _tick.Stop();
        SweepStoryboard.Stop();
        // The entrance holds the card's opacity and scale; scenes need the card back on its local values.
        EnterStoryboard.Stop();
        SweepBand.Visibility = Visibility.Collapsed;
    }

    private void ShowResult(InfoBarSeverity severity, string key)
    {
        Result.Severity = severity;
        Result.Message = Ui.Text(key);
        Result.IsOpen = true;
    }

    private void ShowIdentity()
    {
        IdentityCells.Children.Clear();
        AutomationProperties.SetName(IdentityCells, _code);
        foreach (var c in _code)
        {
            if (c == '-')
            {
                IdentityCells.Children.Add(new TextBlock { Text = "-", Width = 12, FontFamily = Number, FontSize = 26, Foreground = Ui.Brush("MutedBrush"), TextAlignment = TextAlignment.Center, VerticalAlignment = VerticalAlignment.Center });
                continue;
            }
            IdentityCells.Children.Add(new Border
            {
                Width = CAP_WIDTH,
                Height = CAP_HEIGHT,
                CornerRadius = new CornerRadius(8),
                Background = Ui.Brush("Glass2Brush"),
                BorderBrush = Ui.Brush("LineBrush"),
                BorderThickness = new Thickness(1),
                Child = new TextBlock { Text = c.ToString(), FontFamily = Number, FontSize = 26, FontWeight = FontWeights.ExtraBold, Foreground = Ui.Brush("AccentLightBrush"), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
            });
        }
        // The code decodes once, cell by cell (about 0.5 s); comparison is always with the final value.
        _scrambleStep = 0;
        if (Ui.AnimationsEnabled) _scramble.Start();
    }

    private static FontFamily Number => (FontFamily)Application.Current.Resources["NumberFont"];

    private void Scramble()
    {
        var elapsed = ++_scrambleStep * SCRAMBLE_STEP.TotalMilliseconds;
        var locked = 0;
        var cell = 0;
        foreach (var child in IdentityCells.Children)
        {
            if (child is not Border { Child: TextBlock text } box) continue;
            var lockAt = 80 + cell * 50;
            var final = _code.Replace("-", "", StringComparison.Ordinal)[cell++].ToString();
            if (elapsed < lockAt)
            {
                text.Text = BASE32[Random.Shared.Next(BASE32.Length)].ToString();
                continue;
            }
            locked++;
            if (text.Text == final && elapsed - lockAt >= SCRAMBLE_STEP.TotalMilliseconds) continue;
            text.Text = final;
            var pop = Ui.Transform(box);
            Ui.Forget(Ui.PlayAsync(Ui.Anim(pop, "ScaleX", 1.25, 1, 300, 0, Ui.Spring), Ui.Anim(pop, "ScaleY", 1.25, 1, 300, 0, Ui.Spring)), "pair scene");
        }
        if (locked == cell) _scramble.Stop();
    }

    /// <summary>
    /// Success scene, one act after the other (about 2.9 s, then 0.5 s flight): scan pass 0.4 s, QR collapse with the
    /// code caps coming out 0.6 s, wires 0.5 s, violet to green 0.4 s, one card with its tick 0.6 s, a ring while it rests
    /// 0.4 s, then the window switches to Devices and the card flies into the new row (connected animation, 0.5 s).
    /// </summary>
    internal async Task PlaySuccessAsync()
    {
        if (!_active) return;
        var code = _shownCode;
        bool Stale() => code != _shownCode;
        Rest();
        _scramble.Stop();
        ShowIdentityFinal();
        var deviceId = NewDeviceIdAsync();
        var clock = Stopwatch.StartNew();
        // Scene timing: --smoke prints it (stdout), on a device DebugView shows it; an act ends where the next starts.
        void Act(string name)
        {
            var line = string.Create(CultureInfo.InvariantCulture, $"KeyRelay tray: pair scene act={name} t={clock.ElapsedMilliseconds}ms animations={Ui.AnimationsEnabled}");
            Console.WriteLine(line);
            Trace.WriteLine(line);
        }
        var cx = PairGrid.TransformToVisual(Fx).TransformPoint(new Point(PairGrid.ActualWidth / 2, 0)).X;
        var cy = PairGrid.TransformToVisual(Fx).TransformPoint(new Point(0, QR_SIZE / 2 + 14)).Y;
        var qr = QrCard.TransformToVisual(Fx).TransformPoint(new Point(QR_SIZE / 2, QR_SIZE / 2));
        // Act 1 (0.4 s): the tablet read the code, one scan pass.
        Act("scan");
        var beam = Ui.Transform(Beam);
        await Ui.PlayAsync(
            Ui.Keys(beam, "TranslateY", (0, 0, null), (340, QR_SIZE - 6, Ui.EaseOut)),
            Ui.Keys(Beam, "Opacity", (0, 1, null), (340, 1, null), (400, 0, null)));
        if (Stale()) return;
        // Act 2 (0.6 s): QR collapses, the caps come out of its centre and settle in a 4 x 2 grid.
        Act("collapse");
        var chars = _code.Replace("-", "", StringComparison.Ordinal);
        var caps = chars.Select(c => Cap(c.ToString())).ToArray();
        var rows = (caps.Length + CAP_COLUMNS - 1) / CAP_COLUMNS;
        Point Slot(int i) => new(cx + (i % CAP_COLUMNS - (CAP_COLUMNS - 1) / 2.0) * (CAP_WIDTH + CAP_GAP) - CAP_WIDTH / 2, cy + (i / CAP_COLUMNS - (rows - 1) / 2.0) * (CAP_HEIGHT + CAP_GAP) - CAP_HEIGHT / 2);
        var scene = new List<Timeline>
        {
            Ui.Anim(QrScale, "Rotation", 0, 45, 300, 0, Ui.EaseIn),
            Ui.Anim(QrScale, "ScaleX", 1, 0.08, 300, 0, Ui.EaseIn), Ui.Anim(QrScale, "ScaleY", 1, 0.08, 300, 0, Ui.EaseIn),
            Ui.Anim(QrArea, "Opacity", 1, 0, 300), Ui.Anim(Side, "Opacity", 1, 0, 180),
        };
        // The whole QR area goes, glow included: the glow is a sibling of the card, not part of it.
        QrArea.Opacity = Side.Opacity = 0;
        for (var i = 0; i < caps.Length; i++)
        {
            var slot = Slot(i);
            Place(caps[i].Box, slot);
            var (ox, oy, delay, tilt) = (qr.X - slot.X - CAP_WIDTH / 2, qr.Y - slot.Y - CAP_HEIGHT / 2, i * 30.0, i % 2 == 1 ? 9 : -9);
            var move = Ui.Transform(caps[i].Box);
            scene.Add(Ui.Keys(move, "TranslateX", (0, ox, null), (delay, ox, null), (delay + 195, ox / 2, Ui.EaseOut), (delay + 390, 0, Ui.Spring)));
            scene.Add(Ui.Keys(move, "TranslateY", (0, oy, null), (delay, oy, null), (delay + 195, oy / 2 - 30, Ui.EaseOut), (delay + 390, 0, Ui.Spring)));
            scene.Add(Ui.Keys(move, "Rotation", (0, 0, null), (delay, 0, null), (delay + 195, tilt, null), (delay + 390, 0, Ui.Spring)));
            scene.Add(Ui.Keys(move, "ScaleX", (0, 0.2, null), (delay, 0.2, null), (delay + 195, 1.1, null), (delay + 390, 1, Ui.Spring)));
            scene.Add(Ui.Keys(move, "ScaleY", (0, 0.2, null), (delay, 0.2, null), (delay + 195, 1.1, null), (delay + 390, 1, Ui.Spring)));
            scene.Add(Ui.Keys(caps[i].Box, "Opacity", (0, 0, null), (delay, 0, null), (delay + 100, 1, null)));
        }
        Ui.Forget(SwapLeadAsync("PairVerifying"), "pair scene");
        await Ui.PlayAsync([.. scene]);
        if (Stale()) return;
        // Act 3 (0.5 s): wires draw between neighbours.
        Act("wires");
        var wires = Wires(caps.Length, Slot);
        await Ui.PlayAsync([.. wires.Select((wire, k) => Ui.Anim(wire.Line, "StrokeDashOffset", CAP_GAP / 3, 0, 230, k * 30))]);
        if (Stale()) return;
        // Act 4 (0.4 s): the colour turns from violet to green cap by cap.
        Act("green");
        scene.Clear();
        for (var i = 0; i < caps.Length; i++)
        {
            foreach (var brush in caps[i].Brushes) scene.Add(Green(brush, i * 25));
            foreach (var wire in wires.Where(wire => wire.To == i)) scene.Add(Green(wire.Brush, i * 25));
        }
        await Ui.PlayAsync([.. scene]);
        if (Stale()) return;
        // Act 5 (0.6 s): wires fade, caps spin into the centre, one device card pops out of them and its tick draws.
        Act("card");
        var card = DeviceCard(out var tick);
        Place(card, new Point(cx - CARD_SIZE / 2, cy - CARD_SIZE / 2));
        scene.Clear();
        foreach (var wire in wires)
        {
            wire.Line.Opacity = 0;
            scene.Add(Ui.Anim(wire.Line, "Opacity", 1, 0, 150));
        }
        for (var i = 0; i < caps.Length; i++)
        {
            var at = Slot(i);
            var (dx, dy, delay, spin) = (cx - at.X - CAP_WIDTH / 2, cy - at.Y - CAP_HEIGHT / 2, i * 15.0, i % 2 == 1 ? 140 : -140);
            var move = Ui.Transform(caps[i].Box);
            caps[i].Box.Opacity = 0;
            scene.Add(Ui.Anim(move, "TranslateX", 0, dx, 300, delay, Ui.EaseIn));
            scene.Add(Ui.Anim(move, "TranslateY", 0, dy, 300, delay, Ui.EaseIn));
            scene.Add(Ui.Anim(move, "Rotation", 0, spin, 300, delay));
            scene.Add(Ui.Anim(move, "ScaleX", 1, 0.2, 300, delay));
            scene.Add(Ui.Anim(move, "ScaleY", 1, 0.2, 300, delay));
            scene.Add(Ui.Anim(caps[i].Box, "Opacity", 1, 0, 300, delay));
        }
        var pop = Ui.Transform(card);
        scene.Add(Ui.Keys(pop, "ScaleX", (0, 0, null), (240, 0, null), (440, 1.15, Ui.EaseOut), (560, 1, null)));
        scene.Add(Ui.Keys(pop, "ScaleY", (0, 0, null), (240, 0, null), (440, 1.15, Ui.EaseOut), (560, 1, null)));
        scene.Add(Ui.Keys(pop, "Rotation", (0, -90, null), (240, -90, null), (440, 6, Ui.EaseOut), (560, 0, null)));
        tick.StrokeDashOffset = 0;
        scene.Add(Ui.Anim(tick, "StrokeDashOffset", TICK_DASH, 0, 180, 420));
        Ui.Forget(SwapLeadAsync("PairLead"), "pair scene");
        await Ui.PlayAsync([.. scene]);
        if (Stale()) return;
        // Act 6 (0.4 s): the card rests while one ring goes out; the result shows on Devices, not here.
        Act("rest");
        var ring = new Border { Width = CARD_SIZE, Height = CARD_SIZE, CornerRadius = new CornerRadius(30), BorderThickness = new Thickness(2), BorderBrush = Ui.Brush("ReadyBrush"), Opacity = 0 };
        Place(ring, new Point(cx - CARD_SIZE / 2, cy - CARD_SIZE / 2));
        var wave = Ui.Transform(ring);
        await Ui.PlayAsync(Ui.Anim(wave, "ScaleX", 1, 2.6, 400, 0, Ui.EaseOut), Ui.Anim(wave, "ScaleY", 1, 2.6, 400, 0, Ui.EaseOut), Ui.Anim(ring, "Opacity", 0.9, 0, 400));
        Fx.Children.Remove(ring);
        // Act 7 (0.5 s): the window switches to Devices and the card flies into the new row there (connected animation).
        // Leaving this page unloads it, so it starts fresh next time.
        var newDevice = await deviceId;
        if (Stale() || App.Current.Shell is not { } shell) return;
        Act("devices");
        if (Ui.AnimationsEnabled && Ui.IsShown)
        {
            var service = ConnectedAnimationService.GetForCurrentView();
            service.DefaultDuration = FLIGHT;
            service.PrepareToAnimate(DevicesPage.NEW_DEVICE_ANIMATION, card);
        }
        shell.OpenDevices(newDevice);
    }

    // The newest paired device is the one that just paired; null when the service cannot say.
    private static async Task<string?> NewDeviceIdAsync()
    {
        try
        {
            var devices = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.DEVICES))).Devices;
            return devices is { Count: > 0 } ? devices.MaxBy(device => device.AddedAt)!.Id : null;
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            return null;
        }
    }

    /// <summary>Failure end: the QR dims inside a red frame, the ring empties, the reason stays; then the refresh icon turns once.</summary>
    internal async Task PlayExpiredAsync()
    {
        if (!_active) return;
        Rest();
        ShowIdentityFinal();
        Countdown.Value = 0;
        Remaining.Text = Ui.Format("PairRemaining", TimeSpan.Zero);
        ExpiredText.Visibility = Visibility.Visible;
        QrImage.Opacity = EXPIRED_OPACITY;
        ExpiredFrame.Opacity = 1;
        Lead.Foreground = Ui.Brush("ErrorBrush");
        Ui.Forget(SwapLeadAsync("PairExpired"), "pair scene");
        await Ui.PlayAsync(Ui.Anim(QrImage, "Opacity", 1, EXPIRED_OPACITY, 300), Ui.Anim(ExpiredFrame, "Opacity", 0, 1, 300));
        await Task.Delay(EXPIRED_HOLD);
        await Ui.PlayAsync(Ui.Anim(Ui.Transform(NewCodeIcon), "Rotation", 0, 360, 500, 0, Ui.EaseOut));
    }

    private void ShowIdentityFinal()
    {
        var chars = _code.Replace("-", "", StringComparison.Ordinal);
        var i = 0;
        foreach (var child in IdentityCells.Children)
            if (child is Border { Child: TextBlock text }) text.Text = chars[i++].ToString();
    }

    private async Task SwapLeadAsync(string key)
    {
        var lift = Ui.Transform(Lead);
        await Ui.PlayAsync(Ui.Anim(Lead, "Opacity", 1, 0, 140), Ui.Anim(lift, "TranslateY", 0, -6, 140));
        Lead.Text = Ui.Text(key);
        await Ui.PlayAsync(Ui.Anim(Lead, "Opacity", 0, 1, 240), Ui.Anim(lift, "TranslateY", 8, 0, 240, 0, Ui.EaseOut));
    }

    private void Place(FrameworkElement element, Point at)
    {
        Canvas.SetLeft(element, at.X);
        Canvas.SetTop(element, at.Y);
        Fx.Children.Add(element);
    }

    private static (Border Box, SolidColorBrush[] Brushes) Cap(string text)
    {
        SolidColorBrush[] brushes = [new(VIOLET), new(Color.FromArgb(0x33, VIOLET.R, VIOLET.G, VIOLET.B)), new(VIOLET)];
        var box = new Border
        {
            Width = CAP_WIDTH,
            Height = CAP_HEIGHT,
            CornerRadius = new CornerRadius(8),
            BorderThickness = new Thickness(1.5),
            BorderBrush = brushes[0],
            Background = brushes[1],
            Child = new TextBlock { Text = text, FontFamily = Number, FontSize = 26, FontWeight = FontWeights.ExtraBold, Foreground = brushes[2], HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
        };
        return (box, brushes);
    }

    // Neighbour wires in mockup order: top row, columns, bottom row. Each is one dash drawn by its offset.
    private List<(Line Line, SolidColorBrush Brush, int To)> Wires(int count, Func<int, Point> slot)
    {
        var pairs = new List<(int From, int To, bool Across)>();
        for (var c = 0; c < CAP_COLUMNS - 1 && c + 1 < count; c++) pairs.Add((c, c + 1, true));
        for (var c = 0; c < CAP_COLUMNS && c + CAP_COLUMNS < count; c++) pairs.Add((c, c + CAP_COLUMNS, false));
        for (var c = CAP_COLUMNS; c < count - 1; c++)
            if ((c + 1) % CAP_COLUMNS != 0) pairs.Add((c, c + 1, true));
        var wires = new List<(Line, SolidColorBrush, int)>();
        foreach (var (from, to, across) in pairs)
        {
            var (a, b) = (slot(from), slot(to));
            var brush = new SolidColorBrush(VIOLET);
            var line = across
                ? new Line { X1 = a.X + CAP_WIDTH, Y1 = a.Y + CAP_HEIGHT / 2, X2 = b.X, Y2 = b.Y + CAP_HEIGHT / 2 }
                : new Line { X1 = a.X + CAP_WIDTH / 2, Y1 = a.Y + CAP_HEIGHT, X2 = a.X + CAP_WIDTH / 2, Y2 = b.Y };
            (line.Stroke, line.StrokeThickness, line.StrokeStartLineCap, line.StrokeEndLineCap) = (brush, 3, PenLineCap.Round, PenLineCap.Round);
            line.StrokeDashArray = [CAP_GAP / 3, CAP_GAP / 3];
            Fx.Children.Insert(0, line);
            wires.Add((line, brush, to));
        }
        return wires;
    }

    private static ColorAnimation Green(SolidColorBrush brush, double delayMs)
    {
        var to = Color.FromArgb(brush.Color.A, GREEN.R, GREEN.G, GREEN.B);
        var animation = Ui.Fade(brush, brush.Color, to, 220);
        animation.BeginTime = TimeSpan.FromMilliseconds(delayMs);
        brush.Color = to;
        return animation;
    }

    private static Border DeviceCard(out Path tick)
    {
        var gradient = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
        gradient.GradientStops.Add(new GradientStop { Color = Color.FromArgb(255, 0x19, 0xC9, 0x8C), Offset = 0 });
        gradient.GradientStops.Add(new GradientStop { Color = Color.FromArgb(255, 0x0E, 0xA5, 0xA5), Offset = 1 });
        // Tick drawn as one dash; its offset goes from the full length to 0.
        tick = new Path
        {
            Data = (Geometry)Microsoft.UI.Xaml.Markup.XamlBindingHelper.ConvertValue(typeof(Geometry), "M5,12.5 L9.5,17 L19,7"),
            Stroke = new SolidColorBrush(Color.FromArgb(255, 0x0E, 0x8F, 0x63)),
            StrokeThickness = 3,
            StrokeStartLineCap = PenLineCap.Round,
            StrokeEndLineCap = PenLineCap.Round,
            StrokeLineJoin = PenLineJoin.Round,
            StrokeDashArray = [TICK_DASH, TICK_DASH],
            StrokeDashOffset = TICK_DASH,
        };
        var badge = new Border
        {
            Width = 30,
            Height = 30,
            CornerRadius = new CornerRadius(15),
            Background = new SolidColorBrush(Microsoft.UI.Colors.White),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(0, 0, -8, -8),
            Child = new Viewbox { Width = 18, Height = 18, Child = new Canvas { Width = 24, Height = 24, Children = { tick } } },
        };
        var card = new Grid { Width = CARD_SIZE, Height = CARD_SIZE };
        card.Children.Add(new Border { CornerRadius = new CornerRadius(26), Background = gradient });
        card.Children.Add(new FontIcon { Glyph = (string)Application.Current.Resources["GlyphTablet"], FontSize = 36, Foreground = new SolidColorBrush(Microsoft.UI.Colors.White) });
        card.Children.Add(badge);
        return new Border { Child = card };
    }

    internal static async Task<BitmapImage> QrImageAsync(string text)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(text, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(PIXELS_PER_MODULE, INK, PAPER, true);
        using var stream = new InMemoryRandomAccessStream();
        await stream.WriteAsync(png.AsBuffer());
        stream.Seek(0);
        var image = new BitmapImage();
        await image.SetSourceAsync(stream);
        return image;
    }
}
