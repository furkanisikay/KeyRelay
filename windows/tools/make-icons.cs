#:package System.Drawing.Common@10.0.12
#:property TargetFramework=net10.0-windows
#:property TreatWarningsAsErrors=false
#:property ManagePackageVersionsCentrally=false

using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;

// Draws the KeyRelay mark and the three tray glyphs of docs/design/keyrelay-mockups.html into PNG-compressed .ico files.
if (args.Length != 1) throw new ArgumentException("usage: dotnet run tools/make-icons.cs -- <Assets folder>");
var assets = args[0];
int[] appSizes = [16, 20, 24, 32, 48, 64, 256];
int[] traySizes = [16, 20, 24, 32, 48];
WriteIcon(Path.Combine(assets, "KeyRelay.ico"), appSizes, DrawMark);
WriteIcon(Path.Combine(assets, "tray-ready.ico"), traySizes, DrawReady);
WriteIcon(Path.Combine(assets, "tray-waiting.ico"), traySizes, DrawWaiting);
WriteIcon(Path.Combine(assets, "tray-error.ico"), traySizes, DrawError);
Console.WriteLine($"icons written to {Path.GetFullPath(assets)}");

// Every drawing uses the 64 x 64 space of the mockup's SVG viewBox.
static void DrawMark(Graphics g)
{
    using (var square = RoundedRect(new RectangleF(0, 0, 64, 64), 14))
    using (var background = new LinearGradientBrush(new PointF(0, 0), new PointF(64, 64), Color.FromArgb(0x2A, 0x4F, 0xC9), Color.FromArgb(0x0B, 0x14, 0x33)))
    {
        g.FillPath(background, square);
    }
    using (var cap = RoundedRect(new RectangleF(6, 14, 30, 30), 8))
    using (var capFill = new SolidBrush(Color.FromArgb(0xEA, 0xF0, 0xFF)))
    {
        g.FillPath(capFill, cap);
    }
    using var plus = RoundPen(Color.FromArgb(0x1E, 0x3A, 0x8F), 3.5f);
    g.DrawLine(plus, 15, 29, 27, 29);
    g.DrawLine(plus, 21, 23, 21, 35);
    using var flow = RoundPen(Color.FromArgb(0x89, 0xA8, 0xFF), 4);
    g.DrawLine(flow, 40, 30, 58, 30);
    using var head = RoundPen(Color.FromArgb(0xEA, 0xF0, 0xFF), 4);
    PointF[] arrowHead = [new(52, 23), new(59, 30), new(52, 37)];
    g.DrawLines(head, arrowHead);
}

// Tray glyphs fill more of the box than the mockup's 18 px tile so they stay readable at 16 px.
static void DrawReady(Graphics g)
{
    using var fill = new SolidBrush(Color.FromArgb(0x2F, 0xD4, 0x9C));
    g.FillEllipse(fill, 10, 10, 44, 44);
}

static void DrawWaiting(Graphics g)
{
    using var ring = new Pen(Color.FromArgb(0xF2, 0xB5, 0x44), 8);
    g.DrawEllipse(ring, 13, 13, 38, 38);
}

static void DrawError(Graphics g)
{
    using var cross = RoundPen(Color.FromArgb(0xFF, 0x6B, 0x6B), 9);
    g.DrawLine(cross, 16, 16, 48, 48);
    g.DrawLine(cross, 48, 16, 16, 48);
}

static Pen RoundPen(Color color, float width) =>
    new(color, width) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };

static GraphicsPath RoundedRect(RectangleF r, float radius)
{
    var d = radius * 2;
    var path = new GraphicsPath();
    path.AddArc(r.X, r.Y, d, d, 180, 90);
    path.AddArc(r.Right - d, r.Y, d, d, 270, 90);
    path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
    path.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
    path.CloseFigure();
    return path;
}

static byte[] Png(int size, Action<Graphics> draw)
{
    using var bitmap = new Bitmap(size, size, PixelFormat.Format32bppArgb);
    using (var g = Graphics.FromImage(bitmap))
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
        g.ScaleTransform(size / 64f, size / 64f);
        draw(g);
    }
    using var stream = new MemoryStream();
    bitmap.Save(stream, ImageFormat.Png);
    return stream.ToArray();
}

// ICO container with PNG entries (supported since Windows Vista).
static void WriteIcon(string path, int[] sizes, Action<Graphics> draw)
{
    var images = sizes.Select(size => Png(size, draw)).ToArray();
    using var writer = new BinaryWriter(File.Create(path));
    writer.Write((ushort)0);
    writer.Write((ushort)1);
    writer.Write((ushort)images.Length);
    var offset = 6 + 16 * images.Length;
    for (var i = 0; i < images.Length; i++)
    {
        var side = (byte)(sizes[i] >= 256 ? 0 : sizes[i]);
        writer.Write(side);
        writer.Write(side);
        writer.Write((byte)0);
        writer.Write((byte)0);
        writer.Write((ushort)1);
        writer.Write((ushort)32);
        writer.Write(images[i].Length);
        writer.Write(offset);
        offset += images[i].Length;
    }
    foreach (var image in images) writer.Write(image);
}
