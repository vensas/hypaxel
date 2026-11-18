#![cfg(feature = "chrono")]

mod common;

use calamine::Data;
use chrono::{NaiveDate, NaiveTime, TimeDelta};
use common::{Book, num, text};
use hypaxel::*;

fn write(f: impl FnOnce(&mut XlsxWriter<Vec<u8>>) -> Result<()>) -> Book {
    write_with(Options::default(), f)
}

fn write_with(options: Options, f: impl FnOnce(&mut XlsxWriter<Vec<u8>>) -> Result<()>) -> Book {
    let mut writer = XlsxWriter::with_options(Vec::new(), options);
    f(&mut writer).unwrap();
    Book::new(writer.finish().unwrap())
}

#[test]
fn empty_workbook_gets_a_default_sheet() {
    let mut book = write(|_| Ok(()));
    assert_eq!(book.sheet_names(), ["Sheet1"]);
    assert_eq!(book.rows("Sheet1"), 0);
}

#[test]
fn writes_all_value_types_in_both_string_modes() {
    for storage in [StringStorage::Inline, StringStorage::Shared] {
        let dt = NaiveDate::from_ymd_opt(2024, 3, 15).unwrap().and_hms_opt(13, 45, 30).unwrap();
        let mut book = write_with(Options::new().string_storage(storage), |w| {
            let mut s = w.add_worksheet("Data")?;
            s.add_row(("text", 42, 1_234_567_890_123_i64, 3.25, 1.5f32, true, false, u64::MAX))?;
            s.add_row((dt, dt.date(), NaiveTime::from_hms_opt(6, 0, 0).unwrap(), TimeDelta::hours(36), Formula("=SUM(B1:D1)")))?;
            s.add_row((f64::NAN, f64::INFINITY, None::<i32>, "after-gap", 'x'))?;
            Ok(())
        });

        assert_eq!(text(&book.value("Data", 1, 1)), "text");
        assert_eq!(num(&book.value("Data", 1, 2)), 42.0);
        assert_eq!(num(&book.value("Data", 1, 3)), 1_234_567_890_123.0);
        assert_eq!(num(&book.value("Data", 1, 4)), 3.25);
        assert_eq!(num(&book.value("Data", 1, 5)), 1.5);
        assert_eq!(book.value("Data", 1, 6), Data::Bool(true));
        assert_eq!(book.value("Data", 1, 7), Data::Bool(false));
        assert_eq!(num(&book.value("Data", 1, 8)), u64::MAX as f64);

        let Data::DateTime(v) = book.value("Data", 2, 1) else { panic!("expected a date") };
        assert!((v.as_f64() - 45366.573263888888).abs() < 1e-9, "{}", v.as_f64());
        assert!(matches!(book.value("Data", 2, 2), Data::DateTime(d) if d.as_f64() == 45366.0));
        assert!(matches!(book.value("Data", 2, 3), Data::DateTime(d) if d.as_f64() == 0.25));
        assert!(matches!(book.value("Data", 2, 4), Data::DateTime(d) if d.as_f64() == 1.5));
        assert_eq!(book.formula("Data", 2, 5), "SUM(B1:D1)");

        assert!(matches!(book.value("Data", 3, 1), Data::Error(_)));
        assert!(matches!(book.value("Data", 3, 2), Data::Error(_)));
        assert_eq!(book.value("Data", 3, 3), Data::Empty);
        assert_eq!(text(&book.value("Data", 3, 4)), "after-gap");
        assert_eq!(text(&book.value("Data", 3, 5)), "x");
    }
}

#[test]
fn escapes_special_characters() {
    let tricky = "a & b < c > d \"q\" 'single' \t tab\nnewline \u{1}ctrl ünïcødé 😀 snake_case";
    let mut book = write(|w| {
        let mut s = w.add_worksheet("Esc & <Sheet>")?;
        s.add_row((tricky, "  padded  ", "", "_x0041_"))
    });
    assert_eq!(book.sheet_names(), ["Esc & <Sheet>"]);
    // calamine decodes _xHHHH_ escapes, so the original text round-trips.
    assert_eq!(text(&book.value("Esc & <Sheet>", 1, 1)), tricky);
    assert_eq!(text(&book.value("Esc & <Sheet>", 1, 2)), "  padded  ");
    assert_eq!(text(&book.value("Esc & <Sheet>", 1, 4)), "_x0041_");
}

