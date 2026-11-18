// Command bench compares Hypaxel with excelize on the workload shared by all Hypaxel
// implementations. Each case runs in its own process and generates its rows on the fly, so
// the peak resident memory it reports belongs to that case alone. The time includes
// generating the rows; BenchmarkWrite measures writing only.
//
//	go run ./cmd/bench            # 100,000 rows
//	go run ./cmd/bench 1000000    # 1,000,000 rows (skips the in-memory excelize case)
package main

import (
	"fmt"
	"io"
	"iter"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"syscall"
	"time"

	hypaxel "github.com/vensas/hypaxel/go"
	"github.com/vensas/hypaxel/go/tests"
)

type benchCase struct {
	name    string
	maxRows int
	run     func(dst io.Writer, rows iter.Seq[tests.Order]) error
}

var cases = []benchCase{
	{"Hypaxel, typed cells", 0, func(dst io.Writer, rows iter.Seq[tests.Order]) error { return tests.HypaxelTyped(dst, rows) }},
	{"Hypaxel, WriteRow", 0, tests.HypaxelRow},
	{"Hypaxel, struct mapping", 0, tests.HypaxelStructs},
	{"Hypaxel, shared strings", 0, func(dst io.Writer, rows iter.Seq[tests.Order]) error {
		return tests.HypaxelTyped(dst, rows, hypaxel.WithSharedStrings(true))
	}},
	{"excelize, StreamWriter", 0, tests.ExcelizeStream},
	{"excelize, in-memory", 200_000, tests.ExcelizeInMemory},
}

type counter struct{ n int64 }

func (c *counter) Write(p []byte) (int, error) {
	c.n += int64(len(p))
	return len(p), nil
}

func peakRSS() float64 {
	var usage syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &usage); err != nil {
		return 0
	}
	peak := float64(usage.Maxrss)
	if runtime.GOOS != "darwin" {
		// Linux reports kilobytes, macOS bytes.
		peak *= 1024
	}
	return peak / (1 << 20)
}

func child(index, count int) error {
	var out counter
	started := time.Now()
	if err := cases[index].run(&out, tests.Generate(count)); err != nil {
		return err
	}
	elapsed := time.Since(started)
	fmt.Printf("| %s | %.0f ms | %.0f MB | %.1f MB |\n",
		cases[index].name, float64(elapsed.Microseconds())/1000, peakRSS(), float64(out.n)/(1<<20))
	return nil
}

func run() error {
	if len(os.Args) == 4 && os.Args[1] == "--case" {
		index, err := strconv.Atoi(os.Args[2])
		if err != nil || index < 0 || index >= len(cases) {
			return fmt.Errorf("unknown case %q", os.Args[2])
		}
		count, err := strconv.Atoi(os.Args[3])
		if err != nil {
			return err
		}
		return child(index, count)
	}

	count := 100_000
	if len(os.Args) > 1 {
		var err error
		if count, err = strconv.Atoi(os.Args[1]); err != nil || count < 1 {
			return fmt.Errorf("usage: bench [rows]")
		}
	}
	self, err := os.Executable()
	if err != nil {
		return err
	}
	fmt.Printf("%d rows x 10 columns, %s, %s/%s\n\n", count, runtime.Version(), runtime.GOOS, runtime.GOARCH)
	fmt.Println("| Case | Time | Peak RSS | File |")
	fmt.Println("| --- | ---: | ---: | ---: |")
	for i, c := range cases {
		if c.maxRows > 0 && count > c.maxRows {
			fmt.Printf("| %s | not run | | |\n", c.name)
			continue
		}
		cmd := exec.Command(self, "--case", strconv.Itoa(i), strconv.Itoa(count))
		cmd.Stdout, cmd.Stderr = os.Stdout, os.Stderr
		if err := cmd.Run(); err != nil {
			return fmt.Errorf("%s: %w", c.name, err)
		}
	}
	return nil
}

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
