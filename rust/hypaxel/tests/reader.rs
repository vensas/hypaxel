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