#[test]
fn shared_strings_are_deduplicated() {
    let book = write_with(Options::new().string_storage(StringStorage::Shared), |w| {
        let mut s = w.add_worksheet("S")?;
        for i in 0..100 {
            s.add_row(("same", if i % 2 == 0 { "even" } else { "odd" }))?;
        }
        Ok(())
    });
    let sst = book.part("xl/sharedStrings.xml");
    assert!(sst.contains("count=\"200\" uniqueCount=\"3\""), "{sst}");
}

#[test]
fn styles_are_registered_and_deduplicated() {
    let mut ids = Vec::new();
    let book = write(|w| {
        let make = || {
            Style::new()
                .num_format("#,##0.000")
                .bold()
                .italic()
                .font_color(Color::hex("#FF0000").unwrap())
                .font_size(14.0)
                .font_name("Arial")
                .fill(Color::YELLOW)
                .border(Border::all(BorderStyle::Thin, Some(Color::BLACK)))
                .align(HAlign::Center)
                .valign(VAlign::Center)
                .wrap()
        };
        let a = w.add_style(&make());
        let b = w.add_style(&make());
        let bold = w.add_style(&Style::new().bold());
        ids.extend([a, b]);
        let date = NaiveDate::from_ymd_opt(2020, 1, 1).unwrap();
        let mut s = w.add_worksheet("S")?;
        s.write_styled(1.5, a).write_styled(date, a).write_styled(date, bold).end_row()
    });
    assert_eq!(ids[0], ids[1]);
    let styles = book.part("xl/styles.xml");
    assert!(styles.contains("<numFmt numFmtId=\"164\" formatCode=\"#,##0.000\"/>"));
    assert!(styles.contains("<b/><i/><sz val=\"14\"/><color rgb=\"FFFF0000\"/><name val=\"Arial\"/>"));
    assert!(styles.contains("<alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/>"));
    // A bold date gets a derived format: bold font + built-in short date (14).
    assert!(styles.contains("<xf numFmtId=\"14\" fontId=\"2\""), "{styles}");
    let sheet = book.part("xl/worksheets/sheet1.xml");
    assert!(sheet.contains("<c s=\"1\"><v>1.5</v></c><c s=\"1\"><v>43831</v></c><c s=\"3\"><v>43831</v></c>"), "{sheet}");
}

