package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.TypeMap;
import de.vensas.hypaxel.internal.ValueWriter;
import de.vensas.hypaxel.internal.WorkbookEngine;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The worksheet currently being written. Rows go top to bottom and cells left to right. A
 * worksheet is complete, and rejects further writes, once the next worksheet is added or the
 * writer is finished.
 *
 * <p>The {@code write} methods append the next cell of the current row, starting a row when none
 * is open, and return this worksheet for chaining. They only fill an in-memory buffer; the
 * methods that end rows hand buffered output to the destination and therefore declare
 * {@link IOException}. Reference-typed values may be null, which leaves the cell empty.
 *
 * <p>Instances are not thread-safe.
 */
public final class Worksheet {
    private static final Style DEFAULT_HEADER_STYLE = Style.builder()
            .bold()
            .fill(Color.LIGHT_GRAY)
            .border(Border.builder().bottom(BorderLine.of(BorderStyle.THIN)).build())
            .build();

    private final XlsxWriter writer;
    private final WorkbookEngine engine;
    private final ValueWriter values;
    private final String name;
    private boolean open = true;

    Worksheet(XlsxWriter writer, WorkbookEngine engine, ValueWriter values, String name) {
        this.writer = writer;
        this.engine = engine;
        this.values = values;
        this.name = name;
    }

    void complete() {
        open = false;
    }

    private WorkbookEngine engine() {
        if (!open) {
            throw completed();
        }
        return engine;
    }

    private ValueWriter values() {
        if (!open) {
            throw completed();
        }
        return values;
    }

    private XlsxException completed() {
        return new XlsxException(XlsxException.Kind.INVALID_STATE, "worksheet \"" + name + "\" is already completed");
    }

    /** Returns the worksheet name. */
    public String name() {
        return name;
    }

    /** Returns the current (or last written) 1-based row number; 0 before the first row. */
    public int rowNumber() {
        return engine.row();
    }

    /** Returns the 1-based column of the last written cell in the current row; 0 at the start of a row. */
    public int columnNumber() {
        return engine.column();
    }

    // ------------------------------------------------------------------ rows

    /**
     * Starts the next row explicitly. Cells start one implicitly; use this to leave an empty
     * styled row. A following whole-row write such as {@link #addRow(Object...)} fills this row
     * rather than starting another, as long as it has no cells yet.
     */
    public Worksheet beginRow() {
        return beginRowAt(engine().row() + 1);
    }

    /** Starts the next row with a height in points, 0 to 409. */
    public Worksheet beginRow(double height) {
        engine().beginRowAt(engine.row() + 1, checkHeight(height), 0, 0, false, false);
        return this;
    }

    /** Starts the next row with a format for its empty cells. */
    public Worksheet beginRow(StyleId style) {
        engine().beginRowAt(engine.row() + 1, Double.NaN, style.index(), 0, false, false);
        return this;
    }

    /** Starts the next row with a height in points and a format for its empty cells. */
    public Worksheet beginRow(double height, StyleId style) {
        engine().beginRowAt(engine.row() + 1, checkHeight(height), style.index(), 0, false, false);
        return this;
    }

    /**
     * Starts the next row with a height, format, outline (grouping) level or hidden state. Fill it
     * with cell writes or a single whole-row write such as {@link #addRow(Object...)}.
     *
     * <pre>{@code
     * Worksheet sheet = writer.addWorksheet("Tree", SheetOptions.builder().rowOutlineLevels(1).build());
     * sheet.addRow("Region North", 300);
     * sheet.beginRow(RowOptions.builder().outlineLevel(1).build()).addRow("Store A", 120);
     * sheet.beginRow(RowOptions.builder().outlineLevel(1).build()).write("Store B").write(180).endRow();
     * }</pre>
     *
     * @throws XlsxException if the outline level exceeds {@link SheetOptions#rowOutlineLevels()}
     */
    public Worksheet beginRow(RowOptions options) {
        return beginRowAt(engine().row() + 1, options);
    }

    /** Starts the row with the given 1-based number, which must be greater than any row written so far. */
    public Worksheet beginRowAt(int rowNumber) {
        engine().beginRowAt(rowNumber, Double.NaN, 0, 0, false, false);
        return this;
    }

