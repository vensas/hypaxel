#![cfg(all(feature = "tokio", feature = "serde"))]

mod common;

use common::{Book, num, text};
use hypaxel::{Options, XlsxWriter};
use serde::Serialize;
use tokio::io::AsyncReadExt;

#[derive(Serialize)]
struct Order {
    id: u32,
    customer: String,
    amount: f64,
}

/// Writes through an 8 KiB in-memory pipe: the writer must await the reader repeatedly.
#[tokio::test]
async fn streams_through_a_small_pipe_with_backpressure() {
    let (tx, mut rx) = tokio::io::duplex(8 * 1024);
    let reader = tokio::spawn(async move {
        let mut bytes = Vec::new();
        rx.read_to_end(&mut bytes).await.unwrap();
        bytes
    });

    let mut writer = XlsxWriter::with_options(tx, Options::new().flush_threshold(4096));
    let mut sheet = writer.add_worksheet("Rows").unwrap();
    for i in 0..30_000u32 {
        sheet.add_row_async((i, format!("row {i}"), i as f64 / 3.0)).await.unwrap();
    }
    sheet.write("last").end_row_async().await.unwrap();
    let tx = writer.finish_async().await.unwrap();
    drop(tx);

    let mut book = Book::new(reader.await.unwrap());
    assert_eq!(book.rows("Rows"), 30_001);
    assert_eq!(text(&book.value("Rows", 30_000, 2)), "row 29999");
    assert_eq!(text(&book.value("Rows", 30_001, 1)), "last");
}

#[tokio::test]
async fn records_from_a_stream() {
    let orders = futures_util::stream::iter(
        (1..=5_000u32).map(|i| Ok::<_, std::io::Error>(Order { id: i, customer: format!("Customer {}", i % 7), amount: i as f64 * 1.25 })),
    );
    let mut writer = XlsxWriter::new(Vec::<u8>::new());
    writer.write_sheet_stream("Orders", orders).await.unwrap();
    writer.write_sheet_records_async("Small", [Order { id: 1, customer: "x".into(), amount: 2.0 }]).await.unwrap();
    let bytes = writer.finish_async().await.unwrap();

    let mut book = Book::new(bytes);
    assert_eq!(text(&book.value("Orders", 1, 1)), "id");
    assert_eq!(book.rows("Orders"), 5_001);
    assert_eq!(num(&book.value("Orders", 5_001, 3)), 6250.0);
    assert_eq!(text(&book.value("Small", 2, 2)), "x");
}

#[tokio::test]
async fn stream_errors_are_propagated() {
    let items = futures_util::stream::iter(vec![
        Ok(Order { id: 1, customer: "a".into(), amount: 1.0 }),
        Err(std::io::Error::other("database went away")),
    ]);
    let mut writer = XlsxWriter::new(Vec::<u8>::new());
    let err = writer.write_sheet_stream("Orders", items).await.unwrap_err();
    assert!(err.to_string().contains("database went away"));
}
