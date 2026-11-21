using static Hypaxel.Tests.XlsxReaderTests;

namespace Hypaxel.Tests;

public class ReaderLayoutTests
{
    private const string Main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private const string TableRelType = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/table";

    private static XlsxReader Reader(byte[] bytes) => new(new MemoryStream(bytes));

    private static byte[] SheetWithTables(string sheetXml, params string[] tables)
    {
        var rels = string.Concat(tables.Select((_, i) => $"""<Relationship Id="rId{i + 1}" Type="{TableRelType}" Target="../tables/table{i + 1}.xml"/>"""));
        var parts = tables.Select((xml, i) => ($"xl/tables/table{i + 1}.xml", xml))
            .Append(("xl/worksheets/_rels/sheet1.xml.rels", $"""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{rels}</Relationships>"""))
            .ToArray();
        return SingleSheet($"""<worksheet xmlns="{Main}">{sheetXml}</worksheet>""", "", parts);
    }

    [Fact]
    public void ReadsBackTablesWrittenByHypaxel()
    {
        var full = new ExcelTable(1, 1, 3, 2)
        {
            Name = "Orders",
            Columns = ["Product <&>", "Line\nbreak"],
            Style = TableStyle.Dark(3),
            ShowAutoFilter = false,
            ShowRowStripes = false,
            ShowColumnStripes = true,
            HighlightFirstColumn = true,
            HighlightLastColumn = true,
        };
        var bytes = Write(w =>
        {
            var sheet = w.AddWorksheet("Data");
            sheet.AddRow("Product <&>", "Line\nbreak");
            sheet.AddRow("Widget", 3);
            sheet.AddRow("Gadget", 5);
            sheet.AddTable(full);
            sheet.AddTable(new ExcelTable(1, 4, 3, 5) { ShowHeaderRow = false });
            sheet.AddTable(new ExcelTable(5, 1, 6, 1) { Columns = ["Only"], Style = TableStyle.None });
            w.AddWorksheet("Plain").AddRow(1);
        });

        using var reader = Reader(bytes);
        var tables = reader.GetTables("data");
        Assert.Equal(3, tables.Count);

        var first = tables[0];
        Assert.Equal(full.Columns, first.Columns);
        Assert.Equal(full, first with { Columns = full.Columns });

        var second = tables[1];
        Assert.Equal((1, 4, 3, 5), (second.FirstRow, second.FirstColumn, second.LastRow, second.LastColumn));
        Assert.Equal("Table2", second.Name);
        Assert.Equal(["Column1", "Column2"], second.Columns);
        Assert.Equal(TableStyle.Default, second.Style);
        Assert.False(second.ShowHeaderRow);
        Assert.False(second.ShowAutoFilter);
        Assert.True(second.ShowRowStripes);
        Assert.False(second.ShowColumnStripes);

        var third = tables[2];
        Assert.Equal("Table3", third.Name);
        Assert.Equal(TableStyle.None, third.Style);
        Assert.True(third.ShowHeaderRow);
        Assert.True(third.ShowAutoFilter);

        Assert.Empty(reader.GetTables(1));
        Assert.Equal(3, reader.GetTables(0).Count);
        Assert.Throws<KeyNotFoundException>(() => reader.GetTables("Missing"));
        Assert.Throws<ArgumentOutOfRangeException>(() => reader.GetTables(2));
    }

    [Fact]
    public void ReadTablesCanBeWrittenAgain()
    {
        var bytes = Write(w =>
        {
            var sheet = w.AddWorksheet("Data");
            sheet.AddRow("A", "B");
            sheet.AddRow(1, 2);
            sheet.AddTable(new ExcelTable(1, 1, 2, 2) { Name = "T", Columns = ["A", "B"], Style = TableStyle.Light(21) });
        });
        using var reader = Reader(bytes);
        var table = Assert.Single(reader.GetTables(0));

        var copy = Write(w =>
        {
            var sheet = w.AddWorksheet("Copy");
            sheet.AddRow("A", "B");
            sheet.AddRow(1, 2);
            sheet.AddTable(table);
        });
        using var copied = Reader(copy);
        var again = Assert.Single(copied.GetTables(0));
        Assert.Equal(table.Columns, again.Columns);
        Assert.Equal(table, again with { Columns = table.Columns });
    }

