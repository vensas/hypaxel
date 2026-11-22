#![cfg(feature = "chrono")]

use std::io::{Cursor, Write};

use chrono::{NaiveDate, NaiveTime, TimeDelta};
use hypaxel::*;

fn write_with(options: Options, f: impl FnOnce(&mut XlsxWriter<Vec<u8>>) -> Result<()>) -> Vec<u8> {
    let mut writer = XlsxWriter::with_options(Vec::new(), options);
    f(&mut writer).unwrap();
    writer.finish().unwrap()
}

fn write(f: impl FnOnce(&mut XlsxWriter<Vec<u8>>) -> Result<()>) -> Vec<u8> {
    write_with(Options::default(), f)
}

fn reader(bytes: &[u8]) -> XlsxReader<Cursor<&[u8]>> {
    XlsxReader::new(Cursor::new(bytes)).unwrap()
}

/// Every row of a sheet as `(row number, cells as text)`, dates in ISO 8601.
fn dump(bytes: &[u8], sheet: &str) -> Vec<(u32, Vec<String>)> {
    let mut reader = reader(bytes);
    let mut rows = reader.worksheet(sheet).unwrap();
    let mut out = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        out.push((row.number(), row.values().map(|v| v.to_string()).collect()));
    }
    out
}

/// Builds a package from raw parts.
fn package(parts: &[(&str, &str)]) -> Vec<u8> {
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    for (name, content) in parts {
        zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(content.as_bytes()).unwrap();
    }
    zip.finish().unwrap().into_inner()
}

const ROOT_RELS: &str = r#"<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"#;

fn single_sheet(sheet_xml: &str, extra_rels: &str, extra_parts: &[(&str, &str)]) -> Vec<u8> {
    let workbook = r#"<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"#;
    let rels = format!(
        r#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>{extra_rels}</Relationships>"#
    );
    let mut parts = vec![
        ("_rels/.rels", ROOT_RELS),
        ("xl/workbook.xml", workbook),
        ("xl/_rels/workbook.xml.rels", rels.as_str()),
        ("xl/worksheets/sheet1.xml", sheet_xml),
    ];
    parts.extend_from_slice(extra_parts);
    package(&parts)
}

#[test]
fn reads_back_every_value_type_in_both_string_modes() {
    for storage in [StringStorage::Inline, StringStorage::Shared] {
        let dt = NaiveDate::from_ymd_opt(2024, 3, 15).unwrap().and_hms_opt(13, 45, 30).unwrap();
        let bytes = write_with(Options::new().string_storage(storage), |w| {
            let mut s = w.add_worksheet("Data")?;
            s.add_row(("text", 42, -1_234_567_890_123_i64, 3.25, true, false))?;
            s.add_row((dt, dt.date(), NaiveTime::from_hms_opt(6, 0, 0).unwrap(), TimeDelta::hours(36)))?;
            s.add_row((f64::NAN, None::<i32>, "after-gap", "<&>\"' _x0041_ \u{1}\ttab\nline 東京 😀"))?;
            s.add_row((Formula("SUM(B1:D1)"), Blank, ""))?;
            Ok(())
        });

        let mut reader = reader(&bytes);
        let mut rows = reader.worksheet("Data").unwrap();

        let row = rows.next_row().unwrap().unwrap();
        assert_eq!(row.number(), 1);
        assert_eq!(row.get(1), Value::String("text"));
        assert_eq!(row.get(2), Value::Number(42.0));
        assert_eq!(row.get(3), Value::Number(-1_234_567_890_123.0));
        assert_eq!(row.get(4), Value::Number(3.25));
        assert_eq!(row.get(5), Value::Bool(true));
        assert_eq!(row.get(6), Value::Bool(false));
        assert_eq!(row.get(7), Value::Empty);

        let row = rows.next_row().unwrap().unwrap();
        let date = |c| row.get(c).as_datetime().unwrap();
        assert_eq!(date(1).kind(), DateKind::DateTime);
        assert_eq!(date(1).to_naive_datetime(), Some(dt));
        assert_eq!(date(2).kind(), DateKind::Date);
        assert_eq!(date(2).to_naive_date(), Some(dt.date()));
        assert_eq!(date(3).kind(), DateKind::Time);
        assert_eq!(date(3).to_naive_time(), NaiveTime::from_hms_opt(6, 0, 0));
        assert_eq!(date(4).kind(), DateKind::Duration);
        assert_eq!(date(4).to_duration(), Some(TimeDelta::hours(36)));

        let row = rows.next_row().unwrap().unwrap();
        assert_eq!(row.get(1), Value::Error("#NUM!"));
        assert_eq!(row.get(2), Value::Empty);
        assert_eq!(row.get(3), Value::String("after-gap"));
        assert_eq!(row.get(4), Value::String("<&>\"' _x0041_ \u{1}\ttab\nline 東京 😀"));
        assert_eq!(row.cells().map(|c| c.column()).collect::<Vec<_>>(), [1, 3, 4]);

        // A formula without a cached value and a styled blank have no value; "" is a string.
        let row = rows.next_row().unwrap().unwrap();
        assert_eq!(row.number(), 4);
        assert_eq!(row.cells().map(|c| (c.column(), c.value())).collect::<Vec<_>>(), [(3, Value::String(""))]);

        assert!(rows.next_row().unwrap().is_none());
        assert!(rows.next_row().unwrap().is_none());
    }
}

