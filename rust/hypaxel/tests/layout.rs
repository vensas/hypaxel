mod common;

use common::{Book, num, text};
use hypaxel::*;

fn write(f: impl FnOnce(&mut XlsxWriter<Vec<u8>>) -> Result<()>) -> Book {
    let mut writer = XlsxWriter::new(Vec::<u8>::new());
    f(&mut writer).unwrap();
    Book::new(writer.finish().unwrap())
}

fn col_widths(sheet_xml: &str) -> Vec<(u32, f64)> {
    sheet_xml
        .split("<col ")
        .skip(1)
        .map(|c| {
            let attr = |name: &str| c.split(&format!("{name}=\"")).nth(1).unwrap().split('"').next().unwrap().to_owned();
            (attr("min").parse().unwrap(), attr("width").parse().unwrap())
        })
        .collect()
}

#[test]
fn row_outline_levels() {
    let mut book = write(|w| {
        let mut s = w.add_worksheet_with("Tree", &SheetOptions::new().row_outline_levels(2).outline_summary_above())?;
        s.add_row(("North", 300))?;
        s.begin_row_with(&RowOptions::new().outline_level(1))?.write("Store A").write(120).end_row()?;
        s.begin_row_with(&RowOptions::new().outline_level(2).hidden())?.write("Till 1").write(60).end_row()?;
        s.begin_row_with(&RowOptions::new().outline_level(1).collapsed().height(20.0))?.write("Store B").end_row()?;
        s.add_row(("South", 1))?;
        Ok(())
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    assert!(xml.contains("<sheetPr><outlinePr summaryBelow=\"0\"/></sheetPr>"), "{xml}");
    assert!(xml.contains("<sheetFormatPr defaultRowHeight=\"15\" outlineLevelRow=\"2\"/>"));
    assert!(xml.contains("<row r=\"1\">"));
    assert!(xml.contains("<row r=\"2\" outlineLevel=\"1\">"));
    assert!(xml.contains("<row r=\"3\" hidden=\"1\" outlineLevel=\"2\">"));
    assert!(xml.contains("<row r=\"4\" ht=\"20\" customHeight=\"1\" outlineLevel=\"1\" collapsed=\"1\">"));
    assert_eq!(text(&book.value("Tree", 3, 1)), "Till 1");
    assert_eq!(num(&book.value("Tree", 5, 2)), 1.0);
}

#[test]
fn outline_levels_must_be_declared() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let mut s = w.add_worksheet_with("S", &SheetOptions::new().row_outline_levels(1)).unwrap();
    assert!(s.begin_row_with(&RowOptions::new().outline_level(1)).is_ok());
    assert!(matches!(s.begin_row_with(&RowOptions::new().outline_level(2)), Err(Error::InvalidArgument(_))));
    assert!(matches!(s.begin_row_with(&RowOptions::new().outline_level(8)), Err(Error::InvalidArgument(_))));
    assert!(w.add_worksheet_with("T", &SheetOptions::new().row_outline_levels(8)).is_err());
}

#[test]
fn tables() {
    let mut book = write(|w| {
        let mut s = w.add_worksheet("Orders")?;
        s.add_row(("Product", "Units", "Line\nbreak"))?;
        for i in 0..5 {
            s.add_row(("Widget", i, 2.5))?;
        }
        let last = s.row();
        s.add_table(&Table::new(1, 1, last, 3).name("Orders").columns(["Product", "Units", "Line\nbreak"]))?;
        s.add_table(&Table::new(10, 1, 12, 2).header_row(false).style(TableStyle::None))?;

        let mut s = w.add_worksheet("Second")?;
        s.add_row(("A & B",))?;
        s.add_row((1,))?;
        s.add_table(&Table::new(1, 1, 2, 1).columns(["A & B"]).style(TableStyle::Light(1)).column_stripes(true))?;
        Ok(())
    });

    let names = book.part_names();
    for part in [
        "xl/tables/table1.xml",
        "xl/tables/table2.xml",
        "xl/tables/table3.xml",
        "xl/worksheets/_rels/sheet1.xml.rels",
        "xl/worksheets/_rels/sheet2.xml.rels",
    ] {
        assert!(names.iter().any(|n| n == part), "missing {part}");
    }

    let t1 = book.part("xl/tables/table1.xml");
    assert!(t1.contains("id=\"1\" name=\"Orders\" displayName=\"Orders\" ref=\"A1:C6\" totalsRowShown=\"0\""), "{t1}");
    assert!(t1.contains("<autoFilter ref=\"A1:C6\"/>"));
    assert!(t1.contains("<tableColumn id=\"3\" name=\"Line_x000A_break\"/>"));
    assert!(
        t1.contains("name=\"TableStyleMedium2\" showFirstColumn=\"0\" showLastColumn=\"0\" showRowStripes=\"1\" showColumnStripes=\"0\"")
    );

    let t2 = book.part("xl/tables/table2.xml");
    assert!(t2.contains("name=\"Table2\""), "{t2}");
    assert!(t2.contains("headerRowCount=\"0\""));
    assert!(!t2.contains("autoFilter"));
    assert!(t2.contains("<tableColumn id=\"1\" name=\"Column1\"/>"));
    assert!(!t2.contains("TableStyle"));

    let t3 = book.part("xl/tables/table3.xml");
    assert!(t3.contains("name=\"Table3\""));
    assert!(t3.contains("<tableColumn id=\"1\" name=\"A &amp; B\"/>"));
    assert!(t3.contains("name=\"TableStyleLight1\""));

    let sheet = book.part("xl/worksheets/sheet1.xml");
    assert!(sheet.ends_with("<tableParts count=\"2\"><tablePart r:id=\"rId1\"/><tablePart r:id=\"rId2\"/></tableParts></worksheet>"));
    let rels = book.part("xl/worksheets/_rels/sheet2.xml.rels");
    assert!(rels.contains(
        "Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/table\" Target=\"../tables/table3.xml\""
    ));
    let types = book.part("[Content_Types].xml");
    assert_eq!(types.matches("spreadsheetml.table+xml").count(), 3);
    assert_eq!(text(&book.value("Orders", 1, 1)), "Product");
}

#[test]
fn table_validation() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    let mut s = w.add_worksheet("S").unwrap();
    let t = |r2, c2| Table::new(1, 1, r2, c2);

    assert!(matches!(s.add_table(&t(3, 2)), Err(Error::InvalidArgument(_))), "header names are required");
    assert!(matches!(s.add_table(&t(1, 1).columns(["a"])), Err(Error::InvalidArgument(_))), "needs a data row");
    assert!(matches!(s.add_table(&t(3, 2).columns(["a"])), Err(Error::InvalidArgument(_))), "column count");
    assert!(matches!(s.add_table(&t(3, 2).columns(["a", "A"])), Err(Error::InvalidArgument(_))), "duplicate column");
    assert!(matches!(s.add_table(&t(3, 2).columns(["a", ""])), Err(Error::InvalidArgument(_))), "empty column");
    assert!(matches!(s.add_table(&t(3, 1).columns(["a"]).name("A1")), Err(Error::InvalidTableName(_))));
    assert!(matches!(s.add_table(&t(3, 1).columns(["a"]).name("my table")), Err(Error::InvalidTableName(_))));
    assert!(matches!(s.add_table(&t(3, 1).columns(["a"]).style(TableStyle::Medium(29))), Err(Error::InvalidArgument(_))));
    let totals = Table { totals_row: true, ..t(3, 1).columns(["a"]) };
    assert!(matches!(s.add_table(&totals), Err(Error::InvalidArgument(m)) if m.contains("totals row")));

    s.add_table(&t(3, 1).columns(["a"]).name("Data")).unwrap();
    assert!(matches!(s.add_table(&Table::new(3, 1, 5, 1).columns(["b"])), Err(Error::OverlappingRange(_))));
    assert!(matches!(s.add_table(&Table::new(10, 1, 12, 1).columns(["b"]).name("DATA")), Err(Error::DuplicateTableName(_))));
    assert!(matches!(s.merge_cells(2, 1, 2, 3), Err(Error::OverlappingRange(_))));
    assert!(matches!(s.autofilter(1, 1, 9, 1), Err(Error::OverlappingRange(_))));

    s.merge_cells(20, 1, 20, 2).unwrap();
    assert!(matches!(s.add_table(&Table::new(19, 1, 21, 1).columns(["c"])), Err(Error::OverlappingRange(_))));
    s.autofilter(30, 1, 40, 2).unwrap();
    assert!(matches!(s.add_table(&Table::new(35, 2, 36, 2).columns(["d"])), Err(Error::OverlappingRange(_))));

    // Names are unique across worksheets.
    let mut s = w.add_worksheet("T").unwrap();
    assert!(matches!(s.add_table(&t(3, 1).columns(["a"]).name("data")), Err(Error::DuplicateTableName(_))));
    s.add_table(&t(3, 1).columns(["a"])).unwrap();
    Book::new(w.finish().unwrap());
}

