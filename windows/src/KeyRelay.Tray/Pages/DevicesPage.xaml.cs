using KeyRelay.Protocol.Control;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Hosting;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Path = Microsoft.UI.Xaml.Shapes.Path;

namespace KeyRelay.Tray.Pages;

/// <summary>Devices page: paired tablets with connection state; revoking drops the tablet at once (the bin eats the button label, then the card folds away).</summary>
public sealed partial class DevicesPage : Page
{
    /// <summary>Connected animation key from the pairing scene's device card to the new row's avatar.</summary>
    internal const string NEW_DEVICE_ANIMATION = "PairedDevice";
    private const double LETTER_GAP_MS = 55;
    private const double LETTER_MS = 420;
    private const double BIN_SIZE = 18;
    // The other rows settle first, so the new row visibly pushes them down when it is inserted.
    private const double MAKE_ROOM_DELAY_MS = 180;
    // Failure reason stays readable this long before the row rests; content timing, so it also runs with animations off.
    private static readonly TimeSpan REASON_HOLD = TimeSpan.FromMilliseconds(2200);
    // The "paired" highlight of a new row; content timing as well.
    private static readonly TimeSpan PAIRED_HOLD = TimeSpan.FromMilliseconds(3000);
    // Heartbeat ripple: three rings, each 0.46 s, 0.12 s apart, so the beat ends at 0.7 s.
    private const int RIPPLE_RINGS = 3;
    private const double RIPPLE_STAGGER_MS = 120;
    private const double RIPPLE_RING_MS = 460;
    private const double RIPPLE_START_SCALE = 0.5;
    private const double RIPPLE_SCALE = 2.8;
    private const double RIPPLE_OPACITY = 0.8;
    private const double SIGNAL_SIZE = 19;
    private const double DISCONNECTED_OPACITY = 0.7;
    private readonly Dictionary<string, Row> _rows = [];
    private IReadOnlyList<DeviceInfo>? _shown;
    // Last status seen here: a new connected device or a new heartbeat count is an event for this page.
    private string? _connectedDevice;
    private long? _heartbeats;

    public DevicesPage()
    {
        InitializeComponent();
        if (Ui.AnimationsEnabled) Rows.ChildrenTransitions = new TransitionCollection { new EntranceThemeTransition { IsStaggeringEnabled = true }, new RepositionThemeTransition() };
        Loaded += (_, _) =>
        {
            App.Current.StatusChanged += OnStatusChanged;
            _connectedDevice = App.Current.Status?.ConnectedDevice;
            _heartbeats = App.Current.Status?.Heartbeats;
            Ui.Forget(RefreshAsync(App.Current.Shell?.TakeNewDevice()), "devices load");
        };
        Unloaded += (_, _) => App.Current.StatusChanged -= OnStatusChanged;
    }

    /// <summary>
    /// Follows the 1 s status poll while the window is shown: a tablet connecting or leaving redraws the list once
    /// (its rows enter again); a new heartbeat of the connected tablet sends one ripple out of its live dot (0.7 s).
    /// Nothing loops: no heartbeat, no motion.
    /// </summary>
    internal void OnStatusChanged()
    {
        if (!Ui.IsShown || App.Current.Status is not { } status) return;
        var (device, heartbeats) = (status.ConnectedDevice, status.Heartbeats);
        if (device != _connectedDevice)
        {
            _connectedDevice = device;
            _heartbeats = heartbeats;
            Ui.Forget(RefreshAsync(), "devices connection");
            return;
        }
        if (_heartbeats is { } before && heartbeats > before) Ui.Forget(RippleConnectedAsync(), "devices heartbeat");
        _heartbeats = heartbeats;
    }

