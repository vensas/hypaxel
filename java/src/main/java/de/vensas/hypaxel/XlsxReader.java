package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.ReadSource;
import de.vensas.hypaxel.internal.SharedStringTable;
import de.vensas.hypaxel.internal.SheetParser;
import de.vensas.hypaxel.internal.WorkbookPackage;
import de.vensas.hypaxel.internal.XmlReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Streaming XLSX reader.
 *
 * <p>Opening a reader parses the package structure, the sheet list and the cell formats.
 * Worksheet rows are then streamed: memory use is bounded by the shared string table plus the
 * widest row, whatever the number of rows.
 *
 * <pre>{@code
 * try (XlsxReader reader = XlsxReader.open(Path.of("orders.xlsx"));
 *         SheetReader rows = reader.worksheet("Orders")) {
 *     for (Row row = rows.nextRow(); row != null; row = rows.nextRow()) {
 *         System.out.println(row.number() + ": " + row.getString(1) + " " + row.getNumber(3));
 *     }
 * }
 * }</pre>
 *
 * <p>A reader may open several worksheets at the same time, also from different threads: the
 * worksheets share the parsed workbook and the shared string table, and read the source with
 * positional reads. Each {@link SheetReader} is used by one thread at a time.
 */
public final class XlsxReader implements Closeable {
    private final ReadSource source;
    private final WorkbookPackage workbook;
    private final ReadOptions options;
    private final List<String> sheetNames;
    private volatile SharedStringTable sharedStrings;
    private volatile boolean closed;

