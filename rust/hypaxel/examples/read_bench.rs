//! Reader benchmark: `cargo run --release -p hypaxel --example read_bench -- <command>`.
//!
//! - `generate <file> <rows> <inline|shared>` writes the 10-column benchmark dataset
//! - `hypaxel <file>`, `calamine <file>` (whole-sheet `Range`) and `calamine-stream <file>`
//!   (`worksheet_cells_reader`) read every cell of the first sheet and print a checksum, so the
//!   readers can be checked against each other.
//!
//! Run each reader in its own process (e.g. under `/usr/bin/time -l`) to measure peak memory.

use std::io::BufReader;
use std::time::Instant;

use calamine::{Data, DataRef, Reader, Xlsx, open_workbook};
use chrono::{NaiveDate, TimeDelta};
use hypaxel::{Options, StringStorage, Value, XlsxReader, XlsxWriter};

#[derive(Debug, Default, PartialEq)]
struct Checksum {
    cells: u64,
    numbers: f64,
    text_bytes: u64,
    trues: u64,
}

impl Checksum {
    fn number(&mut self, n: f64) {
        self.cells += 1;
        self.numbers += n;
    }

    fn text(&mut self, s: &str) {
        self.cells += 1;
        self.text_bytes += s.len() as u64;
    }

    fn bool(&mut self, b: bool) {
        self.cells += 1;
        self.trues += b as u64;
    }
}

fn generate(path: &str, rows: u32, storage: StringStorage) {
    let start = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap().and_hms_opt(0, 0, 0).unwrap();
    let names: Vec<String> = (0..5000).map(|i| format!("Customer {i}")).collect();
    let file = std::io::BufWriter::new(std::fs::File::create(path).unwrap());
    let mut w = XlsxWriter::with_options(file, Options::new().string_storage(storage));
    let mut s = w.add_worksheet("Data").unwrap();
    let mut comment = String::new();
    for i in 0..rows {
        use std::fmt::Write;
        comment.clear();
        write!(comment, "Unique comment #{i}").unwrap();
        s.add_row((
            i,
            names[(i % 5000) as usize].as_str(),
            "Hardware",
            i as f64 * 1.25,
            i as f64 * 0.01,
            start + TimeDelta::minutes(i as i64),
            i % 2 == 0,
            i as i64 * 3,
            comment.as_str(),
            i as f64 / 7.0,
        ))
        .unwrap();
    }
    w.finish().unwrap();
}

fn read_hypaxel(path: &str) -> Checksum {
    let mut sum = Checksum::default();
    let mut reader = XlsxReader::open(path).unwrap();
    let mut rows = reader.worksheet_at(0).unwrap();
    while let Some(row) = rows.next_row().unwrap() {
        for cell in row.cells() {
            match cell.value() {
                Value::Number(n) => sum.number(n),
                Value::DateTime(d) => sum.number(d.serial()),
                Value::String(s) | Value::Error(s) => sum.text(s),
                Value::Bool(b) => sum.bool(b),
                _ => {}
            }
        }
    }
    sum
}

fn read_calamine(path: &str) -> Checksum {
    let mut sum = Checksum::default();
    let mut book: Xlsx<_> = open_workbook(path).unwrap();
    let name = book.sheet_names()[0].clone();
    let range = book.worksheet_range(&name).unwrap();
    for row in range.rows() {
        for cell in row {
            match cell {
                Data::Int(n) => sum.number(*n as f64),
                Data::Float(n) => sum.number(*n),
                Data::DateTime(d) => sum.number(d.as_f64()),
                Data::String(s) => sum.text(s),
                Data::Bool(b) => sum.bool(*b),
                _ => {}
            }
        }
    }
    sum
}

fn read_calamine_stream(path: &str) -> Checksum {
    let mut sum = Checksum::default();
    let mut book: Xlsx<BufReader<std::fs::File>> = open_workbook(path).unwrap();
    let name = book.sheet_names()[0].clone();
    let mut cells = book.worksheet_cells_reader(&name).unwrap();
    while let Some(cell) = cells.next_cell().unwrap() {
        match cell.get_value() {
            DataRef::Int(n) => sum.number(*n as f64),
            DataRef::Float(n) => sum.number(*n),
            DataRef::DateTime(d) => sum.number(d.as_f64()),
            DataRef::String(s) => sum.text(s),
            DataRef::SharedString(s) => sum.text(s),
            DataRef::Bool(b) => sum.bool(*b),
            _ => {}
        }
    }
    sum
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let command = args.first().map(String::as_str).unwrap_or("");
    let path = args.get(1).map(String::as_str).unwrap_or("bench.xlsx");
    if command == "generate" {
        let rows = args.get(2).and_then(|n| n.parse().ok()).unwrap_or(1_000_000);
        let storage = if args.get(3).map(String::as_str) == Some("shared") { StringStorage::Shared } else { StringStorage::Inline };
        generate(path, rows, storage);
        return;
    }
    let started = Instant::now();
    let sum = match command {
        "hypaxel" => read_hypaxel(path),
        "calamine" => read_calamine(path),
        "calamine-stream" => read_calamine_stream(path),
        _ => {
            eprintln!("usage: read_bench generate <file> <rows> <inline|shared> | hypaxel|calamine|calamine-stream <file>");
            std::process::exit(2);
        }
    };
    println!("{command:16} {:>8.3} s  {sum:?}", started.elapsed().as_secs_f64());
}
