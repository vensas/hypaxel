using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;
using Hypaxel.Internal.Reading;

namespace Hypaxel.Tests;

public class ReaderInternalsTests
{
    private static Utf8XmlReader Xml(string xml, int buffer = 64 * 1024) => Xml(Encoding.UTF8.GetBytes(xml), buffer);

    private static Utf8XmlReader Xml(byte[] xml, int buffer = 64 * 1024) => new(new MemoryStream(xml), "test.xml", buffer);

    private static List<string> Events(string xml)
    {
        using var reader = Xml(xml, 8);
        var events = new List<string>();
        while (true)
        {
            var token = reader.Next();
            switch (token)
            {
                case XmlToken.Eof:
                    return events;
                case XmlToken.Start or XmlToken.Empty:
                {
                    var text = new StringBuilder("<").Append(Encoding.UTF8.GetString(reader.Name));
                    var attributes = new XmlAttributes(reader.Attributes);
                    while (attributes.MoveNext())
                    {
                        text.Append(' ').Append(Encoding.UTF8.GetString(attributes.Name)).Append('=').Append(Encoding.UTF8.GetString(attributes.Value));
                    }

                    events.Add(text.Append(token == XmlToken.Empty ? "/>" : ">").ToString());
                    break;
                }

                case XmlToken.End:
                    events.Add($"</{Encoding.UTF8.GetString(reader.Name)}>");
                    break;
                case XmlToken.Text:
                    events.Add("T:" + Encoding.UTF8.GetString(reader.Value));
                    break;
                case XmlToken.CData:
                    events.Add("C:" + Encoding.UTF8.GetString(reader.Value));
                    break;
            }
        }
    }

    [Fact]
    public void TokenizesAcrossSmallBuffers()
    {
        const string xml = "<?xml version=\"1.0\"?>\n<x:root xmlns:x=\"urn:a\" x:id=\"7\"><!-- a > comment --><c r='A1' t = \"a>b\">1 &amp; 2</c><e/><![CDATA[<raw>]]></x:root>";
        Assert.Equal(["T:\n", "<root id=7>", "<c r=A1 t=a>b>", "T:1 &amp; 2", "</c>", "<e/>", "C:<raw>", "</root>"], Events(xml));
    }

    /// <summary>A reader positioned right after <c>&lt;row&gt;</c>, as the worksheet parser calls the scan.</summary>
    private static Utf8XmlReader AfterRow(string cells, int buffer = 64 * 1024)
    {
        var reader = Xml("<row>" + cells + "</row>", buffer);
        Assert.Equal(XmlToken.Start, reader.Next());
        return reader;
    }

    private static string Bytes(Utf8XmlReader reader, int start, int length) =>
        start < 0 ? "-" : Encoding.UTF8.GetString(reader.Bytes(start, length));

    [Fact]
    public void ScansCommonCellShapes()
    {
        using var reader = AfterRow("<c r=\"B2\" s='3' t=\"n\"><v>4.5</v></c><c/><c r=\"A1\"></c><c t=\"inlineStr\"><is><t xml:space=\"preserve\"> a &amp; b </t></is></c><c><is><t>x</t></is></c>");
        Assert.True(reader.TryScanCell(out var cell));
        Assert.Equal(CellShape.Value, cell.Shape);
        Assert.Equal("B2", Bytes(reader, cell.ReferenceStart, cell.ReferenceLength));
        Assert.Equal("3", Bytes(reader, cell.StyleStart, cell.StyleLength));
        Assert.Equal("n", Bytes(reader, cell.TypeStart, cell.TypeLength));
        Assert.Equal("4.5", Bytes(reader, cell.TextStart, cell.TextLength));

        Assert.True(reader.TryScanCell(out cell));
        Assert.Equal(CellShape.Empty, cell.Shape);
        Assert.Equal("-", Bytes(reader, cell.ReferenceStart, cell.ReferenceLength));

        Assert.True(reader.TryScanCell(out cell));
        Assert.Equal(CellShape.Empty, cell.Shape);
        Assert.Equal("A1", Bytes(reader, cell.ReferenceStart, cell.ReferenceLength));

        Assert.True(reader.TryScanCell(out cell));
        Assert.Equal(CellShape.Inline, cell.Shape);
        Assert.Equal(" a &amp; b ", Bytes(reader, cell.TextStart, cell.TextLength));

        Assert.True(reader.TryScanCell(out cell));
        Assert.Equal(CellShape.Inline, cell.Shape);
        Assert.Equal("-", Bytes(reader, cell.TypeStart, cell.TypeLength));

        Assert.False(reader.TryScanCell(out _));
        Assert.Equal(XmlToken.End, reader.Next());
    }

