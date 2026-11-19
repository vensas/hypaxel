using System.Diagnostics;
using System.Globalization;
using BenchmarkDotNet.Attributes;
using Hypaxel;
using MiniExcelLibs;
using Sylvan.Data.Excel;
using SylvanReader = Sylvan.Data.Excel.ExcelDataReader;

/// <summary>What every reader must agree on: cells with a value, sum of numbers (date serials included), string characters and TRUE cells.</summary>
public readonly record struct Checksum(long Cells, double Sum, long StringChars, long Trues)
{
    public bool Matches(Checksum other) =>
        Cells == other.Cells && StringChars == other.StringChars && Trues == other.Trues && Math.Abs(Sum - other.Sum) <= Math.Abs(Sum) * 1e-12;

    public override string ToString() =>
        string.Create(CultureInfo.InvariantCulture, $"cells={Cells} sum={Sum:R} chars={StringChars} trues={Trues}");
}

/// <summary>Reads the 10-column dataset of <see cref="WriteBenchmarks"/> with each library, cell by cell.</summary>
public static class ReadCases
{
    public static readonly (string Name, Func<string, Checksum> Run)[] All =
    [
        ("Hypaxel (ExcelValue)", HypaxelValues),
        ("Hypaxel (typed, no alloc)", HypaxelTyped),
        ("Hypaxel (background inflate)", path => HypaxelTyped(path, new XlsxReaderOptions { BackgroundDecompression = true })),
        ("Sylvan.Data.Excel", Sylvan),
        ("ExcelDataReader", ExcelDataReaderCase),
        ("MiniExcel", MiniExcelCase),
    ];

    /// <summary>Writes the dataset with the Hypaxel writer (inline or shared strings) unless the file exists.</summary>
    public static string Generate(int rows, bool sharedStrings)
    {
        var path = Path.Combine(Path.GetTempPath(), $"hypaxel-read-{rows}-{(sharedStrings ? "shared" : "inline")}.xlsx");
        if (File.Exists(path))
        {
            return path;
        }

        var temp = path + ".tmp";
        using (var writer = new XlsxWriter(File.Create(temp), new XlsxWriterOptions { OwnsStream = true, StringStorage = sharedStrings ? StringStorage.SharedStrings : StringStorage.Inline }))
        {
            var sheet = writer.AddWorksheet("Data");
            foreach (var r in Data.Create(rows))
            {
                sheet.Write(r.Id);
                sheet.Write(r.Name);
                sheet.Write(r.Category);
                sheet.Write(r.Price);
                sheet.Write(r.Amount);
                sheet.Write(r.Created);
                sheet.Write(r.Active);
                sheet.Write(r.Quantity);
                sheet.Write(r.Comment);
                sheet.Write(r.Ratio);
                sheet.EndRow();
            }

            writer.Complete();
        }

        File.Move(temp, path, overwrite: true);
        return path;
    }

    public static Checksum HypaxelValues(string path)
    {
        long cells = 0, chars = 0, trues = 0;
        double sum = 0;
        using var reader = XlsxReader.Open(path);
        using var rows = reader.OpenWorksheet(0);
        while (rows.Read())
        {
            var row = rows.Row;
            for (var column = 1; column <= row.LastColumn; column++)
            {
                var value = row.GetValue(column);
                if (value.TryGetString(out var text))
                {
                    chars += text.Length;
                }
                else if (value.TryGetDouble(out var number))
                {
                    sum += number;
                }
                else if (value.TryGetBoolean(out var flag))
                {
                    trues += flag ? 1 : 0;
                }
                else if (value.IsEmpty)
                {
                    continue;
                }

                cells++;
            }
        }

        return new(cells, sum, chars, trues);
    }

    public static Checksum HypaxelTyped(string path) => HypaxelTyped(path, null);

    public static Checksum HypaxelTyped(string path, XlsxReaderOptions? options)
    {
        long cells = 0, chars = 0, trues = 0;
        double sum = 0;
        using var reader = XlsxReader.Open(path, options);
        using var rows = reader.OpenWorksheet(0);
        while (rows.Read())
        {
            var row = rows.Row;
            for (var column = 1; column <= row.LastColumn; column++)
            {
                if (row.TryGetText(column, out var text))
                {
                    chars += text.Length;
                }
                else if (row.TryGetDouble(column, out var number))
                {
                    sum += number;
                }
                else if (row.TryGetBoolean(column, out var flag))
                {
                    trues += flag ? 1 : 0;
                }
                else if (row.GetKind(column) == ExcelValueKind.Empty)
                {
                    continue;
                }

                cells++;
            }
        }

        return new(cells, sum, chars, trues);
    }

    public static Checksum Sylvan(string path)
    {
        long cells = 0, chars = 0, trues = 0;
        double sum = 0;
        using var reader = SylvanReader.Create(path, new ExcelDataReaderOptions { Schema = ExcelSchema.NoHeaders });
        while (reader.Read())
        {
            for (var i = 0; i < reader.RowFieldCount; i++)
            {
                switch (reader.GetExcelDataType(i))
                {
                    case ExcelDataType.String:
                        chars += reader.GetString(i).Length;
                        break;
                    case ExcelDataType.Numeric:
                        sum += reader.GetDouble(i);
                        break;
                    case ExcelDataType.DateTime:
                        sum += reader.GetDateTime(i).ToOADate();
                        break;
                    case ExcelDataType.Boolean:
                        trues += reader.GetBoolean(i) ? 1 : 0;
                        break;
                    case ExcelDataType.Null:
                        continue;
                }

                cells++;
            }
        }

        return new(cells, sum, chars, trues);
    }

