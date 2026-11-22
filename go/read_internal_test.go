package hypaxel

import (
	"errors"
	"fmt"
	"math"
	"reflect"
	"strings"
	"testing"
	"testing/iotest"
	"time"
)

func tokens(t *testing.T, xml string, bufSize int) ([]string, error) {
	t.Helper()
	x := newXMLReader(iotest.OneByteReader(strings.NewReader(xml)), "test.xml")
	x.buf = make([]byte, bufSize)
	var out []string
	for {
		ev, err := x.next()
		if err != nil {
			return out, err
		}
		attrs := ""
		for it := (attrIter{x.attrs}); ; {
			n, v, ok := it.next()
			if !ok {
				break
			}
			attrs += " " + string(n) + "=" + string(v)
		}
		switch ev {
		case xmlEOF:
			return out, nil
		case xmlStart:
			out = append(out, "<"+string(x.name)+attrs+">")
		case xmlEmpty:
			out = append(out, "<"+string(x.name)+attrs+"/>")
		case xmlEnd:
			out = append(out, "</"+string(x.name)+">")
		case xmlText:
			out = append(out, "T:"+string(x.text))
		case xmlCData:
			out = append(out, "C:"+string(x.text))
		}
	}
}

func TestXMLTokenizesAcrossSmallBuffers(t *testing.T) {
	xml := "<?xml version=\"1.0\"?>\n<x:root xmlns:x=\"urn:a\" x:id=\"7\"><!-- a > comment --><c r='A1' t = \"a>b\">1 &amp; 2</c><e/><![CDATA[<raw>]]></x:root>"
	want := []string{"T:\n", "<root id=7>", "<c r=A1 t=a>b>", "T:1 &amp; 2", "</c>", "<e/>", "C:<raw>", "</root>"}
	for _, size := range []int{8, 9, 16, 64 << 10} {
		got, err := tokens(t, xml, size)
		if err != nil || !reflect.DeepEqual(got, want) {
			t.Errorf("buffer %d: %q, %v", size, got, err)
		}
	}
}

func TestXMLHandlesByteOrderMarksAndRejectsUTF16(t *testing.T) {
	got, err := tokens(t, "\xEF\xBB\xBF<a/>", 64)
	if err != nil || !reflect.DeepEqual(got, []string{"<a/>"}) {
		t.Errorf("BOM: %q %v", got, err)
	}
	_, err = tokens(t, "\xFF\xFE<\x00a\x00/\x00>\x00", 64)
	wantErr(t, err, ErrUnsupported)
}

func TestXMLRejectsMalformedInput(t *testing.T) {
	for _, xml := range []string{
		`<!DOCTYPE x [<!ENTITY a "b">]><x/>`,
		`<a><b>`,
		`<a attr="never closed`,
		`</a>`,
		`<a></a></b>`,
		`< a/>`,
		`<!ELEMENT a ANY>`,
		`<a><!-- never closed`,
		`<a><![CDATA[never closed`,
		`<?pi never closed`,
		`<`,
	} {
		if _, err := tokens(t, xml, 16); !errors.Is(err, ErrMalformed) {
			t.Errorf("%s: %v", xml, err)
		}
	}
}

func TestXMLBoundsTokens(t *testing.T) {
	x := newXMLReader(strings.NewReader("<a>"+strings.Repeat("x", defaultMaxTokenBytes+1)+"</a>"), "big.xml")
	if _, err := x.next(); err != nil {
		t.Fatal(err)
	}
	_, err := x.next()
	wantErr(t, err, ErrLimitExceeded)
}

func TestDecodesEntitiesAndExcelEscapes(t *testing.T) {
	out, msg := decodeText([]byte("pre:"), []byte("a &lt;&gt;&amp;&quot;&apos; &#65;&#x42; _x000D__x005F_x0041_ _xD83D__xDE00_ _xD83D_ snake_case\r\nx\ry"), true, true)
	if want := "pre:a <>&\"' AB \r_x0041_ \U0001F600 _xD83D_ snake_case\nx\ny"; string(out) != want || msg != "" {
		t.Errorf("got %q %q, want %q", out, msg, want)
	}
	if out, _ := decodeText(nil, []byte("_x0041_"), true, false); string(out) != "_x0041_" {
		t.Errorf("escapes decoded without being asked: %q", out)
	}
	if out, _ := decodeText(nil, []byte("&amp;_x0041_"), false, true); string(out) != "&amp;A" {
		t.Errorf("CDATA: %q", out)
	}
	for _, bad := range []string{"&bogus;", "&#xD800;", "&#x110000;", "&#x200000;", "&#xFFFFFFFF;", "&#;", "& no end", "&#99999999999;", "\xff&amp;"} {
		out, msg := decodeText([]byte("keep"), []byte(bad), true, false)
		if msg == "" || string(out) != "keep" {
			t.Errorf("%q: %q %q", bad, out, msg)
		}
	}
}

