using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;

namespace KeyRelay.Tests.TestSupport;

/// <summary>Private pipe names and an ACL for the test user (production pipes allow only SYSTEM or the console user).</summary>
internal static class TestPipes
{
    internal static string Name() => $"KeyRelay.Test.{Guid.NewGuid():N}";

    internal static PipeSecurity CurrentUser()
    {
        using var identity = WindowsIdentity.GetCurrent();
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(identity.User!, PipeAccessRights.FullControl, AccessControlType.Allow));
        return security;
    }
}
