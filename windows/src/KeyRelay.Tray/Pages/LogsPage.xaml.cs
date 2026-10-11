using KeyRelay.Protocol.Control;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace KeyRelay.Tray.Pages;

/// <summary>Logs page: the service's last 500 log lines, newest at the bottom; time faint, source coloured by level.</summary>
public sealed partial class LogsPage : Page
{
    public LogsPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await RefreshAsync();
    }

    /// <summary>Shows the lines and scrolls to the newest.</summary>
    internal void Render(IReadOnlyList<string> lines)
    {
        var parsed = lines.Select(LogLine.Parse).ToList();
        Lines.ItemsSource = parsed;
        if (parsed.Count > 0) Lines.ScrollIntoView(parsed[^1]);
    }

    private async void OnRefresh(object sender, RoutedEventArgs e) => await RefreshAsync();

    private async Task RefreshAsync()
    {
        IReadOnlyList<string> lines;
        try
        {
            lines = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.LOGS))).Logs ?? [];
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            lines = [Ui.Text("ServiceUnreachable")];
        }
        Render(lines);
    }
}

/// <summary>One service log line split for display; lines in another format are shown whole.</summary>
internal sealed record LogLine(string Time, string Tag, string Text, Brush TagBrush)
{
    // Service format: "HH:mm:ss LEVEL Category: message" (LogBuffer).
    private const int TIME_LENGTH = 8;

    internal static LogLine Parse(string line)
    {
        var parts = line.Split(' ', 3);
        var colon = parts.Length == 3 ? parts[2].IndexOf(": ", StringComparison.Ordinal) : -1;
        if (colon < 0 || parts[0].Length != TIME_LENGTH) return new LogLine("", "", line, Ui.Brush("MutedBrush"));
        var brush = parts[1] switch { "WARN" => "WaitingBrush", "ERROR" => "ErrorBrush", _ => "AccentLightBrush" };
        return new LogLine(parts[0], parts[2][..colon], parts[2][(colon + 2)..], Ui.Brush(brush));
    }
}