    [Theory]
    [InlineData("<c r=\"A1\"><f>1+1</f><v>2</v></c>", "c")]
    [InlineData("<x:c r=\"A1\"><x:v>1</x:v></x:c>", "c")]
    [InlineData("<c x:r=\"A1\"><v>1</v></c>", "c")]
    [InlineData("<c r = \"A1\"><v>1</v></c>", "c")]
    [InlineData("<c\tr=\"A1\"><v>1</v></c>", "c")]
    [InlineData("<c r=A1><v>1</v></c>", "c")]
    [InlineData("<c r=\"A1\"><v><![CDATA[1]]></v></c>", "c")]
    [InlineData("<c r=\"A1\"><v>1<!-- x --></v></c>", "c")]
    [InlineData("<c t=\"inlineStr\"><is><r><t>a</t></r></is></c>", "c")]
    [InlineData("<c t=\"inlineStr\"><is><t/></is></c>", "c")]
    [InlineData("<c t=\"inlineStr\"><is><t>a</t><rPh><t>b</t></rPh></is></c>", "c")]
    [InlineData("<c r=\"A1\"><v>1</v></c >", "c")]
    [InlineData("<col min=\"1\"/>", "col")]
    [InlineData("<cx/>", "cx")]
    public void LeavesOtherShapesToTheGeneralParser(string xml, string name)
    {
        using var reader = AfterRow(xml);
        Assert.False(reader.TryScanCell(out _));
        Assert.True(reader.Next() is XmlToken.Start or XmlToken.Empty);
        Assert.Equal(name, Encoding.UTF8.GetString(reader.Name));
    }

    [Fact]
    public void LeavesTruncatedCellsToTheGeneralParser()
    {
        using var reader = Xml("<row><c r=\"A1\"><v>1</v>");
        reader.Next();
        Assert.False(reader.TryScanCell(out _));
        Assert.Equal(XmlToken.Start, reader.Next());
        Assert.Equal(XmlToken.Start, reader.Next());
        Assert.Equal(XmlToken.Text, reader.Next());
        Assert.Equal(XmlToken.End, reader.Next());
        Assert.Throws<XlsxReadException>(() => reader.Next());
    }

    [Fact]
    public void ScansCellsAcrossBufferRefills()
    {
        var cells = string.Concat(Enumerable.Range(1, 500).Select(i => $"<c r=\"A{i}\" s=\"1\"><v>{i}.25</v></c>"));
        using var reader = AfterRow(cells, buffer: 16);
        for (var i = 1; i <= 500; i++)
        {
            Assert.True(reader.TryScanCell(out var cell), $"cell {i}");
            Assert.Equal($"{i}.25", Bytes(reader, cell.TextStart, cell.TextLength));
            Assert.Equal($"A{i}", Bytes(reader, cell.ReferenceStart, cell.ReferenceLength));
        }

        Assert.False(reader.TryScanCell(out _));
        Assert.Equal(XmlToken.End, reader.Next());
    }

    [Fact]
    public void ScansPlainSharedStringItems()
    {
        using var reader = Xml("<sst><si><t>a</t></si><si><t xml:space=\"preserve\"> b </t></si><si><r><t>c</t></r></si><si><t>d</t><rPh><t>e</t></rPh></si><x:si><x:t>f</x:t></x:si></sst>");
        Assert.Equal(XmlToken.Start, reader.Next());
        Assert.True(reader.TryScanSharedString(out var start, out var length));
        Assert.Equal("a", Bytes(reader, start, length));
        Assert.True(reader.TryScanSharedString(out start, out length));
        Assert.Equal(" b ", Bytes(reader, start, length));
        Assert.False(reader.TryScanSharedString(out _, out _));
        Assert.Equal(XmlToken.Start, reader.Next());
        Assert.Equal("si", Encoding.UTF8.GetString(reader.Name));

        using var mixed = Xml("<sst><si><t>a</t></si><si><r><t>b</t></r><r><t>c</t></r></si><si><t>d</t><rPh><t>e</t></rPh></si><x:si><x:t>f</x:t></x:si><si><t>g</t></si></sst>");
        Assert.Equal(["a", "bc", "d", "f", "g"], SharedStringTableReader.Parse(mixed, long.MaxValue));
    }

