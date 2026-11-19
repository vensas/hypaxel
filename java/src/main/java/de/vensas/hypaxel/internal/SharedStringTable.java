package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.XlsxException;
import java.io.IOException;
import java.util.Arrays;

/** The shared string table of a workbook, read once and shared by all worksheets. */
public final class SharedStringTable {
    public static final SharedStringTable EMPTY = new SharedStringTable(new String[0], 0);

    private static final byte[] SI = Ascii.bytes("si");
    private static final byte[] T = Ascii.bytes("t");
    private static final byte[] R_PH = Ascii.bytes("rPh");
    private static final byte[] R_PR = Ascii.bytes("rPr");
    private static final byte[] PHONETIC_PR = Ascii.bytes("phoneticPr");

    private final String[] strings;
    private final long textBytes;

    private SharedStringTable(String[] strings, long textBytes) {
        this.strings = strings;
        this.textBytes = textBytes;
    }

    public String[] strings() {
        return strings;
    }

    public int size() {
        return strings.length;
    }

    /** Total UTF-8 length of the text in the table. */
    public long textBytes() {
        return textBytes;
    }

    public static SharedStringTable parse(XmlReader xml, long maxBytes) throws IOException {
        String[] strings = new String[1024];
        int count = 0;
        long total = 0;
        ByteBuilder item = new ByteBuilder(256);
        while (true) {
            item.clear();
            if (xml.depth() > 0 && xml.scanSharedString()) {
                String error = XmlText.decode(item, xml.buffer(), xml.textStart, xml.textEnd, true, true);
                if (error != null) {
                    throw xml.malformed(error);
                }
                checkLimit(xml, total + item.length(), maxBytes);
            } else {
                int event = xml.next();
                if (event == XmlReader.EOF) {
                    break;
                }
                if ((event != XmlReader.START && event != XmlReader.EMPTY) || !xml.nameIs(SI)) {
                    continue;
                }
                if (event == XmlReader.START) {
                    readItem(xml, item, total, maxBytes);
                }
            }
            total += item.length();
            if (count == strings.length) {
                if (count == Integer.MAX_VALUE - 8) {
                    throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, xml.part(), "the shared string table has too many entries");
                }
                strings = Arrays.copyOf(strings, (int) Math.min(Integer.MAX_VALUE - 8, count * 2L));
            }
            strings[count++] = item.toString();
        }
        return new SharedStringTable(Arrays.copyOf(strings, count), total);
    }

    /** Reads one {@code <si>} item: plain {@code <t>} or rich-text runs, without phonetic runs. */
    private static void readItem(XmlReader xml, ByteBuilder item, long total, long maxBytes) throws IOException {
        int target = xml.depth() - 1;
        while (true) {
            int event = xml.next();
            if (event == XmlReader.START) {
                if (xml.nameIs(T)) {
                    xml.readTextInto(item, true, (int) Math.min(Integer.MAX_VALUE - 8, Math.max(0, maxBytes - total)));
                } else if (xml.nameIs(R_PH) || xml.nameIs(PHONETIC_PR) || xml.nameIs(R_PR)) {
                    xml.skipElement();
                }
            } else if (event == XmlReader.END && xml.depth() == target) {
                return;
            }
            checkLimit(xml, total + item.length(), maxBytes);
        }
    }

    private static void checkLimit(XmlReader xml, long total, long maxBytes) {
        if (total > maxBytes) {
            throw new XlsxException(XlsxException.Kind.LIMIT_EXCEEDED, xml.part(),
                    "the shared string table exceeds " + maxBytes + " bytes (ReadOptions.maxSharedStringsBytes)");
        }
    }
}