    /// <summary>
    /// One heartbeat: rings spread from the centre of the connected tablet's live dot and fade out, one after the other
    /// (0.7 s in all), then are removed; --smoke calls it directly.
    /// </summary>
    internal async Task RippleConnectedAsync()
    {
        if (!Ui.AnimationsEnabled || _rows.Values.FirstOrDefault(row => row.Signal is not null)?.Signal is not { } signal) return;
        var size = signal.ActualWidth > 0 ? signal.ActualWidth : SIGNAL_SIZE;
        var center = signal.TransformToVisual(Fx).TransformPoint(new Point(size / 2, size / 2));
        var scene = new List<Timeline>();
        var rings = new Ellipse[RIPPLE_RINGS];
        for (var i = 0; i < rings.Length; i++)
        {
            rings[i] = new Ellipse { Width = size, Height = size, Stroke = Ui.Brush("ReadyBrush"), StrokeThickness = 1.5, Opacity = 0, IsHitTestVisible = false };
            Canvas.SetLeft(rings[i], center.X - size / 2);
            Canvas.SetTop(rings[i], center.Y - size / 2);
            Fx.Children.Add(rings[i]);
            var delay = i * RIPPLE_STAGGER_MS;
            var grow = Ui.Transform(rings[i]);
            // Each ring is invisible until its turn, then appears inside the dot and fades only while it spreads;
            // a soft (sine) ease keeps the spread visible instead of jumping most of the way in the first frames.
            scene.Add(Ui.Anim(grow, "ScaleX", RIPPLE_START_SCALE, RIPPLE_SCALE, RIPPLE_RING_MS, delay, Spread));
            scene.Add(Ui.Anim(grow, "ScaleY", RIPPLE_START_SCALE, RIPPLE_SCALE, RIPPLE_RING_MS, delay, Spread));
            scene.Add(Ui.Keys(rings[i], "Opacity", (0, 0, null), (delay, 0, null), (delay, RIPPLE_OPACITY, null), (delay + RIPPLE_RING_MS, 0, Ui.EaseIn)));
        }
        try
        {
            await Ui.PlayAsync([.. scene]);
        }
        finally
        {
            foreach (var ring in rings) Fx.Children.Remove(ring);
        }
    }

    private static EasingFunctionBase Spread => new SineEase { EasingMode = EasingMode.EaseOut };

    /// <summary>Draws the device list, newest first; null means the service is unreachable. An unchanged list is left as it is.</summary>
    internal void Render(IReadOnlyList<DeviceInfo>? devices)
    {
        devices = devices?.OrderByDescending(device => device.AddedAt).ToList();
        if (devices is not null && _shown is not null && devices.SequenceEqual(_shown)) return;
        _shown = devices;
        Rows.Children.Clear();
        _rows.Clear();
        foreach (var device in devices ?? []) Rows.Children.Add(CreateRow(device));
        ShowEmpty();
    }

    /// <summary>Plays the revoke scene on a row while the request runs; --smoke passes a finished request.</summary>
    /// <param name="deviceId">Device whose row plays.</param>
    /// <param name="revoke">The request: null when the device is gone, otherwise the text key of the reason it was not.</param>
    internal async Task PlayRevokeAsync(string deviceId, Task<string?> revoke)
    {
        if (!_rows.TryGetValue(deviceId, out var row))
        {
            await revoke;
            return;
        }
        row.Revoke.IsHitTestVisible = false;
        // Act 1: the lid opens and stays open while the service answers (honest wait, usually milliseconds).
        row.Lid.Rotation = -38;
        var opened = Ui.PlayAsync(Ui.Anim(row.Lid, "Rotation", 0, -38, 180, 0, Ui.Spring));
        var failure = await revoke;
        await opened;
        await Ui.PlayAsync([.. failure is null ? EatLetters(row) : BounceLetters(row)]);
        row.Lid.Rotation = 0;
        await Ui.PlayAsync(Ui.Anim(row.Lid, "Rotation", -38, 0, 200, 0, Ui.Spring));
        if (failure is not null)
        {
            await RefuseAsync(row, failure);
            return;
        }
        await ShrinkToBinAsync(row);
        await FoldAwayAsync(row.Card);
        Rows.Children.Remove(row.Card);
        _rows.Remove(deviceId);
        _shown = _shown?.Where(device => device.Id != deviceId).ToList();
        ShowEmpty();
    }

