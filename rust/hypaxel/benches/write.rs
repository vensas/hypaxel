//! Write benchmark: 10 mixed columns (int, 2× string, 2× float, decimal-ish float, datetime, bool,
//! i64, unique string), the same dataset as the .NET benchmark.
//!
//! * `cargo bench -p hypaxel` — Criterion at 10k / 100k rows.
//! * `HYPAXEL_QUICK=1000000 cargo bench -p hypaxel` — one stopwatch run per library.

use std::hint::black_box;
use std::time::Instant;

use chrono::{NaiveDate, NaiveDateTime, TimeDelta};
use criterion::{BenchmarkId, Criterion, Throughput};
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

fn data(count: usize) -> Vec<Row> {
    let start = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap().and_hms_opt(0, 0, 0).unwrap();
    (0..count)
        .map(|i| Row {
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
        .collect()
}

fn hypaxel_typed(rows: &[Row], options: Options) -> Vec<u8> {
    let mut w = XlsxWriter::with_options(Vec::with_capacity(64 << 20), options);
    let mut s = w.add_worksheet("Data").unwrap();
    for r in rows {
        s.add_row((r.id, r.name.as_str(), r.category, r.price, r.amount, r.created, r.active, r.quantity, r.comment.as_str(), r.ratio))
            .unwrap();
    }
    w.finish().unwrap()
}

fn hypaxel_serde(rows: &[Row]) -> Vec<u8> {
    let mut w = XlsxWriter::new(Vec::with_capacity(64 << 20));
    w.write_sheet_records("Data", rows).unwrap();
    w.finish().unwrap()
}

fn rust_xlsxwriter(rows: &[Row], constant_memory: bool) -> Vec<u8> {
    use rust_xlsxwriter::{Format, Workbook};
    let mut workbook = Workbook::new();
    let date = Format::new().set_num_format("yyyy-mm-dd hh:mm");
    let sheet = if constant_memory { workbook.add_worksheet_with_constant_memory().unwrap() } else { workbook.add_worksheet() };
    for (i, r) in rows.iter().enumerate() {
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
    workbook.save_to_buffer().unwrap()
}

type Case = (&'static str, fn(&[Row]) -> Vec<u8>);

const CASES: [Case; 7] = [
    ("hypaxel (tuples)", |r| hypaxel_typed(r, Options::default())),
    ("hypaxel (serde)", hypaxel_serde),
    ("hypaxel (shared strings)", |r| hypaxel_typed(r, Options::new().string_storage(StringStorage::Shared))),
    ("hypaxel (level 6)", |r| hypaxel_typed(r, Options::new().compression_level(6))),
    ("hypaxel (level 0, no deflate)", |r| hypaxel_typed(r, Options::new().compression_level(0))),
    ("rust_xlsxwriter (constant memory)", |r| rust_xlsxwriter(r, true)),
    ("rust_xlsxwriter", |r| rust_xlsxwriter(r, false)),
];

fn quick(count: usize) {
    let rows = data(count);
    println!("{:<34} {:>10} {:>10} {:>10}", "library", "rows", "ms", "MB");
    for (name, run) in CASES {
        black_box(run(&rows[..rows.len().min(10_000)])); // warm-up
        let start = Instant::now();
        let bytes = black_box(run(&rows));
        let ms = start.elapsed().as_secs_f64() * 1000.0;
        println!("{name:<34} {count:>10} {ms:>10.0} {:>10.1}", bytes.len() as f64 / 1048576.0);
    }
}

fn main() {
    if let Ok(n) = std::env::var("HYPAXEL_QUICK") {
        quick(n.parse().expect("HYPAXEL_QUICK must be a row count"));
        return;
    }

    let mut c = Criterion::default().sample_size(10).configure_from_args();
    let mut group = c.benchmark_group("write");
    for count in [10_000usize, 100_000] {
        let rows = data(count);
        group.throughput(Throughput::Elements(count as u64));
        for (name, run) in CASES {
            group.bench_with_input(BenchmarkId::new(name, count), &rows, |b, rows| b.iter(|| black_box(run(rows))));
        }
    }
    group.finish();
    c.final_summary();
}