    /** Starts the row with the given 1-based number, a height in points and a format for its empty cells. */
    public Worksheet beginRowAt(int rowNumber, double height, StyleId style) {
        engine().beginRowAt(rowNumber, checkHeight(height), style.index(), 0, false, false);
        return this;
    }

    /** Like {@link #beginRow(RowOptions)} for the row with the given 1-based number. */
    public Worksheet beginRowAt(int rowNumber, RowOptions options) {
        Objects.requireNonNull(options, "options");
        engine().beginRowAt(rowNumber, options.height(), options.style().index(), options.outlineLevel(), options.hidden(),
                options.collapsed());
        return this;
    }

    private static double checkHeight(double height) {
        if (Double.isNaN(height)) {
            throw new XlsxException(XlsxException.Kind.INVALID_ARGUMENT, "row height must be within 0..409 points");
        }
        return height;
    }

    /**
     * Ends the current row and writes buffered output to the destination when enough has
     * accumulated. Without an open row it leaves an empty row.
     */
    public void endRow() throws IOException {
        engine().endRow();
        writer.drainIfReady();
    }

    /** Leaves {@code count} cells of the current row empty. */
    public Worksheet skipCells(int count) {
        engine().skipCells(count);
        return this;
    }

    /** Ends the current row, if any, and leaves {@code count} rows empty. */
    public Worksheet skipRows(int count) {
        engine().skipRows(count);
        return this;
    }

    /** Merges a range of cells; row and column numbers are 1-based and inclusive. The range cannot overlap a table. */
    public Worksheet mergeCells(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        engine().merge(firstRow, firstColumn, lastRow, lastColumn);
        return this;
    }

    /**
     * Adds filter drop-downs to a range whose first row is the header; numbers are 1-based and
     * inclusive. The range cannot overlap a table, which has its own filter.
     */
    public Worksheet autoFilter(int firstRow, int firstColumn, int lastRow, int lastColumn) {
        engine().autoFilter(firstRow, firstColumn, lastRow, lastColumn);
        return this;
    }

    /**
     * Turns a range of this worksheet into an Excel table; see {@link ExcelTable}. The range may
     * include rows that are written later: it is recorded now and written when the worksheet
     * completes.
     *
     * @throws XlsxException if the table is invalid ({@link XlsxException.Kind#INVALID_ARGUMENT},
     *     {@link XlsxException.Kind#INVALID_TABLE_NAME}), its name is already used in the workbook
     *     ({@link XlsxException.Kind#DUPLICATE_TABLE_NAME}), or its range overlaps another table,
     *     merged cells or the worksheet auto filter ({@link XlsxException.Kind#OVERLAPPING_RANGE})
     */
    public Worksheet addTable(ExcelTable table) {
        engine().addTable(Objects.requireNonNull(table, "table"));
        return this;
    }

    // ------------------------------------------------------------------ cells