    [Fact]
    public void HandlesByteOrderMarks()
    {
        using var utf8 = Xml([0xEF, 0xBB, 0xBF, .. "<a/>"u8]);
        Assert.Equal(XmlToken.Empty, utf8.Next());
        Assert.Equal("a", Encoding.UTF8.GetString(utf8.Name));

        using var utf16 = Xml([0xFF, 0xFE, (byte)'<', 0, (byte)'a', 0, (byte)'/', 0, (byte)'>', 0]);
        Assert.Contains("UTF-16", Assert.Throws<XlsxReadException>(() => utf16.Next()).Message);
    }

    [Fact]
    public void RejectsDoctypeTruncationAndUnbalancedTags()
    {
        using var doctype = Xml("<!DOCTYPE x [<!ENTITY a \"b\">]><x/>");
        Assert.Throws<XlsxReadException>(() => doctype.Next());

        using var truncated = Xml("<a><b>");
        truncated.Next();
        truncated.Next();
        Assert.Throws<XlsxReadException>(() => truncated.Next());

        using var unclosed = Xml("<a attr=\"never closed");
        Assert.Throws<XlsxReadException>(() => unclosed.Next());

        using var unbalanced = Xml("</a>");
        Assert.Contains("Unbalanced", Assert.Throws<XlsxReadException>(() => unbalanced.Next()).Message);

        using var nameless = Xml("< a/>");
        Assert.Contains("no name", Assert.Throws<XlsxReadException>(() => nameless.Next()).Message);
    }

    [Fact]
    public void BoundsTokenSize()
    {
        var huge = new byte[Utf8XmlReader.DefaultMaxToken + 10];
        huge.AsSpan().Fill((byte)'x');
        "<a>"u8.CopyTo(huge);
        using var reader = Xml(huge);
        reader.Next();
        var error = Assert.Throws<XlsxReadException>(() => reader.Next());
        Assert.Equal(XlsxReadErrorKind.LimitExceeded, error.Kind);
    }

    [Fact]
    public void DecodesEntitiesAndExcelEscapes()
    {
        var output = new CharBuffer(4);
        output.Append("pre:");
        Assert.Null(XmlText.Decode(output, "a &lt;&gt;&amp;&quot;&apos; &#65;&#x42; _x000D__x005F_x0041_ _xD83D__xDE00_ snake_case\r\nx"u8, entities: true, excelEscapes: true));
        Assert.Equal("pre:a <>&\"' AB \r_x0041_ \U0001F600 snake_case\nx", output.Span.ToString());

        output.Clear();
        Assert.Null(XmlText.Decode(output, "_x0041_ _xD83D_ alone _xDE00_ _xzz00_ _x41_"u8, entities: true, excelEscapes: false));
        Assert.Equal("_x0041_ _xD83D_ alone _xDE00_ _xzz00_ _x41_", output.Span.ToString());
        output.Clear();
        Assert.Null(XmlText.Decode(output, "_x0041_ _xD83D_ alone _xDE00_ _xzz00_ _x41_ lone\rcr"u8, entities: true, excelEscapes: true));
        Assert.Equal("A _xD83D_ alone _xDE00_ _xzz00_ _x41_ lone\ncr", output.Span.ToString());

        Assert.NotNull(XmlText.Decode(new CharBuffer(), "&bogus;"u8, entities: true, excelEscapes: false));
        Assert.NotNull(XmlText.Decode(new CharBuffer(), "&#xD800;"u8, entities: true, excelEscapes: false));
        Assert.NotNull(XmlText.Decode(new CharBuffer(), "&amp"u8, entities: true, excelEscapes: false));
        var keep = new CharBuffer();
        keep.Append("keep");
        Assert.NotNull(XmlText.Decode(keep, [0xFF, .. "&amp;"u8], entities: true, excelEscapes: false));
        Assert.Equal("keep", keep.Span.ToString());
        Assert.Equal("a&b", XmlText.DecodeAttribute("a&amp;b"u8));
        Assert.Null(XmlText.DecodeAttribute([0xC3]));
    }

