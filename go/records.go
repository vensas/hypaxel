package hypaxel

import (
	"context"
	"database/sql/driver"
	"encoding"
	"fmt"
	"iter"
	"reflect"
	"strconv"
	"strings"
	"time"
)

type fieldKind uint8

const (
	fieldAny fieldKind = iota
	fieldString
	fieldInt
	fieldUint
	fieldFloat32
	fieldFloat64
	fieldBool
	fieldTime
	fieldDuration
)

type structField struct {
	index    []int
	header   string
	width    float64
	hidden   bool
	dateOnly bool
	kind     fieldKind
	style    StyleID
}

// structPlan maps the exported fields of a struct type to columns. Plans belong to a Writer
// because number formats from tags are registered as that Writer's styles.
type structPlan struct {
	fields []structField
}

var (
	timeType          = reflect.TypeFor[time.Time]()
	durationType      = reflect.TypeFor[time.Duration]()
	valuerType        = reflect.TypeFor[driver.Valuer]()
	textMarshalerType = reflect.TypeFor[encoding.TextMarshaler]()
	stringerType      = reflect.TypeFor[fmt.Stringer]()
)

type tagOptions struct {
	header   string
	width    float64
	hidden   bool
	dateOnly bool
	format   string
}

// parseTag parses `xlsx:"Header,width=20,hidden,date,format=#,##0.00"`. Number formats contain
// commas, so format must come last and takes the rest of the tag.
func parseTag(tag string) (tagOptions, error) {
	var o tagOptions
	const formatKey = "format="
	if i := strings.Index(tag, ","+formatKey); i >= 0 {
		o.format = tag[i+1+len(formatKey):]
		tag = tag[:i]
	} else if strings.HasPrefix(tag, formatKey) {
		o.format = tag[len(formatKey):]
		tag = ""
	}
	parts := strings.Split(tag, ",")
	o.header = parts[0]
	for _, part := range parts[1:] {
		key, value, _ := strings.Cut(part, "=")
		switch key {
		case "width":
			width, err := strconv.ParseFloat(value, 64)
			if err != nil || !(width > 0 && width <= 255) {
				return o, fmt.Errorf("width %q must be a number within 0..255", value)
			}
			o.width = width
		case "hidden":
			o.hidden = true
		case "date":
			o.dateOnly = true
		default:
			return o, fmt.Errorf("unknown option %q", part)
		}
	}
	return o, nil
}

func hasCellMethods(t reflect.Type) bool {
	return t.Implements(valuerType) || t.Implements(textMarshalerType) || t.Implements(stringerType)
}

func kindOfField(t reflect.Type) fieldKind {
	switch {
	case t == timeType:
		return fieldTime
	case t == durationType:
		return fieldDuration
	case hasCellMethods(t):
		return fieldAny
	}
	switch t.Kind() {
	case reflect.String:
		return fieldString
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return fieldInt
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return fieldUint
	case reflect.Float32:
		return fieldFloat32
	case reflect.Float64:
		return fieldFloat64
	case reflect.Bool:
		return fieldBool
	}
	return fieldAny
}

func isPlainStruct(t reflect.Type) bool {
	return t.Kind() == reflect.Struct && t != timeType && !hasCellMethods(t) && !hasCellMethods(reflect.PointerTo(t))
}

func (w *Writer) collectFields(t reflect.Type, prefix []int, fields []structField) ([]structField, error) {
	for i := range t.NumField() {
		f := t.Field(i)
		tag, tagged := f.Tag.Lookup("xlsx")
		if tag == "-" {
			continue
		}
		index := append(append(make([]int, 0, len(prefix)+1), prefix...), i)
		if f.Anonymous && !tagged {
			embedded := f.Type
			if embedded.Kind() == reflect.Pointer {
				embedded = embedded.Elem()
			}
			if isPlainStruct(embedded) {
				var err error
				if fields, err = w.collectFields(embedded, index, fields); err != nil {
					return nil, err
				}
				continue
			}
		}
		if !f.IsExported() {
			continue
		}
		opts, err := parseTag(tag)
		if err != nil {
			return nil, fmt.Errorf("%w: xlsx tag of %s.%s: %v", ErrInvalidArgument, t, f.Name, err)
		}
		field := structField{
			index:    index,
			header:   opts.header,
			width:    opts.width,
			hidden:   opts.hidden,
			dateOnly: opts.dateOnly,
			kind:     kindOfField(f.Type),
		}
		if field.header == "" {
			field.header = f.Name
		}
		if opts.format != "" {
			field.style = w.AddStyle(Style{NumberFormat: opts.format})
		}
		fields = append(fields, field)
	}
	return fields, nil
}

