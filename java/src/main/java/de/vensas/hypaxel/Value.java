package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.DoubleFormat;
import java.util.Objects;

/**
 * The value of one cell read from a workbook. Immutable.
 *
 * <p>{@link Row}'s typed getters ({@link Row#getString(int)}, {@link Row#getNumber(int)}, ...)
 * read values without creating a {@code Value}; this class is for code that handles every type.
 */
public final class Value {
    /** No value. Returned for missing cells and cells that only carry formatting. */
    public static final Value EMPTY = new Value(CellType.EMPTY, null, 0, null);
    /** The boolean {@code TRUE}. */
    public static final Value TRUE = new Value(CellType.BOOLEAN, null, 1, null);
    /** The boolean {@code FALSE}. */
    public static final Value FALSE = new Value(CellType.BOOLEAN, null, 0, null);

    private final CellType type;
    private final String text;
    private final double number;
    private final ExcelDateTime date;

    private Value(CellType type, String text, double number, ExcelDateTime date) {
        this.type = type;
        this.text = text;
        this.number = number;
        this.date = date;
    }

    /** A text value. */
    public static Value of(String text) {
        return new Value(CellType.STRING, Objects.requireNonNull(text, "text"), 0, null);
    }

    /** A number that is not formatted as a date. */
    public static Value of(double number) {
        return new Value(CellType.NUMBER, null, number, null);
    }

    /** {@link #TRUE} or {@link #FALSE}. */
    public static Value of(boolean value) {
        return value ? TRUE : FALSE;
    }

    /** A number formatted as a date, time or duration. */
    public static Value of(ExcelDateTime date) {
        Objects.requireNonNull(date, "date");
        return new Value(CellType.DATE_TIME, null, date.serial(), date);
    }

    /** An error value such as {@code #N/A}. */
    public static Value error(String code) {
        return new Value(CellType.ERROR, Objects.requireNonNull(code, "code"), 0, null);
    }

    /** Returns the type of the value. */
    public CellType type() {
        return type;
    }

    /** Returns whether there is no value. */
    public boolean isEmpty() {
        return type == CellType.EMPTY;
    }

    /** Returns the text of a string value, or null for other types. */
    public String asString() {
        return type == CellType.STRING ? text : null;
    }

    /** Returns the number of a numeric value or the serial of a date value, or null for other types. */
    public Double asNumber() {
        return type == CellType.NUMBER || type == CellType.DATE_TIME ? number : null;
    }

    /** Returns the boolean of a boolean value, or null for other types. */
    public Boolean asBoolean() {
        return type == CellType.BOOLEAN ? number != 0 : null;
    }

    /** Returns the date of a date, time or duration value, or null for other types. */
    public ExcelDateTime asDateTime() {
        return date;
    }

    /** Returns the error code of an error value, such as {@code #N/A}, or null for other types. */
    public String asError() {
        return type == CellType.ERROR ? text : null;
    }

    /**
     * Returns the value as a plain Java object: null, {@link String}, {@link Double},
     * {@link Boolean}, the {@code java.time} type of {@link ExcelDateTime#toObject()}, or the error
     * code as a {@link String}.
     */
    public Object toObject() {
        return switch (type) {
            case EMPTY -> null;
            case STRING, ERROR -> text;
            case NUMBER -> number;
            case BOOLEAN -> number != 0;
            case DATE_TIME -> date.toObject();
        };
    }

    /**
     * Returns the value roughly as Excel's General format shows it: text as is, numbers in their
     * shortest form, {@code TRUE}/{@code FALSE}, dates in ISO 8601 and an empty string for no value.
     */
    @Override
    public String toString() {
        return switch (type) {
            case EMPTY -> "";
            case STRING, ERROR -> text;
            case NUMBER -> DoubleFormat.toString(number);
            case BOOLEAN -> number != 0 ? "TRUE" : "FALSE";
            case DATE_TIME -> date.toString();
        };
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Value other && type == other.type && Objects.equals(text, other.text)
                && Double.compare(number, other.number) == 0 && Objects.equals(date, other.date);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, text, number, date);
    }
}