    [Fact]
    public void ReadsBackRowPropertiesAndSheetLayout()
    {
        var bytes = Write(w =>
        {
            var options = new WorksheetOptions { RowOutlineLevels = 2, OutlineSummaryAbove = true }
                .SetColumnWidths(12.5, 30)
                .SetColumn(4, ColumnDefinition.WithWidth(7));
            var sheet = w.AddWorksheet("Grouped", options);
            sheet.BeginRow(new RowOptions { Collapsed = true, Height = 24.5 });
            sheet.AddRow("North", 300);
            sheet.BeginRow(new RowOptions { OutlineLevel = 1, Hidden = true });
            sheet.AddRow("Store A", 120);
            sheet.BeginRow(new RowOptions { OutlineLevel = 2 });
            sheet.AddRow("Till 1", 60);
            sheet.BeginRow(new RowOptions { OutlineLevel = 1, Hidden = true, Height = 0 });
            sheet.AddRow();
            sheet.AddRow("Total", 300);
            w.AddWorksheet("Plain").AddRow("x");
        });

        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet("Grouped");
        Assert.True(rows.OutlineSummaryAbove);
        Assert.Equal(2, rows.RowOutlineLevels);
        Assert.Equal(
            [new ColumnWidth(1, 1, 12.5), new ColumnWidth(2, 2, 30), new ColumnWidth(4, 4, 7)],
            rows.ColumnWidths);
        Assert.Equal(30, rows.GetColumnWidth(2));
        Assert.Null(rows.GetColumnWidth(3));
        Assert.Null(rows.GetColumnWidth(0));

        var seen = rows.Select(r => (r.Number, r.OutlineLevel, r.Hidden, r.Collapsed, r.Height)).ToList();
        Assert.Equal(
            [
                (1, 0, false, true, 24.5),
                (2, 1, true, false, null),
                (3, 2, false, false, null),
                (5, 0, false, false, (double?)null),
            ],
            seen);
        Assert.Equal(3, rows.ColumnWidths.Count);

        using var plain = reader.OpenWorksheet("Plain");
        Assert.True(plain.Read());
        Assert.False(plain.OutlineSummaryAbove);
        Assert.Equal(0, plain.RowOutlineLevels);
        Assert.Empty(plain.ColumnWidths);
        Assert.Equal(0, plain.Row.OutlineLevel);
        Assert.False(plain.Row.Hidden);
        Assert.Null(plain.Row.Height);
    }

    [Fact]
    public void ReadsBackAutoFittedColumnWidths()
    {
        var bytes = Write(w =>
        {
            var sheet = w.AddWorksheet("Fit", new WorksheetOptions().AutoFitColumns().SetColumn(2, ColumnDefinition.WithWidth(40)));
            sheet.AddRow("a rather long piece of text", "x", "short");
            sheet.AddRow("b", "y", "z");
        });

        using var reader = Reader(bytes);
        using var rows = reader.OpenWorksheet(0);
        var widths = rows.ColumnWidths;
        Assert.Equal([1, 2, 3], widths.Select(c => c.FirstColumn));
        Assert.Equal(40, rows.GetColumnWidth(2));
        Assert.True(rows.GetColumnWidth(1) > rows.GetColumnWidth(3));
        Assert.True(rows.GetColumnWidth(3) > 0);
        Assert.Equal(2, rows.Count());
    }