#[test]
fn round_trips_dates_in_both_date_systems() {
    let dates = [
        NaiveDate::from_ymd_opt(1900, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(1900, 2, 28).unwrap(),
        NaiveDate::from_ymd_opt(1900, 3, 1).unwrap(),
        NaiveDate::from_ymd_opt(1904, 1, 1).unwrap(),
        NaiveDate::from_ymd_opt(1999, 12, 31).unwrap(),
        NaiveDate::from_ymd_opt(2024, 2, 29).unwrap(),
        NaiveDate::from_ymd_opt(9999, 12, 31).unwrap(),
    ];
    for date_1904 in [false, true] {
        let bytes = write_with(Options::new().date_1904(date_1904), |w| {
            let mut s = w.add_worksheet("D")?;
            for d in dates {
                s.add_row((d, d.and_hms_milli_opt(23, 59, 59, 123).unwrap()))?;
            }
            Ok(())
        });
        let mut reader = reader(&bytes);
        assert_eq!(reader.is_1904(), date_1904);
        let mut rows = reader.worksheet_at(0).unwrap();
        for d in dates {
            let row = rows.next_row().unwrap().unwrap();
            if date_1904 && d.year_ce().1 < 1904 {
                assert_eq!(row.get(1), Value::String(&d.to_string()), "dates before 1904 are written as text");
                continue;
            }
            assert_eq!(row.get(1).as_datetime().map(|v| v.to_naive_date()), Some(Some(d)), "{d} 1904={date_1904} {:?}", row.get(1));
            assert_eq!(row.get(2).as_datetime().unwrap().to_naive_datetime(), d.and_hms_milli_opt(23, 59, 59, 123));
        }
    }
}

use chrono::Datelike;

#[test]
fn lists_sheets_with_visibility_and_reads_each() {
    let bytes = write(|w| {
        w.add_worksheet("First")?.add_row(("a", 1))?;
        w.add_worksheet_with("Secret", &SheetOptions::new().hidden())?.add_row(("b", 2))?;
        w.add_worksheet("Q&A <1>")?.add_row(("c", 3))?;
        Ok(())
    });
    let mut reader = reader(&bytes);
    assert_eq!(reader.sheet_names().collect::<Vec<_>>(), ["First", "Secret", "Q&A <1>"]);
    assert_eq!(reader.sheets()[1].visibility(), Visibility::Hidden);
    assert_eq!(reader.sheets()[0].kind(), SheetKind::Worksheet);

    assert_eq!(dump(&bytes, "q&a <1>"), [(1, vec!["c".to_string(), "3".to_string()])]);
    assert_eq!(dump(&bytes, "Secret"), [(1, vec!["b".to_string(), "2".to_string()])]);
    assert!(matches!(reader.worksheet("Missing"), Err(Error::SheetNotFound(_))));
    assert!(matches!(reader.worksheet_at(3), Err(Error::SheetNotFound(_))));

    // The borrowed and the owning variants read the same rows.
    let mut owned = XlsxReader::new(Cursor::new(bytes.clone())).unwrap().into_worksheet("First").unwrap();
    assert_eq!(owned.next_row().unwrap().unwrap().get(2), Value::Number(1.0));
}

#[test]
fn skips_missing_and_valueless_rows_and_reports_row_numbers() {
    let bytes = write(|w| {
        let mut s = w.add_worksheet("S")?;
        s.add_row(("first",))?;
        s.begin_row_at(5, Some(30.0), StyleId::DEFAULT)?.end_row()?;
        s.begin_row_at(1_000, None, StyleId::DEFAULT)?.skip_cells(2).write("C1000").end_row()?;
        s.begin_row_at(MAX_ROWS, None, StyleId::DEFAULT)?.skip_cells(MAX_COLUMNS - 1).write("last").end_row()?;
        Ok(())
    });
    let mut reader = reader(&bytes);
    let mut rows = reader.worksheet("S").unwrap();
    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        seen.push((row.number(), row.last_column(), row.values().count()));
    }
    assert_eq!(seen, [(1, 1, 1), (1_000, 3, 3), (MAX_ROWS, MAX_COLUMNS, MAX_COLUMNS as usize)]);
}

#[test]
fn reads_workbooks_written_by_rust_xlsxwriter() {
    let mut workbook = rust_xlsxwriter::Workbook::new();
    let date = rust_xlsxwriter::Format::new().set_num_format("dd/mm/yyyy hh:mm");
    let sheet = workbook.add_worksheet().set_name("Report").unwrap();
    sheet.write_string(0, 0, "Name").unwrap();
    sheet.write_string(0, 1, "Amount").unwrap();
    sheet.write_string(1, 0, "Widget").unwrap();
    sheet.write_number(1, 1, 12.5).unwrap();
    sheet.write_boolean(2, 3, true).unwrap();
    let dt = NaiveDate::from_ymd_opt(2024, 5, 1).unwrap().and_hms_opt(8, 30, 0).unwrap();
    sheet.write_datetime_with_format(3, 0, dt, &date).unwrap();
    sheet.write_formula(4, 0, rust_xlsxwriter::Formula::new("=1+1").set_result("2")).unwrap();
    sheet.write_string(5, 2, "Widget").unwrap();
    let bytes = workbook.save_to_buffer().unwrap();

    assert_eq!(
        dump(&bytes, "Report"),
        [
            (1, vec!["Name".into(), "Amount".into()]),
            (2, vec!["Widget".into(), "12.5".into()]),
            (3, vec!["".into(), "".into(), "".into(), "TRUE".into()]),
            (4, vec!["2024-05-01T08:30:00".into()]),
            (5, vec!["2".into()]),
            (6, vec!["".into(), "".into(), "Widget".into()]),
        ]
    );
}

#[test]
fn handles_xml_variants_from_other_producers() {
    // Prefixed namespaces, no cell or row references, out-of-order and duplicate cells, inline
    // rich text with phonetic runs, ISO dates, CDATA, comments, entities and CRLF line endings.
    let sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n<x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\
        <x:dimension ref=\"A1:C3\"/><x:sheetViews><x:sheetView workbookViewId=\"0\"/></x:sheetViews>\
        <x:sheetData>\
          <x:row><x:c><x:v>1</x:v></x:c><x:c t=\"str\"><x:f>\"a\"&amp;\"b\"</x:f><x:v>ab</x:v></x:c><x:c t=\"b\"><x:v>0</x:v></x:c></x:row>\
          <x:row r=\"3\" spans=\"1:3\"><x:c r=\"C3\"><x:v>3</x:v></x:c><x:c r=\"A3\"><x:v>1</x:v></x:c><x:c r=\"C3\"><x:v>4</x:v></x:c></x:row>\
          <!-- a comment --><x:row><x:c t=\"inlineStr\"><x:is><x:r><x:t>Tok</x:t></x:r><x:r><x:rPr><x:b/></x:rPr><x:t xml:space=\"preserve\">yo &amp; </x:t></x:r><x:rPh><x:t>トウキョウ</x:t></x:rPh></x:is></x:c>\
            <x:c t=\"d\"><x:v>2024-05-01T08:30:00</x:v></x:c><x:c t=\"inlineStr\"><x:is><x:t><![CDATA[<cdata> & _x0041_]]></x:t></x:is></x:c>\
            <x:c t=\"inlineStr\"><x:is><x:t>line1\r\nline2</x:t></x:is></x:c><x:c t=\"e\"><x:v>#N/A</x:v></x:c><x:c t=\"n\"><x:v> 1.5E3 </x:v></x:c></x:row>\
        </x:sheetData><x:mergeCells count=\"0\"/></x:worksheet>";
    let bytes = single_sheet(sheet, "", &[]);
    assert_eq!(
        dump(&bytes, "S"),
        [
            (1, vec!["1".into(), "ab".into(), "FALSE".into()]),
            (3, vec!["1".into(), "".into(), "4".into()]),
            (
                4,
                vec![
                    "Tokyo & ".into(),
                    "2024-05-01T08:30:00".into(),
                    "<cdata> & A".into(),
                    "line1\nline2".into(),
                    "#N/A".into(),
                    "1500".into()
                ]
            ),
        ]
    );
}

#[test]
fn applies_custom_and_builtin_date_formats() {
    let styles = r#"<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="200" formatCode="[$-409]d\-mmm\-yy;@"/></numFmts><cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="200"/><xf numFmtId="14"/><xf numFmtId="4"/></cellXfs></styleSheet>"#;
    let sheet = r#"<worksheet><sheetData><row r="1"><c r="A1" s="1"><v>45413</v></c><c r="B1" s="2"><v>45413</v></c><c r="C1" s="3"><v>45413</v></c><c r="D1" s="99"><v>1</v></c></row></sheetData></worksheet>"#;
    let rels = r#"<Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="/xl/styles.xml"/>"#;
    let bytes = single_sheet(sheet, rels, &[("xl/styles.xml", styles)]);
    assert_eq!(dump(&bytes, "S"), [(1, vec!["2024-05-01".into(), "2024-05-01".into(), "45413".into(), "1".into()])]);
}

