package hypaxel

import (
	"database/sql"
	"database/sql/driver"
	"errors"
	"io"
	"net/netip"
	"strings"
	"testing"
	"time"
)

type status int

func (s status) String() string { return [...]string{"new", "done"}[s] }

type (
	label    string
	quantity int32
	count    uint16
	ratio32  float32
	ratio64  float64
	flag     bool
)

type failingValuer struct{}

func (failingValuer) Value() (driver.Value, error) { return nil, errors.New("no value") }

type nestedValuer struct{}

func (nestedValuer) Value() (driver.Value, error) { return nestedValuer{}, nil }

type failingText struct{}

func (failingText) MarshalText() ([]byte, error) { return nil, errors.New("no text") }

func TestWriteAny(t *testing.T) {
	text := "pointed"
	number := 7
	var nilString *string
	var nilTime *time.Time
	when := time.Date(2024, 1, 1, 12, 0, 0, 0, time.UTC)
	var bold StyleID
	parts := book(t, func(w *Writer) {
		bold = w.AddStyle(Style{Font: Font{Bold: true}})
		s := mustSheet(t, w, "S")
		noErr(t, s.WriteRow("s", 1, int8(-2), int16(-3), int32(-4), int64(-5), uint(6), uint8(7), uint16(8), uint32(9), uint64(10)))
		noErr(t, s.WriteRow(1.5, float32(0.1), true, []byte("bytes"), Formula("=A1"), nil, "after nil"))
		noErr(t, s.WriteRow(when, &when, nilTime, 90*time.Minute, &text, &number, nilString, "end"))
		noErr(t, s.WriteRow(status(1), label("named"), quantity(-9), count(9), ratio32(0.25), ratio64(2.5), flag(true)))
		noErr(t, s.WriteRow(sql.NullString{String: "valid", Valid: true}, sql.NullString{}, sql.NullInt64{Int64: 5, Valid: true},
			sql.NullFloat64{Float64: 1.25, Valid: true}, sql.NullBool{Bool: true, Valid: true}, sql.NullTime{Time: when, Valid: true},
			netip.MustParseAddr("10.0.0.1")))
		noErr(t, s.WriteRowStyled(bold, "bold", Styled("plain", DefaultStyle), Styled(nil, bold), nil, 2))
		s.Write("single")
		s.Write(3, bold)
		s.Write(Styled(Styled(4.5, bold), DefaultStyle))
		noErr(t, s.EndRow())
		noErr(t, s.WriteStrings([]string{"h1", "h2"}, bold))
		noErr(t, s.WriteStrings(nil))
		noErr(t, s.WriteRow())
		noErr(t, s.WriteRow("row 11"))
	})
	str := func(v string) string { return `<c t="inlineStr"><is><t>` + v + `</t></is></c>` }
	num := func(v string) string { return `<c><v>` + v + `</v></c>` }
	want := `<row r="1">` + str("s") + num("1") + num("-2") + num("-3") + num("-4") + num("-5") + num("6") + num("7") + num("8") + num("9") + num("10") + `</row>` +
		`<row r="2">` + num("1.5") + num("0.1") + `<c t="b"><v>1</v></c>` + str("bytes") + `<c><f>A1</f></c>` + `<c r="G2" t="inlineStr"><is><t>after nil</t></is></c></row>` +
		`<row r="3"><c s="2"><v>45292.5</v></c><c s="2"><v>45292.5</v></c><c r="D3" s="3"><v>0.0625</v></c>` + str("pointed") + num("7") + `<c r="H3" t="inlineStr"><is><t>end</t></is></c></row>` +
		`<row r="4">` + str("done") + str("named") + num("-9") + num("9") + num("0.25") + num("2.5") + `<c t="b"><v>1</v></c></row>` +
		`<row r="5">` + str("valid") + `<c r="C5"><v>5</v></c>` + num("1.25") + `<c t="b"><v>1</v></c><c s="2"><v>45292.5</v></c>` + str("10.0.0.1") + `</row>` +
		`<row r="6"><c s="1" t="inlineStr"><is><t>bold</t></is></c>` + str("plain") + `<c s="1"/><c s="1"/><c s="1"><v>2</v></c></row>` +
		`<row r="7">` + str("single") + `<c s="1"><v>3</v></c><c s="1"><v>4.5</v></c></row>` +
		`<row r="8"><c s="1" t="inlineStr"><is><t>h1</t></is></c><c s="1" t="inlineStr"><is><t>h2</t></is></c></row>` +
		`<row r="9"></row><row r="10"></row><row r="11">` + str("row 11") + `</row>`
	if got := sheetData(t, parts, 1); got != want {
		t.Errorf("\n got %s\nwant %s", strings.ReplaceAll(got, "</row>", "</row>\n"), strings.ReplaceAll(want, "</row>", "</row>\n"))
	}
}

func TestWriteAnyErrors(t *testing.T) {
	for name, value := range map[string]any{
		"struct":        struct{ A int }{1},
		"slice":         []int{1},
		"map":           map[string]int{},
		"func":          func() {},
		"nested valuer": nestedValuer{},
	} {
		w := NewWriter(io.Discard)
		s := mustSheet(t, w, "S")
		s.WriteInt(1)
		err := s.WriteRow("ok", value)
		wantErr(t, err, ErrUnsupported)
		if !strings.Contains(err.Error(), "at row 2, column 2") {
			t.Errorf("%s: error lacks the position: %v", name, err)
		}
	}
	for _, value := range []any{failingValuer{}, failingText{}} {
		w := NewWriter(io.Discard)
		err := mustSheet(t, w, "S").WriteRow(value)
		if err == nil || !strings.Contains(err.Error(), "no ") || !strings.Contains(err.Error(), "row 1, column 1") {
			t.Errorf("%T: error = %v", value, err)
		}
	}
}