func TestClassifiesFormatCodes(t *testing.T) {
	for code, want := range map[string]DateKind{
		"yyyy-mm-dd":              DateKindDate,
		"d/m/yy":                  DateKindDate,
		"mmm yyyy":                DateKindDate,
		"[$-409]mmmm d, yyyy;@":   DateKindDate,
		"yyyy-mm-dd hh:mm:ss":     DateKindDateTime,
		"m/d/yy h:mm AM/PM":       DateKindDateTime,
		"hh:mm":                   DateKindTime,
		"h:mm:ss AM/PM":           DateKindTime,
		"mm:ss.0":                 DateKindTime,
		"[h]:mm:ss":               DateKindDuration,
		"[mm]:ss":                 DateKindDuration,
		"General":                 DateKindNone,
		"0.00":                    DateKindNone,
		`#,##0.00 "days"`:         DateKindNone,
		"[Red]#,##0;[Blue]-#,##0": DateKindNone,
		`_(* #,##0_);_(* (#,##0);_(* "-"??_);_(@_)`: DateKindNone,
		"0.00E+00":      DateKindNone,
		"@":             DateKindNone,
		`"Date:" @`:     DateKindNone,
		`0 \d`:          DateKindNone,
		"General;yyyy":  DateKindNone,
		"[":             DateKindNone,
		`"unterminated`: DateKindNone,
	} {
		if got := formatDateKind(code); got != want {
			t.Errorf("%s: got %v, want %v", code, got, want)
		}
	}
	for id, want := range map[uint32]DateKind{0: DateKindNone, 4: DateKindNone, 14: DateKindDate, 22: DateKindDateTime, 20: DateKindTime, 46: DateKindDuration, 58: DateKindDate, 164: DateKindNone} {
		if got := builtinDateKind(id); got != want {
			t.Errorf("built-in %d: got %v, want %v", id, got, want)
		}
	}
}

func TestParsesCellFormats(t *testing.T) {
	xml := `<styleSheet xmlns="x"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy&quot;-&quot;mm"/><numFmt numFmtId="165" formatCode="0.0"/></numFmts>
		<fonts><font><sz val="11"/></font></fonts>
		<cellStyleXfs count="1"><xf numFmtId="14"/></cellStyleXfs>
		<cellXfs count="5"><xf numFmtId="0"/><xf numFmtId="164" applyNumberFormat="1"/><xf numFmtId="165"/><xf numFmtId="22"><alignment/></xf><xf numFmtId="46"/></cellXfs></styleSheet>`
	kinds, err := parseStyles(newXMLReader(strings.NewReader(xml), "styles.xml"))
	noErr(t, err)
	if want := []DateKind{DateKindNone, DateKindDate, DateKindNone, DateKindDateTime, DateKindDuration}; !reflect.DeepEqual(kinds, want) {
		t.Errorf("got %v, want %v", kinds, want)
	}
}

func TestParsesSharedStrings(t *testing.T) {
	xml := `<?xml version="1.0"?><sst xmlns="x" count="4" uniqueCount="4">
		<si><t>plain</t></si>
		<si><r><rPr><b/></rPr><t xml:space="preserve">rich </t></r><r><t>text</t></r></si>
		<si><t>東京</t><rPh sb="0" eb="2"><t>トウキョウ</t></rPh><phoneticPr fontId="1"/></si>
		<si/>
		<si><t>a &amp; b_x000A_c</t></si></sst>`
	sst, err := parseSharedStrings(newXMLReader(strings.NewReader(xml), "sst.xml"), math.MaxInt64)
	noErr(t, err)
	var got []string
	for i := range sst.len() {
		got = append(got, sst.get(uint32(i)))
	}
	if want := []string{"plain", "rich text", "東京", "", "a & b\nc"}; !reflect.DeepEqual(got, want) {
		t.Errorf("got %q, want %q", got, want)
	}

	_, err = parseSharedStrings(newXMLReader(strings.NewReader("<sst><si><t>0123456789</t></si><si><t>0123456789</t></si></sst>"), "sst.xml"), 15)
	wantErr(t, err, ErrLimitExceeded)
	_, err = parseSharedStrings(newXMLReader(strings.NewReader("<sst><si><t>0123456789</t><t>0123456789</t></si></sst>"), "sst.xml"), 15)
	wantErr(t, err, ErrLimitExceeded)
}

