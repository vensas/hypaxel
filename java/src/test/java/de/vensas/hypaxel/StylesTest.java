package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.FontUnderline;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StylesTest {
    @Test
    void writesFontsFillsAndNumberFormats() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId fancy = writer.addStyle(Style.builder().fontName("Arial").fontSize(14.5).bold().italic().underline().strikethrough()
                    .fontColor(Color.RED).fill(Color.hex("#305496")).build());
            StyleId money = writer.addStyle(Style.builder().numberFormat("#,##0.00 \"€\"").build());
            StyleId percent = writer.addStyle(Style.builder().numberFormat(NumberFormats.PERCENT2).build());
            StyleId text = writer.addStyle(Style.builder().numberFormat(NumberFormats.TEXT).build());
            writer.addWorksheet("S").write("fancy", fancy).write(1234.5, money).write(0.25, percent).write("007", text).write("plain").endRow();
        })) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            XSSFCellStyle fancy = row.getCell(0).getCellStyle();
            XSSFFont font = fancy.getFont();
            assertEquals("Arial", font.getFontName());
            assertEquals(290, font.getFontHeight());
            assertTrue(font.getBold());
            assertTrue(font.getItalic());
            assertTrue(font.getStrikeout());
            assertEquals(FontUnderline.SINGLE.getByteValue(), font.getUnderline());
            assertEquals("FFFF0000", font.getXSSFColor().getARGBHex());
            assertEquals(FillPatternType.SOLID_FOREGROUND, fancy.getFillPattern());
            assertEquals("FF305496", fancy.getFillForegroundColorColor().getARGBHex());

            assertEquals("#,##0.00 \"€\"", row.getCell(1).getCellStyle().getDataFormatString());
            assertEquals(10, row.getCell(2).getCellStyle().getDataFormat());
            assertEquals(49, row.getCell(3).getCellStyle().getDataFormat());

            XSSFCellStyle plain = row.getCell(4).getCellStyle();
            assertEquals("Calibri", plain.getFont().getFontName());
            assertEquals(11, plain.getFont().getFontHeightInPoints());
            assertFalse(plain.getFont().getBold());
            assertEquals(FillPatternType.NO_FILL, plain.getFillPattern());
        }
    }

    @Test
    void writesEveryBorderStyle() throws Exception {
        BorderStyle[] styles = BorderStyle.values();
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            for (BorderStyle style : styles) {
                sheet.write(style.name(), writer.addStyle(Style.builder().border(Border.all(style, Color.BLUE)).build()));
            }
            sheet.endRow();
            Border mixed = Border.builder().left(BorderLine.of(BorderStyle.THICK)).top(BorderLine.of(BorderStyle.DOUBLE, Color.GREEN)).build();
            sheet.write("mixed", writer.addStyle(Style.builder().border(mixed).build())).endRow();
        })) {
            XSSFRow row = workbook.getSheetAt(0).getRow(0);
            for (int i = 0; i < styles.length; i++) {
                XSSFCellStyle style = row.getCell(i).getCellStyle();
                String expected = styles[i] == BorderStyle.SLANT_DASH_DOT ? "SLANTED_DASH_DOT" : styles[i].name();
                assertEquals(expected, style.getBorderLeft().name());
                assertEquals(expected, style.getBorderRight().name());
                assertEquals(expected, style.getBorderTop().name());
                assertEquals(expected, style.getBorderBottom().name());
                if (styles[i] != BorderStyle.NONE) {
                    assertEquals("FF0000FF", style.getLeftBorderXSSFColor().getARGBHex());
                }
            }
            XSSFCellStyle mixed = workbook.getSheetAt(0).getRow(1).getCell(0).getCellStyle();
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.THICK, mixed.getBorderLeft());
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.DOUBLE, mixed.getBorderTop());
            assertEquals("FF008000", mixed.getTopBorderXSSFColor().getARGBHex());
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.NONE, mixed.getBorderRight());
            assertEquals(org.apache.poi.ss.usermodel.BorderStyle.NONE, mixed.getBorderBottom());
        }
    }

    @Test
    void writesAlignment() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            for (HorizontalAlignment horizontal : HorizontalAlignment.values()) {
                sheet.write(horizontal.name(), writer.addStyle(Style.builder().align(horizontal).build()));
            }
            sheet.endRow();
            for (VerticalAlignment vertical : VerticalAlignment.values()) {
                sheet.write(vertical.name(), writer.addStyle(Style.builder().verticalAlign(vertical).build()));
            }
            sheet.endRow();
            StyleId full = writer.addStyle(Style.builder().align(HorizontalAlignment.LEFT).verticalAlign(VerticalAlignment.TOP)
                    .wrapText().indent(3).rotation(45).build());
            StyleId shrink = writer.addStyle(Style.builder().alignment(Alignment.builder().shrinkToFit(true)
                    .rotation(Alignment.VERTICAL_TEXT).build()).build());
            sheet.write("full", full).write("shrink", shrink).endRow();
        })) {
            XSSFRow horizontal = workbook.getSheetAt(0).getRow(0);
            for (HorizontalAlignment value : HorizontalAlignment.values()) {
                String expected = value == HorizontalAlignment.CENTER_CONTINUOUS ? "CENTER_SELECTION" : value.name();
                assertEquals(expected, horizontal.getCell(value.ordinal()).getCellStyle().getAlignment().name());
            }
            XSSFRow vertical = workbook.getSheetAt(0).getRow(1);
            for (VerticalAlignment value : VerticalAlignment.values()) {
                assertEquals(value.name(), vertical.getCell(value.ordinal()).getCellStyle().getVerticalAlignment().name());
            }
            XSSFCellStyle full = workbook.getSheetAt(0).getRow(2).getCell(0).getCellStyle();
            assertEquals(org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT, full.getAlignment());
            assertEquals(org.apache.poi.ss.usermodel.VerticalAlignment.TOP, full.getVerticalAlignment());
            assertTrue(full.getWrapText());
            assertEquals(3, full.getIndention());
            assertEquals(45, full.getRotation());
            XSSFCellStyle shrink = workbook.getSheetAt(0).getRow(2).getCell(1).getCellStyle();
            assertTrue(shrink.getShrinkToFit());
            assertEquals(255, shrink.getRotation());
        }
    }

    @Test
    void deduplicatesIdenticalStyles() throws Exception {
        byte[] xlsx = Xlsx.write(writer -> {
            StyleId first = writer.addStyle(Style.builder().bold().fill(Color.YELLOW).numberFormat("0.000").build());
            StyleId second = writer.addStyle(Style.builder().numberFormat("0.000").fill(Color.rgb(255, 255, 0)).bold().build());
            StyleId other = writer.addStyle(Style.builder().bold().fill(Color.YELLOW).numberFormat("0.0000").build());
            assertEquals(first, second);
            assertEquals(first.hashCode(), second.hashCode());
            assertEquals(first.index(), second.index());
            assertNotEquals(first, other);
            assertSame(StyleId.DEFAULT, writer.addStyle(Style.builder().build()));
            assertEquals("StyleId(0)", StyleId.DEFAULT.toString());
            writer.addWorksheet("S").write(1, first).write(2, second).write(3, other).endRow();
        });
        String styles = Xlsx.part(xlsx, "xl/styles.xml");
        assertTrue(styles.contains("<fonts count=\"2\">"));
        assertTrue(styles.contains("<fills count=\"3\">"));
        assertTrue(styles.contains("<numFmts count=\"2\">"));
        assertTrue(styles.contains("<cellXfs count=\"3\">"));
    }

    @Test
    void keepsStylesOnDatesAndAddsADateFormatWhenMissing() throws Exception {
        try (XSSFWorkbook workbook = Xlsx.roundTrip(writer -> {
            StyleId bold = writer.addStyle(Style.builder().bold().build());
            StyleId iso = writer.addStyle(Style.builder().numberFormat(NumberFormats.DATE).italic().build());
            Worksheet sheet = writer.addWorksheet("S");
            for (int i = 0; i < 3; i++) {
                sheet.write(LocalDate.of(2024, 1, 1), bold).write(LocalDateTime.of(2024, 1, 1, 8, 0), bold)
                        .write(LocalDate.of(2024, 1, 1), iso).write("text", bold).endRow();
            }
        })) {
            XSSFRow row = workbook.getSheetAt(0).getRow(2);
            assertTrue(row.getCell(0).getCellStyle().getFont().getBold());
            assertEquals(14, row.getCell(0).getCellStyle().getDataFormat());
            assertTrue(row.getCell(1).getCellStyle().getFont().getBold());
            assertEquals(22, row.getCell(1).getCellStyle().getDataFormat());
            assertEquals("yyyy-mm-dd", row.getCell(2).getCellStyle().getDataFormatString());
            assertTrue(row.getCell(2).getCellStyle().getFont().getItalic());
            assertEquals(0, row.getCell(3).getCellStyle().getDataFormat());
            assertEquals(5, workbook.getNumCellStyles());
        }
    }

    @Test
    void valueObjectsCompareByContent() {
        Style style = Style.builder().bold().fontColor(Color.RED).fill(Color.BLUE).border(Border.all(BorderStyle.THIN))
                .align(HorizontalAlignment.CENTER).numberFormat("0.0").build();
        assertEquals(style, style.toBuilder().build());
        assertEquals(style.hashCode(), style.toBuilder().build().hashCode());
        assertNotEquals(style, style.toBuilder().italic().build());
        assertNotEquals(style, style.toBuilder().fill(null).build());
        assertNotEquals(style, style.toBuilder().border(null).build());
        assertNotEquals(style, style.toBuilder().alignment(null).build());
        assertNotEquals(style, style.toBuilder().font(null).build());
        assertNotEquals(style, style.toBuilder().numberFormat(null).build());
        assertNotEquals(style, "style");
        assertTrue(style.toString().contains("numberFormat=0.0"));
        assertEquals("0.0", style.numberFormat());
        assertEquals(Color.BLUE, style.fill());

        Font font = Font.builder().name("Arial").size(9).bold(true).italic(true).underline(true).strikethrough(true).color(Color.GREEN).build();
        assertEquals(font, font.toBuilder().build());
        assertNotEquals(font, Font.DEFAULT);
        assertEquals(9.0, font.size());
        assertEquals(Color.GREEN, font.color());
        assertTrue(font.toString().contains("Arial"));
        assertEquals(font, Style.builder().font(font).build().font());

        Alignment alignment = Alignment.builder().horizontal(HorizontalAlignment.RIGHT).vertical(VerticalAlignment.CENTER)
                .wrapText(true).shrinkToFit(true).indent(2).rotation(90).build();
        assertEquals(alignment, alignment.toBuilder().build());
        assertEquals(alignment.hashCode(), alignment.toBuilder().build().hashCode());
        assertNotEquals(alignment, Alignment.builder().build());
        assertTrue(alignment.toString().contains("RIGHT"));

        Border border = Border.builder().left(BorderLine.of(BorderStyle.THIN)).right(BorderLine.of(BorderStyle.THIN, Color.RED))
                .top(BorderLine.NONE).bottom(BorderLine.of(BorderStyle.HAIR)).build();
        assertEquals(border, Border.builder().left(border.left()).right(border.right()).top(border.top()).bottom(border.bottom()).build());
        assertEquals(border.hashCode(), Border.builder().left(border.left()).right(border.right()).bottom(border.bottom()).build().hashCode());
        assertNotEquals(border, Border.all(BorderStyle.THIN));
        assertTrue(border.toString().contains("HAIR"));
        assertNull(BorderLine.of(BorderStyle.THIN).color());
    }

    @Test
    void parsesColors() {
        assertEquals(0xFF305496, Color.hex("#305496").argb());
        assertEquals(0xFF305496, Color.hex("305496").argb());
        assertEquals(0x80FFFFFF, Color.hex("#80FFFFFF").argb());
        assertEquals(Color.argb(0xFFFF0000), Color.RED);
        assertEquals("#FFFF0000", Color.RED.toString());
        assertEquals(Color.RED.hashCode(), Color.rgb(255, 0, 0).hashCode());
        assertNotEquals(Color.RED, Color.BLACK);
        assertNotEquals(Color.WHITE, Color.DARK_GRAY);
        assertEquals(XlsxException.Kind.INVALID_ARGUMENT, assertThrows(XlsxException.class, () -> Color.hex("#12345")).kind());
        assertEquals(XlsxException.Kind.INVALID_ARGUMENT, assertThrows(XlsxException.class, () -> Color.hex("zzzzzz")).kind());
        assertEquals(XlsxException.Kind.INVALID_ARGUMENT, assertThrows(XlsxException.class, () -> Color.rgb(256, 0, 0)).kind());
        assertEquals(XlsxException.Kind.INVALID_ARGUMENT, assertThrows(XlsxException.class, () -> Color.rgb(0, -1, 0)).kind());
    }

    @Test
    void rejectsInvalidStyleSettings() {
        assertThrows(XlsxException.class, () -> Font.builder().size(0.5));
        assertThrows(XlsxException.class, () -> Font.builder().size(Double.NaN));
        assertThrows(XlsxException.class, () -> Font.builder().name(""));
        assertThrows(XlsxException.class, () -> Alignment.builder().indent(-1));
        assertThrows(XlsxException.class, () -> Alignment.builder().rotation(200));
        assertThrows(XlsxException.class, () -> Style.builder().numberFormat(""));
        assertThrows(NullPointerException.class, () -> new BorderLine(null, null));
    }
}
