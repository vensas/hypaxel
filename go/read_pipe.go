package hypaxel

import (
	"io"
	"sync"
)

const (
	pipeChunk   = 64 << 10
	pipeBuffers = 4
)

type pipeChunkData struct {
	buf []byte
	n   int
	err error
}

// pipeReader decompresses a ZIP entry on its own goroutine, so that inflating the next chunk
// overlaps with parsing the current one. At most pipeBuffers chunks exist at a time. The entry
// reader is only touched by that goroutine, which also closes it.
type pipeReader struct {
	filled  chan pipeChunkData
	free    chan []byte
	done    chan struct{}
	exited  chan struct{}
	cur     pipeChunkData
	off     int
	err     error
	closing sync.Once
}

func newPipeReader(rc io.ReadCloser) *pipeReader {
	p := &pipeReader{
		filled: make(chan pipeChunkData, pipeBuffers),
		free:   make(chan []byte, pipeBuffers),
		done:   make(chan struct{}),
		exited: make(chan struct{}),
	}
	for range pipeBuffers {
		p.free <- make([]byte, pipeChunk)
	}
	go p.run(rc)
	return p
}

func (p *pipeReader) run(rc io.ReadCloser) {
	defer close(p.exited)
	defer rc.Close()
	for {
		var buf []byte
		select {
		case buf = <-p.free:
		case <-p.done:
			return
		}
		n, empty := 0, 0
		var err error
		for n < len(buf) && err == nil {
			var m int
			m, err = rc.Read(buf[n:])
			n += m
			if m > 0 {
				empty = 0
			} else if empty++; empty == 100 && err == nil {
				err = io.ErrNoProgress
			}
		}
		select {
		case p.filled <- pipeChunkData{buf, n, err}:
		case <-p.done:
			return
		}
		if err != nil {
			return
		}
	}
}

func (p *pipeReader) Read(b []byte) (int, error) {
	for p.off == p.cur.n {
		if p.err != nil {
			return 0, p.err
		}
		if p.cur.buf != nil {
			p.free <- p.cur.buf
			p.cur.buf = nil
		}
		p.cur = <-p.filled
		p.off = 0
		if p.cur.err != nil {
			p.err = p.cur.err
		}
	}
	n := copy(b, p.cur.buf[p.off:p.cur.n])
	p.off += n
	return n, nil
}

// stop tells the goroutine to finish without waiting for it.
func (p *pipeReader) stop() {
	p.closing.Do(func() { close(p.done) })
}

// Close stops the goroutine and waits for it, so the entry reader is closed when Close returns.
func (p *pipeReader) Close() error {
	p.stop()
	<-p.exited
	return nil
}
