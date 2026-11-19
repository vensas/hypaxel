//! The shared string table, stored as one contiguous string plus end offsets.

use std::io::Read;

use super::xml::{Event, MAX_TOKEN, XmlReader, decode_into};
use crate::error::{Error, Result};

#[derive(Debug, Default)]
pub(crate) struct SharedStrings {
    data: String,
    ends: Vec<u32>,
}

impl SharedStrings {
    pub fn get(&self, index: usize) -> Option<&str> {
        let end = *self.ends.get(index)? as usize;
        let start = if index == 0 { 0 } else { self.ends[index - 1] as usize };
        Some(&self.data[start..end])
    }

    pub fn len(&self) -> usize {
        self.ends.len()
    }

    /// Heap memory used by the table.
    pub fn memory(&self) -> usize {
        self.data.capacity() + self.ends.capacity() * 4
    }

    pub fn parse<S: Read>(xml: &mut XmlReader<S>, max_bytes: u64) -> Result<Self> {
        // Offsets are u32; one more text token must still fit after the limit check.
        let limit = max_bytes.min(u32::MAX as u64 - MAX_TOKEN as u64) as usize;
        let mut sst = SharedStrings::default();
        loop {
            if let Some(text) = xml.scan_shared_string()? {
                decode_into(&mut sst.data, xml.bytes(text), true, true).map_err(|m| xml.malformed(m))?;
                sst.check_limit(limit)?;
                sst.ends.push(sst.data.len() as u32);
                continue;
            }
            match xml.next()? {
                Event::Start(t) if t.name == b"si" => sst.read_item(xml, limit)?,
                Event::Empty(t) if t.name == b"si" => {}
                Event::Eof => break,
                _ => continue,
            }
            sst.ends.push(sst.data.len() as u32);
        }
        sst.data.shrink_to_fit();
        sst.ends.shrink_to_fit();
        Ok(sst)
    }

    /// Reads one `<si>` item: plain `<t>` or rich-text runs `<r><t>`, without phonetic runs.
    fn read_item<S: Read>(&mut self, xml: &mut XmlReader<S>, limit: usize) -> Result<()> {
        loop {
            let action = match xml.next()? {
                Event::Start(t) if t.name == b"t" => 1,
                Event::Start(t) if t.name == b"rPh" || t.name == b"phoneticPr" || t.name == b"rPr" => 2,
                Event::End(name) if name == b"si" => return Ok(()),
                _ => 0,
            };
            match action {
                1 => xml.read_text_into(&mut self.data, true)?,
                2 => xml.skip_element()?,
                _ => {}
            }
            self.check_limit(limit)?;
        }
    }

    fn check_limit(&self, limit: usize) -> Result<()> {
        if self.data.len() > limit {
            return Err(Error::LimitExceeded(format!(
                "the shared string table exceeds {limit} bytes (ReadOptions::max_shared_strings_bytes)"
            )));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_plain_rich_and_phonetic_items() {
        let xml = r#"<?xml version="1.0"?><sst xmlns="x" count="4" uniqueCount="4">
            <si><t>plain</t></si>
            <si><r><rPr><b/></rPr><t xml:space="preserve">rich </t></r><r><t>text</t></r></si>
            <si><t>東京</t><rPh sb="0" eb="2"><t>トウキョウ</t></rPh><phoneticPr fontId="1"/></si>
            <si/>
            <si><t>a &amp; b_x000A_c</t></si></sst>"#;
        let sst = SharedStrings::parse(&mut XmlReader::new(xml.as_bytes(), "sst.xml"), u64::MAX).unwrap();
        assert_eq!(sst.len(), 5);
        assert_eq!(sst.get(0), Some("plain"));
        assert_eq!(sst.get(1), Some("rich text"));
        assert_eq!(sst.get(2), Some("東京"));
        assert_eq!(sst.get(3), Some(""));
        assert_eq!(sst.get(4), Some("a & b\nc"));
        assert_eq!(sst.get(5), None);
    }

    #[test]
    fn enforces_the_size_limit() {
        let xml = "<sst><si><t>0123456789</t></si><si><t>0123456789</t></si></sst>";
        let err = SharedStrings::parse(&mut XmlReader::new(xml.as_bytes(), "sst.xml"), 15).unwrap_err();
        assert!(matches!(err, Error::LimitExceeded(_)));
    }
}
