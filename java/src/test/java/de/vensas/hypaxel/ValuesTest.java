package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ValuesTest {
    private static final double SECOND = 1.0 / 86_400;

    @Test
    void writesNumbersBooleansAndStrings() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            Worksheet sheet = writer.addWorksheet("Values");
            sheet.write("text").write(42).write(-7L).write(Long.MAX_VALUE).write(1.5).write(0.1f).write(true).write(false).write('x').endRow();
            sheet.write(new BigDecimal("1234.56")).write(new BigDecimal("0.01")).write(Integer.MIN_VALUE).write(-0.0).write(1e300)
                    .write(14285.714285714286).write(Long.MIN_VALUE).endRow();
        })) {
            XSSFRow first = workbook.getSheet("Values").getRow(0);
            assertEquals("text", first.getCell(0).getStringCellValue());
            assertEquals(42.0, first.getCell(1).getNumericCellValue());
            assertEquals(-7.0, first.getCell(2).getNumericCellValue());
            assertEquals((double) Long.MAX_VALUE, first.getCell(3).getNumericCellValue());
            assertEquals(1.5, first.getCell(4).getNumericCellValue());
            assertEquals(0.1, first.getCell(5).getNumericCellValue());
            assertTrue(first.getCell(6).getBooleanCellValue());
            assertFalse(first.getCell(7).getBooleanCellValue());
            assertEquals(CellType.BOOLEAN, first.getCell(7).getCellType());
            assertEquals("x", first.getCell(8).getStringCellValue());

            XSSFRow second = workbook.getSheet("Values").getRow(1);
            assertEquals(1234.56, second.getCell(0).getNumericCellValue());
            assertEquals(0.01, second.getCell(1).getNumericCellValue());
            assertEquals((double) Integer.MIN_VALUE, second.getCell(2).getNumericCellValue());
            assertEquals(0.0, second.getCell(3).getNumericCellValue());
            assertEquals(1e300, second.getCell(4).getNumericCellValue());
            assertEquals(14285.714285714286, second.getCell(5).getNumericCellValue());
            assertEquals((double) Long.MIN_VALUE, second.getCell(6).getNumericCellValue());
        }
    }

    @Test
    void writesNonFiniteNumbersAsNumError() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> writer.addWorksheet("S")
                .write(Double.NaN).write(Double.POSITIVE_INFINITY).write(Float.NEGATIVE_INFINITY).write(new BigDecimal("1E+400")).endRow())) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < 4; i++) {
                assertEquals(CellType.ERROR, row.getCell(i).getCellType());
                assertEquals("#NUM!", row.getCell(i).getErrorCellString());
            }
        }
    }

    @Test
    void escapesAndPreservesText() throws Exception {
        List<String> texts = List.of(
                "plain",
                "ünïcødé 日本語 😀 👩‍💻",
                "a&b<c>d\"e'f",
                " leading and trailing ",
                "line1\nline2\ttab",
                "carriage\rreturn\r\nwindows",
                "control\u0001\u001Fchars",
                "snake_case _x0041_ literal",
                "￾ and ￿",
                "",
                "]]> <![CDATA[ &amp;");
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            Worksheet sheet = writer.addWorksheet("Text");
            for (String text : texts) {
                sheet.write(text).endRow();
            }
            sheet.write("lone \uD83D surrogate \uDC69").endRow();
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i < texts.size(); i++) {
                assertEquals(texts.get(i), sheet.getRow(i).getCell(0).getStringCellValue(), "text " + i);
            }
            assertEquals("lone � surrogate �", sheet.getRow(texts.size()).getCell(0).getStringCellValue());
        }
    }

    @Test
    void writesDatesInThe1900System() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            Worksheet sheet = writer.addWorksheet("Dates");
            sheet.write(LocalDate.of(2024, 1, 1)).write(LocalDate.of(1900, 1, 1)).write(LocalDate.of(1900, 2, 28))
                    .write(LocalDate.of(1900, 3, 1)).write(LocalDate.of(9999, 12, 31)).endRow();
            sheet.write(LocalDateTime.of(2024, 5, 17, 13, 45, 30)).write(LocalDateTime.of(2024, 5, 17, 23, 59, 59, 999_999_999))
                    .write(LocalDateTime.of(2024, 5, 17, 6, 0, 0, 500_000_000)).endRow();
            sheet.write(LocalDate.of(1899, 12, 31)).write(LocalDateTime.of(1850, 6, 1, 12, 30)).write(LocalDate.of(10000, 1, 1)).endRow();
        })) {
            assertFalse(workbook.isDate1904());
            XSSFRow dates = workbook.getSheetAt(0).getRow(0);
            assertEquals(45292.0, dates.getCell(0).getNumericCellValue());
            assertEquals(LocalDate.of(2024, 1, 1), dates.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertTrue(DateUtil.isCellDateFormatted(dates.getCell(0)));
            assertEquals(14, dates.getCell(0).getCellStyle().getDataFormat());
            assertEquals(1.0, dates.getCell(1).getNumericCellValue());
            assertEquals(59.0, dates.getCell(2).getNumericCellValue());
            assertEquals(61.0, dates.getCell(3).getNumericCellValue());
            assertEquals(2958465.0, dates.getCell(4).getNumericCellValue());

            XSSFRow times = workbook.getSheetAt(0).getRow(1);
            assertEquals(LocalDateTime.of(2024, 5, 17, 13, 45, 30), times.getCell(0).getLocalDateTimeCellValue());
            assertEquals(22, times.getCell(0).getCellStyle().getDataFormat());
            assertEquals(45430.0, times.getCell(1).getNumericCellValue());
            assertEquals(45429.25 + 0.5 * SECOND, times.getCell(2).getNumericCellValue(), 1e-10);

            XSSFRow outOfRange = workbook.getSheetAt(0).getRow(2);
            assertEquals("1899-12-31", outOfRange.getCell(0).getStringCellValue());
            assertEquals("1850-06-01T12:30", outOfRange.getCell(1).getStringCellValue());
            assertEquals("+10000-01-01", outOfRange.getCell(2).getStringCellValue());
        }
    }

    @Test
    void writesDatesInThe1904System() throws Exception {
        XlsxOptions options = XlsxOptions.builder().date1904(true).build();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(options, writer -> writer.addWorksheet("Dates")
                .write(LocalDate.of(2024, 1, 1)).write(LocalDate.of(1904, 1, 1)).write(LocalDateTime.of(2024, 5, 17, 13, 45, 30))
                .write(LocalDate.of(1903, 12, 31)).write(LocalDate.of(9999, 12, 31)).endRow())) {
            assertTrue(workbook.isDate1904());
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            assertEquals(43830.0, row.getCell(0).getNumericCellValue());
            assertEquals(LocalDate.of(2024, 1, 1), row.getCell(0).getLocalDateTimeCellValue().toLocalDate());
            assertEquals(0.0, row.getCell(1).getNumericCellValue());
            assertEquals(LocalDateTime.of(2024, 5, 17, 13, 45, 30), row.getCell(2).getLocalDateTimeCellValue());
            assertEquals("1903-12-31", row.getCell(3).getStringCellValue());
            assertEquals(LocalDate.of(9999, 12, 31), row.getCell(4).getLocalDateTimeCellValue().toLocalDate());
        }
    }

    @Test
    void writesZonedValuesAsWallClockTime() throws Exception {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        Instant instant = Instant.parse("2024-07-01T10:15:30Z");
        Instant winter = Instant.parse("2024-01-15T23:30:00Z");
        XlsxOptions options = XlsxOptions.builder().zone(berlin).build();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(options, writer -> writer.addWorksheet("Zones")
                .write(instant).write(winter).write(Date.from(instant))
                .write(ZonedDateTime.of(2024, 7, 1, 8, 0, 0, 0, ZoneId.of("America/New_York")))
                .write(OffsetDateTime.of(2024, 7, 1, 20, 0, 0, 0, ZoneOffset.ofHours(9)))
                .write(Instant.parse("1800-01-01T00:00:00Z")).write(new Date(-5_000_000_000_000L)).endRow())) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            assertEquals(LocalDateTime.of(2024, 7, 1, 12, 15, 30), row.getCell(0).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.of(2024, 1, 16, 0, 30), row.getCell(1).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.of(2024, 7, 1, 12, 15, 30), row.getCell(2).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.of(2024, 7, 1, 8, 0), row.getCell(3).getLocalDateTimeCellValue());
            assertEquals(LocalDateTime.of(2024, 7, 1, 20, 0), row.getCell(4).getLocalDateTimeCellValue());
            assertEquals(CellType.STRING, row.getCell(5).getCellType());
            assertTrue(row.getCell(5).getStringCellValue().startsWith("1800-01-01T00:53"));
            assertTrue(row.getCell(6).getStringCellValue().startsWith("1811-"));
        }
    }

    @Test
    void usesFixedOffsetsAndTheSystemZoneByDefault() throws Exception {
        Instant instant = Instant.parse("2024-07-01T10:15:30.250Z");
        XlsxOptions fixed = XlsxOptions.builder().zone(ZoneOffset.ofHoursMinutes(5, 30)).build();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(fixed, writer -> writer.addWorksheet("S").write(instant).write(Date.from(instant)).endRow())) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            double expected = DateUtil.getExcelDate(LocalDateTime.of(2024, 7, 1, 15, 45, 30, 250_000_000));
            assertEquals(expected, row.getCell(0).getNumericCellValue(), 1e-9);
            assertEquals(expected, row.getCell(1).getNumericCellValue(), 1e-9);
        }
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> writer.addWorksheet("S").write(instant).endRow())) {
            LocalDateTime local = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            assertEquals(DateUtil.getExcelDate(local), workbook.getSheetAt(0).getRow(0).getCell(0).getNumericCellValue(), 1e-9);
        }
    }

    @Test
    void writesTimesAndDurations() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> writer.addWorksheet("S")
                .write(LocalTime.of(12, 0)).write(LocalTime.of(18, 30, 15)).write(LocalTime.MIDNIGHT)
                .write(Duration.ofHours(36)).write(Duration.ofSeconds(90)).write(Duration.ofMillis(-1500)).write(Duration.ofHours(-48))
                .write(Duration.ZERO).write(Duration.ofSeconds(-1, 999_999_999)).endRow())) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            assertEquals(0.5, row.getCell(0).getNumericCellValue());
            assertEquals(21, row.getCell(0).getCellStyle().getDataFormat());
            assertEquals((18 * 3600 + 30 * 60 + 15) * SECOND, row.getCell(1).getNumericCellValue(), 1e-10);
            assertEquals(0.0, row.getCell(2).getNumericCellValue());
            assertEquals(1.5, row.getCell(3).getNumericCellValue());
            assertEquals(46, row.getCell(3).getCellStyle().getDataFormat());
            assertEquals(90 * SECOND, row.getCell(4).getNumericCellValue(), 1e-10);
            assertEquals(-1.5 * SECOND, row.getCell(5).getNumericCellValue(), 1e-10);
            assertEquals(-2.0, row.getCell(6).getNumericCellValue());
            assertEquals(0.0, row.getCell(7).getNumericCellValue());
            assertEquals(0.0, row.getCell(8).getNumericCellValue());
        }
    }

    @Test
    void writesFormulasAndBlanks() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId border = writer.addStyle(Style.builder().border(Border.all(BorderStyle.THIN)).build());
            Worksheet sheet = writer.addWorksheet("S");
            sheet.write(1).write(2).writeFormula("SUM(A1:B1)").writeFormula("=A1&\"<x>\"", border).writeBlank(border)
                    .writeBlank(StyleId.DEFAULT).write("after").endRow();
        })) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            assertEquals(CellType.FORMULA, row.getCell(2).getCellType());
            assertEquals("SUM(A1:B1)", row.getCell(2).getCellFormula());
            assertEquals("A1&\"<x>\"", row.getCell(3).getCellFormula());
            assertEquals(CellType.BLANK, row.getCell(4).getCellType());
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.THIN, row.getCell(4).getCellStyle().getBorderLeft());
            assertNull(row.getCell(5));
            assertEquals("after", row.getCell(6).getStringCellValue());
            assertTrue(workbook.getCTWorkbook().getCalcPr().getFullCalcOnLoad());
        }
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> writer.addWorksheet("S").write(1).endRow())) {
            assertFalse(workbook.getCTWorkbook().getCalcPr().getFullCalcOnLoad());
        }
    }

    @Test
    void writesNullsAsEmptyCells() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId fill = writer.addStyle(Style.builder().fill(Color.YELLOW).build());
            writer.addWorksheet("S")
                    .write((String) null).write((BigDecimal) null).write((LocalDate) null).write((LocalDateTime) null)
                    .write((LocalTime) null).write((Instant) null).write((ZonedDateTime) null).write((OffsetDateTime) null)
                    .write((Date) null).write((Duration) null).writeValue(null).write((String) null, fill).write("end").endRow();
        })) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < 11; i++) {
                assertNull(row.getCell(i), "cell " + i);
            }
            assertEquals(CellType.BLANK, row.getCell(11).getCellType());
            assertEquals("end", row.getCell(12).getStringCellValue());
        }
    }

    @Test
    void writesValuesByRuntimeType() throws Exception {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        try (XSSFWorkbook workbook = Xlsx.roundTrip(XlsxOptions.builder().zone(ZoneOffset.UTC).build(), writer -> {
            StyleId money = writer.addStyle(Style.builder().numberFormat(NumberFormats.THOUSANDS2).build());
            StyleId bold = writer.addStyle(Style.builder().bold().build());
            Worksheet sheet = writer.addWorksheet("S");
            sheet.addRow("s", 1, 2L, (short) 3, (byte) 4, 5.5, 6.5f, true, new BigDecimal("7.25"), BigInteger.TEN,
                    BigInteger.TWO.pow(70), new AtomicInteger(9), TimeUnit.DAYS, uuid, 'c', null);
            sheet.addRow(LocalDate.of(2024, 1, 1), LocalDateTime.of(2024, 1, 1, 12, 0), LocalTime.NOON, Instant.parse("2024-01-01T06:00:00Z"),
                    Date.from(Instant.parse("2024-01-01T06:00:00Z")), ZonedDateTime.of(2024, 1, 1, 18, 0, 0, 0, ZoneOffset.UTC),
                    OffsetDateTime.of(2024, 1, 1, 18, 0, 0, 0, ZoneOffset.UTC), Duration.ofHours(12));
            sheet.addStyledRow(bold, "bold", Cell.styled(1234.5, money), Cell.formula("1+1"), Cell.formula("2+2", money), Cell.blank(money), 5);
            sheet.writeValue("styled", bold).writeValue(1.5).endRow();
        })) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            XSSFRow row = sheet.getRow(0);
            assertEquals("s", row.getCell(0).getStringCellValue());
            double[] numbers = {1, 2, 3, 4, 5.5, 6.5};
            for (int i = 0; i < numbers.length; i++) {
                assertEquals(numbers[i], row.getCell(i + 1).getNumericCellValue());
            }
            assertTrue(row.getCell(7).getBooleanCellValue());
            assertEquals(7.25, row.getCell(8).getNumericCellValue());
            assertEquals(10.0, row.getCell(9).getNumericCellValue());
            assertEquals(Math.pow(2, 70), row.getCell(10).getNumericCellValue());
            assertEquals(9.0, row.getCell(11).getNumericCellValue());
            assertEquals("DAYS", row.getCell(12).getStringCellValue());
            assertEquals(uuid.toString(), row.getCell(13).getStringCellValue());
            assertEquals("c", row.getCell(14).getStringCellValue());
            assertNull(row.getCell(15));

            XSSFRow temporal = sheet.getRow(1);
            assertEquals(45292.0, temporal.getCell(0).getNumericCellValue());
            assertEquals(45292.5, temporal.getCell(1).getNumericCellValue());
            assertEquals(0.5, temporal.getCell(2).getNumericCellValue());
            assertEquals(45292.25, temporal.getCell(3).getNumericCellValue());
            assertEquals(45292.25, temporal.getCell(4).getNumericCellValue());
            assertEquals(45292.75, temporal.getCell(5).getNumericCellValue());
            assertEquals(45292.75, temporal.getCell(6).getNumericCellValue());
            assertEquals(0.5, temporal.getCell(7).getNumericCellValue());

            XSSFRow styled = sheet.getRow(2);
            assertTrue(styled.getCell(0).getCellStyle().getFont().getBold());
            assertEquals("#,##0.00", styled.getCell(1).getCellStyle().getDataFormatString());
            assertFalse(styled.getCell(1).getCellStyle().getFont().getBold());
            assertEquals("1+1", styled.getCell(2).getCellFormula());
            assertTrue(styled.getCell(2).getCellStyle().getFont().getBold());
            assertEquals("2+2", styled.getCell(3).getCellFormula());
            assertEquals(CellType.BLANK, styled.getCell(4).getCellType());
            assertTrue(styled.getCell(5).getCellStyle().getFont().getBold());

            XSSFCell last = sheet.getRow(3).getCell(0);
            assertTrue(last.getCellStyle().getFont().getBold());
        }
    }

    @Test
    void appliesStylesInEveryTypedOverload() throws Exception {
        Instant instant = Instant.parse("2024-01-01T06:00:00Z");
        try (XSSFWorkbook workbook = Xlsx.roundTrip(XlsxOptions.builder().zone(ZoneOffset.UTC).build(), writer -> {
            StyleId bold = writer.addStyle(Style.builder().bold().build());
            writer.addWorksheet("S")
                    .write("s", bold).write(1, bold).write(2L, bold).write(3.5, bold).write(4.5f, bold).write(true, bold)
                    .write(new BigDecimal("6.5"), bold).write(LocalDate.of(2024, 1, 1), bold).write(LocalDateTime.of(2024, 1, 1, 6, 0), bold)
                    .write(LocalTime.of(6, 0), bold).write(instant, bold).write(instant.atZone(ZoneOffset.UTC), bold)
                    .write(instant.atOffset(ZoneOffset.UTC), bold).write(Date.from(instant), bold).write(Duration.ofHours(6), bold)
                    .writeFormula("1+1", bold).writeBlank(bold).endRow();
        })) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            assertEquals(17, row.getLastCellNum());
            for (int i = 0; i < 17; i++) {
                assertTrue(row.getCell(i).getCellStyle().getFont().getBold(), "cell " + i);
            }
            double[] numbers = {1, 2, 3.5, 4.5};
            for (int i = 0; i < numbers.length; i++) {
                assertEquals(numbers[i], row.getCell(i + 1).getNumericCellValue());
            }
            assertEquals(6.5, row.getCell(6).getNumericCellValue());
            assertEquals(45292.0, row.getCell(7).getNumericCellValue());
            for (int i = 8; i <= 13; i++) {
                assertEquals(i == 9 ? 0.25 : 45292.25, row.getCell(i).getNumericCellValue(), "cell " + i);
            }
            assertEquals(0.25, row.getCell(14).getNumericCellValue());
            assertEquals(46, row.getCell(14).getCellStyle().getDataFormat());
        }
    }
}