    /** Writes a text cell. */
    public Worksheet write(String value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a text cell with a style. */
    public Worksheet write(String value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a single character as a text cell. */
    public Worksheet write(char value) {
        engine().writeString(String.valueOf(value), 0);
        return this;
    }

    /** Writes a number cell. */
    public Worksheet write(int value) {
        engine().writeLong(value, 0);
        return this;
    }

    /** Writes a number cell with a style. */
    public Worksheet write(int value, StyleId style) {
        engine().writeLong(value, style.index());
        return this;
    }

    /** Writes a number cell. Excel stores numbers as doubles: beyond 2^53 precision is lost. */
    public Worksheet write(long value) {
        engine().writeLong(value, 0);
        return this;
    }

    /** Writes a number cell with a style. */
    public Worksheet write(long value, StyleId style) {
        engine().writeLong(value, style.index());
        return this;
    }

    /** Writes a number cell. NaN and infinities are written as the {@code #NUM!} error. */
    public Worksheet write(double value) {
        engine().writeDouble(value, 0);
        return this;
    }

    /** Writes a number cell with a style. */
    public Worksheet write(double value, StyleId style) {
        engine().writeDouble(value, style.index());
        return this;
    }

    /** Writes a number cell with the float's shortest decimal form, so {@code 0.1f} is stored as 0.1. */
    public Worksheet write(float value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a number cell with a style. */
    public Worksheet write(float value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a boolean cell. */
    public Worksheet write(boolean value) {
        engine().writeBoolean(value, 0);
        return this;
    }

    /** Writes a boolean cell with a style. */
    public Worksheet write(boolean value, StyleId style) {
        engine().writeBoolean(value, style.index());
        return this;
    }

    /** Writes a number cell holding the nearest double; Excel keeps 15 significant digits. */
    public Worksheet write(BigDecimal value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a number cell with a style. */
    public Worksheet write(BigDecimal value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a date cell, shown in the viewer's short date format unless styled. */
    public Worksheet write(LocalDate value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a date cell; a style without a number format gets the short date format. */
    public Worksheet write(LocalDate value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a date and time cell, shown in the viewer's date and time format unless styled. */
    public Worksheet write(LocalDateTime value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a date and time cell with a style. */
    public Worksheet write(LocalDateTime value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a time of day cell, shown as {@code h:mm:ss} unless styled. */
    public Worksheet write(LocalTime value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a time of day cell with a style. */
    public Worksheet write(LocalTime value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a date and time cell using the wall-clock time in {@link XlsxOptions#zone()}. */
    public Worksheet write(Instant value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a date and time cell with a style, using the wall-clock time in {@link XlsxOptions#zone()}. */
    public Worksheet write(Instant value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a date and time cell using the value's own local date and time; the zone is dropped. */
    public Worksheet write(ZonedDateTime value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a date and time cell with a style, using the value's own local date and time. */
    public Worksheet write(ZonedDateTime value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a date and time cell using the value's own local date and time; the offset is dropped. */
    public Worksheet write(OffsetDateTime value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a date and time cell with a style, using the value's own local date and time. */
    public Worksheet write(OffsetDateTime value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a date and time cell using the wall-clock time in {@link XlsxOptions#zone()}. */
    public Worksheet write(Date value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a date and time cell with a style, using the wall-clock time in {@link XlsxOptions#zone()}. */
    public Worksheet write(Date value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a duration as a number of days, shown as {@code [h]:mm:ss} unless styled. */
    public Worksheet write(Duration value) {
        values().write(value, 0);
        return this;
    }

    /** Writes a duration with a style. */
    public Worksheet write(Duration value, StyleId style) {
        values().write(value, style.index());
        return this;
    }

    /** Writes a formula such as {@code "SUM(A1:A9)"}; a leading {@code =} is optional. Excel computes it on open. */
    public Worksheet writeFormula(String formula) {
        engine().writeFormula(formula, 0);
        return this;
    }

    /** Writes a formula with a style. */
    public Worksheet writeFormula(String formula, StyleId style) {
        engine().writeFormula(formula, style.index());
        return this;
    }

    /** Writes an empty cell that carries a format, e.g. a border or fill. */
    public Worksheet writeBlank(StyleId style) {
        engine().writeBlank(style.index());
        return this;
    }

    /**
     * Writes a value according to its runtime type: strings, boxed numbers, booleans, the date
     * and time types of the typed overloads, {@link Cell}, enums (by name), null (empty cell);
     * anything else through {@link Object#toString()}. Prefer the typed overloads in hot loops.
     */
    public Worksheet writeValue(Object value) {
        values().writeObject(value, 0);
        return this;
    }

    /** Writes a value according to its runtime type with a style; a {@link Cell}'s own style wins. */
    public Worksheet writeValue(Object value, StyleId style) {
        values().writeObject(value, style.index());
        return this;
    }

    // ------------------------------------------------------------------ whole rows

    /**
     * Writes a complete row; each value is written as by {@link #writeValue(Object)}. The row is
     * the one started with {@code beginRow} if it has no cells yet, otherwise the next one.
     */
    public void addRow(Object... values) throws IOException {
        addStyledRow(StyleId.DEFAULT, values);
    }

    /** Writes a complete row in which cells without their own style use {@code style}. */
    public void addStyledRow(StyleId style, Object... values) throws IOException {
        ValueWriter target = values();
        int index = style.index();
        engine.beginNextRow();
        for (Object value : values) {
            target.writeObject(value, index);
        }
        endRow();
    }

    /** Writes a complete row from a collection or any other iterable. */
    public void addRow(Iterable<?> values) throws IOException {
        ValueWriter target = values();
        engine.beginNextRow();
        for (Object value : values) {
            target.writeObject(value, 0);
        }
        endRow();
    }

    /**
     * Writes one row per element. An element is an {@code Object[]} or an {@link Iterable} of
     * cell values; any other element is written as a single cell.
     */
    public void addRows(Iterable<?> rows) throws IOException {
        for (Object row : rows) {
            addRowElement(row);
        }
    }

    /** Writes one row per element of the stream, like {@link #addRows(Iterable)}. The stream is not closed. */
    public void addRows(Stream<?> rows) throws IOException {
        Iterator<?> iterator = rows.iterator();
        while (iterator.hasNext()) {
            addRowElement(iterator.next());
        }
    }

    private void addRowElement(Object row) throws IOException {
        if (row instanceof Object[] array) {
            addRow(array);
        } else if (row instanceof Iterable<?> iterable) {
            addRow(iterable);
        } else {
            addRow(row);
        }
    }

    /** Writes one row per item; {@code rowWriter} writes the cells with the typed {@code write} methods. */
    public <T> void writeRows(Iterable<? extends T> items, RowWriter<? super T> rowWriter) throws IOException {
        writeRows(items.iterator(), rowWriter);
    }

    /** Writes one row per item of the stream, like {@link #writeRows(Iterable, RowWriter)}. The stream is not closed. */
    public <T> void writeRows(Stream<? extends T> items, RowWriter<? super T> rowWriter) throws IOException {
        writeRows(items.iterator(), rowWriter);
    }

    private <T> void writeRows(Iterator<? extends T> items, RowWriter<? super T> rowWriter) throws IOException {
        Objects.requireNonNull(rowWriter, "rowWriter");
        while (items.hasNext()) {
            T item = items.next();
            engine().beginNextRow();
            rowWriter.write(this, item);
            endRow();
        }
    }

    // ------------------------------------------------------------------ object mapping

    /**
     * Writes a header row and one row per item below the rows already written. Columns are the
     * record components or bean properties of {@code type}; see {@link ExcelColumn} and
     * {@link ExcelIgnore}. A null item leaves an empty row.
     */
    public <T> void writeObjects(Class<T> type, Iterable<? extends T> items) throws IOException {
        writeObjects(type, items.iterator(), ObjectOptions.DEFAULT);
    }

    /** Writes one row per item with the given options. */
    public <T> void writeObjects(Class<T> type, Iterable<? extends T> items, ObjectOptions options) throws IOException {
        writeObjects(type, items.iterator(), options);
    }

    /** Writes a header row and one row per item of the stream. The stream is not closed. */
    public <T> void writeObjects(Class<T> type, Stream<? extends T> items) throws IOException {
        writeObjects(type, items.iterator(), ObjectOptions.DEFAULT);
    }

    /** Writes one row per item of the stream with the given options. The stream is not closed. */
    public <T> void writeObjects(Class<T> type, Stream<? extends T> items, ObjectOptions options) throws IOException {
        writeObjects(type, items.iterator(), options);
    }

    private <T> void writeObjects(Class<T> type, Iterator<? extends T> items, ObjectOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        ValueWriter target = values();
        TypeMap map = TypeMap.of(type);
        int columns = map.columnCount();
        int[] styles = new int[columns];
        for (int i = 0; i < columns; i++) {
            String format = map.format(i);
            styles[i] = format == null ? 0 : engine.addStyle(Style.builder().numberFormat(format).build());
        }

        int firstRow = engine.nextRow();
        if (options.header()) {
            int headerStyle = options.headerStyle() == null ? engine.addStyle(DEFAULT_HEADER_STYLE) : options.headerStyle().index();
            engine.beginNextRow();
            for (int i = 0; i < columns; i++) {
                engine.writeString(map.header(i), headerStyle);
            }
            endRow();
        }
        while (items.hasNext()) {
            T item = items.next();
            engine().beginNextRow();
            if (item != null) {
                map.writeRow(target, type.cast(item), styles);
            }
            endRow();
        }
        if (options.autoFilter() && options.header()) {
            engine().autoFilter(firstRow, 1, Math.max(engine.row(), firstRow), columns);
        }
    }
}
