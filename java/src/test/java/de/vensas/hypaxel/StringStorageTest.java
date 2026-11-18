package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StringStorageTest {
    private static final XlsxOptions SHARED = XlsxOptions.builder().stringStorage(StringStorage.SHARED).build();

    @Test
    void writesInlineStringsByDefault() {
        byte[] xlsx = Xlsx.write(writer -> writer.addWorksheet("S").write("hello").write("hello").endRow());
        Map<String, String> parts = Xlsx.parts(xlsx);
        assertFalse(parts.containsKey("xl/sharedStrings.xml"));
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains("<c t=\"inlineStr\"><is><t>hello</t></is></c>"));
        assertFalse(parts.get("[Content_Types].xml").contains("sharedStrings"));
    }

    @Test
    void deduplicatesSharedStrings() throws Exception {
        byte[] xlsx = Xlsx.write(SHARED, writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            for (int i = 0; i < 5000; i++) {
                sheet.write("Category " + (i % 7)).write("unique " + i).write(" padded & <escaped> ").endRow();
            }
            writer.addWorksheet("Second").write("Category 3").write("only here").endRow();
        });
        Map<String, String> parts = Xlsx.parts(xlsx);
        String table = parts.get("xl/sharedStrings.xml");
        assertTrue(table.contains("count=\"15002\" uniqueCount=\"5009\""));
        assertTrue(table.contains("<si><t xml:space=\"preserve\"> padded &amp; &lt;escaped&gt; </t></si>"));
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains("<c t=\"s\"><v>0</v></c>"));
        assertTrue(parts.get("[Content_Types].xml").contains("/xl/sharedStrings.xml"));
        assertTrue(parts.get("xl/_rels/workbook.xml.rels").contains("sharedStrings.xml"));

        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i < 5000; i += 499) {
                assertEquals("Category " + (i % 7), sheet.getRow(i).getCell(0).getStringCellValue());
                assertEquals("unique " + i, sheet.getRow(i).getCell(1).getStringCellValue());
                assertEquals(" padded & <escaped> ", sheet.getRow(i).getCell(2).getStringCellValue());
            }
            assertEquals("Category 3", workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue());
            assertEquals("only here", workbook.getSheetAt(1).getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    void omitsTheSharedStringTableWhenThereAreNoStrings() throws Exception {
        byte[] xlsx = Xlsx.write(SHARED, writer -> writer.addWorksheet("S").write(1).endRow());
        assertFalse(Xlsx.parts(xlsx).containsKey("xl/sharedStrings.xml"));
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            assertEquals(1.0, workbook.getSheetAt(0).getRow(0).getCell(0).getNumericCellValue());
        }
    }

    @Test
    void writesCompactCellReferencesByDefault() {
        byte[] xlsx = Xlsx.write(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.write(1).write(2).skipCells(1).write(4).write(5).endRow();
            sheet.skipCells(27).write(28).endRow();
        });
        String sheet = Xlsx.part(xlsx, "xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<row r=\"1\"><c><v>1</v></c><c><v>2</v></c><c r=\"D1\"><v>4</v></c><c><v>5</v></c></row>"));
        assertTrue(sheet.contains("<row r=\"2\"><c r=\"AB2\"><v>28</v></c></row>"));
    }

    @Test
    void writesAllCellReferencesOnRequest() throws Exception {
        XlsxOptions options = XlsxOptions.builder().cellReferences(true).build();
        byte[] xlsx = Xlsx.write(options, writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            sheet.write(1).write("two").endRow();
            sheet.skipCells(16383).write(true).endRow();
            sheet.skipCells(702).write(703).endRow();
        });
        String sheet = Xlsx.part(xlsx, "xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"A1\"><v>1</v></c><c r=\"B1\" t=\"inlineStr\">"));
        assertTrue(sheet.contains("<c r=\"XFD2\" t=\"b\">"));
        assertTrue(sheet.contains("<c r=\"AAA3\">"));
        try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
            assertTrue(workbook.getSheetAt(0).getRow(1).getCell(16383).getBooleanCellValue());
        }
    }
}
