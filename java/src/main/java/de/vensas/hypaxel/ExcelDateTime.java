package de.vensas.hypaxel;

import de.vensas.hypaxel.internal.DateSerials;
import de.vensas.hypaxel.internal.DoubleFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A date, time or duration as Excel stores it: a serial number of days, read from a cell whose
 * number format shows a date.
 *
 * <p>The serial is interpreted in the workbook's date system (1900, or 1904 for workbooks created
 * by old Mac versions of Excel). In the 1900 system Excel counts the nonexistent 1900-02-29 as
 * serial 60, so serials before 1900-03-01 are shifted by one day; the conversions account for it.
 * Times are rounded to the millisecond: serials carry about 15 significant digits, so finer digits
 * are noise.
 */
public final class ExcelDateTime {
    private final double serial;
    private final DateKind kind;
    private final boolean date1904;

    /** Creates a value from a serial number in the given date system. */
    public ExcelDateTime(double serial, DateKind kind, boolean date1904) {
        this.serial = serial;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.date1904 = date1904;
    }

    /** Returns the raw serial number: days, with the time of day as the fraction. */
    public double serial() {
        return serial;
    }

    /** Returns what the cell's number format shows: a date, a date and time, a time of day or a duration. */
    public DateKind kind() {
        return kind;
    }

    /** Returns whether the serial counts from 1904-01-01 (the 1904 date system). */
    public boolean isDate1904() {
        return date1904;
    }

    /**
     * Returns the date and time this serial stands for, or null for negative serials, the
     * fictitious 1900-02-29 (serial 60) and serials beyond the year 9999.
     */
    public LocalDateTime toLocalDateTime() {
        long millis = DateSerials.toEpochMillis(serial, date1904);
        if (millis == DateSerials.INVALID) {
            return null;
        }
        return LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000), Math.floorMod(millis, 1000) * 1_000_000, ZoneOffset.UTC);
    }

    /** Returns the calendar date (the time of day is dropped), or null like {@link #toLocalDateTime()}. */
    public LocalDate toLocalDate() {
        LocalDateTime value = toLocalDateTime();
        return value == null ? null : value.toLocalDate();
    }

    /** Returns the time of day (the date is dropped), or null like {@link #toLocalDateTime()}. */
    public LocalTime toLocalTime() {
        LocalDateTime value = toLocalDateTime();
        return value == null ? null : value.toLocalTime();
    }

    /** Returns the serial as elapsed time, as used by {@code [h]:mm:ss} formats, or null if it is out of range. */
    public Duration toDuration() {
        long millis = DateSerials.toDurationMillis(serial);
        return millis == DateSerials.INVALID ? null : Duration.ofMillis(millis);
    }

    /**
     * Returns the value as the {@code java.time} type that matches its kind: a {@link Duration}
     * for durations, a {@link LocalTime} for times of day below one day, and a
     * {@link LocalDateTime} otherwise. Serials that are not a representable date are returned as
     * a {@link Double}.
     */
    public Object toObject() {
        if (kind == DateKind.DURATION) {
            Duration duration = toDuration();
            return duration == null ? (Object) serial : duration;
        }
        LocalDateTime value = toLocalDateTime();
        if (value == null) {
            return serial;
        }
        if (kind == DateKind.TIME && serial < 1) {
            return value.toLocalTime();
        }
        return value;
    }

    /**
     * Returns ISO 8601 text ({@code 2024-05-01}, {@code 2024-05-01T08:30:00},
     * {@code 08:30:00.250}), or the serial number for durations and serials that are not a
     * representable date.
     */
    @Override
    public String toString() {
        if (kind == DateKind.DURATION) {
            return DoubleFormat.toString(serial);
        }
        long millis = DateSerials.toEpochMillis(serial, date1904);
        if (millis == DateSerials.INVALID) {
            return DoubleFormat.toString(serial);
        }
        long epochDay = Math.floorDiv(millis, DateSerials.MILLIS_PER_DAY);
        int ofDay = (int) Math.floorMod(millis, DateSerials.MILLIS_PER_DAY);
        StringBuilder out = new StringBuilder(23);
        if (kind == DateKind.TIME && serial < 1) {
            appendTime(out, ofDay);
            return out.toString();
        }
        out.append(LocalDate.ofEpochDay(epochDay));
        if (kind != DateKind.DATE || ofDay != 0) {
            out.append('T');
            appendTime(out, ofDay);
        }
        return out.toString();
    }

    private static void appendTime(StringBuilder out, int millisOfDay) {
        int seconds = millisOfDay / 1000;
        two(out, seconds / 3600).append(':');
        two(out, seconds / 60 % 60).append(':');
        two(out, seconds % 60);
        int millis = millisOfDay % 1000;
        if (millis != 0) {
            out.append('.').append((char) ('0' + millis / 100)).append((char) ('0' + millis / 10 % 10)).append((char) ('0' + millis % 10));
        }
    }

    private static StringBuilder two(StringBuilder out, int value) {
        return out.append((char) ('0' + value / 10)).append((char) ('0' + value % 10));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ExcelDateTime other && Double.compare(serial, other.serial) == 0 && kind == other.kind
                && date1904 == other.date1904;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serial, kind, date1904);
    }
}
