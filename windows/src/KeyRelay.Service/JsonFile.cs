using System.Text.Json;

namespace KeyRelay.Service;

/// <summary>Small JSON files, replaced through a temporary file so a crash never leaves half a file behind.</summary>
internal static class JsonFile
{
    private static readonly JsonSerializerOptions OPTIONS = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    /// <returns>The content, or null when the file does not exist.</returns>
    internal static T? Read<T>(string path) where T : class =>
        File.Exists(path) ? JsonSerializer.Deserialize<T>(File.ReadAllBytes(path), OPTIONS) : null;

    internal static void Write<T>(string path, T value)
    {
        var temporary = path + ".tmp";
        using (var file = new FileStream(temporary, FileMode.Create, FileAccess.Write))
        {
            file.Write(JsonSerializer.SerializeToUtf8Bytes(value, OPTIONS));
            // On disk before the rename: a power cut must not leave an empty file under the real name.
            file.Flush(flushToDisk: true);
        }
        File.Move(temporary, path, overwrite: true);
    }
}
