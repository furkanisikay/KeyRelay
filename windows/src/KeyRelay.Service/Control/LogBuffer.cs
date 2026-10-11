using System.Globalization;

namespace KeyRelay.Service.Control;

/// <summary>Keeps the last 500 log lines (Information and above) for the tray's log page.</summary>
internal sealed class LogBuffer : ILoggerProvider
{
    internal const int CAPACITY = 500;
    private readonly Lock _gate = new();
    private readonly string[] _lines = new string[CAPACITY];
    private int _next;
    private int _count;

    public ILogger CreateLogger(string categoryName) => new BufferLogger(this, categoryName[(categoryName.LastIndexOf('.') + 1)..]);

    internal void Add(string line)
    {
        lock (_gate)
        {
            _lines[_next] = line;
            _next = (_next + 1) % CAPACITY;
            _count = Math.Min(_count + 1, CAPACITY);
        }
    }

    /// <returns>Lines oldest first.</returns>
    internal List<string> Snapshot()
    {
        lock (_gate)
        {
            var lines = new List<string>(_count);
            for (var i = 0; i < _count; i++) lines.Add(_lines[(_next - _count + i + CAPACITY) % CAPACITY]);
            return lines;
        }
    }

    public void Dispose()
    {
    }

    private sealed class BufferLogger(LogBuffer buffer, string category) : ILogger
    {
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception, Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel)) return;
            var level = logLevel switch { LogLevel.Warning => "WARN", >= LogLevel.Error => "ERROR", _ => "INFO" };
            var error = exception is null ? "" : $" | {exception.Message}";
            buffer.Add(string.Create(CultureInfo.InvariantCulture, $"{DateTime.Now:HH:mm:ss} {level} {category}: {formatter(state, exception)}{error}"));
        }
    }
}
