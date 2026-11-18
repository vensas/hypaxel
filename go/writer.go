package hypaxel

import (
	"fmt"
	"io"
	"reflect"
	"strings"
)

// Excel's worksheet limits.
const (
	MaxRows       = 1_048_576
	MaxColumns    = 16_384
	MaxCellLength = 32_767
)

// ContentType is the MIME type of an XLSX workbook.
const ContentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

const (
	defaultFlushThreshold = 64 * 1024
	minFlushThreshold     = 1024
	xmlBufferSize         = 64 * 1024
	maxSerial             = 2_958_465
)

type config struct {
	compressionLevel int
	sharedStrings    bool
	flushThreshold   int
	cellReferences   bool
	date1904         bool
}

// Option configures a [Writer].
type Option func(*config)

// WithCompressionLevel sets the deflate level, 0 (stored) to 9 (smallest). The default is 1,
// the fastest level that compresses.
func WithCompressionLevel(level int) Option {
	return func(c *config) { c.compressionLevel = min(max(level, 0), 9) }
}

// WithSharedStrings stores text in a shared string table instead of inline in each cell:
// smaller files for repeated text, but memory grows with the number of distinct strings.
func WithSharedStrings(enabled bool) Option {
	return func(c *config) { c.sharedStrings = enabled }
}

// WithFlushThreshold sets how many compressed bytes are buffered before they are written to
// the destination. The default is 64 KiB and the minimum 1 KiB.
func WithFlushThreshold(bytes int) Option {
	return func(c *config) { c.flushThreshold = max(bytes, minFlushThreshold) }
}

// WithCellReferences writes a reference such as r="B7" on every cell. By default references
// are only written after a gap, which the format allows and most readers accept.
func WithCellReferences(always bool) Option {
	return func(c *config) { c.cellReferences = always }
}

// WithDate1904 selects the 1904 date system instead of the default 1900 system.
func WithDate1904(enabled bool) Option {
	return func(c *config) { c.date1904 = enabled }
}

// Writer writes one workbook to an [io.Writer]. Create it with [NewWriter] and finish it with
// [Writer.Close].
//
// Errors from the destination and from cell writes are sticky: once one occurs, every later
// call returns it and nothing more is written.
type Writer struct {
	dst    io.Writer
	cfg    config
	zip    zipStream
	xml    []byte
	styles styleRegistry
	sst    *sharedStrings
	sheets []sheetMeta
	merges []cellRange
	plans  map[reflect.Type]*structPlan

	current      *Sheet
	row          uint32
	column       uint32
	lastColumn   uint32
	rowOpen      bool
	rowRef       [8]byte
	rowRefLen    int
	usesFormulas bool
	closed       bool
	err          error
	scratch      [40]byte
}

// NewWriter returns a Writer that streams a workbook to dst.
func NewWriter(dst io.Writer, opts ...Option) *Writer {
	w := &Writer{dst: dst, cfg: config{compressionLevel: 1, flushThreshold: defaultFlushThreshold}}
	for _, opt := range opts {
		opt(&w.cfg)
	}
	w.zip.init(w.cfg.compressionLevel, w.cfg.flushThreshold+w.cfg.flushThreshold/2)
	w.xml = make([]byte, 0, xmlBufferSize+4096)
	w.styles.init()
	if w.cfg.sharedStrings {
		w.sst = &sharedStrings{index: map[string]uint32{}}
	}
	return w
}

// AddStyle registers a cell format and returns its handle. Identical styles share a handle.
func (w *Writer) AddStyle(style Style) StyleID {
	return w.styles.add(style)
}

// Column describes one worksheet column. The zero value leaves the column at its defaults.
type Column struct {
	// Width in characters of the default font, up to 255; zero keeps the default width.
	Width float64
	// Style applies to cells of the column that are never written.
	Style  StyleID
	Hidden bool
}

// Widths returns column definitions with the given widths, for [SheetOptions.Columns].
func Widths(widths ...float64) []Column {
	columns := make([]Column, len(widths))
	for i, width := range widths {
		columns[i].Width = width
	}
	return columns
}

// SheetOptions holds the worksheet settings that precede the cell data in the file, which is
// why they are fixed when the sheet is added.
type SheetOptions struct {
	// Columns defines columns starting at A; zero-valued entries are skipped.
	Columns []Column
	// FrozenRows is the number of rows pinned at the top, for example 1 for a header.
	FrozenRows int
	// FrozenColumns is the number of columns pinned at the left.
	FrozenColumns int
	HideGridLines bool
	Hidden        bool
	TabColor      Color
}

// AddSheet adds a worksheet with default options. The previous worksheet is completed and can
// no longer be written to.
func (w *Writer) AddSheet(name string) (*Sheet, error) {
	return w.AddSheetOptions(name, SheetOptions{})
}

