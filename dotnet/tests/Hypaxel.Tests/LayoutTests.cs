using System.Globalization;
using System.IO.Compression;
using System.Text;
using System.Text.RegularExpressions;
using Hypaxel.Internal;
using Hypaxel.Styling;

namespace Hypaxel.Tests;

public class LayoutTests
{
    /// <summary>A generated workbook: validated with the Open XML SDK, with raw access to its parts.</summary>
    private sealed class Output : IDisposable
    {
        private readonly Dictionary<string, string> _parts = [];

        public Output(byte[] bytes)
        {
            using (var zip = new ZipArchive(new MemoryStream(bytes), ZipArchiveMode.Read))
            {
                foreach (var entry in zip.Entries)
                {
                    using var reader = new StreamReader(entry.Open(), Encoding.UTF8);
                    _parts[entry.FullName] = reader.ReadToEnd();
                }
            }

            Book = new Workbook(bytes);
            Book.AssertValid();
        }

        public Workbook Book { get; }

        public IReadOnlyCollection<string> PartNames => _parts.Keys;

        public string Part(string name) => _parts[name];

        public void Dispose() => Book.Dispose();
    }

    private static Output Write(Action<XlsxWriter> write, XlsxWriterOptions? options = null)
    {
        var stream = new MemoryStream();
        using (var writer = new XlsxWriter(stream, options))
        {
            write(writer);
            writer.Complete();
        }

        return new Output(stream.ToArray());
    }

