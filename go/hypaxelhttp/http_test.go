package hypaxelhttp

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	hypaxel "github.com/vensas/hypaxel/go"
)

func get(t *testing.T, handler http.Handler) (*http.Response, []byte, error) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	response, err := http.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	return response, body, err
}

func writeRows(count int) func(*http.Request, *hypaxel.Writer) error {
	return func(_ *http.Request, w *hypaxel.Writer) error {
		sheet, err := w.AddSheet("Data")
		if err != nil {
			return err
		}
		for i := range count {
			if err := sheet.WriteRow(i, "streamed row"); err != nil {
				return err
			}
		}
		return nil
	}
}

func TestHandlerStreamsWorkbook(t *testing.T) {
	response, body, err := get(t, Handler("Bericht 2024", writeRows(50_000), hypaxel.WithFlushThreshold(4096)))
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusOK {
		t.Errorf("status = %d", response.StatusCode)
	}
	if got := response.Header.Get("Content-Type"); got != hypaxel.ContentType {
		t.Errorf("Content-Type = %q", got)
	}
	if got := response.Header.Get("Content-Disposition"); got != `attachment; filename="Bericht 2024.xlsx"` {
		t.Errorf("Content-Disposition = %q", got)
	}
	if response.ContentLength != -1 {
		t.Errorf("Content-Length = %d, want a streamed response", response.ContentLength)
	}
	archive, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	if err != nil {
		t.Fatalf("the response is not a workbook: %v", err)
	}
	if len(archive.File) != 6 {
		t.Errorf("%d parts", len(archive.File))
	}
}

type flushRecorder struct {
	*httptest.ResponseRecorder
	flushes int
}

func (f *flushRecorder) Flush() { f.flushes++ }

func TestServeFlushesEveryChunk(t *testing.T) {
	recorder := &flushRecorder{ResponseRecorder: httptest.NewRecorder()}
	request := httptest.NewRequest(http.MethodGet, "/", nil)
	err := Serve(recorder, request, "x.XLSX", func(w *hypaxel.Writer) error {
		return writeRows(50_000)(request, w)
	}, hypaxel.WithFlushThreshold(4096))
	if err != nil {
		t.Fatal(err)
	}
	if recorder.flushes < 10 {
		t.Errorf("%d flushes for a response of %d bytes", recorder.flushes, recorder.Body.Len())
	}
	if got := recorder.Header().Get("Content-Disposition"); got != "attachment; filename=x.XLSX" {
		t.Errorf("Content-Disposition = %q", got)
	}
}

type plainWriter struct {
	header http.Header
	body   bytes.Buffer
}

func (p *plainWriter) Header() http.Header         { return p.header }
func (p *plainWriter) Write(b []byte) (int, error) { return p.body.Write(b) }
func (p *plainWriter) WriteHeader(int)             {}

func TestServeWithoutFlusher(t *testing.T) {
	rw := &plainWriter{header: http.Header{}}
	request := httptest.NewRequest(http.MethodGet, "/", nil)
	if err := Serve(rw, request, "plain", func(*hypaxel.Writer) error { return nil }); err != nil {
		t.Fatal(err)
	}
	if rw.body.Len() == 0 {
		t.Error("nothing was written")
	}
}

func TestContentDisposition(t *testing.T) {
	for filename, want := range map[string]string{
		"report":           "attachment; filename=report.xlsx",
		"report.xlsx":      "attachment; filename=report.xlsx",
		"":                 "attachment; filename=export.xlsx",
		"../../etc/passwd": "attachment; filename=....etcpasswd.xlsx",
		"a\r\nX-Evil: 1":   `attachment; filename="aX-Evil: 1.xlsx"`,
		`quo"te`:           `attachment; filename="quo\"te.xlsx"`,
		"Umsätze":          "attachment; filename*=utf-8''Ums%C3%A4tze.xlsx",
	} {
		if got := contentDisposition(filename); got != want {
			t.Errorf("contentDisposition(%q) = %q, want %q", filename, got, want)
		}
	}
}

