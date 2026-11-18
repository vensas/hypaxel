package de.vensas.hypaxel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.vensas.hypaxel.XlsxException.Kind;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XlsxWriterLifecycleTest {
    /** Records how the writer uses its destination. */
    private static final class RecordingStream extends ByteArrayOutputStream {
        final List<Integer> writes = new ArrayList<>();
        int flushes;
        int closes;

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            writes.add(length);
            super.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushes++;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class FailingStream extends OutputStream {
        int closes;

        @Override
        public void write(int b) throws IOException {
            throw new IOException("connection reset");
        }

        @Override
        public void close() {
            closes++;
        }
    }

    @Test
    void exposesTheContentType() {
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", XlsxWriter.CONTENT_TYPE);
        assertEquals(1_048_576, XlsxWriter.MAX_ROWS);
        assertEquals(16_384, XlsxWriter.MAX_COLUMNS);
    }

    @Test
    void streamsOutputWhileRowsAreWritten() throws Exception {
        RecordingStream out = new RecordingStream();
        XlsxOptions options = XlsxOptions.builder().flushThreshold(4096).xmlBufferSize(4096).build();
        try (XlsxWriter writer = XlsxWriter.create(out, options)) {
            Worksheet sheet = writer.addWorksheet("S");
            for (int i = 0; i < 20_000; i++) {
                sheet.write(i).write("row " + i * 7919).write(i * 0.37).endRow();
            }
            assertTrue(out.writes.size() >= 5, "output must reach the destination before the workbook is finished");
            assertEquals(0, out.closes);
        }
        assertEquals(1, out.closes);
        assertTrue(out.flushes >= 1);
        for (int length : out.writes.subList(0, out.writes.size() - 1)) {
            assertTrue(length >= 4096, "chunks are at least the flush threshold");
        }
        try (XSSFWorkbook workbook = Xlsx.read(out.toByteArray())) {
            assertEquals(20_000, workbook.getSheetAt(0).getPhysicalNumberOfRows());
            assertEquals("row " + 19_999 * 7919, workbook.getSheetAt(0).getRow(19_999).getCell(1).getStringCellValue());
        }
    }

    @Test
    void flushSendsBufferedBytes() throws Exception {
        RecordingStream out = new RecordingStream();
        try (XlsxWriter writer = XlsxWriter.create(out)) {
            writer.addWorksheet("S").write("first").endRow();
            assertTrue(out.writes.isEmpty());
            writer.flush();
            assertEquals(1, out.writes.size());
            assertEquals(1, out.flushes);
            assertEquals('P', out.toByteArray()[0]);
            assertEquals('K', out.toByteArray()[1]);
        }
    }

    @Test
    void finishLeavesTheDestinationOpenAndIsIdempotent() throws Exception {
        RecordingStream out = new RecordingStream();
        XlsxWriter writer = XlsxWriter.create(out);
        Worksheet sheet = writer.addWorksheet("S");
        sheet.write(1).endRow();
        writer.finish();
        byte[] finished = out.toByteArray();
        writer.finish();
        writer.flush();
        assertEquals(0, out.closes);
        assertArrayEquals(finished, out.toByteArray());

        assertEquals(Kind.INVALID_STATE, assertThrows(XlsxException.class, () -> sheet.write(2)).kind());
        assertEquals(Kind.INVALID_STATE, assertThrows(XlsxException.class, () -> writer.addWorksheet("T")).kind());
        XlsxException e = assertThrows(XlsxException.class, () -> writer.addStyle(Style.builder().bold().build()));
        assertEquals("the writer is already finished", e.getMessage());

        writer.close();
        writer.close();
        assertEquals(1, out.closes);
        assertArrayEquals(finished, out.toByteArray());
        try (XSSFWorkbook workbook = Xlsx.read(finished)) {
            assertEquals(1.0, workbook.getSheetAt(0).getRow(0).getCell(0).getNumericCellValue());
        }
    }

    @Test
    void keepsTheDestinationOpenOnRequest() throws Exception {
        RecordingStream out = new RecordingStream();
        try (XlsxWriter writer = XlsxWriter.create(out, XlsxOptions.builder().closeOutput(false).build())) {
            writer.addWorksheet("S").write(1).endRow();
        }
        assertEquals(0, out.closes);
        assertFalse(XlsxOptions.builder().closeOutput(false).build().closeOutput());
        assertTrue(XlsxOptions.DEFAULT.closeOutput());
    }

    @Test
    void completedWorksheetsRejectWrites() throws IOException {
        try (XlsxWriter writer = XlsxWriter.create(OutputStream.nullOutputStream())) {
            Worksheet first = writer.addWorksheet("First");
            first.write(1);
            Worksheet second = writer.addWorksheet("Second");
            XlsxException e = assertThrows(XlsxException.class, () -> first.write(2));
            assertEquals(Kind.INVALID_STATE, e.kind());
            assertEquals("worksheet \"First\" is already completed", e.getMessage());
            assertThrows(XlsxException.class, () -> first.write("x"));
            assertThrows(XlsxException.class, first::endRow);
            assertThrows(XlsxException.class, () -> first.addRow(1, 2));
            second.write(3).endRow();
        }
    }

    @Test
    void abortLeavesATruncatedFile() throws Exception {
        RecordingStream out = new RecordingStream();
        XlsxWriter writer = XlsxWriter.create(out, XlsxOptions.builder().flushThreshold(1024).build());
        Worksheet sheet = writer.addWorksheet("S");
        for (int i = 0; i < 5000; i++) {
            sheet.write("row " + i * 31).endRow();
        }
        writer.abort();
        writer.abort();
        int written = out.size();
        assertTrue(written > 0);
        assertThrows(XlsxException.class, () -> sheet.write(1));
        writer.finish();
        writer.close();
        assertEquals(written, out.size());
        assertEquals(1, out.closes);
        assertThrows(Exception.class, () -> Xlsx.read(out.toByteArray()));
    }

    @Test
    void destinationFailuresSurfaceAsIoExceptionsAndStopTheWriter() throws IOException {
        FailingStream out = new FailingStream();
        XlsxWriter writer = XlsxWriter.create(out, XlsxOptions.builder().flushThreshold(1024).xmlBufferSize(1024).compressionLevel(0).build());
        Worksheet sheet = writer.addWorksheet("S");
        IOException e = assertThrows(IOException.class, () -> {
            for (int i = 0; i < 50_000; i++) {
                sheet.write("some text").endRow();
            }
        });
        assertEquals("connection reset", e.getMessage());
        assertThrows(XlsxException.class, () -> sheet.write(1));
        writer.close();
        assertEquals(1, out.closes);
    }

    @Test
    void closeReportsAFailingDestination() {
        FailingStream out = new FailingStream();
        XlsxWriter writer = XlsxWriter.create(out);
        writer.addWorksheet("S").write(1);
        assertThrows(IOException.class, writer::close);
        assertEquals(1, out.closes);
    }

    @Test
    void writesFiles(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("report.xlsx");
        try (XlsxWriter writer = XlsxWriter.create(file)) {
            writer.addWorksheet("S").write("to disk").endRow();
        }
        XlsxOptions options = XlsxOptions.builder().compressionLevel(9).closeOutput(false).build();
        Path smaller = directory.resolve("smaller.xlsx");
        try (XlsxWriter writer = XlsxWriter.create(smaller, options)) {
            writer.addWorksheet("S").write("to disk").endRow();
        }
        for (Path path : List.of(file, smaller)) {
            try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
                assertEquals("to disk", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            }
        }
    }

    @Test
    void producesIdenticalBytesForIdenticalInput() {
        Xlsx.Body body = writer -> {
            Worksheet sheet = writer.addWorksheet("S");
            for (int i = 0; i < 100; i++) {
                sheet.write(i).write("text " + i).endRow();
            }
        };
        assertArrayEquals(Xlsx.write(body), Xlsx.write(body));
    }

    @Test
    void supportsEveryCompressionLevel() throws Exception {
        int stored = 0;
        for (int level = 0; level <= 9; level++) {
            XlsxOptions options = XlsxOptions.builder().compressionLevel(level).build();
            assertEquals(level, options.compressionLevel());
            byte[] xlsx = Xlsx.write(options, writer -> {
                Worksheet sheet = writer.addWorksheet("S");
                for (int i = 0; i < 2000; i++) {
                    sheet.write("repetitive text").write(i).endRow();
                }
            });
            if (level == 0) {
                stored = xlsx.length;
            } else {
                assertTrue(xlsx.length < stored / 4);
            }
            try (XSSFWorkbook workbook = Xlsx.read(xlsx)) {
                assertEquals(1999.0, workbook.getSheetAt(0).getRow(1999).getCell(1).getNumericCellValue());
            }
        }
    }
}
