using BenchmarkDotNet.Attributes;
using BenchmarkDotNet.Running;
using ClosedXML.Excel;
using Hypaxel;

if (args is ["--quick", ..])
{
    await QuickRun.RunAsync(args.Length > 1 ? int.Parse(args[1]) : 100_000);
    return;
}

BenchmarkSwitcher.FromAssembly(typeof(WriteBenchmarks).Assembly).Run(args);

public sealed record Row(int Id, string Name, string Category, double Price, decimal Amount, DateTime Created, bool Active, long Quantity, string Comment, double Ratio);

public static class Data
{
    private static readonly string[] Categories = ["Hardware", "Software", "Services", "Consulting", "Support", "Training"];

    public static Row[] Create(int count)
    {
        var rows = new Row[count];
        var start = new DateTime(2024, 1, 1);
        for (var i = 0; i < count; i++)
        {
            rows[i] = new Row(
                i,
                "Customer " + (i % 5000),
                Categories[i % Categories.Length],
                i * 1.25,
                i * 0.01m,
                start.AddMinutes(i),
                i % 2 == 0,
                i * 3L,
                "Unique comment #" + i,
                i / 7.0);
        }

        return rows;
    }
}

[MemoryDiagnoser]
public class WriteBenchmarks
{
    private Row[] _rows = [];
    private MemoryStream _stream = new();

    [Params(10_000, 100_000)]
    public int Rows { get; set; }

    [GlobalSetup]
    public void Setup()
    {
        _rows = Data.Create(Rows);
        _stream = new MemoryStream(capacity: 64 * 1024 * 1024);
    }

    [IterationSetup]
    public void Reset() => _stream.SetLength(0);

    public long OutputLength => _stream.Length;

    [Benchmark(Baseline = true, Description = "Hypaxel (typed cells)")]
    public async Task Hypaxel_Typed()
    {
        await using var writer = new XlsxWriter(_stream);
        var sheet = writer.AddWorksheet("Data");
        foreach (var r in _rows)
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
            await sheet.EndRowAsync();
        }

        await writer.CompleteAsync();
    }

    [Benchmark(Description = "Hypaxel (AddRowAsync)")]
    public async Task Hypaxel_AddRow()
    {
        await using var writer = new XlsxWriter(_stream);
        var sheet = writer.AddWorksheet("Data");
        foreach (var r in _rows)
        {
            await sheet.AddRowAsync(r.Id, r.Name, r.Category, r.Price, r.Amount, r.Created, r.Active, r.Quantity, r.Comment, r.Ratio);
        }

        await writer.CompleteAsync();
    }

    [Benchmark(Description = "Hypaxel (shared strings)")]
    public async Task Hypaxel_SharedStrings()
    {
        await using var writer = new XlsxWriter(_stream, new XlsxWriterOptions { StringStorage = StringStorage.SharedStrings });
        var sheet = writer.AddWorksheet("Data");
        foreach (var r in _rows)
        {
            await sheet.AddRowAsync(r.Id, r.Name, r.Category, r.Price, r.Amount, r.Created, r.Active, r.Quantity, r.Comment, r.Ratio);
        }

        await writer.CompleteAsync();
    }

    [Benchmark(Description = "Hypaxel (object mapping)")]
    public async Task Hypaxel_Objects()
    {
        await using var writer = new XlsxWriter(_stream);
        await writer.WriteWorksheetAsync("Data", _rows);
        await writer.CompleteAsync();
    }

    [Benchmark(Description = "SpreadCheetah")]
    public async Task SpreadCheetah()
    {
        await using var spreadsheet = await global::SpreadCheetah.Spreadsheet.CreateNewAsync(_stream);
        await spreadsheet.StartWorksheetAsync("Data");
        var cells = new global::SpreadCheetah.DataCell[10];
        foreach (var r in _rows)
        {
            cells[0] = new(r.Id);
            cells[1] = new(r.Name);
            cells[2] = new(r.Category);
            cells[3] = new(r.Price);
            cells[4] = new(r.Amount);
            cells[5] = new(r.Created);
            cells[6] = new(r.Active);
            cells[7] = new(r.Quantity);
            cells[8] = new(r.Comment);
            cells[9] = new(r.Ratio);
            await spreadsheet.AddRowAsync(cells);
        }

        await spreadsheet.FinishAsync();
    }

    [Benchmark(Description = "ClosedXML")]
    public void ClosedXml()
    {
        using var workbook = new XLWorkbook();
        var sheet = workbook.AddWorksheet("Data");
        var rowIndex = 1;
        foreach (var r in _rows)
        {
            var row = sheet.Row(rowIndex++);
            row.Cell(1).Value = r.Id;
            row.Cell(2).Value = r.Name;
            row.Cell(3).Value = r.Category;
            row.Cell(4).Value = r.Price;
            row.Cell(5).Value = r.Amount;
            row.Cell(6).Value = r.Created;
            row.Cell(7).Value = r.Active;
            row.Cell(8).Value = r.Quantity;
            row.Cell(9).Value = r.Comment;
            row.Cell(10).Value = r.Ratio;
        }

        workbook.SaveAs(_stream);
    }
}

/// <summary>Fast sanity run without BenchmarkDotNet: <c>dotnet run -c Release -- --quick 1000000</c>.</summary>
public static class QuickRun
{
    public static async Task RunAsync(int rows)
    {
        var bench = new WriteBenchmarks { Rows = rows };
        bench.Setup();
        (string Name, Func<Task> Run)[] cases =
        [
            ("Hypaxel (typed cells)", bench.Hypaxel_Typed),
            ("Hypaxel (AddRowAsync)", bench.Hypaxel_AddRow),
            ("Hypaxel (shared strings)", bench.Hypaxel_SharedStrings),
            ("Hypaxel (object mapping)", bench.Hypaxel_Objects),
            ("SpreadCheetah", bench.SpreadCheetah),
            .. (rows <= 200_000 ? new (string, Func<Task>)[] { ("ClosedXML", () => { bench.ClosedXml(); return Task.CompletedTask; }) } : []),
        ];

        foreach (var (name, run) in cases)
        {
            bench.Reset();
            await run(); // warm-up
            GC.Collect();
            var allocated = GC.GetTotalAllocatedBytes(true);
            bench.Reset();
            var sw = System.Diagnostics.Stopwatch.StartNew();
            await run();
            sw.Stop();
            allocated = GC.GetTotalAllocatedBytes(true) - allocated;
            Console.WriteLine($"{name,-26} {rows,9:N0} rows  {sw.Elapsed.TotalMilliseconds,9:N0} ms  {allocated / 1024.0 / 1024.0,9:N1} MB allocated  {bench.OutputLength / 1024.0 / 1024.0,7:N1} MB file");
        }
    }
}
