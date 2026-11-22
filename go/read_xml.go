package hypaxel

import (
	"archive/zip"
	"bytes"
	"compress/flate"
	"errors"
	"fmt"
	"io"
	"strconv"
	"unicode/utf8"
)

// The reader's XML parser is a byte-level pull parser for the subset of XML that spreadsheet
// packages use: elements, attributes, text, CDATA, comments and processing instructions.
// Namespace prefixes are dropped and local names compared. Document type declarations are
// rejected, so entity expansion is impossible, and every token must fit into a bounded buffer.

const xmlInitialBuffer = 64 << 10

type xmlEvent uint8

const (
	xmlEOF xmlEvent = iota
	xmlStart
	xmlEmpty
	xmlEnd
	xmlText
	xmlCData
)

var (
	seqPIEnd      = []byte("?>")
	seqCommentEnd = []byte("-->")
	seqCDataEnd   = []byte("]]>")
)

type xmlReader struct {
	src     io.Reader
	part    string
	buf     []byte
	pos     int
	end     int
	eof     bool
	started bool
	depth   int
	// maxToken bounds a single tag or run of text, and so the buffer.
	maxToken int
	// The current token, valid until the next call: the local name of a tag, the raw
	// attributes of a start tag, or raw text.
	name  []byte
	attrs []byte
	text  []byte
}

func newXMLReader(src io.Reader, part string) *xmlReader {
	return &xmlReader{src: src, part: part, buf: make([]byte, xmlInitialBuffer), maxToken: defaultMaxTokenBytes}
}

func partError(part string, kind error, format string, args ...any) error {
	return &ReadError{Part: part, Message: fmt.Sprintf(format, args...), kind: kind}
}

func (x *xmlReader) malformed(format string, args ...any) error {
	return partError(x.part, ErrMalformed, format, args...)
}

// sourceError classifies an error from the decompressed ZIP entry: damage is reported as
// ErrMalformed naming the part, anything else (such as an I/O error) is returned unchanged.
func sourceError(part string, err error) error {
	var corrupt flate.CorruptInputError
	if errors.Is(err, zip.ErrChecksum) || errors.Is(err, zip.ErrFormat) || errors.Is(err, io.ErrUnexpectedEOF) || errors.As(err, &corrupt) {
		return &ReadError{Part: part, Message: "the ZIP entry is damaged (" + err.Error() + ")", kind: ErrMalformed, cause: err}
	}
	return err
}

// fill reads more input, keeping pos..end. It returns false at the end of the input.
func (x *xmlReader) fill() (bool, error) {
	if x.eof {
		return false, nil
	}
	if x.pos > 0 {
		x.end = copy(x.buf, x.buf[x.pos:x.end])
		x.pos = 0
	}
	if x.end == len(x.buf) {
		if len(x.buf) >= x.maxToken {
			return false, partError(x.part, ErrLimitExceeded, "an XML token is larger than %d bytes (see WithMaxTokenBytes)", x.maxToken)
		}
		grown := make([]byte, min(2*len(x.buf), x.maxToken))
		copy(grown, x.buf[:x.end])
		x.buf = grown
	}
	for range 100 {
		n, err := x.src.Read(x.buf[x.end:])
		x.end += n
		if err == io.EOF {
			x.eof = true
			return n > 0, nil
		}
		if err != nil {
			return false, sourceError(x.part, err)
		}
		if n > 0 {
			return true, nil
		}
	}
	return false, io.ErrNoProgress
}

func (x *xmlReader) need(n int) (bool, error) {
	for x.end-x.pos < n {
		if ok, err := x.fill(); !ok {
			return false, err
		}
	}
	return true, nil
}

// indexByte is bytes.IndexByte for the short distances between XML markup, where a plain
// loop beats the vectorized search's setup cost.
func indexByte(b []byte, c byte) int {
	const short = 32
	n := min(len(b), short)
	for i := range n {
		if b[i] == c {
			return i
		}
	}
	if len(b) > short {
		if i := bytes.IndexByte(b[short:], c); i >= 0 {
			return short + i
		}
	}
	return -1
}

