//! ZIP archive reader: central directory (with ZIP64), stored and deflated entries.
//!
//! Entries are decompressed as a stream. The declared uncompressed size and CRC-32 are enforced,
//! so a damaged archive or one that lies about its sizes (a "zip bomb") is reported as an error
//! instead of producing unbounded output.

use std::io::{self, Read, Seek, SeekFrom};

use flate2::read::DeflateDecoder;

use crate::error::{Error, Result};

const LOCAL_HEADER_SIG: u32 = 0x0403_4b50;
const CENTRAL_HEADER_SIG: u32 = 0x0201_4b50;
const EOCD_SIG: u32 = 0x0605_4b50;
const ZIP64_EOCD_SIG: u32 = 0x0606_4b50;
const ZIP64_LOCATOR_SIG: u32 = 0x0706_4b50;

const EOCD_LEN: usize = 22;
const MAX_COMMENT: usize = 0xFFFF;
const MAX_CENTRAL_DIRECTORY: u64 = 64 * 1024 * 1024;

const METHOD_STORED: u16 = 0;
const METHOD_DEFLATE: u16 = 8;
const FLAG_ENCRYPTED: u16 = 1;

const OLE_MAGIC: [u8; 8] = [0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];

#[derive(Debug, Clone)]
pub(crate) struct Entry {
    pub name: Box<str>,
    method: u16,
    flags: u16,
    crc: u32,
    compressed: u64,
    pub uncompressed: u64,
    header_offset: u64,
}

#[derive(Debug, Clone)]
pub(crate) struct Directory {
    entries: Vec<Entry>,
}

fn invalid(message: impl Into<String>) -> Error {
    Error::InvalidFile(message.into())
}

fn le16(b: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([b[at], b[at + 1]])
}

fn le32(b: &[u8], at: usize) -> u32 {
    u32::from_le_bytes(b[at..at + 4].try_into().unwrap())
}

fn le64(b: &[u8], at: usize) -> u64 {
    u64::from_le_bytes(b[at..at + 8].try_into().unwrap())
}

fn read_exact_at<R: Read + Seek>(r: &mut R, offset: u64, buf: &mut [u8]) -> Result<()> {
    r.seek(SeekFrom::Start(offset))?;
    r.read_exact(buf).map_err(|e| match e.kind() {
        io::ErrorKind::UnexpectedEof => invalid("the ZIP archive is truncated"),
        _ => Error::Io(e),
    })
}

