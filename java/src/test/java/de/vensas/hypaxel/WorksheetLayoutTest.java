package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;
import org.apache.poi.ss.util.PaneInformation;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WorksheetLayoutTest {
    @Test
    void writesMultipleSheets() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            Worksheet first = writer.addWorksheet("First");
            first.write("a").endRow();
            assertEquals("First", first.name());
            Worksheet second = writer.addWorksheet("Zweites Blatt & <Co>");
            second.write("b").endRow();
            second.write("c").endRow();
            writer.addWorksheet("Empty");
            assertEquals(3, writer.worksheetCount());
        })) {
            assertEquals(3, workbook.getNumberOfSheets());
            assertEquals("First", workbook.getSheetName(0));
            assertEquals("Zweites Blatt & <Co>", workbook.getSheetName(1));
            assertEquals("a", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            assertEquals("c", workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue());
            assertEquals(0, workbook.getSheetAt(2).getPhysicalNumberOfRows());
            assertEquals(0, workbook.getActiveSheetIndex());
        }
    }

    @Test
    void writesAnEmptyWorkbook() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> { })) {
            assertEquals(1, workbook.getNumberOfSheets());
            assertEquals("Sheet1", workbook.getSheetName(0));
        }
    }

    @Test
    void writesColumnDefinitions() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId bold = writer.addStyle(Style.builder().bold().build());
            SheetOptions options = SheetOptions.builder()
                    .columnWidths(20, 12.5)
                    .column(4, Column.ofWidth(30).withStyle(bold))
                    .column(5, Column.DEFAULT.withHidden(true))
                    .column(1, Column.ofWidth(8))
                    .columnWidths(22)
                    .build();
            assertEquals(4, options.columns().size());
            assertTrue(options.columns().get(4).hasWidth());
            assertFalse(options.columns().get(5).hasWidth());
            writer.addWorksheet("S", options).write("x").endRow();
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals(22 * 256, sheet.getColumnWidth(0));
            assertEquals((int) (12.5 * 256), sheet.getColumnWidth(1));
            assertEquals(30 * 256, sheet.getColumnWidth(3));
            assertTrue(workbook.getFontAt(sheet.getColumnStyle(3).getFontIndex()).getBold());
            assertTrue(sheet.isColumnHidden(4));
            assertFalse(sheet.isColumnHidden(0));
        }
    }

    @Test
    void writesRowHeightsStylesAndGaps() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId fill = writer.addStyle(Style.builder().fill(Color.YELLOW).build());
            Worksheet sheet = writer.addWorksheet("S");
            sheet.beginRow(30).write("tall").endRow();
            sheet.beginRow(fill).write("filled").endRow();
            sheet.beginRow(18.5, fill).endRow();
            sheet.beginRow().endRow();
            sheet.endRow();
            sheet.skipRows(2).write("row 8").skipCells(2).write("D8");
            assertEquals(8, sheet.rowNumber());
            assertEquals(4, sheet.columnNumber());
            sheet.beginRowAt(20).write("row 20").endRow();
            sheet.beginRowAt(25, 12, fill).write("row 25");
            sheet.skipRows(0).skipCells(1).write("B26").endRow();
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals(30f, sheet.getRow(0).getHeightInPoints());
            assertEquals("tall", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("FFFFFF00", sheet.getRow(1).getRowStyle().getFillForegroundColorColor().getARGBHex());
            assertEquals(18.5f, sheet.getRow(2).getHeightInPoints());
            assertTrue(sheet.getRow(2).isFormatted());
            assertEquals(0, sheet.getRow(3).getPhysicalNumberOfCells());
            assertNull(sheet.getRow(4));
            assertNull(sheet.getRow(6));
            XSSFRow eighth = sheet.getRow(7);
            assertEquals("row 8", eighth.getCell(0).getStringCellValue());
            assertNull(eighth.getCell(1));
            assertEquals("D8", eighth.getCell(3).getStringCellValue());
            assertEquals("row 20", sheet.getRow(19).getCell(0).getStringCellValue());
            assertEquals(12f, sheet.getRow(24).getHeightInPoints());
            assertEquals("B26", sheet.getRow(25).getCell(1).getStringCellValue());
        }
    }

    @Test
    void freezesPanes() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            writer.addWorksheet("Rows", SheetOptions.builder().freezeRows(1).build()).write(1).endRow();
            writer.addWorksheet("Columns", SheetOptions.builder().freezeColumns(2).build()).write(1).endRow();
            writer.addWorksheet("Both", SheetOptions.builder().freezeRows(3).freezeColumns(1).build()).write(1).endRow();
            writer.addWorksheet("None").write(1).endRow();
        })) {
            PaneInformation rows = workbook.getSheet("Rows").getPaneInformation();
            assertTrue(rows.isFreezePane());
            assertEquals(1, rows.getHorizontalSplitPosition());
            assertEquals(0, rows.getVerticalSplitPosition());
            PaneInformation columns = workbook.getSheet("Columns").getPaneInformation();
            assertEquals(0, columns.getHorizontalSplitPosition());
            assertEquals(2, columns.getVerticalSplitPosition());
            PaneInformation both = workbook.getSheet("Both").getPaneInformation();
            assertEquals(3, both.getHorizontalSplitPosition());
            assertEquals(1, both.getVerticalSplitPosition());
            assertEquals(3, both.getHorizontalSplitTopRow());
            assertEquals(1, both.getVerticalSplitLeftColumn());
            assertNull(workbook.getSheet("None").getPaneInformation());
        }
    }

    @Test
    void writesMergedCellsAndAutoFilter() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("Bob's data");
            sheet.addRow("Title");
            sheet.addRow("a", "b", "c");
            sheet.addRow(1, 2, 3);
            sheet.mergeCells(1, 1, 1, 3).mergeCells(5, 2, 9, 2).autoFilter(2, 1, 3, 3);
            writer.addWorksheet("Other").autoFilter(1, 1, 1, 27);
        });
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals(List.of("A1:C1", "B5:B9"), sheet.getMergedRegions().stream().map(r -> r.formatAsString()).toList());
            assertEquals("A2:C3", sheet.getCTWorksheet().getAutoFilter().getRef());
            assertEquals("A1:AA1", workbook.getSheetAt(1).getCTWorksheet().getAutoFilter().getRef());
            assertEquals("'Bob''s data'!$A$2:$C$3", workbook.getName("_xlnm._FilterDatabase").getRefersToFormula());
        }
        assertTrue(Xlsx.part(xlsx, "xl/workbook.xml").contains("localSheetId=\"1\" hidden=\"1\">'Other'!$A$1:$AA$1<"));
    }

    @Test
    void writesHiddenSheetsTabColorsAndGridLines() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            writer.addWorksheet("Hidden", SheetOptions.builder().hidden().build()).write(1).endRow();
            writer.addWorksheet("Visible", SheetOptions.builder().tabColor(Color.hex("#FF8800")).hideGridLines().build()).write(2).endRow();
            SheetOptions options = SheetOptions.builder().build();
            assertTrue(options.showGridLines());
            assertFalse(options.hidden());
            assertNull(options.tabColor());
            writer.addWorksheet("Plain", options).write(3).endRow();
        })) {
            assertTrue(workbook.isSheetHidden(0));
            assertFalse(workbook.isSheetHidden(1));
            assertEquals(1, workbook.getActiveSheetIndex());
            assertEquals("FFFF8800", workbook.getSheetAt(1).getTabColor().getARGBHex());
            assertFalse(workbook.getSheetAt(1).isDisplayGridlines());
            assertTrue(workbook.getSheetAt(2).isDisplayGridlines());
            assertNull(workbook.getSheetAt(2).getTabColor());
        }
    }

    @Test
    void writesRowsFromIterablesAndStreams() throws Exception {
        record Order(int id, String customer, double total) {
        }
        List<Order> orders = List.of(new Order(1, "Ada", 10.5), new Order(2, "Linus", 20.25));
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId money = writer.addStyle(Style.builder().numberFormat(NumberFormats.DECIMAL2).build());
            Worksheet sheet = writer.addWorksheet("S");
            sheet.addRow(List.of("Id", "Customer", "Total"));
            sheet.writeRows(orders, (row, order) -> row.write(order.id()).write(order.customer()).write(order.total(), money));
            sheet.writeRows(orders.stream().filter(o -> o.id() > 1), (row, order) -> row.write(order.id()));
            sheet.addRows(List.of(new Object[] {"array", 1}, List.of("list", 2), "single"));
            sheet.addRows(Stream.of(List.of("stream", 3)));
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertEquals("Customer", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Ada", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(20.25, sheet.getRow(2).getCell(2).getNumericCellValue());
            assertEquals("0.00", sheet.getRow(2).getCell(2).getCellStyle().getDataFormatString());
            assertEquals(2.0, sheet.getRow(3).getCell(0).getNumericCellValue());
            assertEquals("array", sheet.getRow(4).getCell(0).getStringCellValue());
            assertEquals(2.0, sheet.getRow(5).getCell(1).getNumericCellValue());
            assertEquals("single", sheet.getRow(6).getCell(0).getStringCellValue());
            assertEquals("stream", sheet.getRow(7).getCell(0).getStringCellValue());
            assertEquals(8, sheet.getPhysicalNumberOfRows());
        }
    }
}
