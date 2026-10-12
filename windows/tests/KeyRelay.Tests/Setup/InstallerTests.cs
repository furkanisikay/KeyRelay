using System.Security.AccessControl;
using System.Security.Principal;
using KeyRelay.Service.Setup;
using KeyRelay.Tests.TestSupport;

namespace KeyRelay.Tests.Setup;

[TestClass]
public sealed class InstallerTests
{
    [TestMethod]
    public void SecureDataDirectory_FileWithExplicitEveryoneAce_RemovesAceAndOwnsByAdministrators()
    {
        if (!Environment.IsPrivilegedProcess) Assert.Inconclusive("Assigning the Administrators owner needs an elevated process.");
        using var directory = new TempDirectory();
        var file = new FileInfo(Path.Combine(directory.Path, "certificate.bin"));
        File.WriteAllText(file.FullName, "x");
        var everyone = new SecurityIdentifier(WellKnownSidType.WorldSid, null);
        var open = file.GetAccessControl();
        open.AddAccessRule(new FileSystemAccessRule(everyone, FileSystemRights.FullControl, AccessControlType.Allow));
        file.SetAccessControl(open);

        Installer.SecureDataDirectory(directory.Path);

        var secured = file.GetAccessControl();
        var admins = new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null);
        Assert.AreEqual(0, secured.GetAccessRules(true, false, typeof(SecurityIdentifier)).Count);
        Assert.AreEqual(admins, secured.GetOwner(typeof(SecurityIdentifier)));
        Assert.AreEqual(admins, new DirectoryInfo(directory.Path).GetAccessControl().GetOwner(typeof(SecurityIdentifier)));
    }

    [TestMethod]
    public void SecureDataDirectory_JunctionInside_LeavesTargetFileUntouched()
    {
        if (!Environment.IsPrivilegedProcess) Assert.Inconclusive("Assigning the Administrators owner needs an elevated process.");
        using var data = new TempDirectory();
        using var outside = new TempDirectory();
        var target = new FileInfo(Path.Combine(outside.Path, "other.txt"));
        File.WriteAllText(target.FullName, "x");
        var everyone = new SecurityIdentifier(WellKnownSidType.WorldSid, null);
        var open = target.GetAccessControl();
        open.AddAccessRule(new FileSystemAccessRule(everyone, FileSystemRights.FullControl, AccessControlType.Allow));
        target.SetAccessControl(open);
        var before = target.GetAccessControl().GetSecurityDescriptorSddlForm(AccessControlSections.Owner | AccessControlSections.Access);
        var link = Path.Combine(data.Path, "link");
        CreateJunction(link, outside.Path);

        try
        {
            Installer.SecureDataDirectory(data.Path);

            Assert.AreEqual(before, target.GetAccessControl().GetSecurityDescriptorSddlForm(AccessControlSections.Owner | AccessControlSections.Access));
        }
        finally
        {
            Directory.Delete(link); // recursive delete cannot remove a junction
        }
    }

    [TestMethod]
    public void SecureDataDirectory_RootIsJunction_Throws()
    {
        using var parent = new TempDirectory();
        using var outside = new TempDirectory();
        var root = Path.Combine(parent.Path, "KeyRelay");
        CreateJunction(root, outside.Path);

        try
        {
            Assert.ThrowsExactly<InvalidOperationException>(() => Installer.SecureDataDirectory(root));
        }
        finally
        {
            Directory.Delete(root);
        }
    }

    private static void CreateJunction(string link, string target)
    {
        using var process = System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("cmd.exe", $"/c mklink /J \"{link}\" \"{target}\"") { CreateNoWindow = true, UseShellExecute = false })!;
        process.WaitForExit();
        Assert.AreEqual(0, process.ExitCode);
    }
}
