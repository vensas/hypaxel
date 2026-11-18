//! Run with `cargo run -p hypaxel-axum --example server`, then open
//! http://localhost:3000/orders.xlsx?count=1000000, /report.xlsx or /stream.xlsx.

use std::collections::HashMap;

use axum::Router;
use axum::extract::Query;
use axum::routing::get;
use chrono::{Days, NaiveDate};
use hypaxel::{Color, Formula, HAlign, SheetOptions, Style, Styled, formats};
use hypaxel_axum::Xlsx;
use serde::Serialize;

#[derive(Serialize)]
struct Order {
    #[serde(rename = "Order #")]
    id: u32,
    customer: String,
    #[serde(serialize_with = "hypaxel::records::excel_date")]
    date: NaiveDate,
    amount: f64,
    paid: bool,
}

fn orders(count: u32) -> impl Iterator<Item = Order> {
    let start = NaiveDate::from_ymd_opt(2024, 1, 1).unwrap();
    (1..=count).map(move |i| Order {
        id: i,
        customer: format!("Customer {}", i % 977),
        date: start + Days::new((i % 365) as u64),
        amount: (i * 37 % 100_000) as f64 / 100.0,
        paid: i % 3 != 0,
    })
}

/// One line: records → worksheet with header, frozen top row, auto filter. Generated on the
/// blocking pool and streamed while it is being produced.
async fn orders_xlsx(Query(q): Query<HashMap<String, u32>>) -> Xlsx {
    let count = q.get("count").copied().unwrap_or(100_000);
    Xlsx::from_records("Orders", orders(count)).filename("orders.xlsx")
}

/// Full control: styles, merged title, formulas, multiple sheets.
async fn report_xlsx() -> Xlsx {
    Xlsx::from_fn(|w| {
        let title = w.add_style(&Style::new().bold().font_size(16.0));
        let header = w.add_style(&Style::new().bold().font_color(Color::WHITE).fill(Color::hex("#305496").unwrap()).align(HAlign::Center));
        let money = w.add_style(&Style::new().num_format(formats::THOUSANDS2));
        let percent = w.add_style(&Style::new().num_format(formats::PERCENT2));

        let mut sheet = w.add_worksheet_with("Summary", &SheetOptions::new().freeze_rows(3).column_widths(&[18.0, 14.0, 10.0]))?;
        sheet.add_row((Styled("Quarterly revenue", title),))?;
        sheet.merge_cells(1, 1, 1, 3)?;
        sheet.end_row()?; // blank row 2
        sheet.add_row((Styled("Region", header), Styled("Revenue", header), Styled("Share", header)))?;
        for (i, (region, revenue)) in
            [("North", 48_210.5), ("South", 31_875.0), ("East", 52_400.25), ("West", 27_990.75)].into_iter().enumerate()
        {
            let row = i + 4;
            sheet.add_row((region, Styled(revenue, money), Styled(Formula(&format!("B{row}/SUM(B$4:B$7)")), percent)))?;
        }
        sheet.add_row((Styled("Total", title), Styled(Formula("SUM(B4:B7)"), money)))?;

        w.write_sheet_records("Orders", orders(1_000))
    })
    .filename("report.xlsx")
}

/// Async source: rows arrive from a stream (stand-in for `sqlx::query_as(..).fetch(&pool)`).
async fn stream_xlsx() -> Xlsx {
    let rows = futures_util::stream::iter(orders(250_000).map(Ok::<_, std::io::Error>));
    Xlsx::from_stream("Orders", rows).filename("streamed.xlsx")
}

#[tokio::main]
async fn main() {
    let app = Router::new()
        .route("/orders.xlsx", get(orders_xlsx))
        .route("/report.xlsx", get(report_xlsx))
        .route("/stream.xlsx", get(stream_xlsx));
    let listener = tokio::net::TcpListener::bind("0.0.0.0:3000").await.unwrap();
    println!("listening on http://{}", listener.local_addr().unwrap());
    axum::serve(listener, app).await.unwrap();
}