#[test]
fn autofit_measures_the_sample() {
    let mut book = write(|w| {
        let header = w.add_style(&Style::new().bold());
        let big = w.add_style(&Style::new().font_size(22.0));
        let money = w.add_style(&Style::new().num_format(formats::THOUSANDS2));
        let options =
            SheetOptions::new().autofit_columns().column(2, Column::width(30.0)).column(6, Column { hidden: true, ..Default::default() });
        let mut s = w.add_worksheet_with("Fit", &options)?;
        s.add_row((Styled("Name", header), "fixed", Styled(1_234_567.0, money), true, Styled("x", big)))?;
        s.add_row(("A much longer product name", "x", Styled(1.0, money), false, Styled("x", big)))?;
        s.add_row(("short\nWith a very long second line in a wrapped cell",))?;
        s.skip_cells(7).write(42).end_row()?;
        Ok(())
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    let cols = col_widths(&xml);
    let width = |c: u32| cols.iter().find(|(i, _)| *i == c).map(|(_, w)| *w);

    let name = width(1).unwrap();
    assert!((40.0..50.0).contains(&name), "longest line of column A, got {name}");
    assert_eq!(width(2), Some(30.0), "explicit widths win");
    let money = width(3).unwrap();
    assert!((9.0..13.0).contains(&money), "1,234,567.00 fits, got {money}");
    assert!((5.0..7.0).contains(&width(4).unwrap()), "FALSE");
    assert!(width(5).unwrap() > 2.0, "larger font is wider");
    assert_eq!(width(6), Some(9.140625), "hidden column without content keeps the default width");
    assert!(xml.contains("<col min=\"6\" max=\"6\" width=\"9.140625\" hidden=\"1\"/>"));
    assert!(width(7).is_none(), "skipped empty columns get no entry");
    assert!(width(8).unwrap() < 3.0);
    assert!(xml.find("<cols>").unwrap() < xml.find("<sheetData>").unwrap());
    assert_eq!(num(&book.value("Fit", 4, 8)), 42.0);
}

#[test]
fn autofit_stops_after_the_sample_and_keeps_streaming() {
    struct Counter(Vec<u8>, usize);
    impl std::io::Write for Counter {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            self.1 += 1;
            self.0.extend_from_slice(buf);
            Ok(buf.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    let mut w = XlsxWriter::with_options(Counter(Vec::new(), 0), Options::new().flush_threshold(4096));
    let mut s = w.add_worksheet_with("S", &SheetOptions::new().autofit(AutoFit::new().sample_rows(10).max_width(20.0))).unwrap();
    for i in 0..9 {
        s.add_row((format!("row {i}"), i)).unwrap();
    }
    w.flush().unwrap();
    assert!(w.get_ref().0.len() < 100, "only the ZIP entry header is written while the sample is held back");
    let mut s = w.current_worksheet().unwrap();
    s.add_row(("row 9", 9)).unwrap();
    s.add_row(("a value far wider than anything in the sample, which is not measured", 1)).unwrap();
    for i in 0..20_000 {
        s.add_row((i, i as f64 * 1.5)).unwrap();
    }
    let out = w.finish().unwrap();
    assert!(out.1 > 3, "output is still flushed incrementally after the sample");
    let book = Book::new(out.0);
    let cols = col_widths(&book.part("xl/worksheets/sheet1.xml"));
    assert_eq!(cols.len(), 2);
    assert!(cols[0].1 < 10.0, "rows after the sample are not measured: {cols:?}");
}

#[test]
fn autofit_respects_max_width_and_short_sheets() {
    let book = write(|w| {
        let mut s = w.add_worksheet_with("S", &SheetOptions::new().autofit(AutoFit::new().max_width(15.0)))?;
        s.add_row(("x".repeat(500),))?;
        w.add_worksheet_with("Empty", &SheetOptions::new().autofit_columns())?;
        Ok(())
    });
    assert_eq!(col_widths(&book.part("xl/worksheets/sheet1.xml")), [(1, 15.0)]);
    assert!(!book.part("xl/worksheets/sheet2.xml").contains("<cols>"));
}

#[test]
fn autofit_validation() {
    let mut w = XlsxWriter::new(Vec::<u8>::new());
    assert!(w.add_worksheet_with("A", &SheetOptions::new().autofit(AutoFit::new().sample_rows(0))).is_err());
    assert!(w.add_worksheet_with("B", &SheetOptions::new().autofit(AutoFit::new().max_width(f64::NAN))).is_err());
}

#[test]
fn everything_together_round_trips() {
    let mut book = write(|w| {
        let options = SheetOptions::new().autofit_columns().row_outline_levels(1).freeze_rows(1).tab_color(Color::BLUE);
        let mut s = w.add_worksheet_with("All", &options)?;
        s.add_row(("Region", "Revenue"))?;
        s.add_row(("North", 10))?;
        s.begin_row_with(&RowOptions::new().outline_level(1))?.write("  Store").write(4).end_row()?;
        s.add_table(&Table::new(1, 1, 3, 2).columns(["Region", "Revenue"]))?;
        Ok(())
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    let order = ["<sheetPr>", "<sheetViews>", "<sheetFormatPr", "<cols>", "<sheetData>", "<pageMargins", "<tableParts"];
    let positions: Vec<usize> = order.iter().map(|tag| xml.find(tag).unwrap_or_else(|| panic!("{tag} missing"))).collect();
    assert!(positions.windows(2).all(|p| p[0] < p[1]), "schema order: {xml}");
    assert_eq!(text(&book.value("All", 3, 1)), "  Store");
}

#[test]
fn add_row_fills_an_explicitly_begun_row() {
    let mut book = write(|w| {
        let mut s = w.add_worksheet_with("S", &SheetOptions::new().row_outline_levels(1))?;
        s.begin_row_with(&RowOptions::new().outline_level(1))?.add_row(("grouped", 1))?;
        s.add_row(("plain", 2))?;
        s.begin_row(Some(30.0), StyleId::DEFAULT)?.write("partial");
        s.add_row(("next row", 3))?;
        s.begin_row_with(&RowOptions::new().hidden())?.skip_cells(1);
        s.add_row(("after skip",))?;
        s.begin_row_with(&RowOptions::new().outline_level(1))?.end_row()?;
        s.add_row(("after empty row",))?;
        Ok(())
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    assert!(xml.contains("<row r=\"1\" outlineLevel=\"1\"><c t=\"inlineStr\"><is><t>grouped</t>"), "{xml}");
    assert!(xml.contains("<row r=\"3\" ht=\"30\" customHeight=\"1\"><c t=\"inlineStr\"><is><t>partial</t></is></c></row>"));
    assert!(xml.contains("<row r=\"5\" hidden=\"1\"></row>"));
    assert!(xml.contains("<row r=\"7\" outlineLevel=\"1\"></row>"));
    assert_eq!(text(&book.value("S", 2, 1)), "plain");
    assert_eq!(text(&book.value("S", 4, 1)), "next row");
    assert_eq!(text(&book.value("S", 6, 1)), "after skip");
    assert_eq!(text(&book.value("S", 8, 1)), "after empty row");
    assert_eq!(book.rows("S"), 8);
}

#[cfg(feature = "serde")]
#[test]
fn records_fill_an_explicitly_begun_row() {
    #[derive(serde::Serialize)]
    struct R {
        name: &'static str,
    }
    let book = write(|w| {
        let mut s = w.add_worksheet("S")?;
        s.add_row(("title",))?;
        s.begin_row(Some(25.0), StyleId::DEFAULT)?;
        s.write_records([R { name: "a" }, R { name: "b" }], &records::RecordOptions::new().autofilter(true))
    });
    let xml = book.part("xl/worksheets/sheet1.xml");
    assert!(xml.contains("<row r=\"2\" ht=\"25\" customHeight=\"1\"><c s=\"1\" t=\"inlineStr\"><is><t>name</t>"), "{xml}");
    assert!(xml.contains("<autoFilter ref=\"A2:A4\"/>"), "{xml}");
}
