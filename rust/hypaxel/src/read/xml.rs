//! Minimal pull parser for the XML that spreadsheet packages contain.
//!
//! It handles elements, attributes, text, CDATA, comments and processing instructions, and ignores
//! namespaces by matching local names. Document type declarations are rejected, so entity
//! expansion attacks are impossible. Every token must fit into a bounded buffer.

use std::io::Read;

use memchr::{memchr, memchr2, memchr3, memmem};

use super::zip::unwrap_io;
use crate::error::{Error, Result};

const INITIAL_BUFFER: usize = 64 * 1024;
/// Largest single token (a tag or a run of text). Excel limits cell text to 32,767 characters.
pub(crate) const MAX_TOKEN: usize = 16 * 1024 * 1024;

/// Bytes kept buffered ahead of a fast-path match, so that a whole cell fits.
const CELL_LOOKAHEAD: usize = 4096;

/// A byte range in the reader's buffer, valid until the next call that reads input.
pub(crate) type Span = (usize, usize);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CellShape {
    /// `<c …><v>text</v></c>`
    Value,
    /// `<c …><is><t>text</t></is></c>`
    Inline,
    /// `<c …/>` or `<c …></c>`
    Empty,
}

/// A cell matched by [`XmlReader::scan_cell`]: attribute value spans and the raw text span.
#[derive(Debug, Clone, Copy)]
pub(crate) struct ScannedCell {
    pub shape: CellShape,
    pub reference: Option<Span>,
    pub cell_type: Option<Span>,
    pub style: Option<Span>,
    pub text: Span,
}