#[test]
fn sheet_options_and_ranges() {
    let mut book = write(|w| {
        let options = SheetOptions::new()
            .freeze_rows(1)
            .freeze_columns(2)
            .hide_grid_lines()
            .tab_color(Color::BLUE)
            .column_widths(&[10.0, 20.0, 30.0])
            .column(5, Column { hidden: true, ..Default::default() });
        let mut s = w.add_worksheet_with("Opts", &options)?;
        s.begin_row(Some(30.0), StyleId::DEFAULT)?;
        s.write("Header").end_row()?;
        s.merge_cells(2, 1, 3, 2)?;
        s.autofilter(1, 1, 5, 3)?;
        s.skip_rows(3);
        s.add_row(("row 5",))?;
        w.add_worksheet_with("Hidden", &SheetOptions::new().hidden())?;
        Ok(())
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    assert!(xml.contains("<pane xSplit=\"2\" ySplit=\"1\" topLeftCell=\"C2\" activePane=\"bottomRight\" state=\"frozen\"/>"), "{xml}");
    assert!(xml.contains("showGridLines=\"0\""));
    assert!(xml.contains("<tabColor rgb=\"FF0000FF\"/>"));
    assert!(xml.contains("<col min=\"5\" max=\"5\" width=\"9.140625\" hidden=\"1\"/>"));
    assert!(xml.contains("<row r=\"1\" ht=\"30\" customHeight=\"1\">"));
    assert!(xml.contains("<autoFilter ref=\"A1:C5\"/>"));
    assert!(xml.contains("<mergeCells count=\"1\"><mergeCell ref=\"A2:B3\"/></mergeCells>"));
    assert_eq!(text(&book.value("Opts", 5, 1)), "row 5");
    assert_eq!(book.sheet_names(), ["Opts", "Hidden"]);
    let workbook = book.part("xl/workbook.xml");
    assert!(workbook.contains("<sheet name=\"Hidden\" sheetId=\"2\" state=\"hidden\" r:id=\"rId2\"/>"));
    assert!(workbook.contains("'Opts'!$A$1:$C$5"));
}

#[test]
fn gaps_get_explicit_references() {
    let mut book = write(|w| {
        let mut s = w.add_worksheet("S")?;
        s.write("a").skip_cells(2).write("d").write("e").end_row()
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    assert!(xml.contains("<row r=\"1\"><c t=\"inlineStr\"><is><t>a</t></is></c><c r=\"D1\" t=\"inlineStr\">"), "{xml}");
    assert_eq!(text(&book.value("S", 1, 4)), "d");
    assert_eq!(text(&book.value("S", 1, 5)), "e");

    let book = write_with(Options::new().cell_references(true), |w| w.add_worksheet("S")?.add_row(("a", "b")));
    assert!(book.part("xl/worksheets/sheet1.xml").contains("<c r=\"A1\" t=\"inlineStr\"><is><t>a</t></is></c><c r=\"B1\""));
}

#[test]
fn multiple_sheets() {
    let mut book = write(|w| {
        w.add_worksheet("One")?.add_row((1,))?;
        w.add_worksheet("Two")?.add_row((2,))?;
        Ok(())
    });
    assert_eq!(num(&book.value("One", 1, 1)), 1.0);
    assert_eq!(num(&book.value("Two", 1, 1)), 2.0);
}

#[test]
fn invalid_sheet_names_are_rejected() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    for name in ["", "a/b", "[x]", "'quoted'", "This name is definitely longer than 31 characters", "history"] {
        assert!(matches!(w.add_worksheet(name), Err(Error::InvalidSheetName(_))), "{name}");
    }
    w.add_worksheet("Data").unwrap();
    assert!(matches!(w.add_worksheet("DATA"), Err(Error::DuplicateSheetName(_))));
}

#[test]
fn row_errors() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let mut s = w.add_worksheet("S").unwrap();
    s.begin_row_at(5, None, StyleId::DEFAULT).unwrap();
    assert!(matches!(s.begin_row_at(5, None, StyleId::DEFAULT), Err(Error::RowOrder { row: 5, previous: 5 })));
    assert!(matches!(s.begin_row_at(0, None, StyleId::DEFAULT), Err(Error::RowOutOfRange(0))));
}

#[test]
fn cell_errors_are_deferred_and_keep_the_file_valid() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let mut s = w.add_worksheet("S").unwrap();
    let long = "x".repeat(32_768);
    s.write("ok").write(long.as_str()).write("after");
    assert!(matches!(s.end_row(), Err(Error::TextTooLong { row: 1, column: 2, len: 32_768 })));
    s.write_styled(1, StyleId::DEFAULT).end_row().unwrap();
    let mut book = Book::new(w.finish().unwrap());
    assert_eq!(text(&book.value("S", 1, 1)), "ok");
    assert_eq!(book.value("S", 1, 2), Data::Empty);
    assert_eq!(text(&book.value("S", 1, 3)), "after");
}

#[test]
fn max_columns() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let mut s = w.add_worksheet("Wide").unwrap();
    for i in 0..MAX_COLUMNS {
        s.write(i);
    }
    s.write(0);
    assert!(matches!(s.end_row(), Err(Error::TooManyColumns { row: 1 })));
    let mut book = Book::new(w.finish().unwrap());
    assert_eq!(num(&book.value("Wide", 1, MAX_COLUMNS)), 16_383.0);
}

#[test]
fn date_serials_match_excel() {
    for (y, m, d, expected) in [(1900, 1, 1, 1.0), (1900, 2, 28, 59.0), (1900, 3, 1, 61.0), (2024, 1, 1, 45292.0)] {
        let date = NaiveDate::from_ymd_opt(y, m, d).unwrap();
        let mut book = write(|w| w.add_worksheet("S")?.add_row((date,)));
        assert_eq!(num(&book.value("S", 1, 1)), expected, "{date}");
    }
}