func dateValue(serial float64, kind DateKind, date1904 bool) Value {
	return Value{kind: ValueDateTime, num: serial, date: kind, date1904: date1904}
}

func TestConvertsSerialsLikeExcel(t *testing.T) {
	for _, tc := range []struct {
		serial float64
		kind   DateKind
		want   string
	}{
		{1, DateKindDate, "1900-01-01"},
		{59, DateKindDate, "1900-02-28"},
		{60, DateKindDate, "60"},
		{61, DateKindDate, "1900-03-01"},
		{45_413, DateKindDate, "2024-05-01"},
		{45_413.75, DateKindDateTime, "2024-05-01T18:00:00"},
		{45_413.5 + 0.5/86_400, DateKindDateTime, "2024-05-01T12:00:00.500"},
		{45_413.999_999_999, DateKindDateTime, "2024-05-02T00:00:00"},
		{2_958_465, DateKindDate, "9999-12-31"},
		{0.25, DateKindTime, "06:00:00"},
		{1.25, DateKindTime, "1900-01-01T06:00:00"},
		{1.5, DateKindDuration, "1.5"},
		{-1, DateKindDate, "-1"},
		{math.Inf(1), DateKindDate, "+Inf"},
	} {
		if got := dateValue(tc.serial, tc.kind, false).String(); got != tc.want {
			t.Errorf("%v %v: got %s, want %s", tc.serial, tc.kind, got, tc.want)
		}
	}
	if got := dateValue(0, DateKindDate, true).String(); got != "1904-01-01" {
		t.Errorf("1904 serial 0: %s", got)
	}
	if _, ok := dateValue(60, DateKindDate, false).Time(); ok {
		t.Error("1900-02-29 has a time")
	}
	if got, ok := dateValue(0.25, DateKindTime, false).Time(); !ok || got != time.Date(1899, 12, 31, 6, 0, 0, 0, time.UTC) {
		t.Errorf("time of day: %v", got)
	}
	if d, ok := dateValue(-1.5, DateKindDuration, false).Duration(); !ok || d != -36*time.Hour {
		t.Errorf("negative duration: %v", d)
	}
	if _, ok := dateValue(200_000, DateKindDuration, false).Duration(); ok {
		t.Error("a duration beyond time.Duration")
	}
	if _, ok := (Value{kind: ValueNumber, num: 1}).Time(); ok {
		t.Error("a plain number has a time")
	}
}

func TestParsesISODatesConsistentlyWithSerials(t *testing.T) {
	for _, tc := range []struct {
		serial float64
		kind   DateKind
	}{{1, DateKindDate}, {59, DateKindDate}, {61, DateKindDate}, {45_413.75, DateKindDateTime}} {
		text := dateValue(tc.serial, tc.kind, false).String()
		serial, kind, ok := isoToSerial(text, false)
		if !ok || serial != tc.serial || kind != tc.kind {
			t.Errorf("%s: got %v %v %v", text, serial, kind, ok)
		}
	}
	for text, want := range map[string]float64{"06:00:00": 0.25, "06:00": 0.25, "2024-05-01T08:30:00Z": 45_413 + 8.5/24} {
		if serial, _, ok := isoToSerial(text, false); !ok || math.Abs(serial-want) > 1e-12 {
			t.Errorf("%s: %v", text, serial)
		}
	}
	if serial, _, _ := isoToSerial("1904-01-02", true); serial != 1 {
		t.Errorf("1904: %v", serial)
	}
	for _, bad := range []string{"not a date", "2024-13-01", "2024-01", "2024-01-01T", "12", "2024-01-01Tx:00", "NaN:00"} {
		if _, _, ok := isoToSerial(bad, false); ok {
			t.Errorf("%s parsed", bad)
		}
	}
}

