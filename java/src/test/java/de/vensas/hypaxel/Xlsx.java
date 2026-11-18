package de.vensas.hypaxel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Test support: builds workbooks in memory and reads them back. */
final class Xlsx {
    @FunctionalInterface
    interface Body {
        void write(XlsxWriter writer) throws IOException;
    }

    static {
        // Test workbooks are repetitive enough to trip POI's zip bomb heuristic.
        ZipSecureFile.setMinInflateRatio(0);
    }

    private Xlsx() {
    }

    static byte[] write(Body body) {
        return write(XlsxOptions.DEFAULT, body);
    }

    static byte[] write(XlsxOptions options, Body body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (XlsxWriter writer = XlsxWriter.create(out, options)) {
            body.write(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static XSSFWorkbook read(byte[] xlsx) {
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(xlsx));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static XSSFWorkbook roundTrip(Body body) {
        return read(write(body));
    }

    static XSSFWorkbook roundTrip(XlsxOptions options, Body body) {
        return read(write(options, body));
    }

    /** Reads the archive with the JDK's streaming reader, which relies on the data descriptors. */
    static Map<String, String> parts(byte[] xlsx) {
        Map<String, String> parts = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return parts;
    }

    static String part(byte[] xlsx, String name) {
        String part = parts(xlsx).get(name);
        if (part == null) {
            throw new AssertionError("no part " + name);
        }
        return part;
    }
}