    private XlsxReader(ReadSource source, ReadOptions options) throws IOException {
        this.source = source;
        this.options = Objects.requireNonNull(options, "options");
        WorkbookPackage parsed;
        try {
            parsed = WorkbookPackage.read(source);
        } catch (IOException | RuntimeException e) {
            try {
                source.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        this.workbook = parsed;
        List<String> names = new ArrayList<>(workbook.sheets().size());
        for (SheetInfo sheet : workbook.sheets()) {
            names.add(sheet.name());
        }
        this.sheetNames = Collections.unmodifiableList(names);
    }

    /** Opens a workbook file. Closing the reader closes the file. */
    public static XlsxReader open(Path file) throws IOException {
        return open(file, ReadOptions.DEFAULT);
    }

    /** Opens a workbook file with custom limits. Closing the reader closes the file. */
    public static XlsxReader open(Path file, ReadOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return new XlsxReader(ReadSource.of(Objects.requireNonNull(file, "file")), options);
    }

    /**
     * Reads a workbook from a seekable channel, such as a {@link java.nio.channels.FileChannel}.
     * Closing the reader closes the channel.
     */
    public static XlsxReader open(SeekableByteChannel channel) throws IOException {
        return open(channel, ReadOptions.DEFAULT);
    }

    /** Reads a workbook from a seekable channel with custom limits. Closing the reader closes the channel. */
    public static XlsxReader open(SeekableByteChannel channel, ReadOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return new XlsxReader(ReadSource.of(channel), options);
    }

    /** Reads a workbook held in memory. The array is not copied and must not change while it is read. */
    public static XlsxReader open(byte[] xlsx) throws IOException {
        return open(xlsx, ReadOptions.DEFAULT);
    }

    /** Reads a workbook held in memory with custom limits. */
    public static XlsxReader open(byte[] xlsx, ReadOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return new XlsxReader(ReadSource.of(xlsx), options);
    }

    /**
     * Reads a workbook from a stream, such as an upload. The XLSX format keeps its directory at
     * the end of the file, so the stream is read completely into memory first (up to 2 GiB); for
     * large files prefer {@link #open(Path)}. The stream is not closed.
     */
    public static XlsxReader open(InputStream in) throws IOException {
        return open(in, ReadOptions.DEFAULT);
    }

    /** Reads a workbook from a stream with custom limits. See {@link #open(InputStream)}. */
    public static XlsxReader open(InputStream in, ReadOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return open(Objects.requireNonNull(in, "in").readAllBytes(), options);
    }

    /** Returns the sheets in workbook order, including hidden sheets and chart sheets. */
    public List<SheetInfo> sheets() {
        return workbook.sheets();
    }

    /** Returns the sheet names in workbook order. */
    public List<String> sheetNames() {
        return sheetNames;
    }

    /** Returns whether the workbook uses the 1904 date system. */
    public boolean isDate1904() {
        return workbook.date1904();
    }

    /**
     * Returns the number of entries in the shared string table, loading it if needed. Text that
     * repeats in a workbook is usually stored there once; this table is held in memory while
     * worksheets are read.
     */
    public int sharedStringCount() throws IOException {
        return sharedStrings().size();
    }

    /**
     * Opens the sheet with this name for reading, matched exactly and then ignoring case.
     *
     * @throws XlsxException {@link XlsxException.Kind#SHEET_NOT_FOUND} if there is no such sheet,
     *     {@link XlsxException.Kind#UNSUPPORTED} for chart sheets
     */
    public SheetReader worksheet(String name) throws IOException {
        return worksheet(sheetIndex(name));
    }

    private int sheetIndex(String name) {
        Objects.requireNonNull(name, "name");
        int index = sheetNames.indexOf(name);
        if (index < 0) {
            String lower = name.toLowerCase(Locale.ROOT);
            for (int i = 0; i < sheetNames.size() && index < 0; i++) {
                if (sheetNames.get(i).toLowerCase(Locale.ROOT).equals(lower)) {
                    index = i;
                }
            }
        }
        if (index < 0) {
            throw new XlsxException(XlsxException.Kind.SHEET_NOT_FOUND, "there is no sheet named \"" + name + "\"");
        }
        return index;
    }

    private void checkIndex(int index) {
        ensureOpen();
        if (index < 0 || index >= workbook.sheets().size()) {
            throw new XlsxException(XlsxException.Kind.SHEET_NOT_FOUND,
                    "there is no sheet at index " + index + "; the workbook has " + workbook.sheets().size());
        }
    }

    /**
     * Returns the Excel tables of the sheet with this name, matched exactly and then ignoring
     * case. See {@link #tables(int)}.
     *
     * @throws XlsxException {@link XlsxException.Kind#SHEET_NOT_FOUND} if there is no such sheet
     */
    public List<ExcelTable> tables(String name) throws IOException {
        return tables(sheetIndex(name));
    }

    /**
     * Returns the Excel tables of the sheet at a 0-based position in {@link #sheets()}, as the
     * {@link ExcelTable} values that {@link Worksheet#addTable(ExcelTable)} accepts; empty if the
     * sheet has none.
     *
     * <p>Tables live in their own parts, which are read on each call; reading rows does not touch
     * them. A table style that is not one of Excel's built-in styles is reported as
     * {@link TableStyle#NONE}.
     *
     * @throws XlsxException if a table part is damaged ({@link XlsxException.Kind#MALFORMED})
     */
    public List<ExcelTable> tables(int index) throws IOException {
        checkIndex(index);
        return workbook.readTables(index);
    }

    /** Opens the sheet at a 0-based position in {@link #sheets()} for reading. */
    public SheetReader worksheet(int index) throws IOException {
        checkIndex(index);
        SharedStringTable strings = sharedStrings();
        XmlReader xml = workbook.openSheet(index);
        return new SheetReader(workbook.sheets().get(index), new SheetParser(xml, strings, workbook.styles(), workbook.date1904(), options.includeEmptyRows()));
    }

    private SharedStringTable sharedStrings() throws IOException {
        SharedStringTable table = sharedStrings;
        if (table == null) {
            synchronized (this) {
                table = sharedStrings;
                if (table == null) {
                    ensureOpen();
                    table = workbook.readSharedStrings(options.maxSharedStringsBytes());
                    sharedStrings = table;
                }
            }
        }
        return table;
    }

    private void ensureOpen() {
        if (closed) {
            throw new XlsxException(XlsxException.Kind.INVALID_STATE, "the reader is already closed");
        }
    }

    /** Closes the file or channel the workbook is read from; worksheets that are still open then fail to read further. */
    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            sharedStrings = SharedStringTable.EMPTY;
            source.close();
        }
    }
}