    private void ShowEmpty()
    {
        // Unreachable must not read as "no devices", e.g. right after a revoke.
        Empty.Text = Ui.Text(_shown is null ? "ServiceUnreachable" : "DevicesEmpty");
        Empty.Visibility = Rows.Children.Count > 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    /// <summary>
    /// After a pairing: the other rows are drawn, the new one is inserted at the top so they move down, the pairing
    /// card lands on its avatar (connected animation, prepared by the pair page) and the row shows "paired" for a moment.
    /// </summary>
    internal async Task ShowNewDeviceAsync(IReadOnlyList<DeviceInfo> devices, string newDeviceId)
    {
        Render([.. devices.Where(device => device.Id != newDeviceId)]);
        await Ui.PauseAsync(MAKE_ROOM_DELAY_MS);
        var ordered = devices.OrderByDescending(device => device.AddedAt).ToList();
        var index = ordered.FindIndex(device => device.Id == newDeviceId);
        if (index < 0) return;
        Rows.Children.Insert(index, CreateRow(ordered[index]));
        _shown = ordered;
        ShowEmpty();
        var row = _rows[newDeviceId];
        if (Ui.AnimationsEnabled && ConnectedAnimationService.GetForCurrentView().GetAnimation(NEW_DEVICE_ANIMATION) is { } landing) landing.TryStart(row.Avatar);
        await HighlightPairedAsync(row);
    }

    private async Task RefreshAsync(string? newDeviceId = null)
    {
        ControlResponse? response;
        try
        {
            response = await App.Current.Control.SendAsync(new ControlRequest(ControlOps.DEVICES));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            response = null;
        }
        // The service could not read the device list and set it aside; say why the list may be empty.
        SetAsideNotice.IsOpen = response?.Error == ControlErrors.DEVICES_SET_ASIDE;
        if (newDeviceId is not null && response is { Ok: true, Devices: { } devices } && devices.Any(device => device.Id == newDeviceId))
        {
            await ShowNewDeviceAsync(devices, newDeviceId);
            return;
        }
        Render(response is { Ok: true } ? response.Devices ?? [] : null);
        if (response is { Ok: false }) Empty.Text = Ui.Text(Ui.FailureKey(response));
    }

    /// <summary>A green frame and a "✓ paired" badge that pops in, stays a few seconds and fades; then the row is calm.</summary>
    private static async Task HighlightPairedAsync(Row row)
    {
        var badge = new Border
        {
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(8, 2, 8, 2),
            Background = Ui.Brush("ReadyBrush"),
            VerticalAlignment = VerticalAlignment.Center,
            Child = new TextBlock { Text = "✓ " + Ui.Text("DevicePairedBadge"), FontSize = 11, FontWeight = FontWeights.Bold, Foreground = Ui.Solid(0xFF05261B) },
        };
        row.Title.Children.Add(badge);
        row.Card.BorderBrush = Ui.Brush("ReadyBrush");
        var pop = Ui.Transform(badge);
        await Ui.PlayAsync(Ui.Anim(pop, "ScaleX", 0.6, 1, 320, 0, Ui.Spring), Ui.Anim(pop, "ScaleY", 0.6, 1, 320, 0, Ui.Spring), Ui.Anim(badge, "Opacity", 0, 1, 200));
        await Task.Delay(PAIRED_HOLD);
        badge.Opacity = 0;
        await Ui.PlayAsync(Ui.Anim(badge, "Opacity", 1, 0, 300));
        row.Title.Children.Remove(badge);
        row.Card.ClearValue(Border.BorderBrushProperty);
    }

    private Border CreateRow(DeviceInfo device)
    {
        var grid = new Grid { ColumnSpacing = 14 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var avatar = new Border
        {
            Width = 46,
            Height = 46,
            CornerRadius = new CornerRadius(10),
            Background = device.Connected ? AvatarBrush() : Ui.Brush("Glass2Brush"),
            Child = new FontIcon { Glyph = (string)Application.Current.Resources["GlyphTablet"], FontSize = 20, Foreground = device.Connected ? new SolidColorBrush(Microsoft.UI.Colors.White) : Ui.Brush("MutedBrush") },
        };
        grid.Children.Add(avatar);
        var title = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 };
        title.Children.Add(new TextBlock { Text = device.Name, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        FrameworkElement? signal = null;
        if (device.Connected)
        {
            (var mark, signal) = LiveMark();
            title.Children.Add(mark);
        }
        var detail = new TextBlock { Text = Detail(device), FontSize = 13, Foreground = Ui.Brush("MutedBrush"), TextTrimming = TextTrimming.CharacterEllipsis };
        var meta = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Spacing = 2, Children = { title, detail } };
        Grid.SetColumn(meta, 1);
        grid.Children.Add(meta);
        var (revoke, lid, bin, letters) = BinButton(Ui.Text("DeviceRevoke"));
        // Every row has the same button text; screen readers need the device name.
        AutomationProperties.SetName(revoke, Ui.Format("DeviceRevokeName", device.Name));
        AutomationProperties.SetAutomationId(revoke, $"Revoke-{device.Id}");
        revoke.Click += (_, _) => Ui.Forget(RevokeAsync(device), "revoke");
        revoke.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(revoke, 2);
        grid.Children.Add(revoke);
        // A tablet that is not connected stays faded and still.
        var card = new Border { Style = (Style)Application.Current.Resources["CardStyle"], Padding = new Thickness(14), Child = grid, Opacity = device.Connected ? 1 : DISCONNECTED_OPACITY };
        _rows[device.Id] = new Row(card, avatar, title, revoke, lid, bin, letters, detail, signal);
        return card;
    }

    private static LinearGradientBrush AvatarBrush()
    {
        var brush = new LinearGradientBrush { StartPoint = new Point(0, 0), EndPoint = new Point(1, 1) };
        brush.GradientStops.Add(new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0x3B, 0x6C, 0xFF), Offset = 0 });
        brush.GradientStops.Add(new GradientStop { Color = Windows.UI.Color.FromArgb(255, 0x7A, 0x5C, 0xFF), Offset = 1 });
        return brush;
    }

