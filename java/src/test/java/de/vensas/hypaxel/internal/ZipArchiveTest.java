package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.vensas.hypaxel.XlsxException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class ZipArchiveTest {
    private static final int CENTRAL_SIGNATURE = 0x0201_4b50;

    private static byte[] archive(ZipStream zip, String... namesAndContents) {
        for (int i = 0; i < namesAndContents.length; i += 2) {
            zip.startEntry(namesAndContents[i]);
            byte[] data = namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8);
            zip.write(data, 0, data.length);
            zip.finishEntry();
        }
        zip.finish();
        byte[] bytes = Arrays.copyOf(zip.output(), zip.outputLength());
        zip.end();
        return bytes;
    }

    private static byte[] archive(String... namesAndContents) {
        return archive(new ZipStream(6, 1024), namesAndContents);
    }

    private static byte[] readAll(byte[] bytes, String name) throws IOException {
        ZipArchive zip = ZipArchive.read(ReadSource.of(bytes));
        ZipArchive.Entry entry = zip.find(name);
        if (entry == null) {
            throw new AssertionError("missing " + name);
        }
        ByteInput in = zip.open(entry);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[777];
        for (int n; (n = in.read(buf, 0, buf.length)) > 0; ) {
            out.write(buf, 0, n);
        }
        assertEquals(-1, in.read(buf, 0, buf.length));
        assertEquals(0, new byte[0].length);
        in.close();
        return out.toByteArray();
    }

    private static int lastCentralHeader(byte[] bytes) {
        for (int i = bytes.length - 4; i >= 0; i--) {
            if (ZipArchive.le32(bytes, i) == CENTRAL_SIGNATURE) {
                return i;
            }
        }
        throw new AssertionError("no central header");
    }

    @Test
    void readsEntriesWrittenByTheWriter() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 200_000; i++) {
            big.append(i).append(',');
        }
        byte[] bytes = archive("a.txt", "hello", "dir/b.txt", big.toString());
        assertEquals("hello", new String(readAll(bytes, "a.txt"), StandardCharsets.UTF_8));
        assertEquals(big.toString(), new String(readAll(bytes, "/DIR/B.TXT"), StandardCharsets.UTF_8));
        ZipArchive zip = ZipArchive.read(ReadSource.of(bytes));
        assertEquals(2, zip.entries().size());
        assertNull(zip.find("missing"));
    }

    @Test
    void readsStoredEntriesAndZip64() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] data = "stored data".getBytes(StandardCharsets.UTF_8);
            ZipEntry entry = new ZipEntry("s.txt");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(data.length);
            CRC32 crc = new CRC32();
            crc.update(data);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }
        assertEquals("stored data", new String(readAll(out.toByteArray(), "s.txt"), StandardCharsets.UTF_8));

        // Low limits make the writer emit ZIP64 records for small archives.
        byte[] zip64 = archive(new ZipStream(1, 1024, 10, 1), "a.txt", "more than ten bytes", "b.txt", "second entry");
        assertEquals("more than ten bytes", new String(readAll(zip64, "a.txt"), StandardCharsets.UTF_8));
        assertEquals("second entry", new String(readAll(zip64, "b.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNonZipInput() {
        XlsxException e = assertThrows(XlsxException.class, () -> ZipArchive.read(ReadSource.of("not a zip file at all, really not".getBytes(StandardCharsets.UTF_8))));
        assertEquals(XlsxException.Kind.INVALID_FILE, e.kind());
        assertTrue(assertThrows(XlsxException.class, () -> ZipArchive.read(ReadSource.of(new byte[5]))).getMessage().contains("too small"));
        byte[] ole = new byte[512];
        System.arraycopy(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}, 0, ole, 0, 8);
        assertTrue(assertThrows(XlsxException.class, () -> ZipArchive.read(ReadSource.of(ole))).getMessage().contains("legacy .xls"));
        byte[] empty = {0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertTrue(assertThrows(XlsxException.class, () -> ZipArchive.read(ReadSource.of(empty))).getMessage().contains("no entries"));
    }

    @Test
    void detectsCorruption() {
        byte[] bytes = archive("a.txt", "x".repeat(10_000));
        bytes[40] ^= (byte) 0xFF;
        XlsxException e = assertThrows(XlsxException.class, () -> readAll(bytes, "a.txt"));
        assertEquals(XlsxException.Kind.MALFORMED, e.kind());
        assertEquals("a.txt", e.part());
    }

    @Test
    void detectsWrongSizesAndChecksums() {
        byte[] understated = archive("a.txt", "x".repeat(10_000));
        int cd = lastCentralHeader(understated);
        understated[cd + 24] = 100;
        understated[cd + 25] = 0;
        assertTrue(assertThrows(XlsxException.class, () -> readAll(understated, "a.txt")).getMessage().contains("larger than its declared size"));

        byte[] overstated = archive("a.txt", "x".repeat(100));
        cd = lastCentralHeader(overstated);
        overstated[cd + 25] = 1;
        assertTrue(assertThrows(XlsxException.class, () -> readAll(overstated, "a.txt")).getMessage().contains("shorter than its declared size"));

        byte[] badCrc = archive("a.txt", "x".repeat(100));
        cd = lastCentralHeader(badCrc);
        badCrc[cd + 16] ^= 1;
        assertTrue(assertThrows(XlsxException.class, () -> readAll(badCrc, "a.txt")).getMessage().contains("CRC-32"));

        byte[] encrypted = archive("a.txt", "x");
        cd = lastCentralHeader(encrypted);
        encrypted[cd + 8] |= 1;
        assertEquals(XlsxException.Kind.UNSUPPORTED, assertThrows(XlsxException.class, () -> readAll(encrypted, "a.txt")).kind());

        byte[] method = archive("a.txt", "x");
        cd = lastCentralHeader(method);
        method[cd + 10] = 12;
        assertTrue(assertThrows(XlsxException.class, () -> readAll(method, "a.txt")).getMessage().contains("compression method 12"));

        byte[] header = archive("a.txt", "x");
        header[0] = 0;
        assertTrue(assertThrows(XlsxException.class, () -> readAll(header, "a.txt")).getMessage().contains("local header"));
    }

    @Test
    void truncatedInputIsAnErrorNotACrash() {
        byte[] bytes = archive("a.txt", "hello world");
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            try {
                readAll(truncated, "a.txt");
            } catch (XlsxException | IOException expected) {
                // Rejected as it should be.
            }
        }
        byte[] stored = archive(new ZipStream(0, 1024), "a.txt", "hello world");
        int cd = lastCentralHeader(stored);
        byte[] missingData = Arrays.copyOf(stored, stored.length);
        missingData[cd + 20] = (byte) 0xFF;
        missingData[cd + 21] = (byte) 0xFF;
        missingData[cd + 24] = (byte) 0xFF;
        missingData[cd + 25] = (byte) 0xFF;
        assertThrows(XlsxException.class, () -> readAll(missingData, "a.txt"));
    }

    @Test
    void readsByteArraysWithPositionalReads() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        ReadSource source = ReadSource.of(data);
        byte[] buf = new byte[4];
        assertEquals(2, source.read(3, buf, 0, 4));
        assertEquals(-1, source.read(5, buf, 0, 4));
        source.readFully(1, buf, 0, 3);
        assertArrayEquals(new byte[] {2, 3, 4, 0}, buf);
        assertThrows(XlsxException.class, () -> source.readFully(4, buf, 0, 2));
        source.close();
    }
}