#[test]
fn date_times_are_accurate() {
    for s in ["2024-03-15T13:45:30.123", "2024-03-15T23:59:59.999999999", "2024-03-15T00:00:00.000000001", "1999-12-31T12:00:00"] {
        let dt: chrono::NaiveDateTime = s.parse().unwrap();
        let mut book = write(|w| w.add_worksheet("S")?.add_row((dt,)));
        let Data::DateTime(v) = book.value("S", 1, 1) else { panic!() };
        let epoch = NaiveDate::from_ymd_opt(1899, 12, 30).unwrap().and_hms_opt(0, 0, 0).unwrap();
        let expected = (dt - epoch).num_nanoseconds().unwrap() as f64 / 86_400e9;
        let diff_ms = (v.as_f64() - expected).abs() * 86_400_000.0;
        assert!(diff_ms < 1.0, "{dt}: {} vs {expected}", v.as_f64());
    }
}

#[test]
fn out_of_range_dates_become_text() {
    let mut book = write(|w| w.add_worksheet("S")?.add_row((NaiveDate::from_ymd_opt(1800, 1, 1).unwrap(),)));
    assert_eq!(text(&book.value("S", 1, 1)), "1800-01-01");
}

#[test]
fn date_1904_system() {
    let book =
        write_with(Options::new().date_1904(true), |w| w.add_worksheet("S")?.add_row((NaiveDate::from_ymd_opt(1904, 1, 2).unwrap(),)));
    assert!(book.part("xl/worksheets/sheet1.xml").contains("<v>1</v>"));
    assert!(book.part("xl/workbook.xml").contains("<workbookPr date1904=\"1\"/>"));
}

#[test]
fn package_parts() {
    let book = write_with(Options::new().string_storage(StringStorage::Shared), |w| w.add_worksheet("S")?.add_row(("x",)));
    let mut names = book.part_names();
    names.sort();
    assert_eq!(
        names,
        [
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/_rels/workbook.xml.rels",
            "xl/sharedStrings.xml",
            "xl/styles.xml",
            "xl/workbook.xml",
            "xl/worksheets/sheet1.xml"
        ]
    );
}

#[test]
fn output_is_flushed_incrementally() {
    struct Counting {
        writes: usize,
        bytes: Vec<u8>,
    }
    impl std::io::Write for Counting {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.writes += 1;
            self.bytes.extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    let mut w = XlsxWriter::with_options(Counting { writes: 0, bytes: Vec::new() }, Options::new().flush_threshold(4096));
    let mut s = w.add_worksheet("Big").unwrap();
    for i in 0..50_000u32 {
        s.add_row((i, format!("row {i}"), i as f64 * 0.5, "constant text")).unwrap();
    }
    let out = w.finish().unwrap();
    assert!(out.writes > 20, "expected incremental writes, got {}", out.writes);
    let mut book = Book::new(out.bytes);
    assert_eq!(book.rows("Big"), 50_000);
    assert_eq!(text(&book.value("Big", 50_000, 2)), "row 49999");
}

#[test]
fn helpers() {
    assert_eq!(column_name(1), "A");
    assert_eq!(column_name(26), "Z");
    assert_eq!(column_name(27), "AA");
    assert_eq!(column_name(16_384), "XFD");
    assert_eq!(cell_ref(3, 2), "B3");
}

#[test]
fn rows_from_slices_vecs_and_arrays() {
    let mut book = write(|w| {
        let mut s = w.add_worksheet("S")?;
        let names = vec!["a".to_string(), "b".to_string()];
        s.add_row(&names)?;
        s.add_row([1.5, 2.5, 3.5])?;
        s.add_row(vec![Some(1), None, Some(3)])?;
        s.add_row(&[10u8, 20][..])
    });
    assert_eq!(text(&book.value("S", 1, 2)), "b");
    assert_eq!(num(&book.value("S", 2, 3)), 3.5);
    assert_eq!(book.value("S", 3, 2), Data::Empty);
    assert_eq!(num(&book.value("S", 4, 2)), 20.0);
}