#[derive(Debug)]
pub(crate) enum Event<'a> {
    Start(Tag<'a>),
    Empty(Tag<'a>),
    End(&'a [u8]),
    Text(&'a [u8]),
    CData(&'a [u8]),
    Eof,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct Tag<'a> {
    pub name: &'a [u8],
    attrs: &'a [u8],
}

pub(crate) struct XmlReader<S> {
    src: S,
    part: Box<str>,
    buf: Vec<u8>,
    pos: usize,
    end: usize,
    eof: bool,
    depth: usize,
    started: bool,
}

fn local(name: &[u8]) -> &[u8] {
    match name.iter().rposition(|&b| b == b':') {
        Some(i) => &name[i + 1..],
        None => name,
    }
}

impl<S> XmlReader<S> {
    pub fn new(src: S, part: &str) -> Self {
        Self { src, part: part.into(), buf: vec![0; INITIAL_BUFFER], pos: 0, end: 0, eof: false, depth: 0, started: false }
    }

    pub fn part(&self) -> &str {
        &self.part
    }

    pub fn malformed(&self, message: impl Into<String>) -> Error {
        Error::Malformed { part: self.part.to_string(), message: message.into() }
    }
}

impl<S: Read> XmlReader<S> {
    /// Reads more input, keeping `pos..end`. Returns false at end of input.
    fn fill(&mut self) -> Result<bool> {
        if self.eof {
            return Ok(false);
        }
        if self.pos > 0 {
            self.buf.copy_within(self.pos..self.end, 0);
            self.end -= self.pos;
            self.pos = 0;
        }
        if self.end == self.buf.len() {
            if self.buf.len() >= MAX_TOKEN {
                return Err(Error::LimitExceeded(format!("{} contains an XML token larger than {MAX_TOKEN} bytes", self.part)));
            }
            let len = (self.buf.len() * 2).min(MAX_TOKEN);
            self.buf.resize(len, 0);
        }
        loop {
            match self.src.read(&mut self.buf[self.end..]) {
                Ok(0) => {
                    self.eof = true;
                    return Ok(false);
                }
                Ok(n) => {
                    self.end += n;
                    return Ok(true);
                }
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                Err(e) => return Err(unwrap_io(e)),
            }
        }
    }

    /// Ensures that at least `n` bytes are buffered after `pos`.
    fn need(&mut self, n: usize) -> Result<bool> {
        while self.end - self.pos < n {
            if !self.fill()? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// Finds `needle` at or after `pos + from`, reading more input as needed. Returns the offset
    /// relative to `pos`.
    fn find(&mut self, needle: &[u8], from: usize) -> Result<Option<usize>> {
        let mut from = from;
        loop {
            let hay = &self.buf[self.pos..self.end];
            if from < hay.len() {
                let found = if needle.len() == 1 { memchr(needle[0], &hay[from..]) } else { memmem::find(&hay[from..], needle) };
                if let Some(i) = found {
                    return Ok(Some(from + i));
                }
            }
            from = (self.end - self.pos).saturating_sub(needle.len() - 1).max(from);
            if !self.fill()? {
                return Ok(None);
            }
        }
    }

    /// Finds the `>` that closes a start tag, skipping `>` inside quoted attribute values.
    fn find_tag_end(&mut self) -> Result<Option<usize>> {
        // Fast path: the first `>` closes the tag unless it sits inside a quoted value.
        let hay = &self.buf[self.pos..self.end];
        if let Some(i) = memchr(b'>', &hay[1..]) {
            let tag = &hay[1..1 + i];
            let mut double = 0u32;
            let mut single = false;
            for &b in tag {
                double += (b == b'"') as u32;
                single |= b == b'\'';
            }
            if !single && double % 2 == 0 {
                return Ok(Some(1 + i));
            }
        }
        let mut at = 1;
        let mut quote: Option<u8> = None;
        loop {
            let hay = &self.buf[self.pos..self.end];
            while at < hay.len() {
                match quote {
                    Some(q) => match memchr(q, &hay[at..]) {
                        Some(i) => {
                            at += i + 1;
                            quote = None;
                        }
                        None => at = hay.len(),
                    },
                    None => match memchr3(b'>', b'"', b'\'', &hay[at..]) {
                        Some(i) => {
                            let b = hay[at + i];
                            if b == b'>' {
                                return Ok(Some(at + i));
                            }
                            quote = Some(b);
                            at += i + 1;
                        }
                        None => at = hay.len(),
                    },
                }
            }
            if !self.fill()? {
                return Ok(None);
            }
        }
    }

    fn unexpected_eof(&self) -> Error {
        self.malformed("unexpected end of XML")
    }

    /// Skips a UTF-8 byte order mark and rejects UTF-16, which spreadsheet producers do not use.
    fn check_encoding(&mut self) -> Result<()> {
        self.started = true;
        self.need(3)?;
        let head = &self.buf[self.pos..self.end];
        if head.starts_with(&[0xEF, 0xBB, 0xBF]) {
            self.pos += 3;
        } else if head.starts_with(&[0xFF, 0xFE]) || head.starts_with(&[0xFE, 0xFF]) {
            return Err(Error::Unsupported(format!("{} is encoded as UTF-16", self.part)));
        }
        Ok(())
    }

    /// Reads the rest of the input without parsing it, so that the source can verify what it
    /// delivers (for a ZIP entry, its size and CRC-32).
    pub fn drain(&mut self) -> Result<()> {
        self.pos = self.end;
        let mut sink = [0u8; 8192];
        while !self.eof {
            match self.src.read(&mut sink) {
                Ok(0) => self.eof = true,
                Ok(_) => {}
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
                Err(e) => return Err(unwrap_io(e)),
            }
        }
        Ok(())
    }

    pub fn next(&mut self) -> Result<Event<'_>> {
        if !self.started {
            self.check_encoding()?;
        }
        loop {
            if self.pos == self.end && !self.fill()? {
                if self.depth > 0 {
                    return Err(self.unexpected_eof());
                }
                return Ok(Event::Eof);
            }

            if self.buf[self.pos] != b'<' {
                let len = match self.find(b"<", 0)? {
                    Some(i) => i,
                    None => self.end - self.pos,
                };
                let begin = self.pos;
                self.pos += len;
                return Ok(Event::Text(&self.buf[begin..begin + len]));
            }

            if !self.need(2)? {
                return Err(self.unexpected_eof());
            }
            match self.buf[self.pos + 1] {
                b'/' => {
                    let close = self.find(b">", 2)?.ok_or_else(|| self.unexpected_eof())?;
                    let begin = self.pos;
                    self.pos += close + 1;
                    if self.depth == 0 {
                        return Err(self.malformed("unbalanced end tag"));
                    }
                    self.depth -= 1;
                    let name = trim_end(&self.buf[begin + 2..begin + close]);
                    return Ok(Event::End(local(name)));
                }
                b'?' => {
                    let close = self.find(b"?>", 2)?.ok_or_else(|| self.unexpected_eof())?;
                    self.pos += close + 2;
                }
                b'!' => {
                    self.need(9)?;
                    let rest = &self.buf[self.pos..self.end];
                    if rest.starts_with(b"<!--") {
                        let close = self.find(b"-->", 4)?.ok_or_else(|| self.unexpected_eof())?;
                        self.pos += close + 3;
                    } else if rest.starts_with(b"<![CDATA[") {
                        let close = self.find(b"]]>", 9)?.ok_or_else(|| self.unexpected_eof())?;
                        let begin = self.pos;
                        self.pos += close + 3;
                        return Ok(Event::CData(&self.buf[begin + 9..begin + close]));
                    } else if rest.starts_with(b"<!DOCTYPE") {
                        return Err(self.malformed("document type declarations are not allowed"));
                    } else {
                        return Err(self.malformed("unexpected markup declaration"));
                    }
                }
                _ => {
                    let close = self.find_tag_end()?.ok_or_else(|| self.unexpected_eof())?;
                    let begin = self.pos;
                    self.pos += close + 1;
                    let mut inner = &self.buf[begin + 1..begin + close];
                    let empty = inner.last() == Some(&b'/');
                    if empty {
                        inner = &inner[..inner.len() - 1];
                    }
                    let name_end = inner.iter().position(|b| b.is_ascii_whitespace()).unwrap_or(inner.len());
                    if name_end == 0 {
                        return Err(self.malformed("an element has no name"));
                    }
                    let tag = Tag { name: local(&inner[..name_end]), attrs: &inner[name_end..] };
                    if empty {
                        return Ok(Event::Empty(tag));
                    }
                    self.depth += 1;
                    return Ok(Event::Start(tag));
                }
            }
        }
    }

    pub fn bytes(&self, span: Span) -> &[u8] {
        &self.buf[span.0..span.1]
    }

    fn fill_lookahead(&mut self) -> Result<()> {
        while self.end - self.pos < CELL_LOOKAHEAD && self.fill()? {}
        Ok(())
    }

    /// Fast path for the cell shapes nearly all producers write. If a complete, unprefixed cell of
    /// one of these shapes starts at the current position, consumes it and returns its spans;
    /// otherwise consumes nothing, and the caller falls back to [`next`](Self::next).
    pub fn scan_cell(&mut self) -> Result<Option<ScannedCell>> {
        if !self.started {
            return Ok(None);
        }
        self.fill_lookahead()?;
        let b = &self.buf[..self.end];
        let e = b.len();
        let mut i = self.pos + 2;
        if i >= e || b[self.pos] != b'<' || b[self.pos + 1] != b'c' || !matches!(b[i], b' ' | b'>' | b'/') {
            return Ok(None);
        }
        let mut cell = ScannedCell { shape: CellShape::Empty, reference: None, cell_type: None, style: None, text: (0, 0) };
        loop {
            match b.get(i) {
                None => return Ok(None),
                Some(b' ') => i += 1,
                Some(b'>') => {
                    i += 1;
                    break;
                }
                Some(b'/') => {
                    if b.get(i + 1) != Some(&b'>') {
                        return Ok(None);
                    }
                    self.pos = i + 2;
                    return Ok(Some(cell));
                }
                Some(_) => {
                    let name_start = i;
                    while i < e && b[i] != b'=' {
                        if matches!(b[i], b':' | b'>' | b'/') || b[i].is_ascii_whitespace() {
                            return Ok(None);
                        }
                        i += 1;
                    }
                    let name = &b[name_start..i];
                    i += 1;
                    let Some(&quote) = b.get(i).filter(|q| matches!(q, b'"' | b'\'')) else { return Ok(None) };
                    let value_start = i + 1;
                    let Some(len) = memchr(quote, &b[value_start..]) else { return Ok(None) };
                    i = value_start + len;
                    let span = Some((value_start, i));
                    match name {
                        b"r" => cell.reference = span,
                        b"t" => cell.cell_type = span,
                        b"s" => cell.style = span,
                        _ => {}
                    }
                    i += 1;
                }
            }
        }
        if i + 4 > e || b[i] != b'<' {
            return Ok(None);
        }
        match (b[i + 1], b[i + 2]) {
            (b'v', b'>') => {
                let start = i + 3;
                let Some(len) = memchr(b'<', &b[start..]) else { return Ok(None) };
                let close = start + len;
                if !b[close..].starts_with(b"</v></c>") {
                    return Ok(None);
                }
                cell.shape = CellShape::Value;
                cell.text = (start, close);
                self.pos = close + 8;
            }
            (b'/', b'c') if b[i + 3] == b'>' => self.pos = i + 4,
            (b'i', b's') if b[i + 3] == b'>' => {
                let Some((text, after)) = scan_text(b, i + 4, b"</t></is></c>") else { return Ok(None) };
                cell.shape = CellShape::Inline;
                cell.text = text;
                self.pos = after;
            }
            _ => return Ok(None),
        }
        Ok(Some(cell))
    }

    /// Fast path for `<si><t>text</t></si>` shared string items: consumes the item and returns the
    /// raw text span, or consumes nothing.
    pub fn scan_shared_string(&mut self) -> Result<Option<Span>> {
        if !self.started {
            return Ok(None);
        }
        self.fill_lookahead()?;
        let b = &self.buf[..self.end];
        if !b[self.pos..].starts_with(b"<si>") {
            return Ok(None);
        }
        let Some((text, after)) = scan_text(b, self.pos + 4, b"</t></si>") else { return Ok(None) };
        self.pos = after;
        Ok(Some(text))
    }

    /// For an element whose start tag was just read: if its content is plain text followed by its
    /// end tag, consumes both and returns the raw (undecoded) text. Otherwise consumes nothing.
    pub fn try_raw_text(&mut self) -> Result<Option<&[u8]>> {
        let Some(lt) = self.find(b"<", 0)? else { return Ok(None) };
        if !self.need(lt + 2)? || self.buf[self.pos + lt + 1] != b'/' {
            return Ok(None);
        }
        let Some(gt) = self.find(b">", lt + 2)? else { return Ok(None) };
        let begin = self.pos;
        self.pos += gt + 1;
        self.depth -= 1;
        Ok(Some(&self.buf[begin..begin + lt]))
    }

    /// Skips the rest of the element whose start tag was just read.
    pub fn skip_element(&mut self) -> Result<()> {
        let target = self.depth - 1;
        loop {
            let is_end = matches!(self.next()?, Event::End(_));
            if is_end && self.depth == target {
                return Ok(());
            }
        }
    }

    /// Appends the decoded text of the element whose start tag was just read and consumes its end
    /// tag. Text inside nested elements is included.
    pub fn read_text_into(&mut self, out: &mut String, excel_escapes: bool) -> Result<()> {
        let target = self.depth - 1;
        loop {
            let (is_end, result) = match self.next()? {
                Event::Text(raw) => (false, decode_into(out, raw, true, excel_escapes)),
                Event::CData(raw) => (false, decode_into(out, raw, false, excel_escapes)),
                Event::End(_) => (true, Ok(())),
                _ => (false, Ok(())),
            };
            result.map_err(|m| self.malformed(m))?;
            if is_end && self.depth == target {
                return Ok(());
            }
        }
    }
}

/// Matches `<t>text` or `<t attributes>text` at `at`, followed by `close`. Returns the text span
/// and the position after `close`.
fn scan_text(b: &[u8], at: usize, close: &[u8]) -> Option<(Span, usize)> {
    if !b[at..].starts_with(b"<t") {
        return None;
    }
    let mut j = at + 2;
    match b.get(j)? {
        b'>' => {}
        b' ' => {
            let mut quote = None;
            loop {
                let x = *b.get(j)?;
                if quote.is_none() && x == b'>' {
                    break;
                }
                match quote {
                    Some(q) if x == q => quote = None,
                    None if x == b'"' || x == b'\'' => quote = Some(x),
                    _ => {}
                }
                j += 1;
            }
            if b[j - 1] == b'/' {
                return None;
            }
        }
        _ => return None,
    }
    let start = j + 1;
    let end = start + memchr(b'<', &b[start..])?;
    b[end..].starts_with(close).then_some(((start, end), end + close.len()))
}

fn trim_end(s: &[u8]) -> &[u8] {
    let n = s.iter().rposition(|b| !b.is_ascii_whitespace()).map_or(0, |i| i + 1);
    &s[..n]
}

impl<'a> Tag<'a> {
    /// Iterates over `(local name, raw value)` pairs. Raw values still contain entity references.
    pub fn attrs(&self) -> Attrs<'a> {
        Attrs { rest: self.attrs }
    }

    /// Raw value of the attribute with this local name.
    pub fn attr(&self, name: &[u8]) -> Option<&'a [u8]> {
        self.attrs().find(|(n, _)| *n == name).map(|(_, v)| v)
    }
}

pub(crate) struct Attrs<'a> {
    rest: &'a [u8],
}

impl<'a> Iterator for Attrs<'a> {
    type Item = (&'a [u8], &'a [u8]);

    fn next(&mut self) -> Option<Self::Item> {
        loop {
            let (name, value) = self.next_raw()?;
            if name != b"xmlns" && !name.starts_with(b"xmlns:") {
                return Some((local(name), value));
            }
        }
    }
}

impl<'a> Attrs<'a> {
    fn next_raw(&mut self) -> Option<(&'a [u8], &'a [u8])> {
        let s = self.rest;
        let start = s.iter().position(|b| !b.is_ascii_whitespace())?;
        let eq = start + memchr(b'=', &s[start..])?;
        let name = trim_end(&s[start..eq]);
        let open = eq + 1 + s[eq + 1..].iter().position(|b| !b.is_ascii_whitespace())?;
        let quote = s[open];
        if quote != b'"' && quote != b'\'' {
            self.rest = &[];
            return None;
        }
        let close = open + 1 + memchr(quote, &s[open + 1..])?;
        self.rest = &s[close + 1..];
        Some((name, &s[open + 1..close]))
    }
}

/// Appends `raw` XML character data to `out`. With `entities`, entity and character references
/// are resolved and line endings normalized (text content); without, the bytes are taken as they
/// are (CDATA). With `excel_escapes`, Excel's `_xHHHH_` escapes are decoded as well.
pub(crate) fn decode_into(out: &mut String, raw: &[u8], entities: bool, excel_escapes: bool) -> std::result::Result<(), &'static str> {
    let needs_work = (entities && memchr2(b'&', b'\r', raw).is_some()) || (excel_escapes && memmem::find(raw, b"_x").is_some());
    if !needs_work {
        out.push_str(std::str::from_utf8(raw).map_err(|_| "text is not valid UTF-8")?);
        return Ok(());
    }
    let start = out.len();
    let mut bytes = std::mem::take(out).into_bytes();
    let result = if entities {
        decode_slow(&mut bytes, raw)
    } else {
        bytes.extend_from_slice(raw);
        Ok(())
    };
    if result.is_ok() && excel_escapes {
        decode_excel_escapes(&mut bytes, start);
    }
    match String::from_utf8(bytes) {
        Ok(s) => *out = s,
        Err(e) => {
            let mut bytes = e.into_bytes();
            bytes.truncate(start);
            *out = String::from_utf8(bytes).expect("prefix was valid");
            return Err("text is not valid UTF-8");
        }
    }
    result
}

fn decode_slow(out: &mut Vec<u8>, raw: &[u8]) -> std::result::Result<(), &'static str> {
    let mut i = 0;
    while i < raw.len() {
        let Some(off) = memchr2(b'&', b'\r', &raw[i..]) else {
            out.extend_from_slice(&raw[i..]);
            break;
        };
        out.extend_from_slice(&raw[i..i + off]);
        i += off;
        if raw[i] == b'\r' {
            // XML end-of-line handling: CR LF and lone CR become LF.
            out.push(b'\n');
            i += if raw.get(i + 1) == Some(&b'\n') { 2 } else { 1 };
            continue;
        }
        let semi = memchr(b';', &raw[i..]).filter(|&n| n <= 12).ok_or("unterminated entity reference")?;
        let entity = &raw[i + 1..i + semi];
        match entity {
            b"lt" => out.push(b'<'),
            b"gt" => out.push(b'>'),
            b"amp" => out.push(b'&'),
            b"quot" => out.push(b'"'),
            b"apos" => out.push(b'\''),
            _ => {
                let code = match entity {
                    [b'#', b'x' | b'X', hex @ ..] => parse_radix(hex, 16),
                    [b'#', dec @ ..] => parse_radix(dec, 10),
                    _ => None,
                };
                let c = code.and_then(char::from_u32).ok_or("unknown entity reference")?;
                out.extend_from_slice(c.encode_utf8(&mut [0; 4]).as_bytes());
            }
        }
        i += semi + 1;
    }
    Ok(())
}

