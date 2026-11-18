use std::io::Cursor;

use axum::Router;
use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use axum::routing::get;
use calamine::{Data, Reader, Xlsx as XlsxFile, open_workbook_from_rs};
use http_body_util::BodyExt;
use hypaxel::{Formula, Style, Styled};
use hypaxel_axum::Xlsx;
use serde::Serialize;
use tower::ServiceExt;

#[derive(Serialize, Clone)]
struct Order {
    id: u32,
    customer: String,
    #[serde(serialize_with = "hypaxel::records::excel_date")]
    date: chrono::NaiveDate,
    amount: f64,
}

fn order(id: u32) -> Order {
    Order {
        id,
        customer: format!("Customer {}", id % 97),
        date: chrono::NaiveDate::from_ymd_opt(2024, 1, 1).unwrap() + chrono::Days::new((id % 365) as u64),
        amount: id as f64 * 1.25,
    }
}

fn app() -> Router {
    Router::new()
        .route("/records", get(|| async { Xlsx::from_records("Orders", (1..=20_000).map(order)).filename("orders") }))
        .route(
            "/report",
            get(|| async {
                Xlsx::from_fn(|w| {
                    let bold = w.add_style(&Style::new().bold());
                    let mut s = w.add_worksheet("Summary")?;
                    s.add_row((Styled("Region", bold), Styled("Revenue", bold)))?;
                    s.add_row(("North", 100.0))?;
                    s.add_row(("South", 250.0))?;
                    s.add_row(("Total", Formula("SUM(B2:B3)")))?;
                    Ok(())
                })
                .filename("Bericht März.xlsx")
            }),
        )
        .route(
            "/stream",
            get(|| async {
                let rows = futures_util::stream::iter((1..=10_000).map(|i| Ok::<_, std::io::Error>(order(i))));
                Xlsx::from_stream("Streamed", rows)
            }),
        )
        .route(
            "/async",
            get(|| async {
                Xlsx::from_async_fn(|mut w| async move {
                    let mut s = w.add_worksheet("Async")?;
                    for i in 0..1_000u32 {
                        tokio::task::yield_now().await;
                        s.add_row_async((i, i * 2)).await?;
                    }
                    Ok(w)
                })
            }),
        )
        .route(
            "/panics",
            get(|| async {
                Xlsx::from_fn(|w| {
                    let mut s = w.add_worksheet("Panics")?;
                    for i in 0..50_000u32 {
                        s.add_row((i, "row"))?;
                    }
                    panic!("bug in the export code");
                })
            }),
        )
        .route(
            "/fails",
            get(|| async {
                let rows = futures_util::stream::iter(vec![Ok(order(1)), Err(std::io::Error::other("db down"))]);
                Xlsx::from_stream("Broken", rows)
            }),
        )
}

async fn get_body(path: &str) -> (axum::http::response::Parts, Result<Vec<u8>, String>) {
    let response = app().oneshot(Request::get(path).body(Body::empty()).unwrap()).await.unwrap();
    let (parts, body) = response.into_parts();
    let bytes = body.collect().await.map(|b| b.to_bytes().to_vec()).map_err(|e| e.to_string());
    (parts, bytes)
}

fn open(bytes: Vec<u8>) -> XlsxFile<Cursor<Vec<u8>>> {
    open_workbook_from_rs(Cursor::new(bytes)).unwrap()
}

#[tokio::test]
async fn records_endpoint() {
    let (parts, body) = get_body("/records").await;
    assert_eq!(parts.status, StatusCode::OK);
    assert_eq!(parts.headers[header::CONTENT_TYPE], hypaxel::CONTENT_TYPE);
    assert_eq!(parts.headers[header::CONTENT_DISPOSITION], "attachment; filename=\"orders.xlsx\"; filename*=UTF-8''orders.xlsx");

    let mut book = open(body.unwrap());
    let range = book.worksheet_range("Orders").unwrap();
    assert_eq!(range.height(), 20_001);
    assert_eq!(range.get_value((0, 0)), Some(&Data::String("id".into())));
    assert_eq!(range.get_value((20_000, 0)), Some(&Data::Float(20_000.0)));
    assert!(matches!(range.get_value((1, 2)), Some(Data::DateTime(_))));
}

#[tokio::test]
async fn report_endpoint() {
    let (parts, body) = get_body("/report").await;
    assert!(parts.headers[header::CONTENT_DISPOSITION].to_str().unwrap().contains("filename*=UTF-8''Bericht%20M%C3%A4rz.xlsx"));
    let mut book = open(body.unwrap());
    let formulas = book.worksheet_formula("Summary").unwrap();
    assert_eq!(formulas.get_value((3, 1)).map(String::as_str), Some("SUM(B2:B3)"));
}

#[tokio::test]
async fn stream_and_async_endpoints() {
    let (_, body) = get_body("/stream").await;
    let mut book = open(body.unwrap());
    assert_eq!(book.worksheet_range("Streamed").unwrap().height(), 10_001);

    let (_, body) = get_body("/async").await;
    let mut book = open(body.unwrap());
    let range = book.worksheet_range("Async").unwrap();
    assert_eq!(range.get_value((999, 1)), Some(&Data::Float(1998.0)));
}

#[tokio::test]
async fn producer_errors_abort_the_body() {
    let (parts, body) = get_body("/fails").await;
    assert_eq!(parts.status, StatusCode::OK); // headers were already sent
    let err = body.unwrap_err();
    assert!(err.contains("db down"), "{err}");
}

#[tokio::test]
async fn producer_panics_abort_the_body() {
    let (_, body) = get_body("/panics").await;
    let err = body.unwrap_err();
    assert!(err.contains("xlsx producer failed"), "{err}");
}

#[tokio::test]
async fn client_disconnect_stops_the_producer() {
    // Reports whether the producer stopped because of a broken pipe.
    let (finished_tx, finished_rx) = tokio::sync::oneshot::channel::<bool>();
    let finished_tx = std::sync::Mutex::new(Some(finished_tx));
    let xlsx = Xlsx::from_fn(move |w| {
        let mut s = w.add_worksheet("Endless")?;
        let result = (0..u32::MAX).try_for_each(|i| s.add_row((i, "some text to make the rows bigger", i as f64 * 0.1)));
        let broken_pipe = matches!(&result, Err(hypaxel::Error::Io(e)) if e.kind() == std::io::ErrorKind::BrokenPipe);
        let _ = finished_tx.lock().unwrap().take().unwrap().send(broken_pipe);
        result
    });
    let response = axum::response::IntoResponse::into_response(xlsx);
    let mut body = response.into_body();
    // Read a little, then hang up.
    let first = body.frame().await.unwrap().unwrap();
    assert!(!first.into_data().unwrap().is_empty());
    drop(body);
    let broken_pipe = tokio::time::timeout(std::time::Duration::from_secs(10), finished_rx).await.unwrap().unwrap();
    assert!(broken_pipe);
}
