using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.Windows.ApplicationModel.Resources;
using Windows.UI;
using Windows.UI.ViewManagement;

namespace KeyRelay.Tray;

/// <summary>Helpers shared by the window and the pages.</summary>
internal static partial class Ui
{
    private static readonly ResourceLoader STRINGS = new();
    // Per-user tray settings and error log; the service never reads them.
    private static readonly string DATA_DIRECTORY = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "KeyRelay");
    private static readonly string SETTINGS_FILE = Path.Combine(DATA_DIRECTORY, "tray.json");
    private static readonly string LOG_FILE = Path.Combine(DATA_DIRECTORY, "tray.log");
    // Internal size cap: past it the log moves to tray.log.1 (one backup), so a repeating error cannot fill the disk
    // and its first lines (the cause) survive one more round.
    private const long LOG_MAX_BYTES = 1024 * 1024;
    private const double COMPLETION_MARGIN_MS = 1000;
    private static readonly Lock LOG_GATE = new();
    private static int _loggedErrors;
    private static List<Task>? _forgotten;

    /// <summary>Gets how many errors were logged since start (--smoke fails on any).</summary>
    internal static int LoggedErrors => Volatile.Read(ref _loggedErrors);

    /// <summary>Leaves one line about an error the tray survived in %LOCALAPPDATA%\KeyRelay\tray.log.</summary>
    internal static void Log(Exception? ex, string where)
    {
        Interlocked.Increment(ref _loggedErrors);
        try
        {
            lock (LOG_GATE)
            {
                Directory.CreateDirectory(DATA_DIRECTORY);
                if (new FileInfo(LOG_FILE) is { Exists: true, Length: > LOG_MAX_BYTES }) File.Move(LOG_FILE, LOG_FILE + ".1", overwrite: true);
                File.AppendAllText(LOG_FILE, string.Create(CultureInfo.InvariantCulture, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {where}: {ex}{Environment.NewLine}"));
            }
        }
        catch (Exception logError) when (logError is IOException or UnauthorizedAccessException)
        {
            // Nowhere left to report; the tray keeps running.
        }
    }

    /// <summary>Lets a task run on its own; a fault is logged instead of lost or crashing the process.</summary>
    internal static void Forget(Task task, string where)
    {
        _forgotten?.Add(task);
        task.ContinueWith(failed => Log(failed.Exception, where), CancellationToken.None, TaskContinuationOptions.OnlyOnFaulted | TaskContinuationOptions.ExecuteSynchronously, TaskScheduler.Default);
    }

    /// <summary>From now on keeps every forgotten task in the returned list (UI thread only); --smoke waits for them.</summary>
    internal static List<Task> TrackForgotten() => _forgotten = [];
    // Mockup groups digits with a thin space ("12 418"), not the culture's dot.
    private static readonly NumberFormatInfo GROUPED = new() { NumberGroupSeparator = " ", NumberGroupSizes = [3] };

    /// <summary>Gets Turkish UI text from Strings/Resources.resw.</summary>
    internal static string Text(string key) => STRINGS.GetString(key);

    internal static string Format(string key, params object?[] values) => string.Format(CultureInfo.CurrentCulture, Text(key), values);

    /// <summary>Gets false when the user turned animation effects off in Windows settings; transitions are then instant.</summary>
    internal static bool AnimationsEnabled => new UISettings().AnimationsEnabled;

    /// <summary>Gets or sets whether the status page shows the names of the last keys; off by default, saved per user.</summary>
    internal static bool ShowKeys
    {
        get => LoadSettings().ShowKeys;
        set => SaveSettings(LoadSettings() with { ShowKeys = value });
    }

    /// <summary>Gets or sets whether the one-time "how it works" tip has been closed; saved per user.</summary>
    internal static bool IntroSeen
    {
        get => LoadSettings().IntroSeen;
        set => SaveSettings(LoadSettings() with { IntroSeen = value });
    }

    private static TraySettings LoadSettings()
    {
        try
        {
            return JsonSerializer.Deserialize<TraySettings>(File.ReadAllText(SETTINGS_FILE)) ?? new TraySettings();
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return new TraySettings();
        }
    }

    private static void SaveSettings(TraySettings settings)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SETTINGS_FILE)!);
            File.WriteAllText(SETTINGS_FILE, JsonSerializer.Serialize(settings));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            // Not saved: the choice holds until the window is opened again.
        }
    }

    /// <summary>Gets true while the window is on screen; hidden or minimized windows start no animation.</summary>
    internal static bool IsShown => App.Current.Shell?.IsShown == true;

    /// <summary>Errors that mean "service unreachable", not a bug.</summary>
    internal static bool IsControlFailure(Exception ex) =>
        ex is IOException or TimeoutException or UnauthorizedAccessException or InvalidDataException or JsonException;

    /// <summary>Gets the text key that explains a failed control request; a null response means the service was unreachable.</summary>
    internal static string FailureKey(ControlResponse? response) => response?.Error switch
    {
        ControlErrors.WRONG_SESSION => "WrongSession",
        ControlErrors.INTERNAL_ERROR => "ServiceFailed",
        _ => "ServiceUnreachable",
    };

    /// <summary>Gets the tray tooltip text of a state; the status page also reads it out for screen readers.</summary>
    internal static string StateText(TrayState state) => Text(state switch
    {
        TrayState.Ready => "TrayTooltipReady",
        TrayState.Waiting => "TrayTooltipWaiting",
        _ => "TrayTooltipError",
    });

    internal static string Milliseconds(int micros) => (micros / 1000.0).ToString("0.0", CultureInfo.CurrentCulture);

    internal static string Grouped(long value) => value.ToString("N0", GROUPED);

    internal static Brush Brush(string key) => (Brush)Application.Current.Resources[key];

    internal static SolidColorBrush Solid(uint argb) =>
        new(Color.FromArgb((byte)(argb >> 24), (byte)(argb >> 16), (byte)(argb >> 8), (byte)argb));

    /// <summary>Gets the element's CompositeTransform, adding one centred on the element when missing.</summary>
    internal static CompositeTransform Transform(UIElement element)
    {
        if (element.RenderTransform is CompositeTransform transform) return transform;
        element.RenderTransformOrigin = new Windows.Foundation.Point(0.5, 0.5);
        return (CompositeTransform)(element.RenderTransform = new CompositeTransform());
    }

    /// <summary>Animates one double property from a value to a value, holding the start value during the delay.</summary>
    internal static DoubleAnimationUsingKeyFrames Anim(DependencyObject target, string property, double from, double to, double ms, double delayMs = 0, EasingFunctionBase? ease = null) =>
        Keys(target, property, (0, from, null), (delayMs, from, null), (delayMs + ms, to, ease));

    /// <summary>Animates one double property through key frames given as (time ms, value, easing into it); the first frame is at 0.</summary>
    internal static DoubleAnimationUsingKeyFrames Keys(DependencyObject target, string property, params (double Ms, double Value, EasingFunctionBase? Ease)[] frames)
    {
        // Dash offsets are dependent properties; transforms and opacity stay on the compositor anyway.
        var animation = new DoubleAnimationUsingKeyFrames { EnableDependentAnimation = true };
        foreach (var (ms, value, ease) in frames)
            animation.KeyFrames.Add(new EasingDoubleKeyFrame { KeyTime = TimeSpan.FromMilliseconds(ms), Value = value, EasingFunction = ease });
        Storyboard.SetTarget(animation, target);
        Storyboard.SetTargetProperty(animation, property);
        return animation;
    }

    internal static ColorAnimation Fade(DependencyObject target, Color from, Color to, double ms)
    {
        var animation = new ColorAnimation { From = from, To = to, Duration = TimeSpan.FromMilliseconds(ms), EnableDependentAnimation = true };
        Storyboard.SetTarget(animation, target);
        Storyboard.SetTargetProperty(animation, "Color");
        return animation;
    }

    /// <summary>
    /// Plays the timelines once, then stops them. Callers set the final values as local values first, so the
    /// scene ends exactly there; with animations off nothing plays and the final values show at once.
    /// </summary>
    internal static async Task PlayAsync(params Timeline[] timelines)
    {
        if (!AnimationsEnabled) return;
        var storyboard = new Storyboard();
        var end = 0.0;
        foreach (var timeline in timelines)
        {
            storyboard.Children.Add(timeline);
            end = Math.Max(end, EndMs(timeline));
        }
        var done = new TaskCompletionSource();
        storyboard.Completed += (_, _) => done.TrySetResult();
        storyboard.Begin();
        // Fallback for a window that never raises Completed. Generous on purpose: a storyboard starts on the next frame,
        // measured 150-230 ms after Begin on a busy UI thread, and a 100 ms margin cut scenes short (snapped to their end).
        await Task.WhenAny(done.Task, Task.Delay(TimeSpan.FromMilliseconds(end + COMPLETION_MARGIN_MS)));
        storyboard.Stop();
    }

    /// <summary>Waits between scene steps; skipped when animations are off.</summary>
    internal static Task PauseAsync(double ms) => AnimationsEnabled ? Task.Delay(TimeSpan.FromMilliseconds(ms)) : Task.CompletedTask;

    internal static EasingFunctionBase Spring => new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.45 };

    internal static EasingFunctionBase EaseOut => new CubicEase { EasingMode = EasingMode.EaseOut };

    internal static EasingFunctionBase EaseIn => new CubicEase { EasingMode = EasingMode.EaseIn };

    // Includes BeginTime: without it a delayed timeline outlived the wait and Stop cut it to its end (pair scene act 4).
    private static double EndMs(Timeline timeline)
    {
        var begin = timeline.BeginTime?.TotalMilliseconds ?? 0;
        if (timeline is DoubleAnimationUsingKeyFrames keys) return begin + keys.KeyFrames.Max(frame => frame.KeyTime.TimeSpan.TotalMilliseconds);
        return begin + (timeline.Duration.HasTimeSpan ? timeline.Duration.TimeSpan.TotalMilliseconds : 0);
    }

    [LibraryImport("user32.dll")]
    internal static partial uint GetDpiForWindow(nint window);

    private sealed record TraySettings(bool ShowKeys = false, bool IntroSeen = false);
}