#[test]
fn accepts_strict_open_xml_and_unusual_part_names() {
    let root = r#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument" Target="/Book/WB.xml"/></Relationships>"#;
    let workbook = r#"<workbook xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main" xmlns:r="http://purl.oclc.org/ooxml/officeDocument/relationships"><workbookPr date1904="true"/><sheets><sheet name="Strict" sheetId="1" state="veryHidden" r:id="rIdA"/></sheets></workbook>"#;
    let rels = r#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdA" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/worksheet" Target="sheets/../sheets/One.xml"/><Relationship Id="rIdS" Type="http://purl.oclc.org/ooxml/officeDocument/relationships/sharedStrings" Target="strings.xml"/><Relationship Id="rIdX" Type="http://x/hyperlink" Target="https://example.com" TargetMode="External"/></Relationships>"#;
    let sst = r#"<sst xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main"><si><t>strict</t></si></sst>"#;
    let sheet = r#"<worksheet xmlns="http://purl.oclc.org/ooxml/spreadsheetml/main"><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="d"><v>1904-01-02</v></c></row></sheetData></worksheet>"#;
    let bytes = package(&[
        ("_rels/.rels", root),
        ("Book/WB.xml", workbook),
        ("Book/_rels/WB.xml.rels", rels),
        ("Book/sheets/One.xml", sheet),
        ("Book/strings.xml", sst),
    ]);
    let mut reader = reader(&bytes);
    assert!(reader.is_1904());
    assert_eq!(reader.sheets()[0].visibility(), Visibility::VeryHidden);
    let mut rows = reader.worksheet("Strict").unwrap();
    let row = rows.next_row().unwrap().unwrap();
    assert_eq!(row.get(1), Value::String("strict"));
    assert_eq!(row.get(2).as_datetime().unwrap().to_naive_date(), NaiveDate::from_ymd_opt(1904, 1, 2));
}