    private static List<(int Column, double Width)> ColumnWidths(string sheetXml) =>
        Regex.Matches(sheetXml, "<col min=\"(\\d+)\" max=\"\\d+\" width=\"([^\"]+)\"")
            .Select(m => (int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture), double.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture)))
            .ToList();

    // ------------------------------------------------------------------ row outlines

    [Fact]
    public void AddRowFillsAnExplicitlyBegunRow()
    {
        using var output = Write(w =>
        {
            var s = w.AddWorksheet("S", new WorksheetOptions { RowOutlineLevels = 1 });
            s.BeginRow(new RowOptions { OutlineLevel = 1 });
            s.AddRow("grouped", 1);
            s.AddRow("plain", 2);
            s.BeginRow(30.0);
            s.Write("partial");
            s.AddRow("next row", 3);
            s.BeginRow(new RowOptions { Hidden = true });
            s.SkipCells(1);
            s.AddRow("after skip");
            s.BeginRow(new RowOptions { OutlineLevel = 1 });
            s.EndRow();
            s.AddRow("after empty row");
        });
        var xml = output.Part("xl/worksheets/sheet1.xml");
        Assert.Contains("<row r=\"1\" outlineLevel=\"1\"><c t=\"inlineStr\"><is><t>grouped</t>", xml);
        Assert.Contains("<row r=\"3\" ht=\"30\" customHeight=\"1\"><c t=\"inlineStr\"><is><t>partial</t></is></c></row>", xml);
        Assert.Contains("<row r=\"5\" hidden=\"1\"></row>", xml);
        Assert.Contains("<row r=\"7\" outlineLevel=\"1\"></row>", xml);
        Assert.Equal("plain", output.Book.Value("S", "A2"));
        Assert.Equal("next row", output.Book.Value("S", "A4"));
        Assert.Equal("after skip", output.Book.Value("S", "A6"));
        Assert.Equal("after empty row", output.Book.Value("S", "A8"));
    }

    private sealed record Named(string Name);

    [Fact]
    public void ObjectsFillAnExplicitlyBegunRow()
    {
        using var output = Write(w =>
        {
            var s = w.AddWorksheet("S");
            s.AddRow("title");
            s.BeginRow(25.0);
            s.WriteObjects([new Named("a"), new Named("b")], new ObjectWriteOptions { AutoFilter = true });
        });
        var xml = output.Part("xl/worksheets/sheet1.xml");
        Assert.Contains("<row r=\"2\" ht=\"25\" customHeight=\"1\">", xml);
        Assert.Equal("Name", output.Book.Value("S", "A2"));
        Assert.Contains("<autoFilter ref=\"A2:A4\"/>", xml);
    }

    [Fact]
    public void RowOutlineLevels()
    {
        using var output = Write(w =>
        {
            var s = w.AddWorksheet("Tree", new WorksheetOptions { RowOutlineLevels = 2, OutlineSummaryAbove = true });
            s.AddRow("North", 300);
            s.BeginRow(new RowOptions { OutlineLevel = 1 });
            s.Write("Store A");
            s.Write(120);
            s.EndRow();
            s.BeginRow(new RowOptions { OutlineLevel = 2, Hidden = true });
            s.Write("Till 1");
            s.Write(60);
            s.EndRow();
            s.BeginRow(new RowOptions { OutlineLevel = 1, Collapsed = true, Height = 20 });
            s.Write("Store B");
            s.EndRow();
            s.AddRow("South", 1);
        });

        var xml = output.Part("xl/worksheets/sheet1.xml");
        Assert.Contains("<sheetPr><outlinePr summaryBelow=\"0\"/></sheetPr>", xml);
        Assert.Contains("<sheetFormatPr defaultRowHeight=\"15\" outlineLevelRow=\"2\"/>", xml);
        Assert.Contains("<row r=\"1\">", xml);
        Assert.Contains("<row r=\"2\" outlineLevel=\"1\">", xml);
        Assert.Contains("<row r=\"3\" hidden=\"1\" outlineLevel=\"2\">", xml);
        Assert.Contains("<row r=\"4\" ht=\"20\" customHeight=\"1\" outlineLevel=\"1\" collapsed=\"1\">", xml);
        Assert.Equal("Till 1", output.Book.Value("Tree", "A3"));
        Assert.Equal("1", output.Book.Value("Tree", "B5"));
    }

    [Fact]
    public void OutlineLevelsMustBeDeclared()
    {
        using var writer = new XlsxWriter(new MemoryStream());
        var s = writer.AddWorksheet("S", new WorksheetOptions { RowOutlineLevels = 1 });
        s.BeginRow(new RowOptions { OutlineLevel = 1 });
        var error = Assert.Throws<ArgumentOutOfRangeException>(() => s.BeginRow(new RowOptions { OutlineLevel = 2 }));
        Assert.Contains("RowOutlineLevels", error.Message);
        Assert.Throws<ArgumentOutOfRangeException>(() => s.BeginRow(new RowOptions { OutlineLevel = 8 }));
        Assert.Throws<ArgumentOutOfRangeException>(() => writer.AddWorksheet("T", new WorksheetOptions { RowOutlineLevels = 8 }));
    }

    [Fact]
    public void TabColorAndSummaryAboveShareSheetPr()
    {
        using var output = Write(w => w.AddWorksheet("S", new WorksheetOptions { TabColor = ExcelColor.Blue, OutlineSummaryAbove = true }).AddRow(1));
        Assert.Contains("<sheetPr><tabColor rgb=\"FF0000FF\"/><outlinePr summaryBelow=\"0\"/></sheetPr>", output.Part("xl/worksheets/sheet1.xml"));
    }

    // ------------------------------------------------------------------ tables

    [Fact]
    public void Tables()
    {
        using var output = Write(w =>
        {
            var s = w.AddWorksheet("Orders");
            s.AddRow("Product", "Units", "Line\nbreak");
            for (var i = 0; i < 5; i++)
            {
                s.AddRow("Widget", i, 2.5);
            }

            s.AddTable(new ExcelTable(1, 1, s.RowNumber, 3) { Name = "Orders", Columns = ["Product", "Units", "Line\nbreak"] });
            s.AddTable(new ExcelTable(10, 1, 12, 2) { ShowHeaderRow = false, Style = TableStyle.None });

            s = w.AddWorksheet("Second");
            s.AddRow("A & B");
            s.AddRow(1);
            s.AddTable(new ExcelTable(1, 1, 2, 1) { Columns = ["A & B"], Style = TableStyle.Light(1), ShowColumnStripes = true });
        });

        foreach (var part in new[] { "xl/tables/table1.xml", "xl/tables/table2.xml", "xl/tables/table3.xml", "xl/worksheets/_rels/sheet1.xml.rels", "xl/worksheets/_rels/sheet2.xml.rels" })
        {
            Assert.Contains(part, output.PartNames);
        }

        var t1 = output.Part("xl/tables/table1.xml");
        Assert.Contains("id=\"1\" name=\"Orders\" displayName=\"Orders\" ref=\"A1:C6\" totalsRowShown=\"0\"", t1);
        Assert.Contains("<autoFilter ref=\"A1:C6\"/>", t1);
        Assert.Contains("<tableColumn id=\"3\" name=\"Line_x000A_break\"/>", t1);
        Assert.Contains("name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"0\"", t1);

        var t2 = output.Part("xl/tables/table2.xml");
        Assert.Contains("name=\"Table2\"", t2);
        Assert.Contains("headerRowCount=\"0\"", t2);
        Assert.DoesNotContain("autoFilter", t2);
        Assert.Contains("<tableColumn id=\"1\" name=\"Column1\"/>", t2);
        Assert.DoesNotContain("TableStyle", t2);

        var t3 = output.Part("xl/tables/table3.xml");
        Assert.Contains("name=\"Table3\"", t3);
        Assert.Contains("<tableColumn id=\"1\" name=\"A &amp; B\"/>", t3);
        Assert.Contains("name=\"TableStyleLight1\"", t3);
        Assert.Contains("showColumnStripes=\"1\"", t3);

        Assert.EndsWith(
            "<tableParts count=\"2\"><tablePart r:id=\"rId1\"/><tablePart r:id=\"rId2\"/></tableParts></worksheet>",
            output.Part("xl/worksheets/sheet1.xml"));
        Assert.Contains(
            "Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table3.xml\"",
            output.Part("xl/worksheets/_rels/sheet2.xml.rels"));
        Assert.Equal(3, Regex.Matches(output.Part("[Content_Types].xml"), "spreadsheetml\\.table\\+xml").Count);
        Assert.Equal("Product", output.Book.Value("Orders", "A1"));
        Assert.Equal("Orders", output.Book.SheetPart("Orders").TableDefinitionParts.First().Table.Name!.Value);
    }

    [Fact]
    public void TableValidation()
    {
        var stream = new MemoryStream();
        using (var w = new XlsxWriter(stream))
        {
            var s = w.AddWorksheet("S");
            static ExcelTable T(int lastRow, int lastColumn) => new(1, 1, lastRow, lastColumn);

            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 2)));                                          // header names are required
            Assert.Throws<ArgumentException>(() => s.AddTable(T(1, 1) with { Columns = ["a"] }));                // needs a data row
            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 2) with { Columns = ["a"] }));                // column count
            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 2) with { Columns = ["a", "A"] }));           // duplicate column
            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 2) with { Columns = ["a", ""] }));            // empty column
            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 1) with { Columns = ["a"], Name = "A1" }));
            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 1) with { Columns = ["a"], Name = "my table" }));
            Assert.Throws<ArgumentOutOfRangeException>(() => TableStyle.Medium(29));
            Assert.Throws<ArgumentOutOfRangeException>(() => TableStyle.Light(0));
            Assert.Throws<ArgumentOutOfRangeException>(() => TableStyle.Dark(12));

            s.AddTable(T(3, 1) with { Columns = ["a"], Name = "Data" });
            Assert.Throws<InvalidOperationException>(() => s.AddTable(new ExcelTable(3, 1, 5, 1) { Columns = ["b"] }));
            Assert.Throws<ArgumentException>(() => s.AddTable(new ExcelTable(10, 1, 12, 1) { Columns = ["b"], Name = "DATA" }));
            Assert.Throws<InvalidOperationException>(() => s.MergeCells(2, 1, 2, 3));
            Assert.Throws<InvalidOperationException>(() => s.SetAutoFilter(1, 1, 9, 1));

            s.MergeCells(20, 1, 20, 2);
            Assert.Throws<InvalidOperationException>(() => s.AddTable(new ExcelTable(19, 1, 21, 1) { Columns = ["c"] }));
            s.SetAutoFilter(30, 1, 40, 2);
            Assert.Throws<InvalidOperationException>(() => s.AddTable(new ExcelTable(35, 2, 36, 2) { Columns = ["d"] }));

            // Names are unique across worksheets.
            s = w.AddWorksheet("T");
            Assert.Throws<ArgumentException>(() => s.AddTable(T(3, 1) with { Columns = ["a"], Name = "data" }));
            s.AddTable(T(3, 1) with { Columns = ["a"] });
            w.Complete();
        }

        using var output = new Output(stream.ToArray());
        Assert.Contains("name=\"Table2\"", output.Part("xl/tables/table2.xml"));
    }

    [Theory]
    [InlineData("Orders")]
    [InlineData("_t")]
    [InlineData("Sales.2024")]
    [InlineData("Résumé")]
    [InlineData("R1C1x")]
    [InlineData("XFE1")]
    [InlineData("ABCD1")]
    [InlineData("\\name")]
    public void TableNames_Valid(string name) => TableDefinition.ValidateName(name);

    [Theory]
    [InlineData("")]
    [InlineData("1abc")]
    [InlineData("A1")]
    [InlineData("xfd1048576")]
    [InlineData("R")]
    [InlineData("C")]
    [InlineData("r1c1")]
    [InlineData("RC")]
    [InlineData("R12")]
    [InlineData("C3")]
    [InlineData("has space")]
    [InlineData("a-b")]
    [InlineData("a:b")]
    public void TableNames_Invalid(string name) => Assert.Throws<ArgumentException>(() => TableDefinition.ValidateName(name));

    // ------------------------------------------------------------------ auto-fit

    [Fact]
    public void TextWidths()
    {
        Assert.Equal(0f, ColumnFit.TextPx(""));
        Assert.Equal(70f, ColumnFit.TextPx("0123456789"));
        Assert.Equal(ColumnFit.TextPx("longer line"), ColumnFit.TextPx("ab\nlonger line"));
        Assert.True(ColumnFit.TextPx("WWW") > ColumnFit.TextPx("iii"));
        Assert.Equal(30f, ColumnFit.TextPx("日本"));
        Assert.Equal(15f, ColumnFit.TextPx("😀"));
    }

    [Theory]
    [InlineData("#,##0.00", 1234567.891, "1,234,567.89")]
    [InlineData("0.0%", 0.256, "25.6%")]
    [InlineData("0", -42.0, "-42")]
    [InlineData("000", 7.0, "007")]
    [InlineData("\"$\"#,##0", 1500.0, "$1,500")]
    [InlineData("[$€-407] #,##0.00", 10.0, "€ 10.00")]
    [InlineData("yyyy-mm-dd", 45000.0, "2026-12-31")]
    [InlineData("dd mmmm yyyy", 45000.0, "31 September 2026")]
    [InlineData("h:mm AM/PM", 0.5, "12:00 PM")]
    [InlineData("General", 3.25, "3.25")]
    [InlineData("@", 1.0 / 3.0, "0.333333333")]
    public void NumberShapes(string format, double value, string rendered) =>
        Assert.Equal(ColumnFit.TextPx(rendered), ColumnFit.NumberPx(ColumnFit.CustomShape(format), value));

    [Fact]
    public void GeneralNumbers()
    {
        Assert.Equal(ColumnFit.TextPx("42"), ColumnFit.NumberPx(ColumnFit.BuiltInShape(0), 42));
        Assert.Equal(ColumnFit.TextPx("1.23457E+11"), ColumnFit.NumberPx(ColumnFit.BuiltInShape(0), 1e15));
    }

    [Theory]
    [InlineData(StringStorage.Inline)]
    [InlineData(StringStorage.SharedStrings)]
    public void AutoFitMeasuresTheSample(StringStorage storage)
    {
        using var output = Write(
            w =>
            {
                var header = w.AddStyle(new CellStyle { Font = new Font { Bold = true } });
                var big = w.AddStyle(new CellStyle { Font = new Font { Size = 22 } });
                var money = w.AddStyle(new CellStyle { NumberFormat = NumberFormats.Thousands2 });
                var options = new WorksheetOptions()
                    .AutoFitColumns()
                    .SetColumn(2, ColumnDefinition.WithWidth(30))
                    .SetColumn(6, new ColumnDefinition { Hidden = true });
                var s = w.AddWorksheet("Fit", options);
                s.AddRow(CellValue.From("Name", header), "fixed", CellValue.From(1_234_567.0, money), true, CellValue.From("x", big));
                s.AddRow("A much longer product name", "x", CellValue.From(1.0, money), false, CellValue.From("x", big));
                s.AddRow("short\nWith a very long second line in a wrapped cell");
                s.SkipCells(7);
                s.Write(42);
                s.EndRow();
            },
            new XlsxWriterOptions { StringStorage = storage });

        var xml = output.Part("xl/worksheets/sheet1.xml");
        var cols = ColumnWidths(xml);
        double? Width(int column) => cols.Where(c => c.Column == column).Select(c => (double?)c.Width).FirstOrDefault();

        Assert.InRange(Width(1)!.Value, 40, 49.99);                  // longest line of column A
        Assert.Equal(30, Width(2));                                  // explicit widths win
        Assert.InRange(Width(3)!.Value, 9, 12.99);                   // 1,234,567.00 fits
        Assert.InRange(Width(4)!.Value, 5, 6.99);                    // FALSE
        Assert.True(Width(5) > 2);                                   // larger font is wider
        Assert.Equal(9.140625, Width(6));                            // hidden column without content keeps the default width
        Assert.Contains("<col min=\"6\" max=\"6\" width=\"9.140625\" hidden=\"1\"/>", xml);
        Assert.Null(Width(7));                                       // skipped empty columns get no entry
        Assert.True(Width(8) < 3);
        Assert.True(xml.IndexOf("<cols>", StringComparison.Ordinal) < xml.IndexOf("<sheetData>", StringComparison.Ordinal));
        Assert.Equal("42", output.Book.Value("Fit", "H4"));
    }

    [Fact]
    public void AutoFitMeasuresDatesDecimalsAndDurations()
    {
        using var output = Write(w =>
        {
            var iso = w.AddStyle(new CellStyle { NumberFormat = NumberFormats.Date });
            var border = w.AddStyle(new CellStyle { Border = Border.All(BorderLineStyle.Thin) });
            var s = w.AddWorksheet("Fit", new WorksheetOptions { AutoFit = new AutoFitOptions() });
            s.AddRow(
                new DateOnly(2024, 5, 1),
                new DateTime(2024, 5, 1, 13, 45, 0),
                CellValue.From(new DateOnly(2024, 5, 1), iso),
                1.5m,
                TimeSpan.FromHours(36),
                CellValue.Formula("=REPT(\"x\",500)"),
                CellValue.From((string?)null, border));
        });

        var cols = ColumnWidths(output.Part("xl/worksheets/sheet1.xml"));
        Assert.Equal(new (int, double)[] { (1, 9.72), (2, 14.58), (3, 9.43), (4, 2.72), (5, 8.15) }, cols);
    }

    private sealed class CountingStream : Stream
    {
        public MemoryStream Inner { get; } = new();

        public int Writes { get; private set; }

        public override bool CanRead => false;
        public override bool CanSeek => false;
        public override bool CanWrite => true;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }

        public override void Write(byte[] buffer, int offset, int count) => Write(buffer.AsSpan(offset, count));

        public override void Write(ReadOnlySpan<byte> buffer)
        {
            Writes++;
            Inner.Write(buffer);
        }

        public override void Flush()
        {
        }

        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
    }

    [Fact]
    public async Task AutoFitStopsAfterTheSampleAndKeepsStreaming()
    {
        var destination = new CountingStream();
        using (var w = new XlsxWriter(destination, new XlsxWriterOptions { FlushThreshold = 4096, XmlBufferSize = 1024 }))
        {
            var s = w.AddWorksheet("S", new WorksheetOptions { AutoFit = new AutoFitOptions { SampleRows = 10, MaxWidth = 20 } });
            for (var i = 0; i < 9; i++)
            {
                s.AddRow($"row {i} " + new string('x', 200), i);
            }

            await w.FlushAsync();
            Assert.True(destination.Inner.Length < 100, "only the zip entry header is written while the sample is held back");

            s.AddRow("row 9", 9);
            s.AddRow("a value far wider than anything in the sample, which is not measured", 1);
            for (var i = 0; i < 20_000; i++)
            {
                s.AddRow(i, i * 1.5);
            }

            w.Complete();
        }

        Assert.True(destination.Writes > 3, "output is still flushed incrementally after the sample");
        using var output = new Output(destination.Inner.ToArray());
        var cols = ColumnWidths(output.Part("xl/worksheets/sheet1.xml"));
        Assert.Equal(2, cols.Count);
        Assert.Equal(20, cols[0].Width);                                       // clamped by MaxWidth
        Assert.True(cols[1].Width < 2, "rows after the sample are not measured");
    }

    [Fact]
    public void AutoFitStopsAtTheSampleRowCount()
    {
        using var output = Write(w =>
        {
            var s = w.AddWorksheet("S", new WorksheetOptions().AutoFitColumns(new AutoFitOptions { SampleRows = 2 }));
            s.AddRow("a");
            s.AddRow("b");
            s.AddRow("a value far wider than anything in the sample, which is not measured");
        });

        Assert.Equal(new (int, double)[] { (1, ColumnFitWidth("b")) }, ColumnWidths(output.Part("xl/worksheets/sheet1.xml")));
        Assert.Equal(3, output.Book.RowCount("S"));
    }

    private static double ColumnFitWidth(string text) => Math.Ceiling((ColumnFit.TextPx(text) + 2) / 7.0 * 100) / 100;

    [Fact]
    public void AutoFitRespectsMaxWidthAndShortSheets()
    {
        using var output = Write(w =>
        {
            var s = w.AddWorksheet("S", new WorksheetOptions { AutoFit = new AutoFitOptions { MaxWidth = 15 } });
            s.AddRow(new string('x', 500));
            w.AddWorksheet("Empty", new WorksheetOptions().AutoFitColumns());
        });

        Assert.Equal(new (int, double)[] { (1, 15.0) }, ColumnWidths(output.Part("xl/worksheets/sheet1.xml")));
        Assert.DoesNotContain("<cols>", output.Part("xl/worksheets/sheet2.xml"));
    }

    [Fact]
    public void AutoFitValidation()
    {
        using var w = new XlsxWriter(new MemoryStream());
        Assert.Throws<ArgumentOutOfRangeException>(() => w.AddWorksheet("A", new WorksheetOptions { AutoFit = new AutoFitOptions { SampleRows = 0 } }));
        Assert.Throws<ArgumentOutOfRangeException>(() => w.AddWorksheet("B", new WorksheetOptions { AutoFit = new AutoFitOptions { MaxWidth = double.NaN } }));
        Assert.Empty(w.Worksheets);
    }

    [Fact]
    public void AutoFitHoldsBackLargeSamplesBeyondTheXmlBuffer()
    {
        using var output = Write(
            w =>
            {
                var s = w.AddWorksheet("S", new WorksheetOptions().AutoFitColumns());
                for (var i = 0; i < 500; i++)
                {
                    s.AddRow(new string('x', 100 + (i % 7)), i, "tail");
                }
            },
            new XlsxWriterOptions { XmlBufferSize = 1024 });

        var cols = ColumnWidths(output.Part("xl/worksheets/sheet1.xml"));
        Assert.Equal(ColumnFitWidth(new string('x', 106)), cols[0].Width);
        Assert.Equal(500, output.Book.RowCount("S"));
        Assert.Equal("tail", output.Book.Value("S", "C500"));
    }

    [Fact]
    public void EverythingTogetherRoundTrips()
    {
        using var output = Write(w =>
        {
            var options = new WorksheetOptions { RowOutlineLevels = 1, FrozenRows = 1, TabColor = ExcelColor.Blue }.AutoFitColumns();
            var s = w.AddWorksheet("All", options);
            s.AddRow("Region", "Revenue");
            s.AddRow("North", 10);
            s.BeginRow(new RowOptions { OutlineLevel = 1 });
            s.Write("  Store");
            s.Write(4);
            s.EndRow();
            s.AddTable(new ExcelTable(1, 1, 3, 2) { Columns = ["Region", "Revenue"] });
        });

        var xml = output.Part("xl/worksheets/sheet1.xml");
        string[] order = ["<sheetPr>", "<sheetViews>", "<sheetFormatPr", "<cols>", "<sheetData>", "<pageMargins", "<tableParts"];
        var positions = order.Select(tag => xml.IndexOf(tag, StringComparison.Ordinal)).ToList();
        Assert.DoesNotContain(-1, positions);
        Assert.Equal(positions.Order(), positions);
        Assert.Equal("  Store", output.Book.Value("All", "A3"));
    }
}
