package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZipStreamTest {
    private static final int END_SIGNATURE = 0x0605_4b50;
    private static final int ZIP64_END_SIGNATURE = 0x0606_4b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x0706_4b50;
    private static final int CENTRAL_SIGNATURE = 0x0201_4b50;

    private static byte[] bytes(ZipStream zip) {
        byte[] archive = Arrays.copyOf(zip.output(), zip.outputLength());
        assertEquals(archive.length, zip.position());
        zip.end();
        return archive;
    }

    private static void add(ZipStream zip, String name, byte[] content) {
        zip.startEntry(name);
        zip.write(content, 0, content.length);
        zip.finishEntry();
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> readWithJdk(byte[] archive, Path directory) throws IOException {
        Path file = Files.createTempFile(directory, "archive", ".zip");
        Files.write(file, archive);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                byte[] content = zip.getInputStream(entry).readAllBytes();
                assertEquals(content.length, entry.getSize());
                CRC32 crc = new CRC32();
                crc.update(content);
                assertEquals(crc.getValue(), entry.getCrc());
                entries.put(entry.getName(), content);
            }
        }
        return entries;
    }

    private static Map<String, byte[]> readWithCommonsCompress(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (org.apache.commons.compress.archivers.zip.ZipFile zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setSeekableByteChannel(new SeekableInMemoryByteChannel(archive)).get()) {
            for (ZipArchiveEntry entry : Collections.list(zip.getEntriesInPhysicalOrder())) {
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }

    private static ByteBuffer buffer(byte[] archive) {
        return ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static int lastIndexOf(byte[] archive, int signature) {
        ByteBuffer buffer = buffer(archive);
        for (int i = archive.length - 4; i >= 0; i--) {
            if (buffer.getInt(i) == signature) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void roundTripsThroughStreamingAndRandomAccessReaders(@TempDir Path directory) throws IOException {
        byte[] large = text("<x>0123456789</x>".repeat(50_000));
        ZipStream zip = new ZipStream(1, 1024);
        zip.startEntry("a.txt");
        zip.write(text("hello "), 0, 6);
        zip.write(text("xxworldxx"), 2, 5);
        zip.write(new byte[0], 0, 0);
        zip.finishEntry();
        add(zip, "dir/b.xml", large);
        zip.startEntry("empty");
        zip.finishEntry();
        zip.finish();
        byte[] archive = bytes(zip);

        Map<String, byte[]> streamed = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                streamed.put(entry.getName(), in.readAllBytes());
            }
        }
        assertEquals("[a.txt, dir/b.xml, empty]", streamed.keySet().toString());
        assertEquals("hello world", new String(streamed.get("a.txt"), StandardCharsets.UTF_8));
        assertArrayEquals(large, streamed.get("dir/b.xml"));
        assertEquals(0, streamed.get("empty").length);

        Map<String, byte[]> random = readWithJdk(archive, directory);
        assertArrayEquals(large, random.get("dir/b.xml"));
        assertEquals(3, random.size());
        assertArrayEquals(large, readWithCommonsCompress(archive).get("dir/b.xml"));

        assertEquals(-1, lastIndexOf(archive, ZIP64_END_SIGNATURE), "small archives stay in the classic format");
        ByteBuffer end = buffer(archive);
        int endOffset = archive.length - 22;
        assertEquals(END_SIGNATURE, end.getInt(endOffset));
        assertEquals(3, end.getShort(endOffset + 10));
        assertEquals(CENTRAL_SIGNATURE, end.getInt(end.getInt(endOffset + 16)));
    }

    @Test
    void writesStoredEntriesAtLevelZero(@TempDir Path directory) throws IOException {
        byte[] content = text("stored content ".repeat(10_000));
        ZipStream zip = new ZipStream(0, 1024);
        add(zip, "stored.txt", content);
        zip.finish();
        byte[] archive = bytes(zip);
        assertTrue(archive.length > content.length);
        assertArrayEquals(content, readWithJdk(archive, directory).get("stored.txt"));
    }

    @Test
    void writesZip64RecordsForLargeEntriesAndOffsets(@TempDir Path directory) throws IOException {
        byte[] small = text("tiny");
        byte[] large = new byte[5000];
        new java.util.Random(1).nextBytes(large);

        ZipStream zip = new ZipStream(6, 1024, 1000, ZipStream.CLASSIC_ENTRY_LIMIT);
        add(zip, "small-at-start.txt", small);
        add(zip, "large.bin", large);
        add(zip, "small-after-limit.txt", small);
        zip.finish();
        byte[] archive = bytes(zip);

        Map<String, byte[]> jdk = readWithJdk(archive, directory);
        assertArrayEquals(small, jdk.get("small-at-start.txt"));
        assertArrayEquals(large, jdk.get("large.bin"));
        assertArrayEquals(small, jdk.get("small-after-limit.txt"));
        Map<String, byte[]> commons = readWithCommonsCompress(archive);
        assertArrayEquals(large, commons.get("large.bin"));
        assertArrayEquals(small, commons.get("small-after-limit.txt"));

        ByteBuffer buffer = buffer(archive);
        int endOffset = archive.length - 22;
        assertEquals(END_SIGNATURE, buffer.getInt(endOffset));
        assertEquals(3, buffer.getShort(endOffset + 10), "the entry count still fits");
        assertEquals(-1, buffer.getInt(endOffset + 16), "the directory offset is deferred to the ZIP64 record");

        int locator = endOffset - 20;
        assertEquals(ZIP64_LOCATOR_SIGNATURE, buffer.getInt(locator));
        long zip64End = buffer.getLong(locator + 8);
        assertEquals(1, buffer.getInt(locator + 16));
        assertEquals(ZIP64_END_SIGNATURE, buffer.getInt((int) zip64End));
        assertEquals(44, buffer.getLong((int) zip64End + 4));
        assertEquals(3, buffer.getLong((int) zip64End + 24));
        assertEquals(3, buffer.getLong((int) zip64End + 32));
        long directorySize = buffer.getLong((int) zip64End + 40);
        long directoryStart = buffer.getLong((int) zip64End + 48);
        assertEquals(zip64End, directoryStart + directorySize);

        int first = (int) directoryStart;
        assertEquals(CENTRAL_SIGNATURE, buffer.getInt(first));
        assertEquals(20, buffer.getShort(first + 6), "an entry within the limits needs no ZIP64");
        assertEquals(small.length, buffer.getInt(first + 24));
        assertEquals(0, buffer.getShort(first + 30));

        int second = first + 46 + "small-at-start.txt".length();
        assertEquals(CENTRAL_SIGNATURE, buffer.getInt(second));
        assertEquals(45, buffer.getShort(second + 6));
        assertEquals(-1, buffer.getInt(second + 20));
        assertEquals(-1, buffer.getInt(second + 24));
        assertEquals(20, buffer.getShort(second + 30), "sizes only: header plus two 8-byte fields");
        int secondExtra = second + 46 + "large.bin".length();
        assertEquals(1, buffer.getShort(secondExtra));
        assertEquals(16, buffer.getShort(secondExtra + 2));
        assertEquals(large.length, buffer.getLong(secondExtra + 4));
        long compressedSize = buffer.getLong(secondExtra + 12);
        assertTrue(compressedSize >= large.length);

        int third = secondExtra + 20;
        assertEquals(CENTRAL_SIGNATURE, buffer.getInt(third));
        assertEquals(small.length, buffer.getInt(third + 24));
        assertEquals(-1, buffer.getInt(third + 42), "the local header offset is deferred to the extra field");
        assertEquals(12, buffer.getShort(third + 30), "offset only: header plus one 8-byte field");
        int thirdExtra = third + 46 + "small-after-limit.txt".length();
        assertEquals(8, buffer.getShort(thirdExtra + 2));
        long thirdOffset = buffer.getLong(thirdExtra + 4);
        assertEquals(0x0403_4b50, buffer.getInt((int) thirdOffset));

        int largeOffset = buffer.getInt(second + 42);
        int descriptor = (int) (largeOffset + 30 + "large.bin".length() + compressedSize);
        assertEquals(0x0807_4b50, buffer.getInt(descriptor));
        assertEquals(compressedSize, buffer.getLong(descriptor + 8), "large entries get 8-byte sizes in the data descriptor");
        assertEquals(large.length, buffer.getLong(descriptor + 16));
        assertEquals(thirdOffset, descriptor + 24);
    }

    @Test
    void writesZip64EndRecordForManyEntries(@TempDir Path directory) throws IOException {
        ZipStream zip = new ZipStream(1, 1024, ZipStream.CLASSIC_SIZE_LIMIT, 4);
        for (int i = 0; i < 7; i++) {
            add(zip, "entry" + i, text("content " + i));
        }
        zip.finish();
        byte[] archive = bytes(zip);

        Map<String, byte[]> entries = readWithJdk(archive, directory);
        assertEquals(7, entries.size());
        assertEquals("content 6", new String(entries.get("entry6"), StandardCharsets.UTF_8));
        assertEquals(7, readWithCommonsCompress(archive).size());

        ByteBuffer buffer = buffer(archive);
        int endOffset = archive.length - 22;
        assertEquals((short) 0xFFFF, buffer.getShort(endOffset + 8));
        assertEquals((short) 0xFFFF, buffer.getShort(endOffset + 10));
        int zip64End = lastIndexOf(archive, ZIP64_END_SIGNATURE);
        assertEquals(7, buffer.getLong(zip64End + 32));
        assertEquals(buffer.getInt(endOffset + 16), buffer.getLong(zip64End + 48), "the classic offset stays valid when it fits");
    }

    @Test
    void realEntryCountLimitSwitchesToZip64(@TempDir Path directory) throws IOException {
        ZipStream zip = new ZipStream(1, 1024);
        byte[] content = text("x");
        int count = ZipStream.CLASSIC_ENTRY_LIMIT + 5;
        for (int i = 0; i < count; i++) {
            add(zip, "e" + i, content);
        }
        zip.finish();
        byte[] archive = bytes(zip);
        assertNotNull(readWithJdk(archive, directory).get("e" + (count - 1)));
        assertEquals(count, buffer(archive).getLong(lastIndexOf(archive, ZIP64_END_SIGNATURE) + 32));
    }

    @Test
    void drainsOutputIncrementally() {
        ZipStream zip = new ZipStream(1, 1024);
        zip.startEntry("a");
        int headerLength = zip.outputLength();
        assertEquals(31, headerLength);
        zip.clearOutput();
        assertEquals(0, zip.outputLength());
        assertEquals(headerLength, zip.position());
        byte[] random = new byte[300_000];
        new java.util.Random(7).nextBytes(random);
        zip.write(random, 0, random.length);
        zip.finishEntry();
        assertTrue(zip.outputLength() > 250_000, "incompressible data grows the output buffer");
        zip.finish();
        assertEquals(headerLength + zip.outputLength(), zip.position());
        zip.end();
    }

    @Test
    void rejectsMisuse() {
        ZipStream zip = new ZipStream(1, 1024);
        assertThrows(IllegalStateException.class, () -> zip.write(new byte[1], 0, 1));
        assertThrows(IllegalStateException.class, zip::finishEntry);
        zip.startEntry("a");
        assertThrows(IllegalStateException.class, () -> zip.startEntry("b"));
        assertThrows(IllegalStateException.class, zip::finish);
        zip.finishEntry();
        zip.finish();
        zip.end();
        assertTrue(zip.outputLength() > 0);
    }
}
