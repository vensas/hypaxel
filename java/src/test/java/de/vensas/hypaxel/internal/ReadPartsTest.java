package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.vensas.hypaxel.DateKind;
import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/** Number parsing, date formats, shared strings, date serials and part paths. */
class ReadPartsTest {
    private static XmlReader xml(String text) {
        return new XmlReader(ByteInput.of(text.getBytes(StandardCharsets.UTF_8)), "part.xml");
    }

    private static double parse(String s) {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        return Numbers.parse(b, 0, b.length);
    }

    private static double reference(String s) {
        if (!Numbers.isDecimalSyntax(s)) {
            return Double.NaN;
        }
        try {
            double d = Double.parseDouble(s);
            return Double.isFinite(d) ? d : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    @Test
    void parsesNumbersExactlyLikeTheJdk() {
        String[] cases = {"0", "-0", "42", "-123456789012345", "12345678901234567890", "3.25E-2", "1.", ".5", "+1", "1e5", "1E+5",
            "0.1", "0.000123", "9007199254740993", "9007199254740992.5", "4.9e-324", "2.2250738585072011E-308", "1.7976931348623157E308",
            "123456789012345678901234567890", "0.14285714285714285", "45292.0006944444", "99999999999999999999e-5", "1e-400",
            "1e400", "1e", "-", "e5", ".", "1..2", "1.5.5", "0x10", "NaN", "Infinity", "inf", "1d", "1,5", "", "1e5x"};
        for (String c : cases) {
            assertEquals(Double.doubleToLongBits(reference(c)), Double.doubleToLongBits(parse(c)), c);
        }
        assertTrue(Double.isNaN(parse("1d")));
        SplittableRandom random = new SplittableRandom(7);
        for (int i = 0; i < 300_000; i++) {
            String s;
            switch (i % 4) {
                case 0 -> s = Double.toString(Double.longBitsToDouble(random.nextLong() & 0x7FEF_FFFF_FFFF_FFFFL));
                case 1 -> s = Double.toString(random.nextDouble() * Math.pow(10, random.nextInt(-30, 30)));
                case 2 -> s = new BigDecimal(random.nextDouble() * 1e6).round(new java.math.MathContext(random.nextInt(1, 25))).toPlainString();
                default -> {
                    StringBuilder digits = new StringBuilder();
                    for (int k = random.nextInt(1, 24); k > 0; k--) {
                        digits.append((char) ('0' + random.nextInt(10)));
                    }
                    if (random.nextBoolean()) {
                        digits.insert(random.nextInt(digits.length() + 1), '.');
                    }
                    if (random.nextInt(3) == 0) {
                        digits.append('e').append(random.nextInt(-340, 330));
                    }
                    s = digits.toString();
                }
            }
            assertEquals(Double.doubleToLongBits(reference(s)), Double.doubleToLongBits(parse(s)), s);
        }
    }

    @Test
    void classifiesFormatCodes() {
        Object[][] cases = {
            {"yyyy-mm-dd", DateKind.DATE}, {"d/m/yy", DateKind.DATE}, {"mmm yyyy", DateKind.DATE}, {"[$-409]mmmm d, yyyy;@", DateKind.DATE},
            {"yyyy-mm-dd hh:mm:ss", DateKind.DATE_TIME}, {"m/d/yy h:mm AM/PM", DateKind.DATE_TIME}, {"hh:mm", DateKind.TIME},
            {"h:mm:ss AM/PM", DateKind.TIME}, {"mm:ss.0", DateKind.TIME}, {"[h]:mm:ss", DateKind.DURATION}, {"[mm]:ss", DateKind.DURATION},
            {"General", null}, {"0.00", null}, {"#,##0.00 \"days\"", null}, {"[Red]#,##0;[Blue]-#,##0", null},
            {"_(* #,##0_);_(* (#,##0);_(* \"-\"??_);_(@_)", null}, {"0.00E+00", null}, {"@", null}, {"\"Date:\" @", null}, {"0 \\d", null},
            {"General;yyyy", null}, {"[h", DateKind.DURATION}, {"[]0", null}, {"\"unterminated", null},
        };
        for (Object[] c : cases) {
            assertEquals(c[1], DateStyles.formatKind((String) c[0]), (String) c[0]);
        }
        assertEquals(DateKind.DATE, DateStyles.builtinKind(14));
        assertEquals(DateKind.DATE_TIME, DateStyles.builtinKind(22));
        assertEquals(DateKind.TIME, DateStyles.builtinKind(45));
        assertEquals(DateKind.DURATION, DateStyles.builtinKind(46));
        assertNull(DateStyles.builtinKind(4));
    }

    @Test
    void parsesCellFormats() throws IOException {
        String text = "<styleSheet xmlns=\"x\"><numFmts count=\"3\"><numFmt numFmtId=\"164\" formatCode=\"yyyy&quot;-&quot;mm\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"0.0\"/><numFmt numFmtId=\"14\" formatCode=\"0.00\"/><numFmt formatCode=\"yyyy\"/></numFmts>"
                + "<fonts><font><sz val=\"11\"/></font></fonts><cellStyleXfs count=\"1\"><xf numFmtId=\"14\"/></cellStyleXfs>"
                + "<cellXfs count=\"7\"><xf numFmtId=\"0\"/><xf numFmtId=\"164\" applyNumberFormat=\"1\"/><xf numFmtId=\"165\"/><xf numFmtId=\"22\"><alignment/></xf>"
                + "<xf numFmtId=\"46\"/><xf numFmtId=\"14\"/><xf numFmtId=\" 18 \"/>"
                + "<xf/><xf/><xf/><xf/><xf/><xf/><xf/><xf/><xf/><xf/><xf numFmtId=\"15\"/></cellXfs><dxfs><dxf><numFmt numFmtId=\"300\" formatCode=\"d\"/></dxf></dxfs></styleSheet>";
        DateStyles styles = DateStyles.parse(xml(text));
        assertNull(styles.kind(0));
        assertEquals(DateKind.DATE, styles.kind(1));
        assertNull(styles.kind(2));
        assertEquals(DateKind.DATE_TIME, styles.kind(3));
        assertEquals(DateKind.DURATION, styles.kind(4));
        assertNull(styles.kind(5), "a custom format overrides a built-in id");
        assertEquals(DateKind.TIME, styles.kind(6));
        assertEquals(DateKind.DATE, styles.kind(17));
        assertNull(styles.kind(99));
        assertNull(styles.kind(-1));
        assertEquals(0, DateStyles.NONE.kindCode(0));
    }

    @Test
    void parsesSharedStrings() throws IOException {
        String text = "<?xml version=\"1.0\"?><sst xmlns=\"x\" count=\"4\" uniqueCount=\"4\">"
                + "<si><t>plain</t></si><si><r><rPr><b/></rPr><t xml:space=\"preserve\">rich </t></r><r><t>text</t></r></si>"
                + "<si><t>東京</t><rPh sb=\"0\" eb=\"2\"><t>トウキョウ</t></rPh><phoneticPr fontId=\"1\"/></si><si/>"
                + "<si><t>a &amp; b_x000A_c</t></si><si><t/></si><si><t xml:space=\"preserve\"> x </t></si></sst>";
        SharedStringTable sst = SharedStringTable.parse(xml(text), Long.MAX_VALUE);
        assertEquals(Arrays.asList("plain", "rich text", "東京", "", "a & b\nc", "", " x "), Arrays.asList(sst.strings()));
        assertEquals(7, sst.size());
        assertEquals(30, sst.textBytes());

        XlsxException e = assertThrows(XlsxException.class,
                () -> SharedStringTable.parse(xml("<sst><si><t>0123456789</t></si><si><t>0123456789</t></si></sst>"), 15));
        assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, e.kind());
        assertThrows(XlsxException.class, () -> SharedStringTable.parse(xml("<sst><si><t>&bad;</t></si></sst>"), 100));

        StringBuilder many = new StringBuilder("<sst>");
        for (int i = 0; i < 3_000; i++) {
            many.append("<si><t>").append(i).append("</t></si>");
        }
        assertEquals("2999", SharedStringTable.parse(xml(many.append("</sst>").toString()), Long.MAX_VALUE).strings()[2_999]);
    }

    @Test
    void convertsSerialsAndIsoDates() {
        assertEquals(DateSerials.INVALID, DateSerials.toEpochMillis(-1, false));
        assertEquals(DateSerials.INVALID, DateSerials.toEpochMillis(Double.NaN, false));
        assertEquals(DateSerials.INVALID, DateSerials.toEpochMillis(3_000_000, false));
        assertEquals(DateSerials.INVALID, DateSerials.toEpochMillis(60, false));
        assertEquals(0, DateSerials.toEpochMillis(25_569, false));
        assertEquals(DateSerials.INVALID, DateSerials.toDurationMillis(Double.POSITIVE_INFINITY));
        assertEquals(-129_600_000, DateSerials.toDurationMillis(-1.5));

        assertEquals(1.0, DateSerials.isoToSerial("1900-01-01", false));
        assertEquals(59.0, DateSerials.isoToSerial("1900-02-28", false));
        assertEquals(61.0, DateSerials.isoToSerial("1900-03-01", false));
        assertEquals(45_413.75, DateSerials.isoToSerial("2024-05-01T18:00:00Z", false));
        assertEquals(0.25, DateSerials.isoToSerial(" 06:00 ", false));
        assertEquals(1.0, DateSerials.isoToSerial("1904-01-02", true));
        assertEquals(DateKind.DATE_TIME, DateSerials.isoKind("2024-05-01T18:00:00"));
        assertEquals(DateKind.TIME, DateSerials.isoKind("18:00"));
        assertEquals(DateKind.DATE, DateSerials.isoKind("2024-05-01"));
        for (String bad : new String[] {"not a date", "2024-13-01", "2024-01-32", "2024-01", "12", "1:x", "2024-01-01Tx:00", "99999999-01-01", "+-1-01-01"}) {
            assertTrue(Double.isNaN(DateSerials.isoToSerial(bad, false)), bad);
        }
        for (long days : new long[] {1, 365, 693_594, 738_000, 1_000_000}) {
            java.time.LocalDate date = java.time.LocalDate.of(1, 1, 1).plusDays(days - 1);
            assertEquals(days, DateSerials.daysFromCe(date.getYear(), date.getMonthValue(), date.getDayOfMonth()));
        }
    }

    @Test
    void resolvesRelationshipTargets() {
        assertEquals("", WorkbookPackage.sourceDirectory("_rels/.rels"));
        assertEquals("xl/", WorkbookPackage.sourceDirectory("xl/_rels/workbook.xml.rels"));
        assertEquals("", WorkbookPackage.sourceDirectory("x.rels"));
        assertEquals("xl/_rels/workbook.xml.rels", WorkbookPackage.relsPath("xl/workbook.xml"));
        assertEquals("_rels/workbook.xml.rels", WorkbookPackage.relsPath("workbook.xml"));
        assertEquals("xl/worksheets/sheet1.xml", WorkbookPackage.resolve("xl/", "worksheets/sheet1.xml"));
        assertEquals("xl/worksheets/sheet1.xml", WorkbookPackage.resolve("xl/", "/xl/worksheets/sheet1.xml"));
        assertEquals("customXml/item1.xml", WorkbookPackage.resolve("xl/", "../customXml/item1.xml"));
        assertEquals("a.xml", WorkbookPackage.resolve("", "../../a.xml"));
        assertEquals("xl/a/b.xml", WorkbookPackage.resolve("xl/", "./a\\b.xml"));
        assertEquals("xl/workbook.xml", WorkbookPackage.resolve("", "xl/workbook.xml"));
    }

    @Test
    void parsesReferencesAndDigits() {
        byte[] b = "A1z9AA10XFD1048576ABCD112".getBytes(StandardCharsets.US_ASCII);
        assertEquals(1, SheetParser.parseColumn(b, 0, 2));
        assertEquals(26, SheetParser.parseColumn(b, 2, 4));
        assertEquals(27, SheetParser.parseColumn(b, 4, 8));
        assertEquals(16_384, SheetParser.parseColumn(b, 8, 18));
        assertEquals(-1, SheetParser.parseColumn(b, 18, 23));
        assertEquals(-1, SheetParser.parseColumn(b, 23, 25));
        byte[] digits = "4294967295 2147483647".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1, SheetParser.parseDigits(digits, 0, 10));
        assertEquals(Integer.MAX_VALUE, SheetParser.parseDigits(digits, 11, 21));
        assertEquals(-1, SheetParser.parseDigits(digits, 0, 0));
        assertEquals(-1, Ascii.parseUnsigned(null));
        assertEquals(7, Ascii.parseUnsigned(" +7 "));
        assertEquals(-1, Ascii.parseUnsigned("x"));
        assertEquals(-1, Ascii.parseUnsigned("99999999999"));
    }
}
