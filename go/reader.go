package hypaxel

import (
	"archive/zip"
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"runtime"
	"slices"
	"strings"
	"sync"
)

const defaultMaxSharedStringsBytes = 1 << 30

var oleMagic = []byte{0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1}

type readConfig struct {
	maxSharedStringsBytes int64
	pipeline              bool
}

// ReadOption configures a [Reader].
type ReadOption func(*readConfig)

// WithMaxSharedStringsBytes caps the text of the shared string table, the one part of a
// workbook that is held in memory while rows are read. The default is 1 GiB. Lower it when
// reading untrusted uploads.
func WithMaxSharedStringsBytes(n int64) ReadOption {
	return func(c *readConfig) { c.maxSharedStringsBytes = max(n, 0) }
}

// WithConcurrentInflate sets whether a worksheet and the shared string table are decompressed
// on a separate goroutine while their XML is parsed. It is on by default, which roughly halves
// the time to read a sheet when a spare CPU core is available, for about 4% more CPU time. It is
// always off when GOMAXPROCS is 1. Turn it off for CPU-bound batch work that already keeps
// every core busy.
func WithConcurrentInflate(enabled bool) ReadOption {
	return func(c *readConfig) { c.pipeline = enabled }
}

// Visibility is whether a sheet is shown in Excel's tab bar.
type Visibility uint8

const (
	// SheetVisible is shown in the tab bar.
	SheetVisible Visibility = iota
	// SheetHidden is hidden; the user can unhide it.
	SheetHidden
	// SheetVeryHidden is hidden; only VBA can unhide it.
	SheetVeryHidden
)

// SheetKind is what a sheet contains.
type SheetKind uint8

const (
	// SheetKindWorksheet is a grid of cells.
	SheetKindWorksheet SheetKind = iota
	// SheetKindChartsheet only holds a chart; it has no rows.
	SheetKindChartsheet
	// SheetKindOther is a dialog sheet or an Excel 4 macro sheet.
	SheetKindOther
)

// SheetInfo describes a sheet listed in the workbook.
type SheetInfo struct {
	Name       string
	Visibility Visibility
	Kind       SheetKind
	part       string
}

// Reader reads XLSX workbooks one row at a time. Create it with [OpenReader] or [NewReader].
//
// Opening a Reader parses the package structure, the sheet list and the cell formats. Rows are
// then streamed: memory use is bounded by the shared string table, which is loaded when the
// first sheet is opened, plus the current row, whatever the number of rows.
//
// A Reader is safe for concurrent use: several sheets, or the same sheet several times, can be
// read at once, for example from different goroutines.
type Reader struct {
	files    map[string]*zip.File
	folded   map[string]*zip.File
	closer   io.Closer
	sheets   []SheetInfo
	styles   []DateKind
	date1904 bool
	sst      func() (*stringTable, error)
	cfg      readConfig
}

// OpenReader opens the workbook file at path. Close the Reader to close the file.
func OpenReader(path string, opts ...ReadOption) (*Reader, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, err
	}
	r, err := NewReader(f, info.Size(), opts...)
	if err != nil {
		f.Close()
		return nil, err
	}
	r.closer = f
	return r, nil
}