    [Fact]
    public void ReadsLayoutWrittenByOtherProducers()
    {
        var sheet = $"""
            <worksheet xmlns="{Main}" xmlns:x14ac="http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac">
              <sheetPr><tabColor rgb="FFFF0000"/><outlinePr summaryBelow="false" summaryRight="0"/><pageSetUpPr fitToPage="1"/></sheetPr>
              <dimension ref="A1:B4"/>
              <sheetViews><sheetView workbookViewId="0"><selection activeCell="A1"/></sheetView></sheetViews>
              <sheetFormatPr defaultRowHeight="15" outlineLevelRow="9" x14ac:dyDescent="0.25"/>
              <cols>
                <col min="1" max="16384" width="9.5" style="1"/>
                <col min="2" max="3" width=" 20 " customWidth="1"/>
                <col min="4" max="4" hidden="1"/>
                <col min="0" max="2" width="5"/>
                <col min="7" max="6" width="5"/>
                <col min="5" max="99999" width="1e1"/>
                <col min="6" max="6" width="-1"/>
                <col min="6" max="6" width="NaN"/>
              </cols>
              <sheetData>
                <row r="1" spans="1:2" ht="18.75" x14ac:dyDescent="0.3"><c r="A1"><v>1</v></c></row>
                <row r="2" ht="30" customHeight="1" outlineLevel="12" hidden="true" collapsed="1"><c r="A2"><v>2</v></c></row>
                <row r="3" ht="tall" outlineLevel="x" hidden="0" collapsed="false"><c r="A3"><v>3</v></c></row>
                <row r="4" outlineLevel="3" hidden="1"/>
                <row r="5" outlineLevel=" 4 "><c r="A5"><v>5</v></c></row>
              </sheetData>
            </worksheet>
            """;
        using var reader = Reader(SingleSheet(sheet));
        using var rows = reader.OpenWorksheet(0);
        Assert.True(rows.OutlineSummaryAbove);
        Assert.Equal(7, rows.RowOutlineLevels);
        Assert.Equal(
            [new ColumnWidth(1, 16384, 9.5), new ColumnWidth(2, 3, 20), new ColumnWidth(5, 16384, 10)],
            rows.ColumnWidths);
        Assert.Equal(9.5, rows.GetColumnWidth(3));
        Assert.Equal(9.5, rows.GetColumnWidth(16384));
        Assert.Null(rows.GetColumnWidth(16385));

        Assert.Equal(
            [
                (1, 0, false, false, 18.75),
                (2, 7, true, true, 30),
                (3, 0, false, false, null),
                (5, 4, false, false, (double?)null),
            ],
            rows.Select(r => (r.Number, r.OutlineLevel, r.Hidden, r.Collapsed, r.Height)).ToList());
    }

    [Fact]
    public void LayoutIsReadLazilyAndSurvivesTheEndOfTheSheet()
    {
        var cols = $"""<worksheet xmlns="{Main}"><cols><col min="1" max="1" width="3"/></cols><sheetData/></worksheet>""";
        using (var reader = Reader(SingleSheet(cols)))
        {
            using var rows = reader.OpenWorksheet(0);
            Assert.False(rows.Read());
            Assert.Equal(3, rows.GetColumnWidth(1));
        }

        using (var reader = Reader(SingleSheet($"""<worksheet xmlns="{Main}"><cols><col min="1" max="1" width="3"/>""")))
        {
            using var rows = reader.OpenWorksheet(0);
            var e = Assert.Throws<XlsxReadException>(() => rows.ColumnWidths);
            Assert.Equal(XlsxReadErrorKind.Malformed, e.Kind);
        }

        var many = string.Concat(Enumerable.Repeat("""<col min="1" max="1" width="3"/>""", 16385));
        using (var reader = Reader(SingleSheet($"""<worksheet xmlns="{Main}"><cols>{many}</cols><sheetData/></worksheet>""")))
        {
            using var rows = reader.OpenWorksheet(0);
            var e = Assert.Throws<XlsxReadException>(() => rows.Read());
            Assert.Equal(XlsxReadErrorKind.Malformed, e.Kind);
            Assert.Contains("16384 column ranges", e.Message);
        }
    }

