package de.vensas.hypaxel.internal;

import de.vensas.hypaxel.Cell;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.Date;

/**
 * Converts Java values to cells. Dates and times become Excel serials with integer arithmetic;
 * dates the workbook's date system cannot represent are written as ISO-8601 text.
 */
public final class ValueWriter {
    private static final long SECONDS_PER_DAY = 86_400;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final WorkbookEngine engine;
    private final ZoneRules zoneRules;
    private final boolean fixedOffset;
    private final int fixedOffsetSeconds;

    public ValueWriter(WorkbookEngine engine, ZoneId zone) {
        this.engine = engine;
        this.zoneRules = zone.getRules();
        this.fixedOffset = zoneRules.isFixedOffset();
        this.fixedOffsetSeconds = fixedOffset ? zoneRules.getOffset(Instant.EPOCH).getTotalSeconds() : 0;
    }

    public WorkbookEngine engine() {
        return engine;
    }

    public void write(String value, int style) {
        if (value == null) {
            engine.writeBlank(style);
        } else {
            engine.writeString(value, style);
        }
    }

    /** Writes the shortest decimal form of the float, so 0.1f becomes 0.1 rather than 0.10000000149. */
    public void write(float value, int style) {
        engine.writeDouble(Float.isFinite(value) ? Double.parseDouble(Float.toString(value)) : value, style);
    }

    public void write(BigDecimal value, int style) {
        if (value == null) {
            engine.writeBlank(style);
        } else {
            engine.writeDouble(value.doubleValue(), style);
        }
    }

    public void write(LocalDate value, int style) {
        if (value == null) {
            engine.writeBlank(style);
            return;
        }
        long day = engine.serialDay(value.toEpochDay());
        if (day < 0) {
            engine.writeString(value.toString(), style);
        } else {
            engine.writeSerial(day, 0, ValueKind.DATE, style);
        }
    }

    public void write(LocalDateTime value, int style) {
        if (value == null) {
            engine.writeBlank(style);
            return;
        }
        long day = engine.serialDay(value.toLocalDate().toEpochDay());
        if (day < 0) {
            engine.writeString(value.toString(), style);
        } else {
            engine.writeSerial(day, value.toLocalTime().toNanoOfDay(), ValueKind.DATE_TIME, style);
        }
    }

    public void write(LocalTime value, int style) {
        if (value == null) {
            engine.writeBlank(style);
        } else {
            engine.writeDays(false, 0, value.toNanoOfDay(), ValueKind.TIME, style);
        }
    }

    public void write(ZonedDateTime value, int style) {
        write(value == null ? null : value.toLocalDateTime(), style);
    }

    public void write(OffsetDateTime value, int style) {
        write(value == null ? null : value.toLocalDateTime(), style);
    }

    public void write(Instant value, int style) {
        if (value == null) {
            engine.writeBlank(style);
            return;
        }
        int offset = fixedOffset ? fixedOffsetSeconds : zoneRules.getOffset(value).getTotalSeconds();
        writeLocalSeconds(value.getEpochSecond() + offset, value.getNano(), style);
    }

    public void write(Date value, int style) {
        if (value == null) {
            engine.writeBlank(style);
            return;
        }
        long millis = value.getTime();
        int offset = fixedOffset ? fixedOffsetSeconds : zoneRules.getOffset(Instant.ofEpochMilli(millis)).getTotalSeconds();
        long seconds = Math.floorDiv(millis, 1000);
        writeLocalSeconds(seconds + offset, (int) (Math.floorMod(millis, 1000) * NANOS_PER_MILLI), style);
    }

    private void writeLocalSeconds(long localSeconds, int nanos, int style) {
        long epochDay = Math.floorDiv(localSeconds, SECONDS_PER_DAY);
        long secondOfDay = Math.floorMod(localSeconds, SECONDS_PER_DAY);
        long day = engine.serialDay(epochDay);
        if (day < 0) {
            engine.writeString(LocalDateTime.ofEpochSecond(localSeconds, nanos, ZoneOffset.UTC).toString(), style);
        } else {
            engine.writeSerial(day, secondOfDay * NANOS_PER_SECOND + nanos, ValueKind.DATE_TIME, style);
        }
    }

    public void write(Duration value, int style) {
        if (value == null) {
            engine.writeBlank(style);
            return;
        }
        long seconds = value.getSeconds();
        long nanos = value.getNano();
        boolean negative = seconds < 0;
        if (negative) {
            // Duration keeps nanos in 0..999,999,999 and counts them upwards from the seconds.
            if (nanos > 0) {
                seconds++;
                nanos = NANOS_PER_SECOND - nanos;
            }
            seconds = -seconds;
        }
        engine.writeDays(negative, seconds / SECONDS_PER_DAY, seconds % SECONDS_PER_DAY * NANOS_PER_SECOND + nanos,
                ValueKind.DURATION, style);
    }

    /**
     * Writes a value according to its runtime type. Unknown types are written with
     * {@link Object#toString()}.
     */
    public void writeObject(Object value, int style) {
        if (value == null) {
            engine.writeBlank(style);
        } else if (value instanceof String v) {
            engine.writeString(v, style);
        } else if (value instanceof Number v) {
            writeNumber(v, style);
        } else if (value instanceof Boolean v) {
            engine.writeBoolean(v, style);
        } else if (value instanceof LocalDateTime v) {
            write(v, style);
        } else if (value instanceof LocalDate v) {
            write(v, style);
        } else if (value instanceof Instant v) {
            write(v, style);
        } else if (value instanceof Date v) {
            write(v, style);
        } else if (value instanceof ZonedDateTime v) {
            write(v, style);
        } else if (value instanceof OffsetDateTime v) {
            write(v, style);
        } else if (value instanceof LocalTime v) {
            write(v, style);
        } else if (value instanceof Duration v) {
            write(v, style);
        } else if (value instanceof Cell v) {
            writeCell(v, style);
        } else if (value instanceof Enum<?> v) {
            engine.writeString(v.name(), style);
        } else {
            engine.writeString(value.toString(), style);
        }
    }

    private void writeNumber(Number value, int style) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            engine.writeLong(value.longValue(), style);
        } else if (value instanceof Float v) {
            write(v.floatValue(), style);
        } else if (value instanceof BigInteger v && v.bitLength() < 64) {
            engine.writeLong(v.longValue(), style);
        } else {
            engine.writeDouble(value.doubleValue(), style);
        }
    }

    private void writeCell(Cell cell, int style) {
        int cellStyle = cell.style() == null ? style : cell.style().index();
        if (cell.isFormula()) {
            engine.writeFormula((String) cell.value(), cellStyle);
        } else {
            writeObject(cell.value(), cellStyle);
        }
    }
}
