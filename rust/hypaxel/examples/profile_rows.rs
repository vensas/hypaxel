//! Profiling target: `cargo run --release -p hypaxel --example profile_rows -- 200000`.
use chrono::{NaiveDate, TimeDelta};
use hypaxel::{Options, XlsxWriter};

fn main() {
    let n: u32 = std::env::args().nth(1).and_then(|a| a.parse().ok()).unwrap_or(1_000_000);
    let start = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap().and_hms_opt(0, 0, 0).unwrap();
    let names: Vec<String> = (0..5000).map(|i| format!("Customer {i}")).collect();
    let level = std::env::var("LEVEL").ok().and_then(|l| l.parse().ok()).unwrap_or(1);
    let mut w = XlsxWriter::with_options(std::io::sink(), Options::new().compression_level(level));
    let mut s = w.add_worksheet("Data").unwrap();
    let mut comment = String::new();
    for i in 0..n {
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
