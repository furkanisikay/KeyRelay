using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace KeyRelay.Tray;

/// <summary>
/// The small ⓘ next to a technical label: its explanation shows on hover (tooltip), on click (a small popup) and to
/// screen readers (HelpText). Texts are Resources.resw keys (docs/glossary.md).
/// </summary>
public sealed partial class InfoTip : Button
{
    private const string GLYPH_INFO = "";
    private const double EXPLANATION_WIDTH = 300;

    public InfoTip()
    {
        Content = new FontIcon { Glyph = GLYPH_INFO, FontSize = 12, Foreground = Ui.Brush("MutedBrush") };
        Padding = new Thickness(4);
        MinWidth = 0;
        MinHeight = 0;
        BorderThickness = new Thickness(0);
        Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        VerticalAlignment = VerticalAlignment.Center;
    }

    /// <summary>Gets or sets the Resources.resw key of the explanation.</summary>
    public string TextKey
    {
        get => field ?? "";
        set
        {
            field = value;
            var text = Ui.Text(value);
            ToolTipService.SetToolTip(this, text);
            Flyout = new Flyout { Content = new TextBlock { Text = text, TextWrapping = TextWrapping.Wrap, MaxWidth = EXPLANATION_WIDTH } };
            AutomationProperties.SetName(this, Ui.Text("InfoName"));
            AutomationProperties.SetHelpText(this, text);
            AutomationProperties.SetAutomationId(this, $"Info-{value}");
        }
    }
}
