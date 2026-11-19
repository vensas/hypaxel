package hypaxel

import (
	"math"
	"strconv"
	"strings"
	"time"
)

// ValueKind is the type of a cell [Value].
type ValueKind uint8

const (
	// ValueEmpty is a missing cell or a cell that only carries formatting.
	ValueEmpty ValueKind = iota
	// ValueString is text: a shared string, an inline string or the cached result of a text formula.
	ValueString
	// ValueNumber is a number that is not formatted as a date or time.
	ValueNumber
	// ValueBool is TRUE or FALSE.
	ValueBool
	// ValueDateTime is a number formatted as a date, time or duration.
	ValueDateTime
	// ValueError is an error value such as #DIV/0! or #N/A.
	ValueError
)

func (k ValueKind) String() string {
	switch k {
	case ValueEmpty:
		return "empty"
	case ValueString:
		return "string"
	case ValueNumber:
		return "number"
	case ValueBool:
		return "bool"
	case ValueDateTime:
		return "datetime"
	case ValueError:
		return "error"
	}
	return "ValueKind(" + strconv.Itoa(int(k)) + ")"
}

// DateKind is how a cell's number format presents a date serial.
type DateKind uint8

const (
	// DateKindNone marks a value that is not a date.
	DateKindNone DateKind = iota
	// DateKindDate is a calendar date without a time of day (e.g. yyyy-mm-dd).
	DateKindDate
	// DateKindDateTime is a date with a time of day (e.g. yyyy-mm-dd hh:mm).
	DateKindDateTime
	// DateKindTime is a time of day (e.g. hh:mm:ss).
	DateKindTime
	// DateKindDuration is elapsed time (e.g. [h]:mm:ss).
	DateKindDuration
)

func (k DateKind) String() string {
	switch k {
	case DateKindNone:
		return "none"
	case DateKindDate:
		return "date"
	case DateKindDateTime:
		return "datetime"
	case DateKindTime:
		return "time"
	case DateKindDuration:
		return "duration"
	}
	return "DateKind(" + strconv.Itoa(int(k)) + ")"
}

// Value is the value of one cell. The zero Value is empty. Values are small, immutable and
// safe to keep after the row they came from has been replaced.
type Value struct {
	text     string
	num      float64
	kind     ValueKind
	date     DateKind
	date1904 bool
}

// Kind reports the type of the value.
func (v Value) Kind() ValueKind { return v.kind }

// IsEmpty reports whether the cell has no value.
func (v Value) IsEmpty() bool { return v.kind == ValueEmpty }

// Text returns the text of a string or error value, and "" for other kinds.
func (v Value) Text() string { return v.text }

// Float returns the number of a numeric cell or, for dates and times, the serial number.
func (v Value) Float() (float64, bool) {
	return v.num, v.kind == ValueNumber || v.kind == ValueDateTime
}

// Bool returns the value of a boolean cell.
func (v Value) Bool() (value, ok bool) {
	return v.num != 0, v.kind == ValueBool
}

// DateKind reports what the number format of a date value shows, or DateKindNone.
func (v Value) DateKind() DateKind { return v.date }

// Time returns the date and time of a date value as a UTC wall-clock time, rounded to the
// millisecond. A time of day without a date falls on Excel's day 0 (1899-12-31, or 1904-01-01 in
// the 1904 date system); use its Clock. It returns false for other kinds, negative serials and
// the fictitious 1900-02-29 of the 1900 date system.
func (v Value) Time() (time.Time, bool) {
	if v.kind != ValueDateTime {
		return time.Time{}, false
	}
	return serialToTime(v.num, v.date1904)
}

// Duration returns a date value as elapsed time, as used by formats such as [h]:mm:ss, rounded
// to the millisecond. It returns false for other kinds and values beyond time.Duration's range.
func (v Value) Duration() (time.Duration, bool) {
	if v.kind != ValueDateTime || math.IsNaN(v.num) || math.Abs(v.num) >= 106_751 {
		return 0, false
	}
	whole := math.Trunc(v.num)
	millis := int64(whole)*millisPerDay + int64(math.Round((v.num-whole)*millisPerDay))
	return time.Duration(millis) * time.Millisecond, true
}

// String formats the value roughly as Excel's General format would, with dates in ISO 8601:
// 2024-05-01, 2024-05-01T08:30:00, 08:30:00 for a time of day. Durations and serials that are
// not a representable date are shown as numbers.
func (v Value) String() string {
	switch v.kind {
	case ValueString, ValueError:
		return v.text
	case ValueNumber:
		return formatNumber(v.num)
	case ValueBool:
		if v.num != 0 {
			return "TRUE"
		}
		return "FALSE"
	case ValueDateTime:
		return string(v.appendDateTime(nil))
	}
	return ""
}

func formatNumber(n float64) string {
	return strconv.FormatFloat(n, 'f', -1, 64)
}

const (
	millisPerDay = 86_400_000
	// Days from 1899-12-30 and 1904-01-01 to the Unix epoch.
	unixDays1900 = 25_569
	unixDays1904 = 24_107
)