#[test]
fn reports_clear_errors_for_wrong_inputs() {
    let err = XlsxReader::new(Cursor::new(b"id,name\n1,Alice\n".to_vec())).unwrap_err();
    assert!(matches!(err, Error::InvalidFile(_)), "{err}");

    let docx =
        package(&[("_rels/.rels", ROOT_RELS.replace("xl/workbook.xml", "word/document.xml").as_str()), ("word/document.xml", "<w/>")]);
    let err = XlsxReader::new(Cursor::new(docx)).unwrap_err();
    assert!(err.to_string().contains("Word or PowerPoint"), "{err}");

    let bad_sst = single_sheet(r#"<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>7</v></c></row></sheetData></worksheet>"#, "", &[]);
    let mut reader = reader(&bad_sst);
    let err = reader.worksheet("S").unwrap().next_row().unwrap_err();
    assert!(matches!(&err, Error::Malformed { part, .. } if part == "xl/worksheets/sheet1.xml"), "{err}");

    let bad_number = single_sheet(r#"<worksheet><sheetData><row r="1"><c r="A1"><v>1,5</v></c></row></sheetData></worksheet>"#, "", &[]);
    let err = reader_rows_error(&bad_number);
    assert!(err.contains("invalid number \"1,5\""), "{err}");

    let doctype = single_sheet(r#"<!DOCTYPE lol [<!ENTITY a "aaaa">]><worksheet><sheetData/></worksheet>"#, "", &[]);
    assert!(reader_rows_error(&doctype).contains("document type"));

    let truncated = single_sheet(r#"<worksheet><sheetData><row r="1"><c r="A1"><v>1</v></c>"#, "", &[]);
    assert!(reader_rows_error(&truncated).contains("unexpected end"));
}

fn reader_rows_error(bytes: &[u8]) -> String {
    let mut reader = reader(bytes);
    let mut rows = reader.worksheet("S").unwrap();
    loop {
        match rows.next_row() {
            Ok(Some(_)) => continue,
            Ok(None) => panic!("expected an error"),
            Err(e) => return e.to_string(),
        }
    }
}

#[test]
fn enforces_the_shared_string_limit() {
    let bytes = write_with(Options::new().string_storage(StringStorage::Shared), |w| {
        let mut s = w.add_worksheet("S")?;
        for i in 0..1_000 {
            s.add_row((format!("unique string number {i}"),))?;
        }
        Ok(())
    });
    let mut reader = XlsxReader::with_options(Cursor::new(&bytes[..]), &ReadOptions::new().max_shared_strings_bytes(1_000)).unwrap();
    assert!(matches!(reader.worksheet("S"), Err(Error::LimitExceeded(_))));
    let mut reader = XlsxReader::new(Cursor::new(&bytes[..])).unwrap();
    reader.worksheet("S").unwrap();
    assert!(reader.shared_strings_memory() > 20_000);
}

#[test]
fn shares_the_parsed_workbook_across_sources() {
    let bytes = write_with(Options::new().string_storage(StringStorage::Shared), |w| {
        w.add_worksheet("A")?.add_row(("in a",))?;
        w.add_worksheet("B")?.add_row(("in b",))?;
        Ok(())
    });
    let mut first = XlsxReader::new(Cursor::new(bytes.clone())).unwrap();
    let second = first.with_source(Cursor::new(bytes.clone())).unwrap();
    let handle = std::thread::spawn(move || {
        let mut rows = second.into_worksheet("B").unwrap();
        rows.next_row().unwrap().unwrap().get(1).to_string()
    });
    let mut rows = first.worksheet("A").unwrap();
    assert_eq!(rows.next_row().unwrap().unwrap().get(1), Value::String("in a"));
    assert_eq!(handle.join().unwrap(), "in b");
}

#[test]
fn streams_large_sheets_with_bounded_buffers() {
    let bytes = write(|w| {
        let mut s = w.add_worksheet("Big")?;
        for i in 0..200_000u32 {
            s.add_row((i, "constant text", i as f64 * 0.5, i % 2 == 0))?;
        }
        Ok(())
    });
    let mut reader = reader(&bytes);
    let mut rows = reader.worksheet("Big").unwrap();
    let mut count = 0u32;
    let mut sum = 0.0;
    while let Some(row) = rows.next_row().unwrap() {
        assert_eq!(row.number(), count + 1);
        sum += row.get(3).as_f64().unwrap();
        count += 1;
    }
    assert_eq!(count, 200_000);
    assert_eq!(sum, (0..200_000u32).map(|i| i as f64 * 0.5).sum::<f64>());
}

#[test]
fn damaged_files_fail_without_panicking() {
    let bytes = write_with(Options::new().string_storage(StringStorage::Shared).compression_level(0), |w| {
        let date = w.add_style(&Style::new().num_format("yyyy-mm-dd"));
        let mut s = w.add_worksheet("S")?;
        for i in 0..50 {
            s.add_row((format!("s{i}"), i, Styled(45_000 + i, date), i % 3 == 0))?;
        }
        Ok(())
    });
    // Deterministic pseudo-random mutations of an uncompressed package, so that the XML parser
    // (not only the ZIP CRC check) sees damaged input.
    let mut state = 0x2545_f491_4f6c_dd1du64;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    for _ in 0..2_000 {
        let mut damaged = bytes.clone();
        for _ in 0..1 + next() % 4 {
            let at = (next() % damaged.len() as u64) as usize;
            damaged[at] = next() as u8;
        }
        if next() % 4 == 0 {
            damaged.truncate((next() % damaged.len() as u64) as usize);
        }
        let Ok(mut reader) = XlsxReader::new(Cursor::new(&damaged[..])) else { continue };
        let Ok(mut rows) = reader.worksheet_at(0) else { continue };
        while let Ok(Some(row)) = rows.next_row() {
            for cell in row.cells() {
                let _ = cell.value().to_string();
            }
        }
    }
}

#[test]
fn reads_back_tables_rows_and_layout_written_by_the_writer() {
    let full = Table::new(1, 1, 4, 3)
        .name("Orders")
        .columns(["Product", "Units & more", "Line\nbreak"])
        .style(TableStyle::Dark(3))
        .row_stripes(false)
        .column_stripes(true)
        .highlight_first_column(true)
        .highlight_last_column(true);
    let plain = Table::new(10, 1, 12, 2).header_row(false).autofilter(false).style(TableStyle::None);
    let bytes = write(|w| {
        let options = SheetOptions::new()
            .column_widths(&[12.5, 30.0])
            .column(4, Column { hidden: true, ..Column::width(5.0) })
            .row_outline_levels(2)
            .outline_summary_above();
        let mut s = w.add_worksheet_with("Data", &options)?;
        s.add_row(("Product", "Units & more", "Line\nbreak"))?;
        s.begin_row_with(&RowOptions::new().outline_level(1))?.write("Widget").write(3).end_row()?;
        s.begin_row_with(&RowOptions::new().outline_level(2).hidden())?.write("Gadget").write(5).end_row()?;
        s.begin_row_with(&RowOptions::new().outline_level(1).collapsed().height(20.5))?.write("Gizmo").end_row()?;
        s.begin_row_at(10, Some(33.0), StyleId::default())?.write(1).write(2).end_row()?;
        s.add_row((3, 4))?;
        s.add_row((5, 6))?;
        s.add_table(&full)?;
        s.add_table(&plain)?;
        let mut fitted = w.add_worksheet_with("Fitted", &SheetOptions::new().autofit_columns())?;
        fitted.add_row(("a rather long header", 1))?;
        Ok(())
    });
    let mut reader = reader(&bytes);

    let tables = reader.tables("data").unwrap();
    assert_eq!(tables, [full, Table { name: Some("Table2".into()), columns: vec!["Column1".into(), "Column2".into()], ..plain }]);
    assert!(reader.tables_at(1).unwrap().is_empty());
    assert!(matches!(reader.tables("Missing"), Err(Error::SheetNotFound(_))));
    assert!(matches!(reader.tables_at(2), Err(Error::SheetNotFound(_))));

    let mut rows = reader.worksheet("Data").unwrap();
    let layout = rows.layout().unwrap().clone();
    assert!(layout.outline_summary_above());
    assert_eq!(layout.row_outline_levels(), 2);
    let widths: Vec<_> =
        layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap(), c.is_hidden())).collect();
    assert_eq!(widths, [(1, 1, 12.5, false), (2, 2, 30.0, false), (4, 4, 5.0, true)]);
    assert_eq!((layout.column_width(2), layout.column_width(3)), (Some(30.0), None));
    assert_eq!((layout.is_column_hidden(2), layout.is_column_hidden(3), layout.is_column_hidden(4)), (false, false, true));

    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        seen.push((row.number(), row.outline_level(), row.is_hidden(), row.is_collapsed(), row.height()));
    }
    assert_eq!(
        seen,
        [
            (1, 0, false, false, None),
            (2, 1, false, false, None),
            (3, 2, true, false, None),
            (4, 1, false, true, Some(20.5)),
            (10, 0, false, false, Some(33.0)),
            (11, 0, false, false, None),
            (12, 0, false, false, None),
        ]
    );
    assert_eq!(rows.layout().unwrap(), &layout);

    let mut fitted = reader.worksheet("Fitted").unwrap();
    let layout = fitted.layout().unwrap();
    assert!(!layout.outline_summary_above());
    assert_eq!(layout.row_outline_levels(), 0);
    assert!(layout.column_width(1).unwrap() > layout.column_width(2).unwrap());
    assert_eq!(fitted.next_row().unwrap().unwrap().get(2), Value::Number(1.0));
}

const TABLE_REL: &str = r#"<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table1.xml"/>"#;

fn sheet_with_table(table_xml: &str) -> Vec<u8> {
    let rels =
        format!(r#"<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">{TABLE_REL}</Relationships>"#);
    single_sheet(
        r#"<worksheet><sheetData/></worksheet>"#,
        "",
        &[("xl/worksheets/_rels/sheet1.xml.rels", rels.as_str()), ("xl/tables/table1.xml", table_xml)],
    )
}

#[test]
fn reads_tables_and_layout_from_other_producers() {
    let bytes = sheet_with_table(
        r#"<?xml version="1.0"?><x:table xmlns:x="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="7" name="Internal" displayName="Sales_2026" ref="$B$2:$D$9" totalsRowCount="1"><x:autoFilter ref="B2:D8"><x:filterColumn colId="0"/></x:autoFilter><x:tableColumns count="3"><x:tableColumn id="1" name="Region &amp; area"/><x:tableColumn id="2" name="Net_x000A_total"><x:calculatedColumnFormula>1+1</x:calculatedColumnFormula></x:tableColumn><x:tableColumn id="3" name="Σ"/></x:tableColumns><x:tableStyleInfo name="My Corporate Style" showFirstColumn="true" showLastColumn="0" showRowStripes="1" showColumnStripes="false"/></x:table>"#,
    );
    let tables = reader(&bytes).tables_at(0).unwrap();
    let expected = Table::new(2, 2, 9, 4)
        .name("Sales_2026")
        .columns(["Region & area", "Net\ntotal", "Σ"])
        .style(TableStyle::Custom("My Corporate Style".into()))
        .highlight_first_column(true);
    assert_eq!(tables, [Table { totals_row: true, ..expected }]);

    let bare = sheet_with_table(
        r#"<table name="OnlyName" ref="A1" headerRowCount="0"><tableColumns><tableColumn name="x"/></tableColumns></table>"#,
    );
    let tables = reader(&bare).tables("S").unwrap();
    let expected = Table::new(1, 1, 1, 1)
        .name("OnlyName")
        .columns(["x"])
        .style(TableStyle::None)
        .header_row(false)
        .autofilter(false)
        .row_stripes(false);
    assert_eq!(tables, [expected]);

    let sheet = single_sheet(
        r#"<worksheet><sheetPr><tabColor rgb="FF00FF00"/><outlinePr summaryBelow="false" summaryRight="0"/><pageSetUpPr fitToPage="1"/></sheetPr><dimension ref="A1:B3"/><sheetViews><sheetView workbookViewId="0"><selection activeCell="A1"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight="15" outlineLevelRow="9" x14ac:dyDescent="0.25"/><cols><col min="1" max="1" width="8.5" customWidth="1"/><col min="3" max="16384" width="4" hidden="1"/><col min="2" max="2" style="1"/><col min="0" max="3" width="1"/><col min="5" max="4" width="1"/><col min="2" max="2" width="-1"/></cols><sheetData><row r="1" spans="1:2" ht="18.75" x14ac:dyDescent="0.25"><c r="A1"><v>1</v></c></row><row r="2" outlineLevel="9" hidden="true" collapsed="1" customHeight="1"><c r="A2"><v>2</v></c></row><row r="3" outlineLevel="x" hidden="0" ht="abc"><c r="A3"><v>3</v></c></row></sheetData></worksheet>"#,
        "",
        &[],
    );
    let mut reader = reader(&sheet);
    assert!(reader.tables("S").unwrap().is_empty());
    let mut rows = reader.worksheet("S").unwrap();
    let layout = rows.layout().unwrap();
    assert!(layout.outline_summary_above());
    assert_eq!(layout.row_outline_levels(), 7);
    let widths: Vec<_> = layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap())).collect();
    assert_eq!(widths, [(1, 1, 8.5), (3, 16_384, 4.0)]);
    assert_eq!((layout.column_width(1), layout.column_width(2), layout.column_width(9_000)), (Some(8.5), None, Some(4.0)));
    assert_eq!((layout.is_column_hidden(1), layout.is_column_hidden(9_000)), (false, true));
    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        seen.push((row.number(), row.outline_level(), row.is_hidden(), row.is_collapsed(), row.height()));
    }
    assert_eq!(seen, [(1, 0, false, false, Some(18.75)), (2, 7, true, true, None), (3, 0, false, false, None)]);
}

