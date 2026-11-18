package hypaxel

import (
	"compress/flate"
	"encoding/binary"
	"hash/crc32"
	"sync"
)

const (
	zipLocalHeaderSig    = 0x04034b50
	zipDataDescriptorSig = 0x08074b50
	zipCentralHeaderSig  = 0x02014b50
	zipEOCDSig           = 0x06054b50
	zip64EOCDSig         = 0x06064b50
	zip64LocatorSig      = 0x07064b50

	zipFlagDataDescriptor = 1 << 3
	zipMethodDeflate      = 8
	zipVersionDefault     = 20
	zipVersionZip64       = 45
	// MS-DOS timestamp for 1980-01-01 00:00:00. A fixed time keeps output reproducible.
	zipDOSTime = 0
	zipDOSDate = 1<<5 | 1
)

type zipRecord struct {
	name         string
	crc          uint32
	compressed   uint64
	uncompressed uint64
	offset       uint64
}

// zipStream is a minimal streaming ZIP writer. Every entry carries a data descriptor, so sizes
// and CRCs never have to be patched into bytes already written and the archive works with
// non-seekable destinations. ZIP64 records are added when an entry, an offset or the entry
// count exceeds the classic limits.
//
// It performs no I/O: bytes accumulate in out and the owner drains them.
type zipStream struct {
	out      []byte
	position uint64
	level    int
	deflater *flate.Writer
	open     bool
	entry    zipRecord
	dataFrom uint64
	central  []zipRecord
	// Classic format limits, lowered by tests to exercise the ZIP64 paths.
	limit32 uint64
	limit16 uint64
}

var deflaterPools [10]sync.Pool

func (z *zipStream) init(level, capacity int) {
	z.out = make([]byte, 0, capacity)
	z.level = level
	z.limit32 = 0xFFFFFFFF
	z.limit16 = 0xFFFF
}

// Write receives compressed bytes from the deflater.
func (z *zipStream) Write(p []byte) (int, error) {
	z.out = append(z.out, p...)
	z.position += uint64(len(p))
	return len(p), nil
}

