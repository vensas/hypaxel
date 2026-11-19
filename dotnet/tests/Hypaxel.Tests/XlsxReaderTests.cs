using System.IO.Compression;
using System.Text;
using Hypaxel.Styling;

namespace Hypaxel.Tests;

public class XlsxReaderTests
{
    internal const string RootRels = """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""";

    internal static byte[] Write(Action<XlsxWriter> write, XlsxWriterOptions? options = null)
    {
        var stream = new MemoryStream();
        using (var writer = new XlsxWriter(stream, options))
        {
            write(writer);
            writer.Complete();
        }

        return stream.ToArray();
    }

    private static XlsxReader Reader(byte[] bytes, XlsxReaderOptions? options = null) => new(new MemoryStream(bytes), options);

    /// <summary>Every row of a sheet as (row number, cells as text), dates in ISO 8601.</summary>
    internal static List<(int Number, string Values)> Dump(byte[] bytes, string sheet)
    {
        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet(sheet);
        return rows.Select(row => (row.Number, J(row.GetValues().Select(v => v.ToString()).ToArray()))).ToList();
    }

    internal static string J(params string[] values) => string.Join("|", values);

    internal static byte[] Package(params (string Name, string Content)[] parts)
    {
        var stream = new MemoryStream();
        using (var zip = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var (name, content) in parts)
            {
                using var entry = zip.CreateEntry(name).Open();
                entry.Write(Encoding.UTF8.GetBytes(content));
            }
        }

