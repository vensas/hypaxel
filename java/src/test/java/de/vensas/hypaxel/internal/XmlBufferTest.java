package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class XmlBufferTest {
    private static String build(Consumer<XmlBuffer> body) {
        XmlBuffer buffer = new XmlBuffer(0);
        body.accept(buffer);
        return new String(buffer.array(), 0, buffer.length(), StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        return build(x -> x.putText(text));
    }

    @Test
    void escapesText() {
        assertEquals("plain ünïcødé 😀", escape("plain ünïcødé 😀"));
        assertEquals("a&amp;b&lt;c&gt;d&quot;e'f", escape("a&b<c>d\"e'f"));
        assertEquals("tab\tnl\ncr_x000D_", escape("tab\tnl\ncr\r"));
        assertEquals("_x0001_x_x001F_", escape("\u0001x\u001f"));
        assertEquals("_x0000_", escape("\u0000"));
        assertEquals("a_x005F_x0041_b snake_case", escape("a_x0041_b snake_case"));
        assertEquals("_x005F_xABCD_ _xABCG_ _x12_ _", escape("_xABCD_ _xABCG_ _x12_ _"));
        assertEquals("_x１２３４_", escape("_x１２３４_"));
        assertEquals("_xFFFE__xFFFF_！", escape("￾￿！"));
        assertEquals("߿ࠀ�", escape("߿ࠀ�"));
        assertEquals("��", escape("\uDC00\uD800"));
        assertEquals("x�", escape("x\uD83D"));
        assertEquals("", escape(""));
    }

    @Test
    void escapesLongTextBeyondTheInitialCapacity() {
        String text = "<\u0001_x0041_￿😀&>".repeat(5000);
        String expected = "&lt;_x0001__x005F_x0041__xFFFF_😀&amp;&gt;".repeat(5000);
        assertEquals(expected, escape(text));
        assertEquals("\u0001".repeat(3000).replace("\u0001", "_x0001_"), escape("\u0001".repeat(3000)));
    }

    @Test
    void writesTextElements() {
        assertEquals("<t>text</t>", build(x -> x.putTextElement("text")));
        assertEquals("<t></t>", build(x -> x.putTextElement("")));
        assertEquals("<t xml:space=\"preserve\"> text</t>", build(x -> x.putTextElement(" text")));
        assertEquals("<t xml:space=\"preserve\">text\n</t>", build(x -> x.putTextElement("text\n")));
        assertEquals("<t xml:space=\"preserve\">\ttext</t>", build(x -> x.putTextElement("\ttext")));
        assertEquals("<t xml:space=\"preserve\">text_x000D_</t>", build(x -> x.putTextElement("text\r")));
    }

    @Test
    void writesIntegers() {
        long[] values = {0, 1, 9, 10, 99, 100, 12345, 2147483647L, 2147483648L, 9999999999L, 123456789012345678L,
            Long.MAX_VALUE, Long.MIN_VALUE, -1, -10, -2147483649L};
        for (long value : values) {
            assertEquals(Long.toString(value), build(x -> x.putLong(value)));
        }
    }

    @Test
    void writesDoubles() {
        assertEquals("1 -0.1 1E300 0 0 -3 9007199254740992 0.5",
                build(x -> x.putDouble(1.0).raw(' ').putDouble(-0.1).raw(' ').putDouble(1e300).raw(' ').putDouble(0.0).raw(' ')
                        .putDouble(-0.0).raw(' ').putDouble(-3.0).raw(' ').putDouble(9007199254740992.0).raw(' ').putDouble(0.5)));
    }

    @Test
    void writesDateSerials() {
        long noon = 12L * 3600 * 1_000_000_000L;
        assertEquals("45292", build(x -> x.putSerial(false, 45292, 0)));
        assertEquals("45292.5", build(x -> x.putSerial(false, 45292, noon)));
        assertEquals("45293", build(x -> x.putSerial(false, 45292, 86_400_000_000_000L - 1)));
        assertEquals("0.0000115741", build(x -> x.putSerial(false, 0, 1_000_000_000L)));
        assertEquals("45292.0000000116", build(x -> x.putSerial(false, 45292, 1_000_000L)));
        assertEquals("-1.5", build(x -> x.putSerial(true, 1, noon)));
        assertEquals("0", build(x -> x.putSerial(true, 0, 0)));
        assertEquals("0", build(x -> x.putSerial(true, 0, 1000)));
    }

    @Test
    void writesReferencesAndColors() {
        assertEquals("A1", build(x -> x.putCellReference(1, 1)));
        assertEquals("Z9", build(x -> x.putCellReference(9, 26)));
        assertEquals("AA10", build(x -> x.putCellReference(10, 27)));
        assertEquals("ZZ1", build(x -> x.putCellReference(1, 702)));
        assertEquals("AAA1", build(x -> x.putCellReference(1, 703)));
        assertEquals("XFD1048576", build(x -> x.putCellReference(1_048_576, 16_384)));
        assertEquals("B2:AMJ1024", build(x -> x.putRange(2, 2, 1024, 1024)));
        assertEquals("FF305496 00000000", build(x -> x.putArgb(0xFF305496).raw(' ').putArgb(0)));
        for (int column = 1; column <= 16_384; column++) {
            int expected = column;
            assertEquals(de.vensas.hypaxel.CellReference.columnName(expected), build(x -> x.putColumnName(expected)));
        }
    }

    @Test
    void growsAndClears() {
        XmlBuffer buffer = new XmlBuffer(16);
        for (int i = 0; i < 1000; i++) {
            buffer.raw("0123456789").raw(XmlBuffer.ascii("abc"));
        }
        assertEquals(13_000, buffer.length());
        assertTrue(buffer.array().length >= 13_000);
        buffer.clear();
        assertTrue(buffer.isEmpty());
    }
}
