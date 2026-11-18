package hypaxel

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"io"
	"strconv"
	"strings"
	"testing"
)

func readArchive(t *testing.T, data []byte) map[string]string {
	t.Helper()
	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		t.Fatalf("zip.NewReader: %v", err)
	}
	files := map[string]string{}
	for _, file := range zr.File {
		rc, err := file.Open()
		if err != nil {
			t.Fatalf("%s: %v", file.Name, err)
		}
		content, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			t.Fatalf("%s: %v (CRC or size mismatch)", file.Name, err)
		}
		files[file.Name] = string(content)
	}
	return files
}

func TestZipRoundTrip(t *testing.T) {
	big := strings.Repeat("<x>0123456789</x>", 50_000)
	for level := 0; level <= 9; level++ {
		var z zipStream
		z.init(level, 1024)
		z.startEntry("a.txt")
		z.write([]byte("hello "))
		z.write([]byte("world"))
		z.finishEntry()
		z.startEntry("dir/b.xml")
		z.write([]byte(big))
		z.finishEntry()
		z.startEntry("empty")
		z.finishEntry()
		z.finish()
		z.release()

		if z.position != uint64(len(z.out)) {
			t.Fatalf("level %d: position %d, wrote %d bytes", level, z.position, len(z.out))
		}
		files := readArchive(t, z.out)
		if len(files) != 3 || files["a.txt"] != "hello world" || files["dir/b.xml"] != big || files["empty"] != "" {
			t.Errorf("level %d: archive content differs", level)
		}
		if level > 0 && len(z.out) > len(big)/10 {
			t.Errorf("level %d: %d bytes, expected compression", level, len(z.out))
		}
		if bytes.Contains(z.out, binary.LittleEndian.AppendUint32(nil, zip64EOCDSig)) {
			t.Errorf("level %d: a small archive must not contain ZIP64 records", level)
		}
	}
}

func TestZipIsReproducible(t *testing.T) {
	archive := func() []byte {
		var z zipStream
		z.init(1, 1024)
		z.startEntry("a")
		z.write([]byte(strings.Repeat("abc", 1000)))
		z.finishEntry()
		z.finish()
		z.release()
		return z.out
	}
	if !bytes.Equal(archive(), archive()) {
		t.Error("two archives of the same content differ")
	}
}

func TestZipFlushMakesDataReadable(t *testing.T) {
	var z zipStream
	z.init(1, 1024)
	z.flush()
	z.startEntry("a")
	z.write([]byte("some bytes"))
	before := len(z.out)
	z.flush()
	if len(z.out) == before {
		t.Error("flush emitted nothing")
	}
	z.finishEntry()
	z.finish()
	if files := readArchive(t, z.out); files["a"] != "some bytes" {
		t.Errorf("content = %q", files["a"])
	}
}

// The ZIP64 paths are exercised by lowering the classic limits: a real archive would need more
// than 4 GiB. The standard library then reads sizes and offsets from the ZIP64 records.
func TestZip64SizesAndOffsets(t *testing.T) {
	var z zipStream
	z.init(1, 1024)
	z.limit32 = 2000
	small := "tiny"
	large := strings.Repeat("0123456789abcdef", 1000)
	z.startEntry("small")
	z.write([]byte(small))
	z.finishEntry()
	z.startEntry("large")
	z.write([]byte(large))
	z.finishEntry()
	random := make([]byte, 4096)
	state := uint32(1)
	for i := range random {
		state = state*1664525 + 1013904223
		random[i] = byte(state >> 24)
	}
	z.startEntry("incompressible")
	z.write(random)
	z.finishEntry()
	z.startEntry("far")
	z.write([]byte(small))
	z.finishEntry()
	z.finish()

	if !bytes.Contains(z.out, binary.LittleEndian.AppendUint32(nil, zip64EOCDSig)) ||
		!bytes.Contains(z.out, binary.LittleEndian.AppendUint32(nil, zip64LocatorSig)) {
		t.Fatal("no ZIP64 end of central directory records")
	}
	files := readArchive(t, z.out)
	if files["small"] != small || files["large"] != large || files["incompressible"] != string(random) || files["far"] != small {
		t.Error("content differs after reading through the ZIP64 records")
	}

	zr, err := zip.NewReader(bytes.NewReader(z.out), int64(len(z.out)))
	noErr(t, err)
	for _, file := range zr.File {
		offset, err := file.DataOffset()
		noErr(t, err)
		if file.Name == "far" && offset <= 2000 {
			t.Errorf("far: data offset %d, expected one beyond the lowered limit", offset)
		}
		if file.Name == "large" && file.UncompressedSize64 != uint64(len(large)) {
			t.Errorf("large: size %d, want %d", file.UncompressedSize64, len(large))
		}
	}

	descriptor := binary.LittleEndian.AppendUint32(nil, zipDataDescriptorSig)
	first := bytes.Index(z.out, descriptor)
	second := first + 16 + bytes.Index(z.out[first+16:], binary.LittleEndian.AppendUint32(nil, zipLocalHeaderSig))
	if second-first != 16 {
		t.Errorf("small entry: data descriptor of %d bytes, want 16", second-first)
	}
	largeAt := bytes.Index(z.out, []byte("large"))
	largeDescriptor := largeAt + bytes.Index(z.out[largeAt:], descriptor)
	next := largeDescriptor + bytes.Index(z.out[largeDescriptor:], binary.LittleEndian.AppendUint32(nil, zipLocalHeaderSig))
	if next-largeDescriptor != 24 {
		t.Errorf("large entry: data descriptor of %d bytes, want 24 (8-byte sizes)", next-largeDescriptor)
	}
}

func TestZip64EntryCount(t *testing.T) {
	const count = 0xFFFF + 2
	var z zipStream
	z.init(1, 1024)
	for i := range count {
		z.startEntry("e" + strconv.Itoa(i))
		z.finishEntry()
	}
	z.finish()
	if !bytes.Contains(z.out, binary.LittleEndian.AppendUint32(nil, zip64EOCDSig)) {
		t.Fatal("more than 65535 entries need a ZIP64 end of central directory record")
	}
	zr, err := zip.NewReader(bytes.NewReader(z.out), int64(len(z.out)))
	noErr(t, err)
	if len(zr.File) != count {
		t.Errorf("read %d entries, want %d", len(zr.File), count)
	}
}
