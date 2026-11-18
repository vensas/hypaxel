package hypaxel

import (
	"database/sql/driver"
	"encoding"
	"fmt"
	"reflect"
	"time"
)

// Formula is a cell value holding a formula such as "SUM(A1:A9)"; the leading "=" is optional.
type Formula string

// Cell is a value with its own style, for use in [Sheet.Write] and [Sheet.WriteRow].
type Cell struct {
	Value any
	Style StyleID
}

// Styled pairs a value with a style: sheet.WriteRow("Total", hypaxel.Styled(1234.5, money)).
func Styled(value any, style StyleID) Cell {
	return Cell{Value: value, Style: style}
}

// Write writes any supported value as the next cell: strings, integers, floats, booleans,
// [time.Time], [time.Duration], [Formula], [Cell], []byte (as text) and nil (a blank cell),
// as well as pointers to them (nil pointers are blank) and named types based on them. Values
// implementing [driver.Valuer] (such as sql.NullString), [encoding.TextMarshaler] or
// [fmt.Stringer] are written through those methods. Anything else records [ErrUnsupported].
//
// Passing a value as any may allocate; the typed methods do not.
func (s *Sheet) Write(v any, style ...StyleID) {
	s.writeAny(v, styleOf(style))
}

func (s *Sheet) writeAny(v any, style StyleID) {
	switch x := v.(type) {
	case nil:
		s.writeBlank(style)
	case string:
		s.writeString(x, style)
	case int:
		s.WriteInt(int64(x), style)
	case int64:
		s.WriteInt(x, style)
	case float64:
		s.writeFloat(x, 64, style)
	case bool:
		s.WriteBool(x, style)
	case time.Time:
		s.writeTemporal(x, kindDateTime, style)
	case *time.Time:
		if x == nil {
			s.writeBlank(style)
		} else {
			s.writeTemporal(*x, kindDateTime, style)
		}
	case time.Duration:
		s.WriteDuration(x, style)
	case Formula:
		s.WriteFormula(string(x), style)
	case Cell:
		s.writeAny(x.Value, x.Style)
	case int8:
		s.WriteInt(int64(x), style)
	case int16:
		s.WriteInt(int64(x), style)
	case int32:
		s.WriteInt(int64(x), style)
	case uint:
		s.WriteUint(uint64(x), style)
	case uint8:
		s.WriteUint(uint64(x), style)
	case uint16:
		s.WriteUint(uint64(x), style)
	case uint32:
		s.WriteUint(uint64(x), style)
	case uint64:
		s.WriteUint(x, style)
	case float32:
		s.writeFloat(float64(x), 32, style)
	case []byte:
		s.writeString(string(x), style)
	default:
		s.writeOther(v, style)
	}
}

func (s *Sheet) writeOther(v any, style StyleID) {
	rv := reflect.ValueOf(v)
	if rv.Kind() == reflect.Pointer && rv.IsNil() {
		s.writeBlank(style)
		return
	}
	switch x := v.(type) {
	case driver.Valuer:
		value, err := x.Value()
		if err != nil {
			s.failValue(v, err)
			return
		}
		if _, again := value.(driver.Valuer); again {
			s.unsupported(v)
			return
		}
		s.writeAny(value, style)
		return
	case encoding.TextMarshaler:
		text, err := x.MarshalText()
		if err != nil {
			s.failValue(v, err)
			return
		}
		s.writeString(string(text), style)
		return
	case fmt.Stringer:
		s.writeString(x.String(), style)
		return
	}
	switch rv.Kind() {
	case reflect.Pointer:
		s.writeAny(rv.Elem().Interface(), style)
	case reflect.String:
		s.writeString(rv.String(), style)
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		s.WriteInt(rv.Int(), style)
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		s.WriteUint(rv.Uint(), style)
	case reflect.Float32:
		s.writeFloat(rv.Float(), 32, style)
	case reflect.Float64:
		s.writeFloat(rv.Float(), 64, style)
	case reflect.Bool:
		s.WriteBool(rv.Bool(), style)
	default:
		s.unsupported(v)
	}
}

func (s *Sheet) unsupported(v any) {
	s.failValue(v, ErrUnsupported)
}

func (s *Sheet) failValue(v any, err error) {
	if s.usable() {
		row, column := s.w.nextCell()
		s.w.fail(fmt.Errorf("%w: %T at row %d, column %d", err, v, row, column))
	}
}

// WriteRow writes a complete row from values accepted by [Sheet.Write] and ends it. A row
// that is still open is ended first.
func (s *Sheet) WriteRow(values ...any) error {
	return s.WriteRowStyled(DefaultStyle, values...)
}

// WriteRowStyled is like [Sheet.WriteRow] with a style for every cell that is not a [Cell]
// carrying its own.
func (s *Sheet) WriteRowStyled(style StyleID, values ...any) error {
	if !s.usable() || !s.w.beginNextRow() {
		return s.w.err
	}
	for _, v := range values {
		s.writeAny(v, style)
	}
	return s.endWrittenRow()
}

// WriteStrings writes a complete row of text cells, such as a header, and ends it.
func (s *Sheet) WriteStrings(values []string, style ...StyleID) error {
	if !s.usable() || !s.w.beginNextRow() {
		return s.w.err
	}
	st := styleOf(style)
	for _, v := range values {
		s.writeString(v, st)
	}
	return s.endWrittenRow()
}

func (s *Sheet) endWrittenRow() error {
	w := s.w
	if w.err == nil && w.rowOpen {
		w.closeRow()
	}
	return w.err
}
