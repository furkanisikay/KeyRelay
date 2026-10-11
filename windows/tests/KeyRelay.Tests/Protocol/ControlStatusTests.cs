using KeyRelay.Protocol.Control;

namespace KeyRelay.Tests.Protocol;

[TestClass]
public sealed class ControlStatusTests
{
    [TestMethod]
    public void From_ServiceUnreachable_IsError()
    {
        Assert.AreEqual(TrayState.Error, TrayStates.From(null));
    }

    [TestMethod]
    [DataRow(false, null, false, TrayState.Error, DisplayName = "no agent")]
    [DataRow(true, null, true, TrayState.Waiting, DisplayName = "no tablet")]
    [DataRow(true, "Tab", false, TrayState.Waiting, DisplayName = "tablet, desktop not writable")]
    [DataRow(true, "Tab", true, TrayState.Ready, DisplayName = "tablet and ready")]
    public void ToTrayState_Combination_MatchesTrayIcon(bool agentConnected, string? device, bool ready, TrayState expected)
    {
        var status = new ControlStatus { AgentConnected = agentConnected, ConnectedDevice = device, Ready = ready };

        Assert.AreEqual(expected, status.ToTrayState());
    }
}