    [Theory]
    [InlineData("yyyy-mm-dd", DateKind.Date)]
    [InlineData("d/m/yy", DateKind.Date)]
    [InlineData("mmm yyyy", DateKind.Date)]
    [InlineData("[$-409]mmmm d, yyyy;@", DateKind.Date)]
    [InlineData("yyyy-mm-dd hh:mm:ss", DateKind.DateTime)]
    [InlineData("m/d/yy h:mm AM/PM", DateKind.DateTime)]
    [InlineData("hh:mm", DateKind.Time)]
    [InlineData("h:mm:ss AM/PM", DateKind.Time)]
    [InlineData("mm:ss.0", DateKind.Time)]
    [InlineData("[h]:mm:ss", DateKind.Duration)]
    [InlineData("[mm]:ss", DateKind.Duration)]
    [InlineData("General", null)]
    [InlineData("0.00", null)]
    [InlineData("#,##0.00 \"days\"", null)]
    [InlineData("[Red]#,##0;[Blue]-#,##0", null)]
    [InlineData("_(* #,##0_);_(* (#,##0);_(* \"-\"??_);_(@_)", null)]
    [InlineData("0.00E+00", null)]
    [InlineData("@", null)]
    [InlineData("\"Date:\" @", null)]
    [InlineData("0 \\d", null)]
    [InlineData("General;yyyy", null)]
    public void ClassifiesFormatCodes(string code, DateKind? expected) => Assert.Equal(expected, StyleTable.FormatKind(code));

    [Fact]
    public void ClassifiesBuiltinFormats()
    {
        Assert.Equal(DateKind.Date, StyleTable.BuiltinKind(14));
        Assert.Equal(DateKind.DateTime, StyleTable.BuiltinKind(22));
        Assert.Equal(DateKind.Time, StyleTable.BuiltinKind(21));
        Assert.Equal(DateKind.Duration, StyleTable.BuiltinKind(46));
        Assert.Null(StyleTable.BuiltinKind(4));
    }

    [Fact]
    public void ParsesCellFormats()
    {
        const string xml = """
            <styleSheet xmlns="x"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy&quot;-&quot;mm"/><numFmt numFmtId="165" formatCode="0.0"/></numFmts>
            <fonts><font><sz val="11"/></font></fonts>
            <cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs>
            <cellXfs count="5"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="165"/><xf numFmtId="22"><alignment/></xf><xf numFmtId="46"/></cellXfs></styleSheet>
            """;
        using var reader = Xml(xml);
        var styles = StyleTable.Parse(reader);
        Assert.Equal([null, DateKind.Date, null, DateKind.DateTime, DateKind.Duration], styles.Kinds);
        Assert.Null(styles.GetDateKind(99));
        Assert.Null(styles.GetDateKind(-1));
    }

    [Fact]
    public void ParsesPlainRichAndPhoneticSharedStrings()
    {
        const string xml = """
            <?xml version="1.0"?><sst xmlns="x" count="4" uniqueCount="4">
            <si><t>plain</t></si>
            <si><r><rPr><b/></rPr><t xml:space="preserve">rich </t></r><r><t>text</t></r></si>
            <si><t>東京</t><rPh sb="0" eb="2"><t>トウキョウ</t></rPh><phoneticPr fontId="1"/></si>
            <si/>
            <si><t>a &amp; b_x000A_c</t></si></sst>
            """;
        using var reader = Xml(xml);
        Assert.Equal(["plain", "rich text", "東京", "", "a & b\nc"], SharedStringTableReader.Parse(reader, long.MaxValue));

        using var limited = Xml("<sst><si><t>0123456789</t></si><si><t>0123456789</t></si></sst>");
        Assert.Equal(XlsxReadErrorKind.LimitExceeded, Assert.Throws<XlsxReadException>(() => SharedStringTableReader.Parse(limited, 15)).Kind);
        using var oneItem = Xml("<sst><si><r><t>0123456789</t></r><r><t>0123456789</t></r></si></sst>");
        Assert.Throws<XlsxReadException>(() => SharedStringTableReader.Parse(oneItem, 15));
    }

