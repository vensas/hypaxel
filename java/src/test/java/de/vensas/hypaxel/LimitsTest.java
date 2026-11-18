package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.vensas.hypaxel.XlsxException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class LimitsTest {
    private static void assertKind(Kind kind, Executable executable) {
        assertEquals(kind, assertThrows(XlsxException.class, executable).kind());
    }

    private static XlsxWriter discarding() {
        return XlsxWriter.create(OutputStream.nullOutputStream());
    }

    @Test
    void rejectsInvalidSheetNames() throws IOException {
        try (XlsxWriter writer = discarding()) {
            for (String name : List.of("", "a".repeat(32), "a:b", "a\\b", "a/b", "a?b", "a*b", "a[b", "a]b", "'quoted", "quoted'", "History", "hisTORY")) {
                XlsxException e = assertThrows(XlsxException.class, () -> writer.addWorksheet(name), name);
                assertEquals(Kind.INVALID_SHEET_NAME, e.kind());
                assertTrue(e.getMessage().contains("invalid worksheet name"));
            }
            writer.addWorksheet("a".repeat(31));
            writer.addWorksheet("it's fine");
            assertEquals(2, writer.worksheetCount());
        }
    }

    @Test
    void rejectsDuplicateSheetNames() throws IOException {
        try (XlsxWriter writer = discarding()) {
            Worksheet first = writer.addWorksheet("Data");
            assertKind(Kind.DUPLICATE_SHEET_NAME, () -> writer.addWorksheet("Data"));
            assertKind(Kind.DUPLICATE_SHEET_NAME, () -> writer.addWorksheet("DATA"));
            first.write("still writable").endRow();
        }
    }

    @Test
    void rejectsTextOverTheCellLimit() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.write("x".repeat(XlsxWriter.MAX_CELL_TEXT_LENGTH));
            XlsxException e = assertThrows(XlsxException.class, () -> sheet.write("y".repeat(XlsxWriter.MAX_CELL_TEXT_LENGTH + 1)));
            assertEquals(Kind.TEXT_TOO_LONG, e.kind());
            assertTrue(e.getMessage().contains("row 1, column 2 has 32768 characters"));
            sheet.write("after").endRow();
        });
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            assertEquals(32767, workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue().length());
            assertEquals("after", workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    void writesLongTextThatNeedsEscaping() throws Exception {
        String text = "<&\u0001_x0041_日本😀>".repeat(2000);
        XlsxOptions options = XlsxOptions.builder().xmlBufferSize(1024).flushThreshold(1024).build();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(options, writer -> writer.addWorksheet("S").write(text).write(text).endRow())) {
            assertEquals(text, workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    void rejectsTooManyColumns() throws IOException {
        try (XlsxWriter writer = discarding()) {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.skipCells(XlsxWriter.MAX_COLUMNS - 1).write("last");
            assertEquals(XlsxWriter.MAX_COLUMNS, sheet.columnNumber());
            assertKind(Kind.TOO_MANY_COLUMNS, () -> sheet.write("one too many"));
            assertKind(Kind.TOO_MANY_COLUMNS, () -> sheet.skipCells(1));
            assertKind(Kind.TOO_MANY_COLUMNS, () -> sheet.writeBlank(StyleId.DEFAULT));
            sheet.endRow();
            assertKind(Kind.TOO_MANY_COLUMNS, () -> sheet.skipCells(XlsxWriter.MAX_COLUMNS + 1));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.skipCells(-1));
        }
    }

    @Test
    void rejectsRowsBeyondTheLimit() throws IOException {
        try (XlsxWriter writer = discarding()) {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.beginRowAt(XlsxWriter.MAX_ROWS).write("last row").endRow();
            assertEquals(XlsxWriter.MAX_ROWS, sheet.rowNumber());
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> sheet.write("next"));
            assertKind(Kind.ROW_OUT_OF_RANGE, sheet::beginRow);
            assertKind(Kind.ROW_OUT_OF_RANGE, sheet::endRow);
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> sheet.skipRows(1));
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> sheet.skipCells(1));
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> sheet.addRow("x"));

            Worksheet other = writer.addWorksheet("Other");
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> other.beginRowAt(0));
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> other.beginRowAt(XlsxWriter.MAX_ROWS + 1));
            assertKind(Kind.INVALID_ARGUMENT, () -> other.skipRows(-1));
            other.skipRows(XlsxWriter.MAX_ROWS);
            assertKind(Kind.ROW_OUT_OF_RANGE, () -> other.write(1));
        }
    }

    @Test
    void rejectsRowsOutOfOrder() throws IOException {
        try (XlsxWriter writer = discarding()) {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.beginRowAt(5).write(1).endRow();
            XlsxException e = assertThrows(XlsxException.class, () -> sheet.beginRowAt(5));
            assertEquals(Kind.ROW_ORDER, e.kind());
            assertEquals("rows must ascend: row 5 follows row 5", e.getMessage());
            assertKind(Kind.ROW_ORDER, () -> sheet.beginRowAt(2));
            sheet.beginRowAt(6);
        }
    }

    @Test
    void rejectsInvalidRowHeightsRangesAndFormulas() throws IOException {
        try (XlsxWriter writer = discarding()) {
            Worksheet sheet = writer.addWorksheet("S");
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.beginRow(-1));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.beginRow(410));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.beginRow(Double.NaN));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.mergeCells(1, 1, 1, 1));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.mergeCells(0, 1, 2, 2));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.mergeCells(3, 1, 2, 2));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.mergeCells(1, 3, 2, 2));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.mergeCells(1, 1, XlsxWriter.MAX_ROWS + 1, 2));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.autoFilter(1, 1, 1, XlsxWriter.MAX_COLUMNS + 1));
            assertKind(Kind.INVALID_ARGUMENT, () -> sheet.writeFormula("="));
            assertEquals(0, sheet.rowNumber());
        }
    }

    @Test
    void rejectsStylesOfAnotherWriter() throws IOException {
        try (XlsxWriter other = discarding(); XlsxWriter writer = discarding()) {
            other.addStyle(Style.builder().bold().build());
            StyleId foreign = other.addStyle(Style.builder().italic().build());
            Worksheet sheet = writer.addWorksheet("S");
            assertKind(Kind.INVALID_STYLE, () -> sheet.write("x", foreign));
            assertKind(Kind.INVALID_STYLE, () -> sheet.write(LocalDate.of(2024, 1, 1), foreign));
            assertKind(Kind.INVALID_STYLE, () -> sheet.beginRow(foreign));
            assertKind(Kind.INVALID_STYLE, () -> sheet.writeBlank(foreign));
            assertKind(Kind.INVALID_STYLE, () -> writer.addWorksheet("T", SheetOptions.builder().column(1, Column.DEFAULT.withStyle(foreign)).build()));
            assertEquals(0, sheet.columnNumber());
            assertEquals(1, writer.worksheetCount());
        }
    }

    @Test
    void rejectsInvalidOptions() {
        assertKind(Kind.INVALID_ARGUMENT, () -> XlsxOptions.builder().compressionLevel(10));
        assertKind(Kind.INVALID_ARGUMENT, () -> XlsxOptions.builder().compressionLevel(-1));
        assertKind(Kind.INVALID_ARGUMENT, () -> XlsxOptions.builder().flushThreshold(100));
        assertKind(Kind.INVALID_ARGUMENT, () -> XlsxOptions.builder().xmlBufferSize(Integer.MAX_VALUE));
        assertKind(Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().freezeRows(-1));
        assertKind(Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().freezeRows(XlsxWriter.MAX_ROWS));
        assertKind(Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().freezeColumns(XlsxWriter.MAX_COLUMNS));
        assertKind(Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().column(0, Column.DEFAULT));
        assertKind(Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().column(XlsxWriter.MAX_COLUMNS + 1, Column.DEFAULT));
        assertKind(Kind.INVALID_ARGUMENT, () -> SheetOptions.builder().columnWidths(new double[XlsxWriter.MAX_COLUMNS + 1]));
        assertKind(Kind.INVALID_ARGUMENT, () -> Column.ofWidth(256));
        assertKind(Kind.INVALID_ARGUMENT, () -> Column.ofWidth(-1));
        assertKind(Kind.INVALID_ARGUMENT, () -> CellReference.columnName(0));
        assertKind(Kind.INVALID_ARGUMENT, () -> CellReference.of(0, 1));
    }

    @Test
    void requiresAVisibleWorksheet() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxWriter writer = XlsxWriter.create(out);
        writer.addWorksheet("Hidden", SheetOptions.builder().hidden().build());
        assertKind(Kind.NO_VISIBLE_WORKSHEET, writer::finish);
        assertKind(Kind.NO_VISIBLE_WORKSHEET, writer::close);
    }

    @Test
    void buildsCellReferences() {
        assertEquals("A", CellReference.columnName(1));
        assertEquals("Z", CellReference.columnName(26));
        assertEquals("AA", CellReference.columnName(27));
        assertEquals("ZZ", CellReference.columnName(702));
        assertEquals("AAA", CellReference.columnName(703));
        assertEquals("XFD", CellReference.columnName(16384));
        assertEquals("B7", CellReference.of(7, 2));
        assertEquals("A1:C9", CellReference.range(1, 1, 9, 3));
    }
}
