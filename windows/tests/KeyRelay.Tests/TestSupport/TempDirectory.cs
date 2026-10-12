namespace KeyRelay.Tests.TestSupport;

/// <summary>A fresh directory under %TEMP% that is deleted with the test.</summary>
internal sealed class TempDirectory : IDisposable
{
    internal TempDirectory() => Directory.CreateDirectory(Path);

    internal string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "keyrelay-tests", Guid.NewGuid().ToString("N"));

    public void Dispose() => Directory.Delete(Path, recursive: true);
}
