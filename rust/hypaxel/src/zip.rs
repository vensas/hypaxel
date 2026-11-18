//! Minimal streaming ZIP writer.
//!
//! Every entry is written with a *data descriptor* (general purpose flag bit 3), so sizes and CRCs
//! never have to be patched into already-written bytes: the archive is produced strictly
//! front-to-back and works with non-seekable outputs such as HTTP response bodies. ZIP64 records
//! are added automatically when an entry, an offset or the entry count exceeds the classic limits.
//!
//! The writer is sans-IO: compressed bytes accumulate in [`ZipStream::output`] and the caller
//! drains them to wherever they need to go (sync or async).

use flate2::{Compress, Compression, FlushCompress, Status};

const LOCAL_HEADER_SIG: u32 = 0x0403_4b50;
const DATA_DESCRIPTOR_SIG: u32 = 0x0807_4b50;
const CENTRAL_HEADER_SIG: u32 = 0x0201_4b50;
const EOCD_SIG: u32 = 0x0605_4b50;
const ZIP64_EOCD_SIG: u32 = 0x0606_4b50;
const ZIP64_LOCATOR_SIG: u32 = 0x0706_4b50;

const FLAG_DATA_DESCRIPTOR: u16 = 1 << 3;
const METHOD_DEFLATE: u16 = 8;
const VERSION_DEFAULT: u16 = 20;
const VERSION_ZIP64: u16 = 45;
// MS-DOS timestamp for 1980-01-01 00:00:00. A fixed time keeps output byte-for-byte reproducible.
const DOS_TIME: u16 = 0;
const DOS_DATE: u16 = (1 << 5) | 1;

const U32_LIMIT: u64 = 0xFFFF_FFFF;

struct CentralRecord {
    name: Box<str>,
    crc: u32,
    compressed: u64,
    uncompressed: u64,
    offset: u64,
}

struct OpenEntry {
    name: Box<str>,
    offset: u64,
    crc: crc32fast::Hasher,
    uncompressed: u64,
    compressed_start: u64,
}

pub(crate) struct ZipStream {
    /// Bytes ready to be written to the destination.
    pub output: Vec<u8>,
    /// Total bytes ever appended to `output` (i.e. the current archive offset).
    position: u64,
    compress: Compress,
    entry: Option<OpenEntry>,
    central: Vec<CentralRecord>,
}

impl ZipStream {
    pub fn new(level: u32, capacity: usize) -> Self {
        Self {
            output: Vec::with_capacity(capacity),
            position: 0,
            compress: Compress::new(Compression::new(level), false),
            entry: None,
            central: Vec::new(),
        }
    }

    fn emit(&mut self, bytes: &[u8]) {
        self.output.extend_from_slice(bytes);
        self.position += bytes.len() as u64;
    }

    pub fn start_entry(&mut self, name: &str) {
        debug_assert!(self.entry.is_none(), "previous entry not finished");
        let offset = self.position;
        let mut header = Vec::with_capacity(30 + name.len());
        put_u32(&mut header, LOCAL_HEADER_SIG);
        put_u16(&mut header, VERSION_DEFAULT);
        put_u16(&mut header, FLAG_DATA_DESCRIPTOR);
        put_u16(&mut header, METHOD_DEFLATE);
        put_u16(&mut header, DOS_TIME);
        put_u16(&mut header, DOS_DATE);
        put_u32(&mut header, 0); // crc (in data descriptor)
        put_u32(&mut header, 0); // compressed size (in data descriptor)
        put_u32(&mut header, 0); // uncompressed size (in data descriptor)
        put_u16(&mut header, name.len() as u16);
        put_u16(&mut header, 0); // extra field length
        header.extend_from_slice(name.as_bytes());
        self.emit(&header);
        self.compress.reset();
        self.entry =
            Some(OpenEntry { name: name.into(), offset, crc: crc32fast::Hasher::new(), uncompressed: 0, compressed_start: self.position });
    }

    /// Compresses `data` into the current entry.
    pub fn write(&mut self, data: &[u8]) {
        let entry = self.entry.as_mut().expect("no open zip entry");
        entry.crc.update(data);
        entry.uncompressed += data.len() as u64;
        self.deflate(data, FlushCompress::None);
    }

    pub fn finish_entry(&mut self) {
        self.deflate(&[], FlushCompress::Finish);
        let entry = self.entry.take().expect("no open zip entry");
        let compressed = self.position - entry.compressed_start;
        let crc = entry.crc.finalize();

        let mut descriptor = Vec::with_capacity(24);
        put_u32(&mut descriptor, DATA_DESCRIPTOR_SIG);
        put_u32(&mut descriptor, crc);
        if compressed > U32_LIMIT || entry.uncompressed > U32_LIMIT {
            put_u64(&mut descriptor, compressed);
            put_u64(&mut descriptor, entry.uncompressed);
        } else {
            put_u32(&mut descriptor, compressed as u32);
            put_u32(&mut descriptor, entry.uncompressed as u32);
        }
        self.emit(&descriptor);

        self.central.push(CentralRecord { name: entry.name, crc, compressed, uncompressed: entry.uncompressed, offset: entry.offset });
    }

    fn deflate(&mut self, mut input: &[u8], flush: FlushCompress) {
        loop {
            if self.output.capacity() - self.output.len() < 16 * 1024 {
                self.output.reserve(64 * 1024);
            }

            let before_in = self.compress.total_in();
            let before_out = self.output.len();
            let status = self.compress.compress_vec(input, &mut self.output, flush).expect("deflate failed");
            let consumed = (self.compress.total_in() - before_in) as usize;
            self.position += (self.output.len() - before_out) as u64;
            input = &input[consumed..];

            let done = match flush {
                FlushCompress::Finish => status == Status::StreamEnd,
                _ => input.is_empty(),
            };
            if done {
                return;
            }
        }
    }