#[test]
fn rejects_damaged_table_parts() {
    for (table_xml, expected) in [
        (r#"<worksheet/>"#, "not a table"),
        (r#""#, "not a table"),
        (r#"<table name="T"/>"#, "no valid cell range"),
        (r#"<table name="T" ref="C3:A1"/>"#, "no valid cell range"),
        (r#"<table name="T" ref="A1:B2"><tableColumns>"#, "unexpected end"),
        (r#"<!DOCTYPE t [<!ENTITY a "b">]><table name="T" ref="A1:B2"/>"#, "document type"),
    ] {
        let err = reader(&sheet_with_table(table_xml)).tables_at(0).unwrap_err();
        assert!(matches!(&err, Error::Malformed { part, .. } if part == "xl/tables/table1.xml"), "{err}");
        assert!(err.to_string().contains(expected), "{err}");
    }

    let columns = r#"<tableColumn name="c"/>"#.repeat(16_385);
    let wide = sheet_with_table(&format!(r#"<table name="T" ref="A1:B2"><tableColumns>{columns}</tableColumns></table>"#));
    assert!(reader(&wide).tables_at(0).unwrap_err().to_string().contains("more than 16384 columns"));

    let rels = format!(r#"<Relationships>{}</Relationships>"#, TABLE_REL.repeat(4_097));
    let many = single_sheet(
        r#"<worksheet><sheetData/></worksheet>"#,
        "",
        &[("xl/worksheets/_rels/sheet1.xml.rels", rels.as_str()), ("xl/tables/table1.xml", r#"<table name="T" ref="A1"/>"#)],
    );
    assert!(matches!(reader(&many).tables_at(0), Err(Error::LimitExceeded(_))));

    let dangling = single_sheet(
        r#"<worksheet><sheetData/></worksheet>"#,
        "",
        &[("xl/worksheets/_rels/sheet1.xml.rels", format!("<Relationships>{TABLE_REL}</Relationships>").as_str())],
    );
    let mut reader = reader(&dangling);
    assert!(matches!(reader.tables_at(0), Err(Error::Malformed { .. })));
    assert!(reader.worksheet_at(0).unwrap().next_row().unwrap().is_none());

    let cols = r#"<col min="1" max="1" width="1"/>"#.repeat(16_385);
    let sheet = single_sheet(&format!(r#"<worksheet><cols>{cols}</cols><sheetData/></worksheet>"#), "", &[]);
    assert!(reader_rows_error_at_layout(&sheet).contains("more than 16384 column ranges"));
}

fn reader_rows_error_at_layout(bytes: &[u8]) -> String {
    let mut reader = reader(bytes);
    let mut rows = reader.worksheet("S").unwrap();
    rows.layout().unwrap_err().to_string()
}

#[test]
fn returns_rows_without_values_on_request() {
    let bytes = write(|w| {
        let mut s = w.add_worksheet_with("Data", &SheetOptions::new().row_outline_levels(1))?;
        s.add_row(("first",))?;
        s.begin_row_with(&RowOptions::new().height(40.0))?.end_row()?;
        s.begin_row_at(5, None, StyleId::default())?.write(5).end_row()?;
        s.begin_row_with(&RowOptions::new().outline_level(1).hidden())?.end_row()?;
        Ok(())
    });
    let props = |options: &ReadOptions| {
        let mut reader = XlsxReader::with_options(Cursor::new(&bytes[..]), options).unwrap();
        let mut rows = reader.worksheet_at(0).unwrap();
        let mut seen = Vec::new();
        while let Some(row) = rows.next_row().unwrap() {
            assert_eq!(row.is_empty(), row.cells().len() == 0);
            assert_eq!(row.values().count(), row.last_column() as usize);
            seen.push((row.number(), row.len(), row.outline_level(), row.is_hidden(), row.height()));
        }
        seen
    };
    assert_eq!(props(&ReadOptions::new()), [(1, 1, 0, false, None), (5, 1, 0, false, None)]);
    assert_eq!(
        props(&ReadOptions::new().include_empty_rows(true)),
        [(1, 1, 0, false, None), (2, 0, 0, false, Some(40.0)), (5, 1, 0, false, None), (6, 0, 1, true, None)]
    );

    let other = single_sheet(
        r#"<worksheet><sheetData><row r="2" ht="30"/><row r="3" hidden="1"><c r="A3" s="1"/></row><row><c><v>1</v></c></row></sheetData></worksheet>"#,
        "",
        &[],
    );
    let mut reader = XlsxReader::with_options(Cursor::new(other), &ReadOptions::new().include_empty_rows(true)).unwrap();
    let mut rows = reader.worksheet("S").unwrap();
    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        seen.push((row.number(), row.len(), row.is_hidden(), row.height()));
    }
    assert_eq!(seen, [(2, 0, false, Some(30.0)), (3, 0, true, None), (4, 1, false, None)]);
}

/// `testdata/reader/excel.xlsx` is a workbook written by Hypaxel and saved again by Excel for Mac.
#[test]
fn reads_tables_rows_and_layout_saved_by_excel() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/reader/excel.xlsx");
    let mut reader = XlsxReader::open_with(path, &ReadOptions::new().include_empty_rows(true)).unwrap();
    assert_eq!(reader.sheet_names().collect::<Vec<_>>(), ["Orders", "Plain"]);

    let orders = Table::new(1, 1, 4, 3)
        .name("Orders")
        .columns(["Product", "Units & more", "Price"])
        .style(TableStyle::Dark(3))
        .row_stripes(false)
        .column_stripes(true)
        .highlight_first_column(true)
        .highlight_last_column(true);
    let plain =
        Table::new(7, 1, 8, 2).name("Table2").columns(["Column1", "Column2"]).style(TableStyle::None).header_row(false).autofilter(false);
    assert_eq!(reader.tables("Orders").unwrap(), [orders, plain]);
    assert!(reader.tables("Plain").unwrap().is_empty());

    let mut rows = reader.worksheet("Orders").unwrap();
    let layout = rows.layout().unwrap();
    assert!(layout.outline_summary_above());
    assert_eq!(layout.row_outline_levels(), 2);
    let widths: Vec<_> =
        layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap(), c.is_hidden())).collect();
    assert_eq!(widths, [(1, 1, 14.0, false), (2, 2, 22.5, false), (4, 4, 6.0, true)]);

    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        seen.push((row.number(), row.len(), row.outline_level(), row.is_hidden(), row.is_collapsed(), row.height()));
    }
    assert_eq!(
        seen,
        [
            (1, 3, 0, false, false, None),
            (2, 3, 1, false, true, None),
            (3, 3, 2, true, false, None),
            (4, 3, 1, false, true, Some(30.0)),
            (6, 0, 0, false, false, Some(45.0)),
            (7, 2, 0, false, false, None),
            (8, 2, 0, false, false, None),
        ]
    );
}

/// `testdata/reader/libreoffice.xlsx` is the same workbook saved by LibreOffice Calc, which drops
/// table styles and stores a height on every row.
#[test]
fn reads_tables_rows_and_layout_saved_by_libreoffice() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/reader/libreoffice.xlsx");
    let mut reader = XlsxReader::open_with(path, &ReadOptions::new().include_empty_rows(true)).unwrap();

    let unstyled = |t: Table| t.style(TableStyle::None).row_stripes(false);
    let orders = unstyled(Table::new(1, 1, 4, 3).name("Orders").columns(["Product", "Units & more", "Price"]));
    let plain = unstyled(Table::new(7, 1, 8, 2).name("Table2").columns(["Column1", "Column2"]).header_row(false).autofilter(false));
    assert_eq!(reader.tables("Orders").unwrap(), [orders, plain]);
    assert!(reader.tables("Plain").unwrap().is_empty());

    let mut rows = reader.worksheet("Orders").unwrap();
    let layout = rows.layout().unwrap();
    assert!(layout.outline_summary_above());
    assert_eq!(layout.row_outline_levels(), 2);
    let widths: Vec<_> =
        layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap(), c.is_hidden())).collect();
    assert_eq!(widths, [(1, 1, 14.0, false), (2, 2, 22.5, false), (4, 4, 6.0, true)]);

    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        seen.push((row.number(), row.len(), row.outline_level(), row.is_hidden(), row.is_collapsed(), row.height()));
    }
    assert_eq!(
        seen,
        [
            (1, 3, 0, false, false, Some(15.0)),
            (2, 3, 1, false, false, Some(15.0)),
            (3, 3, 2, true, false, Some(15.0)),
            (4, 3, 1, false, true, Some(30.0)),
            (6, 0, 0, false, false, Some(45.0)),
            (7, 2, 0, false, false, Some(15.0)),
            (8, 2, 0, false, false, Some(15.0)),
        ]
    );
}

/// `testdata/reader/excel-totals.xlsx` is an inventory list saved by Excel for Mac: one table
/// over 1,000 rows with a totals row.
#[test]
fn reads_a_table_with_a_totals_row_saved_by_excel() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/reader/excel-totals.xlsx");
    let mut reader = XlsxReader::open(path).unwrap();
    assert_eq!(reader.sheet_names().collect::<Vec<_>>(), ["Inventory"]);

    let columns = ["SKU", "Item", "Group", "Vendor", "Cost", "Price", "Opening", "Sold", "Closing", "Reorder", "Status"];
    let expected = Table { totals_row: true, ..Table::new(1, 1, 1002, 11).name("Table1").columns(columns) };
    assert_eq!(reader.tables_at(0).unwrap(), [expected]);

    let mut rows = reader.worksheet_at(0).unwrap();
    let layout = rows.layout().unwrap();
    assert!(!layout.outline_summary_above());
    let widths: Vec<_> =
        layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap(), c.is_hidden())).collect();
    assert_eq!(widths.len(), 11);
    assert_eq!((widths[0], widths[10]), ((1, 1, 11.6640625, false), (11, 11, 13.1640625, false)));

    let mut count = 0;
    let mut totals = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        count += 1;
        assert_eq!((row.outline_level(), row.is_hidden(), row.height()), (0, false, None));
        if row.number() == 1002 {
            totals = row.cells().map(|c| (c.column(), c.value().to_string())).collect();
        }
    }
    assert_eq!(count, 1002);
    let totals: Vec<_> = totals.iter().map(|(column, value)| (*column, value.as_str())).collect();
    assert_eq!(totals, [(1, "Total"), (5, "21.085"), (6, "33.736"), (8, "99696"), (10, "34500"), (11, "1000")]);
}

