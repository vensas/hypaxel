package de.vensas.hypaxel;

import static de.vensas.hypaxel.ReaderTest.singleSheet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

/** Reading tables, row properties, column widths and outline settings. */
class ReaderLayoutTest {
    static final String NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    static final String TABLE_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/table";

    static String sheetRels(String... targets) {
        StringBuilder rels = new StringBuilder("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < targets.length; i++) {
            rels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"").append(TABLE_TYPE).append("\" Target=\"")
                    .append(targets[i]).append("\"/>");
        }
        return rels.append("</Relationships>").toString();
    }

    static byte[] withTable(String tableXml) {
        return singleSheet("<worksheet " + NS + "><sheetData/></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels", sheetRels("../tables/table1.xml"), "xl/tables/table1.xml", tableXml);
    }

    static XlsxException tableError(String tableXml) throws IOException {
        try (XlsxReader reader = XlsxReader.open(withTable(tableXml))) {
            return assertThrows(XlsxException.class, () -> reader.tables(0));
        }
    }

    @Test
    void readsBackTablesWrittenByTheWriter() throws IOException {
        ExcelTable full = ExcelTable.builder(1, 1, 3, 2).name("Orders").columns("Product", "Units\nsold")
                .style(TableStyle.dark(3)).autoFilter(false).rowStripes(false).columnStripes(true)
                .highlightFirstColumn(true).highlightLastColumn(true).build();
        ExcelTable defaults = ExcelTable.builder(1, 4, 3, 5).columns("A & B", "<C>").build();
        ExcelTable headerless = ExcelTable.builder(5, 1, 6, 2).headerRow(false).style(TableStyle.NONE).build();
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet s = writer.addWorksheet("Data");
            s.addRow("Product", "Units\nsold", null, "A & B", "<C>");
            s.addRow("Widget", 3, null, 1, 2);
            s.addRow("Gadget", 5, null, 3, 4);
            s.addRow();
            s.addRow(1, 2);
            s.addRow(3, 4);
            s.addTable(full).addTable(defaults).addTable(headerless);
            writer.addWorksheet("Plain").addRow(1);
        });

        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            List<ExcelTable> tables = reader.tables("data");
            assertEquals(3, tables.size());
            assertEquals(full, tables.get(0));
            assertEquals(full.hashCode(), tables.get(0).hashCode());
            assertEquals(ExcelTable.builder(1, 4, 3, 5).name("Table2").columns("A & B", "<C>").build(), tables.get(1));
            assertEquals(ExcelTable.builder(5, 1, 6, 2).name("Table3").columns("Column1", "Column2").headerRow(false)
                    .autoFilter(false).style(TableStyle.NONE).build(), tables.get(2));
            assertEquals(tables, reader.tables(0));
            assertTrue(tables.get(0).toString().startsWith("Orders A1:B3 "), tables.get(0).toString());
            assertThrows(UnsupportedOperationException.class, () -> tables.add(full));

            assertEquals(List.of(), reader.tables("Plain"));
            assertEquals(XlsxException.Kind.SHEET_NOT_FOUND, assertThrows(XlsxException.class, () -> reader.tables("Nope")).kind());
            assertEquals(XlsxException.Kind.SHEET_NOT_FOUND, assertThrows(XlsxException.class, () -> reader.tables(2)).kind());
        }

        byte[] copy = Xlsx.write(writer -> {
            Worksheet s = writer.addWorksheet("Copy");
            s.addRow("Product", "Units\nsold");
            s.addRow("Widget", 3);
            s.addRow("Gadget", 5);
            try (XlsxReader reader = XlsxReader.open(xlsx)) {
                s.addTable(reader.tables(0).get(0));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        try (XlsxReader reader = XlsxReader.open(copy)) {
            assertEquals(List.of(full), reader.tables(0));
        }
    }

    @Test
    void readsTablesFromOtherProducers() throws IOException {
        String custom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><x:table xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "id=\"7\" name=\"Internal\" displayName=\"Sales_2024\" ref=\"$B$2:$D$10\" totalsRowCount=\"1\">"
                + "<x:autoFilter ref=\"B2:D9\"><x:filterColumn colId=\"0\"><x:filters><x:filter val=\"a\"/></x:filters></x:filterColumn></x:autoFilter>"
                + "<x:tableColumns count=\"3\"><x:tableColumn id=\"1\" name=\"Line_x000A_break\"/>"
                + "<x:tableColumn id=\"2\" name=\"R&amp;D\"><x:calculatedColumnFormula>A1</x:calculatedColumnFormula></x:tableColumn>"
                + "<x:tableColumn id=\"3\" name=\"Total\" totalsRowFunction=\"sum\"/></x:tableColumns>"
                + "<x:tableStyleInfo name=\"My Custom Style\" showFirstColumn=\"true\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"false\"/></x:table>";
        String bare = "<table " + NS + " id=\"2\" name=\"OnlyName\" ref=\"f5\" headerRowCount=\"0\"><tableColumns count=\"1\"><tableColumn id=\"1\"/></tableColumns></table>";
        String styled = "<table " + NS + " id=\"3\" ref=\"A1:A2\"><tableStyleInfo name=\"TableStyleLight21\"/><extLst><autoFilter/></extLst></table>";
        byte[] xlsx = singleSheet("<worksheet " + NS + "><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c></row></sheetData></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels",
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId9\" Type=\"" + TABLE_TYPE + "\" Target=\"../tables/custom.xml\"/>"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"http://example.com\" TargetMode=\"External\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://purl.oclc.org/ooxml/officeDocument/relationships/table\" Target=\"/xl/tables/bare.xml\"/>"
                        + "<Relationship Id=\"rId3\" Type=\"" + TABLE_TYPE + "\" Target=\"../tables/styled.xml\"/></Relationships>",
                "xl/tables/custom.xml", custom, "xl/tables/bare.xml", bare, "xl/tables/styled.xml", styled);

        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            List<ExcelTable> tables = reader.tables("S");
            assertEquals(3, tables.size());
            assertEquals(ExcelTable.builder(2, 2, 10, 4).name("Sales_2024").columns("Line\nbreak", "R&D", "Total").style(TableStyle.NONE)
                    .highlightFirstColumn(true).totalsRow(true).build(), tables.get(1));
            assertTrue(tables.get(1).toString().endsWith(" totalsRow=true"), tables.get(1).toString());
            assertEquals(ExcelTable.builder(5, 6, 5, 6).name("OnlyName").columns("").style(TableStyle.NONE).headerRow(false)
                    .autoFilter(false).rowStripes(false).build(), tables.get(2));
            assertFalse(tables.get(2).totalsRow());
            XlsxException rejected = assertThrows(XlsxException.class,
                    () -> Xlsx.write(writer -> writer.addWorksheet("S").addTable(tables.get(1))));
            assertEquals(XlsxException.Kind.INVALID_ARGUMENT, rejected.kind());
            assertTrue(rejected.getMessage().contains("totals row"), rejected.getMessage());
            ExcelTable third = tables.get(0);
            assertNull(third.name());
            assertEquals(TableStyle.light(21), third.style());
            assertFalse(third.autoFilter());
            assertFalse(third.rowStripes());
            assertTrue(third.headerRow());
            assertEquals(List.of(), third.columns());
            assertTrue(third.toString().startsWith("(unnamed) A1:A2 "));
        }
    }

    @Test
    void mapsOnlyBuiltInTableStyles() throws IOException {
        String[][] cases = {{"TableStyleMedium28", "TableStyleMedium28"}, {"TableStyleDark11", "TableStyleDark11"},
                {" TableStyleLight1 ", "TableStyleLight1"}, {"TableStyleDark12", null}, {"TableStyleMedium", null},
                {"TableStyleLight0", null}, {"TableStyleLight1x", null}, {"TableStyleFancy3", null}, {"PivotStyleLight16", null}, {"", null}};
        for (String[] c : cases) {
            byte[] xlsx = withTable("<table " + NS + " ref=\"A1:B2\"><tableStyleInfo name=\"" + c[0] + "\"/></table>");
            try (XlsxReader reader = XlsxReader.open(xlsx)) {
                assertEquals(c[1], reader.tables(0).get(0).style().name(), c[0]);
            }
        }
    }

    @Test
    void rejectsMalformedTableParts() throws IOException {
        XlsxException e = tableError("<worksheet " + NS + "/>");
        assertEquals(XlsxException.Kind.MALFORMED, e.kind());
        assertEquals("xl/tables/table1.xml", e.part());
        assertTrue(e.getMessage().contains("not a table"), e.getMessage());
        assertEquals(XlsxException.Kind.MALFORMED, tableError("").kind());

        for (String ref : new String[] {"", "A", "1", "A0", "XFE1", "A1048577", "C3:A1", "A1:B", "A1:B2:C3", "1A", "ABCD1", "A12345678"}) {
            e = tableError("<table " + NS + " ref=\"" + ref + "\"/>");
            assertEquals(XlsxException.Kind.MALFORMED, e.kind(), ref);
            assertTrue(e.getMessage().contains("no valid cell range"), e.getMessage());
        }
        assertTrue(tableError("<table " + NS + " name=\"T\"/>").getMessage().contains("no valid cell range"));

        StringBuilder wide = new StringBuilder("<table " + NS + " ref=\"A1:B2\"><tableColumns>");
        for (int i = 0; i <= 16384; i++) {
            wide.append("<tableColumn name=\"c\"/>");
        }
        assertTrue(tableError(wide.append("</tableColumns></table>").toString()).getMessage().contains("more than 16384 columns"));

        assertEquals(XlsxException.Kind.MALFORMED, tableError("<!DOCTYPE table><table " + NS + " ref=\"A1\"/>").kind());
        assertEquals(XlsxException.Kind.MALFORMED, tableError("<table " + NS + " ref=\"A1\"><tableColumns>").kind());

        byte[] missing = singleSheet("<worksheet " + NS + "><sheetData/></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels", sheetRels("../tables/gone.xml"));
        try (XlsxReader reader = XlsxReader.open(missing)) {
            e = assertThrows(XlsxException.class, () -> reader.tables(0));
            assertEquals(XlsxException.Kind.MALFORMED, e.kind());
            assertEquals("xl/tables/gone.xml", e.part());
            try (SheetReader rows = reader.worksheet(0)) {
                assertNull(rows.nextRow());
            }
        }
    }

    @Test
    void limitsTheNumberOfTablesPerSheet() throws IOException {
        String[] targets = new String[4097];
        java.util.Arrays.fill(targets, "../tables/table1.xml");
        byte[] xlsx = singleSheet("<worksheet " + NS + "><sheetData/></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels", sheetRels(targets), "xl/tables/table1.xml", "<table " + NS + " ref=\"A1\"/>");
        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            XlsxException e = assertThrows(XlsxException.class, () -> reader.tables(0));
            assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, e.kind());
        }
    }

    @Test
    void boundsTheMemoryOfASheetsTables() throws IOException {
        String table = "<table " + NS + " name=\"T\" ref=\"A1:B2\"><tableColumns><tableColumn name=\"a\"/><tableColumn name=\"b\"/></tableColumns></table>";
        byte[] repeated = singleSheet("<worksheet " + NS + "><sheetData/></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels", sheetRels("../tables/table1.xml", "../tables/table1.xml", "../tables/table1.xml"),
                "xl/tables/table1.xml", table);
        try (XlsxReader reader = XlsxReader.open(repeated)) {
            assertEquals(1, reader.tables(0).size());
        }

        String column = "<tableColumn name=\"" + "n".repeat(4200) + "\"/>";
        XlsxException e = tableError("<table " + NS + " name=\"T\" ref=\"A1:B2\"><tableColumns>" + column.repeat(16384) + "</tableColumns></table>");
        assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, e.kind());
        assertTrue(e.getMessage().contains("too many names"), e.getMessage());
    }

    @Test
    void boundsTheRelationshipsOfAPart() throws IOException {
        String table = "<table " + NS + " name=\"T\" ref=\"A1:B2\"><tableColumns><tableColumn name=\"a\"/><tableColumn name=\"b\"/></tableColumns></table>";
        String open = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">";
        String external = "<Relationship Id=\"x\" Type=\"t/hyperlink\" Target=\"https://example.com\" TargetMode=\"External\"/>";
        String tableRel = "<Relationship Id=\"rId1\" Type=\"" + TABLE_TYPE + "\" Target=\"../tables/table1.xml\"/>";
        byte[] links = singleSheet("<worksheet " + NS + "><sheetData/></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels", open + external.repeat(70_000) + tableRel + "</Relationships>",
                "xl/tables/table1.xml", table);
        try (XlsxReader reader = XlsxReader.open(links)) {
            assertEquals(1, reader.tables(0).size(), "external links do not count");
        }

        String internal = "<Relationship Id=\"x\" Type=\"t/other\" Target=\"other.xml\"/>";
        byte[] flood = singleSheet("<worksheet " + NS + "><sheetData/></worksheet>", "",
                "xl/worksheets/_rels/sheet1.xml.rels", open + internal.repeat(65_537) + "</Relationships>");
        try (XlsxReader reader = XlsxReader.open(flood)) {
            XlsxException e = assertThrows(XlsxException.class, () -> reader.tables(0));
            assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, e.kind());
            assertTrue(e.getMessage().contains("more than 65536 relationships"), e.getMessage());
            assertTrue(e.getMessage().contains("xl/worksheets/_rels/sheet1.xml.rels"), e.getMessage());
        }
    }

    @Test
    void parsesIntegerAttributesAsUnsigned32BitDigits() throws IOException {
        String sheet = "<worksheet " + NS + "><sheetFormatPr outlineLevelRow=\"4294967295\"/><cols>"
                + "<col min=\"1\" max=\"4294967295\" width=\"9\"/><col min=\"+2\" max=\"3\" width=\"5\"/>"
                + "<col min=\"2\" max=\"4294967296\" width=\"5\"/></cols><sheetData>"
                + "<row r=\"1\" outlineLevel=\"4294967295\"><c><v>1</v></c></row>"
                + "<row r=\"2\" outlineLevel=\"+3\"><c><v>2</v></c></row></sheetData></worksheet>";
        try (XlsxReader reader = XlsxReader.open(singleSheet(sheet, "")); SheetReader rows = reader.worksheet(0)) {
            assertEquals(List.of(new ColumnWidth(1, 16384, 9, false)), rows.columnWidths());
            assertEquals(7, rows.rowOutlineLevels());
            assertEquals(7, rows.nextRow().outlineLevel());
            assertEquals(0, rows.nextRow().outlineLevel());
        }

        String totals = "<table " + NS + " name=\"T\" ref=\"A1:A3\" totalsRowCount=\"%s\"/>";
        for (String[] c : new String[][] {{"3000000000", "true"}, {"+1", "false"}, {"0", "false"}, {" 1 ", "true"}, {"", "false"}}) {
            try (XlsxReader reader = XlsxReader.open(withTable(String.format(totals, c[0])))) {
                assertEquals(Boolean.parseBoolean(c[1]), reader.tables(0).get(0).totalsRow(), c[0]);
            }
        }
    }

    @Test
    void sheetsWithoutAPartHaveNoTablesAndAClosedReaderRejectsTheCall() throws IOException {
        String workbook = "<workbook " + NS + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Lost\" sheetId=\"1\" r:id=\"rId5\"/></sheets></workbook>";
        byte[] xlsx = ReaderTest.pkg("_rels/.rels", ReaderTest.ROOT_RELS, "xl/workbook.xml", workbook);
        XlsxReader reader = XlsxReader.open(xlsx);
        assertEquals(List.of(), reader.tables(0));
        reader.close();
        assertEquals(XlsxException.Kind.INVALID_STATE, assertThrows(XlsxException.class, () -> reader.tables(0)).kind());
    }

    @Test
    void readsBackRowPropertiesAndSheetLayout() throws IOException {
        byte[] xlsx = Xlsx.write(writer -> {
            SheetOptions options = SheetOptions.builder().rowOutlineLevels(2).outlineSummaryAbove()
                    .column(1, Column.ofWidth(30)).column(3, Column.ofWidth(12.5).withHidden(true)).build();
            Worksheet s = writer.addWorksheet("Grouped", options);
            s.beginRow(RowOptions.builder().collapsed().height(24).build()).addRow("Total", 300);
            s.beginRow(RowOptions.builder().outlineLevel(1).hidden().build()).addRow("Store A", 120);
            s.beginRow(RowOptions.builder().outlineLevel(2).hidden().height(18.75).build()).addRow("Detail", 60);
            s.addRow("Plain", 1);
            Worksheet fitted = writer.addWorksheet("Fitted", SheetOptions.builder().autoFitColumns().column(2, Column.ofWidth(40)).build());
            fitted.addRow("a fairly long piece of text", "x", 1);
            fitted.addRow("short", "y", 2);
            writer.addWorksheet("Plain").addRow(1);
        });

        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            try (SheetReader rows = reader.worksheet("Grouped")) {
                assertTrue(rows.outlineSummaryAbove());
                assertEquals(2, rows.rowOutlineLevels());
                assertEquals(List.of(new ColumnWidth(1, 1, 30, false), new ColumnWidth(3, 3, 12.5, true)), rows.columnWidths());
                assertFalse(rows.columnHidden(1));
                assertFalse(rows.columnHidden(2));
                assertTrue(rows.columnHidden(3));
                assertEquals(OptionalDouble.of(30), rows.columnWidth(1));
                assertEquals(OptionalDouble.empty(), rows.columnWidth(2));
                assertEquals(OptionalDouble.of(12.5), rows.columnWidth(3));
                assertThrows(UnsupportedOperationException.class, () -> rows.columnWidths().clear());

                Row row = rows.nextRow();
                assertEquals("Total", row.getString(1));
                assertEquals(0, row.outlineLevel());
                assertFalse(row.hidden());
                assertTrue(row.collapsed());
                assertEquals(OptionalDouble.of(24), row.height());
                Row kept = row.copy();

                row = rows.nextRow();
                assertEquals(1, row.outlineLevel());
                assertTrue(row.hidden());
                assertFalse(row.collapsed());
                assertEquals(OptionalDouble.empty(), row.height());

                row = rows.nextRow();
                assertEquals(2, row.outlineLevel());
                assertTrue(row.hidden());
                assertEquals(OptionalDouble.of(18.75), row.height());

                row = rows.nextRow();
                assertEquals(0, row.outlineLevel());
                assertFalse(row.hidden());
                assertFalse(row.collapsed());
                assertEquals(OptionalDouble.empty(), row.height());
                assertNull(rows.nextRow());

                assertTrue(kept.collapsed());
                assertEquals(OptionalDouble.of(24), kept.height());
                assertEquals(0, kept.outlineLevel());
                assertFalse(kept.hidden());
                assertEquals(2, rows.columnWidths().size());
            }

            try (SheetReader rows = reader.worksheet("Fitted")) {
                assertEquals("a fairly long piece of text", rows.nextRow().getString(1));
                List<ColumnWidth> widths = rows.columnWidths();
                assertEquals(3, widths.size());
                assertEquals(OptionalDouble.of(40), rows.columnWidth(2));
                assertTrue(rows.columnWidth(1).getAsDouble() > rows.columnWidth(3).getAsDouble());
                assertTrue(rows.columnWidth(1).getAsDouble() > 20);
                assertFalse(rows.outlineSummaryAbove());
                assertEquals(0, rows.rowOutlineLevels());
            }

            try (SheetReader rows = reader.worksheet("Plain")) {
                assertEquals(List.of(), rows.columnWidths());
                assertFalse(rows.columnHidden(1));
                assertEquals(OptionalDouble.empty(), rows.columnWidth(1));
                assertFalse(rows.outlineSummaryAbove());
                assertEquals(0, rows.rowOutlineLevels());
                assertEquals(1, rows.nextRow().number());
            }
        }
    }

    @Test
    void readsLayoutFromOtherProducers() throws IOException {
        String sheet = "<?xml version=\"1.0\"?><worksheet " + NS + " xmlns:x14ac=\"http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac\">"
                + "<sheetPr codeName=\"S\"><tabColor rgb=\"FFFF0000\"/><outlinePr summaryBelow=\" false \" summaryRight=\"0\"/><pageSetUpPr fitToPage=\"1\"/></sheetPr>"
                + "<dimension ref=\"A1:C9\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\"/></sheetView></sheetViews>"
                + "<sheetFormatPr baseColWidth=\"10\" defaultRowHeight=\"16\" outlineLevelRow=\"9\" x14ac:dyDescent=\"0.2\"/>"
                + "<cols><col min=\"1\" max=\"16384\" width=\"10.83203125\" style=\"1\"/><col min=\"2\" max=\"3\" width=\"7\" customWidth=\"1\" hidden=\"true\"></col>"
                + "<col min=\"5\" max=\"99999\" width=\"1e1\"/><col min=\"0\" max=\"2\" width=\"4\"/><col min=\"4\" max=\"3\" width=\"4\"/>"
                + "<col min=\"x\" max=\"3\" width=\"4\"/><col min=\"6\" max=\"6\" width=\"-1\"/><col min=\"6\" max=\"6\" width=\"wide\"/>"
                + "<col min=\"7\" max=\"7\" hidden=\"1\"/><col min=\"8\" width=\"3\"/><col min=\"16385\" max=\"16390\" width=\"3\"/></cols>"
                + "<sheetData>"
                + "<row r=\"1\" spans=\"1:3\" ht=\"31.5\" x14ac:dyDescent=\"0.2\"><c r=\"A1\"><v>1</v></c></row>"
                + "<row r=\"2\" ht=\"20\" customHeight=\"1\" hidden=\"true\" outlineLevel=\"3\" collapsed=\"true\"><c r=\"A2\"><v>2</v></c></row>"
                + "<row r=\"3\" ht=\"tall\" hidden=\"0\" outlineLevel=\"x\" collapsed=\"no\"><c r=\"A3\"><v>3</v></c></row>"
                + "<row r=\"4\" ht=\"-5\" outlineLevel=\"12\" hidden=\" 1 \"><c r=\"A4\"><v>4</v></c></row>"
                + "<row r=\"5\" hidden=\"1\" outlineLevel=\"2\"/><row r=\"6\" hidden=\"1\"><c r=\"A6\" s=\"1\"/></row>"
                + "<row r=\"7\"><c r=\"A7\"><v>7</v></c></row>"
                + "</sheetData></worksheet>";
        try (XlsxReader reader = XlsxReader.open(singleSheet(sheet, "")); SheetReader rows = reader.worksheet(0)) {
            assertEquals(List.of(new ColumnWidth(1, 16384, 10.83203125, false), new ColumnWidth(2, 3, 7, true), new ColumnWidth(5, 16384, 10, false)),
                    rows.columnWidths());
            assertFalse(rows.columnHidden(1));
            assertFalse(rows.columnHidden(0));
            assertTrue(rows.columnWidths().get(1).hidden());
            assertEquals(OptionalDouble.of(10.83203125), rows.columnWidth(2));
            assertEquals(OptionalDouble.empty(), rows.columnWidth(0));
            assertTrue(rows.outlineSummaryAbove());
            assertEquals(7, rows.rowOutlineLevels());

            Row row = rows.nextRow();
            assertEquals(OptionalDouble.of(31.5), row.height());
            assertEquals(0, row.outlineLevel());
            assertFalse(row.hidden());

            row = rows.nextRow();
            assertEquals(OptionalDouble.of(20), row.height());
            assertEquals(3, row.outlineLevel());
            assertTrue(row.hidden());
            assertTrue(row.collapsed());

            row = rows.nextRow();
            assertEquals(3, row.number());
            assertEquals(OptionalDouble.empty(), row.height());
            assertEquals(0, row.outlineLevel());
            assertFalse(row.hidden());
            assertFalse(row.collapsed());

            row = rows.nextRow();
            assertEquals(OptionalDouble.empty(), row.height());
            assertEquals(7, row.outlineLevel());
            assertTrue(row.hidden());

            row = rows.nextRow();
            assertEquals(7, row.number());
            assertEquals(0, row.outlineLevel());
            assertFalse(row.hidden());
            assertNull(rows.nextRow());
        }

        String summaryBelow = "<worksheet " + NS + "><sheetPr><outlinePr summaryBelow=\"1\"/></sheetPr><sheetData/><cols><col min=\"1\" max=\"1\" width=\"5\"/></cols></worksheet>";
        try (XlsxReader reader = XlsxReader.open(singleSheet(summaryBelow, "")); SheetReader rows = reader.worksheet(0)) {
            assertFalse(rows.outlineSummaryAbove());
            assertEquals(List.of(), rows.columnWidths());
            assertNull(rows.nextRow());
            assertEquals(List.of(), rows.columnWidths());
        }
    }

    @Test
    void layoutErrorsSurfaceWhenTheLayoutOrTheFirstRowIsRead() throws IOException {
        StringBuilder cols = new StringBuilder("<worksheet " + NS + "><cols>");
        for (int i = 0; i <= 16384; i++) {
            cols.append("<col min=\"1\" max=\"1\" width=\"1\"/>");
        }
        byte[] tooMany = singleSheet(cols.append("</cols><sheetData/></worksheet>").toString(), "");
        try (XlsxReader reader = XlsxReader.open(tooMany)) {
            SheetReader rows = reader.worksheet(0);
            XlsxException e = assertThrows(XlsxException.class, rows::columnWidths);
            assertEquals(XlsxException.Kind.MALFORMED, e.kind());
            assertTrue(e.getMessage().contains("more than 16384 column ranges"), e.getMessage());
            assertEquals(e, assertThrows(XlsxException.class, rows::nextRow));

            assertThrows(XlsxException.class, reader.worksheet(0)::nextRow);
        }

        byte[] truncated = singleSheet("<worksheet " + NS + "><sheetPr><outlinePr summaryBelow=\"0\"/>", "");
        try (XlsxReader reader = XlsxReader.open(truncated)) {
            SheetReader rows = reader.worksheet(0);
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::outlineSummaryAbove).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, reader.worksheet(0)::rowOutlineLevels).kind());
        }

        try (XlsxReader reader = XlsxReader.open(singleSheet("<worksheet " + NS + "><cols><col min=\"1\" max=\"2\" width=\"9\"/></cols><sheetData/></worksheet>", ""))) {
            SheetReader closedEarly = reader.worksheet(0);
            closedEarly.close();
            assertEquals(XlsxException.Kind.INVALID_STATE, assertThrows(XlsxException.class, closedEarly::columnWidths).kind());
            assertEquals(XlsxException.Kind.INVALID_STATE, assertThrows(XlsxException.class, closedEarly::rowOutlineLevels).kind());

            SheetReader readFirst = reader.worksheet(0);
            assertEquals(1, readFirst.columnWidths().size());
            readFirst.close();
            assertEquals(1, readFirst.columnWidths().size());
            assertFalse(readFirst.outlineSummaryAbove());
        }

        String manyCols = "<col min=\"1\" max=\"1\" width=\"1\"/>".repeat(16385);
        try (XlsxReader reader = XlsxReader.open(singleSheet("<worksheet " + NS + "><cols>" + manyCols + "</cols><sheetData/></worksheet>", ""))) {
            SheetReader rows = reader.worksheet(0);
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::columnWidths).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::columnWidths).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::outlineSummaryAbove).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, () -> rows.columnHidden(1)).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::nextRow).kind());
        }
        try (XlsxReader reader = XlsxReader.open(singleSheet("<worksheet " + NS + "><cols>" + manyCols + "</cols><sheetData/></worksheet>", ""))) {
            SheetReader rows = reader.worksheet(0);
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::nextRow).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::columnWidths).kind());
            assertEquals(XlsxException.Kind.MALFORMED, assertThrows(XlsxException.class, rows::nextRow).kind());
        }
    }

    @Test
    void verifiesTheWholeWorksheetEntry() throws IOException {
        String trailer = "<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>";
        String empty = "<worksheet " + NS + "><cols><col min=\"1\" max=\"1\" width=\"9\"/></cols><sheetData/>" + trailer + "</worksheet>";
        String oneRow = "<worksheet " + NS + "><sheetData><row r=\"1\"><c><v>1</v></c></row></sheetData>" + trailer + "</worksheet>";
        for (String sheet : List.of(empty, oneRow)) {
            byte[] good = storedSingleSheet(sheet);
            try (XlsxReader reader = XlsxReader.open(good); SheetReader rows = reader.worksheet(0)) {
                rows.columnWidths();
                while (rows.nextRow() != null) {
                    // read to the end
                }
            }
            byte[] bad = withBadSheetCrc(good);
            try (XlsxReader reader = XlsxReader.open(bad); SheetReader rows = reader.worksheet(0)) {
                XlsxException e = assertThrows(XlsxException.class, () -> {
                    rows.columnWidths();
                    while (rows.nextRow() != null) {
                        // read to the end
                    }
                });
                assertTrue(e.getMessage().contains("CRC"), e.getMessage());
            }
        }

        byte[] bad = withBadSheetCrc(storedSingleSheet(empty));
        try (XlsxReader reader = XlsxReader.open(bad); SheetReader rows = reader.worksheet(0)) {
            for (int i = 0; i < 2; i++) {
                assertTrue(assertThrows(XlsxException.class, rows::columnWidths).getMessage().contains("CRC"));
            }
            assertTrue(assertThrows(XlsxException.class, rows::nextRow).getMessage().contains("CRC"));
        }
        try (XlsxReader reader = XlsxReader.open(bad); SheetReader rows = reader.worksheet(0)) {
            assertTrue(assertThrows(XlsxException.class, rows::nextRow).getMessage().contains("CRC"));
            assertTrue(assertThrows(XlsxException.class, rows::outlineSummaryAbove).getMessage().contains("CRC"));
        }
    }

    /** A package whose parts are stored without compression. */
    private static byte[] storedSingleSheet(String sheetXml) throws IOException {
        byte[] deflated = singleSheet(sheetXml, "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(deflated));
                java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (java.util.zip.ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] data = in.readAllBytes();
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(data);
                java.util.zip.ZipEntry stored = new java.util.zip.ZipEntry(entry.getName());
                stored.setMethod(java.util.zip.ZipEntry.STORED);
                stored.setSize(data.length);
                stored.setCompressedSize(data.length);
                stored.setCrc(crc.getValue());
                zip.putNextEntry(stored);
                zip.write(data);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Flips a byte of the worksheet's CRC-32 in its local and its central header. */
    private static byte[] withBadSheetCrc(byte[] xlsx) {
        byte[] bytes = xlsx.clone();
        byte[] name = "xl/worksheets/sheet1.xml".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int patched = 0;
        for (int i = 0; i + 4 <= bytes.length; i++) {
            int crcAt;
            int nameAt;
            if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 3 && bytes[i + 3] == 4) {
                crcAt = i + 14;
                nameAt = i + 30;
            } else if (bytes[i] == 'P' && bytes[i + 1] == 'K' && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                crcAt = i + 16;
                nameAt = i + 46;
            } else {
                continue;
            }
            if (nameAt + name.length <= bytes.length
                    && java.util.Arrays.equals(bytes, nameAt, nameAt + name.length, name, 0, name.length)) {
                bytes[crcAt] ^= (byte) 0xFF;
                patched++;
            }
        }
        assertEquals(2, patched);
        return bytes;
    }

    private static List<String> rowSummaries(byte[] xlsx, ReadOptions options) throws IOException {
        List<String> seen = new java.util.ArrayList<>();
        try (XlsxReader reader = XlsxReader.open(xlsx, options); SheetReader rows = reader.worksheet(0)) {
            for (Row row : rows) {
                assertEquals(row.cellCount() == 0 ? 0 : row.column(row.cellCount() - 1), row.lastColumn());
                assertEquals(row.lastColumn(), row.values().size());
                assertEquals(row.lastColumn(), row.toList().size());
                seen.add(row.number() + ":" + row.cellCount() + ":" + row.outlineLevel() + ":" + row.hidden() + ":" + row.collapsed()
                        + ":" + (row.height().isPresent() ? row.height().getAsDouble() : "-"));
            }
        }
        return seen;
    }

    @Test
    void returnsRowsWithoutValuesOnRequest() throws IOException {
        ReadOptions include = ReadOptions.builder().includeEmptyRows(true).build();
        assertFalse(ReadOptions.DEFAULT.includeEmptyRows());
        assertTrue(include.includeEmptyRows());

        byte[] written = Xlsx.write(writer -> {
            Worksheet s = writer.addWorksheet("Data", SheetOptions.builder().rowOutlineLevels(1).build());
            s.addRow("first");
            s.beginRow(RowOptions.builder().height(40).build()).endRow();
            s.skipRows(2);
            s.addRow(5);
            s.beginRow(RowOptions.builder().outlineLevel(1).hidden().build()).endRow();
        });
        assertEquals(List.of("1:1:0:false:false:-", "5:1:0:false:false:-"), rowSummaries(written, ReadOptions.DEFAULT));
        assertEquals(List.of("1:1:0:false:false:-", "2:0:0:false:false:40.0", "5:1:0:false:false:-", "6:0:1:true:false:-"),
                rowSummaries(written, include));

        byte[] other = singleSheet("<worksheet " + NS + "><sheetData><row r=\"2\" ht=\"30\"/>"
                + "<row r=\"3\" hidden=\"1\" collapsed=\"1\"><c r=\"A3\" s=\"1\"/></row>"
                + "<row><c><v>1</v></c></row><row/></sheetData></worksheet>", "");
        assertEquals(List.of("4:1:0:false:false:-"), rowSummaries(other, ReadOptions.DEFAULT));
        assertEquals(List.of("2:0:0:false:false:30.0", "3:0:0:true:true:-", "4:1:0:false:false:-", "5:0:0:false:false:-"),
                rowSummaries(other, include));

        try (XlsxReader reader = XlsxReader.open(other, include); SheetReader rows = reader.worksheet(0)) {
            Row empty = rows.nextRow().copy();
            rows.nextRow();
            assertEquals(2, empty.number());
            assertEquals(0, empty.cellCount());
            assertEquals(OptionalDouble.of(30), empty.height());
            assertEquals(4, rows.stream().filter(r -> r.cellCount() > 0).findFirst().orElseThrow().number());
        }
    }

    static final List<ColumnWidth> SEED_WIDTHS =
            List.of(new ColumnWidth(1, 1, 14, false), new ColumnWidth(2, 2, 22.5, false), new ColumnWidth(4, 4, 6, true));

    static void assertSeedLayout(byte[] xlsx, ReadOptions options) throws IOException {
        try (XlsxReader reader = XlsxReader.open(xlsx, options); SheetReader rows = reader.worksheet("Orders")) {
            assertEquals(List.of("Orders", "Plain"), reader.sheetNames());
            assertEquals(List.of(), reader.tables("Plain"));
            assertTrue(rows.outlineSummaryAbove());
            assertEquals(2, rows.rowOutlineLevels());
            assertEquals(SEED_WIDTHS, rows.columnWidths());
            assertFalse(rows.columnHidden(2));
            assertFalse(rows.columnHidden(3));
            assertTrue(rows.columnHidden(4));
        }
    }

    /** {@code testdata/reader/excel.xlsx} is a workbook written by Hypaxel and saved again by Excel for Mac. */
    @Test
    void readsTablesRowsAndLayoutSavedByExcel() throws IOException {
        byte[] xlsx = Files.readAllBytes(Path.of("..", "testdata", "reader", "excel.xlsx"));
        ReadOptions include = ReadOptions.builder().includeEmptyRows(true).build();
        ExcelTable orders = ExcelTable.builder(1, 1, 4, 3).name("Orders").columns("Product", "Units & more", "Price")
                .style(TableStyle.dark(3)).rowStripes(false).columnStripes(true)
                .highlightFirstColumn(true).highlightLastColumn(true).build();
        ExcelTable plain = ExcelTable.builder(7, 1, 8, 2).name("Table2").columns("Column1", "Column2")
                .style(TableStyle.NONE).headerRow(false).autoFilter(false).build();
        try (XlsxReader reader = XlsxReader.open(xlsx, include)) {
            assertEquals(List.of(orders, plain), reader.tables("Orders"));
        }
        assertSeedLayout(xlsx, include);
        assertEquals(List.of("1:3:0:false:false:-", "2:3:1:false:true:-", "3:3:2:true:false:-", "4:3:1:false:true:30.0",
                "6:0:0:false:false:45.0", "7:2:0:false:false:-", "8:2:0:false:false:-"), rowSummaries(xlsx, include));
    }

    /**
     * {@code testdata/reader/libreoffice.xlsx} is the same workbook saved by LibreOffice Calc, which
     * drops table styles and stores a height on every row.
     */
    @Test
    void readsTablesRowsAndLayoutSavedByLibreOffice() throws IOException {
        byte[] xlsx = Files.readAllBytes(Path.of("..", "testdata", "reader", "libreoffice.xlsx"));
        ReadOptions include = ReadOptions.builder().includeEmptyRows(true).build();
        ExcelTable orders = ExcelTable.builder(1, 1, 4, 3).name("Orders").columns("Product", "Units & more", "Price")
                .style(TableStyle.NONE).rowStripes(false).build();
        ExcelTable plain = ExcelTable.builder(7, 1, 8, 2).name("Table2").columns("Column1", "Column2")
                .style(TableStyle.NONE).rowStripes(false).headerRow(false).autoFilter(false).build();
        try (XlsxReader reader = XlsxReader.open(xlsx, include)) {
            assertEquals(List.of(orders, plain), reader.tables("Orders"));
        }
        assertSeedLayout(xlsx, include);
        assertEquals(List.of("1:3:0:false:false:15.0", "2:3:1:false:false:15.0", "3:3:2:true:false:15.0", "4:3:1:false:true:30.0",
                "6:0:0:false:false:45.0", "7:2:0:false:false:15.0", "8:2:0:false:false:15.0"), rowSummaries(xlsx, include));
    }

    /**
     * {@code testdata/reader/excel-totals.xlsx} is an inventory list saved by Excel for Mac: one
     * table over 1,000 rows with a totals row.
     */
    @Test
    void readsATableWithATotalsRowSavedByExcel() throws IOException {
        byte[] xlsx = Files.readAllBytes(Path.of("..", "testdata", "reader", "excel-totals.xlsx"));
        ExcelTable expected = ExcelTable.builder(1, 1, 1002, 11).name("Table1")
                .columns("SKU", "Item", "Group", "Vendor", "Cost", "Price", "Opening",
                        "Sold", "Closing", "Reorder", "Status")
                .totalsRow(true).build();
        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            assertEquals(List.of("Inventory"), reader.sheetNames());
            assertEquals(List.of(expected), reader.tables(0));
            try (SheetReader sheet = reader.worksheet(0)) {
                assertFalse(sheet.outlineSummaryAbove());
                List<ColumnWidth> widths = sheet.columnWidths();
                assertEquals(11, widths.size());
                assertEquals(new ColumnWidth(1, 1, 11.6640625, false), widths.get(0));
                assertEquals(new ColumnWidth(11, 11, 13.1640625, false), widths.get(10));

                int count = 0;
                Row totals = null;
                for (Row row = sheet.nextRow(); row != null; row = sheet.nextRow()) {
                    count++;
                    assertEquals(0, row.outlineLevel());
                    assertFalse(row.hidden());
                    assertTrue(row.height().isEmpty());
                    if (row.number() == 1002) {
                        totals = row.copy();
                    }
                }
                assertEquals(1002, count);
                assertEquals(6, totals.cellCount());
                assertEquals(List.of(1, 5, 6, 8, 10, 11), List.of(totals.column(0), totals.column(1), totals.column(2),
                        totals.column(3), totals.column(4), totals.column(5)));
                assertEquals("Total", totals.getString(1));
                assertEquals(21.085, totals.getNumber(5));
                assertEquals(33.736, totals.getNumber(6));
                assertEquals(99696, totals.getNumber(8));
                assertEquals(34500, totals.getNumber(10));
                assertEquals(1000, totals.getNumber(11));
            }
        }
    }

    /**
     * {@code testdata/reader/google-sheets.xlsx} is a table downloaded from Google Sheets: a custom
     * table style, no filter buttons, a totals row and a height on every row but the first.
     */
    @Test
    void readsATableDownloadedFromGoogleSheets() throws IOException {
        byte[] xlsx = Files.readAllBytes(Path.of("..", "testdata", "reader", "google-sheets.xlsx"));
        ExcelTable expected = ExcelTable.builder(1, 1, 903, 11).name("Table1")
                .columns("SKU", "Item", "Group", "Vendor", "Cost", "Price", "Opening",
                        "Sold", "Closing", "Reorder", "Status")
                .style(TableStyle.NONE).autoFilter(false)
                .highlightFirstColumn(true).highlightLastColumn(true)
                .totalsRow(true).build();
        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            assertEquals(List.of("Inventory"), reader.sheetNames());
            assertEquals(List.of(expected), reader.tables(0));
            try (SheetReader sheet = reader.worksheet(0)) {
                assertFalse(sheet.outlineSummaryAbove());
                List<ColumnWidth> widths = sheet.columnWidths();
                assertEquals(12, widths.size());
                assertEquals(new ColumnWidth(1, 1, 11.63, false), widths.get(0));
                assertEquals(new ColumnWidth(12, 26, 8.88, false), widths.get(11));
                assertEquals(8.88, sheet.columnWidth(20).orElseThrow());

                int count = 0;
                Row totals = null;
                for (Row row = sheet.nextRow(); row != null; row = sheet.nextRow()) {
                    count++;
                    assertEquals(0, row.outlineLevel());
                    assertFalse(row.hidden());
                    if (row.number() == 1) {
                        assertTrue(row.height().isEmpty());
                    } else {
                        assertEquals(15.75, row.height().orElseThrow());
                    }
                    if (row.number() == 2) {
                        assertEquals(250, row.getNumber(7));
                    }
                    if (row.number() == 903) {
                        totals = row.copy();
                    }
                }
                assertEquals(903, count);
                assertEquals(6, totals.cellCount());
                assertEquals(List.of(1, 5, 6, 8, 10, 11), List.of(totals.column(0), totals.column(1), totals.column(2),
                        totals.column(3), totals.column(4), totals.column(5)));
                assertEquals("Total", totals.getString(1));
                assertEquals(21.20421754, totals.getNumber(5));
                assertEquals(33.92674806, totals.getNumber(6));
                assertEquals(90058, totals.getNumber(8));
                assertEquals(31060, totals.getNumber(10));
                assertEquals(901, totals.getNumber(11));
            }
        }
    }
}
