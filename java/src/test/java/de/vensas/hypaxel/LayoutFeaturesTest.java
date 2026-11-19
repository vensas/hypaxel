package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class LayoutFeaturesTest {
    public record Item(String name) {
    }

    private static RowOptions level(int level) {
        return RowOptions.builder().outlineLevel(level).build();
    }

    private static Map<Integer, Double> columnWidths(String sheetXml) {
        Map<Integer, Double> widths = new TreeMap<>();
        String[] columns = sheetXml.split("<col ");
        for (int i = 1; i < columns.length; i++) {
            widths.put(Integer.parseInt(attribute(columns[i], "min")), Double.parseDouble(attribute(columns[i], "width")));
        }
        return widths;
    }

    private static String attribute(String element, String name) {
        String rest = element.split(name + "=\"", 2)[1];
        return rest.substring(0, rest.indexOf('"'));
    }

    private static void assertKind(XlsxException.Kind kind, Runnable action) {
        assertEquals(kind, assertThrows(XlsxException.class, action::run).kind());
    }

    // ------------------------------------------------------------------ row outline levels

    @Test
    void writesRowOutlineLevels() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("Tree", SheetOptions.builder().rowOutlineLevels(2).outlineSummaryAbove().build());
            sheet.addRow("North", 300);
            sheet.beginRow(level(1)).write("Store A").write(120).endRow();
            sheet.beginRow(RowOptions.builder().outlineLevel(2).hidden().build()).write("Till 1").write(60).endRow();
            sheet.beginRow(RowOptions.builder().outlineLevel(1).collapsed().height(20).build()).write("Store B").endRow();
            sheet.addRow("South", 1);
        });
        String xml = Xlsx.part(xlsx, "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("<sheetPr><outlinePr summaryBelow=\"0\"/></sheetPr>"), xml);
        assertTrue(xml.contains("<sheetFormatPr defaultRowHeight=\"15\" outlineLevelRow=\"2\"/>"));
        assertTrue(xml.contains("<row r=\"1\">"));
        assertTrue(xml.contains("<row r=\"2\" outlineLevel=\"1\">"));
        assertTrue(xml.contains("<row r=\"3\" hidden=\"1\" outlineLevel=\"2\">"));
        assertTrue(xml.contains("<row r=\"4\" ht=\"20\" customHeight=\"1\" outlineLevel=\"1\" collapsed=\"1\">"));

        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Tree");
            assertFalse(sheet.getRowSumsBelow());
            assertEquals(2, sheet.getCTWorksheet().getSheetFormatPr().getOutlineLevelRow());
            assertEquals(0, sheet.getRow(0).getOutlineLevel());
            assertEquals(1, sheet.getRow(1).getOutlineLevel());
            XSSFRow hidden = sheet.getRow(2);
            assertEquals(2, hidden.getOutlineLevel());
            assertTrue(hidden.getZeroHeight());
            assertEquals("Till 1", hidden.getCell(0).getStringCellValue());
            assertTrue(sheet.getRow(3).getCTRow().getCollapsed());
            assertEquals(20f, sheet.getRow(3).getHeightInPoints());
            assertEquals(1.0, sheet.getRow(4).getCell(1).getNumericCellValue());
        }
    }

    @Test
    void combinesTabColorAndSummaryAbove() {
        String xml = Xlsx.part(Xlsx.write(writer -> writer.addWorksheet("S",
                SheetOptions.builder().tabColor(Color.BLUE).outlineSummaryAbove().build())), "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("<sheetPr><tabColor rgb=\"FF0000FF\"/><outlinePr summaryBelow=\"0\"/></sheetPr>"), xml);
    }

    @Test
    void outlineLevelsMustBeDeclared() {
        Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S", SheetOptions.builder().rowOutlineLevels(1).build());
            sheet.beginRow(level(1));
            XlsxException e = assertThrows(XlsxException.class, () -> sheet.beginRow(level(2)));
            assertEquals(XlsxException.Kind.INVALID_ARGUMENT, e.kind());
            assertTrue(e.getMessage().contains("SheetOptions.Builder.rowOutlineLevels"), e.getMessage());
            Worksheet plain = writer.addWorksheet("T");
            assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> plain.beginRowAt(3, level(1)));
            plain.beginRowAt(3, RowOptions.DEFAULT).write("x");
        });
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> RowOptions.builder().outlineLevel(8));
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> RowOptions.builder().outlineLevel(-1));
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> RowOptions.builder().height(410));
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().rowOutlineLevels(8));
        RowOptions options = RowOptions.builder().height(12).build();
        assertTrue(options.hasHeight());
        assertFalse(RowOptions.DEFAULT.hasHeight());
        assertEquals(StyleId.DEFAULT, options.style());
    }

    // ------------------------------------------------------------------ explicit rows and whole-row writes

    @Test
    void addRowFillsAnExplicitlyBegunRow() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S", SheetOptions.builder().rowOutlineLevels(1).build());
            sheet.beginRow(level(1)).addRow("grouped", 1);
            sheet.addRow("plain", 2);
            sheet.beginRow(30).write("partial");
            sheet.addRow("next row", 3);
            sheet.beginRow(RowOptions.builder().hidden().build()).skipCells(1);
            sheet.addRow("after skip");
            sheet.beginRow(level(1)).endRow();
            sheet.addRow("after empty row");
            sheet.beginRow(level(1)).addRow(List.of("iterable"));
            sheet.beginRow(level(1));
            sheet.addRows(List.of(new Object[] {"rows 1"}, new Object[] {"rows 2"}));
            sheet.beginRow(level(1));
            sheet.writeRows(List.of("row writer"), (s, item) -> s.write(item));
        });
        String xml = Xlsx.part(xlsx, "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("<row r=\"1\" outlineLevel=\"1\"><c t=\"inlineStr\"><is><t>grouped</t>"), xml);
        assertTrue(xml.contains("<row r=\"3\" ht=\"30\" customHeight=\"1\"><c t=\"inlineStr\"><is><t>partial</t></is></c></row>"));
        assertTrue(xml.contains("<row r=\"5\" hidden=\"1\"></row>"));
        assertTrue(xml.contains("<row r=\"7\" outlineLevel=\"1\"></row>"));
        assertTrue(xml.contains("<row r=\"9\" outlineLevel=\"1\"><c t=\"inlineStr\"><is><t>iterable</t>"));
        assertTrue(xml.contains("<row r=\"10\" outlineLevel=\"1\"><c t=\"inlineStr\"><is><t>rows 1</t>"));
        assertTrue(xml.contains("<row r=\"11\"><c t=\"inlineStr\"><is><t>rows 2</t>"));
        assertTrue(xml.contains("<row r=\"12\" outlineLevel=\"1\"><c t=\"inlineStr\"><is><t>row writer</t>"));
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("S");
            assertEquals("plain", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("next row", sheet.getRow(3).getCell(0).getStringCellValue());
            assertEquals("after skip", sheet.getRow(5).getCell(0).getStringCellValue());
            assertEquals("after empty row", sheet.getRow(7).getCell(0).getStringCellValue());
            assertEquals(11, sheet.getLastRowNum());
        }
    }

    @Test
    void objectsFillAnExplicitlyBegunRow() {
        String xml = Xlsx.part(Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.addRow("title");
            sheet.beginRow(25);
            sheet.writeObjects(Item.class, List.of(new Item("a"), new Item("b")), ObjectOptions.builder().autoFilter(true).build());
        }), "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("<row r=\"2\" ht=\"25\" customHeight=\"1\"><c s=\"1\" t=\"inlineStr\"><is><t>name</t>"), xml);
        assertTrue(xml.contains("<autoFilter ref=\"A2:A4\"/>"), xml);
    }

    @Test
    void objectsStartBelowARowWithCells() {
        String xml = Xlsx.part(Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.beginRow().write("title");
            sheet.writeObjects(Item.class, List.of(new Item("a")), ObjectOptions.builder().autoFilter(true).build());
        }), "xl/worksheets/sheet1.xml");
        assertTrue(xml.contains("<autoFilter ref=\"A2:A3\"/>"), xml);
    }

    // ------------------------------------------------------------------ tables

    @Test
    void writesTables() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("Orders");
            sheet.addRow("Product", "Units", "Line\nbreak");
            for (int i = 0; i < 5; i++) {
                sheet.addRow("Widget", i, 2.5);
            }
            int last = sheet.rowNumber();
            sheet.addTable(ExcelTable.builder(1, 1, last, 3).name("Orders").columns("Product", "Units", "Line\nbreak").build());
            sheet.addTable(ExcelTable.builder(10, 1, 12, 2).headerRow(false).style(TableStyle.NONE).build());

            Worksheet second = writer.addWorksheet("Second");
            second.addRow("A & B");
            second.addRow(1);
            second.addTable(ExcelTable.builder(1, 1, 2, 1).columns(List.of("A & B")).style(TableStyle.light(1)).columnStripes(true).build());
            writer.addWorksheet("Third").addRow("no tables");
        });

        Map<String, String> parts = Xlsx.parts(xlsx);
        for (String part : List.of("xl/tables/table1.xml", "xl/tables/table2.xml", "xl/tables/table3.xml",
                "xl/worksheets/_rels/sheet1.xml.rels", "xl/worksheets/_rels/sheet2.xml.rels")) {
            assertTrue(parts.containsKey(part), "missing " + part);
        }
        assertFalse(parts.containsKey("xl/worksheets/_rels/sheet3.xml.rels"));

        String t1 = parts.get("xl/tables/table1.xml");
        assertTrue(t1.contains("id=\"1\" name=\"Orders\" displayName=\"Orders\" ref=\"A1:C6\" totalsRowShown=\"0\""), t1);
        assertTrue(t1.contains("<autoFilter ref=\"A1:C6\"/>"));
        assertTrue(t1.contains("<tableColumn id=\"3\" name=\"Line_x000A_break\"/>"));
        assertTrue(t1.contains(
                "name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"0\""));

        String t2 = parts.get("xl/tables/table2.xml");
        assertTrue(t2.contains("name=\"Table2\""), t2);
        assertTrue(t2.contains("headerRowCount=\"0\""));
        assertFalse(t2.contains("autoFilter"));
        assertTrue(t2.contains("<tableColumn id=\"1\" name=\"Column1\"/>"));
        assertFalse(t2.contains("TableStyle"));

        String t3 = parts.get("xl/tables/table3.xml");
        assertTrue(t3.contains("name=\"Table3\""));
        assertTrue(t3.contains("<tableColumn id=\"1\" name=\"A &amp; B\"/>"));
        assertTrue(t3.contains("name=\"TableStyleLight1\""));
        assertTrue(t3.contains("showColumnStripes=\"1\""));

        assertTrue(parts.get("xl/worksheets/sheet1.xml").endsWith(
                "<tableParts count=\"2\"><tablePart r:id=\"rId1\"/><tablePart r:id=\"rId2\"/></tableParts></worksheet>"));
        assertTrue(parts.get("xl/worksheets/_rels/sheet2.xml.rels").contains("Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/"
                + "officeDocument/2006/relationships/table\" Target=\"../tables/table3.xml\""));
        String types = parts.get("[Content_Types].xml");
        assertEquals(3, types.split("spreadsheetml\\.table\\+xml", -1).length - 1);

        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            List<XSSFTable> tables = workbook.getSheet("Orders").getTables();
            assertEquals(2, tables.size());
            XSSFTable orders = workbook.getTable("Orders");
            assertEquals("A1:C6", orders.getArea().formatAsString());
            assertEquals("Units", orders.getColumns().get(1).getName());
            assertEquals("TableStyleMedium2", orders.getStyleName());
            assertEquals(1, workbook.getSheet("Second").getTables().size());
            assertEquals("Product", workbook.getSheet("Orders").getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void validatesTables() {
        Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            XlsxException.Kind invalid = XlsxException.Kind.INVALID_ARGUMENT;
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 2).build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(1, 1, 1, 1).columns("a").build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 2).columns("a").build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 2).columns("a", "A").build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 2).columns("a", "").build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 1).columns("x".repeat(256)).build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(0, 1, 3, 1).columns("a").build()));
            assertKind(invalid, () -> sheet.addTable(ExcelTable.builder(3, 1, 1, 1).columns("a").build()));
            assertKind(XlsxException.Kind.INVALID_TABLE_NAME, () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 1).columns("a").name("A1").build()));
            assertKind(XlsxException.Kind.INVALID_TABLE_NAME,
                    () -> sheet.addTable(ExcelTable.builder(1, 1, 3, 1).columns("a").name("my table").build()));
            assertKind(invalid, () -> TableStyle.medium(29));
            assertKind(invalid, () -> TableStyle.light(0));
            assertKind(invalid, () -> TableStyle.dark(12));

            sheet.addTable(ExcelTable.builder(1, 1, 3, 1).columns("a").name("Data").build());
            XlsxException.Kind overlap = XlsxException.Kind.OVERLAPPING_RANGE;
            assertKind(overlap, () -> sheet.addTable(ExcelTable.builder(3, 1, 5, 1).columns("b").build()));
            assertKind(XlsxException.Kind.DUPLICATE_TABLE_NAME,
                    () -> sheet.addTable(ExcelTable.builder(10, 1, 12, 1).columns("b").name("DATA").build()));
            assertKind(overlap, () -> sheet.mergeCells(2, 1, 2, 3));
            assertKind(overlap, () -> sheet.autoFilter(1, 1, 9, 1));

            sheet.mergeCells(20, 1, 20, 2);
            assertKind(overlap, () -> sheet.addTable(ExcelTable.builder(19, 1, 21, 1).columns("c").build()));
            sheet.autoFilter(30, 1, 40, 2);
            assertKind(overlap, () -> sheet.addTable(ExcelTable.builder(35, 2, 36, 2).columns("d").build()));

            Worksheet other = writer.addWorksheet("T");
            assertKind(XlsxException.Kind.DUPLICATE_TABLE_NAME,
                    () -> other.addTable(ExcelTable.builder(1, 1, 3, 1).columns("a").name("data").build()));
            other.addTable(ExcelTable.builder(1, 1, 3, 1).columns("a").build());
            assertKind(XlsxException.Kind.INVALID_STATE, () -> sheet.addTable(ExcelTable.builder(50, 1, 52, 1).columns("a").build()));
        });
    }

    @Test
    void exposesTableSettings() {
        ExcelTable table = ExcelTable.builder(2, 3, 4, 5).name("T").columns("a", "b", "c").style(TableStyle.dark(11))
                .headerRow(true).autoFilter(false).rowStripes(false).columnStripes(true)
                .highlightFirstColumn(true).highlightLastColumn(true).build();
        assertEquals(List.of(2, 3, 4, 5), List.of(table.firstRow(), table.firstColumn(), table.lastRow(), table.lastColumn()));
        assertEquals("T", table.name());
        assertEquals(List.of("a", "b", "c"), table.columns());
        assertEquals(TableStyle.dark(11), table.style());
        assertEquals("TableStyleDark11", table.style().toString());
        assertEquals(TableStyle.dark(11).hashCode(), table.style().hashCode());
        assertEquals("None", TableStyle.NONE.toString());
        assertNull(TableStyle.NONE.name());
        assertTrue(table.headerRow() && !table.autoFilter() && !table.rowStripes() && table.columnStripes());
        assertTrue(table.highlightFirstColumn() && table.highlightLastColumn());

        String xml = Xlsx.part(Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.addTable(table);
        }), "xl/tables/table1.xml");
        assertTrue(xml.contains("<tableStyleInfo name=\"TableStyleDark11\" showFirstColumn=\"1\" showLastColumn=\"1\" "
                + "showRowStripes=\"0\" showColumnStripes=\"1\"/>"), xml);
        assertFalse(xml.contains("autoFilter"));
    }

    // ------------------------------------------------------------------ auto-fit

    @Test
    void autoFitMeasuresTheSample() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            StyleId header = writer.addStyle(Style.builder().bold().build());
            StyleId big = writer.addStyle(Style.builder().fontSize(22).build());
            StyleId money = writer.addStyle(Style.builder().numberFormat(NumberFormats.THOUSANDS2).build());
            SheetOptions options = SheetOptions.builder()
                    .autoFitColumns()
                    .column(2, Column.ofWidth(30))
                    .column(6, Column.DEFAULT.withHidden(true))
                    .build();
            Worksheet sheet = writer.addWorksheet("Fit", options);
            sheet.write("Name", header).write("fixed").write(1_234_567.0, money).write(true).write("x", big).endRow();
            sheet.write("A much longer product name").write("x").write(1.0, money).write(false).write("x", big).endRow();
            sheet.addRow("short\nWith a very long second line in a wrapped cell");
            sheet.skipCells(7).write(42).endRow();
        });
        String xml = Xlsx.part(xlsx, "xl/worksheets/sheet1.xml");
        Map<Integer, Double> widths = columnWidths(xml);

        double name = widths.get(1);
        assertTrue(name >= 40 && name < 50, "longest line of column A, got " + name);
        assertEquals(30.0, widths.get(2), "explicit widths win");
        double money = widths.get(3);
        assertTrue(money >= 9 && money < 13, "1,234,567.00 fits, got " + money);
        assertTrue(widths.get(4) >= 5 && widths.get(4) < 7, "FALSE");
        assertTrue(widths.get(5) > 2, "larger font is wider");
        assertEquals(9.140625, widths.get(6), "hidden column without content keeps the default width");
        assertTrue(xml.contains("<col min=\"6\" max=\"6\" width=\"9.140625\" hidden=\"1\"/>"));
        assertFalse(widths.containsKey(7), "skipped empty columns get no entry");
        assertTrue(widths.get(8) < 3);
        assertTrue(xml.indexOf("<cols>") < xml.indexOf("<sheetData>"));

        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            XSSFSheet sheet = workbook.getSheet("Fit");
            assertEquals(42.0, sheet.getRow(3).getCell(7).getNumericCellValue());
            assertEquals(30 * 256, sheet.getColumnWidth(1));
            assertTrue(sheet.isColumnHidden(5));
        }
    }

    @Test
    void autoFitMeasuresEveryValueType() {
        String xml = Xlsx.part(Xlsx.write(writer -> {
            StyleId iso = writer.addStyle(Style.builder().numberFormat("dddd, mmmm d, yyyy").build());
            Worksheet sheet = writer.addWorksheet("S", SheetOptions.builder().autoFitColumns().build());
            sheet.write(LocalDate.of(2026, 1, 2))
                    .write(LocalDate.of(2026, 1, 2), iso)
                    .write(new BigDecimal("12345.678"))
                    .write(12L)
                    .writeFormula("SUM(1,2)")
                    .write(java.time.LocalTime.NOON)
                    .write(java.time.Duration.ofHours(-30))
                    .write(Double.NaN)
                    .write(1e20)
                    .endRow();
        }), "xl/worksheets/sheet1.xml");
        Map<Integer, Double> widths = columnWidths(xml);
        assertTrue(widths.get(1) > 9 && widths.get(1) < 12, "short date: " + widths);
        assertTrue(widths.get(2) > 25, "long date: " + widths);
        assertTrue(widths.get(3) > 8 && widths.get(3) < 11, "BigDecimal: " + widths);
        assertTrue(widths.get(4) < 3, "integer: " + widths);
        assertFalse(widths.containsKey(5), "formulas are not measured: " + widths);
        assertTrue(widths.get(6) > 7 && widths.get(7) > 7, "time and duration: " + widths);
        assertTrue(widths.get(8) > 5 && widths.get(9) > 10, "#NUM! and scientific: " + widths);
    }

    @Test
    void autoFitStopsAfterTheSampleAndKeepsStreaming() throws Exception {
        final class CountingStream extends OutputStream {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int writes;

            @Override
            public void write(int b) {
                writes++;
                bytes.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                writes++;
                bytes.write(b, off, len);
            }
        }

        CountingStream out = new CountingStream();
        try (XlsxWriter writer = XlsxWriter.create(out, XlsxOptions.builder().flushThreshold(4096).build())) {
            AutoFit fit = AutoFit.builder().sampleRows(10).maxWidth(20).build();
            Worksheet sheet = writer.addWorksheet("S", SheetOptions.builder().autoFit(fit).build());
            for (int i = 0; i < 9; i++) {
                sheet.addRow("row " + i, i);
            }
            writer.flush();
            assertTrue(out.bytes.size() < 100, "only the ZIP entry header is written while the sample is held back");
            sheet.addRow("row 9", 9);
            sheet.addRow("a value far wider than anything in the sample, which is not measured", 1);
            for (int i = 0; i < 20_000; i++) {
                sheet.addRow(i, i * 1.5);
            }
        }
        assertTrue(out.writes > 3, "output is still flushed incrementally after the sample");
        Map<Integer, Double> widths = columnWidths(Xlsx.part(out.bytes.toByteArray(), "xl/worksheets/sheet1.xml"));
        assertEquals(2, widths.size());
        assertTrue(widths.get(1) < 10, "rows after the sample are not measured: " + widths);
    }

    @Test
    void autoFitStopsAtTheSampleSizeLimit() {
        String text = "x".repeat(30_000);
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S", SheetOptions.builder().autoFitColumns().build());
            for (int i = 0; i < 300; i++) {
                sheet.addRow(text);
            }
            sheet.addRow(text, "measured only before the limit");
        });
        Map<Integer, Double> widths = columnWidths(Xlsx.part(xlsx, "xl/worksheets/sheet1.xml"));
        assertEquals(Map.of(1, 255.0), widths);
    }

    @Test
    void autoFitRespectsMaxWidthAndShortSheets() {
        byte[] xlsx = Xlsx.write(writer -> {
            writer.addWorksheet("S", SheetOptions.builder().autoFit(AutoFit.builder().maxWidth(15).build()).build()).addRow("x".repeat(500));
            writer.addWorksheet("Empty", SheetOptions.builder().autoFitColumns().build());
            writer.addWorksheet("Explicit only", SheetOptions.builder().autoFitColumns().columnWidths(12).build()).addRow(1);
        });
        Map<String, String> parts = Xlsx.parts(xlsx);
        assertEquals(Map.of(1, 15.0), columnWidths(parts.get("xl/worksheets/sheet1.xml")));
        assertFalse(parts.get("xl/worksheets/sheet2.xml").contains("<cols>"));
        assertEquals(Map.of(1, 12.0), columnWidths(parts.get("xl/worksheets/sheet3.xml")));
    }

    @Test
    void autoFitWorksWithSharedStrings() throws Exception {
        XlsxOptions options = XlsxOptions.builder().stringStorage(StringStorage.SHARED).build();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(options, writer -> {
            Worksheet sheet = writer.addWorksheet("S", SheetOptions.builder().autoFitColumns().build());
            sheet.addRow("A fairly long shared string");
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertTrue(sheet.getColumnWidth(0) > 20 * 256);
            assertEquals("A fairly long shared string", sheet.getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void validatesAutoFit() {
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> AutoFit.builder().sampleRows(0));
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> AutoFit.builder().maxWidth(Double.NaN));
        assertKind(XlsxException.Kind.INVALID_ARGUMENT, () -> AutoFit.builder().maxWidth(0));
        assertEquals(1000, AutoFit.DEFAULT.sampleRows());
        assertEquals(255.0, AutoFit.DEFAULT.maxWidth());
        SheetOptions options = SheetOptions.builder().autoFitColumns().rowOutlineLevels(3).outlineSummaryAbove().build();
        assertEquals(AutoFit.DEFAULT, options.autoFit());
        assertEquals(3, options.rowOutlineLevels());
        assertTrue(options.outlineSummaryAbove());
        assertNull(SheetOptions.DEFAULT.autoFit());
    }

    @Test
    void everythingTogetherRoundTrips() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            SheetOptions options = SheetOptions.builder().autoFitColumns().rowOutlineLevels(1).freezeRows(1).tabColor(Color.BLUE).build();
            Worksheet sheet = writer.addWorksheet("All", options);
            sheet.addRow("Region", "Revenue");
            sheet.addRow("North", 10);
            sheet.beginRow(level(1)).write("  Store").write(4).endRow();
            sheet.addTable(ExcelTable.builder(1, 1, 3, 2).columns("Region", "Revenue").build());
        });
        String xml = Xlsx.part(xlsx, "xl/worksheets/sheet1.xml");
        List<Integer> positions = new ArrayList<>();
        for (String tag : List.of("<sheetPr>", "<sheetViews>", "<sheetFormatPr", "<cols>", "<sheetData>", "<pageMargins", "<tableParts")) {
            int position = xml.indexOf(tag);
            assertTrue(position >= 0, tag + " missing");
            positions.add(position);
        }
        List<Integer> sorted = new ArrayList<>(positions);
        sorted.sort(null);
        assertEquals(sorted, positions, "schema order: " + xml);
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            assertEquals("  Store", workbook.getSheet("All").getRow(2).getCell(0).getStringCellValue());
        }
    }

    @Test
    void writesNothingNewWithTheFeaturesOff() throws IOException {
        String xml = Xlsx.part(Xlsx.write(writer -> writer.addWorksheet("S").addRow("a")), "xl/worksheets/sheet1.xml");
        assertFalse(xml.contains("<sheetPr>"));
        assertTrue(xml.contains("<sheetFormatPr defaultRowHeight=\"15\"/>"));
        assertFalse(xml.contains("<cols>"));
        assertFalse(xml.contains("tableParts"));
    }
}