// serialDays splits a serial into days since the Unix epoch and milliseconds into the day,
// applying the 1900 leap-year bug.
func serialDays(serial float64, date1904 bool) (days, millis int64, ok bool) {
	if math.IsNaN(serial) || serial < 0 || serial >= 3_000_000 {
		return 0, 0, false
	}
	// Split before scaling: the whole serial times the milliseconds per day would lose precision.
	whole := math.Floor(serial)
	day := int64(whole)
	millis = int64(math.Round((serial - whole) * millisPerDay))
	if millis >= millisPerDay {
		day++
		millis -= millisPerDay
	}
	switch {
	case date1904:
		days = day - unixDays1904
	case day < 60:
		// Serials before Excel's fictitious 1900-02-29 count from 1899-12-31.
		days = day - unixDays1900 + 1
	case day == 60:
		return 0, 0, false
	default:
		days = day - unixDays1900
	}
	return days, millis, true
}

func serialToTime(serial float64, date1904 bool) (time.Time, bool) {
	days, millis, ok := serialDays(serial, date1904)
	if !ok {
		return time.Time{}, false
	}
	return time.UnixMilli(days*millisPerDay + millis).UTC(), true
}

func (v Value) appendDateTime(dst []byte) []byte {
	days, millis, ok := serialDays(v.num, v.date1904)
	if v.date == DateKindDuration || !ok {
		return strconv.AppendFloat(dst, v.num, 'f', -1, 64)
	}
	if v.date == DateKindTime && v.num < 1 {
		return appendClock(dst, millis)
	}
	t := time.UnixMilli(days * millisPerDay).UTC()
	dst = appendPadded(dst, t.Year(), 4)
	dst = append(dst, '-')
	dst = appendPadded(dst, int(t.Month()), 2)
	dst = append(dst, '-')
	dst = appendPadded(dst, t.Day(), 2)
	if v.date != DateKindDate || millis != 0 {
		dst = append(dst, 'T')
		dst = appendClock(dst, millis)
	}
	return dst
}

func appendClock(dst []byte, millis int64) []byte {
	secs := int(millis / 1000)
	dst = appendPadded(dst, secs/3600, 2)
	dst = append(dst, ':')
	dst = appendPadded(dst, secs/60%60, 2)
	dst = append(dst, ':')
	dst = appendPadded(dst, secs%60, 2)
	if frac := int(millis % 1000); frac != 0 {
		dst = append(dst, '.')
		dst = appendPadded(dst, frac, 3)
	}
	return dst
}

func appendPadded(dst []byte, n, width int) []byte {
	for w := 10; width > 1; width-- {
		if n < w {
			dst = append(dst, '0')
		}
		w *= 10
	}
	return strconv.AppendInt(dst, int64(n), 10)
}

// daysFromCivil returns the days from the Unix epoch to a proleptic Gregorian date.
func daysFromCivil(y int64, m, d int) int64 {
	if m <= 2 {
		y--
	}
	era := y / 400
	if y < 0 && y%400 != 0 {
		era--
	}
	yoe := y - era*400
	mp := int64((m + 9) % 12)
	doy := (153*mp+2)/5 + int64(d) - 1
	doe := yoe*365 + yoe/4 - yoe/100 + doy
	return era*146_097 + doe - 719_468
}

// isoToSerial converts an ISO 8601 date, date-time or time (t="d" cells) to a serial number.
func isoToSerial(s string, date1904 bool) (float64, DateKind, bool) {
	s = strings.TrimSuffix(strings.TrimSpace(s), "Z")
	var date, clock string
	if d, t, found := strings.Cut(s, "T"); found {
		if d == "" || t == "" {
			return 0, 0, false
		}
		date, clock = d, t
	} else if strings.Contains(s, ":") {
		clock = s
	} else {
		date = s
	}
	var day int64
	if date != "" {
		parts := strings.SplitN(date, "-", 3)
		if len(parts) != 3 {
			return 0, 0, false
		}
		y, err1 := strconv.ParseInt(parts[0], 10, 32)
		m, err2 := strconv.Atoi(parts[1])
		d, err3 := strconv.Atoi(parts[2])
		if err1 != nil || err2 != nil || err3 != nil || m < 1 || m > 12 || d < 1 || d > 31 {
			return 0, 0, false
		}
		days := daysFromCivil(y, m, d)
		if date1904 {
			day = days + unixDays1904
		} else {
			day = days + unixDays1900
			if day < 61 {
				day--
			}
		}
	}
	var fraction float64
	if clock != "" {
		parts := strings.SplitN(clock, ":", 3)
		if len(parts) < 2 {
			return 0, 0, false
		}
		var hms [3]float64
		for i, p := range parts {
			n, err := strconv.ParseFloat(p, 64)
			if err != nil || math.IsNaN(n) || math.IsInf(n, 0) {
				return 0, 0, false
			}
			hms[i] = n
		}
		fraction = (hms[0]*3600 + hms[1]*60 + hms[2]) / 86_400
	}
	kind := DateKindTime
	switch {
	case date != "" && clock != "":
		kind = DateKindDateTime
	case date != "":
		kind = DateKindDate
	}
	return float64(day) + fraction, kind, true
}