func (z *zipStream) startEntry(name string) {
	offset := z.position
	h := z.out
	h = binary.LittleEndian.AppendUint32(h, zipLocalHeaderSig)
	h = binary.LittleEndian.AppendUint16(h, zipVersionDefault)
	h = binary.LittleEndian.AppendUint16(h, zipFlagDataDescriptor)
	h = binary.LittleEndian.AppendUint16(h, zipMethodDeflate)
	h = binary.LittleEndian.AppendUint16(h, zipDOSTime)
	h = binary.LittleEndian.AppendUint16(h, zipDOSDate)
	h = append(h, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
	h = binary.LittleEndian.AppendUint16(h, uint16(len(name)))
	h = binary.LittleEndian.AppendUint16(h, 0)
	h = append(h, name...)
	z.position += uint64(len(h) - len(z.out))
	z.out = h

	if z.deflater == nil {
		if pooled, ok := deflaterPools[z.level].Get().(*flate.Writer); ok {
			z.deflater = pooled
		} else {
			// The level is clamped to 0..9, the only source of an error here.
			z.deflater, _ = flate.NewWriter(z, z.level)
		}
	}
	z.deflater.Reset(z)
	z.entry = zipRecord{name: name, offset: offset}
	z.dataFrom = z.position
	z.open = true
}

// write compresses data into the current entry. The deflater's only error source is its
// destination, which is this in-memory stream and cannot fail.
func (z *zipStream) write(data []byte) {
	z.entry.crc = crc32.Update(z.entry.crc, crc32.IEEETable, data)
	z.entry.uncompressed += uint64(len(data))
	_, _ = z.deflater.Write(data)
}

// flush emits everything compressed so far, so a reader of the destination sees it.
func (z *zipStream) flush() {
	if z.open {
		_ = z.deflater.Flush()
	}
}

func (z *zipStream) finishEntry() {
	_ = z.deflater.Close()
	z.open = false
	e := z.entry
	e.compressed = z.position - z.dataFrom

	d := z.out
	d = binary.LittleEndian.AppendUint32(d, zipDataDescriptorSig)
	d = binary.LittleEndian.AppendUint32(d, e.crc)
	if e.compressed > z.limit32 || e.uncompressed > z.limit32 {
		d = binary.LittleEndian.AppendUint64(d, e.compressed)
		d = binary.LittleEndian.AppendUint64(d, e.uncompressed)
	} else {
		d = binary.LittleEndian.AppendUint32(d, uint32(e.compressed))
		d = binary.LittleEndian.AppendUint32(d, uint32(e.uncompressed))
	}
	z.position += uint64(len(d) - len(z.out))
	z.out = d
	z.central = append(z.central, e)
}

// finish writes the central directory. No entries may be added afterwards.
func (z *zipStream) finish() {
	const max32 = 0xFFFFFFFF
	start := z.position
	b := z.out
	for _, r := range z.central {
		zip64Sizes := r.compressed > z.limit32 || r.uncompressed > z.limit32
		zip64Offset := r.offset > z.limit32
		extraLen := 0
		if zip64Sizes {
			extraLen += 16
		}
		if zip64Offset {
			extraLen += 8
		}
		version := uint16(zipVersionDefault)
		if extraLen > 0 {
			version = zipVersionZip64
			extraLen += 4
		}
		compressed, uncompressed, offset := uint32(r.compressed), uint32(r.uncompressed), uint32(r.offset)
		if zip64Sizes {
			compressed, uncompressed = max32, max32
		}
		if zip64Offset {
			offset = max32
		}

		b = binary.LittleEndian.AppendUint32(b, zipCentralHeaderSig)
		b = binary.LittleEndian.AppendUint16(b, zipVersionZip64)
		b = binary.LittleEndian.AppendUint16(b, version)
		b = binary.LittleEndian.AppendUint16(b, zipFlagDataDescriptor)
		b = binary.LittleEndian.AppendUint16(b, zipMethodDeflate)
		b = binary.LittleEndian.AppendUint16(b, zipDOSTime)
		b = binary.LittleEndian.AppendUint16(b, zipDOSDate)
		b = binary.LittleEndian.AppendUint32(b, r.crc)
		b = binary.LittleEndian.AppendUint32(b, compressed)
		b = binary.LittleEndian.AppendUint32(b, uncompressed)
		b = binary.LittleEndian.AppendUint16(b, uint16(len(r.name)))
		b = binary.LittleEndian.AppendUint16(b, uint16(extraLen))
		b = append(b, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
		b = binary.LittleEndian.AppendUint32(b, offset)
		b = append(b, r.name...)
		if extraLen > 0 {
			b = binary.LittleEndian.AppendUint16(b, 0x0001)
			b = binary.LittleEndian.AppendUint16(b, uint16(extraLen-4))
			if zip64Sizes {
				b = binary.LittleEndian.AppendUint64(b, r.uncompressed)
				b = binary.LittleEndian.AppendUint64(b, r.compressed)
			}
			if zip64Offset {
				b = binary.LittleEndian.AppendUint64(b, r.offset)
			}
		}
	}
	z.position += uint64(len(b) - len(z.out))

	size := z.position - start
	count := uint64(len(z.central))
	tailFrom := len(b)
	if count > z.limit16 || size > z.limit32 || start > z.limit32 {
		eocdOffset := z.position
		b = binary.LittleEndian.AppendUint32(b, zip64EOCDSig)
		b = binary.LittleEndian.AppendUint64(b, 44)
		b = binary.LittleEndian.AppendUint16(b, zipVersionZip64)
		b = binary.LittleEndian.AppendUint16(b, zipVersionZip64)
		b = binary.LittleEndian.AppendUint32(b, 0)
		b = binary.LittleEndian.AppendUint32(b, 0)
		b = binary.LittleEndian.AppendUint64(b, count)
		b = binary.LittleEndian.AppendUint64(b, count)
		b = binary.LittleEndian.AppendUint64(b, size)
		b = binary.LittleEndian.AppendUint64(b, start)

		b = binary.LittleEndian.AppendUint32(b, zip64LocatorSig)
		b = binary.LittleEndian.AppendUint32(b, 0)
		b = binary.LittleEndian.AppendUint64(b, eocdOffset)
		b = binary.LittleEndian.AppendUint32(b, 1)

		// A lowered test limit must still produce the marker values a reader expects.
		if count > z.limit16 {
			count = 0xFFFF
		}
		if size > z.limit32 {
			size = max32
		}
		if start > z.limit32 {
			start = max32
		}
	}
	b = binary.LittleEndian.AppendUint32(b, zipEOCDSig)
	b = binary.LittleEndian.AppendUint16(b, 0)
	b = binary.LittleEndian.AppendUint16(b, 0)
	b = binary.LittleEndian.AppendUint16(b, uint16(count))
	b = binary.LittleEndian.AppendUint16(b, uint16(count))
	b = binary.LittleEndian.AppendUint32(b, uint32(size))
	b = binary.LittleEndian.AppendUint32(b, uint32(start))
	b = binary.LittleEndian.AppendUint16(b, 0)
	z.position += uint64(len(b) - tailFrom)
	z.out = b
}

// release returns the deflater to the pool shared by writers using the same level.
func (z *zipStream) release() {
	if z.deflater != nil {
		z.deflater.Reset(nil)
		deflaterPools[z.level].Put(z.deflater)
		z.deflater = nil
	}
}