    [Fact]
    public void ParsesColumnsAndNumbers()
    {
        Assert.Equal(1, WorksheetReader.ParseColumn("A1"u8));
        Assert.Equal(26, WorksheetReader.ParseColumn("z9"u8));
        Assert.Equal(27, WorksheetReader.ParseColumn("AA10"u8));
        Assert.Equal(16_384, WorksheetReader.ParseColumn("XFD1048576"u8));
        Assert.Equal(-1, WorksheetReader.ParseColumn("ABCD1"u8));
        Assert.Equal(-1, WorksheetReader.ParseColumn("12"u8));

        (string Text, double? Value)[] cases =
        [
            ("42", 42), ("-123456789012345", -123_456_789_012_345), ("12345678901234567890", 1.2345678901234567e19), ("3.25E-2", 0.0325),
            ("0.1", 0.1), ("45292.0006944444", 45292.0006944444), ("-.5", -0.5), ("5.", 5), ("0.30000000000000004", 0.30000000000000004),
            ("123456789012.3456", 123456789012.3456), ("-", null), (".", null), ("1.2.3", null), ("NaN", null), ("Infinity", null), ("1e400", null), ("1,5", null),
        ];
        foreach (var (text, expected) in cases)
        {
            var ok = WorksheetReader.TryParseNumber(Encoding.ASCII.GetBytes(text), out var value);
            Assert.True(expected.HasValue == ok, text);
            if (ok)
            {
                Assert.Equal(expected!.Value, value);
            }
        }

        Assert.Equal(int.MaxValue, WorksheetReader.ParseDigits("2147483647"u8));
        Assert.Equal(-1, WorksheetReader.ParseDigits("2147483648"u8));
        Assert.Equal(-1, WorksheetReader.ParseDigits(""u8));
        Assert.Equal(-1, WorksheetReader.ParseDigits("1a"u8));
    }

    [Fact]
    public void ResolvesRelationshipTargets()
    {
        Assert.Equal("", XlsxReader.SourceDirectory("_rels/.rels"));
        Assert.Equal("xl/", XlsxReader.SourceDirectory("xl/_rels/workbook.xml.rels"));
        Assert.Equal("xl/_rels/workbook.xml.rels", XlsxReader.RelationshipsPath("xl/workbook.xml"));
        Assert.Equal("_rels/book.xml.rels", XlsxReader.RelationshipsPath("book.xml"));
        Assert.Equal("xl/worksheets/sheet1.xml", XlsxReader.Resolve("xl/", "worksheets/sheet1.xml"));
        Assert.Equal("xl/worksheets/sheet1.xml", XlsxReader.Resolve("xl/", "/xl/worksheets/sheet1.xml"));
        Assert.Equal("customXml/item1.xml", XlsxReader.Resolve("xl/", "../customXml/item1.xml"));
        Assert.Equal("xl/workbook.xml", XlsxReader.Resolve("", "xl/workbook.xml"));
        Assert.Equal("a.xml", XlsxReader.Resolve("", "../../a.xml"));
    }

    [Fact]
    public void ComputesCrc32()
    {
        Assert.Equal(0xCBF43926u, Crc32.Compute("123456789"u8));
        Assert.Equal(0u, Crc32.Compute([]));
        var data = new byte[1001];
        new Random(7).NextBytes(data);
        var whole = Crc32.Compute(data);
        var split = ~Crc32.Update(Crc32.Update(0xFFFFFFFF, data.AsSpan(0, 13)), data.AsSpan(13));
        Assert.Equal(whole, split);
        Assert.Equal(whole, ~Crc32.UpdateWithTable(Crc32.UpdateWithTable(0xFFFFFFFF, data.AsSpan(0, 5)), data.AsSpan(5)));
        Assert.Equal(0xCBF43926u, ~Crc32.UpdateWithTable(0xFFFFFFFF, "123456789"u8));
    }

    private static byte[] Archive(CompressionLevel level, params (string Name, byte[] Data)[] entries)
    {
        var stream = new MemoryStream();
        using (var zip = new ZipArchive(stream, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var (name, data) in entries)
            {
                using var entry = zip.CreateEntry(name, level).Open();
                entry.Write(data);
            }
        }

        return stream.ToArray();
    }

    private static byte[] ReadAll(byte[] archive, string name)
    {
        var source = new MemoryStream(archive);
        var directory = ZipDirectory.Read(source);
        var entry = directory.Find(name) ?? throw new KeyNotFoundException(name);
        using var stream = ZipDirectory.Open(entry, source);
        var output = new MemoryStream();
        stream.CopyTo(output);
        return output.ToArray();
    }

