using KeyRelay.Protocol.Control;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Windows.UI;
using Path = Microsoft.UI.Xaml.Shapes.Path;

namespace KeyRelay.Tray.Pages;

/// <summary>
/// Durum: a hero card whose colour, badge shape and tablet-to-PC light line follow the state, latency wave with
/// p50/p99, keys today, session and address. Motion only on events: state change (badge, colour, line drawn or
/// broken), keys arriving (one keycap flight per poll) and a one-time count-up.
/// </summary>
public sealed partial class StatusPage : Page
{
    private const int GRAPH_SECONDS = 60;
    // Finish line 7 target (p99 below 2 ms) as the graph's minimum scale, so small values do not look alarming.
    private const int GRAPH_FLOOR_MICROS = 2000;
    private const double WAVE_WIDTH = 360;
    private const double WAVE_HEIGHT = 80;
    // Length of each half of the mockup arc (de Casteljau split at t = 0.5), measured by flattening the curve.
    private const double HALF_LENGTH = 287.6;
    private const double DOT = 9.2;
    private const double GAP = 13.8;
    // Error state: each half stops this far (share of the half) before the middle, leaving a visible break.
    private const double BREAK = 0.18;
    private const double STATE_MS = 400;
    private const int SHAPE_POINTS = 60;
    private const int COUNT_STEPS = 30;
    // Same length as the service's RecentKeys.CAPACITY.
    private const int STRIP_KEYS = 8;
    private const string GLYPH_SHOW = "";
    private const string GLYPH_HIDE = "";
    private static readonly TimeSpan COUNT_STEP = TimeSpan.FromMilliseconds(30);
    // Aurora triples of the mockup by tray state: error red, waiting amber, ready (relaying) blue.
    private static readonly Color[][] PALETTES =
    [
        [Color.FromArgb(255, 0xFF, 0x54, 0x68), Color.FromArgb(255, 0xFF, 0x8A, 0x65), Color.FromArgb(255, 0xB3, 0x26, 0x4A)],
        [Color.FromArgb(255, 0xF2, 0xB5, 0x44), Color.FromArgb(255, 0xFF, 0x8A, 0x3D), Color.FromArgb(255, 0xFF, 0xD6, 0x6B)],
        [Color.FromArgb(255, 0x3B, 0x6C, 0xFF), Color.FromArgb(255, 0x22, 0xD3, 0xEE), Color.FromArgb(255, 0x7A, 0x5C, 0xFF)],
    ];
    private readonly List<(DependencyObject Target, int Slot, byte Alpha)> _tints = [];
    private readonly BezierSegment[] _lineSegments = new BezierSegment[GRAPH_SECONDS - 1];
    private readonly BezierSegment[] _areaSegments = new BezierSegment[GRAPH_SECONDS - 1];
    private readonly PathFigure _lineFigure = new();
    private readonly PathFigure _areaFigure = new() { IsClosed = true };
    private readonly Point[] _points = new Point[GRAPH_SECONDS];
    private TrayState? _state;
    private long? _keysToday;
    private bool _flying;
    private bool _counted;
    private long _countTarget;
    private int _countStep;
    private DispatcherQueueTimer? _countUp;
    private readonly List<string> _strip = [];
    private bool _showKeys;
    private bool _fetchingKeys;

    public StatusPage()
    {
        InitializeComponent();
        CreateBrushes();
        CreateWave();
        _showKeys = Ui.ShowKeys;
        ShowEye();
        if (Ui.AnimationsEnabled)
        {
            Cards.ChildrenTransitions = new TransitionCollection { new EntranceThemeTransition { IsStaggeringEnabled = true } };
            // The intro card enters with the page; when it closes, the cards below slide up into its place.
            Body.ChildrenTransitions = new TransitionCollection { new EntranceThemeTransition { IsStaggeringEnabled = true }, new RepositionThemeTransition() };
        }
        if (!Ui.IntroSeen) ShowIntro();
        Loaded += (_, _) =>
        {
            App.Current.StatusChanged += OnStatusChanged;
            if (App.Current.Shell is { } shell) shell.ShownChanged += OnShownChanged;
            Render(App.Current.Status);
            Ui.Forget(RefreshAddressAsync(), "status address");
        };
        Unloaded += (_, _) =>
        {
            App.Current.StatusChanged -= OnStatusChanged;
            if (App.Current.Shell is { } shell) shell.ShownChanged -= OnShownChanged;
            _countUp?.Stop();
            if (_showKeys) Ui.Forget(StopKeysAsync(), "status scene");
        };
    }

