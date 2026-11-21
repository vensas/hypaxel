package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.DoubleFormat;
import de.vensas.hypaxel.internal.RowBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/**
 * One row of a worksheet that is being read. Columns are 1-based (A = 1).
 *
 * <p>{@link SheetReader} reuses one {@code Row} for all rows of a sheet: its contents are only
 * valid until the next row is read. Keep {@link #copy()}, {@link #toList()} or the values you
 * need instead of the row itself.
 *
 * <p>The typed getters ({@link #getString(int)}, {@link #getNumber(int)}, ...) do not allocate,
 * except for the {@code String} of an inline (not shared) string.
 */
public final class Row {
    private static final DateKind[] KINDS = DateKind.values();

    private final RowBuffer buffer;

    Row(RowBuffer buffer) {
        this.buffer = buffer;
    }

    /** Returns the 1-based row number as shown in Excel. */
    public int number() {
        return buffer.number;
    }

    /** Returns the outline (grouping) level of the row, 0 to 7. */
    public int outlineLevel() {
        return buffer.outlineLevel;
    }

    /** Returns whether the row is hidden. */
    public boolean hidden() {
        return buffer.hidden;
    }

    /** Returns whether the row is the summary row of a collapsed group. */
    public boolean collapsed() {
        return buffer.collapsed;
    }

    /** Returns the height of the row in points, if the row stores one. */
    public OptionalDouble height() {
        return Double.isNaN(buffer.height) ? OptionalDouble.empty() : OptionalDouble.of(buffer.height);
    }

    /** Returns the number of cells with a value; always at least one. */
    public int cellCount() {
        return buffer.count;
    }

    /** Returns the 1-based column of the last cell with a value. */
    public int lastColumn() {
        return buffer.count == 0 ? 0 : buffer.columns[buffer.count - 1];
    }

    /** Returns the 1-based column of the cell at {@code index} (0 to {@link #cellCount()} - 1), in column order. */
    public int column(int index) {
        if (index < 0 || index >= buffer.count) {
            throw new IndexOutOfBoundsException("cell index " + index + " is outside 0.." + (buffer.count - 1));
        }
        return buffer.columns[index];
    }

    /** Returns the type of the value in a 1-based column; {@link CellType#EMPTY} if there is none. */
    public CellType type(int column) {
        int i = buffer.indexOf(column);
        if (i < 0) {
            return CellType.EMPTY;
        }
        return switch (buffer.types[i]) {
            case RowBuffer.SHARED, RowBuffer.TEXT -> CellType.STRING;
            case RowBuffer.NUMBER -> CellType.NUMBER;
            case RowBuffer.BOOL -> CellType.BOOLEAN;
            case RowBuffer.DATE -> CellType.DATE_TIME;
            default -> CellType.ERROR;
        };
    }

    /** Returns the value in a 1-based column; {@link Value#EMPTY} if there is none. */
    public Value getValue(int column) {
        int i = buffer.indexOf(column);
        return i < 0 ? Value.EMPTY : value(i);
    }

    private Value value(int i) {
        return switch (buffer.types[i]) {
            case RowBuffer.SHARED, RowBuffer.TEXT -> Value.of(buffer.string(i));
            case RowBuffer.NUMBER -> Value.of(buffer.numbers[i]);
            case RowBuffer.BOOL -> Value.of(buffer.numbers[i] != 0);
            case RowBuffer.DATE -> Value.of(dateTime(i));
            default -> Value.error(buffer.string(i));
        };
    }

    private ExcelDateTime dateTime(int i) {
        return new ExcelDateTime(buffer.numbers[i], KINDS[buffer.kinds[i] - 1], buffer.date1904);
    }

    /** Returns the text of a string cell, or null if the cell is empty or holds another type. */
    public String getString(int column) {
        int i = buffer.indexOf(column);
        if (i < 0) {
            return null;
        }
        byte type = buffer.types[i];
        return type == RowBuffer.SHARED || type == RowBuffer.TEXT ? buffer.string(i) : null;
    }

    /**
     * Returns the number of a numeric cell or the serial of a date cell, or {@code NaN} if the cell
     * is empty or holds another type. Excel cannot store {@code NaN}, so it only means "no number".
     */
    public double getNumber(int column) {
        int i = buffer.indexOf(column);
        if (i < 0) {
            return Double.NaN;
        }
        byte type = buffer.types[i];
        return type == RowBuffer.NUMBER || type == RowBuffer.DATE ? buffer.numbers[i] : Double.NaN;
    }