func (w *Writer) planFor(t reflect.Type) (*structPlan, error) {
	if plan, ok := w.plans[t]; ok {
		return plan, nil
	}
	if !isPlainStruct(t) {
		return nil, fmt.Errorf("%w: %s is not a struct", ErrUnsupported, t)
	}
	fields, err := w.collectFields(t, nil, nil)
	if err != nil {
		return nil, err
	}
	if len(fields) == 0 {
		return nil, fmt.Errorf("%w: %s has no exported fields", ErrUnsupported, t)
	}
	if len(fields) > MaxColumns {
		return nil, fmt.Errorf("%w: %s", ErrTooManyColumns, t)
	}
	plan := &structPlan{fields: fields}
	if w.plans == nil {
		w.plans = map[reflect.Type]*structPlan{}
	}
	w.plans[t] = plan
	return plan, nil
}

func (p *structPlan) columns() []Column {
	columns := make([]Column, len(p.fields))
	for i, f := range p.fields {
		columns[i] = Column{Width: f.width, Hidden: f.hidden}
	}
	return columns
}

func derefType(t reflect.Type) reflect.Type {
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	return t
}

// derefValue follows pointers and interfaces; ok is false when it meets a nil.
func derefValue(v reflect.Value) (reflect.Value, bool) {
	for v.Kind() == reflect.Pointer || v.Kind() == reflect.Interface {
		if v.IsNil() {
			return v, false
		}
		v = v.Elem()
	}
	return v, v.IsValid()
}

func (s *Sheet) writeFields(plan *structPlan, v reflect.Value) {
	for i := range plan.fields {
		f := &plan.fields[i]
		var fv reflect.Value
		if len(f.index) == 1 {
			fv = v.Field(f.index[0])
		} else {
			var err error
			if fv, err = v.FieldByIndexErr(f.index); err != nil {
				s.writeBlank(f.style)
				continue
			}
		}
		switch f.kind {
		case fieldString:
			s.writeString(fv.String(), f.style)
		case fieldInt:
			s.WriteInt(fv.Int(), f.style)
		case fieldUint:
			s.WriteUint(fv.Uint(), f.style)
		case fieldFloat32:
			s.writeFloat(fv.Float(), 32, f.style)
		case fieldFloat64:
			s.writeFloat(fv.Float(), 64, f.style)
		case fieldBool:
			s.WriteBool(fv.Bool(), f.style)
		case fieldDuration:
			s.WriteDuration(time.Duration(fv.Int()), f.style)
		case fieldTime:
			var t time.Time
			if fv.CanAddr() {
				// Through the address, because boxing a time.Time allocates.
				t = *fv.Addr().Interface().(*time.Time)
			} else {
				t = fv.Interface().(time.Time)
			}
			s.writeTemporal(t, f.temporalKind(), f.style)
		default:
			value := fv.Interface()
			if t, ok := value.(*time.Time); ok && t != nil && f.dateOnly {
				s.writeTemporal(*t, kindDate, f.style)
			} else {
				s.writeAny(value, f.style)
			}
		}
	}
}

func (f *structField) temporalKind() valueKind {
	if f.dateOnly {
		return kindDate
	}
	return kindDateTime
}

// StructColumns returns the column widths and visibility declared by the xlsx tags of a
// struct type, for [SheetOptions.Columns]. v is a struct or a pointer to one.
func (w *Writer) StructColumns(v any) ([]Column, error) {
	if v == nil {
		return nil, fmt.Errorf("%w: nil", ErrUnsupported)
	}
	plan, err := w.planFor(derefType(reflect.TypeOf(v)))
	if err != nil {
		return nil, err
	}
	return plan.columns(), nil
}

// WriteStructHeader writes a row with the column headers of a struct type and ends it.
// v is a struct or a pointer to one; only its type is used.
func (s *Sheet) WriteStructHeader(v any, style ...StyleID) error {
	if !s.usable() {
		return s.w.err
	}
	if v == nil {
		return fmt.Errorf("%w: nil", ErrUnsupported)
	}
	plan, err := s.w.planFor(derefType(reflect.TypeOf(v)))
	if err != nil {
		return err
	}
	return s.writeHeader(plan, styleOf(style))
}

func (s *Sheet) writeHeader(plan *structPlan, style StyleID) error {
	if !s.w.beginNextRow() {
		return s.w.err
	}
	for i := range plan.fields {
		s.writeString(plan.fields[i].header, style)
	}
	return s.endWrittenRow()
}

// WriteStruct writes one row from the exported fields of a struct and ends it. v is a struct
// or a pointer to one; a nil pointer writes an empty row.
//
// Fields map to columns in declaration order and are configured with a struct tag:
//
//	type Order struct {
//		ID      int       `xlsx:"Order #,width=10"`
//		Created time.Time `xlsx:"Created,date"`
//		Amount  float64   `xlsx:"Amount,width=14,format=#,##0.00"`
//		Note    string    `xlsx:"-"`
//	}
//
// The first element is the header (the field name when empty), followed by any of width=N,
// hidden, date (drop the time of day) and format=CODE. A number format contains commas, so
// format must come last and takes the rest of the tag. Embedded structs are flattened.
func (s *Sheet) WriteStruct(v any) error {
	if !s.usable() {
		return s.w.err
	}
	if v == nil {
		return fmt.Errorf("%w: nil", ErrUnsupported)
	}
	plan, err := s.w.planFor(derefType(reflect.TypeOf(v)))
	if err != nil {
		return err
	}
	return s.writeStructValue(plan, reflect.ValueOf(v))
}

