// Package hypaxelhttp streams XLSX workbooks as HTTP responses.
package hypaxelhttp

import (
	"context"
	"errors"
	"fmt"
	"mime"
	"net/http"
	"strings"

	hypaxel "github.com/vensas/hypaxel/go"
)

// ErrPartial is wrapped by errors from [Serve] when part of the workbook had already been
// sent, so the response can no longer be replaced by an error page.
var ErrPartial = errors.New("hypaxelhttp: response partially written")

type responseSink struct {
	rw       http.ResponseWriter
	rc       *http.ResponseController
	ctx      context.Context
	filename string
	started  bool
	// abandoned stops a failed export from sending its remaining bytes.
	abandoned bool
}

func (s *responseSink) Write(p []byte) (int, error) {
	if s.abandoned {
		return 0, context.Canceled
	}
	if err := context.Cause(s.ctx); err != nil {
		return 0, err
	}
	if !s.started {
		s.started = true
		header := s.rw.Header()
		header.Set("Content-Type", hypaxel.ContentType)
		header.Set("Content-Disposition", contentDisposition(s.filename))
		header.Set("X-Content-Type-Options", "nosniff")
	}
	n, err := s.rw.Write(p)
	if err != nil {
		return n, err
	}
	if err := s.rc.Flush(); err != nil && !errors.Is(err, http.ErrNotSupported) {
		return n, err
	}
	return n, nil
}

func contentDisposition(filename string) string {
	name := strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7F || r == '/' || r == '\\' {
			return -1
		}
		return r
	}, filename)
	if name == "" {
		name = "export"
	}
	if !strings.HasSuffix(strings.ToLower(name), ".xlsx") {
		name += ".xlsx"
	}
	if value := mime.FormatMediaType("attachment", map[string]string{"filename": name}); value != "" {
		return value
	}
	return "attachment"
}

// Serve streams a workbook as a download named filename (".xlsx" is appended when missing).
// It creates a [hypaxel.Writer] on the response, calls fn to write the workbook, and closes
// the writer. Every chunk is flushed to the client as soon as it is produced.
//
// Headers are sent with the first chunk, so when Serve returns an error that does not wrap
// [ErrPartial], nothing has been written and the caller can still send an error response.
// When the request context is cancelled, the next chunk fails with the context's error, which
// fn sees from its next EndRow or WriteRow.
func Serve(rw http.ResponseWriter, r *http.Request, filename string, fn func(w *hypaxel.Writer) error, opts ...hypaxel.Option) error {
	sink := &responseSink{rw: rw, rc: http.NewResponseController(rw), ctx: r.Context(), filename: filename}
	w := hypaxel.NewWriter(sink, opts...)
	err := fn(w)
	if err == nil {
		err = w.Close()
	} else {
		// Releases the writer's buffers; the workbook is abandoned, so its result is irrelevant.
		sink.abandoned = true
		_ = w.Close()
	}
	if err != nil && sink.started {
		return fmt.Errorf("%w: %w", ErrPartial, err)
	}
	return err
}

// Handler returns an [http.Handler] that serves the workbook written by fn. When fn fails
// before anything was sent it responds with 500 Internal Server Error. When fn fails later it
// aborts the connection by panicking with [http.ErrAbortHandler], the standard way to make the
// client see a failed download instead of a truncated file.
func Handler(filename string, fn func(r *http.Request, w *hypaxel.Writer) error, opts ...hypaxel.Option) http.Handler {
	return http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		err := Serve(rw, r, filename, func(w *hypaxel.Writer) error { return fn(r, w) }, opts...)
		switch {
		case err == nil:
		case errors.Is(err, ErrPartial):
			panic(http.ErrAbortHandler)
		case r.Context().Err() == nil:
			http.Error(rw, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
		}
	})
}