func TestHandlerErrorBeforeFirstByte(t *testing.T) {
	boom := errors.New("query failed")
	response, body, err := get(t, Handler("x", func(_ *http.Request, w *hypaxel.Writer) error {
		sheet, err := w.AddSheet("Data")
		if err != nil {
			return err
		}
		if err := sheet.WriteRow("a few rows fit into the buffer"); err != nil {
			return err
		}
		return boom
	}))
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusInternalServerError {
		t.Errorf("status = %d, want 500", response.StatusCode)
	}
	if strings.Contains(string(body), "PK") || response.Header.Get("Content-Disposition") != "" {
		t.Error("an error response must not carry workbook bytes or download headers")
	}
}

func TestHandlerAbortsAfterPartialResponse(t *testing.T) {
	boom := errors.New("query failed halfway")
	_, body, err := get(t, Handler("x", func(r *http.Request, w *hypaxel.Writer) error {
		if err := writeRows(50_000)(r, w); err != nil {
			return err
		}
		return boom
	}, hypaxel.WithFlushThreshold(4096)))
	if err == nil {
		t.Errorf("the client read %d bytes without an error; a failed export must not look complete", len(body))
	}
}

func TestServeReportsPartialResponse(t *testing.T) {
	boom := errors.New("query failed halfway")
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/", nil)
	err := Serve(recorder, request, "x", func(w *hypaxel.Writer) error {
		if err := writeRows(50_000)(request, w); err != nil {
			return err
		}
		return boom
	}, hypaxel.WithFlushThreshold(4096))
	if !errors.Is(err, ErrPartial) || !errors.Is(err, boom) {
		t.Errorf("err = %v, want ErrPartial wrapping the cause", err)
	}
	sent := recorder.Body.Len()
	if _, zipErr := zip.NewReader(bytes.NewReader(recorder.Body.Bytes()), int64(sent)); zipErr == nil {
		t.Error("an abandoned export must not be completed into a valid archive")
	}

	recorder = httptest.NewRecorder()
	err = Serve(recorder, request, "x", func(*hypaxel.Writer) error { return boom })
	if !errors.Is(err, boom) || errors.Is(err, ErrPartial) || recorder.Body.Len() != 0 || len(recorder.Header()) != 0 {
		t.Errorf("err = %v, body = %d bytes, headers = %v", err, recorder.Body.Len(), recorder.Header())
	}
}

func TestServeStopsWhenRequestIsCancelled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/", nil).WithContext(ctx)
	rows := 0
	err := Serve(recorder, request, "x", func(w *hypaxel.Writer) error {
		sheet, err := w.AddSheet("Data")
		if err != nil {
			return err
		}
		for ; rows < 5_000_000; rows++ {
			if rows == 1000 {
				cancel()
			}
			if err := sheet.WriteRow(rows, "row"); err != nil {
				return err
			}
		}
		return nil
	}, hypaxel.WithFlushThreshold(1024))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if rows >= 100_000 {
		t.Errorf("the export wrote %d rows after the request was cancelled at row 1000", rows)
	}
}

func TestHandlerStaysQuietWhenClientIsGone(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/", nil).WithContext(ctx)
	Handler("x", writeRows(10)).ServeHTTP(recorder, request)
	if recorder.Body.Len() != 0 {
		t.Errorf("wrote %d bytes for a cancelled request", recorder.Body.Len())
	}
}

type brokenFlusher struct{ *httptest.ResponseRecorder }

func (brokenFlusher) FlushError() error { return errors.New("connection reset") }

func TestServeReturnsFlushErrors(t *testing.T) {
	request := httptest.NewRequest(http.MethodGet, "/", nil)
	done := make(chan error, 1)
	go func() {
		done <- Serve(brokenFlusher{httptest.NewRecorder()}, request, "x", func(*hypaxel.Writer) error { return nil })
	}()
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "connection reset") || !errors.Is(err, ErrPartial) {
			t.Errorf("err = %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Serve did not return")
	}
}
