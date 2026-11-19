package hypaxel

import (
	"bytes"
	"errors"
	"io"
	"runtime"
	"strings"
	"testing"
	"time"
)

// settles waits until the number of goroutines drops back to want, running the garbage
// collector so that cleanups of unreachable Rows run.
func settles(t *testing.T, want int) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for runtime.NumGoroutine() > want {
		if time.Now().After(deadline) {
			buf := make([]byte, 1<<16)
			t.Fatalf("%d goroutines, want %d:\n%s", runtime.NumGoroutine(), want, buf[:runtime.Stack(buf, true)])
		}
		runtime.GC()
		time.Sleep(time.Millisecond)
	}
}

func bigBook(t *testing.T) []byte {
	return writeBook(t, func(w *Writer) {
		s := mustSheet(t, w, "S")
		for i := range 50_000 {
			s.WriteInt(int64(i))
			s.WriteString("text that spans many 64 KiB chunks once decompressed")
			noErr(t, s.EndRow())
		}
	}, WithSharedStrings(true))
}

func TestConcurrentInflateLeavesNoGoroutines(t *testing.T) {
	if runtime.GOMAXPROCS(0) == 1 {
		t.Skip("concurrent inflate is off with GOMAXPROCS=1")
	}
	data := bigBook(t)
	base := runtime.NumGoroutine()
	r := openBytes(t, data)

	rows, err := r.Rows("S")
	noErr(t, err)
	if runtime.NumGoroutine() <= base {
		t.Fatal("no inflate goroutine was started")
	}
	for range 3 {
		mustNext(t, rows)
	}
	noErr(t, rows.Close())
	noErr(t, rows.Close())
	if n := runtime.NumGoroutine(); n != base {
		t.Errorf("%d goroutines after Close, want %d", n, base)
	}
	if rows.Next() {
		t.Error("Next after Close")
	}

	rows, err = r.Rows("S")
	noErr(t, err)
	for row, err := range rows.All() {
		noErr(t, err)
		if row.Index() == 10 {
			break
		}
	}
	settles(t, base)

	rows, err = r.Rows("S")
	noErr(t, err)
	n := 0
	for rows.Next() {
		n++
	}
	noErr(t, rows.Err())
	if n != 50_000 {
		t.Errorf("%d rows", n)
	}
	settles(t, base)

	func() {
		rows, err := r.Rows("S")
		noErr(t, err)
		mustNext(t, rows)
		// Abandoned without Close: the cleanup stops the goroutine once rows is unreachable.
	}()
	settles(t, base)
}

func TestConcurrentInflateIsOffWithOneProcessor(t *testing.T) {
	data := bigBook(t)
	defer runtime.GOMAXPROCS(runtime.GOMAXPROCS(1))
	base := runtime.NumGoroutine()
	rows, err := openBytes(t, data).Rows("S")
	noErr(t, err)
	mustNext(t, rows)
	if n := runtime.NumGoroutine(); n != base {
		t.Errorf("%d goroutines, want %d", n, base)
	}
	rows.Close()
}

func TestBothInflateModesReadTheSameRows(t *testing.T) {
	data := bigBook(t)
	var dumps [2]string
	for i, concurrent := range []bool{false, true} {
		rows, err := openBytes(t, data, WithConcurrentInflate(concurrent)).Rows("S")
		noErr(t, err)
		var b strings.Builder
		for row, err := range rows.All() {
			noErr(t, err)
			for _, v := range row.AppendValues(nil) {
				b.WriteString(v.String())
				b.WriteByte('|')
			}
		}
		dumps[i] = b.String()
	}
	if dumps[0] != dumps[1] || len(dumps[0]) < 1<<20 {
		t.Errorf("modes differ or too little data: %d and %d bytes", len(dumps[0]), len(dumps[1]))
	}
}

type scriptedReader struct {
	data    []byte
	err     error
	closed  bool
	block   chan struct{}
	entered chan struct{}
}

func (s *scriptedReader) Read(p []byte) (int, error) {
	if s.block != nil {
		if s.entered != nil {
			close(s.entered)
			s.entered = nil
		}
		<-s.block
	}
	if len(s.data) == 0 {
		return 0, s.err
	}
	n := copy(p, s.data[:min(len(s.data), 1000)])
	s.data = s.data[n:]
	return n, nil
}

func (s *scriptedReader) Close() error {
	s.closed = true
	return nil
}

func TestPipeReaderDeliversDataThenTheError(t *testing.T) {
	want := bytes.Repeat([]byte("0123456789"), 30_000)
	failure := errors.New("boom")
	src := &scriptedReader{data: bytes.Clone(want), err: failure}
	p := newPipeReader(src)
	got, err := io.ReadAll(p)
	if !errors.Is(err, failure) || !bytes.Equal(got, want) {
		t.Errorf("%d bytes, %v", len(got), err)
	}
	if _, err := p.Read(make([]byte, 10)); !errors.Is(err, failure) {
		t.Errorf("error is not sticky: %v", err)
	}
	noErr(t, p.Close())
	if !src.closed {
		t.Error("the source was not closed")
	}

	p = newPipeReader(&scriptedReader{})
	if _, err := io.ReadAll(p); !errors.Is(err, io.ErrNoProgress) {
		t.Errorf("a reader without progress: %v", err)
	}
	p.Close()
}

func TestPipeReaderCloseWaitsForTheSource(t *testing.T) {
	entered := make(chan struct{})
	src := &scriptedReader{data: make([]byte, 1<<20), err: io.EOF, block: make(chan struct{}), entered: entered}
	p := newPipeReader(src)
	<-entered
	closed := make(chan struct{})
	go func() {
		p.Close()
		close(closed)
	}()
	select {
	case <-closed:
		t.Fatal("Close returned while the source was still being read")
	case <-time.After(20 * time.Millisecond):
	}
	close(src.block)
	<-closed
	if !src.closed {
		t.Error("the source was not closed")
	}
}
