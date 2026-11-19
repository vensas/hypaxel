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
	{"HypaxelAutoFit", 0, HypaxelAutoFit},
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

// BenchmarkRead reads the workload, written by Hypaxel with cell references, with every reader
// and checks that each returns the same values.
//
//	go test -bench Read -benchmem                      # 100,000 rows
//	go test -bench Read -benchmem -rows 1000000 -benchtime 3x
func BenchmarkRead(b *testing.B) {
	dir := b.TempDir()
	for _, mode := range []struct {
		name string
		opts []hypaxel.Option
	}{{"inline", nil}, {"shared", []hypaxel.Option{hypaxel.WithSharedStrings(true)}}} {
		path, err := WriteReadInput(dir, *rowCount, mode.opts...)
		if err != nil {
			b.Fatal(err)
		}
		want, err := HypaxelRead(path)
		if err != nil {
			b.Fatal(err)
		}
		for _, c := range ReadCases {
			if c.MaxRows > 0 && *rowCount > c.MaxRows {
				continue
			}
			b.Run(c.Name+"/"+mode.name+"/rows="+strconv.Itoa(*rowCount), func(b *testing.B) {
				b.ReportAllocs()
				for b.Loop() {
					got, err := c.Run(path)
					if err != nil {
						b.Fatal(err)
					}
					if !c.Matches(got, want) {
						b.Fatalf("got %v, want %v", got, want)
					}
				}
			})
		}
	}
}