func TestParsesNumbersReferencesAndPaths(t *testing.T) {
	for raw, want := range map[string]float64{"42": 42, "-123456789012345": -123_456_789_012_345, "12345678901234567890": 1.2345678901234567e19, "3.25E-2": 0.0325, "+1": 1, ".5": 0.5} {
		if got, ok := parseNumber([]byte(raw)); !ok || got != want {
			t.Errorf("%s: %v %v", raw, got, ok)
		}
	}
	for _, bad := range []string{"-", "NaN", "inf", "Infinity", "0x10", "1_000", "1e999", "1,5", ""} {
		if _, ok := parseNumber([]byte(bad)); ok {
			t.Errorf("%q parsed", bad)
		}
	}
	for raw, want := range map[string]uint32{"A1": 1, "z9": 26, "AA10": 27, "XFD1048576": 16_384, "ABCD1": 0, "12": 0, "": 0} {
		if got := parseColumn([]byte(raw)); got != want {
			t.Errorf("column %s: %d", raw, got)
		}
	}
	if n, ok := parseDigits([]byte("4294967295")); !ok || n != math.MaxUint32 {
		t.Error("max uint32")
	}
	for _, bad := range []string{"4294967296", "", "-1", "12345678901"} {
		if _, ok := parseDigits([]byte(bad)); ok {
			t.Errorf("%q parsed", bad)
		}
	}
	for _, tc := range [][3]string{
		{"xl/", "worksheets/sheet1.xml", "xl/worksheets/sheet1.xml"},
		{"xl/", "/xl/worksheets/sheet1.xml", "xl/worksheets/sheet1.xml"},
		{"xl/", "../customXml/item1.xml", "customXml/item1.xml"},
		{"xl/", "../../../x.xml", "x.xml"},
		{"", "xl/workbook.xml", "xl/workbook.xml"},
		{"xl/", `sub\.\a.xml`, "xl/sub/a.xml"},
	} {
		if got := resolvePart(tc[0], tc[1]); got != tc[2] {
			t.Errorf("resolve(%q, %q) = %q", tc[0], tc[1], got)
		}
	}
	if got := relsPath("xl/workbook.xml"); got != "xl/_rels/workbook.xml.rels" {
		t.Error(got)
	}
	if got := relsPath("workbook.xml"); got != "_rels/workbook.xml.rels" {
		t.Error(got)
	}
}

func TestValueAccessorsAndKinds(t *testing.T) {
	var empty Value
	if !empty.IsEmpty() || empty.String() != "" || empty.Kind() != ValueEmpty {
		t.Error("zero Value")
	}
	if _, ok := empty.Float(); ok {
		t.Error("empty Float")
	}
	if _, ok := (Value{kind: ValueString, text: "x"}).Bool(); ok {
		t.Error("string Bool")
	}
	if got := (Value{kind: ValueNumber, num: 1e21}).String(); got != "1000000000000000000000" {
		t.Error(got)
	}
	var names []string
	for k := ValueEmpty; k <= ValueError+1; k++ {
		names = append(names, k.String())
	}
	for k := DateKindNone; k <= DateKindDuration+1; k++ {
		names = append(names, k.String())
	}
	if got := fmt.Sprint(names); got != "[empty string number bool datetime error ValueKind(6) none date datetime time duration DateKind(5)]" {
		t.Error(got)
	}
}

func TestSharedStringFastPathMatchesTheGeneralParser(t *testing.T) {
	xml := `<sst><si><t>plain</t></si><si><t xml:space="preserve"> padded </t></si><si><t a='x>y'>quoted</t></si>` +
		`<si><t/></si><si><t></t></si><si><t>a &amp; b_x000A_c&#x1F600;</t></si><si><t>line1` + "\r\n" + `line2</t></si>` +
		`<si><t>run</t><t>s</t></si><si><r><t>rich</t></r></si><si><t>東京</t><rPh><t>トウキョウ</t></rPh></si>` +
		`<si><tx>not t</tx><t>after</t></si><si><t><![CDATA[<cdata>]]></t></si><si/></sst>`
	want := []string{"plain", " padded ", "quoted", "", "", "a & b\nc\U0001F600", "line1\nline2", "runs", "rich", "東京", "after", "<cdata>", ""}
	for _, size := range []int{8, 13, 32, 64 << 10} {
		x := newXMLReader(iotest.OneByteReader(strings.NewReader(xml)), "sst.xml")
		x.buf = make([]byte, size)
		sst, err := parseSharedStrings(x, math.MaxInt64)
		noErr(t, err)
		var got []string
		for i := range sst.len() {
			got = append(got, sst.get(uint32(i)))
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("buffer %d:\n got %q\nwant %q", size, got, want)
		}
	}
	x := newXMLReader(strings.NewReader("<sst><si><t>\xff</t></si></sst>"), "sst.xml")
	if _, err := parseSharedStrings(x, math.MaxInt64); !errors.Is(err, ErrMalformed) {
		t.Errorf("invalid UTF-8: %v", err)
	}
	x = newXMLReader(strings.NewReader(strings.Repeat("<si><t>0123456789</t></si>", 3)), "sst.xml")
	if _, err := parseSharedStrings(x, 15); !errors.Is(err, ErrLimitExceeded) {
		t.Errorf("limit: %v", err)
	}
}
