using System.Globalization;

namespace Hypaxel;

/// <summary>How a cell's number format presents a serial value.</summary>
public enum DateKind : byte
{
    /// <summary>A calendar date without a time of day (e.g. <c>yyyy-mm-dd</c>).</summary>
    Date,

    /// <summary>A date with a time of day (e.g. <c>yyyy-mm-dd hh:mm</c>).</summary>
    DateTime,

    /// <summary>A time of day (e.g. <c>hh:mm:ss</c>).</summary>
    Time,

    /// <summary>Elapsed time (e.g. <c>[h]:mm:ss</c>).</summary>
    Duration,
}

/// <summary>
/// A date, time or duration as Excel stores it: a serial number of days, interpreted in the workbook's
/// date system.
/// </summary>
/// <remarks>
/// In the 1900 date system Excel counts the nonexistent 1900-02-29 as serial 60, so serials before
/// 1900-03-01 are shifted by one day; the conversions account for it, and serial 60 itself has no
/// <see cref="System.DateTime"/>. Times are rounded to the millisecond: serials carry about 15
/// significant digits, so finer digits are noise.
/// </remarks>
public readonly struct ExcelDateTime : IEquatable<ExcelDateTime>
{
    private const double MillisPerDay = 86_400_000d;

    internal const long Epoch1900 = 693_594; // days from 0001-01-01 (day 1) to 1899-12-30
    internal const long Epoch1904 = 695_056; // days from 0001-01-01 (day 1) to 1904-01-01

    private static readonly long s_ticks18991230 = new DateTime(1899, 12, 30).Ticks;
    private static readonly long s_ticks18991231 = new DateTime(1899, 12, 31).Ticks;
    private static readonly long s_ticks19040101 = new DateTime(1904, 1, 1).Ticks;

    /// <summary>Creates a value from a serial number.</summary>
    public ExcelDateTime(double serial, DateKind kind, bool is1904)
    {
        Serial = serial;
        Kind = kind;
        Is1904 = is1904;
    }

    /// <summary>The raw serial number (days, with the time of day as the fraction).</summary>
    public double Serial { get; }

    /// <summary>What the cell's number format shows: a date, a date and time, a time of day or a duration.</summary>
    public DateKind Kind { get; }

    /// <summary>True for workbooks that use the 1904 date system.</summary>
    public bool Is1904 { get; }

    /// <summary>
    /// Converts the serial to a <see cref="System.DateTime"/> (<see cref="DateTimeKind.Unspecified"/>).
    /// Returns false for negative serials, the fictitious 1900-02-29 and values beyond 9999-12-31.
    /// </summary>
    public bool TryGetDateTime(out DateTime value)
    {
        value = default;
        var serial = Serial;
        if (!double.IsFinite(serial) || serial < 0 || serial >= 3_000_000)
        {
            return false;
        }

        // Split before scaling: the whole serial times the units per day would lose precision.
        var whole = Math.Floor(serial);
        var day = (long)whole;
        var millis = (long)Math.Round((serial - whole) * MillisPerDay, MidpointRounding.AwayFromZero);
        if (millis >= (long)MillisPerDay)
        {
            day++;
            millis -= (long)MillisPerDay;
        }

        long epoch;
        if (Is1904)
        {
            epoch = s_ticks19040101;
        }
        else if (day < 60)
        {
            epoch = s_ticks18991231;
        }
        else if (day == 60)
        {
            return false;
        }
        else
        {
            epoch = s_ticks18991230;
        }

        var ticks = epoch + (day * TimeSpan.TicksPerDay) + (millis * TimeSpan.TicksPerMillisecond);
        if (ticks > DateTime.MaxValue.Ticks)
        {
            return false;
        }

        value = new DateTime(ticks, DateTimeKind.Unspecified);
        return true;
    }

    /// <summary>The calendar date (the time of day is dropped).</summary>
    public bool TryGetDateOnly(out DateOnly value)
    {
        var ok = TryGetDateTime(out var dateTime);
        value = ok ? DateOnly.FromDateTime(dateTime) : default;
        return ok;
    }

    /// <summary>The time of day (the date is dropped).</summary>
    public bool TryGetTimeOnly(out TimeOnly value)
    {
        var ok = TryGetDateTime(out var dateTime);
        value = ok ? TimeOnly.FromDateTime(dateTime) : default;
        return ok;
    }

    /// <summary>
    /// The serial as elapsed time (as shown by <c>[h]:mm:ss</c> formats), independent of the date system.
    /// Returns false for non-finite serials and magnitudes of 100 million days or more.
    /// </summary>
    public bool TryGetTimeSpan(out TimeSpan value)
    {
        value = default;
        var serial = Serial;
        if (!double.IsFinite(serial) || Math.Abs(serial) >= 100_000_000)
        {
            return false;
        }

        var whole = Math.Truncate(serial);
        var millis = (long)Math.Round((serial - whole) * MillisPerDay, MidpointRounding.AwayFromZero);
        value = new TimeSpan(((long)whole * TimeSpan.TicksPerDay) + (millis * TimeSpan.TicksPerMillisecond));
        return true;
    }

    /// <summary>
    /// ISO 8601 (<c>2024-05-01</c>, <c>2024-05-01T08:30:00</c>, <c>08:30:00</c>); the serial number for
    /// durations and serials that are not a representable date.
    /// </summary>
    public override string ToString()
    {
        if (Kind == DateKind.Duration || !TryGetDateTime(out var value))
        {
            return Serial.ToString("R", CultureInfo.InvariantCulture);
        }

        var hasMillis = value.Millisecond != 0;
        if (Kind == DateKind.Time && Serial < 1)
        {
            return value.ToString(hasMillis ? "HH:mm:ss.fff" : "HH:mm:ss", CultureInfo.InvariantCulture);
        }

        if (Kind == DateKind.Date && value.TimeOfDay == TimeSpan.Zero)
        {
            return value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        }

        return value.ToString(hasMillis ? "yyyy-MM-dd'T'HH:mm:ss.fff" : "yyyy-MM-dd'T'HH:mm:ss", CultureInfo.InvariantCulture);
    }

    /// <inheritdoc />
    public bool Equals(ExcelDateTime other) =>
        Serial.Equals(other.Serial) && Kind == other.Kind && Is1904 == other.Is1904;

    /// <inheritdoc />
    public override bool Equals(object? obj) => obj is ExcelDateTime other && Equals(other);

    /// <inheritdoc />
    public override int GetHashCode() => HashCode.Combine(Serial, Kind, Is1904);

#pragma warning disable CS1591
    public static bool operator ==(ExcelDateTime left, ExcelDateTime right) => left.Equals(right);

    public static bool operator !=(ExcelDateTime left, ExcelDateTime right) => !left.Equals(right);
#pragma warning restore CS1591

    /// <summary>Days since 0001-01-01 (day 1), proleptic Gregorian.</summary>
    /// <remarks>Howard Hinnant's <c>days_from_civil</c>: https://howardhinnant.github.io/date_algorithms.html</remarks>
    internal static long DaysFromCe(long year, int month, int day)
    {
        var y = month <= 2 ? year - 1 : year;
        var era = (y >= 0 ? y : y - 399) / 400;
        var yoe = y - (era * 400);
        var mp = (month + 9) % 12;
        var doy = (((153 * mp) + 2) / 5) + day - 1;
        var doe = (yoe * 365) + (yoe / 4) - (yoe / 100) + doy;
        return (era * 146_097) + doe - 719_468 + 719_163;
    }

    /// <summary>Converts an ISO 8601 date, date-time or time (<c>t="d"</c> cells) to a serial number.</summary>
    internal static bool TryParseIso(ReadOnlySpan<char> text, bool is1904, out double serial, out DateKind kind)
    {
        serial = 0;
        kind = DateKind.Date;
        var s = text.Trim().TrimEnd('Z');
        ReadOnlySpan<char> date = default, time = default;
        bool hasDate, hasTime;
        var t = s.IndexOf('T');
        if (t >= 0)
        {
            date = s[..t];
            time = s[(t + 1)..];
            hasDate = hasTime = true;
        }
        else if (s.Contains(':'))
        {
            time = s;
            hasDate = false;
            hasTime = true;
        }
        else
        {
            date = s;
            hasDate = true;
            hasTime = false;
        }

        long dayNumber = 0;
        if (hasDate)
        {
            var dash1 = date.IndexOf('-');
            if (dash1 < 0)
            {
                return false;
            }

            var rest = date[(dash1 + 1)..];
            var dash2 = rest.IndexOf('-');
            if (dash2 < 0
                || !long.TryParse(date[..dash1], NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var year)
                || !int.TryParse(rest[..dash2], NumberStyles.None, CultureInfo.InvariantCulture, out var month)
                || !int.TryParse(rest[(dash2 + 1)..], NumberStyles.None, CultureInfo.InvariantCulture, out var day)
                || month is < 1 or > 12
                || day is < 1 or > 31)
            {
                return false;
            }

            var days = DaysFromCe(year, month, day);
            if (is1904)
            {
                dayNumber = days - Epoch1904;
            }
            else
            {
                dayNumber = days - Epoch1900;
                if (dayNumber < 61)
                {
                    dayNumber--;
                }
            }
        }

        double fraction = 0;
        if (hasTime)
        {
            var colon1 = time.IndexOf(':');
            if (colon1 < 0)
            {
                return false;
            }

            var rest = time[(colon1 + 1)..];
            var colon2 = rest.IndexOf(':');
            var minutesText = colon2 < 0 ? rest : rest[..colon2];
            double seconds = 0;
            if (!double.TryParse(time[..colon1], NumberStyles.Float, CultureInfo.InvariantCulture, out var hours)
                || !double.TryParse(minutesText, NumberStyles.Float, CultureInfo.InvariantCulture, out var minutes)
                || (colon2 >= 0 && !double.TryParse(rest[(colon2 + 1)..], NumberStyles.Float, CultureInfo.InvariantCulture, out seconds)))
            {
                return false;
            }

            fraction = ((hours * 3600) + (minutes * 60) + seconds) / 86_400;
        }

        kind = hasDate && hasTime ? DateKind.DateTime : hasDate ? DateKind.Date : DateKind.Time;
        serial = dayNumber + fraction;
        return true;
    }
}