#[test]
fn bounds_the_memory_of_a_sheets_tables() {
    let table = r#"<table name="T" ref="A1:B2"><tableColumns><tableColumn name="a"/><tableColumn name="b"/></tableColumns></table>"#;
    let rels = format!("<Relationships>{}</Relationships>", TABLE_REL.repeat(3));
    let repeated = single_sheet(
        "<worksheet><sheetData/></worksheet>",
        "",
        &[("xl/worksheets/_rels/sheet1.xml.rels", rels.as_str()), ("xl/tables/table1.xml", table)],
    );
    assert_eq!(reader(&repeated).tables_at(0).unwrap().len(), 1, "a part referenced three times is read once");

    let column = format!(r#"<tableColumn name="{}"/>"#, "n".repeat(4200));
    let heavy = sheet_with_table(&format!(r#"<table name="T" ref="A1:B2"><tableColumns>{}</tableColumns></table>"#, column.repeat(16_384)));
    let err = reader(&heavy).tables_at(0).unwrap_err();
    assert!(matches!(err, Error::LimitExceeded(_)), "{err}");

    let external = r#"<Relationship Id="x" Type="t/hyperlink" Target="https://example.com" TargetMode="External"/>"#;
    let rels = format!("<Relationships>{}{}</Relationships>", external.repeat(70_000), TABLE_REL);
    let links = single_sheet(
        "<worksheet><sheetData/></worksheet>",
        "",
        &[("xl/worksheets/_rels/sheet1.xml.rels", rels.as_str()), ("xl/tables/table1.xml", table)],
    );
    assert_eq!(reader(&links).tables_at(0).unwrap().len(), 1, "external links do not count");
    let internal = r#"<Relationship Id="x" Type="t/other" Target="other.xml"/>"#;
    let rels = format!("<Relationships>{}</Relationships>", internal.repeat(65_537));
    let flood = single_sheet("<worksheet><sheetData/></worksheet>", "", &[("xl/worksheets/_rels/sheet1.xml.rels", rels.as_str())]);
    assert!(matches!(reader(&flood).tables_at(0), Err(Error::LimitExceeded(_))));

    let signed = single_sheet(
        r#"<worksheet><sheetFormatPr outlineLevelRow="+2"/><cols><col min="+1" max="2" width="5"/></cols><sheetData><row r="1" outlineLevel="+3"><c><v>1</v></c></row></sheetData></worksheet>"#,
        "",
        &[],
    );
    let mut reader = reader(&signed);
    let mut rows = reader.worksheet_at(0).unwrap();
    assert_eq!((rows.layout().unwrap().row_outline_levels(), rows.layout().unwrap().column_widths().len()), (0, 0));
    assert_eq!(rows.next_row().unwrap().unwrap().outline_level(), 0);
}

/// `testdata/reader/google-sheets.xlsx` is a table downloaded from Google Sheets: a custom table
/// style, no filter buttons, a totals row and a height on every row but the first.
#[test]
fn reads_a_table_downloaded_from_google_sheets() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/reader/google-sheets.xlsx");
    let mut reader = XlsxReader::open(path).unwrap();
    assert_eq!(reader.sheet_names().collect::<Vec<_>>(), ["Inventory"]);

    let columns = ["SKU", "Item", "Group", "Vendor", "Cost", "Price", "Opening", "Sold", "Closing", "Reorder", "Status"];
    let expected = Table::new(1, 1, 903, 11)
        .name("Table1")
        .columns(columns)
        .style(TableStyle::Custom("Inventory-style".into()))
        .autofilter(false)
        .highlight_first_column(true)
        .highlight_last_column(true);
    assert_eq!(reader.tables_at(0).unwrap(), [Table { totals_row: true, ..expected }]);

    let mut rows = reader.worksheet_at(0).unwrap();
    let layout = rows.layout().unwrap();
    assert!(!layout.outline_summary_above());
    let widths: Vec<_> =
        layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap(), c.is_hidden())).collect();
    assert_eq!(widths.len(), 12);
    assert_eq!((widths[0], widths[11]), ((1, 1, 11.63, false), (12, 26, 8.88, false)));
    assert_eq!(layout.column_width(20), Some(8.88));

    let mut count = 0;
    let mut totals = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        count += 1;
        assert_eq!((row.outline_level(), row.is_hidden()), (0, false));
        assert_eq!(row.height(), if row.number() == 1 { None } else { Some(15.75) });
        if row.number() == 2 {
            assert_eq!(row.get(7), Value::Number(250.0));
        }
        if row.number() == 903 {
            totals = row.cells().map(|c| (c.column(), c.value().to_string())).collect();
        }
    }
    assert_eq!(count, 903);
    let totals: Vec<_> = totals.iter().map(|(column, value)| (*column, value.as_str())).collect();
    assert_eq!(totals, [(1, "Total"), (5, "21.20421754"), (6, "33.92674806"), (8, "90058"), (10, "31060"), (11, "901")]);
}