impl Directory {
    pub fn read<R: Read + Seek>(r: &mut R) -> Result<Self> {
        let len = r.seek(SeekFrom::End(0))?;
        if len >= 8 {
            let mut magic = [0u8; 8];
            read_exact_at(r, 0, &mut magic)?;
            if magic == OLE_MAGIC {
                return Err(invalid("this is an OLE compound file: a legacy .xls workbook or a password-protected (encrypted) workbook"));
            }
        }
        if len < EOCD_LEN as u64 {
            return Err(invalid("the input is too small to be a ZIP archive"));
        }

        let tail_len = len.min((EOCD_LEN + MAX_COMMENT) as u64) as usize;
        let tail_start = len - tail_len as u64;
        let mut tail = vec![0u8; tail_len];
        read_exact_at(r, tail_start, &mut tail)?;
        let eocd = (0..=tail_len - EOCD_LEN)
            .rev()
            .find(|&i| le32(&tail, i) == EOCD_SIG)
            .ok_or_else(|| invalid("no end of central directory record found; the input is not a ZIP archive"))?;
        let eocd_offset = tail_start + eocd as u64;
        let e = &tail[eocd..];

        let mut count = le16(e, 10) as u64;
        let mut cd_size = le32(e, 12) as u64;
        let mut cd_offset = le32(e, 16) as u64;

        if (count == 0xFFFF || cd_size == 0xFFFF_FFFF || cd_offset == 0xFFFF_FFFF) && eocd_offset >= 20 {
            let mut locator = [0u8; 20];
            read_exact_at(r, eocd_offset - 20, &mut locator)?;
            if le32(&locator, 0) == ZIP64_LOCATOR_SIG {
                let mut record = [0u8; 56];
                read_exact_at(r, le64(&locator, 8), &mut record)?;
                if le32(&record, 0) != ZIP64_EOCD_SIG {
                    return Err(invalid("the ZIP64 end of central directory record is missing"));
                }
                count = le64(&record, 32);
                cd_size = le64(&record, 40);
                cd_offset = le64(&record, 48);
            }
        }

        if cd_size > MAX_CENTRAL_DIRECTORY {
            return Err(Error::LimitExceeded(format!(
                "the ZIP central directory is {cd_size} bytes; the limit is {MAX_CENTRAL_DIRECTORY}"
            )));
        }
        if cd_offset.checked_add(cd_size).is_none_or(|end| end > eocd_offset) {
            return Err(invalid("the ZIP central directory lies outside the archive"));
        }

        let mut cd = vec![0u8; cd_size as usize];
        read_exact_at(r, cd_offset, &mut cd)?;

        let mut entries = Vec::with_capacity(count.min(cd_size / 46) as usize);
        let mut at = 0usize;
        while at + 46 <= cd.len() {
            if le32(&cd, at) != CENTRAL_HEADER_SIG {
                break;
            }
            let h = &cd[at..];
            let flags = le16(h, 8);
            let method = le16(h, 10);
            let crc = le32(h, 16);
            let mut compressed = le32(h, 20) as u64;
            let mut uncompressed = le32(h, 24) as u64;
            let name_len = le16(h, 28) as usize;
            let extra_len = le16(h, 30) as usize;
            let comment_len = le16(h, 32) as usize;
            let mut header_offset = le32(h, 42) as u64;
            let end = 46 + name_len + extra_len + comment_len;
            if h.len() < end {
                return Err(invalid("a ZIP central directory entry is truncated"));
            }
            let name = String::from_utf8_lossy(&h[46..46 + name_len]).replace('\\', "/");

            let mut extra = &h[46 + name_len..46 + name_len + extra_len];
            while extra.len() >= 4 {
                let id = le16(extra, 0);
                let size = (le16(extra, 2) as usize).min(extra.len() - 4);
                if id == 0x0001 {
                    let mut field = &extra[4..4 + size];
                    let mut take = |value: u64| {
                        if value == 0xFFFF_FFFF && field.len() >= 8 {
                            let wide = le64(field, 0);
                            field = &field[8..];
                            wide
                        } else {
                            value
                        }
                    };
                    uncompressed = take(uncompressed);
                    compressed = take(compressed);
                    header_offset = take(header_offset);
                }
                extra = &extra[4 + size..];
            }

            entries.push(Entry { name: name.into(), method, flags, crc, compressed, uncompressed, header_offset });
            at += end;
        }

        if entries.is_empty() {
            return Err(invalid("the ZIP archive has no entries"));
        }
        Ok(Directory { entries })
    }

    /// Finds an entry by its part name, ignoring a leading `/` and, as a fallback, ASCII case.
    pub fn find(&self, name: &str) -> Option<&Entry> {
        let name = name.trim_start_matches('/');
        self.entries.iter().find(|e| &*e.name == name).or_else(|| self.entries.iter().find(|e| e.name.eq_ignore_ascii_case(name)))
    }

    /// Opens an entry for streaming decompression.
    pub fn open<R: Read + Seek>(entry: &Entry, mut r: R) -> Result<EntryReader<R>> {
        if entry.flags & FLAG_ENCRYPTED != 0 {
            return Err(Error::Unsupported(format!("the ZIP entry {} is encrypted", entry.name)));
        }
        if entry.method != METHOD_STORED && entry.method != METHOD_DEFLATE {
            return Err(Error::Unsupported(format!("the ZIP entry {} uses compression method {}", entry.name, entry.method)));
        }
        let mut header = [0u8; 30];
        read_exact_at(&mut r, entry.header_offset, &mut header)?;
        if le32(&header, 0) != LOCAL_HEADER_SIG {
            return Err(invalid(format!("the local header of ZIP entry {} is missing", entry.name)));
        }
        let data_start = entry.header_offset + 30 + le16(&header, 26) as u64 + le16(&header, 28) as u64;
        r.seek(SeekFrom::Start(data_start))?;

        let raw = r.take(entry.compressed);
        let inner = if entry.method == METHOD_DEFLATE { Inner::Deflate(DeflateDecoder::new(raw)) } else { Inner::Stored(raw) };
        Ok(EntryReader {
            inner,
            name: entry.name.clone(),
            expected_crc: entry.crc,
            expected_len: entry.uncompressed,
            crc: crc32fast::Hasher::new(),
            produced: 0,
        })
    }
}

enum Inner<R> {
    Stored(io::Take<R>),
    Deflate(DeflateDecoder<io::Take<R>>),
}

/// Decompressed contents of one ZIP entry. Fails if the entry produces more bytes than declared,
/// ends early, or its CRC-32 does not match.
pub(crate) struct EntryReader<R> {
    inner: Inner<R>,
    name: Box<str>,
    expected_crc: u32,
    expected_len: u64,
    crc: crc32fast::Hasher,
    produced: u64,
}

