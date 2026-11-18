#![allow(dead_code)]

use std::io::{Cursor, Read};

use calamine::{Data, Reader, Xlsx, open_workbook_from_rs};

pub struct Book {
    pub bytes: Vec<u8>,
    xlsx: Xlsx<Cursor<Vec<u8>>>,
}

impl Book {
    pub fn new(bytes: Vec<u8>) -> Self {
        let xlsx = open_workbook_from_rs(Cursor::new(bytes.clone())).expect("calamine failed to open the workbook");
        Book { bytes, xlsx }
    }

    pub fn sheet_names(&self) -> Vec<String> {
        self.xlsx.sheet_names().to_vec()
    }

    /// Value at a 1-based row/column.
    pub fn value(&mut self, sheet: &str, row: u32, col: u32) -> Data {
        let range = self.xlsx.worksheet_range(sheet).expect("sheet");
        range.get_value((row - 1, col - 1)).cloned().unwrap_or(Data::Empty)
    }

    pub fn formula(&mut self, sheet: &str, row: u32, col: u32) -> String {
        let range = self.xlsx.worksheet_formula(sheet).expect("sheet");
        range.get_value((row - 1, col - 1)).cloned().unwrap_or_default()
    }

    pub fn rows(&mut self, sheet: &str) -> usize {
        let range = self.xlsx.worksheet_range(sheet).expect("sheet");
        range.end().map_or(0, |(r, _)| r as usize + 1)
    }

    /// Raw XML of a package part.
    pub fn part(&self, name: &str) -> String {
        let mut archive = zip::ZipArchive::new(Cursor::new(self.bytes.clone())).unwrap();
        let mut s = String::new();
        archive.by_name(name).unwrap_or_else(|_| panic!("missing part {name}")).read_to_string(&mut s).unwrap();
        s
    }

    pub fn part_names(&self) -> Vec<String> {
        let archive = zip::ZipArchive::new(Cursor::new(self.bytes.clone())).unwrap();
        archive.file_names().map(|n| n.unwrap().into_owned()).collect()
    }
}

pub fn text(d: &Data) -> String {
    match d {
        Data::String(s) => s.clone(),
        other => format!("{other:?}"),
    }
}

pub fn num(d: &Data) -> f64 {
    match d {
        Data::Float(f) => *f,
        Data::Int(i) => *i as f64,
        Data::DateTime(dt) => dt.as_f64(),
        other => panic!("not a number: {other:?}"),
    }
}
