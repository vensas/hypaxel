package tests

import (
	"io"
	"iter"
	"slices"
	"strconv"
	"testing"

	hypaxel "github.com/vensas/hypaxel/go"
)

type counter struct{ n int64 }

func (c *counter) Write(p []byte) (int, error) {
	c.n += int64(len(p))
	return len(p), nil
}

var cases = []struct {
	name    string
	maxRows int
	run     func(dst io.Writer, rows iter.Seq[Order]) error
}{
	{"HypaxelTyped", 0, func(dst io.Writer, rows iter.Seq[Order]) error { return HypaxelTyped(dst, rows) }},
	{"HypaxelWriteRow", 0, HypaxelRow},
	{"HypaxelStructs", 0, HypaxelStructs},
	{"HypaxelSharedStrings", 0, func(dst io.Writer, rows iter.Seq[Order]) error {
		return HypaxelTyped(dst, rows, hypaxel.WithSharedStrings(true))
	}},
	{"HypaxelLevel6", 0, func(dst io.Writer, rows iter.Seq[Order]) error {
		return HypaxelTyped(dst, rows, hypaxel.WithCompressionLevel(6))
	}},
	{"HypaxelLevel0", 0, func(dst io.Writer, rows iter.Seq[Order]) error {
		return HypaxelTyped(dst, rows, hypaxel.WithCompressionLevel(0))
	}},
	{"ExcelizeStreamWriter", 0, ExcelizeStream},
	{"ExcelizeInMemory", 100_000, ExcelizeInMemory},
}

// BenchmarkWrite writes the shared 10-column workload to a discarding writer.
//
//	go test -bench Write -benchmem                      # 100,000 rows
//	go test -bench Write -benchmem -rows 1000000 -benchtime 1x
func BenchmarkWrite(b *testing.B) {
	data := Data(*rowCount)
	rows := slices.Values(data)
	for _, c := range cases {
		if c.maxRows > 0 && len(data) > c.maxRows {
			continue
		}
		b.Run(c.name+"/rows="+strconv.Itoa(len(data)), func(b *testing.B) {
			b.ReportAllocs()
			var out counter
			for b.Loop() {
				out.n = 0
				if err := c.run(&out, rows); err != nil {
					b.Fatal(err)
				}
			}
			b.ReportMetric(float64(out.n)/(1<<20), "MB-file")
		})
	}
}