// findByte returns the offset from pos of the first c at or after pos+from, reading more input
// as needed, or -1 at the end of the input.
func (x *xmlReader) findByte(c byte, from int) (int, error) {
	for {
		if from < x.end-x.pos {
			if i := indexByte(x.buf[x.pos+from:x.end], c); i >= 0 {
				return from + i, nil
			}
			from = x.end - x.pos
		}
		if ok, err := x.fill(); !ok {
			return -1, err
		}
	}
}

func (x *xmlReader) findSeq(seq []byte, from int) (int, error) {
	for {
		hay := x.buf[x.pos:x.end]
		if from < len(hay) {
			if i := bytes.Index(hay[from:], seq); i >= 0 {
				return from + i, nil
			}
		}
		from = max(from, len(hay)-len(seq)+1)
		if ok, err := x.fill(); !ok {
			return -1, err
		}
	}
}

// findTagEnd returns the offset of the '>' closing the tag at pos, skipping '>' inside quoted
// attribute values.
func (x *xmlReader) findTagEnd() (int, error) {
	at := 1
	var quote byte
	for {
		hay := x.buf[x.pos:x.end]
		for ; at < len(hay); at++ {
			b := hay[at]
			if quote != 0 {
				if b == quote {
					quote = 0
				}
			} else if b == '>' {
				return at, nil
			} else if b == '"' || b == '\'' {
				quote = b
			}
		}
		if ok, err := x.fill(); !ok {
			return -1, err
		}
	}
}

func (x *xmlReader) unexpectedEOF() error {
	return x.malformed("unexpected end of XML")
}

// checkEncoding skips a UTF-8 byte order mark and rejects UTF-16, which spreadsheet producers
// do not use.
func (x *xmlReader) checkEncoding() error {
	x.started = true
	if _, err := x.need(3); err != nil {
		return err
	}
	head := x.buf[x.pos:x.end]
	switch {
	case bytes.HasPrefix(head, []byte{0xEF, 0xBB, 0xBF}):
		x.pos += 3
	case bytes.HasPrefix(head, []byte{0xFF, 0xFE}), bytes.HasPrefix(head, []byte{0xFE, 0xFF}):
		return partError(x.part, ErrUnsupported, "the part is encoded as UTF-16")
	}
	return nil
}

func localName(name []byte) []byte {
	if i := bytes.LastIndexByte(name, ':'); i >= 0 {
		return name[i+1:]
	}
	return name
}

func trimSpaceRight(s []byte) []byte {
	n := len(s)
	for n > 0 && isXMLSpace(s[n-1]) {
		n--
	}
	return s[:n]
}

func trimSpace(s []byte) []byte {
	i := 0
	for i < len(s) && isXMLSpace(s[i]) {
		i++
	}
	return trimSpaceRight(s[i:])
}