func (s *Sheet) writeStructValue(plan *structPlan, v reflect.Value) error {
	if !s.w.beginNextRow() {
		return s.w.err
	}
	if v, ok := derefValue(v); ok {
		s.writeFields(plan, v)
	}
	return s.endWrittenRow()
}

// table writes a sheet from a sequence of rows of one type: structs get a header row, a
// frozen pane and an auto filter, slices and arrays are written as plain rows.
type table struct {
	sheet *Sheet
	plan  *structPlan
}

func newTable(w *Writer, name string, rowType reflect.Type) (*table, error) {
	t := derefType(rowType)
	if t.Kind() == reflect.Slice || t.Kind() == reflect.Array || t.Kind() == reflect.Interface {
		sheet, err := w.AddSheet(name)
		if err != nil {
			return nil, err
		}
		return &table{sheet: sheet}, nil
	}
	if w.closed {
		return nil, ErrClosed
	}
	plan, err := w.planFor(t)
	if err != nil {
		return nil, err
	}
	sheet, err := w.AddSheetOptions(name, SheetOptions{Columns: plan.columns(), FrozenRows: 1})
	if err != nil {
		return nil, err
	}
	header := w.AddStyle(Style{Font: Font{Bold: true}})
	if err := sheet.writeHeader(plan, header); err != nil {
		return nil, err
	}
	return &table{sheet: sheet, plan: plan}, nil
}

func (t *table) write(v reflect.Value) error {
	s := t.sheet
	if t.plan != nil {
		return s.writeStructValue(t.plan, v)
	}
	if !s.usable() || !s.w.beginNextRow() {
		return s.w.err
	}
	if v, ok := derefValue(v); ok {
		switch v.Kind() {
		case reflect.Slice, reflect.Array:
			for i := range v.Len() {
				s.writeAny(v.Index(i).Interface(), DefaultStyle)
			}
		case reflect.Struct:
			plan, err := s.w.planFor(v.Type())
			if err != nil {
				return err
			}
			s.writeFields(plan, v)
		default:
			return fmt.Errorf("%w: row of type %s; rows are structs, slices or arrays", ErrUnsupported, v.Type())
		}
	}
	return s.endWrittenRow()
}

func (t *table) finish() error {
	s := t.sheet
	if t.plan != nil && s.usable() {
		return s.AutoFilter(1, 1, max(s.Row(), 1), len(t.plan.fields))
	}
	return s.w.err
}

// WriteSheet adds a worksheet and writes one row per element of rows.
//
// When T is a struct or a pointer to one, the sheet gets a bold header row from the xlsx
// struct tags (see [Sheet.WriteStruct]), a frozen first row, the tagged column widths and an
// auto filter. When T is a slice or array, such as []any, its elements are written as plain
// rows without a header.
func WriteSheet[T any](w *Writer, name string, rows []T) error {
	t, err := newTable(w, name, reflect.TypeFor[T]())
	if err != nil {
		return err
	}
	for i := range rows {
		if err := t.write(reflect.ValueOf(&rows[i]).Elem()); err != nil {
			return err
		}
	}
	return t.finish()
}

// WriteSheetSeq is like [WriteSheet] for a sequence, which is consumed lazily: memory stays
// flat however many rows it yields.
func WriteSheetSeq[T any](w *Writer, name string, rows iter.Seq[T]) error {
	t, err := newTable(w, name, reflect.TypeFor[T]())
	if err != nil {
		return err
	}
	// One addressable slot for every row, so reflection never boxes a row.
	var slot T
	v := reflect.ValueOf(&slot).Elem()
	for row := range rows {
		slot = row
		if err := t.write(v); err != nil {
			return err
		}
	}
	return t.finish()
}

// WriteSheetSeq2 is like [WriteSheetSeq] for a sequence that can fail, such as a database
// cursor. It stops at the first non-nil error and returns it.
func WriteSheetSeq2[T any](w *Writer, name string, rows iter.Seq2[T, error]) error {
	t, err := newTable(w, name, reflect.TypeFor[T]())
	if err != nil {
		return err
	}
	var slot T
	v := reflect.ValueOf(&slot).Elem()
	for row, err := range rows {
		if err != nil {
			return err
		}
		slot = row
		if err := t.write(v); err != nil {
			return err
		}
	}
	return t.finish()
}

// WriteSheetChan is like [WriteSheet] for rows received from a channel. It returns when the
// channel is closed, or with the context's error when ctx is done first.
func WriteSheetChan[T any](ctx context.Context, w *Writer, name string, rows <-chan T) error {
	t, err := newTable(w, name, reflect.TypeFor[T]())
	if err != nil {
		return err
	}
	var slot T
	v := reflect.ValueOf(&slot).Elem()
	for {
		select {
		case <-ctx.Done():
			return context.Cause(ctx)
		case row, ok := <-rows:
			if !ok {
				return t.finish()
			}
			slot = row
			if err := t.write(v); err != nil {
				return err
			}
		}
	}
}
