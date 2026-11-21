package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.SheetParser;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.OptionalDouble;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The rows of one worksheet, read front to back. Created by {@link XlsxReader#worksheet(String)}.
 *
 * <pre>{@code
 * try (SheetReader rows = reader.worksheet("Orders")) {
 *     for (Row row = rows.nextRow(); row != null; row = rows.nextRow()) {
 *         String customer = row.getString(1);
 *         double amount = row.getNumber(3);
 *     }
 * }
 * }</pre>
 *
 * <p>Rows that are missing from the file or only carry formatting are skipped; {@link Row#number()}
 * tells where a row is. With {@link ReadOptions.Builder#includeEmptyRows(boolean)}, rows that the
 * file stores without values are returned as well, with no cells. The worksheet is decompressed and parsed as it is read, so memory stays
 * bounded by the widest row. Close the reader (or read it to the end) to release the decompressor
 * early. Instances are not thread-safe.
 */
public final class SheetReader implements Closeable, Iterable<Row> {
    private final SheetInfo sheet;
    private final SheetParser parser;
    private final Row row;
    private boolean finished;
    private Exception layoutFailure;

    SheetReader(SheetInfo sheet, SheetParser parser) {
        this.sheet = sheet;
        this.parser = parser;
        this.row = new Row(parser.row());
    }

    /** Returns the sheet being read. */
    public SheetInfo sheet() {
        return sheet;
    }

    /**
     * Returns the column widths the sheet defines, explicit and auto-fitted alike, in file order.
     * Columns that are not covered have the default width. Like the outline settings, the widths
     * precede the rows in the file, so they are available before the first row is read, and they
     * stay available after the rows were read or the reader was closed.
     *
     * @throws XlsxException if the worksheet is damaged ({@link XlsxException.Kind#MALFORMED}), on
     *     this and every later call; or if the reader was closed before the layout was read
     *     ({@link XlsxException.Kind#INVALID_STATE})
     * @throws IOException if the source cannot be read
     */
    public List<ColumnWidth> columnWidths() throws IOException {
        readLayout();
        return parser.columnWidths();
    }

    /** Returns the width of a 1-based column; empty if the sheet leaves it at the default. */
    public OptionalDouble columnWidth(int column) throws IOException {
        for (ColumnWidth width : columnWidths()) {
            if (column >= width.firstColumn() && column <= width.lastColumn()) {
                return OptionalDouble.of(width.width());
            }
        }
        return OptionalDouble.empty();
    }

    /** Returns whether a 1-based column is hidden. */
    public boolean columnHidden(int column) throws IOException {
        for (ColumnWidth width : columnWidths()) {
            if (column >= width.firstColumn() && column <= width.lastColumn()) {
                return width.hidden();
            }
        }
        return false;
    }

    /** Returns whether summary rows sit above their groups, as set by {@link SheetOptions.Builder#outlineSummaryAbove()}. */
    public boolean outlineSummaryAbove() throws IOException {
        readLayout();
        return parser.outlineSummaryAbove();
    }

    /** Returns the deepest row outline level the sheet declares; 0 without grouping. */
    public int rowOutlineLevels() throws IOException {
        readLayout();
        return parser.rowOutlineLevels();
    }

    private void readLayout() throws IOException {
        if (parser.layoutComplete()) {
            return;
        }
        if (layoutFailure instanceof IOException) {
            throw (IOException) layoutFailure;
        }
        if (layoutFailure != null) {
            throw (RuntimeException) layoutFailure;
        }
        if (finished) {
            throw new XlsxException(XlsxException.Kind.INVALID_STATE, "the sheet reader was closed before its layout was read");
        }
        try {
            parser.readLayout();
        } catch (IOException | RuntimeException e) {
            layoutFailure = e;
            close();
            throw e;
        }
    }

    /**
     * Reads the next row that contains at least one value (or, with
     * {@link ReadOptions.Builder#includeEmptyRows(boolean)}, the next row the file stores), or
     * returns null at the end of the sheet. The returned row is reused: it is only valid until the next call.
     *
     * @throws XlsxException if the worksheet is damaged ({@link XlsxException.Kind#MALFORMED}) or
     *     exceeds a limit
     * @throws IOException if the source cannot be read
     */
    public Row nextRow() throws IOException {
        if (layoutFailure instanceof IOException) {
            throw (IOException) layoutFailure;
        }
        if (layoutFailure != null) {
            throw (RuntimeException) layoutFailure;
        }
        if (finished) {
            return null;
        }
        try {
            if (parser.next()) {
                return row;
            }
        } catch (IOException | RuntimeException e) {
            if (!parser.layoutComplete()) {
                layoutFailure = e;
            }
            close();
            throw e;
        }
        finished = true;
        return null;
    }

    /**
     * Iterates over the rows. Like {@link #nextRow()}, the iterator returns the same, reused
     * {@link Row} each time. I/O failures are thrown as {@link UncheckedIOException}.
     */
    @Override
    public Iterator<Row> iterator() {
        return new Iterator<>() {
            private Row next;

            @Override
            public boolean hasNext() {
                if (next == null) {
                    try {
                        next = nextRow();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return next != null;
            }

            @Override
            public Row next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Row current = next;
                next = null;
                return current;
            }
        };
    }

    /**
     * Returns the remaining rows as a sequential stream of independent copies (see
     * {@link Row#copy()}), so they may be collected or sorted. Closing the stream closes this
     * reader. I/O failures are thrown as {@link UncheckedIOException}.
     *
     * <pre>{@code
     * try (Stream<Row> rows = reader.worksheet(0).stream()) {
     *     List<List<Object>> table = rows.map(Row::toList).toList();
     * }
     * }</pre>
     */
    public Stream<Row> stream() {
        Iterator<Row> rows = iterator();
        Iterator<Row> copies = new Iterator<>() {
            @Override
            public boolean hasNext() {
                return rows.hasNext();
            }

            @Override
            public Row next() {
                return rows.next().copy();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(copies, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(this::close);
    }

    /** Stops reading and releases the decompressor. Further calls to {@link #nextRow()} return null. */
    @Override
    public void close() {
        finished = true;
        parser.close();
    }
}