    /// <summary>Draws a status; null means the service is unreachable. A state change after the first draw plays its scene.</summary>
    internal void Render(ControlStatus? status)
    {
        var state = TrayStates.From(status);
        var connected = status?.ConnectedDevice is not null;
        Lead.Text = status is null ? Ui.Text("StatusLeadUnreachable")
            : status.AgentSession is { } session ? Ui.Format("StatusLeadRunning", session)
            : Ui.Text("StatusLeadNoAgent");
        StateLabel.Text = Ui.Text(state switch { TrayState.Ready => "HeroStateReady", TrayState.Waiting => "HeroStateWaiting", _ => "HeroStateError" });
        Headline.Text = Ui.Text(status is null ? "HeroUnreachable"
            : !status.AgentConnected ? "HeroNoAgent"
            : state == TrayState.Ready ? "HeroReady"
            : connected ? "HeroNotWritable"
            : "HeroWaiting");
        Sub.Text = status is null ? Ui.Text("HeroUnreachableSub")
            : !status.AgentConnected ? Ui.Text("HeroNoAgentSub")
            : state == TrayState.Ready ? Ui.Format("HeroReadySub", status.ConnectedDevice)
            : connected ? Ui.Format("HeroNotWritableSub", DesktopName(status.InputDesktop))
            : Ui.Text("HeroWaitingSub");
        AutomationProperties.SetName(Hero, $"{Headline.Text}. {Sub.Text}");
        DeviceValue.Text = status?.ConnectedDevice ?? Ui.Text("StatusNoDevice");
        // The dots show the state by colour only; screen readers get it as text.
        AutomationProperties.SetName(DeviceValue, $"{DeviceValue.Text}, {Ui.StateText(state)}");
        DeviceDot.Fill = Ui.Brush(connected ? "ReadyBrush" : "FaintBrush");
        LinkValue.Text = Ui.Text(connected ? "LinkSecure" : status is null ? "LinkServiceDown" : !status.AgentConnected ? "LinkNoAgent" : "LinkListening");
        LinkDot.Fill = Ui.Brush(state switch { TrayState.Ready => "ReadyBrush", TrayState.Waiting => "WaitingBrush", _ => "ErrorBrush" });
        P50Value.Text = status is null ? Ui.Text("NoValue") : Ui.Milliseconds(status.LatencyP50Micros);
        P99Value.Text = status is null ? "" : Ui.Format("P99Format", Ui.Milliseconds(status.LatencyP99Micros));
        SessionValue.Text = status is null ? Ui.Text("NoValue")
            : status.AgentSession is { } agentSession ? Ui.Format("SessionRunning", agentSession)
            : Ui.Text("SessionNoAgent");
        DesktopValue.Text = status is null || status.InputDesktop.Length == 0 ? Ui.Text("NoValue")
            : Ui.Format(status.Writable ? "DesktopWritable" : "DesktopNotWritable", DesktopName(status.InputDesktop));
        DesktopDot.Fill = Ui.Brush(status is null ? "FaintBrush" : status.Writable ? "ReadyBrush" : "WaitingBrush");
        RenderGraph(status);
        ShowKeys(status, state);
        var previous = _state;
        _state = state;
        if (state != previous) ShowState(state, previous);
    }