    [Fact]
    public void ReadsTablesWrittenByOtherProducers()
    {
        var excel = $"""
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <x:table xmlns:x="{Main}" id="4" name="Internal" displayName="Sales_2024" ref="$B$2:$D$10" totalsRowCount="1">
              <x:autoFilter ref="B2:D9"><x:filterColumn colId="0"><x:filters><x:filter val="a"/></x:filters></x:filterColumn></x:autoFilter>
              <x:sortState ref="B3:D9"><x:sortCondition ref="B3:B9"/></x:sortState>
              <x:tableColumns count="3">
                <x:tableColumn id="1" name="Region &amp; _x000A_Country" totalsRowLabel="Total"/>
                <x:tableColumn id="2" name="Net"><x:calculatedColumnFormula>1+1</x:calculatedColumnFormula></x:tableColumn>
                <x:tableColumn id="3" name="Größe _x005F_x0041_"/>
              </x:tableColumns>
              <x:tableStyleInfo name="My Custom Style" showFirstColumn="true" showLastColumn="0" showRowStripes="1" showColumnStripes="false"/>
            </x:table>
            """.TrimStart();
        var bare = $"""<table xmlns="{Main}" id="2" name="OnlyName" ref="a1" headerRowCount=" 0 "><tableColumns count="1"><tableColumn id="1"/></tableColumns></table>""";
        var styled = $"""<table xmlns="{Main}" id="3" ref="F1:G2" headerRowCount="1"><tableColumns/><tableStyleInfo name="TableStyleMedium28"/></table>""";

        using var reader = Reader(SheetWithTables("<sheetData/>", excel, bare, styled));
        var tables = reader.GetTables(0);
        Assert.Equal(3, tables.Count);

        var first = tables[0];
        Assert.Equal("Sales_2024", first.Name);
        Assert.Equal((2, 2, 10, 4), (first.FirstRow, first.FirstColumn, first.LastRow, first.LastColumn));
        Assert.Equal(["Region & \nCountry", "Net", "Größe _x0041_"], first.Columns);
        Assert.Equal(TableStyle.None, first.Style);
        Assert.True(first.ShowHeaderRow);
        Assert.True(first.ShowAutoFilter);
        Assert.True(first.ShowRowStripes);
        Assert.False(first.ShowColumnStripes);
        Assert.True(first.HighlightFirstColumn);
        Assert.False(first.HighlightLastColumn);

        var second = tables[1];
        Assert.Equal("OnlyName", second.Name);
        Assert.Equal((1, 1, 1, 1), (second.FirstRow, second.FirstColumn, second.LastRow, second.LastColumn));
        Assert.Equal([""], second.Columns);
        Assert.Equal(TableStyle.None, second.Style);
        Assert.False(second.ShowHeaderRow);
        Assert.False(second.ShowAutoFilter);
        Assert.False(second.ShowRowStripes);

        var third = tables[2];
        Assert.Null(third.Name);
        Assert.Empty(third.Columns!);
        Assert.Equal(TableStyle.Medium(28), third.Style);
        Assert.True(third.ShowHeaderRow);
        Assert.False(third.ShowRowStripes);
    }

    [Theory]
    [InlineData("TableStyleLight1", "TableStyleLight1")]
    [InlineData("TableStyleLight21", "TableStyleLight21")]
    [InlineData(" TableStyleDark11 ", "TableStyleDark11")]
    [InlineData("TableStyleDark12", null)]
    [InlineData("TableStyleLight0", null)]
    [InlineData("TableStyleMedium", null)]
    [InlineData("TableStyleMedium2x", null)]
    [InlineData("TableStyleMedium999", null)]
    [InlineData("PivotStyleLight16", null)]
    [InlineData("tablestylemedium2", null)]
    [InlineData("", null)]
    public void MapsOnlyBuiltInTableStyles(string name, string? expected)
    {
        var table = $"""<table xmlns="{Main}" ref="A1:A2"><tableStyleInfo name="{name}"/></table>""";
        using var reader = Reader(SheetWithTables("<sheetData/>", table));
        Assert.Equal(expected, Assert.Single(reader.GetTables(0)).Style.Name);
    }

