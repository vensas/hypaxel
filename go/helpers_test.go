package hypaxel

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"errors"
	"io"
	"strings"
	"testing"
)

// book writes a workbook, checks that every part is well-formed XML and returns the parts.
func book(t *testing.T, write func(w *Writer), opts ...Option) map[string]string {
	t.Helper()
	var buf bytes.Buffer
	w := NewWriter(&buf, opts...)
	write(w)
	if err := w.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	return unzip(t, buf.Bytes())
}

func unzip(t *testing.T, data []byte) map[string]string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("not a readable ZIP archive: %v", err)
	}
	parts := map[string]string{}
	for _, file := range zr.File {
		if file.Flags&0x8 == 0 || file.Method != zip.Deflate {
			t.Errorf("%s: flags %#x, method %d; want a deflated entry with a data descriptor", file.Name, file.Flags, file.Method)
		}
		rc, err := file.Open()
		if err != nil {
			t.Fatalf("%s: %v", file.Name, err)
		}
		content, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("%s: %v", file.Name, err)
		}
		decoder := xml.NewDecoder(bytes.NewReader(content))
		for {
			if _, err := decoder.Token(); err == io.EOF {
				break
			} else if err != nil {
				t.Fatalf("%s is not well-formed XML: %v", file.Name, err)
			}
		}
		parts[file.Name] = string(content)
	}
	return parts
}

// sheetData returns what lies between <sheetData> and </sheetData> of a worksheet part.
func sheetData(t *testing.T, parts map[string]string, index int) string {
	t.Helper()
	name := "xl/worksheets/sheet" + string(rune('0'+index)) + ".xml"
	content, ok := parts[name]
	if !ok {
		t.Fatalf("no part %s", name)
	}
	_, rest, _ := strings.Cut(content, "<sheetData>")
	data, _, found := strings.Cut(rest, "</sheetData>")
	if !found {
		t.Fatalf("%s has no sheetData: %s", name, content)
	}
	return data
}

func mustSheet(t *testing.T, w *Writer, name string) *Sheet {
	t.Helper()
	s, err := w.AddSheet(name)
	if err != nil {
		t.Fatalf("AddSheet(%q): %v", name, err)
	}
	return s
}

func wantErr(t *testing.T, err, target error) {
	t.Helper()
	if !errors.Is(err, target) {
		t.Fatalf("got error %v, want %v", err, target)
	}
}

func noErr(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func contains(t *testing.T, haystack string, needles ...string) {
	t.Helper()
	for _, needle := range needles {
		if !strings.Contains(haystack, needle) {
			t.Errorf("missing %s in:\n%s", needle, haystack)
		}
	}
}
