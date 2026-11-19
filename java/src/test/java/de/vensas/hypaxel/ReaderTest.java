package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** Reading workbooks: round trips through the writer and packages from other producers. */
class ReaderTest {
    static final String ROOT_RELS = "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";

    /** Builds a package from (name, content) pairs. */
    static byte[] pkg(String... namesAndContents) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndContents.length; i += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[i]));
                zip.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** A workbook with one worksheet "S" whose XML is given, plus extra relationships and parts. */
    static byte[] singleSheet(String sheetXml, String extraRels, String... extraParts) {
        String workbook = "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";
        String rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + extraRels + "</Relationships>";
        List<String> parts = new ArrayList<>(List.of("_rels/.rels", ROOT_RELS, "xl/workbook.xml", workbook, "xl/_rels/workbook.xml.rels", rels,
                "xl/worksheets/sheet1.xml", sheetXml));
        parts.addAll(List.of(extraParts));
        return pkg(parts.toArray(String[]::new));
    }

    /** Every row of a sheet as "number: [values]" with dates in ISO 8601. */
    static List<String> dump(byte[] xlsx, String sheet) throws IOException {
        List<String> out = new ArrayList<>();
        try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet(sheet)) {
            for (Row row : rows) {
                out.add(row.toString());
            }
        }
        return out;
    }

    static String rowsError(byte[] xlsx) throws IOException {
        try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet("S")) {
            XlsxException e = assertThrows(XlsxException.class, () -> {
                while (rows.nextRow() != null) {
                    // Read until the damage is found.
                }
            });
            return e.getMessage();
        }
    }

    @Test
    void readsBackEveryValueTypeInBothStringModes() throws IOException {
        LocalDateTime dt = LocalDateTime.of(2024, 3, 15, 13, 45, 30);
        String tricky = "<&>\"' _x0041_ \u0001\ttab\nline 東京 😀";
        for (StringStorage storage : StringStorage.values()) {
            byte[] xlsx = Xlsx.write(XlsxOptions.builder().stringStorage(storage).build(), writer -> {
                Worksheet s = writer.addWorksheet("Data");
                s.addRow("text", 42, -1_234_567_890_123L, 3.25, true, false);
                s.addRow(dt, dt.toLocalDate(), LocalTime.of(6, 0), Duration.ofHours(36));
                s.addRow(Double.NaN, null, "after-gap", tricky);
                s.addRow(Cell.formula("SUM(B1:D1)"), Cell.blank(StyleId.DEFAULT), "");
            });

            try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet("Data")) {
                assertEquals(storage == StringStorage.SHARED, reader.sharedStringCount() > 0);
                Row row = rows.nextRow();
                assertEquals(1, row.number());
                assertEquals(Value.of("text"), row.getValue(1));
                assertEquals(Value.of(42), row.getValue(2));
                assertEquals(Value.of(-1_234_567_890_123.0), row.getValue(3));
                assertEquals(3.25, row.getNumber(4));
                assertEquals(Value.TRUE, row.getValue(5));
                assertEquals(Boolean.FALSE, row.getBoolean(6));
                assertEquals(Value.EMPTY, row.getValue(7));
                assertEquals(CellType.EMPTY, row.type(7));
                assertEquals(storage == StringStorage.SHARED ? 0 : -1, row.sharedStringIndex(1));

                row = rows.nextRow();
                assertEquals(DateKind.DATE_TIME, row.getDateTime(1).kind());
                assertEquals(dt, row.getLocalDateTime(1));
                assertEquals(dt, row.getObject(1));
                assertEquals(DateKind.DATE, row.getDateTime(2).kind());
                assertEquals(dt.toLocalDate(), row.getDateTime(2).toLocalDate());
                assertEquals(DateKind.TIME, row.getDateTime(3).kind());
                assertEquals(LocalTime.of(6, 0), row.getObject(3));
                assertEquals(DateKind.DURATION, row.getDateTime(4).kind());
                assertEquals(Duration.ofHours(36), row.getObject(4));
                assertEquals(1.5, row.getNumber(4));
                assertEquals(CellType.DATE_TIME, row.type(4));

                row = rows.nextRow();
                assertEquals(Value.error("#NUM!"), row.getValue(1));
                assertEquals("#NUM!", row.getError(1));
                assertEquals(CellType.ERROR, row.type(1));
                assertEquals(Value.EMPTY, row.getValue(2));
                assertEquals("after-gap", row.getString(3));
                assertEquals(tricky, row.getString(4));
                assertEquals(List.of(1, 3, 4), row.cells().stream().map(CellValue::column).toList());

                // A formula without a cached value and a styled blank have no value; "" is a string.
                row = rows.nextRow();
                assertEquals(4, row.number());
                assertEquals(List.of(new CellValue(3, Value.of(""))), row.cells());

                assertNull(rows.nextRow());
                assertNull(rows.nextRow());
            }
        }
    }

    @Test
    void roundTripsDatesInBothDateSystems() throws IOException {
        List<LocalDate> dates = List.of(LocalDate.of(1900, 1, 1), LocalDate.of(1900, 2, 28), LocalDate.of(1900, 3, 1),
                LocalDate.of(1904, 1, 1), LocalDate.of(1999, 12, 31), LocalDate.of(2024, 2, 29), LocalDate.of(9999, 12, 31));
        for (boolean date1904 : new boolean[] {false, true}) {
            byte[] xlsx = Xlsx.write(XlsxOptions.builder().date1904(date1904).build(), writer -> {
                Worksheet s = writer.addWorksheet("D");
                for (LocalDate d : dates) {
                    s.addRow(d, d.atTime(23, 59, 59, 123_000_000));
                }
            });
            try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet(0)) {
                assertEquals(date1904, reader.isDate1904());
                for (LocalDate d : dates) {
                    Row row = rows.nextRow();
                    if (date1904 && d.getYear() < 1904) {
                        assertEquals(d.toString(), row.getString(1), "dates before 1904 are written as text");
                        continue;
                    }
                    assertEquals(d, row.getDateTime(1).toLocalDate(), d + " 1904=" + date1904);
                    assertEquals(date1904, row.getDateTime(1).isDate1904());
                    assertEquals(d.atTime(23, 59, 59, 123_000_000), row.getLocalDateTime(2));
                }
            }
        }
    }

    @Test
    void listsSheetsWithVisibilityAndReadsEach() throws IOException {
        byte[] xlsx = Xlsx.write(writer -> {
            writer.addWorksheet("First").addRow("a", 1);
            writer.addWorksheet("Secret", SheetOptions.builder().hidden().build()).addRow("b", 2);
            writer.addWorksheet("Q&A <1>").addRow("c", 3);
        });
        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            assertEquals(List.of("First", "Secret", "Q&A <1>"), reader.sheetNames());
            assertEquals(SheetVisibility.HIDDEN, reader.sheets().get(1).visibility());
            assertEquals(new SheetInfo("First", SheetVisibility.VISIBLE, SheetKind.WORKSHEET), reader.sheets().get(0));
            assertEquals(XlsxException.Kind.SHEET_NOT_FOUND, assertThrows(XlsxException.class, () -> reader.worksheet("Missing")).kind());
            assertEquals(XlsxException.Kind.SHEET_NOT_FOUND, assertThrows(XlsxException.class, () -> reader.worksheet(3)).kind());
            assertEquals(XlsxException.Kind.SHEET_NOT_FOUND, assertThrows(XlsxException.class, () -> reader.worksheet(-1)).kind());
        }
        assertEquals(List.of("1: [c, 3]"), dump(xlsx, "q&a <1>"));
        assertEquals(List.of("1: [b, 2]"), dump(xlsx, "Secret"));
    }

    @Test
    void readsSeveralSheetsAtOnceAndFromOtherThreads() throws Exception {
        byte[] xlsx = Xlsx.write(XlsxOptions.builder().stringStorage(StringStorage.SHARED).build(), writer -> {
            Worksheet a = writer.addWorksheet("A");
            for (int i = 0; i < 5_000; i++) {
                a.addRow("in a", i);
            }
            Worksheet b = writer.addWorksheet("B");
            for (int i = 0; i < 5_000; i++) {
                b.addRow("in b", -i);
            }
        });
        try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader a = reader.worksheet("A"); SheetReader b = reader.worksheet("B")) {
            double[] other = new double[1];
            Thread thread = new Thread(() -> {
                try (SheetReader rows = reader.worksheet("B")) {
                    for (Row row : rows) {
                        other[0] += row.getNumber(2);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            thread.start();
            double sumA = 0;
            double sumB = 0;
            for (int i = 0; i < 5_000; i++) {
                Row rowA = a.nextRow();
                assertEquals("in a", rowA.getString(1));
                sumA += rowA.getNumber(2);
                Row rowB = b.nextRow();
                assertEquals("in b", rowB.getString(1));
                sumB += rowB.getNumber(2);
            }
            thread.join();
            assertEquals(12_497_500, sumA);
            assertEquals(-12_497_500, sumB);
            assertEquals(-12_497_500, other[0]);
        }
    }

    @Test
    void skipsMissingAndValuelessRowsAndReportsRowNumbers() throws IOException {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet s = writer.addWorksheet("S");
            s.addRow("first");
            s.beginRowAt(5, 30.0, StyleId.DEFAULT).endRow();
            s.beginRowAt(1_000).skipCells(2).write("C1000").endRow();
            s.beginRowAt(XlsxWriter.MAX_ROWS).skipCells(XlsxWriter.MAX_COLUMNS - 1).write("last").endRow();
        });
        List<String> seen = new ArrayList<>();
        try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet("S")) {
            for (Row row = rows.nextRow(); row != null; row = rows.nextRow()) {
                seen.add(row.number() + ":" + row.lastColumn() + ":" + row.values().size() + ":" + row.cellCount());
            }
        }
        assertEquals(List.of("1:1:1:1", "1000:3:3:1", "1048576:16384:16384:1"), seen);
    }

    @Test
    void readsWorkbooksWrittenByApachePoi() throws IOException {
        byte[] xlsx;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFCellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("Report");
            sheet.createRow(0).createCell(0).setCellValue("Name");
            sheet.getRow(0).createCell(1).setCellValue("Amount");
            sheet.createRow(1).createCell(0).setCellValue("Widget");
            sheet.getRow(1).createCell(1).setCellValue(12.5);
            sheet.createRow(2).createCell(3).setCellValue(true);
            org.apache.poi.xssf.usermodel.XSSFCell when = sheet.createRow(3).createCell(0);
            when.setCellValue(LocalDateTime.of(2024, 5, 1, 8, 30));
            when.setCellStyle(date);
            org.apache.poi.xssf.usermodel.XSSFCell formula = sheet.createRow(4).createCell(0);
            formula.setCellFormula("1+1");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(formula);
            org.apache.poi.xssf.usermodel.XSSFCell text = sheet.createRow(5).createCell(2);
            text.setCellFormula("\"Wid\"&\"get\"");
            workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(text);
            sheet.createRow(6).createCell(0).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());
            workbook.createSheet("Second");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            xlsx = out.toByteArray();
        }
        assertEquals(List.of("1: [Name, Amount]", "2: [Widget, 12.5]", "3: [, , , TRUE]", "4: [2024-05-01T08:30:00]", "5: [2]",
                "6: [, , Widget]", "7: [#N/A]"), dump(xlsx, "Report"));
        assertEquals(List.of(), dump(xlsx, "Second"));
    }

    @Test
    void readsWorkbooksWrittenByPoiStreaming() throws IOException {
        byte[] xlsx;
        try (org.apache.poi.xssf.streaming.SXSSFWorkbook workbook = new org.apache.poi.xssf.streaming.SXSSFWorkbook(10)) {
            org.apache.poi.xssf.streaming.SXSSFSheet sheet = workbook.createSheet("Big");
            for (int i = 0; i < 100; i++) {
                org.apache.poi.xssf.streaming.SXSSFRow row = sheet.createRow(i);
                row.createCell(0).setCellValue("row " + i);
                row.createCell(1).setCellValue(i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            xlsx = out.toByteArray();
        }
        List<String> rows = dump(xlsx, "Big");
        assertEquals(100, rows.size());
        assertEquals("100: [row 99, 99]", rows.get(99));
    }

    @Test
    void handlesXmlVariantsFromOtherProducers() throws IOException {
        // Prefixed namespaces, no cell or row references, out-of-order and duplicate cells, inline
        // rich text with phonetic runs, ISO dates, CDATA, comments, entities and CRLF line endings.
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<x:dimension ref=\"A1:C3\"/><x:sheetViews><x:sheetView workbookViewId=\"0\"/></x:sheetViews>"
                + "<x:sheetData>"
                + "<x:row><x:c><x:v>1</x:v></x:c><x:c t=\"str\"><x:f>\"a\"&amp;\"b\"</x:f><x:v>ab</x:v></x:c><x:c t=\"b\"><x:v>0</x:v></x:c></x:row>"
                + "<x:row r=\"3\" spans=\"1:3\"><x:c r=\"C3\"><x:v>3</x:v></x:c><x:c r=\"A3\"><x:v>1</x:v></x:c><x:c r=\"C3\"><x:v>4</x:v></x:c></x:row>"
                + "<!-- a comment --><x:row><x:c t=\"inlineStr\"><x:is><x:r><x:t>Tok</x:t></x:r><x:r><x:rPr><x:b/></x:rPr><x:t xml:space=\"preserve\">yo &amp; </x:t></x:r><x:rPh><x:t>トウキョウ</x:t></x:rPh></x:is></x:c>"
                + "<x:c t=\"d\"><x:v>2024-05-01T08:30:00</x:v></x:c><x:c t=\"inlineStr\"><x:is><x:t><![CDATA[<cdata> & _x0041_]]></x:t></x:is></x:c>"
                + "<x:c t=\"inlineStr\"><x:is><x:t>line1\r\nline2</x:t></x:is></x:c><x:c t=\"e\"><x:v>#N/A</x:v></x:c><x:c t=\"n\"><x:v> 1.5E3 </x:v></x:c></x:row>"
                + "</x:sheetData><x:mergeCells count=\"0\"/></x:worksheet>";
        assertEquals(List.of("1: [1, ab, FALSE]", "3: [1, , 4]", "4: [Tokyo & , 2024-05-01T08:30:00, <cdata> & A, line1\nline2, #N/A, 1500]"),
                dump(singleSheet(sheet, ""), "S"));
    }

    @Test
    void handlesUnusualShapesOfCommonCells() throws IOException {
        // Shapes the fast path does not take must give the same values as the shapes it takes.
        String sheet = "<worksheet><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" s='0' t='s'><v>0</v></c><c r=\"B1\" t=\"inlineStr\"><is><t xml:space=\"preserve\"> a&lt;b </t></is></c>"
                + "<c r=\"C1\"><v>2&#46;5</v></c><c r=\"D1\" t=\"s\"><v>1</v></c><c r=\"E1\" t=\"str\"><v>x_x0042_</v></c><c r=\"F1\" t=\"s\" ></c><c r=\"G1\"/>"
                + "<c r=\"H1\" t=\"inlineStr\"><is><t/></is></c><c r=\"I1\" t=\"s\"><is><t>ignored</t></is></c><c r=\"J1\"><v></v></c>"
                + "<c r=\"K1\" t=\"inlineStr\"><v>v as text</v></c><c\tr=\"L1\"\n><v>\n7\n</v></c><c r=\"M1\" t=\"d\"><v>not a date</v></c>"
                + "<c r=\"N1\" t=\"d\"><v>06:00:00</v></c><c r=\"O1\" t=\"d\"><v>2024-05-01</v></c><c r=\"P1\" t='b'><v>true</v></c></row>"
                + "<row r=\"2\"><c r=\"B2\"><v>2</v></c><c><v>3</v></c><c r=\"A2\"><v>1</v></c></row>"
                + "</sheetData></worksheet>";
        String sst = "<sst><si><t>shared</t></si><si><r><t>ri</t></r><r><t>ch</t></r></si></sst>";
        String rels = "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>";
        byte[] xlsx = singleSheet(sheet, rels, "xl/sharedStrings.xml", sst);
        assertEquals(List.of("1: [shared,  a<b , 2.5, rich, xB, , , , , , v as text, 7, not a date, 06:00:00, 2024-05-01, TRUE]", "2: [1, 2, 3]"),
                dump(xlsx, "S"));
        try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet(0)) {
            Row row = rows.nextRow();
            assertEquals(CellType.STRING, row.type(8));
            assertEquals("", row.getString(8));
            assertEquals(1, row.sharedStringIndex(4));
            assertEquals(DateKind.TIME, row.getDateTime(14).kind());
            assertEquals(DateKind.DATE, row.getDateTime(15).kind());
        }
    }

    @Test
    void appliesCustomAndBuiltinDateFormats() throws IOException {
        String styles = "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><numFmts count=\"1\"><numFmt numFmtId=\"200\" formatCode=\"[$-409]d\\-mmm\\-yy;@\"/></numFmts>"
                + "<cellXfs count=\"4\"><xf numFmtId=\"0\"/><xf numFmtId=\"200\"/><xf numFmtId=\"14\"/><xf numFmtId=\"4\"/></cellXfs></styleSheet>";
        String sheet = "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" s=\"1\"><v>45413</v></c><c r=\"B1\" s=\"2\"><v>45413</v></c>"
                + "<c r=\"C1\" s=\"3\"><v>45413</v></c><c r=\"D1\" s=\"99\"><v>1</v></c></row></sheetData></worksheet>";
        String rels = "<Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"/xl/styles.xml\"/>";
        assertEquals(List.of("1: [2024-05-01, 2024-05-01, 45413, 1]"), dump(singleSheet(sheet, rels, "xl/styles.xml", styles), "S"));
    }

    @Test
    void acceptsStrictOpenXmlAndUnusualPartNames() throws IOException {
        String root = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" "
                + "Type=\"http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument\" Target=\"/Book/WB.xml\"/></Relationships>";
        String workbook = "<workbook xmlns=\"http://purl.oclc.org/ooxml/spreadsheetml/main\" xmlns:r=\"http://purl.oclc.org/ooxml/officeDocument/relationships\">"
                + "<workbookPr date1904=\"true\"/><sheets><sheet name=\"Strict\" sheetId=\"1\" state=\"veryHidden\" r:id=\"rIdA\"/>"
                + "<sheet name=\"Chart\" sheetId=\"2\" r:id=\"rIdC\"/><sheet name=\"Macro\" sheetId=\"3\" r:id=\"rIdM\"/><sheet name=\"Lost\" sheetId=\"4\"/></sheets></workbook>";
        String rels = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdA\" Type=\"http://purl.oclc.org/ooxml/officeDocument/relationships/worksheet\" Target=\"sheets/../sheets/One.xml\"/>"
                + "<Relationship Id=\"rIdC\" Type=\"http://purl.oclc.org/ooxml/officeDocument/relationships/chartsheet\" Target=\"charts/c.xml\"/>"
                + "<Relationship Id=\"rIdM\" Type=\"http://schemas.microsoft.com/office/2006/relationships/xlMacrosheet\" Target=\"macro.xml\"/>"
                + "<Relationship Id=\"rIdS\" Type=\"http://purl.oclc.org/ooxml/officeDocument/relationships/sharedStrings\" Target=\"strings.xml\"/>"
                + "<Relationship Id=\"rIdX\" Type=\"http://x/hyperlink\" Target=\"https://example.com\" TargetMode=\"External\"/></Relationships>";
        String sst = "<sst xmlns=\"http://purl.oclc.org/ooxml/spreadsheetml/main\"><si><t>strict</t></si></sst>";
        String sheet = "<worksheet xmlns=\"http://purl.oclc.org/ooxml/spreadsheetml/main\"><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                + "<c r=\"B1\" t=\"d\"><v>1904-01-02</v></c></row></sheetData></worksheet>";
        byte[] xlsx = pkg("_rels/.rels", root, "Book/WB.xml", workbook, "Book/_rels/WB.xml.rels", rels, "Book/sheets/One.xml", sheet,
                "Book/strings.xml", sst, "Book/charts/c.xml", "<chartsheet/>");
        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            assertTrue(reader.isDate1904());
            assertEquals(SheetVisibility.VERY_HIDDEN, reader.sheets().get(0).visibility());
            assertEquals(SheetKind.CHARTSHEET, reader.sheets().get(1).kind());
            assertEquals(SheetKind.OTHER, reader.sheets().get(2).kind());
            try (SheetReader rows = reader.worksheet("Strict")) {
                Row row = rows.nextRow();
                assertEquals("strict", row.getString(1));
                assertEquals(LocalDate.of(1904, 1, 2), row.getDateTime(2).toLocalDate());
            }
            XlsxException chart = assertThrows(XlsxException.class, () -> reader.worksheet("Chart"));
            assertEquals(XlsxException.Kind.UNSUPPORTED, chart.kind());
            assertTrue(chart.getMessage().contains("chart sheet"), chart.getMessage());
            XlsxException macro = assertThrows(XlsxException.class, () -> reader.worksheet("Macro"));
            assertEquals("Book/macro.xml", macro.part());
            XlsxException lost = assertThrows(XlsxException.class, () -> reader.worksheet("Lost"));
            assertTrue(lost.getMessage().contains("no relationship"), lost.getMessage());
        }
    }

    @Test
    void fallsBackToTheDefaultWorkbookPathAndSkipsChartData() throws IOException {
        String workbook = "<workbook><sheets><sheet name=\"S\" r:id=\"rId1\"/></sheets></workbook>";
        String rels = "<Relationships><Relationship Id=\"rId1\" Type=\"x/worksheet\" Target=\"worksheets/s.xml\"/></Relationships>";
        String sheet = "<worksheet><sheetPr><tabColor rgb=\"FF0000\"/></sheetPr><sheetData/></worksheet>";
        byte[] xlsx = pkg("xl/workbook.xml", workbook, "xl/_rels/workbook.xml.rels", rels, "xl/worksheets/s.xml", sheet);
        assertEquals(List.of(), dump(xlsx, "S"));
        byte[] noData = pkg("xl/workbook.xml", workbook, "xl/_rels/workbook.xml.rels", rels, "xl/worksheets/s.xml", "<worksheet><cols/></worksheet>");
        assertEquals(List.of(), dump(noData, "S"));
    }

    @Test
    void reportsClearErrorsForWrongInputs() throws IOException {
        XlsxException csv = assertThrows(XlsxException.class, () -> XlsxReader.open("id,name\n1,Alice\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(XlsxException.Kind.INVALID_FILE, csv.kind());

        byte[] ole = new byte[512];
        System.arraycopy(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, 0, ole, 0, 8);
        XlsxException xls = assertThrows(XlsxException.class, () -> XlsxReader.open(ole));
        assertTrue(xls.getMessage().contains("legacy .xls"), xls.getMessage());

        byte[] docx = pkg("_rels/.rels", ROOT_RELS.replace("xl/workbook.xml", "word/document.xml"), "word/document.xml", "<w/>");
        XlsxException word = assertThrows(XlsxException.class, () -> XlsxReader.open(docx));
        assertEquals(XlsxException.Kind.INVALID_FILE, word.kind());
        assertTrue(word.getMessage().contains("Word or PowerPoint"), word.getMessage());
        byte[] noWorkbook = pkg("_rels/.rels", ROOT_RELS, "other.xml", "<x/>");
        assertTrue(assertThrows(XlsxException.class, () -> XlsxReader.open(noWorkbook)).getMessage().contains("Word or PowerPoint"));

        byte[] badSst = singleSheet("<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>7</v></c></row></sheetData></worksheet>", "");
        try (XlsxReader reader = XlsxReader.open(badSst); SheetReader rows = reader.worksheet("S")) {
            XlsxException e = assertThrows(XlsxException.class, rows::nextRow);
            assertEquals(XlsxException.Kind.MALFORMED, e.kind());
            assertEquals("xl/worksheets/sheet1.xml", e.part());
            assertTrue(e.getMessage().contains("row 1 has an invalid shared string index \"7\""), e.getMessage());
            assertNull(rows.nextRow(), "a failed reader stays at its end");
        }

        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>1,5</v></c></row></sheetData></worksheet>", ""))
                .contains("invalid number \"1,5\""));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"2\"><c t=\"b\"><v>yes</v></c></row></sheetData></worksheet>", ""))
                .contains("row 2 has an invalid boolean \"yes\""));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"1\"><c r=\"1A\"><v>1</v></c></row></sheetData></worksheet>", ""))
                .contains("invalid reference"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"1\"><c r=\"XFE1\"><v>1</v></c></row></sheetData></worksheet>", ""))
                .contains("invalid reference"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"1\"><c t=\"x\"><v>1</v></c></row></sheetData></worksheet>", ""))
                .contains("unknown type"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"x\"/></sheetData></worksheet>", "")).contains("invalid row number"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"1048577\"/></sheetData></worksheet>", "")).contains("outside"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row><c><v>1&bogus;</v></c></row></sheetData></worksheet>", ""))
                .contains("unknown entity"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row><c t=\"inlineStr\"><is><t>&#xD800;</t></is></c></row></sheetData></worksheet>", ""))
                .contains("unknown entity"));
        assertTrue(rowsError(singleSheet("<!DOCTYPE lol [<!ENTITY a \"aaaa\">]><worksheet><sheetData/></worksheet>", "")).contains("document type"));
        assertTrue(rowsError(singleSheet("<worksheet><sheetData><row r=\"1\"><c r=\"A1\"><v>1</v></c>", "")).contains("unexpected end"));
        assertTrue(rowsError(singleSheet("<worksheet></worksheet></worksheet>", "")).contains("unbalanced end tag"));
    }

    @Test
    void readsWideRowsOfEveryType() throws IOException {
        for (StringStorage storage : StringStorage.values()) {
            byte[] xlsx = Xlsx.write(XlsxOptions.builder().stringStorage(storage).build(), writer -> {
                Worksheet s = writer.addWorksheet("Wide");
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 300; column++) {
                        switch (column % 5) {
                            case 0 -> s.write("t" + column);
                            case 1 -> s.write(column % 2 == 0);
                            case 2 -> s.write(column * 1.5);
                            case 3 -> s.write(LocalDate.of(2024, 1, 1).plusDays(column));
                            default -> s.write(Double.NaN);
                        }
                    }
                    s.endRow();
                }
            });
            try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet(0)) {
                for (Row row : rows) {
                    assertEquals(300, row.cellCount());
                    for (int column = 1; column <= 300; column++) {
                        int c = column - 1;
                        Object expected = switch (c % 5) {
                            case 0 -> "t" + c;
                            case 1 -> c % 2 == 0;
                            case 2 -> c * 1.5;
                            case 3 -> LocalDate.of(2024, 1, 1).plusDays(c).atStartOfDay();
                            default -> "#NUM!";
                        };
                        assertEquals(expected, row.getObject(column), storage + " column " + column);
                    }
                }
            }
        }
    }

    @Test
    void rejectsRowsWithTooManyCells() throws IOException {
        StringBuilder sheet = new StringBuilder("<worksheet><sheetData><row r=\"1\">");
        for (int i = 0; i <= XlsxWriter.MAX_COLUMNS; i++) {
            sheet.append("<c r=\"A1\"><v>").append(i).append("</v></c>");
        }
        sheet.append("</row></sheetData></worksheet>");
        assertTrue(rowsError(singleSheet(sheet.toString(), "")).contains("more than 16384 cells"));
    }

    @Test
    void enforcesTheSharedStringLimit() throws IOException {
        byte[] xlsx = Xlsx.write(XlsxOptions.builder().stringStorage(StringStorage.SHARED).build(), writer -> {
            Worksheet s = writer.addWorksheet("S");
            for (int i = 0; i < 1_000; i++) {
                s.addRow("unique string number " + i);
            }
        });
        ReadOptions small = ReadOptions.builder().maxSharedStringsBytes(1_000).build();
        try (XlsxReader reader = XlsxReader.open(xlsx, small)) {
            XlsxException e = assertThrows(XlsxException.class, () -> reader.worksheet("S"));
            assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, e.kind());
            assertTrue(e.getMessage().contains("maxSharedStringsBytes"), e.getMessage());
        }
        String rich = "<sst><si><r><t>0123456789</t></r><r><t>0123456789</t></r></si></sst>";
        String rels = "<Relationship Id=\"rId2\" Type=\"x/sharedStrings\" Target=\"sharedStrings.xml\"/>";
        byte[] richXlsx = singleSheet("<worksheet/>", rels, "xl/sharedStrings.xml", rich);
        try (XlsxReader reader = XlsxReader.open(richXlsx, ReadOptions.builder().maxSharedStringsBytes(15).build())) {
            assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, assertThrows(XlsxException.class, reader::sharedStringCount).kind());
        }
        try (XlsxReader reader = XlsxReader.open(xlsx)) {
            assertEquals(1_000, reader.sharedStringCount());
        }
        assertEquals(XlsxException.Kind.INVALID_ARGUMENT, assertThrows(XlsxException.class, () -> ReadOptions.builder().maxSharedStringsBytes(-1)).kind());
        assertEquals(1L << 30, ReadOptions.DEFAULT.maxSharedStringsBytes());
    }

    @Test
    void streamsLargeSheetsWithBoundedBuffers() throws IOException {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet s = writer.addWorksheet("Big");
            for (int i = 0; i < 200_000; i++) {
                s.write(i).write("constant text").write(i * 0.5).write(i % 2 == 0).endRow();
            }
        });
        int count = 0;
        double sum = 0;
        double expected = 0;
        try (XlsxReader reader = XlsxReader.open(xlsx); SheetReader rows = reader.worksheet("Big")) {
            for (Row row = rows.nextRow(); row != null; row = rows.nextRow()) {
                assertEquals(count + 1, row.number());
                sum += row.getNumber(3);
                expected += count * 0.5;
                count++;
            }
        }
        assertEquals(200_000, count);
        assertEquals(expected, sum);
    }

    @Test
    void damagedFilesFailWithoutUnexpectedExceptions() throws IOException {
        byte[] xlsx = Xlsx.write(XlsxOptions.builder().stringStorage(StringStorage.SHARED).compressionLevel(0).build(), writer -> {
            StyleId date = writer.addStyle(Style.builder().numberFormat("yyyy-mm-dd").build());
            Worksheet s = writer.addWorksheet("S");
            for (int i = 0; i < 50; i++) {
                s.addRow("s" + i, i, Cell.styled(45_000 + i, date), i % 3 == 0, "inline <&> " + i);
            }
        });
        // Deterministic pseudo-random mutations of an uncompressed package, so that the XML parser
        // (not only the ZIP CRC check) sees damaged input.
        long[] state = {0x2545_f491_4f6c_dd1dL};
        java.util.function.LongSupplier next = () -> {
            long x = state[0];
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state[0] = x;
            return x & Long.MAX_VALUE;
        };
        int opened = 0;
        for (int round = 0; round < 3_000; round++) {
            byte[] damaged = xlsx.clone();
            for (long n = 1 + next.getAsLong() % 4; n > 0; n--) {
                damaged[(int) (next.getAsLong() % damaged.length)] = (byte) next.getAsLong();
            }
            if (next.getAsLong() % 4 == 0) {
                damaged = java.util.Arrays.copyOf(damaged, (int) (next.getAsLong() % damaged.length));
            }
            try (XlsxReader reader = XlsxReader.open(damaged); SheetReader rows = reader.worksheet(0)) {
                opened++;
                for (Row row : rows) {
                    for (CellValue cell : row.cells()) {
                        assertFalse(cell.value().toString() == null);
                    }
                    row.toList();
                }
            } catch (XlsxException e) {
                assertFalse(e.getMessage().isEmpty());
            } catch (RuntimeException e) {
                fail("round " + round + ": unexpected " + e, e);
            }
        }
        assertTrue(opened > 100, "most mutations leave the package readable: " + opened);
    }

    @Test
    void convenienceApis() throws IOException {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet s = writer.addWorksheet("S");
            s.addRow("a", 1.5, true, LocalDate.of(2024, 5, 1));
            s.addRow(null, null, "c");
            s.addRow(new BigDecimal("2.25"));
        });
        try (XlsxReader reader = XlsxReader.open(new java.io.ByteArrayInputStream(xlsx))) {
            try (java.util.stream.Stream<Row> stream = reader.worksheet(0).stream()) {
                List<Row> rows = stream.toList();
                assertEquals(3, rows.size());
                assertEquals(List.of("a", 1.5, true, LocalDateTime.of(2024, 5, 1, 0, 0)), rows.get(0).toList());
                assertEquals(java.util.Arrays.asList(null, null, "c"), rows.get(1).toList());
                assertEquals(List.of(Value.EMPTY, Value.EMPTY, Value.of("c")), rows.get(1).values());
                assertEquals("2: [, , c]", rows.get(1).toString());
                assertEquals(2.25, rows.get(2).getNumber(1));
            }
            try (SheetReader rows = reader.worksheet("S")) {
                Row row = rows.nextRow();
                assertEquals(3, row.column(2));
                assertThrows(IndexOutOfBoundsException.class, () -> row.column(4));
                assertEquals("1.5", row.getText(2));
                assertEquals("TRUE", row.getText(3));
                assertEquals("2024-05-01", row.getText(4));
                assertEquals("", row.getText(9));
                assertTrue(Double.isNaN(row.getNumber(1)));
                assertNull(row.getString(2));
                assertNull(row.getBoolean(1));
                assertNull(row.getDateTime(1));
                assertNull(row.getLocalDateTime(1));
                assertNull(row.getError(1));
                assertNull(row.getObject(9));
                assertEquals(-1, row.sharedStringIndex(9));
                assertEquals(CellType.STRING, row.type(1));
                assertEquals(CellType.NUMBER, row.type(2));
                assertEquals(CellType.BOOLEAN, row.type(3));
                Row copy = row.copy();
                rows.nextRow();
                assertEquals("a", copy.getString(1));
                assertEquals(1, copy.number());
                assertEquals(rows.sheet(), reader.sheets().get(0));
                java.util.Iterator<Row> it = rows.iterator();
                assertTrue(it.hasNext());
                assertEquals(3, it.next().number());
                assertFalse(it.hasNext());
                assertThrows(java.util.NoSuchElementException.class, it::next);
            }
        }
        java.nio.file.Path file = java.nio.file.Files.createTempFile("hypaxel", ".xlsx");
        try {
            java.nio.file.Files.write(file, xlsx);
            try (XlsxReader reader = XlsxReader.open(file); SheetReader rows = reader.worksheet(0)) {
                assertEquals("a", rows.nextRow().getString(1));
            }
            try (XlsxReader reader = XlsxReader.open(java.nio.file.Files.newByteChannel(file)); SheetReader rows = reader.worksheet(0)) {
                assertEquals("a", rows.nextRow().getString(1));
            }
            XlsxReader reader = XlsxReader.open(file, ReadOptions.DEFAULT);
            reader.close();
            reader.close();
            assertEquals(XlsxException.Kind.INVALID_STATE, assertThrows(XlsxException.class, () -> reader.worksheet(0)).kind());
        } finally {
            java.nio.file.Files.delete(file);
        }
        try (XlsxReader reader = XlsxReader.open(
                new org.apache.commons.compress.utils.SeekableInMemoryByteChannel(xlsx), ReadOptions.DEFAULT)) {
            assertEquals(List.of("S"), reader.sheetNames());
        }
    }

    @Test
    void valuesAndDates() {
        ExcelDateTime date = new ExcelDateTime(45_413.75, DateKind.DATE_TIME, false);
        assertEquals("2024-05-01T18:00:00", date.toString());
        assertEquals(LocalDateTime.of(2024, 5, 1, 18, 0), date.toObject());
        assertEquals(LocalTime.of(18, 0), date.toLocalTime());
        assertEquals("2024-05-01T12:00:00.500", new ExcelDateTime(45_413.5 + 0.5 / 86_400, DateKind.DATE_TIME, false).toString());
        assertEquals("1900-01-01", new ExcelDateTime(1, DateKind.DATE, false).toString());
        assertEquals("1900-02-28", new ExcelDateTime(59, DateKind.DATE, false).toString());
        assertEquals("60", new ExcelDateTime(60, DateKind.DATE, false).toString());
        assertNull(new ExcelDateTime(60, DateKind.DATE, false).toLocalDate());
        assertNull(new ExcelDateTime(60, DateKind.DATE, false).toLocalTime());
        assertEquals(60.0, new ExcelDateTime(60, DateKind.DATE, false).toObject());
        assertEquals("1900-03-01", new ExcelDateTime(61, DateKind.DATE, false).toString());
        assertEquals("06:00:00", new ExcelDateTime(0.25, DateKind.TIME, false).toString());
        assertEquals("1900-01-01T06:00:00", new ExcelDateTime(1.25, DateKind.TIME, false).toString());
        assertEquals(LocalDateTime.of(1900, 1, 1, 6, 0), new ExcelDateTime(1.25, DateKind.TIME, false).toObject());
        assertEquals("1.5", new ExcelDateTime(1.5, DateKind.DURATION, false).toString());
        assertEquals(Duration.ofHours(-36), new ExcelDateTime(-1.5, DateKind.DURATION, false).toDuration());
        assertNull(new ExcelDateTime(1e9, DateKind.DURATION, false).toDuration());
        assertEquals(1e9, new ExcelDateTime(1e9, DateKind.DURATION, false).toObject());
        assertEquals("-1", new ExcelDateTime(-1, DateKind.DATE, false).toString());
        assertEquals("1904-01-01", new ExcelDateTime(0, DateKind.DATE, true).toString());
        assertEquals("2024-05-01T23:59:59.999", new ExcelDateTime(45_413 + 86_399.999 / 86_400, DateKind.DATE_TIME, false).toString());
        assertEquals(LocalDate.of(2024, 5, 2), new ExcelDateTime(45_413.999_999_999, DateKind.DATE, false).toLocalDate());
        assertEquals(date, new ExcelDateTime(45_413.75, DateKind.DATE_TIME, false));
        assertEquals(date.hashCode(), new ExcelDateTime(45_413.75, DateKind.DATE_TIME, false).hashCode());
        assertFalse(date.equals(new ExcelDateTime(45_413.75, DateKind.DATE_TIME, true)));

        assertEquals("", Value.EMPTY.toString());
        assertTrue(Value.EMPTY.isEmpty());
        assertNull(Value.EMPTY.toObject());
        assertEquals("1500", Value.of(1500).toString());
        assertEquals("0.1", Value.of(0.1).toString());
        assertEquals("FALSE", Value.FALSE.toString());
        assertEquals(Value.TRUE, Value.of(true));
        assertEquals(Boolean.TRUE, Value.TRUE.asBoolean());
        assertEquals(Boolean.TRUE, Value.TRUE.toObject());
        assertNull(Value.TRUE.asString());
        assertNull(Value.of("x").asNumber());
        assertNull(Value.of("x").asBoolean());
        assertNull(Value.of("x").asError());
        assertEquals("x", Value.of("x").toObject());
        assertEquals(2.0, Value.of(2).asNumber());
        assertEquals(2.0, Value.of(2).toObject());
        assertEquals(45_413.75, Value.of(date).asNumber());
        assertEquals(date, Value.of(date).asDateTime());
        assertEquals(CellType.DATE_TIME, Value.of(date).type());
        assertEquals("2024-05-01T18:00:00", Value.of(date).toString());
        assertEquals("#N/A", Value.error("#N/A").asError());
        assertEquals("#N/A", Value.error("#N/A").toString());
        assertFalse(Value.error("#N/A").equals(Value.of("#N/A")));
        assertEquals(Value.of("a").hashCode(), Value.of("a").hashCode());
        assertThrows(NullPointerException.class, () -> Value.of((String) null));
        assertThrows(NullPointerException.class, () -> Value.of((ExcelDateTime) null));
    }
}