        return stream.ToArray();
    }

    internal static byte[] SingleSheet(string sheetXml, string extraRels = "", params (string, string)[] extraParts)
    {
        const string workbook = """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""";
        var rels = $"""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>{extraRels}</Relationships>""";
        return Package([("_rels/.rels", RootRels), ("xl/workbook.xml", workbook), ("xl/_rels/workbook.xml.rels", rels), ("xl/worksheets/sheet1.xml", sheetXml), .. extraParts]);
    }

    private static string RowsError(byte[] bytes) => RowsException(bytes).Message;

    private static XlsxReadException RowsException(byte[] bytes)
    {
        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet("S");
        return Assert.Throws<XlsxReadException>(() =>
        {
            while (rows.Read())
            {
            }
        });
    }

    [Theory]
    [InlineData(StringStorage.Inline)]
    [InlineData(StringStorage.SharedStrings)]
    public void ReadsBackEveryValueType(StringStorage storage)
    {
        const string tricky = "<&>\"' _x0041_ \u0001\ttab\nline 東京 😀";
        var dt = new DateTime(2024, 3, 15, 13, 45, 30);
        var bytes = Write(
            w =>
            {
                var bold = w.AddStyle(new CellStyle { Font = new Font { Bold = true } });
                var s = w.AddWorksheet("Data");
                s.AddRow("text", 42, -1_234_567_890_123L, 3.25, true, false);
                s.AddRow(dt, DateOnly.FromDateTime(dt), new TimeOnly(6, 0), TimeSpan.FromHours(36));
                s.AddRow(double.NaN, (string?)null, "after-gap", tricky);
                s.WriteFormula("SUM(B1:D1)");
                s.WriteBlank(bold);
                s.Write("");
                s.EndRow();
            },
            new XlsxWriterOptions { StringStorage = storage });

        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet("Data");

        Assert.True(rows.Read());
        var row = rows.Row;
        Assert.Equal(1, row.Number);
        Assert.Equal(ExcelValue.FromString("text"), row[1]);
        Assert.Equal(ExcelValue.FromNumber(42), row[2]);
        Assert.Equal(ExcelValue.FromNumber(-1_234_567_890_123), row[3]);
        Assert.Equal(ExcelValue.FromNumber(3.25), row[4]);
        Assert.Equal(ExcelValue.FromBoolean(true), row[5]);
        Assert.Equal(ExcelValue.FromBoolean(false), row[6]);
        Assert.Equal(ExcelValue.Empty, row[7]);
        Assert.True(row.TryGetString(1, out var text));
        Assert.Equal("text", text);
        Assert.True(row.TryGetText(1, out var span));
        Assert.Equal("text", span.ToString());
        Assert.True(row.TryGetDouble(2, out var number));
        Assert.Equal(42, number);
        Assert.False(row.TryGetDouble(1, out _));
        Assert.True(row.TryGetBoolean(5, out var flag) && flag);
        Assert.Equal(ExcelValueKind.Boolean, row.GetKind(6));
        Assert.Equal(storage == StringStorage.SharedStrings ? 0 : -1, row.Cells[0].SharedStringIndex);

        Assert.True(rows.Read());
        row = rows.Row;
        ExcelDateTime Date(int column)
        {
            Assert.True(row[column].TryGetExcelDateTime(out var value));
            return value;
        }

        Assert.Equal(DateKind.DateTime, Date(1).Kind);
        Assert.True(Date(1).TryGetDateTime(out var dateTime));
        Assert.Equal(dt, dateTime);
        Assert.True(row.TryGetDateTime(1, out dateTime));
        Assert.Equal(dt, dateTime);
        Assert.Equal(DateKind.Date, Date(2).Kind);
        Assert.True(Date(2).TryGetDateOnly(out var date));
        Assert.Equal(DateOnly.FromDateTime(dt), date);
        Assert.Equal(DateKind.Time, Date(3).Kind);
        Assert.True(Date(3).TryGetTimeOnly(out var time));
        Assert.Equal(new TimeOnly(6, 0), time);
        Assert.Equal("06:00:00", row[3].ToString());
        Assert.Equal(DateKind.Duration, Date(4).Kind);
        Assert.True(Date(4).TryGetTimeSpan(out var duration));
        Assert.Equal(TimeSpan.FromHours(36), duration);
        Assert.True(row[4].TryGetDouble(out var serial));
        Assert.Equal(1.5, serial);

        Assert.True(rows.Read());
        row = rows.Row;
        Assert.True(row[1].TryGetError(out var error));
        Assert.Equal("#NUM!", error);
        Assert.Equal(ExcelValueKind.Error, row[1].Kind);
        Assert.Equal(ExcelValue.Empty, row[2]);
        Assert.Equal(ExcelValue.FromString("after-gap"), row[3]);
        Assert.Equal(ExcelValue.FromString(tricky), row[4]);
        Assert.Equal([1, 3, 4], row.Cells.Select(c => c.Column));

        // A formula without a cached value and a styled blank have no value; "" is a string.
        Assert.True(rows.Read());
        row = rows.Row;
        Assert.Equal(4, row.Number);
        Assert.Equal([new XlsxCell(3, ExcelValue.FromString(""), storage == StringStorage.SharedStrings ? row.Cells[0].SharedStringIndex : -1)], row.Cells.ToArray());

        Assert.False(rows.Read());
        Assert.False(rows.Read());
    }

    [Theory]
    [InlineData(StringStorage.Inline)]
    [InlineData(StringStorage.SharedStrings)]
    public void PreservesWhitespaceAndLineBreaks(StringStorage storage)
    {
        string[] texts = ["  padded  ", "\tlead", "trail\n", "a\r\nb", " "];
        var bytes = Write(w => w.AddWorksheet("S").AddRow([.. texts.Select(t => (CellValue)t)]), new XlsxWriterOptions { StringStorage = storage });
        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet(0);
        Assert.True(rows.Read());
        Assert.Equal(texts.Select(t => t.Replace("\r\n", "\n")), rows.Row.GetValues().Select(v => v.ToString()));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void RoundTripsDatesInBothDateSystems(bool use1904)
    {
        DateOnly[] dates =
        [
            new(1900, 1, 1), new(1900, 2, 28), new(1900, 3, 1), new(1904, 1, 1), new(1999, 12, 31), new(2024, 2, 29), new(9999, 12, 31),
        ];
        var bytes = Write(
            w =>
            {
                var s = w.AddWorksheet("D");
                foreach (var d in dates)
                {
                    s.AddRow(d, d.ToDateTime(new TimeOnly(23, 59, 59, 123)));
                }
            },
            new XlsxWriterOptions { Use1904DateSystem = use1904 });

        using var reader = Reader(bytes);
        Assert.Equal(use1904, reader.Is1904DateSystem);
        using var rows = reader.OpenWorksheet(0);
        foreach (var d in dates)
        {
            Assert.True(rows.Read());
            var row = rows.Row;
            if (use1904 && d.Year < 1904)
            {
                Assert.Equal(ExcelValue.FromString(d.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture)), row[1]);
                continue;
            }

            Assert.True(row[1].TryGetExcelDateTime(out var value), $"{d} 1904={use1904} {row[1]}");
            Assert.Equal(use1904, value.Is1904);
            Assert.True(value.TryGetDateOnly(out var date));
            Assert.Equal(d, date);
            Assert.True(row.TryGetDateTime(2, out var dateTime));
            Assert.Equal(d.ToDateTime(new TimeOnly(23, 59, 59, 123)), dateTime);
        }
    }

    [Fact]
    public void ListsSheetsWithVisibilityAndReadsEach()
    {
        var bytes = Write(w =>
        {
            w.AddWorksheet("First").AddRow("a", 1);
            w.AddWorksheet("Secret", new WorksheetOptions { Hidden = true }).AddRow("b", 2);
            w.AddWorksheet("Q&A <1>").AddRow("c", 3);
        });
        using var reader = Reader(bytes);
        Assert.Equal(["First", "Secret", "Q&A <1>"], reader.SheetNames);
        Assert.Equal(SheetVisibility.Hidden, reader.Sheets[1].Visibility);
        Assert.Equal(SheetVisibility.Visible, reader.Sheets[0].Visibility);
        Assert.Equal(SheetKind.Worksheet, reader.Sheets[0].Kind);

        Assert.Equal([(1, J("c", "3"))], Dump(bytes, "q&a <1>"));
        Assert.Equal([(1, J("b", "2"))], Dump(bytes, "Secret"));
        var missing = Assert.Throws<KeyNotFoundException>(() => reader.OpenWorksheet("Missing"));
        Assert.Contains("'First'", missing.Message);
        Assert.Throws<ArgumentOutOfRangeException>(() => reader.OpenWorksheet(3));
        Assert.Throws<ArgumentOutOfRangeException>(() => reader.OpenWorksheet(-1));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void SeveralWorksheetsCanBeReadAtTheSameTime(bool background)
    {
        var bytes = Write(
            w =>
            {
                var a = w.AddWorksheet("A");
                for (var i = 0; i < 20_000; i++)
                {
                    a.AddRow("a", i);
                }

                var b = w.AddWorksheet("B");
                for (var i = 0; i < 20_000; i++)
                {
                    b.AddRow("b", -i);
                }
            },
            new XlsxWriterOptions { StringStorage = StringStorage.SharedStrings });
        using var reader = Reader(bytes, new XlsxReaderOptions { BackgroundDecompression = background });
        using var first = reader.OpenWorksheet("A");
        using var second = reader.OpenWorksheet("B");
        for (var i = 0; i < 20_000; i++)
        {
            Assert.True(first.Read());
            Assert.True(second.Read());
            Assert.Equal(ExcelValue.FromNumber(i), first.Row[2]);
            Assert.Equal(ExcelValue.FromNumber(-i), second.Row[2]);
            Assert.Equal(ExcelValue.FromString("b"), second.Row[1]);
        }

        Assert.False(first.Read());
        Assert.False(second.Read());
    }

    [Fact]
    public void SkipsMissingAndValuelessRowsAndReportsRowNumbers()
    {
        var bytes = Write(w =>
        {
            var s = w.AddWorksheet("S");
            s.AddRow("first");
            s.BeginRow(5, height: 30);
            s.EndRow();
            s.BeginRow(1_000);
            s.SkipCells(2);
            s.Write("C1000");
            s.EndRow();
            s.BeginRow(CellReference.MaxRows);
            s.SkipCells(CellReference.MaxColumns - 1);
            s.Write("last");
            s.EndRow();
        });
        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet("S");
        var seen = rows.Select(r => (r.Number, r.LastColumn, r.GetValues().Length, r.Count)).ToList();
        Assert.Equal([(1, 1, 1, 1), (1_000, 3, 3, 1), (CellReference.MaxRows, CellReference.MaxColumns, CellReference.MaxColumns, 1)], seen);
    }

    [Fact]
    public void FillsDenseValuesIntoAReusedBuffer()
    {
        var bytes = SingleSheet("""<worksheet><sheetData><row r="2"><c r="B2"><v>2</v></c><c r="D2" t="inlineStr"><is><t>d</t></is></c><c r="F2"><v>6</v></c></row></sheetData></worksheet>""");
        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet(0);
        Assert.True(rows.Read());
        var buffer = new ExcelValue[4];
        buffer[0] = ExcelValue.FromNumber(99);
        rows.Row.GetValues(buffer);
        Assert.Equal([ExcelValue.Empty, ExcelValue.FromNumber(2), ExcelValue.Empty, ExcelValue.FromString("d")], buffer);
        Assert.Equal(6, rows.Row.GetValues().Length);
        Assert.Equal("2: B2=2 D2=d F2=6", rows.Row.ToString());
    }

    [Fact]
    public void HandlesXmlVariantsFromOtherProducers()
    {
        // Prefixed namespaces, no cell or row references, out-of-order and duplicate cells, inline rich text
        // with phonetic runs, ISO dates, CDATA, comments, entities and CRLF line endings.
        const string sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<x:dimension ref=\"A1:C3\"/><x:sheetViews><x:sheetView workbookViewId=\"0\"/></x:sheetViews>"
            + "<x:sheetData>"
            + "<x:row><x:c><x:v>1</x:v></x:c><x:c t=\"str\"><x:f>\"a\"&amp;\"b\"</x:f><x:v>ab</x:v></x:c><x:c t=\"b\"><x:v>0</x:v></x:c></x:row>"
            + "<x:row r=\"3\" spans=\"1:3\"><x:c r=\"C3\"><x:v>3</x:v></x:c><x:c r=\"A3\"><x:v>1</x:v></x:c><x:c r=\"C3\"><x:v>4</x:v></x:c></x:row>"
            + "<!-- a comment --><x:row><x:c t=\"inlineStr\"><x:is><x:r><x:t>Tok</x:t></x:r><x:r><x:rPr><x:b/></x:rPr><x:t xml:space=\"preserve\">yo &amp; </x:t></x:r><x:rPh><x:t>トウキョウ</x:t></x:rPh></x:is></x:c>"
            + "<x:c t=\"d\"><x:v>2024-05-01T08:30:00</x:v></x:c><x:c t=\"inlineStr\"><x:is><x:t><![CDATA[<cdata> & _x0041_]]></x:t></x:is></x:c>"
            + "<x:c t=\"inlineStr\"><x:is><x:t>line1\r\nline2</x:t></x:is></x:c><x:c t=\"e\"><x:v>#N/A</x:v></x:c><x:c t=\"n\"><x:v> 1.5E3 </x:v></x:c></x:row>"
            + "<x:row><x:c><x:v><![CDATA[7]]></x:v></x:c><x:c><x:v>&#56;</x:v></x:c><x:c t=\"d\"><x:v>not a date</x:v></x:c><x:c t=\"s\"></x:c><x:c><x:v></x:v></x:c><x:c t=\"b\"><x:v>true</x:v></x:c></x:row>"
            + "</x:sheetData><x:mergeCells count=\"0\"/></x:worksheet>";
        var bytes = SingleSheet(sheet);
        Assert.Equal(
            [
                (1, J("1", "ab", "FALSE")),
                (3, J("1", "", "4")),
                (4, J("Tokyo & ", "2024-05-01T08:30:00", "<cdata> & A", "line1\nline2", "#N/A", "1500")),
                (5, J("7", "8", "not a date", "", "", "TRUE")),
            ],
            Dump(bytes, "S"));
    }

    [Fact]
    public void AppliesCustomAndBuiltinDateFormats()
    {
        const string styles = """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="200" formatCode="[$-409]d\-mmm\-yy;@"/></numFmts><cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="200"/><xf numFmtId="14"/><xf numFmtId="4"/></cellXfs></styleSheet>""";
        const string sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="1"><v>45413</v></c><c r="B1" s="2"><v>45413</v></c><c r="C1" s="3"><v>45413</v></c><c r="D1" s="99"><v>1</v></c></row></sheetData></worksheet>""";
        const string rels = """<Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="/xl/styles.xml"/>""";
        var bytes = SingleSheet(sheet, rels, ("xl/styles.xml", styles));
        Assert.Equal([(1, J("2024-05-01", "2024-05-01", "45413", "1"))], Dump(bytes, "S"));
    }

    [Fact]
    public void AcceptsStrictOpenXmlAndUnusualPartNames()
    {
        const string root = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument" Target="/Book/WB.xml"/></Relationships>""";
        const string workbook = """<workbook xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main" xmlns:r="http://purl.oclc.org/ooxml/officeDocument/relationships"><workbookPr date1904="true"/><sheets><sheet name="Strict" sheetId="1" state="veryHidden" r:id="rIdA"/><sheet name="Chart" sheetId="2" r:id="rIdC"/></sheets></workbook>""";
        const string rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdA" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/worksheet" Target="sheets/../sheets/One.xml"/><Relationship Id="rIdC" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/chartsheet" Target="charts/c.xml"/><Relationship Id="rIdS" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/sharedStrings" Target="strings.xml"/><Relationship Id="rIdX" Type="http://x/hyperlink" Target="https://example.com" TargetMode="External"/></Relationships>""";
        const string sst = """<sst xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main"><si><t>strict</t></si></sst>""";
        const string sheet = """<worksheet xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="d"><v>1904-01-02</v></c></row></sheetData></worksheet>""";
        var bytes = Package(("_rels/.rels", root), ("Book/WB.xml", workbook), ("Book/_rels/WB.xml.rels", rels), ("Book/sheets/One.xml", sheet), ("Book/strings.xml", sst));

        using var reader = Reader(bytes);
        Assert.True(reader.Is1904DateSystem);
        Assert.Equal(SheetVisibility.VeryHidden, reader.Sheets[0].Visibility);
        Assert.Equal(SheetKind.Chartsheet, reader.Sheets[1].Kind);
        var chart = Assert.Throws<XlsxReadException>(() => reader.OpenWorksheet("Chart"));
        Assert.Equal(XlsxReadErrorKind.Unsupported, chart.Kind);

        using var rows = reader.OpenWorksheet("Strict");
        Assert.True(rows.Read());
        Assert.Equal(ExcelValue.FromString("strict"), rows.Row[1]);
        Assert.True(rows.Row[2].TryGetExcelDateTime(out var date));
        Assert.True(date.TryGetDateOnly(out var day));
        Assert.Equal(new DateOnly(1904, 1, 2), day);
    }

    [Fact]
    public void ReportsClearErrorsForWrongInputs()
    {
        var csv = Assert.Throws<XlsxReadException>(() => Reader("id,name\n1,Alice\n"u8.ToArray()));
        Assert.Equal(XlsxReadErrorKind.InvalidFile, csv.Kind);

        var ole = new byte[512];
        new byte[] { 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1 }.CopyTo(ole, 0);
        var legacy = Assert.Throws<XlsxReadException>(() => Reader(ole));
        Assert.Equal(XlsxReadErrorKind.InvalidFile, legacy.Kind);
        Assert.Contains("legacy .xls", legacy.Message);

        var docx = Package(("_rels/.rels", RootRels.Replace("xl/workbook.xml", "word/document.xml")), ("word/document.xml", "<w/>"));
        var word = Assert.Throws<XlsxReadException>(() => Reader(docx));
        Assert.Contains("Word or PowerPoint", word.Message);

        var notWorkbook = Package(("_rels/.rels", RootRels), ("xl/workbook.xml", "<document/>"));
        Assert.Contains("Word or PowerPoint", Assert.Throws<XlsxReadException>(() => Reader(notWorkbook)).Message);

        var badSst = RowsException(SingleSheet("""<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>7</v></c></row></sheetData></worksheet>"""));
        Assert.Equal(XlsxReadErrorKind.Malformed, badSst.Kind);
        Assert.Equal("xl/worksheets/sheet1.xml", badSst.Part);
        Assert.Contains("Row 1", badSst.Message);

        Assert.Contains("invalid number \"1,5\"", RowsError(SingleSheet("""<worksheet><sheetData><row r="1"><c r="A1"><v>1,5</v></c></row></sheetData></worksheet>""")));
        Assert.Contains("invalid boolean", RowsError(SingleSheet("""<worksheet><sheetData><row r="1"><c r="A1" t="b"><v>yes</v></c></row></sheetData></worksheet>""")));
        Assert.Contains("invalid reference", RowsError(SingleSheet("""<worksheet><sheetData><row r="1"><c r="ABCD1"><v>1</v></c></row></sheetData></worksheet>""")));
        Assert.Contains("unknown type", RowsError(SingleSheet("""<worksheet><sheetData><row r="1"><c r="A1" t="x"><v>1</v></c></row></sheetData></worksheet>""")));
        Assert.Contains("invalid row number", RowsError(SingleSheet("""<worksheet><sheetData><row r="x"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""")));
        Assert.Contains("outside 1..1048576", RowsError(SingleSheet("""<worksheet><sheetData><row r="1048577"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""")));
        Assert.Contains("DTD", RowsError(SingleSheet("""<!DOCTYPE lol [<!ENTITY a "aaaa">]><worksheet><sheetData/></worksheet>""")));
        Assert.Contains("Unexpected end", RowsError(SingleSheet("""<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c>""")));

        var missingPart = Package(
            ("_rels/.rels", RootRels),
            ("xl/workbook.xml", """<workbook xmlns:r="r"><sheets><sheet name="S" r:id="rId1"/><sheet name="NoRel"/></sheets></workbook>"""),
            ("xl/_rels/workbook.xml.rels", """<Relationships><Relationship Id="rId1" Type="x/worksheet" Target="worksheets/gone.xml"/></Relationships>"""));
        using var reader = Reader(missingPart);
        Assert.Contains("is missing", Assert.Throws<XlsxReadException>(() => reader.OpenWorksheet("S")).Message);
        Assert.Contains("no relationship", Assert.Throws<XlsxReadException>(() => reader.OpenWorksheet("NoRel")).Message);
    }

    [Fact]
    public void ReadsZip64Packages()
    {
        // More than 65,535 entries make ZipArchive write ZIP64 end of central directory records.
        var source = Write(w => w.AddWorksheet("S").AddRow("zip64", 64));
        var stream = new MemoryStream();
        using (var input = new ZipArchive(new MemoryStream(source), ZipArchiveMode.Read))
        using (var output = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var entry in input.Entries)
            {
                using var from = entry.Open();
                using var to = output.CreateEntry(entry.FullName).Open();
                from.CopyTo(to);
            }

            for (var i = 0; i < 65_536; i++)
            {
                output.CreateEntry($"padding/{i}", CompressionLevel.NoCompression);
            }
        }

        Assert.Equal([(1, J("zip64", "64"))], Dump(stream.ToArray(), "S"));
    }

    [Fact]
    public void RejectsUnusableStreams()
    {
        Assert.Throws<ArgumentNullException>(() => new XlsxReader(null!));
        Assert.Throws<ArgumentException>(() => new XlsxReader(new AsyncOnlyStream()));
        Assert.Throws<ArgumentOutOfRangeException>(() => Reader([], new XlsxReaderOptions { MaxSharedStringsBytes = -1 }));
    }

    [Fact]
    public void DisposesTheStreamOnlyWhenItOwnsIt()
    {
        var bytes = Write(w => w.AddWorksheet("S").AddRow(1));
        var borrowed = new MemoryStream(bytes);
        new XlsxReader(borrowed).Dispose();
        Assert.True(borrowed.CanRead);

        var owned = new MemoryStream(bytes);
        var reader = new XlsxReader(owned, new XlsxReaderOptions { OwnsStream = true });
        reader.Dispose();
        Assert.False(owned.CanRead);
        Assert.Throws<ObjectDisposedException>(() => reader.OpenWorksheet(0));

        var invalid = new MemoryStream("not a workbook, not a workbook"u8.ToArray());
        Assert.Throws<XlsxReadException>(() => new XlsxReader(invalid, new XlsxReaderOptions { OwnsStream = true }));
        Assert.False(invalid.CanRead);
    }

    [Fact]
    public void OpensFilesByPath()
    {
        var path = Path.Combine(Path.GetTempPath(), $"hypaxel-reader-{Guid.NewGuid():N}.xlsx");
        try
        {
            File.WriteAllBytes(path, Write(w => w.AddWorksheet("Orders").AddRow("Widget", 12.5)));
            using (var reader = XlsxReader.Open(path))
            using (var rows = reader.OpenWorksheet("Orders"))
            {
                Assert.True(rows.Read());
                Assert.Equal("1: A1=Widget B1=12.5", rows.Row.ToString());
            }

            File.Delete(path);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void EnforcesTheSharedStringLimit()
    {
        var bytes = Write(
            w =>
            {
                var s = w.AddWorksheet("S");
                for (var i = 0; i < 1_000; i++)
                {
                    s.AddRow($"unique string number {i}");
                }
            },
            new XlsxWriterOptions { StringStorage = StringStorage.SharedStrings });

        using var limited = Reader(bytes, new XlsxReaderOptions { MaxSharedStringsBytes = 1_000, BackgroundDecompression = true });
        var error = Assert.Throws<XlsxReadException>(() => limited.OpenWorksheet("S"));
        Assert.Equal(XlsxReadErrorKind.LimitExceeded, error.Kind);
        Assert.Contains("MaxSharedStringsBytes", error.Message);

        using var reader = Reader(bytes);
        Assert.Equal(1_000, reader.SharedStringCount);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void StreamsLargeSheets(bool background)
    {
        var bytes = Write(w =>
        {
            var s = w.AddWorksheet("Big");
            for (var i = 0; i < 200_000; i++)
            {
                s.AddRow(i, "constant text", i * 0.5, i % 2 == 0);
            }
        });
        using var reader = Reader(bytes, new XlsxReaderOptions { BackgroundDecompression = background });
        using var rows = reader.OpenWorksheet("Big");
        var count = 0;
        var sum = 0.0;
        foreach (var row in rows)
        {
            Assert.Equal(count + 1, row.Number);
            Assert.True(row.TryGetDouble(3, out var value));
            sum += value;
            count++;
        }

        Assert.Equal(200_000, count);
        Assert.Equal(Enumerable.Range(0, 200_000).Sum(i => i * 0.5), sum);
    }

    [Theory]
    [InlineData(CompressionLevel.NoCompression, false)]
    [InlineData(CompressionLevel.Fastest, false)]
    [InlineData(CompressionLevel.NoCompression, true)]
    [InlineData(CompressionLevel.Fastest, true)]
    public void DamagedFilesFailWithTheDocumentedException(CompressionLevel compression, bool background)
    {
        var bytes = Write(
            w =>
            {
                var date = w.AddStyle(new CellStyle { NumberFormat = "yyyy-mm-dd" });
                var s = w.AddWorksheet("S");
                for (var i = 0; i < 50; i++)
                {
                    s.AddRow($"s{i}", i, CellValue.From(45_000 + i, date), i % 3 == 0, "inline <&>");
                }
            },
            new XlsxWriterOptions { StringStorage = StringStorage.SharedStrings, CompressionLevel = compression });

        // Deterministic pseudo-random mutations of an uncompressed package, so that the XML parser (not only
        // the ZIP CRC check, which runs at the end of each entry) sees damaged input.
        var state = 0x2545_f491_4f6c_dd1dUL;
        ulong Next()
        {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            return state;
        }

        var opened = 0;
        for (var round = 0; round < 2_000; round++)
        {
            var damaged = (byte[])bytes.Clone();
            for (var k = 0UL; k < 1 + (Next() % 4); k++)
            {
                damaged[(int)(Next() % (ulong)damaged.Length)] = (byte)Next();
            }

            if (Next() % 4 == 0)
            {
                Array.Resize(ref damaged, (int)(Next() % (ulong)damaged.Length));
            }

            try
            {
                using var reader = Reader(damaged, new XlsxReaderOptions { BackgroundDecompression = background });
                using var rows = reader.OpenWorksheet(0);
                opened++;
                while (rows.Read())
                {
                    foreach (var cell in rows.Row.Cells)
                    {
                        _ = cell.Value.ToString();
                        _ = cell.Value.TryGetDateTime(out _);
                    }
                }
            }
            catch (XlsxReadException)
            {
            }
        }

        Assert.True(opened > 20, $"only {opened} damaged packages could be opened");
    }
}