/// A stored (uncompressed) package whose CRC-32 of `xl/worksheets/sheet1.xml` is wrong.
fn with_bad_sheet_crc(bytes: Vec<u8>) -> Vec<u8> {
    let mut bytes = bytes;
    let name = b"xl/worksheets/sheet1.xml";
    let mut patched = 0;
    for i in 0..bytes.len().saturating_sub(4) {
        let signature = &bytes[i..i + 4];
        let (crc_at, name_at) = match signature {
            b"PK\x03\x04" => (i + 14, i + 30),
            b"PK\x01\x02" => (i + 16, i + 46),
            _ => continue,
        };
        if bytes.get(name_at..name_at + name.len()) == Some(&name[..]) {
            bytes[crc_at] ^= 0xFF;
            patched += 1;
        }
    }
    assert_eq!(patched, 2, "local and central header");
    bytes
}

fn stored_single_sheet(sheet_xml: &str) -> Vec<u8> {
    let workbook = r#"<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>"#;
    let rels = r#"<Relationships><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"#;
    let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let stored = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for (name, content) in [
        ("_rels/.rels", ROOT_RELS),
        ("xl/workbook.xml", workbook),
        ("xl/_rels/workbook.xml.rels", rels),
        ("xl/worksheets/sheet1.xml", sheet_xml),
    ] {
        zip.start_file(name, stored).unwrap();
        zip.write_all(content.as_bytes()).unwrap();
    }
    zip.finish().unwrap().into_inner()
}

