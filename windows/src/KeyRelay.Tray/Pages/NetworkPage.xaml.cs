using System.Globalization;
using KeyRelay.Protocol.Control;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace KeyRelay.Tray.Pages;

/// <summary>Network page: listening port, Funnel address (setting), certificate fingerprint and the addresses put into the QR.</summary>
public sealed partial class NetworkPage : Page
{
    private const int FINGERPRINT_SHORT = 3;

    public NetworkPage()
    {
        InitializeComponent();
        Loaded += async (_, _) => await RefreshAsync();
    }

    /// <summary>Draws the network settings; null means the service is unreachable.</summary>
    internal void Render(NetworkInfo? network)
    {
        if (network is null)
        {
            Show(InfoBarSeverity.Error, "ServiceUnreachable");
            return;
        }
        Result.IsOpen = false;
        var port = network.Port.ToString(CultureInfo.InvariantCulture);
        var funnel = network.FunnelAddress.Length > 0;
        PortValue.Text = port;
        FunnelBox.Text = network.FunnelAddress;
        FunnelValue.Text = funnel ? Ui.Format("FunnelOn", network.FunnelAddress) : Ui.Text("FunnelOff");
        // Short form as on the tablet; the full value stays in the tooltip and for screen readers.
        FingerprintValue.Text = network.Fingerprint.Length > FINGERPRINT_SHORT * 2
            ? $"sha256/{network.Fingerprint[..FINGERPRINT_SHORT]}…{network.Fingerprint[^FINGERPRINT_SHORT..]}"
            : network.Fingerprint;
        ToolTipService.SetToolTip(FingerprintValue, network.Fingerprint);
        AutomationProperties.SetName(FingerprintValue, network.Fingerprint);
        RouteMiddle.Text = Ui.Text(funnel ? "RouteFunnel" : "RouteLocal");
        RouteMiddleSub.Text = funnel ? network.FunnelAddress[network.FunnelAddress.LastIndexOf(':')..] : $":{port}";
        RoutePcSub.Text = $":{port}";
        AddressesValue.Text = string.Join(" · ", network.Addresses);
    }

    private async Task RefreshAsync()
    {
        NetworkInfo? network;
        try
        {
            network = (await App.Current.Control.SendAsync(new ControlRequest(ControlOps.NETWORK))).Network;
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            network = null;
        }
        Render(network);
    }

    private async void OnSaveFunnel(object sender, RoutedEventArgs e)
    {
        var address = FunnelBox.Text.Trim();
        ControlResponse response;
        try
        {
            response = await App.Current.Control.SendAsync(new ControlRequest(ControlOps.SET_FUNNEL, FunnelAddress: address));
        }
        catch (Exception ex) when (Ui.IsControlFailure(ex))
        {
            Show(InfoBarSeverity.Error, "ServiceUnreachable");
            return;
        }
        if (!response.Ok)
        {
            Show(InfoBarSeverity.Error, response.Error == ControlErrors.INVALID_FUNNEL ? "FunnelInvalid" : Ui.FailureKey(response));
            return;
        }
        // Refresh first: a successful Render closes the result bar.
        await RefreshAsync();
        Show(InfoBarSeverity.Success, address.Length == 0 ? "FunnelCleared" : "FunnelSaved");
    }

    private void Show(InfoBarSeverity severity, string key)
    {
        Result.Severity = severity;
        Result.Message = Ui.Text(key);
        Result.IsOpen = true;
    }
}
