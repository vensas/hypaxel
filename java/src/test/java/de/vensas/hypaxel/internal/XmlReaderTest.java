package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class XmlReaderTest {
    private static XmlReader reader(String xml, int bufferSize) {
        return new XmlReader(ByteInput.of(xml.getBytes(StandardCharsets.UTF_8)), "test.xml", bufferSize, XmlReader.MAX_TOKEN);
    }

    private static String s(XmlReader r, int start, int end) {
        return new String(r.buffer(), start, end - start, StandardCharsets.UTF_8);
    }

    private static List<String> events(String xml, int bufferSize) throws IOException {
        XmlReader r = reader(xml, bufferSize);
        List<String> out = new ArrayList<>();
        while (true) {
            int event = r.next();
            switch (event) {
                case XmlReader.EOF:
                    return out;
                case XmlReader.START:
                case XmlReader.EMPTY: {
                    StringBuilder tag = new StringBuilder("<").append(s(r, r.nameStart, r.nameEnd));
                    while (r.nextAttribute()) {
                        tag.append(' ').append(s(r, r.attrNameStart, r.attrNameEnd)).append('=').append(s(r, r.valueStart, r.valueEnd));
                    }
                    out.add(tag.append(event == XmlReader.EMPTY ? "/>" : ">").toString());
                    break;
                }
                case XmlReader.END:
                    out.add("</" + s(r, r.nameStart, r.nameEnd) + ">");
                    break;
                case XmlReader.TEXT:
                    out.add("T:" + s(r, r.textStart, r.textEnd));
                    break;
                default:
                    out.add("C:" + s(r, r.textStart, r.textEnd));
                    break;
            }
        }
    }

    @Test
    void tokenizesAcrossSmallBuffers() throws IOException {
        String xml = "<?xml version=\"1.0\"?>\n<x:root xmlns:x=\"urn:a\" xmlns=\"urn:b\" x:id=\"7\"><!-- a > comment --><c r='A1' t = \"a>b\">1 &amp; 2</c><e/>"
                + "<![CDATA[<raw>]]></x:root >";
        List<String> expected = List.of("T:\n", "<root id=7>", "<c r=A1 t=a>b>", "T:1 &amp; 2", "</c>", "<e/>", "C:<raw>", "</root>");
        for (int size : new int[] {8, 13, 64, 65_536}) {
            assertEquals(expected, events(xml, size), "buffer " + size);
        }
    }

    @Test
    void handlesByteOrderMarks() throws IOException {
        XmlReader r = new XmlReader(ByteInput.of(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '<', 'a', '/', '>'}), "t");
        assertEquals(XmlReader.EMPTY, r.next());
        assertEquals("a", s(r, r.nameStart, r.nameEnd));
        XmlReader utf16 = new XmlReader(ByteInput.of(new byte[] {(byte) 0xFF, (byte) 0xFE, '<', 0, 'a', 0}), "t");
        XlsxException e = assertThrows(XlsxException.class, utf16::next);
        assertEquals(XlsxException.Kind.UNSUPPORTED, e.kind());
        assertTrue(e.getMessage().contains("UTF-16"));
        assertEquals(XmlReader.EOF, reader("", 16).next());
    }

    @Test
    void rejectsDoctypesAndTruncation() throws IOException {
        assertThrows(XlsxException.class, () -> reader("<!DOCTYPE x [<!ENTITY a \"b\">]><x/>", 64).next());
        assertTrue(assertThrows(XlsxException.class, () -> reader("<!ELEMENT x ANY><x/>", 64).next()).getMessage().contains("markup declaration"));
        XmlReader open = reader("<a><b>", 64);
        open.next();
        open.next();
        assertTrue(assertThrows(XlsxException.class, open::next).getMessage().contains("unexpected end"));
        for (String truncated : new String[] {"<a attr=\"never closed", "<a></a", "<?pi", "<!-- open", "<![CDATA[open", "<", "<a>text"}) {
            XmlReader r = reader(truncated, 8);
            XlsxException e = assertThrows(XlsxException.class, () -> {
                while (r.next() != XmlReader.EOF) {
                    // Read to the damage.
                }
            }, truncated);
            assertEquals("test.xml", e.part());
        }
        assertTrue(assertThrows(XlsxException.class, () -> reader("< />", 64).next()).getMessage().contains("no name"));
    }

    @Test
    void readsTextAndSkipsElements() throws IOException {
        XmlReader r = reader("<a><b>x<i>y</i><![CDATA[&z]]>&amp;</b><skip><deep><deeper/></deep></skip><v>12</v><v>1<!--c-->2</v></a>", 8);
        r.next();
        r.next();
        ByteBuilder out = new ByteBuilder(4);
        r.readTextInto(out, false);
        assertEquals("xy&z&", out.toString());
        r.next();
        r.skipElement();
        assertEquals(XmlReader.START, r.next());
        assertTrue(r.tryRawText());
        assertEquals("12", s(r, r.textStart, r.textEnd));
        assertEquals(XmlReader.START, r.next());
        assertFalse(r.tryRawText());
        out.clear();
        r.readTextInto(out, false);
        assertEquals("12", out.toString());
        assertEquals(XmlReader.END, r.next());
        assertEquals(XmlReader.EOF, r.next());

        XmlReader limited = reader("<t>0123456789<x/>0123456789</t>", 64);
        limited.next();
        ByteBuilder text = new ByteBuilder(4);
        limited.readTextInto(text, false, 5);
        assertEquals("0123456789", text.toString());
        XmlReader bad = reader("<t>a &nope; b</t>", 64);
        bad.next();
        assertThrows(XlsxException.class, () -> bad.readTextInto(new ByteBuilder(4), false));
    }

    @Test
    void decodesAttributes() throws IOException {
        XmlReader r = reader("<a x:name=\"a &amp; b\" bad=\"&nope;\" raw='r1' broken=x/>", 64);
        r.next();
        assertEquals("a & b", r.attribute("name".getBytes(StandardCharsets.US_ASCII)));
        assertNull(r.attribute("bad".getBytes(StandardCharsets.US_ASCII)));
        assertNull(r.attribute("missing".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(r.nextAttribute());
        assertTrue(r.nextAttribute());
        assertTrue(r.nextAttribute());
        assertTrue(r.attributeIs("raw".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("r1", s(r, r.valueStart, r.valueEnd));
        assertFalse(r.nextAttribute());
        XmlReader unterminated = reader("<a x=\"1 y/>", 64);
        assertThrows(XlsxException.class, unterminated::next);
        XmlReader noValue = reader("<a x  />", 64);
        noValue.next();
        assertFalse(noValue.nextAttribute());
    }

    @Test
    void decodesEntitiesAndExcelEscapes() {
        ByteBuilder out = new ByteBuilder(4);
        out.append((byte) 'p');
        byte[] raw = "a &lt;&gt;&amp;&quot;&apos; &#65;&#x42;&#X43; _x000D__x005F_x0041_ _xD83D__xDE00_ _xD83D_ snake_case _x12_\r\nx\ry"
                .getBytes(StandardCharsets.UTF_8);
        assertNull(XmlText.decode(out, raw, 0, raw.length, true, true));
        assertEquals("pa <>&\"' ABC \r_x0041_ \uD83D\uDE00 _xD83D_ snake_case _x12_\nx\ny", out.toString());

        ByteBuilder plain = new ByteBuilder(4);
        byte[] escape = "_x0041_ &amp;".getBytes(StandardCharsets.UTF_8);
        assertNull(XmlText.decode(plain, escape, 0, escape.length, false, false));
        assertEquals("_x0041_ &amp;", plain.toString());

        for (String bad : new String[] {"&bogus;", "&#;", "&#x110000;", "&#xD800;", "&amp", "&#123456789;", "&#xZZ;"}) {
            ByteBuilder kept = new ByteBuilder(4);
            kept.append((byte) 'k');
            byte[] b = bad.getBytes(StandardCharsets.UTF_8);
            assertTrue(XmlText.decode(kept, b, 0, b.length, true, false) != null, bad);
            assertEquals("k", kept.toString(), bad);
        }
        ByteBuilder wide = new ByteBuilder(1);
        byte[] b = "&#xE9;&#x20AC;&#x1F600;".getBytes(StandardCharsets.UTF_8);
        assertNull(XmlText.decode(wide, b, 0, b.length, true, false));
        assertEquals("é€😀", wide.toString());
    }

    @Test
    void scansCommonCellShapes() throws IOException {
        String xml = "<c r=\"B2\" s=\"3\" t=\"s\" cm=\"1\"><v>12</v></c><c/><c s='1'></c><c t=\"inlineStr\"><is><t xml:space=\"preserve\"> a </t></is></c>"
                + "<c><f>1</f><v>2</v></c><c x:r=\"A1\"><v>1</v></c>";
        XmlReader r = reader(xml, 64);
        assertEquals(XmlReader.CELL_VALUE, r.scanCell());
        assertEquals("B2", s(r, r.cellRefStart, r.cellRefEnd));
        assertEquals("s", s(r, r.cellTypeStart, r.cellTypeEnd));
        assertEquals("3", s(r, r.cellStyleStart, r.cellStyleEnd));
        assertEquals("12", s(r, r.textStart, r.textEnd));
        assertEquals(XmlReader.CELL_EMPTY, r.scanCell());
        assertEquals(-1, r.cellRefStart);
        assertEquals(XmlReader.CELL_EMPTY, r.scanCell());
        assertEquals(XmlReader.CELL_INLINE, r.scanCell());
        assertEquals(" a ", s(r, r.textStart, r.textEnd));
        assertEquals(XmlReader.CELL_NONE, r.scanCell());
        assertEquals(XmlReader.START, r.next());
        r.skipElement();
        assertEquals(XmlReader.CELL_NONE, r.scanCell());
    }

    @Test
    void enforcesTheTokenLimit() {
        StringBuilder huge = new StringBuilder("<a b=\"");
        huge.append("x".repeat(XmlReader.MAX_TOKEN));
        huge.append("\"/>");
        XlsxException e = assertThrows(XlsxException.class, () -> reader(huge.toString(), 1024).next());
        assertEquals(XlsxException.Kind.LIMIT_EXCEEDED, e.kind());
    }
}