    private static int CentralHeader(byte[] archive)
    {
        for (var i = archive.Length - 4; i >= 0; i--)
        {
            if (BinaryPrimitives.ReadUInt32LittleEndian(archive.AsSpan(i)) == 0x02014b50)
            {
                return i;
            }
        }

        throw new InvalidOperationException("no central header");
    }

    [Fact]
    public void ReadsZipEntries()
    {
        var big = new byte[800_000];
        new Random(3).NextBytes(big);
        var archive = Archive(CompressionLevel.Fastest, ("a.txt", "hello"u8.ToArray()), ("dir/b.bin", big));
        Assert.Equal("hello"u8.ToArray(), ReadAll(archive, "a.txt"));
        Assert.Equal(big, ReadAll(archive, "/DIR/B.BIN"));
        Assert.Null(ZipDirectory.Read(new MemoryStream(archive)).Find("missing"));
    }

    [Theory]
    [InlineData(CompressionLevel.NoCompression)]
    [InlineData(CompressionLevel.Optimal)]
    public void DetectsCorruptedAndMisdeclaredEntries(CompressionLevel level)
    {
        var data = new byte[10_000];
        new Random(5).NextBytes(data);
        var archive = Archive(level, ("a.txt", data));

        var corrupt = (byte[])archive.Clone();
        corrupt[100] ^= 0x01;
        Assert.Throws<XlsxReadException>(() => ReadAll(corrupt, "a.txt"));

        var understated = (byte[])archive.Clone();
        BinaryPrimitives.WriteUInt32LittleEndian(understated.AsSpan(CentralHeader(understated) + 24), 100);
        Assert.Contains("larger than its declared size", Assert.Throws<XlsxReadException>(() => ReadAll(understated, "a.txt")).Message);

        var overstated = (byte[])archive.Clone();
        BinaryPrimitives.WriteUInt32LittleEndian(overstated.AsSpan(CentralHeader(overstated) + 24), 20_000);
        Assert.Contains("shorter than its declared size", Assert.Throws<XlsxReadException>(() => ReadAll(overstated, "a.txt")).Message);

        var wrongCrc = (byte[])archive.Clone();
        BinaryPrimitives.WriteUInt32LittleEndian(wrongCrc.AsSpan(CentralHeader(wrongCrc) + 16), 12345);
        Assert.Contains("CRC-32", Assert.Throws<XlsxReadException>(() => ReadAll(wrongCrc, "a.txt")).Message);

        var encrypted = (byte[])archive.Clone();
        encrypted[CentralHeader(encrypted) + 8] |= 1;
        Assert.Equal(XlsxReadErrorKind.Unsupported, Assert.Throws<XlsxReadException>(() => ReadAll(encrypted, "a.txt")).Kind);

        var method = (byte[])archive.Clone();
        method[CentralHeader(method) + 10] = 14;
        Assert.Equal(XlsxReadErrorKind.Unsupported, Assert.Throws<XlsxReadException>(() => ReadAll(method, "a.txt")).Kind);
    }

    [Fact]
    public void TruncatedArchivesFailWithTheDocumentedException()
    {
        var archive = Archive(CompressionLevel.Optimal, ("a.txt", "hello world, hello world"u8.ToArray()));
        for (var length = 0; length < archive.Length; length++)
        {
            try
            {
                ReadAll(archive[..length], "a.txt");
            }
            catch (XlsxReadException)
            {
            }
            catch (KeyNotFoundException)
            {
            }
        }
    }