#[test]
fn verifies_the_whole_sheet_and_remembers_layout_failures() {
    let trailer = r#"<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>"#;
    for sheet in [
        format!(r#"<worksheet><cols><col min="1" max="1" width="9"/></cols><sheetData/>{trailer}</worksheet>"#),
        format!(r#"<worksheet><sheetData><row r="1"><c><v>1</v></c></row></sheetData>{trailer}</worksheet>"#),
    ] {
        let good = stored_single_sheet(&sheet);
        let mut book = reader(&good);
        let mut rows = book.worksheet_at(0).unwrap();
        rows.layout().unwrap();
        while rows.next_row().unwrap().is_some() {}

        let bad = with_bad_sheet_crc(good);
        let mut book = reader(&bad);
        let mut rows = book.worksheet_at(0).unwrap();
        let error = match rows.layout() {
            Err(e) => e,
            Ok(_) => loop {
                match rows.next_row() {
                    Ok(Some(_)) => {}
                    Ok(None) => panic!("the damaged entry was accepted: {sheet}"),
                    Err(e) => break e,
                }
            },
        };
        assert!(error.to_string().contains("CRC-32"), "{error}");
    }

    let empty = with_bad_sheet_crc(stored_single_sheet(&format!("<worksheet><sheetData/>{trailer}</worksheet>")));
    let mut book = reader(&empty);
    let mut rows = book.worksheet_at(0).unwrap();
    for _ in 0..2 {
        assert!(rows.layout().unwrap_err().to_string().contains("CRC-32"));
    }
    assert!(rows.next_row().unwrap_err().to_string().contains("CRC-32"));

    let cols = r#"<col min="1" max="1" width="1"/>"#.repeat(16_385);
    let wide =
        single_sheet(&format!(r#"<worksheet><cols>{cols}</cols><sheetData><row><c><v>1</v></c></row></sheetData></worksheet>"#), "", &[]);
    let mut book = reader(&wide);
    let mut rows = book.worksheet_at(0).unwrap();
    for _ in 0..2 {
        assert!(matches!(rows.layout(), Err(Error::Malformed { .. })), "the failure is reported every time");
    }
    assert!(matches!(rows.next_row(), Err(Error::Malformed { .. })));
}

/// `testdata/reader/numbers.xlsx` is exported by Apple Numbers: its table is plain cells, not an
/// Excel table, and an empty first row holds the chart.
#[test]
fn reads_a_sheet_exported_by_numbers() {
    let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/reader/numbers.xlsx");
    let mut reader = XlsxReader::open_with(path, &ReadOptions::new().include_empty_rows(true)).unwrap();
    assert_eq!(reader.sheet_names().collect::<Vec<_>>(), ["Table and Chart"]);
    assert!(reader.tables_at(0).unwrap().is_empty());

    let mut rows = reader.worksheet_at(0).unwrap();
    let layout = rows.layout().unwrap();
    let widths: Vec<_> =
        layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width().unwrap(), c.is_hidden())).collect();
    assert_eq!(widths, [(1, 1, 24.6719, false), (2, 2, 18.3672, false), (3, 5, 18.7188, false), (6, 16_384, 27.75, false)]);
    assert_eq!((layout.outline_summary_above(), layout.row_outline_levels()), (false, 0));

    let mut seen = Vec::new();
    while let Some(row) = rows.next_row().unwrap() {
        let values: Vec<String> = row.values().map(|v| v.to_string()).collect();
        seen.push((row.number(), row.height(), values));
    }
    let text = |values: &[&str]| values.iter().map(|v| v.to_string()).collect::<Vec<_>>();
    assert_eq!(
        seen,
        [
            (1, Some(311.6), text(&[])),
            (2, Some(27.65), text(&["Table 1"])),
            (3, Some(20.25), text(&["", "Category A", "Category B"])),
            (4, Some(20.25), text(&["Item 1", "5", "7"])),
            (5, Some(20.05), text(&["Item 2", "10", "8"])),
            (6, Some(20.05), text(&["Item 3", "9", "15"])),
            (7, Some(20.05), text(&["Item 4", "7", "12"])),
            (8, Some(20.05), text(&["Item 5", "16", "21"])),
        ]
    );

    let mut reader = XlsxReader::open(path).unwrap();
    let mut rows = reader.worksheet_at(0).unwrap();
    assert_eq!(rows.next_row().unwrap().unwrap().number(), 2, "without the option the chart row is skipped");
}

#[test]
fn table_names_that_look_like_excel_escapes_round_trip() {
    let table = Table::new(1, 1, 2, 1).name("T_x0041_").columns(["a_x0042_"]);
    let bytes = write(|w| {
        let mut s = w.add_worksheet("S")?;
        s.add_row(("a_x0042_",))?;
        s.add_row((1,))?;
        s.add_table(&table)?;
        Ok(())
    });
    assert_eq!(reader(&bytes).tables_at(0).unwrap(), [table]);

    // As Excel saves a table it was told to name `T_x0041_`: lower-case hex digits.
    let excel = sheet_with_table(
        r#"<table name="T_x005f_x0041_" displayName="T_x005f_x0041_" ref="A1:A2"><tableColumns><tableColumn name="a"/></tableColumns></table>"#,
    );
    assert_eq!(reader(&excel).tables_at(0).unwrap()[0].name.as_deref(), Some("T_x0041_"));
}

#[test]
fn reads_hidden_columns_without_a_width() {
    let sheet = single_sheet(
        r#"<worksheet><cols><col min="1" max="1" width="9"/><col min="2" max="3" hidden="1"/><col min="4" max="4" style="2"/><col min="5" max="5" hidden="0"/></cols><sheetData/></worksheet>"#,
        "",
        &[],
    );
    let mut reader = reader(&sheet);
    let mut rows = reader.worksheet_at(0).unwrap();
    let layout = rows.layout().unwrap();
    let columns: Vec<_> = layout.column_widths().iter().map(|c| (c.first_column(), c.last_column(), c.width(), c.is_hidden())).collect();
    assert_eq!(columns, [(1, 1, Some(9.0), false), (2, 3, None, true)]);
    assert_eq!((layout.column_width(2), layout.is_column_hidden(2), layout.is_column_hidden(3)), (None, true, true));
    assert_eq!((layout.is_column_hidden(4), layout.is_column_hidden(5)), (false, false));
}

#[test]
fn round_trips_custom_table_styles() {
    let custom = Table::new(1, 1, 2, 1).name("Branded").columns(["a"]).style(TableStyle::Custom("Brand & Co_x0041_".into()));
    let lookalike = Table::new(1, 3, 2, 3).name("Lookalike").columns(["b"]).style(TableStyle::Custom("TableStyleMedium9".into()));
    let bytes = write(|w| {
        let mut s = w.add_worksheet("S")?;
        s.add_row(("a", "", "b"))?;
        s.add_row(("1", "", "2"))?;
        s.add_table(&custom)?;
        s.add_table(&lookalike)?;
        Ok(())
    });
    let tables = reader(&bytes).tables_at(0).unwrap();
    assert_eq!(tables, [custom, lookalike.style(TableStyle::Medium(9))]);

    for bad in ["", "line\nbreak", "tab\there", "nul\u{0}", &"x".repeat(256)] {
        let table = Table::new(1, 1, 2, 1).columns(["a"]).style(TableStyle::Custom(bad.to_owned()));
        let mut writer = XlsxWriter::new(Vec::<u8>::new());
        let mut sheet = writer.add_worksheet("S").unwrap();
        assert!(matches!(sheet.add_table(&table), Err(Error::InvalidArgument(_))), "{bad:?}");
    }
}

#[test]
fn applies_configured_limits() {
    let open = |bytes: &[u8], options: ReadOptions| XlsxReader::with_options(Cursor::new(bytes.to_vec()), &options).unwrap();
    let table = r#"<table name="T" ref="A1:B2"><tableColumns><tableColumn name="a"/><tableColumn name="b"/></tableColumns><tableStyleInfo name="A rather long custom style"/></table>"#;
    let rels =
        format!("<Relationships>{TABLE_REL}{}</Relationships>", TABLE_REL.replace("rId1", "rId2").replace("table1.xml", "table2.xml"));
    let two = single_sheet(
        "<worksheet><sheetData/></worksheet>",
        "",
        &[("xl/worksheets/_rels/sheet1.xml.rels", rels.as_str()), ("xl/tables/table1.xml", table), ("xl/tables/table2.xml", table)],
    );
    assert_eq!(open(&two, ReadOptions::new()).tables_at(0).unwrap().len(), 2);
    let err = open(&two, ReadOptions::new().max_tables(1)).tables_at(0).unwrap_err();
    assert!(matches!(&err, Error::LimitExceeded(m) if m.contains("max_tables")), "{err}");
    assert_eq!(open(&two, ReadOptions::new().max_table_bytes(2 * (1 + 2 * 33 + 26))).tables_at(0).unwrap().len(), 2);
    let err = open(&two, ReadOptions::new().max_table_bytes(2 * (1 + 2 * 33 + 26) - 1)).tables_at(0).unwrap_err();
    assert!(matches!(&err, Error::LimitExceeded(m) if m.contains("max_table_bytes")), "{err}");

    let built_in = sheet_with_table(
        r#"<table name="T" ref="A1:B2"><tableColumns><tableColumn name="a"/><tableColumn name="b"/></tableColumns><tableStyleInfo name="TableStyleMedium2"/></table>"#,
    );
    assert_eq!(open(&built_in, ReadOptions::new().max_table_bytes(1 + 2 * 33)).tables_at(0).unwrap()[0].style, TableStyle::Medium(2));
    let err = open(&built_in, ReadOptions::new().max_table_bytes(2 * 33)).tables_at(0).unwrap_err();
    assert!(matches!(&err, Error::LimitExceeded(m) if m.contains("max_table_bytes")), "{err}");

    let text = "x".repeat(100_000);
    let sheet = single_sheet(
        &format!(
            r#"<worksheet><sheetData><row r="1"><c t="inlineStr"><is><t>{text}</t></is></c><c t="inlineStr"><is><t>{text}</t></is></c></row></sheetData></worksheet>"#
        ),
        "",
        &[],
    );
    let first_row = |options: ReadOptions| open(&sheet, options).worksheet_at(0).unwrap().next_row().map(|r| r.is_some());
    assert!(first_row(ReadOptions::new()).unwrap());
    let err = first_row(ReadOptions::new().max_token_bytes(1)).unwrap_err();
    assert!(matches!(&err, Error::LimitExceeded(m) if m.contains("max_token_bytes") && m.contains("65536")), "{err}");
    assert!(first_row(ReadOptions::new().max_row_text_bytes(200_000)).unwrap());
    let err = first_row(ReadOptions::new().max_row_text_bytes(199_999)).unwrap_err();
    assert!(matches!(&err, Error::LimitExceeded(m) if m.contains("max_row_text_bytes")), "{err}");
}