func (x *xmlReader) next() (xmlEvent, error) {
	if !x.started {
		if err := x.checkEncoding(); err != nil {
			return xmlEOF, err
		}
	}
	for {
		if x.pos == x.end {
			ok, err := x.fill()
			if err != nil {
				return xmlEOF, err
			}
			if !ok {
				if x.depth > 0 {
					return xmlEOF, x.unexpectedEOF()
				}
				return xmlEOF, nil
			}
		}

		if x.buf[x.pos] != '<' {
			n, err := x.findByte('<', 0)
			if err != nil {
				return xmlEOF, err
			}
			if n < 0 {
				n = x.end - x.pos
			}
			x.text = x.buf[x.pos : x.pos+n]
			x.pos += n
			return xmlText, nil
		}

		if ok, err := x.need(2); !ok {
			if err != nil {
				return xmlEOF, err
			}
			return xmlEOF, x.unexpectedEOF()
		}
		switch x.buf[x.pos+1] {
		case '/':
			end, err := x.findByte('>', 2)
			if err != nil {
				return xmlEOF, err
			}
			if end < 0 {
				return xmlEOF, x.unexpectedEOF()
			}
			begin := x.pos
			x.pos += end + 1
			if x.depth == 0 {
				return xmlEOF, x.malformed("unbalanced end tag")
			}
			x.depth--
			x.name = localName(trimSpaceRight(x.buf[begin+2 : begin+end]))
			return xmlEnd, nil
		case '?':
			end, err := x.findSeq(seqPIEnd, 2)
			if err != nil {
				return xmlEOF, err
			}
			if end < 0 {
				return xmlEOF, x.unexpectedEOF()
			}
			x.pos += end + 2
		case '!':
			if _, err := x.need(9); err != nil {
				return xmlEOF, err
			}
			rest := x.buf[x.pos:x.end]
			switch {
			case bytes.HasPrefix(rest, []byte("<!--")):
				end, err := x.findSeq(seqCommentEnd, 4)
				if err != nil {
					return xmlEOF, err
				}
				if end < 0 {
					return xmlEOF, x.unexpectedEOF()
				}
				x.pos += end + 3
			case bytes.HasPrefix(rest, []byte("<![CDATA[")):
				end, err := x.findSeq(seqCDataEnd, 9)
				if err != nil {
					return xmlEOF, err
				}
				if end < 0 {
					return xmlEOF, x.unexpectedEOF()
				}
				begin := x.pos
				x.pos += end + 3
				x.text = x.buf[begin+9 : begin+end]
				return xmlCData, nil
			case bytes.HasPrefix(rest, []byte("<!DOCTYPE")):
				return xmlEOF, x.malformed("document type declarations are not allowed")
			default:
				return xmlEOF, x.malformed("unexpected markup declaration")
			}
		default:
			end, err := x.findTagEnd()
			if err != nil {
				return xmlEOF, err
			}
			if end < 0 {
				return xmlEOF, x.unexpectedEOF()
			}
			begin := x.pos
			x.pos += end + 1
			inner := x.buf[begin+1 : begin+end]
			empty := len(inner) > 0 && inner[len(inner)-1] == '/'
			if empty {
				inner = inner[:len(inner)-1]
			}
			nameEnd := 0
			for nameEnd < len(inner) && !isXMLSpace(inner[nameEnd]) {
				nameEnd++
			}
			if nameEnd == 0 {
				return xmlEOF, x.malformed("an element has no name")
			}
			x.name = localName(inner[:nameEnd])
			x.attrs = inner[nameEnd:]
			if empty {
				return xmlEmpty, nil
			}
			x.depth++
			return xmlStart, nil
		}
	}
}

// rawText handles an element whose start tag was just read: if its content is plain text
// followed by its end tag, it consumes both and returns the undecoded text. Otherwise it
// consumes nothing and returns false.
func (x *xmlReader) rawText() ([]byte, bool, error) {
	lt, err := x.findByte('<', 0)
	if lt < 0 || err != nil {
		return nil, false, err
	}
	if ok, err := x.need(lt + 2); !ok || x.buf[x.pos+lt+1] != '/' {
		return nil, false, err
	}
	gt, err := x.findByte('>', lt+2)
	if gt < 0 || err != nil {
		return nil, false, err
	}
	text := x.buf[x.pos : x.pos+lt]
	x.pos += gt + 1
	x.depth--
	return text, true, nil
}

// consume skips lit if the buffered input starts with it. Tags matched this way must be
// balanced by the caller adjusting depth. It is a fast path only: on a buffer boundary it
// declines, and the caller falls back to next.
func (x *xmlReader) consume(lit string) bool {
	if x.end-x.pos < len(lit) || string(x.buf[x.pos:x.pos+len(lit)]) != lit {
		return false
	}
	x.pos += len(lit)
	return true
}