// AddSheetOptions adds a worksheet with column definitions, frozen panes and other settings.
func (w *Writer) AddSheetOptions(name string, opts SheetOptions) (*Sheet, error) {
	if w.closed {
		return nil, ErrClosed
	}
	if w.err != nil {
		return nil, w.err
	}
	if err := w.validateSheet(name, &opts); err != nil {
		return nil, err
	}

	w.completeSheet()
	index := len(w.sheets)
	w.sheets = append(w.sheets, sheetMeta{name: name, hidden: opts.Hidden})
	w.xml = append(w.xml[:0], "xl/worksheets/sheet"...)
	w.xml = appendUint(w.xml, uint64(index+1))
	w.xml = append(w.xml, ".xml"...)
	w.zip.startEntry(string(w.xml))
	w.current = &Sheet{w: w}
	w.row, w.column, w.lastColumn, w.rowOpen = 0, 0, 0, false
	w.merges = w.merges[:0]
	w.xml = appendSheetHeader(w.xml[:0], index, &opts)
	return w.current, nil
}

func (w *Writer) validateSheet(name string, opts *SheetOptions) error {
	if name == "" || utf16Len(name) > 31 || strings.ContainsAny(name, `:\/?*[]`) ||
		strings.HasPrefix(name, "'") || strings.HasSuffix(name, "'") || strings.EqualFold(name, "History") {
		return fmt.Errorf("%w: %q", ErrInvalidSheetName, name)
	}
	for _, sheet := range w.sheets {
		if strings.EqualFold(sheet.name, name) {
			return fmt.Errorf("%w: %q", ErrDuplicateSheetName, name)
		}
	}
	if opts.FrozenRows < 0 || opts.FrozenRows >= MaxRows || opts.FrozenColumns < 0 || opts.FrozenColumns >= MaxColumns {
		return fmt.Errorf("%w: frozen panes are out of range", ErrInvalidArgument)
	}
	if len(opts.Columns) > MaxColumns {
		return fmt.Errorf("%w: more than %d column definitions", ErrInvalidArgument, MaxColumns)
	}
	for _, column := range opts.Columns {
		if !w.validStyle(column.Style) {
			return fmt.Errorf("%w: column style %d", ErrInvalidStyle, column.Style)
		}
	}
	return nil
}

func (w *Writer) validStyle(style StyleID) bool {
	return uint32(style) < uint32(len(w.styles.xfs))
}

func appendSheetHeader(x []byte, index int, o *SheetOptions) []byte {
	x = append(x, xmlDeclaration...)
	x = append(x, `<worksheet xmlns="`+nsMain+`" xmlns:r="`+nsRel+`">`...)
	if o.TabColor != 0 {
		x = append(x, `<sheetPr><tabColor rgb="`...)
		x = appendARGB(x, o.TabColor)
		x = append(x, `"/></sheetPr>`...)
	}
	x = append(x, `<sheetViews><sheetView workbookViewId="0"`...)
	if index == 0 {
		x = append(x, ` tabSelected="1"`...)
	}
	if o.HideGridLines {
		x = append(x, ` showGridLines="0"`...)
	}
	if o.FrozenRows > 0 || o.FrozenColumns > 0 {
		pane := "topRight"
		switch {
		case o.FrozenRows > 0 && o.FrozenColumns > 0:
			pane = "bottomRight"
		case o.FrozenRows > 0:
			pane = "bottomLeft"
		}
		x = append(x, "><pane"...)
		if o.FrozenColumns > 0 {
			x = append(x, ` xSplit="`...)
			x = appendUint(x, uint64(o.FrozenColumns))
			x = append(x, '"')
		}
		if o.FrozenRows > 0 {
			x = append(x, ` ySplit="`...)
			x = appendUint(x, uint64(o.FrozenRows))
			x = append(x, '"')
		}
		x = append(x, ` topLeftCell="`...)
		x = appendCellRef(x, uint32(o.FrozenRows+1), uint32(o.FrozenColumns+1))
		x = append(x, `" activePane="`...)
		x = append(x, pane...)
		x = append(x, `" state="frozen"/><selection pane="`...)
		x = append(x, pane...)
		x = append(x, `"/></sheetView></sheetViews>`...)
	} else {
		x = append(x, "/></sheetViews>"...)
	}
	x = append(x, `<sheetFormatPr defaultRowHeight="15"/>`...)

	wroteCols := false
	for i, column := range o.Columns {
		if column == (Column{}) {
			continue
		}
		if !wroteCols {
			x = append(x, "<cols>"...)
			wroteCols = true
		}
		x = append(x, `<col min="`...)
		x = appendUint(x, uint64(i+1))
		x = append(x, `" max="`...)
		x = appendUint(x, uint64(i+1))
		x = append(x, `" width="`...)
		if column.Width > 0 {
			x = appendFloat(x, min(column.Width, 255), 64)
			x = append(x, `" customWidth="1"`...)
		} else {
			x = append(x, `9.140625"`...)
		}
		if column.Style != 0 {
			x = append(x, ` style="`...)
			x = appendUint(x, uint64(column.Style))
			x = append(x, '"')
		}
		if column.Hidden {
			x = append(x, ` hidden="1"`...)
		}
		x = append(x, "/>"...)
	}
	if wroteCols {
		x = append(x, "</cols>"...)
	}
	return append(x, "<sheetData>"...)
}

