package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.TypeMap;
import de.vensas.hypaxel.internal.ValueWriter;
import de.vensas.hypaxel.internal.WorkbookEngine;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Forward-only XLSX writer.
 *
 * <p>Rows go top to bottom, cells left to right, one worksheet at a time. Every cell is
 * serialized immediately and compressed into a small buffer that is written to the destination
 * whenever it passes {@link XlsxOptions#flushThreshold()}, so memory stays constant regardless of
 * the number of rows. The destination is any {@link OutputStream}; it need not be seekable.
 *
 * <pre>{@code
 * try (XlsxWriter writer = XlsxWriter.create(Path.of("report.xlsx"))) {
 *     StyleId bold = writer.addStyle(Style.builder().bold().build());
 *     Worksheet sheet = writer.addWorksheet("Sales");
 *     sheet.write("Product", bold).write("Units", bold).endRow();
 *     sheet.write("Widget").write(3).endRow();
 *     sheet.write("Total").writeFormula("SUM(B2:B2)").endRow();
 * }
 * }</pre>
 *
 * <p>{@link #close()} completes the workbook. When an export fails half-way, call
 * {@link #abort()} before the writer is closed so that the destination does not receive a
 * workbook that looks complete.
 *
 * <p>Instances are not thread-safe.
 */
public final class XlsxWriter implements Closeable {
    /** The XLSX media type. */
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    /** Maximum number of rows per worksheet. */
    public static final int MAX_ROWS = WorkbookEngine.MAX_ROWS;
    /** Maximum number of columns per worksheet. */
    public static final int MAX_COLUMNS = WorkbookEngine.MAX_COLUMNS;
    /** Maximum number of characters in a cell. */
    public static final int MAX_CELL_TEXT_LENGTH = WorkbookEngine.MAX_TEXT_LENGTH;

    private enum State {
        OPEN,
        FINISHED,
        ABORTED,
        CLOSED
    }

    private final OutputStream out;
    private final boolean closeOutput;
    private final WorkbookEngine engine;
    private final ValueWriter values;
    private Worksheet current;
    private State state = State.OPEN;

    private XlsxWriter(OutputStream out, XlsxOptions options, boolean closeOutput) {
        this.out = Objects.requireNonNull(out, "out");
        this.closeOutput = closeOutput;
        this.engine = new WorkbookEngine(Objects.requireNonNull(options, "options"));
        this.values = new ValueWriter(engine, options.zone() == null ? ZoneId.systemDefault() : options.zone());
    }

    /** Creates a writer with the default options. */
    public static XlsxWriter create(OutputStream out) {
        return create(out, XlsxOptions.DEFAULT);
    }

    /** Creates a writer. */
    public static XlsxWriter create(OutputStream out, XlsxOptions options) {
        return new XlsxWriter(out, options, options.closeOutput());
    }

    /** Creates a writer for a file, replacing an existing one. Closing the writer closes the file. */
    public static XlsxWriter create(Path file) throws IOException {
        return create(file, XlsxOptions.DEFAULT);
    }

    /** Creates a writer for a file, replacing an existing one. Closing the writer closes the file. */
    public static XlsxWriter create(Path file, XlsxOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return new XlsxWriter(Files.newOutputStream(file), options, true);
    }

    /** Registers a cell format. Identical styles share a handle. */
    public StyleId addStyle(Style style) {
        ensureOpen();
        int index = engine.addStyle(Objects.requireNonNull(style, "style"));
        return index == 0 ? StyleId.DEFAULT : new StyleId(index);
    }

    /** Adds a worksheet with default options. The previous worksheet is completed. */
    public Worksheet addWorksheet(String name) {
        return addWorksheet(name, SheetOptions.DEFAULT);
    }

    /** Adds a worksheet with column widths, frozen panes and other settings. The previous worksheet is completed. */
    public Worksheet addWorksheet(String name, SheetOptions options) {
        ensureOpen();
        engine.startSheet(Objects.requireNonNull(name, "name"), Objects.requireNonNull(options, "options"));
        if (current != null) {
            current.complete();
        }
        current = new Worksheet(this, engine, values, name);
        return current;
    }

    /**
     * Adds a worksheet with one row per item, a styled and frozen header row, an auto filter and
     * column widths from {@link ExcelColumn#width()} or estimated from the header.
     */
    public <T> Worksheet writeWorksheet(String name, Class<T> type, Iterable<? extends T> items) throws IOException {
        Worksheet sheet = addTableWorksheet(name, type);
        sheet.writeObjects(type, items, ObjectOptions.builder().autoFilter(true).build());
        return sheet;
    }

    /** Stream variant of {@link #writeWorksheet(String, Class, Iterable)}. The stream is not closed. */
    public <T> Worksheet writeWorksheet(String name, Class<T> type, Stream<? extends T> items) throws IOException {
        Worksheet sheet = addTableWorksheet(name, type);
        sheet.writeObjects(type, items, ObjectOptions.builder().autoFilter(true).build());
        return sheet;
    }

    private Worksheet addTableWorksheet(String name, Class<?> type) {
        double[] widths = TypeMap.of(Objects.requireNonNull(type, "type")).widths();
        return addWorksheet(name, SheetOptions.builder().freezeRows(1).columnWidths(widths).build());
    }

    /** Returns the number of worksheets added so far. */
    public int worksheetCount() {
        return engine.sheetCount();
    }

    /** Writes buffered output to the destination and flushes it, e.g. to send the first bytes of a download early. */
    public void flush() throws IOException {
        if (state == State.OPEN) {
            drain();
        }
        out.flush();
    }

    /**
     * Completes the workbook and writes everything to the destination, which stays open. A
     * workbook without worksheets gets an empty one named "Sheet1". Further calls do nothing.
     */
    public void finish() throws IOException {
        if (state != State.OPEN) {
            return;
        }
        engine.finish();
        if (current != null) {
            current.complete();
        }
        state = State.FINISHED;
        try {
            drain();
            out.flush();
        } finally {
            engine.release();
        }
    }

    /**
     * Stops writing without completing the workbook: nothing more is written, so the destination
     * holds a truncated file that readers reject. Use it when an export fails.
     */
    public void abort() {
        if (state == State.OPEN) {
            state = State.ABORTED;
            if (current != null) {
                current.complete();
            }
            engine.release();
        }
    }

    /**
     * Completes the workbook unless it was finished or aborted, then closes the destination
     * unless {@link XlsxOptions#closeOutput()} is false.
     */
    @Override
    public void close() throws IOException {
        if (state == State.CLOSED) {
            return;
        }
        try {
            if (state == State.OPEN) {
                try {
                    finish();
                } finally {
                    abort();
                }
            }
        } finally {
            state = State.CLOSED;
            if (closeOutput) {
                out.close();
            }
        }
    }

    void drainIfReady() throws IOException {
        if (engine.outputReady()) {
            drain();
        }
    }

    private void drain() throws IOException {
        try {
            engine.drainTo(out);
        } catch (IOException | RuntimeException e) {
            // The destination is in an unknown state, so the workbook cannot be completed.
            abort();
            throw e;
        }
    }

    private void ensureOpen() {
        if (state != State.OPEN) {
            throw new XlsxException(XlsxException.Kind.INVALID_STATE, "the writer is already " + state.name().toLowerCase(Locale.ROOT));
        }
    }
}