    public static Checksum ExcelDataReaderCase(string path)
    {
        // ExcelDataReader's configuration asks for code page 1252 even for .xlsx files.
        System.Text.Encoding.RegisterProvider(System.Text.CodePagesEncodingProvider.Instance);
        using var stream = File.OpenRead(path);
        using var reader = global::ExcelDataReader.ExcelReaderFactory.CreateOpenXmlReader(stream);
        var checksum = new Accumulator();
        while (reader.Read())
        {
            for (var i = 0; i < reader.FieldCount; i++)
            {
                checksum.Add(reader.GetValue(i));
            }
        }

        return checksum.Result;
    }

    public static Checksum MiniExcelCase(string path)
    {
        var checksum = new Accumulator();
        foreach (IDictionary<string, object?> row in MiniExcel.Query(path, useHeaderRow: false))
        {
            foreach (var value in row.Values)
            {
                checksum.Add(value);
            }
        }

        return checksum.Result;
    }

    private sealed class Accumulator
    {
        private long _cells, _chars, _trues;
        private double _sum;

        public Checksum Result => new(_cells, _sum, _chars, _trues);

        public void Add(object? value)
        {
            switch (value)
            {
                case null:
                    return;
                case string s:
                    _chars += s.Length;
                    break;
                case double d:
                    _sum += d;
                    break;
                case DateTime dt:
                    _sum += dt.ToOADate();
                    break;
                case bool b:
                    _trues += b ? 1 : 0;
                    break;
                default:
                    _sum += Convert.ToDouble(value, CultureInfo.InvariantCulture);
                    break;
            }

            _cells++;
        }
    }

    /// <summary>
    /// Stopwatch run over a 1M-row workbook (or the given size): best of three, managed allocations of one run,
    /// and the process's peak working set. Pass a reader name (and <c>inline</c> or <c>shared</c>) to run only
    /// that reader, so that each reader's peak memory is measured in a fresh process; the checksum is then
    /// printed for comparison instead of being checked.
    /// </summary>
    public static void RunQuick(int rows, string? only, string? storage)
    {
        foreach (var shared in new[] { false, true })
        {
            if (storage is not null && storage != (shared ? "shared" : "inline"))
            {
                continue;
            }

            var path = Generate(rows, shared);
            var mode = shared ? "shared" : "inline";
            Checksum? reference = null;
            foreach (var (name, run) in All)
            {
                if (only is not null && !name.StartsWith(only, StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                var checksum = run(path);
                if (only is null)
                {
                    reference ??= checksum;
                }
                var best = double.MaxValue;
                long allocated = 0;
                for (var i = 0; i < 3; i++)
                {
                    GC.Collect();
                    GC.WaitForPendingFinalizers();
                    var before = GC.GetTotalAllocatedBytes(true);
                    var watch = Stopwatch.StartNew();
                    checksum = run(path);
                    best = Math.Min(best, watch.Elapsed.TotalMilliseconds);
                    allocated = GC.GetTotalAllocatedBytes(true) - before;
                }

                var ok = reference is null ? checksum.ToString() : checksum.Matches(reference.Value) ? "ok" : $"MISMATCH {checksum} vs {reference}";
                // Zero on macOS, where peak memory has to be measured from outside (e.g. /usr/bin/time -l).
                var peak = Process.GetCurrentProcess().PeakWorkingSet64;
                var peakText = peak > 0 ? string.Create(CultureInfo.InvariantCulture, $"  {peak / 1048576.0,6:N0} MB peak WS") : "";
                Console.WriteLine(string.Create(
                    CultureInfo.InvariantCulture,
                    $"{mode,-6} {name,-30} {best,8:N0} ms  {allocated / 1048576.0,9:N1} MB allocated{peakText}  {new FileInfo(path).Length / 1048576.0,5:N1} MB file  {ok}"));
            }
        }
    }
}

[MemoryDiagnoser]
public class ReadBenchmarks
{
    private string _inline = "";
    private string _shared = "";

    [Params(100_000)]
    public int Rows { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        _inline = ReadCases.Generate(Rows, sharedStrings: false);
        _shared = ReadCases.Generate(Rows, sharedStrings: true);
    }

    [Benchmark(Baseline = true, Description = "Hypaxel (ExcelValue)")]
    public Checksum Hypaxel() => ReadCases.HypaxelValues(_inline);

    [Benchmark(Description = "Hypaxel (typed, no alloc)")]
    public Checksum HypaxelTyped() => ReadCases.HypaxelTyped(_inline);

    [Benchmark(Description = "Hypaxel (background inflate)")]
    public Checksum HypaxelBackground() => ReadCases.HypaxelTyped(_inline, new XlsxReaderOptions { BackgroundDecompression = true });

    [Benchmark(Description = "Hypaxel (shared strings)")]
    public Checksum HypaxelShared() => ReadCases.HypaxelValues(_shared);

    [Benchmark(Description = "Sylvan.Data.Excel")]
    public Checksum Sylvan() => ReadCases.Sylvan(_inline);

    [Benchmark(Description = "ExcelDataReader")]
    public Checksum ExcelDataReader() => ReadCases.ExcelDataReaderCase(_inline);

    [Benchmark(Description = "MiniExcel")]
    public Checksum MiniExcel() => ReadCases.MiniExcelCase(_inline);
}