// scanSharedString matches a whole plain item, <si><t>text</t></si> or with attributes on <t>,
// in the buffered input and returns its raw text. It consumes nothing and returns false for
// any other shape or an item that is not completely buffered.
func (x *xmlReader) scanSharedString() ([]byte, bool) {
	b := x.buf[x.pos:x.end]
	if len(b) < 7 || string(b[:6]) != "<si><t" {
		return nil, false
	}
	j := 6
	switch b[j] {
	case '>':
	case ' ':
		var quote byte
		for ; ; j++ {
			if j >= len(b) {
				return nil, false
			}
			c := b[j]
			if quote != 0 {
				if c == quote {
					quote = 0
				}
			} else if c == '>' {
				break
			} else if c == '"' || c == '\'' {
				quote = c
			}
		}
		if b[j-1] == '/' {
			return nil, false
		}
	default:
		return nil, false
	}
	start := j + 1
	end := indexByte(b[start:], '<')
	if end < 0 {
		return nil, false
	}
	end += start
	const closing = "</t></si>"
	if len(b)-end < len(closing) || string(b[end:end+len(closing)]) != closing {
		return nil, false
	}
	x.pos += end + len(closing)
	return b[start:end], true
}

// skipElement skips the rest of the element whose start tag was just read.
func (x *xmlReader) skipElement() error {
	target := x.depth - 1
	for {
		ev, err := x.next()
		if err != nil {
			return err
		}
		if ev == xmlEnd && x.depth == target {
			return nil
		}
		if ev == xmlEOF {
			return x.unexpectedEOF()
		}
	}
}

// readText appends the decoded text of the element whose start tag was just read, including
// text in nested elements, and consumes its end tag.
func (x *xmlReader) readText(dst []byte, excelEscapes bool) ([]byte, error) {
	if raw, ok, err := x.rawText(); err != nil {
		return dst, err
	} else if ok {
		var msg string
		if dst, msg = decodeText(dst, raw, true, excelEscapes); msg != "" {
			return dst, x.malformed("%s", msg)
		}
		return dst, nil
	}
	target := x.depth - 1
	for {
		ev, err := x.next()
		if err != nil {
			return dst, err
		}
		switch ev {
		case xmlText, xmlCData:
			var msg string
			dst, msg = decodeText(dst, x.text, ev == xmlText, excelEscapes)
			if msg != "" {
				return dst, x.malformed("%s", msg)
			}
		case xmlEnd:
			if x.depth == target {
				return dst, nil
			}
		case xmlEOF:
			return dst, x.unexpectedEOF()
		}
	}
}

// nextAttr splits the first attribute off raw start-tag attributes.
func nextAttr(s []byte) (name, value, rest []byte, ok bool) {
	i := 0
	for i < len(s) && isXMLSpace(s[i]) {
		i++
	}
	eq := bytes.IndexByte(s[i:], '=')
	if eq < 0 {
		return nil, nil, nil, false
	}
	eq += i
	name = trimSpaceRight(s[i:eq])
	j := eq + 1
	for j < len(s) && isXMLSpace(s[j]) {
		j++
	}
	if j >= len(s) || (s[j] != '"' && s[j] != '\'') {
		return nil, nil, nil, false
	}
	end := bytes.IndexByte(s[j+1:], s[j])
	if end < 0 {
		return nil, nil, nil, false
	}
	return name, s[j+1 : j+1+end], s[j+2+end:], true
}

// attrIter yields (local name, raw value) pairs, skipping namespace declarations.
type attrIter struct{ rest []byte }

func (a *attrIter) next() (name, value []byte, ok bool) {
	for {
		var n []byte
		n, value, a.rest, ok = nextAttr(a.rest)
		if !ok {
			return nil, nil, false
		}
		if string(n) != "xmlns" && !bytes.HasPrefix(n, []byte("xmlns:")) {
			return localName(n), value, true
		}
	}
}

func attr(attrs []byte, name string) ([]byte, bool) {
	it := attrIter{attrs}
	for {
		n, v, ok := it.next()
		if !ok {
			return nil, false
		}
		if string(n) == name {
			return v, true
		}
	}
}

func decodedAttr(attrs []byte, name string) (string, bool) {
	raw, ok := attr(attrs, name)
	if !ok {
		return "", false
	}
	out, msg := decodeText(nil, raw, true, false)
	return string(out), msg == ""
}