fn parse_radix(digits: &[u8], radix: u32) -> Option<u32> {
    if digits.is_empty() || digits.len() > 8 {
        return None;
    }
    u32::from_str_radix(std::str::from_utf8(digits).ok()?, radix).ok()
}

/// Decodes `_xHHHH_` escapes in `bytes[start..]` in place. Unpaired surrogates and code points
/// that are not characters are left as written.
fn decode_excel_escapes(bytes: &mut Vec<u8>, start: usize) {
    let Some(first) = memmem::find(&bytes[start..], b"_x").map(|i| start + i) else {
        return;
    };
    let mut out = bytes[..first].to_vec();
    let mut i = first;
    while i < bytes.len() {
        if bytes[i] == b'_' && i + 7 <= bytes.len() && bytes[i + 1] == b'x' && bytes[i + 6] == b'_' {
            if let Some(code) = parse_radix(&bytes[i + 2..i + 6], 16) {
                let mut j = i + 7;
                let mut c = char::from_u32(code);
                if (0xD800..0xDC00).contains(&code)
                    && j + 7 <= bytes.len()
                    && bytes[j] == b'_'
                    && bytes[j + 1] == b'x'
                    && bytes[j + 6] == b'_'
                {
                    if let Some(low) = parse_radix(&bytes[j + 2..j + 6], 16).filter(|l| (0xDC00..0xE000).contains(l)) {
                        c = char::from_u32(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00));
                        j += 7;
                    }
                }
                if let Some(c) = c {
                    out.extend_from_slice(c.encode_utf8(&mut [0; 4]).as_bytes());
                    i = j;
                    continue;
                }
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    *bytes = out;
}

#[cfg(test)]
mod tests {
    use super::*;

    fn events(xml: &str) -> Vec<String> {
        let mut r = XmlReader::new(xml.as_bytes(), "test.xml");
        r.buf = vec![0; 8];
        let mut out = Vec::new();
        loop {
            let e = match r.next().unwrap() {
                Event::Start(t) => format!("<{}{}>", s(t.name), attrs(&t)),
                Event::Empty(t) => format!("<{}{}/>", s(t.name), attrs(&t)),
                Event::End(n) => format!("</{}>", s(n)),
                Event::Text(t) => format!("T:{}", s(t)),
                Event::CData(t) => format!("C:{}", s(t)),
                Event::Eof => break,
            };
            out.push(e);
        }
        out
    }

    fn s(b: &[u8]) -> String {
        String::from_utf8(b.to_vec()).unwrap()
    }

    fn attrs(t: &Tag) -> String {
        t.attrs().map(|(n, v)| format!(" {}={}", s(n), s(v))).collect()
    }

    #[test]
    fn tokenizes_across_small_buffers() {
        let xml = "<?xml version=\"1.0\"?>\n<x:root xmlns:x=\"urn:a\" x:id=\"7\"><!-- a > comment --><c r='A1' t = \"a>b\">1 &amp; 2</c><e/><![CDATA[<raw>]]></x:root>";
        assert_eq!(events(xml), ["T:\n", "<root id=7>", "<c r=A1 t=a>b>", "T:1 &amp; 2", "</c>", "<e/>", "C:<raw>", "</root>"]);
    }

    fn scan(xml: &str) -> (Option<ScannedCell>, XmlReader<&[u8]>) {
        let mut r = XmlReader::new(xml.as_bytes(), "t");
        r.started = true;
        (r.scan_cell().unwrap(), r)
    }

    #[test]
    fn scans_common_cell_shapes() {
        let (cell, r) = scan(r#"<c r="B2" s='3' t="n"><v>4.5</v></c><c/>"#);
        let cell = cell.unwrap();
        assert_eq!(cell.shape, CellShape::Value);
        assert_eq!(r.bytes(cell.reference.unwrap()), b"B2");
        assert_eq!(r.bytes(cell.style.unwrap()), b"3");
        assert_eq!(r.bytes(cell.cell_type.unwrap()), b"n");
        assert_eq!(r.bytes(cell.text), b"4.5");
        assert_eq!(&r.buf[r.pos..r.end], b"<c/>");

        let (cell, r) = scan(r#"<c t="inlineStr"><is><t xml:space="preserve"> a &amp; b </t></is></c>"#);
        assert_eq!(cell.map(|c| (c.shape, r.bytes(c.text).to_vec())), Some((CellShape::Inline, b" a &amp; b ".to_vec())));
        assert_eq!(scan("<c r=\"A1\"/>").0.unwrap().shape, CellShape::Empty);
        assert_eq!(scan("<c r=\"A1\"></c>").0.unwrap().shape, CellShape::Empty);

        // Anything else is left to the general parser.
        for other in [
            "<c r=\"A1\"><f>1+1</f><v>2</v></c>",
            "<x:c r=\"A1\"><x:v>1</x:v></x:c>",
            "<c x:r=\"A1\"><v>1</v></c>",
            "<c r=\"A1\"><v><![CDATA[1]]></v></c>",
            "<c t=\"inlineStr\"><is><r><t>a</t></r></is></c>",
            "<col min=\"1\"/>",
            "<c r=\"A1\"><v>1</v>",
        ] {
            let (cell, r) = scan(other);
            assert!(cell.is_none(), "{other}");
            assert_eq!(r.pos, 0);
        }
    }

    #[test]
    fn handles_byte_order_marks() {
        let mut r = XmlReader::new(&b"\xEF\xBB\xBF<a/>"[..], "t");
        assert!(matches!(r.next().unwrap(), Event::Empty(t) if t.name == b"a"));
        let mut r = XmlReader::new(&b"\xFF\xFE<\0a\0/\0>\0"[..], "t");
        assert!(r.next().unwrap_err().to_string().contains("UTF-16"));
    }

    #[test]
    fn rejects_doctype_and_truncation() {
        let mut r = XmlReader::new(&b"<!DOCTYPE x [<!ENTITY a \"b\">]><x/>"[..], "t");
        assert!(r.next().is_err());
        let mut r = XmlReader::new(&b"<a><b>"[..], "t");
        r.next().unwrap();
        r.next().unwrap();
        assert!(r.next().is_err());
        let mut r = XmlReader::new(&b"<a attr=\"never closed"[..], "t");
        assert!(r.next().is_err());
    }

    #[test]
    fn decodes_entities_and_excel_escapes() {
        let mut out = String::from("pre:");
        decode_into(&mut out, b"a &lt;&gt;&amp;&quot;&apos; &#65;&#x42; _x000D__x005F_x0041_ _xD83D__xDE00_ snake_case\r\nx", true, true)
            .unwrap();
        assert_eq!(out, "pre:a <>&\"' AB \r_x0041_ \u{1F600} snake_case\nx");
        let mut out = String::new();
        decode_into(&mut out, b"_x0041_", true, false).unwrap();
        assert_eq!(out, "_x0041_");
        assert!(decode_into(&mut String::new(), b"&bogus;", true, false).is_err());
        let mut out = String::from("keep");
        assert!(decode_into(&mut out, b"\xff&amp;", true, false).is_err());
        assert_eq!(out, "keep");
    }
}