    /// <summary>Shows the first-run "how it works" card; --smoke calls it to play the close.</summary>
    internal void ShowIntro() => IntroCard.Visibility = Visibility.Visible;

    /// <summary>The intro card shrinks towards its top edge and fades (0.25 s), then leaves the page.</summary>
    internal async Task CloseIntroAsync()
    {
        IntroCard.IsHitTestVisible = false;
        IntroCard.Opacity = 0;
        var shrink = Ui.Transform(IntroCard);
        IntroCard.RenderTransformOrigin = new Point(0.5, 0);
        await Ui.PlayAsync(
            Ui.Anim(shrink, "ScaleX", 1, 0.94, 250, 0, Ui.EaseIn), Ui.Anim(shrink, "ScaleY", 1, 0.6, 250, 0, Ui.EaseIn),
            Ui.Anim(IntroCard, "Opacity", 1, 0, 250));
        Body.Children.Remove(IntroCard);
    }

    private void OnIntroClose(object sender, RoutedEventArgs e)
    {
        Ui.IntroSeen = true;
        Ui.Forget(CloseIntroAsync(), "status intro");
    }

    /// <summary>Returns the plain-language name of a Windows input desktop; unknown names are shown as they are.</summary>
    private static string DesktopName(string desktop) => desktop switch
    {
        "Default" => Ui.Text("DesktopDefault"),
        "Winlogon" => Ui.Text("DesktopWinlogon"),
        "Screen-saver" => Ui.Text("DesktopScreenSaver"),
        _ => desktop,
    };

    /// <summary>Draws the last-keys strip: key names from the service, or "+N" counts while names are off.</summary>
    internal void RenderStrip(IReadOnlyList<string> keys)
    {
        if (keys.SequenceEqual(_strip)) return;
        _strip.Clear();
        _strip.AddRange(keys);
        Strip.Children.Clear();
        foreach (var key in keys)
        {
            Strip.Children.Add(new Border
            {
                MinWidth = 30,
                Height = 26,
                Padding = new Thickness(8, 0, 8, 0),
                CornerRadius = new CornerRadius(7),
                Background = Ui.Solid(0xFFF2F6FF),
                BorderBrush = Ui.Solid(0xFF9FB4FF),
                BorderThickness = new Thickness(0, 0, 0, 3),
                Child = new TextBlock { Text = key, FontFamily = (FontFamily)Application.Current.Resources["NumberFont"], FontSize = 11, FontWeight = Microsoft.UI.Text.FontWeights.ExtraBold, Foreground = Ui.Solid(0xFF1E3A8F), HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center },
            });
        }
        AutomationProperties.SetName(Strip, string.Join(' ', keys));
    }

    private void OnStatusChanged()
    {
        // The tray polls every second all day; a hidden window draws nothing.
        if (!Ui.IsShown) return;
        Render(App.Current.Status);
        if (_showKeys) Ui.Forget(RefreshKeysAsync(), "status scene");
    }

    private void OnToggleKeys(object sender, RoutedEventArgs e)
    {
        _showKeys = !_showKeys;
        Ui.ShowKeys = _showKeys;
        ShowEye();
        RenderStrip([]);
        if (_showKeys) Ui.Forget(RefreshKeysAsync(), "status scene");
        else Ui.Forget(StopKeysAsync(), "status scene");
    }

    private void ShowEye()
    {
        EyeIcon.Glyph = _showKeys ? GLYPH_HIDE : GLYPH_SHOW;
        AutomationProperties.SetName(EyeButton, Ui.Text(_showKeys ? "StatusHideKeys" : "StatusShowKeys"));
        ToolTipService.SetToolTip(EyeButton, Ui.Text(_showKeys ? "StatusHideKeys" : "StatusShowKeys"));
    }