impl<R> std::fmt::Debug for EntryReader<R> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("EntryReader").field("name", &self.name).field("produced", &self.produced).finish_non_exhaustive()
    }
}

impl<R: Read> Read for EntryReader<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }
        let remaining = self.expected_len - self.produced;
        // Ask for one byte more than declared so that oversized output is detected.
        let limit = buf.len().min(remaining.saturating_add(1).min(usize::MAX as u64) as usize);
        let n = match &mut self.inner {
            Inner::Stored(r) => r.read(&mut buf[..limit])?,
            Inner::Deflate(r) => r.read(&mut buf[..limit]).map_err(|e| self.corrupt(&format!("invalid deflate data ({e})")))?,
        };
        if n as u64 > remaining {
            return Err(self.corrupt("the entry is larger than its declared size"));
        }
        self.produced += n as u64;
        self.crc.update(&buf[..n]);
        if n == 0 {
            if self.produced != self.expected_len {
                return Err(self.corrupt("the entry is shorter than its declared size"));
            }
            if self.crc.clone().finalize() != self.expected_crc {
                return Err(self.corrupt("CRC-32 mismatch"));
            }
        }
        Ok(n)
    }
}

impl<R> EntryReader<R> {
    fn corrupt(&self, message: &str) -> io::Error {
        io::Error::new(io::ErrorKind::InvalidData, Error::Malformed { part: self.name.to_string(), message: message.to_string() })
    }
}

/// Turns an I/O error that wraps one of our own errors back into that error.
pub(crate) fn unwrap_io(e: io::Error) -> Error {
    if e.get_ref().is_some_and(|inner| inner.is::<Error>()) {
        let inner = e.into_inner().expect("checked above");
        return *inner.downcast::<Error>().expect("checked above");
    }
    Error::Io(e)
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use super::*;

    fn archive(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut zip = crate::zip::ZipStream::new(6, 1024);
        for (name, data) in entries {
            zip.start_entry(name);
            zip.write(data);
            zip.finish_entry();
        }
        zip.finish();
        zip.output
    }

    fn read_all(bytes: &[u8], name: &str) -> Result<Vec<u8>> {
        let mut cursor = Cursor::new(bytes);
        let dir = Directory::read(&mut cursor)?;
        let entry = dir.find(name).ok_or_else(|| invalid("missing"))?.clone();
        let mut out = Vec::new();
        Directory::open(&entry, cursor)?.read_to_end(&mut out).map_err(unwrap_io)?;
        Ok(out)
    }

    #[test]
    fn reads_entries_written_by_the_writer() {
        let big: Vec<u8> = (0..200_000u32).flat_map(|i| i.to_le_bytes()).collect();
        let bytes = archive(&[("a.txt", b"hello"), ("dir/b.bin", &big)]);
        assert_eq!(read_all(&bytes, "a.txt").unwrap(), b"hello");
        assert_eq!(read_all(&bytes, "/DIR/B.BIN").unwrap(), big);
    }

    #[test]
    fn rejects_non_zip_input() {
        assert!(matches!(Directory::read(&mut Cursor::new(b"not a zip file at all, really not")), Err(Error::InvalidFile(_))));
        let mut ole = OLE_MAGIC.to_vec();
        ole.resize(512, 0);
        let err = Directory::read(&mut Cursor::new(ole)).unwrap_err();
        assert!(err.to_string().contains("legacy .xls"), "{err}");
    }

    #[test]
    fn detects_corruption() {
        let mut bytes = archive(&[("a.txt", &[b'x'; 10_000])]);
        // Flip a byte inside the compressed data (after the 30 + 5 byte local header).
        bytes[40] ^= 0xFF;
        assert!(read_all(&bytes, "a.txt").is_err());
    }

    #[test]
    fn detects_understated_sizes() {
        let mut bytes = archive(&[("a.txt", &[b'x'; 10_000])]);
        let cd = (0..bytes.len() - 4).rev().find(|&i| le32(&bytes, i) == CENTRAL_HEADER_SIG).unwrap();
        bytes[cd + 24..cd + 28].copy_from_slice(&100u32.to_le_bytes());
        let err = read_all(&bytes, "a.txt").unwrap_err();
        assert!(err.to_string().contains("larger than its declared size"), "{err}");
    }

    #[test]
    fn truncated_input_is_an_error_not_a_panic() {
        let bytes = archive(&[("a.txt", b"hello world")]);
        for len in 0..bytes.len() {
            let _ = read_all(&bytes[..len], "a.txt");
        }
    }
}