    /** Returns the value of a boolean cell, or null if the cell is empty or holds another type. */
    public Boolean getBoolean(int column) {
        int i = buffer.indexOf(column);
        return i >= 0 && buffer.types[i] == RowBuffer.BOOL ? Boolean.valueOf(buffer.numbers[i] != 0) : null;
    }

    /** Returns the date, time or duration of a date cell, or null if the cell is empty or holds another type. */
    public ExcelDateTime getDateTime(int column) {
        int i = buffer.indexOf(column);
        return i >= 0 && buffer.types[i] == RowBuffer.DATE ? dateTime(i) : null;
    }

    /**
     * Returns the date and time of a date cell, or null if the cell holds another type or its
     * serial is not a representable date. See {@link ExcelDateTime#toLocalDateTime()}.
     */
    public LocalDateTime getLocalDateTime(int column) {
        ExcelDateTime date = getDateTime(column);
        return date == null ? null : date.toLocalDateTime();
    }

    /** Returns the code of an error cell, such as {@code #N/A}, or null if the cell holds another type. */
    public String getError(int column) {
        int i = buffer.indexOf(column);
        return i >= 0 && buffer.types[i] == RowBuffer.ERROR ? buffer.string(i) : null;
    }

    /**
     * Returns the value in a 1-based column as a plain Java object, or null if there is none.
     * See {@link Value#toObject()}.
     */
    public Object getObject(int column) {
        int i = buffer.indexOf(column);
        if (i < 0) {
            return null;
        }
        return switch (buffer.types[i]) {
            case RowBuffer.SHARED, RowBuffer.TEXT, RowBuffer.ERROR -> buffer.string(i);
            case RowBuffer.NUMBER -> buffer.numbers[i];
            case RowBuffer.BOOL -> buffer.numbers[i] != 0;
            default -> dateTime(i).toObject();
        };
    }

    /**
     * Returns the value in a 1-based column as text, roughly as Excel's General format shows it,
     * or an empty string if there is none. See {@link Value#toString()}.
     */
    public String getText(int column) {
        int i = buffer.indexOf(column);
        if (i < 0) {
            return "";
        }
        return switch (buffer.types[i]) {
            case RowBuffer.SHARED, RowBuffer.TEXT, RowBuffer.ERROR -> buffer.string(i);
            case RowBuffer.NUMBER -> DoubleFormat.toString(buffer.numbers[i]);
            case RowBuffer.BOOL -> buffer.numbers[i] != 0 ? "TRUE" : "FALSE";
            default -> dateTime(i).toString();
        };
    }

    /**
     * For text from the shared string table, returns its index in that table, otherwise -1. Equal
     * indices mean equal text, so callers can convert each shared string once and reuse the result.
     */
    public int sharedStringIndex(int column) {
        int i = buffer.indexOf(column);
        return i >= 0 && buffer.types[i] == RowBuffer.SHARED ? buffer.starts[i] : -1;
    }

    /** Returns the cells that have a value, in column order. */
    public List<CellValue> cells() {
        List<CellValue> cells = new ArrayList<>(buffer.count);
        for (int i = 0; i < buffer.count; i++) {
            cells.add(new CellValue(buffer.columns[i], value(i)));
        }
        return Collections.unmodifiableList(cells);
    }

    /** Returns the values of columns 1 to {@link #lastColumn()}, with {@link Value#EMPTY} for gaps. */
    public List<Value> values() {
        Value[] values = new Value[lastColumn()];
        Arrays.fill(values, Value.EMPTY);
        for (int i = 0; i < buffer.count; i++) {
            values[buffer.columns[i] - 1] = value(i);
        }
        return Collections.unmodifiableList(Arrays.asList(values));
    }

    /**
     * Returns the values of columns 1 to {@link #lastColumn()} as plain Java objects (see
     * {@link #getObject(int)}), with null for gaps. The list is a new, modifiable list.
     */
    public List<Object> toList() {
        Object[] values = new Object[lastColumn()];
        for (int i = 0; i < buffer.count; i++) {
            values[buffer.columns[i] - 1] = getObject(buffer.columns[i]);
        }
        return new ArrayList<>(Arrays.asList(values));
    }

    /** Returns an independent copy that is not overwritten when the next row is read. */
    public Row copy() {
        return new Row(buffer.copy());
    }

    /** Returns the row number and the values, e.g. {@code 3: [Widget, , 12.5]}. */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder().append(number()).append(": [");
        for (int column = 1; column <= lastColumn(); column++) {
            if (column > 1) {
                out.append(", ");
            }
            out.append(getText(column));
        }
        return out.append(']').toString();
    }
}