// NewReader reads the workbook structure from src, which holds size bytes, such as an
// [os.File], a [bytes.Reader] or an [io.SectionReader].
func NewReader(src io.ReaderAt, size int64, opts ...ReadOption) (*Reader, error) {
	cfg := readConfig{maxSharedStringsBytes: defaultMaxSharedStringsBytes, pipeline: true}
	for _, opt := range opts {
		opt(&cfg)
	}
	if size >= int64(len(oleMagic)) {
		magic := make([]byte, len(oleMagic))
		if _, err := src.ReadAt(magic, 0); err != nil && err != io.EOF {
			return nil, err
		}
		if bytes.Equal(magic, oleMagic) {
			return nil, fmt.Errorf("%w: this is an OLE compound file: a legacy .xls workbook or a password-protected (encrypted) workbook", ErrInvalidFile)
		}
	}
	zr, err := zip.NewReader(src, size)
	if err != nil && !errors.Is(err, zip.ErrInsecurePath) {
		if errors.Is(err, zip.ErrFormat) || errors.Is(err, zip.ErrAlgorithm) || errors.Is(err, zip.ErrChecksum) || errors.Is(err, io.ErrUnexpectedEOF) {
			return nil, fmt.Errorf("%w: the input is not a valid ZIP archive (%v)", ErrInvalidFile, err)
		}
		return nil, err
	}
	if len(zr.File) == 0 {
		return nil, fmt.Errorf("%w: the ZIP archive has no entries", ErrInvalidFile)
	}

	r := &Reader{cfg: cfg, files: make(map[string]*zip.File, len(zr.File)), folded: make(map[string]*zip.File, len(zr.File))}
	for _, f := range zr.File {
		name := strings.TrimLeft(strings.ReplaceAll(f.Name, `\`, "/"), "/")
		if _, dup := r.files[name]; !dup {
			r.files[name] = f
		}
		if folded := strings.ToLower(name); r.folded[folded] == nil {
			r.folded[folded] = f
		}
	}

	workbookPart := "xl/workbook.xml"
	rootRels, err := r.relationships("_rels/.rels")
	if err != nil {
		return nil, err
	}
	for _, rel := range rootRels {
		if rel.is("officeDocument") {
			workbookPart = rel.target
			break
		}
	}
	if r.find(workbookPart) == nil {
		return nil, errNotWorkbook
	}
	rels, err := r.relationships(relsPath(workbookPart))
	if err != nil {
		return nil, err
	}
	if err := r.readWorkbook(workbookPart, rels); err != nil {
		return nil, err
	}

	var sstPart string
	for _, rel := range rels {
		switch {
		case rel.is("styles") && r.styles == nil && r.find(rel.target) != nil:
			x, rc, err := r.open(rel.target)
			if err != nil {
				return nil, err
			}
			r.styles, err = parseStyles(x)
			rc.Close()
			if err != nil {
				return nil, err
			}
		case rel.is("sharedStrings") && sstPart == "" && r.find(rel.target) != nil:
			sstPart = rel.target
		}
	}
	r.sst = sync.OnceValues(func() (*stringTable, error) {
		if sstPart == "" {
			return &stringTable{}, nil
		}
		x, rc, err := r.open(sstPart)
		if err != nil {
			return nil, err
		}
		rc = r.overlap(x, rc)
		defer rc.Close()
		return parseSharedStrings(x, cfg.maxSharedStringsBytes)
	})
	return r, nil
}

var errNotWorkbook = fmt.Errorf("%w: the package has no workbook part; it may be a Word or PowerPoint file", ErrInvalidFile)

// Close closes the file opened by [OpenReader]. For a Reader from [NewReader] it does nothing.
// Rows still being read fail after Close.
func (r *Reader) Close() error {
	if r.closer == nil {
		return nil
	}
	err := r.closer.Close()
	r.closer = nil
	return err
}

// Sheets returns the sheets in workbook order, including hidden sheets and chart sheets.
func (r *Reader) Sheets() []SheetInfo {
	return slices.Clone(r.sheets)
}

// SheetNames returns the sheet names in workbook order.
func (r *Reader) SheetNames() []string {
	names := make([]string, len(r.sheets))
	for i, s := range r.sheets {
		names[i] = s.Name
	}
	return names
}

// Date1904 reports whether the workbook uses the 1904 date system.
func (r *Reader) Date1904() bool {
	return r.date1904
}

// Rows streams the rows of the sheet with this name, matched exactly and then ignoring case.
func (r *Reader) Rows(name string) (*Rows, error) {
	for i, s := range r.sheets {
		if s.Name == name {
			return r.RowsAt(i)
		}
	}
	for i, s := range r.sheets {
		if strings.EqualFold(s.Name, name) {
			return r.RowsAt(i)
		}
	}
	return nil, fmt.Errorf("%w: %q", ErrSheetNotFound, name)
}

// RowsAt streams the rows of the sheet at a 0-based position in [Reader.Sheets].
func (r *Reader) RowsAt(index int) (*Rows, error) {
	if index < 0 || index >= len(r.sheets) {
		return nil, fmt.Errorf("%w: no sheet at index %d", ErrSheetNotFound, index)
	}
	info := r.sheets[index]
	if info.Kind == SheetKindChartsheet {
		return nil, partError(info.part, ErrUnsupported, "sheet %q is a chart sheet and has no cells", info.Name)
	}
	if info.part == "" {
		return nil, partError("xl/workbook.xml", ErrMalformed, "sheet %q has no relationship to a worksheet part", info.Name)
	}
	if r.find(info.part) == nil {
		return nil, partError(info.part, ErrMalformed, "the worksheet part of sheet %q is missing", info.Name)
	}
	sst, err := r.sst()
	if err != nil {
		return nil, err
	}
	x, rc, err := r.open(info.part)
	if err != nil {
		return nil, err
	}
	rc = r.overlap(x, rc)
	rows := &Rows{x: x, rc: rc, styles: r.styles, row: Row{sst: sst, date1904: r.date1904}}
	if p, ok := rc.(*pipeReader); ok {
		// Rows dropped without Close would otherwise leave the goroutine blocked forever.
		runtime.AddCleanup(rows, (*pipeReader).stop, p)
	}
	return rows, nil
}

// overlap moves decompression of an opened part to its own goroutine when that can help.
func (r *Reader) overlap(x *xmlReader, rc io.ReadCloser) io.ReadCloser {
	if !r.cfg.pipeline || runtime.GOMAXPROCS(0) == 1 {
		return rc
	}
	p := newPipeReader(rc)
	x.src = p
	return p
}

// find returns the ZIP entry of a part, ignoring a leading "/" and, as a fallback, case.
func (r *Reader) find(part string) *zip.File {
	part = strings.TrimLeft(part, "/")
	if f := r.files[part]; f != nil {
		return f
	}
	return r.folded[strings.ToLower(part)]
}

func (r *Reader) open(part string) (*xmlReader, io.ReadCloser, error) {
	f := r.find(part)
	if f == nil {
		return nil, nil, partError(part, ErrMalformed, "the part is missing")
	}
	if f.Flags&1 != 0 {
		return nil, nil, partError(part, ErrUnsupported, "the ZIP entry is encrypted")
	}
	if f.Method != zip.Store && f.Method != zip.Deflate {
		return nil, nil, partError(part, ErrUnsupported, "the ZIP entry uses compression method %d", f.Method)
	}
	rc, err := f.Open()
	if err != nil {
		return nil, nil, sourceError(part, err)
	}
	return newXMLReader(rc, part), rc, nil
}

type relationship struct {
	id, kind, target string
}

// is matches the last segment of the relationship type, so that both Transitional and Strict
// Office Open XML are accepted.
func (rel *relationship) is(kind string) bool {
	return rel.kind[strings.LastIndexByte(rel.kind, '/')+1:] == kind
}

func (r *Reader) relationships(part string) ([]relationship, error) {
	if r.find(part) == nil {
		return nil, nil
	}
	// Targets are relative to the source part, which lives one level above _rels/.
	base := part[:strings.LastIndexByte(part, '/')+1]
	base = strings.TrimSuffix(base, "_rels/")
	x, rc, err := r.open(part)
	if err != nil {
		return nil, err
	}
	defer rc.Close()
	var rels []relationship
	for {
		ev, err := x.next()
		if err != nil {
			return nil, err
		}
		if ev == xmlEOF {
			return rels, nil
		}
		if (ev != xmlStart && ev != xmlEmpty) || string(x.name) != "Relationship" {
			continue
		}
		if mode, _ := decodedAttr(x.attrs, "TargetMode"); strings.EqualFold(mode, "External") {
			continue
		}
		id, ok1 := decodedAttr(x.attrs, "Id")
		kind, ok2 := decodedAttr(x.attrs, "Type")
		target, ok3 := decodedAttr(x.attrs, "Target")
		if ok1 && ok2 && ok3 {
			rels = append(rels, relationship{id: id, kind: kind, target: resolvePart(base, target)})
		}
	}
}

func (r *Reader) readWorkbook(part string, rels []relationship) error {
	x, rc, err := r.open(part)
	if err != nil {
		return err
	}
	defer rc.Close()
	root := true
	for {
		ev, err := x.next()
		if err != nil {
			return err
		}
		if ev == xmlEOF {
			return nil
		}
		if ev != xmlStart && ev != xmlEmpty {
			continue
		}
		if root {
			root = false
			if string(x.name) != "workbook" {
				return errNotWorkbook
			}
		}
		switch string(x.name) {
		case "workbookPr":
			v, _ := attr(x.attrs, "date1904")
			r.date1904 = string(v) == "1" || string(v) == "true"
		case "sheet":
			name, ok := decodedAttr(x.attrs, "name")
			if !ok {
				return partError(part, ErrMalformed, "a sheet has no name")
			}
			info := SheetInfo{Name: name}
			switch state, _ := attr(x.attrs, "state"); string(state) {
			case "hidden":
				info.Visibility = SheetHidden
			case "veryHidden":
				info.Visibility = SheetVeryHidden
			}
			if id, ok := decodedAttr(x.attrs, "id"); ok {
				for i := range rels {
					if rels[i].id != id {
						continue
					}
					info.part = rels[i].target
					switch {
					case rels[i].is("worksheet"):
					case rels[i].is("chartsheet"):
						info.Kind = SheetKindChartsheet
					default:
						info.Kind = SheetKindOther
					}
					break
				}
			}
			r.sheets = append(r.sheets, info)
		}
	}
}

func relsPath(part string) string {
	i := strings.LastIndexByte(part, '/')
	return part[:i+1] + "_rels/" + part[i+1:] + ".rels"
}

// resolvePart resolves a relationship target against the source part's directory.
func resolvePart(base, target string) string {
	joined := base + target
	if strings.HasPrefix(target, "/") {
		joined = target
	}
	var parts []string
	for _, segment := range strings.FieldsFunc(joined, func(r rune) bool { return r == '/' || r == '\\' }) {
		switch segment {
		case ".":
		case "..":
			if len(parts) > 0 {
				parts = parts[:len(parts)-1]
			}
		default:
			parts = append(parts, segment)
		}
	}
	return strings.Join(parts, "/")
}