// decodeText appends raw XML character data to dst. With entities, entity and character
// references are resolved and line endings normalized (text content); without, the bytes are
// taken as they are (CDATA). With excelEscapes, Excel's _xHHHH_ escapes are decoded as well. A
// non-empty message reports invalid input; dst is then returned unchanged.
func decodeText(dst, raw []byte, entities, excelEscapes bool) ([]byte, string) {
	if !utf8.Valid(raw) {
		return dst, "text is not valid UTF-8"
	}
	escapes := excelEscapes && bytes.Index(raw, []byte("_x")) >= 0
	if !escapes && (!entities || bytes.IndexByte(raw, '&') < 0 && bytes.IndexByte(raw, '\r') < 0) {
		return append(dst, raw...), ""
	}
	start := len(dst)
	out := dst
	if entities {
		var msg string
		if out, msg = decodeEntities(out, raw); msg != "" {
			return dst[:start], msg
		}
	} else {
		out = append(out, raw...)
	}
	if escapes {
		out = decodeExcelEscapes(out, start)
	}
	return out, ""
}

func decodeEntities(out, raw []byte) ([]byte, string) {
	for len(raw) > 0 {
		i := bytes.IndexAny(raw, "&\r")
		if i < 0 {
			return append(out, raw...), ""
		}
		out = append(out, raw[:i]...)
		raw = raw[i:]
		if raw[0] == '\r' {
			// XML end-of-line handling: CR LF and a lone CR become LF.
			out = append(out, '\n')
			if len(raw) > 1 && raw[1] == '\n' {
				raw = raw[2:]
			} else {
				raw = raw[1:]
			}
			continue
		}
		semi := bytes.IndexByte(raw, ';')
		if semi < 0 || semi > 12 {
			return out, "unterminated entity reference"
		}
		switch entity := raw[1:semi]; string(entity) {
		case "lt":
			out = append(out, '<')
		case "gt":
			out = append(out, '>')
		case "amp":
			out = append(out, '&')
		case "quot":
			out = append(out, '"')
		case "apos":
			out = append(out, '\'')
		default:
			code, ok := uint64(0), false
			if len(entity) > 2 && entity[0] == '#' && (entity[1] == 'x' || entity[1] == 'X') {
				code, ok = parseRadix(entity[2:], 16)
			} else if len(entity) > 1 && entity[0] == '#' {
				code, ok = parseRadix(entity[1:], 10)
			}
			if !ok || code > utf8.MaxRune || !utf8.ValidRune(rune(code)) {
				return out, "unknown entity reference"
			}
			out = utf8.AppendRune(out, rune(code))
		}
		raw = raw[semi+1:]
	}
	return out, ""
}

func parseRadix(digits []byte, base int) (uint64, bool) {
	if len(digits) == 0 || len(digits) > 8 {
		return 0, false
	}
	for _, c := range digits {
		if !isHexDigit(c) || base == 10 && c > '9' {
			return 0, false
		}
	}
	// 21 bits hold utf8.MaxRune, so the result always converts to a rune.
	n, err := strconv.ParseUint(string(digits), base, 21)
	return n, err == nil
}

func isHexDigit(c byte) bool {
	return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
}

func excelEscapeAt(b []byte, i int) (uint64, bool) {
	if i+7 > len(b) || b[i] != '_' || b[i+1] != 'x' || b[i+6] != '_' {
		return 0, false
	}
	return parseRadix(b[i+2:i+6], 16)
}

// decodeExcelEscapes decodes _xHHHH_ escapes in b[start:] in place. Unpaired surrogates are
// left as written.
func decodeExcelEscapes(b []byte, start int) []byte {
	w := start
	for i := start; i < len(b); {
		if code, ok := excelEscapeAt(b, i); ok {
			j := i + 7
			r := rune(code)
			if code >= 0xD800 && code < 0xDC00 {
				if low, ok := excelEscapeAt(b, j); ok && low >= 0xDC00 && low < 0xE000 {
					r = rune(0x10000 + (code-0xD800)<<10 + (low - 0xDC00))
					j += 7
				}
			}
			if utf8.ValidRune(r) {
				// An escape is 7 bytes and a rune at most 4, so writing never overtakes reading.
				w += utf8.EncodeRune(b[w:], r)
				i = j
				continue
			}
		}
		b[w] = b[i]
		w++
		i++
	}
	return b[:w]
}
