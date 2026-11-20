//! Writer memory benchmark: `cargo run --release -p hypaxel --example write_bench -- <case> <rows> <file>`.
//!
//! Writes the 10-column dataset of `benches/write.rs` to a file, generating each row as it is
//! written, as a web service streaming a database cursor would. Cases: `hypaxel`, `hypaxel-serde`,
//! `hypaxel-shared`, `hypaxel-level6`, `rust_xlsxwriter-constant-memory`, `rust_xlsxwriter`, and
//! `baseline`, which only generates the rows.
//!
//! Run each case in its own process (e.g. under `/usr/bin/time -l`) to measure peak memory.

use std::fs::File;
use std::hint::black_box;
use std::time::Instant;

use chrono::{NaiveDate, NaiveDateTime, TimeDelta};
use hypaxel::{Options, StringStorage, XlsxWriter};
use serde::Serialize;

#[derive(Serialize)]
struct Row {
    id: i32,
    name: String,
    category: &'static str,
    price: f64,
    amount: f64,
    #[serde(serialize_with = "hypaxel::records::excel_date")]
    created: NaiveDateTime,
    active: bool,
    quantity: i64,
    comment: String,
    ratio: f64,
}

const CATEGORIES: [&str; 6] = ["Hardware", "Software", "Services", "Consulting", "Support", "Training"];

fn rows(count: usize) -> impl Iterator<Item = Row> {
    let start = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap().and_hms_opt(0, 0, 0).unwrap();
    (0..count).map(move |i| Row {
        id: i as i32,
        name: format!("Customer {}", i % 5000),
        category: CATEGORIES[i % CATEGORIES.len()],
        price: i as f64 * 1.25,
        amount: i as f64 * 0.01,
        created: start + TimeDelta::minutes(i as i64),
        active: i % 2 == 0,
        quantity: i as i64 * 3,
        comment: format!("Unique comment #{i}"),
        ratio: i as f64 / 7.0,
    })
}

fn hypaxel_typed(path: &str, count: usize, options: Options) {
    let mut w = XlsxWriter::with_options(File::create(path).unwrap(), options);
    let mut s = w.add_worksheet("Data").unwrap();
    for r in rows(count) {
        s.add_row((r.id, r.name.as_str(), r.category, r.price, r.amount, r.created, r.active, r.quantity, r.comment.as_str(), r.ratio))
            .unwrap();
    }
    w.finish().unwrap();
}

fn hypaxel_serde(path: &str, count: usize) {
    let mut w = XlsxWriter::new(File::create(path).unwrap());
    w.write_sheet_records("Data", rows(count)).unwrap();
    w.finish().unwrap();
}

fn rust_xlsxwriter(path: &str, count: usize, constant_memory: bool) {
    use rust_xlsxwriter::{Format, Workbook};
    let mut workbook = Workbook::new();
    let date = Format::new().set_num_format("yyyy-mm-dd hh:mm");
    let sheet = if constant_memory { workbook.add_worksheet_with_constant_memory().unwrap() } else { workbook.add_worksheet() };
    for (i, r) in rows(count).enumerate() {
        let row = i as u32;
        sheet.write_number(row, 0, r.id).unwrap();
        sheet.write_string(row, 1, &r.name).unwrap();
        sheet.write_string(row, 2, r.category).unwrap();
        sheet.write_number(row, 3, r.price).unwrap();
        sheet.write_number(row, 4, r.amount).unwrap();
        sheet.write_datetime_with_format(row, 5, r.created, &date).unwrap();
        sheet.write_boolean(row, 6, r.active).unwrap();
        sheet.write_number(row, 7, r.quantity as f64).unwrap();
        sheet.write_string(row, 8, &r.comment).unwrap();
        sheet.write_number(row, 9, r.ratio).unwrap();
    }
    workbook.save(path).unwrap();
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let case = args.first().map(String::as_str).unwrap_or("");
    let count = args.get(1).and_then(|n| n.parse().ok()).unwrap_or(1_000_000);
    let path = args.get(2).map(String::as_str).unwrap_or("bench.xlsx");
    let started = Instant::now();
    match case {
        "baseline" => rows(count).for_each(|r| {
            black_box(r);
        }),
        "hypaxel" => hypaxel_typed(path, count, Options::default()),
        "hypaxel-serde" => hypaxel_serde(path, count),
        "hypaxel-shared" => hypaxel_typed(path, count, Options::new().string_storage(StringStorage::Shared)),
        "hypaxel-level6" => hypaxel_typed(path, count, Options::new().compression_level(6)),
        "rust_xlsxwriter-constant-memory" => rust_xlsxwriter(path, count, true),
        "rust_xlsxwriter" => rust_xlsxwriter(path, count, false),
        _ => {
            eprintln!(
                "usage: write_bench baseline|hypaxel|hypaxel-serde|hypaxel-shared|hypaxel-level6|rust_xlsxwriter-constant-memory|rust_xlsxwriter <rows> <file>"
            );
            std::process::exit(2);
        }
    }
    println!("{case:32} {count:>8} rows {:>8.3} s", started.elapsed().as_secs_f64());
}