func (w *Writer) completeSheet() {
	if w.current == nil {
		return
	}
	if w.rowOpen {
		w.closeRow()
	}
	x := append(w.xml, "</sheetData>"...)
	if f := w.sheets[len(w.sheets)-1].autoFilter; f != nil {
		x = append(x, `<autoFilter ref="`...)
		x = appendRange(x, *f)
		x = append(x, `"/>`...)
	}
	if len(w.merges) > 0 {
		x = append(x, `<mergeCells count="`...)
		x = appendUint(x, uint64(len(w.merges)))
		x = append(x, `">`...)
		for _, merge := range w.merges {
			x = append(x, `<mergeCell ref="`...)
			x = appendRange(x, merge)
			x = append(x, `"/>`...)
		}
		x = append(x, "</mergeCells>"...)
	}
	w.xml = append(x, `<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/></worksheet>`...)
	w.compress()
	w.zip.finishEntry()
	w.current = nil
}

func (w *Writer) writePart(name string, build func(x []byte) []byte) {
	w.xml = build(w.xml[:0])
	w.zip.startEntry(name)
	w.compress()
	w.zip.finishEntry()
}

// compress moves the pending XML into the ZIP entry and hands full output to the destination.
func (w *Writer) compress() {
	if len(w.xml) > 0 {
		w.zip.write(w.xml)
		w.xml = w.xml[:0]
	}
	if len(w.zip.out) >= w.cfg.flushThreshold {
		w.drain()
	}
}

func (w *Writer) drain() {
	if len(w.zip.out) == 0 {
		return
	}
	if w.err == nil {
		if _, err := w.dst.Write(w.zip.out); err != nil {
			w.err = err
		}
	}
	w.zip.out = w.zip.out[:0]
}

func (w *Writer) fail(err error) {
	if w.err == nil {
		w.err = err
	}
}

// Err returns the first error that stopped the Writer, if any.
func (w *Writer) Err() error {
	return w.err
}

// Flush writes everything produced so far to the destination, including rows still held in
// the compressor. Calling it often costs compression ratio; it is not needed for normal use.
func (w *Writer) Flush() error {
	if w.closed {
		return w.closeErr()
	}
	if w.current != nil {
		if len(w.xml) > 0 {
			w.zip.write(w.xml)
			w.xml = w.xml[:0]
		}
		w.zip.flush()
	}
	w.drain()
	return w.err
}

func (w *Writer) closeErr() error {
	if w.err != nil {
		return w.err
	}
	return ErrClosed
}

// Close completes the workbook and writes the remaining bytes to the destination. It does not
// close the destination. A workbook without worksheets gets an empty "Sheet1". After an error
// the output is incomplete and must be discarded.
func (w *Writer) Close() error {
	if w.closed {
		return w.err
	}
	defer func() {
		w.closed = true
		w.current = nil
		w.zip.release()
	}()
	if w.err != nil {
		return w.err
	}
	if len(w.sheets) == 0 {
		if _, err := w.AddSheet("Sheet1"); err != nil {
			return err
		}
	}
	w.completeSheet()
	visible := false
	for _, sheet := range w.sheets {
		visible = visible || !sheet.hidden
	}
	if !visible {
		w.err = ErrNoVisibleSheet
		return w.err
	}

	shared := w.sst != nil && len(w.sst.strings) > 0
	if shared {
		w.zip.startEntry("xl/sharedStrings.xml")
		w.xml = w.sst.appendHeader(w.xml[:0])
		for _, value := range w.sst.strings {
			w.xml = append(w.xml, "<si>"...)
			w.xml = appendTextElement(w.xml, value)
			w.xml = append(w.xml, "</si>"...)
			if len(w.xml) >= xmlBufferSize {
				w.compress()
			}
		}
		w.xml = append(w.xml, "</sst>"...)
		w.compress()
		w.zip.finishEntry()
	}
	w.writePart("xl/styles.xml", w.styles.appendXML)
	w.writePart("xl/workbook.xml", func(x []byte) []byte {
		return appendWorkbook(x, w.sheets, w.usesFormulas, w.cfg.date1904)
	})
	w.writePart("xl/_rels/workbook.xml.rels", func(x []byte) []byte {
		return appendWorkbookRels(x, len(w.sheets), shared)
	})
	w.writePart("[Content_Types].xml", func(x []byte) []byte {
		return appendContentTypes(x, len(w.sheets), shared)
	})
	w.writePart("_rels/.rels", appendRootRels)
	w.zip.finish()
	w.drain()
	return w.err
}
