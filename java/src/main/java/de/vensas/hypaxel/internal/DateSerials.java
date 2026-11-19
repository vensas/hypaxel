package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.DateKind;

/**
 * Conversions between Excel serial numbers and calendar dates.
 *
 * <p>In the 1900 date system Excel counts the nonexistent 1900-02-29 as serial 60, so serials
 * before 1900-03-01 are shifted by one day.
 */
public final class DateSerials {
    /** Returned by {@link #toEpochMillis} for serials that are not a representable date. */
    public static final long INVALID = Long.MIN_VALUE;

    public static final long MILLIS_PER_DAY = 86_400_000L;
    /** 1899-12-30, the day before serial 1 counted from after 1900-02-28. */
    private static final long EPOCH_DAY_1900 = -25_569;
    /** 1904-01-01, serial 0 in the 1904 date system. */
    private static final long EPOCH_DAY_1904 = -24_107;
    private static final double MAX_SERIAL = 3_000_000;
    private static final double MAX_DURATION_DAYS = 100_000_000;

    private DateSerials() {
    }

    /**
     * Converts a serial to milliseconds since 1970-01-01T00:00 (wall-clock time, no zone), rounded
     * to the millisecond. Returns {@link #INVALID} for negative serials, the fictitious 1900-02-29,
     * serials past the year 10,000 and non-finite values.
     */
    public static long toEpochMillis(double serial, boolean date1904) {
        if (!(serial >= 0) || serial >= MAX_SERIAL) {
            return INVALID;
        }
        // Split before scaling: the whole serial times the milliseconds per day would lose precision.
        double whole = Math.floor(serial);
        long day = (long) whole;
        long millis = Math.round((serial - whole) * MILLIS_PER_DAY);
        if (millis >= MILLIS_PER_DAY) {
            day++;
            millis -= MILLIS_PER_DAY;
        }
        long epochDay;
        if (date1904) {
            epochDay = EPOCH_DAY_1904 + day;
        } else if (day < 60) {
            epochDay = EPOCH_DAY_1900 + day + 1;
        } else if (day == 60) {
            return INVALID;
        } else {
            epochDay = EPOCH_DAY_1900 + day;
        }
        return epochDay * MILLIS_PER_DAY + millis;
    }

    /** The serial as elapsed milliseconds, independent of the date system; {@link #INVALID} if out of range. */
    public static long toDurationMillis(double serial) {
        if (!(Math.abs(serial) < MAX_DURATION_DAYS)) {
            return INVALID;
        }
        double whole = serial < 0 ? Math.ceil(serial) : Math.floor(serial);
        double fraction = (serial - whole) * MILLIS_PER_DAY;
        long rounded = (long) Math.floor(Math.abs(fraction) + 0.5);
        return (long) whole * MILLIS_PER_DAY + (fraction < 0 ? -rounded : rounded);
    }

    /** Days since 0001-01-01 (day 1), proleptic Gregorian, without validating the day of the month. */
    static long daysFromCe(long y, int m, int d) {
        y = m <= 2 ? y - 1 : y;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long mp = (m + 9) % 12;
        long doy = (153 * mp + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146_097 + doe - 719_468 + 719_163;
    }

    /** {@link #daysFromCe} of 1899-12-30 and 1904-01-01. */
    private static final long CE_1900 = 693_594;
    private static final long CE_1904 = 695_056;

    /** The kind of an ISO 8601 value (as written in {@code t="d"} cells). */
    public static DateKind isoKind(String s) {
        String t = trimIso(s);
        int sep = t.indexOf('T');
        if (sep >= 0) {
            return DateKind.DATE_TIME;
        }
        return t.indexOf(':') >= 0 ? DateKind.TIME : DateKind.DATE;
    }

    private static String trimIso(String s) {
        String t = s.strip();
        int end = t.length();
        while (end > 0 && t.charAt(end - 1) == 'Z') {
            end--;
        }
        return t.substring(0, end);
    }

    /** Converts an ISO 8601 date, date-time or time to a serial; NaN if it cannot be parsed. */
    public static double isoToSerial(String s, boolean date1904) {
        String t = trimIso(s);
        String date;
        String time;
        int sep = t.indexOf('T');
        if (sep >= 0) {
            date = t.substring(0, sep);
            time = t.substring(sep + 1);
        } else if (t.indexOf(':') >= 0) {
            date = null;
            time = t;
        } else {
            date = t;
            time = null;
        }
        long day = 0;
        if (date != null) {
            String[] parts = date.split("-", 3);
            if (parts.length != 3) {
                return Double.NaN;
            }
            long y = parseInteger(parts[0], 6);
            long m = parseInteger(parts[1], 2);
            long d = parseInteger(parts[2], 2);
            if (y == Long.MIN_VALUE || m < 1 || m > 12 || d < 1 || d > 31) {
                return Double.NaN;
            }
            long days = daysFromCe(y, (int) m, (int) d);
            if (date1904) {
                day = days - CE_1904;
            } else {
                day = days - CE_1900;
                if (day < 61) {
                    day--;
                }
            }
        }
        double fraction = 0;
        if (time != null) {
            String[] parts = time.split(":", 3);
            if (parts.length < 2) {
                return Double.NaN;
            }
            double h = parseDecimal(parts[0]);
            double m = parseDecimal(parts[1]);
            double sec = parts.length == 3 ? parseDecimal(parts[2]) : 0;
            fraction = (h * 3600 + m * 60 + sec) / 86_400;
        }
        return day + fraction;
    }

    private static long parseInteger(String s, int maxDigits) {
        int start = s.startsWith("+") || s.startsWith("-") ? 1 : 0;
        if (s.length() == start || s.length() - start > maxDigits) {
            return Long.MIN_VALUE;
        }
        long n = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return Long.MIN_VALUE;
            }
            n = n * 10 + (c - '0');
        }
        return s.charAt(0) == '-' ? -n : n;
    }

    private static double parseDecimal(String s) {
        if (s.isEmpty() || s.length() > 32 || !Numbers.isDecimalSyntax(s)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