    // Connected: a static dot with a halo and three signal bars; no pulse, the tray runs all day. Each heartbeat sends
    // one ripple out of the dot (OnStatusChanged).
    private static (StackPanel Mark, FrameworkElement Signal) LiveMark()
    {
        var ready = Ui.Brush("ReadyBrush");
        var dot = new Grid { Width = SIGNAL_SIZE, Height = SIGNAL_SIZE, VerticalAlignment = VerticalAlignment.Center };
        dot.Children.Add(new Ellipse { Stroke = ready, StrokeThickness = 2, Opacity = 0.45 });
        dot.Children.Add(new Ellipse { Width = 9, Height = 9, Fill = ready });
        var mark = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        mark.Children.Add(dot);
        var panel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 3, VerticalAlignment = VerticalAlignment.Center, Height = 16 };
        var bars = ((double[])[6, 11, 16]).Select(height => new Rectangle
        {
            Width = 4,
            Height = height,
            RadiusX = 2,
            RadiusY = 2,
            Fill = ready,
            VerticalAlignment = VerticalAlignment.Bottom,
        }).ToArray();
        foreach (var bar in bars) panel.Children.Add(bar);
        mark.Children.Add(panel);
        AutomationProperties.SetName(mark, Ui.Text("DeviceConnected"));
        return (mark, dot);
    }

    // The bin is drawn from separate parts so the lid can open; the label is one TextBlock per letter so the bin can eat it.
    private static (Button Button, CompositeTransform Lid, FrameworkElement Bin, TextBlock[] Letters) BinButton(string label)
    {
        var ink = Ui.Brush("TextFillColorPrimaryBrush");
        var lid = new CompositeTransform { CenterX = 3.5, CenterY = 6.5 };
        var canvas = new Canvas { Width = 24, Height = 24 };
        canvas.Children.Add(BinPath("M3.5,6.5 H20.5 M9,6.5 V4 H15 V6.5", ink, lid));
        canvas.Children.Add(BinPath("M6,6.5 L7,20 H17 L18,6.5 M10,10.5 V16.5 M14,10.5 V16.5", ink, null));
        var bin = new Viewbox { Width = BIN_SIZE, Height = BIN_SIZE, Child = canvas };
        var letters = label.Select(c => new TextBlock { Text = c.ToString() }).ToArray();
        var text = new StackPanel { Orientation = Orientation.Horizontal };
        foreach (var letter in letters) text.Children.Add(letter);
        var content = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { bin, text } };
        AutomationProperties.SetAccessibilityView(content, Microsoft.UI.Xaml.Automation.Peers.AccessibilityView.Raw);
        return (new Button { Content = content }, lid, bin, letters);
    }

    private static Path BinPath(string data, Brush ink, Transform? transform)
    {
        var path = new Path { Stroke = ink, StrokeThickness = 2, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, StrokeLineJoin = PenLineJoin.Round, RenderTransform = transform };
        path.Data = (Geometry)Microsoft.UI.Xaml.Markup.XamlBindingHelper.ConvertValue(typeof(Geometry), data);
        return path;
    }

    private static string Detail(DeviceInfo device) =>
        device.Connected ? Ui.Format("DeviceConnectedDetail", device.AddedAt.ToLocalTime())
        : device.LastSeenAt is { } seen ? Ui.Format("DeviceLastSeen", device.AddedAt.ToLocalTime(), seen.ToLocalTime())
        : Ui.Format("DevicePaired", device.AddedAt.ToLocalTime());

    private async Task RevokeAsync(DeviceInfo device)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = Ui.Text("RevokeTitle"),
            Content = Ui.Format("RevokeBody", device.Name),
            PrimaryButtonText = Ui.Text("RevokeConfirm"),
            CloseButtonText = Ui.Text("RevokeCancel"),
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var played = false;
        try
        {
            await PlayRevokeAsync(device.Id, SendRevokeAsync(device.Id));
            played = true;
        }
        finally
        {
            // The list below shows what the service still has; a scene that broke halfway is redrawn from scratch.
            if (!played) _shown = null;
            await RefreshAsync();
        }
    }

    private static async Task<string?> SendRevokeAsync(string deviceId)
    {
        try
        {
            var response = await App.Current.Control.SendAsync(new ControlRequest(ControlOps.REVOKE, DeviceId: deviceId));
            // Unknown device: already gone, which is what the user asked for.
            return response.Ok || response.Error == ControlErrors.UNKNOWN_DEVICE ? null : Ui.FailureKey(response);
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            return Ui.FailureKey(null);
        }
    }

    /// <summary>Letters arc into the bin, nearest first: X linear, Y up then down with its own easing, spinning and shrinking.</summary>
    private static List<Timeline> EatLetters(Row row)
    {
        var scene = new List<Timeline>();
        var bin = row.Bin.TransformToVisual(row.Revoke).TransformPoint(new Point(BIN_SIZE / 2, 3));
        for (var i = 0; i < row.Letters.Length; i++)
        {
            var letter = row.Letters[i];
            var center = letter.TransformToVisual(row.Revoke).TransformPoint(new Point(letter.ActualWidth / 2, letter.ActualHeight / 2));
            var (dx, dy, delay) = (bin.X - center.X, bin.Y - center.Y, i * LETTER_GAP_MS);
            var end = delay + LETTER_MS;
            var move = Ui.Transform(letter);
            letter.Opacity = 0;
            scene.Add(Ui.Anim(move, "TranslateX", 0, dx, LETTER_MS, delay));
            scene.Add(Ui.Keys(move, "TranslateY", (0, 0, null), (delay, 0, null), (delay + LETTER_MS * 0.45, dy - 24, Ui.EaseOut), (end, dy + 6, Ui.EaseIn)));
            scene.Add(Ui.Keys(move, "Rotation", (0, 0, null), (delay, 0, null), (end, -320, null)));
            scene.Add(Ui.Anim(move, "ScaleX", 1, 0.35, LETTER_MS, delay));
            scene.Add(Ui.Anim(move, "ScaleY", 1, 0.35, LETTER_MS, delay));
            scene.Add(Ui.Keys(letter, "Opacity", (0, 1, null), (end - 80, 1, null), (end, 0, null)));
        }
        return scene;
    }

    /// <summary>Failure: the letters jump towards the bin and fall back; the bin refuses them.</summary>
    private static List<Timeline> BounceLetters(Row row)
    {
        var scene = new List<Timeline>();
        for (var i = 0; i < row.Letters.Length; i++)
        {
            var delay = i * LETTER_GAP_MS;
            var move = Ui.Transform(row.Letters[i]);
            scene.Add(Ui.Keys(move, "TranslateY", (0, 0, null), (delay, 0, null), (delay + 190, -14, Ui.EaseOut), (delay + 380, 2, Ui.EaseIn), (delay + 480, 0, null)));
            scene.Add(Ui.Keys(move, "Rotation", (0, 0, null), (delay, 0, null), (delay + 190, -25, Ui.EaseOut), (delay + 480, 0, null)));
        }
        return scene;
    }

    private static async Task RefuseAsync(Row row, string reason)
    {
        var (background, foreground, text, textBrush) = (row.Revoke.Background, row.Revoke.Foreground, row.Detail.Text, row.Detail.Foreground);
        row.Revoke.Background = Ui.Brush("ErrorBrush");
        row.Revoke.Foreground = new SolidColorBrush(Microsoft.UI.Colors.White);
        row.Detail.Text = Ui.Text(reason);
        row.Detail.Foreground = Ui.Brush("ErrorBrush");
        await Ui.PlayAsync(Ui.Keys(Ui.Transform(row.Revoke), "TranslateX", (0, 0, null), (72, -6, null), (144, 5, null), (216, -3, null), (360, 0, null)));
        await Task.Delay(REASON_HOLD);
        (row.Revoke.Background, row.Revoke.Foreground, row.Detail.Text, row.Detail.Foreground) = (background, foreground, text, textBrush);
        row.Revoke.IsHitTestVisible = true;
    }

    /// <summary>The button closes around the bin (inset clip), the bin squashes once and one ring goes out.</summary>
    private async Task ShrinkToBinAsync(Row row)
    {
        var bin = row.Bin.TransformToVisual(row.Revoke).TransformPoint(default);
        var visual = ElementCompositionPreview.GetElementVisual(row.Revoke);
        var clip = visual.Compositor.CreateInsetClip();
        visual.Clip = clip;
        var (left, right) = ((float)Math.Max(0, bin.X - 9), (float)Math.Max(0, row.Revoke.ActualWidth - bin.X - BIN_SIZE - 9));
        if (Ui.AnimationsEnabled)
        {
            foreach (var (property, value) in (ReadOnlySpan<(string, float)>)[("LeftInset", left), ("RightInset", right)])
            {
                var animation = visual.Compositor.CreateScalarKeyFrameAnimation();
                animation.InsertKeyFrame(1, value);
                animation.Duration = TimeSpan.FromMilliseconds(280);
                clip.StartAnimation(property, animation);
            }
        }
        else
        {
            (clip.LeftInset, clip.RightInset) = (left, right);
        }
        var center = row.Bin.TransformToVisual(Fx).TransformPoint(new Point(BIN_SIZE / 2, BIN_SIZE / 2));
        var size = BIN_SIZE + 18;
        var ring = new Ellipse { Width = size, Height = size, Stroke = Ui.Brush("ErrorBrush"), StrokeThickness = 2, Opacity = 0 };
        Canvas.SetLeft(ring, center.X - size / 2);
        Canvas.SetTop(ring, center.Y - size / 2);
        Fx.Children.Add(ring);
        var scale = Ui.Transform(ring);
        var squash = Ui.Transform(row.Bin);
        await Ui.PlayAsync(
            Ui.Anim(squash, "ScaleY", 0.8, 1, 260, 0, Ui.Spring),
            Ui.Anim(scale, "ScaleX", 1, 1.9, 400, 280), Ui.Anim(scale, "ScaleY", 1, 1.9, 400, 280),
            Ui.Keys(ring, "Opacity", (0, 0, null), (280, 0.9, null), (680, 0, null)));
        Fx.Children.Remove(ring);
    }

    /// <summary>The card tips back on its top edge and fades (0.3 s); the list then closes the gap.</summary>
    private static Task FoldAwayAsync(Border card)
    {
        var fold = new PlaneProjection { CenterOfRotationY = 0 };
        card.Projection = fold;
        card.Opacity = 0;
        var scale = Ui.Transform(card);
        return Ui.PlayAsync(
            Ui.Anim(fold, "RotationX", 0, 75, 300, 0, Ui.EaseIn),
            Ui.Anim(scale, "ScaleX", 1, 0.7, 300, 0, Ui.EaseIn), Ui.Anim(scale, "ScaleY", 1, 0.7, 300, 0, Ui.EaseIn),
            Ui.Anim(card, "Opacity", 1, 0, 300, 0, Ui.EaseIn));
    }

    private void OnAdd(object sender, RoutedEventArgs e)
    {
        if (Ui.AnimationsEnabled) ConnectedAnimationService.GetForCurrentView().PrepareToAnimate(PairPage.CARD_ANIMATION, AddButton);
        App.Current.Shell?.Open("pair");
    }

    private sealed record Row(Border Card, Border Avatar, StackPanel Title, Button Revoke, CompositeTransform Lid, FrameworkElement Bin, TextBlock[] Letters, TextBlock Detail, FrameworkElement? Signal);
}
