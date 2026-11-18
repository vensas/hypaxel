package hypaxel

import (
	"math"
	"strings"
	"testing"
)

func TestAppendEscaped(t *testing.T) {
	for in, want := range map[string]string{
		"":                             "",
		"plain ünïcødé 😀":              "plain ünïcødé 😀",
		`a&b<c>d"e'f`:                  `a&amp;b&lt;c&gt;d&quot;e'f`,
		"tab\tnl\ncr\r":                "tab\tnl\ncr\r",
		"\x01x\x1f":                    "_x0001_x_x001F_",
		"a_x0041_b snake_case":         "a_x005F_x0041_b snake_case",
		"_x00zz_ _x004":                "_x00zz_ _x004",
		"￾￿！":                          "_xFFFE__xFFFF_！",
		"genuine � stays":              "genuine � stays",
		"bad \xff byte":                "bad � byte",
		"lone surrogate \xed\xa0\x80.": "lone surrogate ���.",
		"truncated \xe2\x82":           "truncated ��",
		"é<":                           "é&lt;",
	} {
		if got := string(appendEscaped(nil, in)); got != want {
			t.Errorf("appendEscaped(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestAppendTextElement(t *testing.T) {
	for in, want := range map[string]string{
		"":       "<t></t>",
		"a b":    "<t>a b</t>",
		" a":     `<t xml:space="preserve"> a</t>`,
		"a\n":    "<t xml:space=\"preserve\">a\n</t>",
		"\ta<b>": "<t xml:space=\"preserve\">\ta&lt;b&gt;</t>",
	} {
		if got := string(appendTextElement(nil, in)); got != want {
			t.Errorf("appendTextElement(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestAppendFloat(t *testing.T) {
	for _, tc := range []struct {
		v       float64
		bitSize int
		want    string
	}{
		{1, 64, "1"},
		{-0.1, 64, "-0.1"},
		{1e300, 64, "1e+300"},
		{math.Copysign(0, -1), 64, "0"},
		{1 << 53, 64, "9.007199254740992e+15"},
		{-(1<<53 - 1), 64, "-9007199254740991"},
		{0.30000000000000004, 64, "0.30000000000000004"},
		{float64(float32(0.1)), 32, "0.1"},
		{float64(float32(0.1)), 64, "0.10000000149011612"},
		{1.5e-7, 64, "1.5e-07"},
	} {
		if got := string(appendFloat(nil, tc.v, tc.bitSize)); got != tc.want {
			t.Errorf("appendFloat(%v, %d) = %q, want %q", tc.v, tc.bitSize, got, tc.want)
		}
	}
}

func TestAppendSerial(t *testing.T) {
	const noon = 12 * 3600 * 1_000_000_000
	for _, tc := range []struct {
		day   int64
		nanos uint64
		want  string
	}{
		{45292, 0, "45292"},
		{45292, noon, "45292.5"},
		{45292, nanosPerDay - 1, "45293"},
		{45292, 60 * 1_000_000_000, "45292.0006944444"},
		{0, 1_000_000, "0.0000000116"},
	} {
		if got := string(appendSerial(nil, tc.day, tc.nanos)); got != tc.want {
			t.Errorf("appendSerial(%d, %d) = %q, want %q", tc.day, tc.nanos, got, tc.want)
		}
	}
}

func TestReferences(t *testing.T) {
	for column, want := range map[int]string{1: "A", 26: "Z", 27: "AA", 702: "ZZ", 703: "AAA", MaxColumns: "XFD", 0: "", MaxColumns + 1: "", -1: ""} {
		if got := ColumnName(column); got != want {
			t.Errorf("ColumnName(%d) = %q, want %q", column, got, want)
		}
	}
	if got := CellRef(MaxRows, MaxColumns); got != "XFD1048576" {
		t.Errorf("CellRef(max) = %q", got)
	}
	for _, bad := range [][2]int{{0, 1}, {1, 0}, {MaxRows + 1, 1}, {1, MaxColumns + 1}} {
		if got := CellRef(bad[0], bad[1]); got != "" {
			t.Errorf("CellRef(%d, %d) = %q, want empty", bad[0], bad[1], got)
		}
	}
}

func TestUTF16Len(t *testing.T) {
	for in, want := range map[string]int{"": 0, "abc": 3, "é": 1, "😀": 2, strings.Repeat("😀", 10): 20} {
		if got := utf16Len(in); got != want {
			t.Errorf("utf16Len(%q) = %d, want %d", in, got, want)
		}
	}
}