    [Fact]
    public void ConvertsSerialsLikeExcel()
    {
        static string Dt(double serial, DateKind kind, bool is1904 = false) => new ExcelDateTime(serial, kind, is1904).ToString();

        Assert.Equal("1900-01-01", Dt(1, DateKind.Date));
        Assert.Equal("1900-02-28", Dt(59, DateKind.Date));
        Assert.Equal("60", Dt(60, DateKind.Date));
        Assert.Equal("1900-03-01", Dt(61, DateKind.Date));
        Assert.Equal("2024-05-01", Dt(45_413, DateKind.Date));
        Assert.Equal("2024-05-01T18:00:00", Dt(45_413.75, DateKind.DateTime));
        Assert.Equal("2024-05-01T12:00:00.500", Dt(45_413.5 + (0.5 / 86_400), DateKind.DateTime));
        Assert.Equal("06:00:00", Dt(0.25, DateKind.Time));
        Assert.Equal("1.5", Dt(1.5, DateKind.Duration));
        Assert.Equal("-1", Dt(-1, DateKind.Date));
        Assert.Equal("1904-01-01", Dt(0, DateKind.Date, is1904: true));
        Assert.Equal("9999-12-31", Dt(2_958_465, DateKind.Date));
        Assert.Equal("2958466", Dt(2_958_466, DateKind.Date));
        Assert.Equal("1899-12-31T00:00:00", Dt(0, DateKind.DateTime));
        Assert.Equal("NaN", Dt(double.NaN, DateKind.Date));

        Assert.False(new ExcelDateTime(60, DateKind.Date, false).TryGetDateTime(out _));
        Assert.True(new ExcelDateTime(60, DateKind.Date, true).TryGetDateTime(out var leap));
        Assert.Equal(new DateTime(1904, 3, 1), leap);
        Assert.True(new ExcelDateTime(-1.25, DateKind.Duration, false).TryGetTimeSpan(out var negative));
        Assert.Equal(TimeSpan.FromHours(-30), negative);
        Assert.False(new ExcelDateTime(1e9, DateKind.Duration, false).TryGetTimeSpan(out _));
        Assert.Equal(new ExcelDateTime(1, DateKind.Date, false), new ExcelDateTime(1, DateKind.Date, false));
        Assert.NotEqual(new ExcelDateTime(1, DateKind.Date, false), new ExcelDateTime(1, DateKind.Date, true));
    }

    [Fact]
    public void ParsesIsoDatesConsistentlyWithSerials()
    {
        foreach (var (serial, kind) in new[] { (1.0, DateKind.Date), (59.0, DateKind.Date), (61.0, DateKind.Date), (45_413.75, DateKind.DateTime) })
        {
            var text = new ExcelDateTime(serial, kind, false).ToString();
            Assert.True(ExcelDateTime.TryParseIso(text, false, out var parsed, out var parsedKind), text);
            Assert.Equal((serial, kind), (parsed, parsedKind));
        }

        Assert.True(ExcelDateTime.TryParseIso("06:00:00", false, out var time, out var timeKind));
        Assert.Equal((0.25, DateKind.Time), (time, timeKind));
        Assert.True(ExcelDateTime.TryParseIso("1904-01-02Z", true, out var day, out _));
        Assert.Equal(1, day);
        Assert.False(ExcelDateTime.TryParseIso("not a date", false, out _, out _));
        Assert.False(ExcelDateTime.TryParseIso("2024-13-01", false, out _, out _));
        Assert.False(ExcelDateTime.TryParseIso("2024-05", false, out _, out _));
        Assert.False(ExcelDateTime.TryParseIso("08:30:00+02:00", false, out _, out _));
    }

    [Fact]
    public void ExcelValuesCompareAndFormat()
    {
        Assert.Equal("", ExcelValue.Empty.ToString());
        Assert.True(ExcelValue.Empty.IsEmpty);
        Assert.Equal("TRUE", ExcelValue.FromBoolean(true).ToString());
        Assert.Equal("0.1", ExcelValue.FromNumber(0.1).ToString());
        Assert.Equal("#N/A", ExcelValue.FromError("#N/A").ToString());
        Assert.NotEqual(ExcelValue.FromString("#N/A"), ExcelValue.FromError("#N/A"));
        Assert.True(ExcelValue.FromString("a") == ExcelValue.FromString("a"));
        Assert.False(ExcelValue.FromNumber(1).TryGetString(out _));
        Assert.False(ExcelValue.FromString("1").TryGetDouble(out _));
        Assert.False(ExcelValue.FromNumber(1).TryGetDateTime(out _));
        var date = ExcelValue.FromDateTime(new ExcelDateTime(45_413, DateKind.Date, false));
        Assert.True(date.TryGetDateTime(out var value));
        Assert.Equal(new DateTime(2024, 5, 1), value);
        Assert.Equal("2024-05-01", date.ToString());
        Assert.Equal(date.GetHashCode(), ExcelValue.FromDateTime(new ExcelDateTime(45_413, DateKind.Date, false)).GetHashCode());
    }
}