    // Asking is what keeps the service collecting; it holds the last 8 presses in memory only while asked.
    private async Task RefreshKeysAsync()
    {
        if (_fetchingKeys) return;
        _fetchingKeys = true;
        try
        {
            var keys = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS))).RecentKeys;
            if (_showKeys && keys is not null) RenderStrip(keys);
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            // Unreachable service: the strip keeps what it last showed.
        }
        finally
        {
            _fetchingKeys = false;
        }
    }

    private static async Task StopKeysAsync()
    {
        try
        {
            await App.Current.Control.SendAsync(new ControlRequest(ControlOps.RECENT_KEYS_STOP));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            // The service's short lease ends collection anyway.
        }
    }

    private void OnShownChanged()
    {
        if (!Ui.IsShown)
        {
            if (_showKeys) Ui.Forget(StopKeysAsync(), "status scene");
            return;
        }
        // Whatever changed while hidden is drawn as the new resting state, without replaying scenes.
        _state = null;
        _keysToday = null;
        Render(App.Current.Status);
    }

    private async Task RefreshAddressAsync()
    {
        IReadOnlyList<string> addresses;
        try
        {
            addresses = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.NETWORK))).Network?.Addresses ?? [];
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            addresses = [];
        }
        AddressValue.Text = addresses.Count > 0 ? addresses[0] : Ui.Text("NoValue");
        AddressMore.Text = string.Join(" · ", addresses.Skip(1));
    }

    private void CreateBrushes()
    {
        var arc = new LinearGradientBrush { MappingMode = BrushMappingMode.Absolute, StartPoint = new Point(122, 0), EndPoint = new Point(638, 0) };
        arc.GradientStops.Add(Tinted(new GradientStop { Offset = 0 }, 1));
        arc.GradientStops.Add(Tinted(new GradientStop { Offset = 0.5 }, 0));
        arc.GradientStops.Add(Tinted(new GradientStop { Offset = 1 }, 2));
        HaloA.Stroke = HaloB.Stroke = CoreA.Stroke = CoreB.Stroke = arc;
        var badge = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
        badge.GradientStops.Add(Tinted(new GradientStop { Offset = 0 }, 0));
        badge.GradientStops.Add(Tinted(new GradientStop { Offset = 0.5 }, 1));
        badge.GradientStops.Add(Tinted(new GradientStop { Offset = 1 }, 2));
        BadgeShape.Fill = badge;
        var glow = new RadialGradientBrush { Center = new Point(0.15, 0), GradientOrigin = new Point(0.15, 0), RadiusX = 0.7, RadiusY = 1.1 };
        glow.GradientStops.Add(Tinted(new GradientStop { Offset = 0 }, 0, 0x5A));
        glow.GradientStops.Add(Tinted(new GradientStop { Offset = 1 }, 0, 0));
        Glow.Fill = glow;
        StateLabel.Foreground = Tinted(new SolidColorBrush(), 0);
        Hero.BorderBrush = Tinted(new SolidColorBrush { Opacity = 0.45 }, 0);
        BurstRing.Stroke = Tinted(new SolidColorBrush(), 1);
    }

    private T Tinted<T>(T target, int slot, byte alpha = 255) where T : DependencyObject
    {
        _tints.Add((target, slot, alpha));
        return target;
    }

    private void ShowState(TrayState state, TrayState? previous)
    {
        var palette = PALETTES[(int)state];
        var scene = new List<Timeline>();
        foreach (var (target, slot, alpha) in _tints)
        {
            var to = Color.FromArgb(alpha, palette[slot].R, palette[slot].G, palette[slot].B);
            var from = target is GradientStop stop ? stop.Color : ((SolidColorBrush)target).Color;
            if (target is GradientStop gradientStop) gradientStop.Color = to;
            else ((SolidColorBrush)target).Color = to;
            scene.Add(Ui.Fade(target, from, to, STATE_MS));
        }
        BadgeShape.Data = Shape(state);
        ArrowGlyph.Visibility = state == TrayState.Ready ? Visibility.Visible : Visibility.Collapsed;
        DotsGlyph.Visibility = WaitMark.Visibility = state == TrayState.Waiting ? Visibility.Visible : Visibility.Collapsed;
        BangGlyph.Visibility = ErrorMark.Visibility = state == TrayState.Error ? Visibility.Visible : Visibility.Collapsed;
        PcNode.Opacity = state == TrayState.Error ? 0.45 : 1;
        var disconnected = previous == TrayState.Ready && state != TrayState.Ready;
        if (disconnected) Ui.Forget(DisconnectAsync(), "status scene");
        else ApplyLine(state);
        if (previous is null) return;
        // Badge changes shape with a spring, glyph turns in, headline rises: one 0.4 s beat per state change.
        var badge = Ui.Transform(Badge);
        var glyph = Ui.Transform(BadgeGlyph);
        scene.AddRange(
        [
            Ui.Anim(badge, "ScaleX", 0.6, 1, STATE_MS, 0, Ui.Spring), Ui.Anim(badge, "ScaleY", 0.6, 1, STATE_MS, 0, Ui.Spring),
            Ui.Anim(glyph, "ScaleX", 0.4, 1, 300, 0, Ui.Spring), Ui.Anim(glyph, "ScaleY", 0.4, 1, 300, 0, Ui.Spring),
            Ui.Anim(glyph, "Rotation", -40, 0, 300, 0, Ui.Spring), Ui.Anim(BadgeGlyph, "Opacity", 0, 1, 300),
            Ui.Anim(Headline, "Opacity", 0, 1, 320), Ui.Anim(Ui.Transform(Headline), "TranslateY", 10, 0, 320, 0, Ui.Spring),
        ]);
        Ui.Forget(Ui.PlayAsync([.. scene]), "status scene");
        if (state == TrayState.Ready) Ui.Forget(ConnectAsync(), "status scene");
    }

    // Each half of the arc is one dash as long as the half; moving the dash offset grows, retracts or breaks it.
    private void ApplyLine(TrayState state)
    {
        foreach (var path in (Path[])[HaloA, HaloB, CoreA, CoreB])
        {
            var unit = path.StrokeThickness;
            var dotted = state == TrayState.Waiting && path.StrokeThickness < 10;
            path.StrokeDashArray = dotted ? [DOT / unit, GAP / unit] : [HALF_LENGTH / unit, HALF_LENGTH / unit];
            var broken = state == TrayState.Error ? BREAK * HALF_LENGTH / unit : 0;
            path.StrokeDashOffset = IsFirstHalf(path) ? broken : -broken;
        }
        HaloA.Opacity = HaloB.Opacity = state == TrayState.Ready ? 0.35 : 0.2;
    }

    private bool IsFirstHalf(Path path) => path == HaloA || path == CoreA;

    /// <summary>The line draws itself from the tablet to the PC (0.62 s), then the PC node takes a small light burst.</summary>
    private async Task ConnectAsync()
    {
        var scene = new List<Timeline>();
        foreach (var path in (Path[])[HaloA, HaloB, CoreA, CoreB])
        {
            var half = HALF_LENGTH / path.StrokeThickness;
            scene.Add(IsFirstHalf(path) ? Ui.Anim(path, "StrokeDashOffset", half, 0, 310, 0, Ui.EaseIn) : Ui.Anim(path, "StrokeDashOffset", half, 0, 310, 310, Ui.EaseOut));
        }
        await Ui.PlayAsync([.. scene]);
        await BurstAsync();
    }

    /// <summary>The line snaps in the middle and both halves pull back to their nodes (0.42 s), then the resting line fades in.</summary>
    private async Task DisconnectAsync()
    {
        var scene = new List<Timeline>();
        foreach (var path in (Path[])[HaloA, HaloB, CoreA, CoreB])
        {
            var half = HALF_LENGTH / path.StrokeThickness;
            path.StrokeDashArray = [half, half];
            path.StrokeDashOffset = IsFirstHalf(path) ? half : -half;
            scene.Add(Ui.Anim(path, "StrokeDashOffset", 0, path.StrokeDashOffset, 420, 0, Ui.EaseIn));
        }
        await Ui.PlayAsync([.. scene]);
        // A newer state may have arrived during the scene; the resting line shows the latest one.
        ApplyLine(_state ?? TrayState.Error);
        if (_state == TrayState.Ready) return;
        await Ui.PlayAsync(Ui.Anim(Line, "Opacity", 0, 1, 220));
    }

    private Task BurstAsync()
    {
        var ring = Ui.Transform(BurstRing);
        var node = Ui.Transform(PcNode);
        return Ui.PlayAsync(
            Ui.Anim(ring, "ScaleX", 0.3, 2.6, 400, 0, Ui.EaseOut), Ui.Anim(ring, "ScaleY", 0.3, 2.6, 400, 0, Ui.EaseOut),
            Ui.Anim(BurstRing, "Opacity", 1, 0, 400),
            Ui.Keys(node, "ScaleX", (0, 1, null), (150, 1.12, Ui.EaseOut), (300, 1, Ui.EaseIn)),
            Ui.Keys(node, "ScaleY", (0, 1, null), (150, 1.12, Ui.EaseOut), (300, 1, Ui.EaseIn)));
    }

    private void ShowKeys(ControlStatus? status, TrayState state)
    {
        var keys = status?.KeysToday ?? 0;
        _countTarget = keys;
        if (!_counted && keys > 0 && Ui.AnimationsEnabled)
        {
            // The number counts up once, the first time the page shows it (0.9 s), then just follows the data.
            _counted = true;
            _countUp = DispatcherQueue.CreateTimer();
            _countUp.Interval = COUNT_STEP;
            _countUp.Tick += (timer, _) =>
            {
                var t = (double)++_countStep / COUNT_STEPS;
                KeysValue.Text = Ui.Grouped((long)(_countTarget * (1 - Math.Pow(1 - t, 4))));
                if (_countStep >= COUNT_STEPS) timer.Stop();
            };
            _countUp.Start();
        }
        else if (_countUp is not { IsRunning: true }) KeysValue.Text = Ui.Grouped(keys);
        // New keys since the last poll fly once along the line; the poll (1/s) is the throttle, a flight in progress wins.
        if (_keysToday is { } before && keys > before)
        {
            // Names off: the strip only counts, from the counter the status already carries.
            if (!_showKeys) RenderStrip([.. _strip.TakeLast(STRIP_KEYS - 1), Count(keys - before)]);
            if (state == TrayState.Ready && !_flying) Ui.Forget(FlyKeyAsync(keys - before), "status scene");
        }
        _keysToday = status is null ? null : keys;
    }

    private async Task FlyKeyAsync(long count)
    {
        _flying = true;
        try
        {
            KeyCapText.Text = Count(count);
            var cap = Ui.Transform(KeyCap);
            // X linear and Y with its own easing: the cap follows the arc without a path animation.
            await Ui.PlayAsync(
                Ui.Anim(cap, "TranslateX", 0, 516, 700),
                Ui.Keys(cap, "TranslateY", (0, 0, null), (350, -104, Ui.EaseOut), (700, 0, Ui.EaseIn)),
                Ui.Keys(cap, "ScaleX", (0, 0.72, null), (350, 1.14, Ui.EaseOut), (700, 0.72, Ui.EaseIn)),
                Ui.Keys(cap, "ScaleY", (0, 0.72, null), (350, 1.14, Ui.EaseOut), (700, 0.72, Ui.EaseIn)),
                Ui.Keys(KeyCap, "Opacity", (0, 0, null), (100, 1, null), (600, 1, null), (700, 0, null)));
            await BurstAsync();
        }
        finally
        {
            // A failed flight must not block every later one.
            _flying = false;
        }
    }

    private static string Count(long keys) => keys > 99 ? "+99" : $"+{keys}";

    private static PathGeometry Shape(TrayState state)
    {
        // Mockup badge shapes: cookie (relaying), squircle (waiting), diamond (error); same point count for each.
        var points = new PointCollection();
        for (var i = 0; i < SHAPE_POINTS; i++)
        {
            var t = i * 2 * Math.PI / SHAPE_POINTS;
            var (cos, sin) = (Math.Cos(t), Math.Sin(t));
            var r = state switch
            {
                TrayState.Ready => 1 + 0.075 * Math.Cos(9 * t),
                TrayState.Waiting => 0.9 * Math.Pow(Math.Pow(Math.Abs(cos), 4) + Math.Pow(Math.Abs(sin), 4), -0.25),
                _ => 1 / (Math.Abs(cos) + Math.Abs(sin)),
            };
            points.Add(new Point(25 + 23 * r * cos, 25 + 23 * r * sin));
        }
        var figure = new PathFigure { StartPoint = points[0], IsClosed = true };
        figure.Segments.Add(new PolyLineSegment { Points = points });
        var geometry = new PathGeometry();
        geometry.Figures.Add(figure);
        return geometry;
    }

    private void CreateWave()
    {
        for (var i = 0; i < _lineSegments.Length; i++)
        {
            _lineFigure.Segments.Add(_lineSegments[i] = new BezierSegment());
            _areaFigure.Segments.Add(_areaSegments[i] = new BezierSegment());
        }
        _areaFigure.Segments.Add(new LineSegment { Point = new Point(WAVE_WIDTH, WAVE_HEIGHT) });
        _areaFigure.Segments.Add(new LineSegment { Point = new Point(0, WAVE_HEIGHT) });
        var line = new PathGeometry();
        line.Figures.Add(_lineFigure);
        WaveLine.Data = line;
        var area = new PathGeometry();
        area.Figures.Add(_areaFigure);
        WaveArea.Data = area;
    }

    private void RenderGraph(ControlStatus? status)
    {
        IReadOnlyList<int> perSecond = status?.LatencyPerSecondMicros ?? [];
        var max = Math.Max(GRAPH_FLOOR_MICROS, perSecond.Count == 0 ? 0 : perSecond.Max());
        double Y(int micros) => WAVE_HEIGHT - 4 - micros * (WAVE_HEIGHT - 10) / max;
        // Newest second on the right; seconds without data lie on the floor.
        var offset = GRAPH_SECONDS - perSecond.Count;
        for (var i = 0; i < GRAPH_SECONDS; i++)
            _points[i] = new Point(i * WAVE_WIDTH / (GRAPH_SECONDS - 1), Y(i >= offset ? perSecond[i - offset] : 0));
        _lineFigure.StartPoint = _areaFigure.StartPoint = _points[0];
        for (var i = 0; i < _lineSegments.Length; i++)
        {
            // Catmull-Rom through the points, written as cubic Beziers.
            var (p0, p1, p2, p3) = (_points[Math.Max(i - 1, 0)], _points[i], _points[i + 1], _points[Math.Min(i + 2, GRAPH_SECONDS - 1)]);
            var c1 = new Point(p1.X + (p2.X - p0.X) / 6, p1.Y + (p2.Y - p0.Y) / 6);
            var c2 = new Point(p2.X - (p3.X - p1.X) / 6, p2.Y - (p3.Y - p1.Y) / 6);
            foreach (var segment in (BezierSegment[])[_lineSegments[i], _areaSegments[i]])
            {
                segment.Point1 = c1;
                segment.Point2 = c2;
                segment.Point3 = p2;
            }
        }
        var hasData = status is not null && perSecond.Count > 0;
        P50Line.Visibility = P99Line.Visibility = hasData ? Visibility.Visible : Visibility.Collapsed;
        if (!hasData) return;
        P50Line.Y1 = P50Line.Y2 = Y(Math.Min(status!.LatencyP50Micros, max));
        P99Line.Y1 = P99Line.Y2 = Y(Math.Min(status.LatencyP99Micros, max));
    }
}