    /// Writes the central directory. No entries may be added afterwards.
    pub fn finish(&mut self) {
        debug_assert!(self.entry.is_none());
        let cd_start = self.position;
        let mut buf = Vec::with_capacity(self.central.len() * 96);
        for record in &self.central {
            let zip64_sizes = record.compressed > U32_LIMIT || record.uncompressed > U32_LIMIT;
            let zip64_offset = record.offset > U32_LIMIT;
            let mut extra = Vec::new();
            if zip64_sizes || zip64_offset {
                let mut fields = Vec::new();
                if zip64_sizes {
                    put_u64(&mut fields, record.uncompressed);
                    put_u64(&mut fields, record.compressed);
                }
                if zip64_offset {
                    put_u64(&mut fields, record.offset);
                }
                put_u16(&mut extra, 0x0001);
                put_u16(&mut extra, fields.len() as u16);
                extra.extend_from_slice(&fields);
            }

            let version = if extra.is_empty() { VERSION_DEFAULT } else { VERSION_ZIP64 };
            put_u32(&mut buf, CENTRAL_HEADER_SIG);
            put_u16(&mut buf, VERSION_ZIP64); // version made by (MS-DOS, 4.5)
            put_u16(&mut buf, version);
            put_u16(&mut buf, FLAG_DATA_DESCRIPTOR);
            put_u16(&mut buf, METHOD_DEFLATE);
            put_u16(&mut buf, DOS_TIME);
            put_u16(&mut buf, DOS_DATE);
            put_u32(&mut buf, record.crc);
            put_u32(&mut buf, if zip64_sizes { u32::MAX } else { record.compressed as u32 });
            put_u32(&mut buf, if zip64_sizes { u32::MAX } else { record.uncompressed as u32 });
            put_u16(&mut buf, record.name.len() as u16);
            put_u16(&mut buf, extra.len() as u16);
            put_u16(&mut buf, 0); // comment length
            put_u16(&mut buf, 0); // disk number start
            put_u16(&mut buf, 0); // internal attributes
            put_u32(&mut buf, 0); // external attributes
            put_u32(&mut buf, if zip64_offset { u32::MAX } else { record.offset as u32 });
            buf.extend_from_slice(record.name.as_bytes());
            buf.extend_from_slice(&extra);
        }
        self.emit(&buf);

        let cd_size = self.position - cd_start;
        let count = self.central.len() as u64;
        let needs_zip64 = count > 0xFFFF || cd_size > U32_LIMIT || cd_start > U32_LIMIT;
        let mut tail = Vec::with_capacity(98);
        if needs_zip64 {
            let zip64_eocd_offset = self.position;
            put_u32(&mut tail, ZIP64_EOCD_SIG);
            put_u64(&mut tail, 44); // size of the remaining record
            put_u16(&mut tail, VERSION_ZIP64);
            put_u16(&mut tail, VERSION_ZIP64);
            put_u32(&mut tail, 0); // this disk
            put_u32(&mut tail, 0); // disk with central directory
            put_u64(&mut tail, count);
            put_u64(&mut tail, count);
            put_u64(&mut tail, cd_size);
            put_u64(&mut tail, cd_start);

            put_u32(&mut tail, ZIP64_LOCATOR_SIG);
            put_u32(&mut tail, 0);
            put_u64(&mut tail, zip64_eocd_offset);
            put_u32(&mut tail, 1); // total disks
        }

        put_u32(&mut tail, EOCD_SIG);
        put_u16(&mut tail, 0);
        put_u16(&mut tail, 0);
        put_u16(&mut tail, count.min(0xFFFF) as u16);
        put_u16(&mut tail, count.min(0xFFFF) as u16);
        put_u32(&mut tail, cd_size.min(U32_LIMIT) as u32);
        put_u32(&mut tail, cd_start.min(U32_LIMIT) as u32);
        put_u16(&mut tail, 0); // comment length
        self.emit(&tail);
    }
}

#[inline]
fn put_u16(buf: &mut Vec<u8>, v: u16) {
    buf.extend_from_slice(&v.to_le_bytes());
}

#[inline]
fn put_u32(buf: &mut Vec<u8>, v: u32) {
    buf.extend_from_slice(&v.to_le_bytes());
}

#[inline]
fn put_u64(buf: &mut Vec<u8>, v: u64) {
    buf.extend_from_slice(&v.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    #[test]
    fn round_trips_through_zip_crate() {
        let mut z = ZipStream::new(1, 1024);
        z.start_entry("a.txt");
        z.write(b"hello ");
        z.write(b"world");
        z.finish_entry();
        z.start_entry("dir/b.xml");
        let big = "<x>0123456789</x>".repeat(50_000);
        z.write(big.as_bytes());
        z.finish_entry();
        z.start_entry("empty");
        z.finish_entry();
        z.finish();

        let mut archive = ::zip::ZipArchive::new(std::io::Cursor::new(z.output)).unwrap();
        assert_eq!(archive.len(), 3);
        let mut s = String::new();
        archive.by_name("a.txt").unwrap().read_to_string(&mut s).unwrap();
        assert_eq!(s, "hello world");
        s.clear();
        archive.by_name("dir/b.xml").unwrap().read_to_string(&mut s).unwrap();
        assert_eq!(s, big);
        s.clear();
        archive.by_name("empty").unwrap().read_to_string(&mut s).unwrap();
        assert!(s.is_empty());
    }
}