    [Theory]
    [InlineData("""<table xmlns="M"/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref=""/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref="C3:A1"/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref="A1:XFE2"/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref="A0:B2"/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref="A1:B1048577"/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref="A1:B2:C3"/>""", "no valid cell range")]
    [InlineData("""<table xmlns="M" ref="1A:B2"/>""", "no valid cell range")]
    [InlineData("""<worksheet xmlns="M"><table ref="A1:B2"/></worksheet>""", "not a table")]
    [InlineData("", "not a table")]
    [InlineData("""<table xmlns="M" ref="A1:B2"><tableColumns>""", "end of XML")]
    [InlineData("""<!DOCTYPE table [<!ENTITY a "b">]><table xmlns="M" ref="A1:B2"/>""", "document type")]
    public void RejectsMalformedTableParts(string xml, string message)
    {
        using var reader = Reader(SheetWithTables("<sheetData/>", xml.Replace("\"M\"", $"\"{Main}\"")));
        var e = Assert.Throws<XlsxReadException>(() => reader.GetTables(0));
        Assert.Equal(XlsxReadErrorKind.Malformed, e.Kind);
        Assert.Equal("xl/tables/table1.xml", e.Part);
        Assert.Contains(message, e.Message, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void EnforcesTableLimits()
    {
        var columns = string.Concat(Enumerable.Range(1, 16385).Select(i => $"""<tableColumn id="{i}" name="c{i}"/>"""));
        var wide = $"""<table xmlns="{Main}" ref="A1:B2"><tableColumns>{columns}</tableColumns></table>""";
        using (var reader = Reader(SheetWithTables("<sheetData/>", wide)))
        {
            var e = Assert.Throws<XlsxReadException>(() => reader.GetTables(0));
            Assert.Equal(XlsxReadErrorKind.Malformed, e.Kind);
            Assert.Contains("16384 columns", e.Message);
        }

        var rel = $"""<Relationship Id="rId1" Type="{TableRelType}" Target="../tables/table1.xml"/>""";
        var rels = $"""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{string.Concat(Enumerable.Repeat(rel, 4097))}</Relationships>""";
        var bytes = SingleSheet(
            $"""<worksheet xmlns="{Main}"><sheetData/></worksheet>""",
            "",
            ("xl/worksheets/_rels/sheet1.xml.rels", rels),
            ("xl/tables/table1.xml", $"""<table xmlns="{Main}" ref="A1:B2"/>"""));
        using (var reader = Reader(bytes))
        {
            var e = Assert.Throws<XlsxReadException>(() => reader.GetTables(0));
            Assert.Equal(XlsxReadErrorKind.LimitExceeded, e.Kind);
        }
    }

    [Fact]
    public void IgnoresOtherRelationshipsAndReportsMissingTableParts()
    {
        const string sheet = $"""<worksheet xmlns="{Main}"><sheetData><row r="1"><c r="A1"><v>1</v></c></row></sheetData></worksheet>""";
        var rels = $"""
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/table" TargetMode="External"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
              <Relationship Id="rId3" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/table" Target="/xl/tables/gone.xml"/>
            </Relationships>
            """;
        using var reader = Reader(SingleSheet(sheet, "", ("xl/worksheets/_rels/sheet1.xml.rels", rels)));
        var e = Assert.Throws<XlsxReadException>(() => reader.GetTables(0));
        Assert.Equal(XlsxReadErrorKind.Malformed, e.Kind);
        Assert.Equal("xl/tables/gone.xml", e.Part);

        using var rows = reader.OpenWorksheet(0);
        Assert.True(rows.Read());

        using var none = Reader(SingleSheet(sheet));
        Assert.Empty(none.GetTables("S"));

        var disposed = Reader(SingleSheet(sheet));
        disposed.Dispose();
        Assert.Throws<ObjectDisposedException>(() => disposed.GetTables(0));
    }
}
