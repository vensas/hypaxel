#![cfg(all(feature = "serde", feature = "chrono"))]

mod common;

use std::collections::BTreeMap;

use calamine::Data;
use chrono::{NaiveDate, NaiveDateTime, NaiveTime, TimeDelta};
use common::{Book, num, text};
use hypaxel::records::RecordOptions;
use hypaxel::{Style, XlsxWriter};
use serde::Serialize;

#[derive(Serialize, Clone)]
#[serde(rename_all = "PascalCase")]
enum Status {
    Active,
    Suspended,
}

#[derive(Serialize, Clone)]
struct Customer {
    #[serde(rename = "Customer #")]
    id: u32,
    name: Option<String>,
    balance: f64,
    #[serde(serialize_with = "hypaxel::records::excel_date")]
    last_order: Option<NaiveDateTime>,
    #[serde(serialize_with = "hypaxel::records::excel_date")]
    since: NaiveDate,
    status: Status,
    small: i16,
    #[serde(skip)]
    #[allow(dead_code)]
    secret: String,
    plain_date: NaiveDate,
}

fn sample() -> Vec<Customer> {
    vec![
        Customer {
            id: 1,
            name: Some("Alice".into()),
            balance: 1234.5,
            last_order: Some(NaiveDate::from_ymd_opt(2024, 5, 1).unwrap().and_hms_opt(12, 0, 0).unwrap()),
            since: NaiveDate::from_ymd_opt(2020, 1, 1).unwrap(),
            status: Status::Active,
            small: 7,
            secret: "hidden".into(),
            plain_date: NaiveDate::from_ymd_opt(2024, 1, 1).unwrap(),
        },
        Customer {
            id: 2,
            name: None,
            balance: -3.0,
            last_order: None,
            since: NaiveDate::from_ymd_opt(2021, 6, 30).unwrap(),
            status: Status::Suspended,
            small: -1,
            secret: "hidden".into(),
            plain_date: NaiveDate::from_ymd_opt(2024, 1, 2).unwrap(),
        },
    ]
}

#[test]
fn write_sheet_records_writes_a_table() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    w.write_sheet_records("Customers", sample()).unwrap();
    let mut book = Book::new(w.finish().unwrap());

    let headers: Vec<String> = (1..=8).map(|c| text(&book.value("Customers", 1, c))).collect();
    assert_eq!(headers, ["Customer #", "name", "balance", "last_order", "since", "status", "small", "plain_date"]);
    assert_eq!(book.value("Customers", 1, 9), Data::Empty);

    assert_eq!(num(&book.value("Customers", 2, 1)), 1.0);
    assert_eq!(text(&book.value("Customers", 2, 2)), "Alice");
    assert_eq!(num(&book.value("Customers", 2, 3)), 1234.5);
    let v = book.value("Customers", 2, 4);
    assert!(matches!(v, Data::DateTime(d) if d.as_f64() == 45413.5), "{v:?}");
    assert!(matches!(book.value("Customers", 2, 5), Data::DateTime(d) if d.as_f64() == 43831.0));
    assert_eq!(text(&book.value("Customers", 2, 6)), "Active");
    assert_eq!(num(&book.value("Customers", 2, 7)), 7.0);
    // Without the helper chrono serializes as an ISO string.
    assert_eq!(text(&book.value("Customers", 2, 8)), "2024-01-01");

    assert_eq!(book.value("Customers", 3, 2), Data::Empty);
    assert_eq!(book.value("Customers", 3, 4), Data::Empty);
    assert_eq!(text(&book.value("Customers", 3, 6)), "Suspended");
    assert_eq!(num(&book.value("Customers", 3, 7)), -1.0);

    let xml = book.part("xl/worksheets/sheet1.xml");
    assert!(xml.contains("<autoFilter ref=\"A1:H3\"/>"), "{xml}");
    assert!(xml.contains("state=\"frozen\""));
    assert!(xml.contains("<col min=\"1\" max=\"1\" width=\"13\" customWidth=\"1\"/>"));
}

#[test]
fn column_styles_and_appending_without_header() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let money = w.add_style(&Style::new().num_format("#,##0.00"));
    let mut sheet = w.add_worksheet("S").unwrap();
    sheet.add_row(("Report title",)).unwrap();
    sheet.write_records(sample(), &RecordOptions::new().header(false).column_style("balance", money)).unwrap();
    let mut book = Book::new(w.finish().unwrap());
    assert_eq!(text(&book.value("S", 1, 1)), "Report title");
    assert_eq!(text(&book.value("S", 2, 2)), "Alice");
    assert_eq!(book.rows("S"), 3);
    assert!(book.part("xl/worksheets/sheet1.xml").contains("<c s=\"1\"><v>1234.5</v></c>"));
}

#[test]
fn tuples_maps_and_scalars() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    w.write_sheet_records("Tuples", [(1, "a", true), (2, "b", false)]).unwrap();
    let maps: Vec<BTreeMap<&str, f64>> = vec![[("x", 1.0), ("y", 2.0)].into(), [("x", 3.0), ("y", 4.0)].into()];
    w.write_sheet_records("Maps", &maps).unwrap();
    w.write_sheet_records("Scalars", [10, 20, 30]).unwrap();
    let mut book = Book::new(w.finish().unwrap());
    assert_eq!(text(&book.value("Tuples", 1, 2)), "Column2");
    assert_eq!(text(&book.value("Tuples", 3, 2)), "b");
    assert_eq!(text(&book.value("Maps", 1, 2)), "y");
    assert_eq!(num(&book.value("Maps", 3, 2)), 4.0);
    assert_eq!(text(&book.value("Scalars", 1, 1)), "Value");
    assert_eq!(num(&book.value("Scalars", 4, 1)), 30.0);
}

#[test]
fn time_and_duration_helpers() {
    #[derive(Serialize)]
    struct Shift {
        #[serde(serialize_with = "hypaxel::records::excel_date")]
        start: NaiveTime,
        #[serde(serialize_with = "hypaxel::records::excel_date")]
        length: TimeDelta,
        #[serde(serialize_with = "hypaxel::records::excel_date")]
        at: chrono::DateTime<chrono::Utc>,
    }
    let shift = Shift {
        start: NaiveTime::from_hms_opt(8, 30, 0).unwrap(),
        length: TimeDelta::minutes(90),
        at: chrono::DateTime::from_timestamp(1_700_000_000, 0).unwrap(),
    };

    // Other serializers see plain ISO strings / seconds.
    let json = serde_json::to_string(&shift).unwrap();
    assert_eq!(json, r#"{"start":"08:30:00","length":5400.0,"at":"2023-11-14T22:13:20"}"#);

    let mut w = XlsxWriter::new(Vec::<u8>::new());
    w.write_sheet_records("S", [shift]).unwrap();
    let mut book = Book::new(w.finish().unwrap());
    assert!(matches!(book.value("S", 2, 1), Data::DateTime(d) if (d.as_f64() - 8.5 / 24.0).abs() < 1e-12));
    assert!(matches!(book.value("S", 2, 2), Data::DateTime(d) if (d.as_f64() - 1.5 / 24.0).abs() < 1e-12));
    assert!(matches!(book.value("S", 2, 3), Data::DateTime(d) if (d.as_f64() - 45244.92592592593).abs() < 1e-8));
}

#[test]
fn nested_values_are_reported() {
    #[derive(Serialize)]
    struct Bad {
        list: Vec<i32>,
    }
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let err = w.write_sheet_records("S", [Bad { list: vec![1] }]).unwrap_err();
    assert!(matches!(err, hypaxel::Error::Unsupported(_)), "{err}");
}
