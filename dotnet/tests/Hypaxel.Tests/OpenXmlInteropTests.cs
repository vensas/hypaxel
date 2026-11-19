using DocumentFormat.OpenXml;
using DocumentFormat.OpenXml.Packaging;
using S = DocumentFormat.OpenXml.Spreadsheet;

namespace Hypaxel.Tests;

public class OpenXmlInteropTests
{
    [Fact]
    public void ReadsWorkbooksWrittenByTheOpenXmlSdk()
    {
        var stream = new MemoryStream();
        using (var document = SpreadsheetDocument.Create(stream, SpreadsheetDocumentType.Workbook))
        {
            var workbook = document.AddWorkbookPart();
            workbook.Workbook = new S.Workbook();
            workbook.AddNewPart<WorkbookStylesPart>().Stylesheet = new S.Stylesheet(
                new S.NumberingFormats(new S.NumberingFormat { NumberFormatId = 164, FormatCode = "dd/mm/yyyy hh:mm" }) { Count = 1 },
                new S.Fonts(new S.Font()) { Count = 1 },
                new S.Fills(new S.Fill(new S.PatternFill { PatternType = S.PatternValues.None }), new S.Fill(new S.PatternFill { PatternType = S.PatternValues.Gray125 })) { Count = 2 },
                new S.Borders(new S.Border()) { Count = 1 },
                new S.CellFormats(new S.CellFormat(), new S.CellFormat { NumberFormatId = 164, ApplyNumberFormat = true }) { Count = 2 });
            workbook.AddNewPart<SharedStringTablePart>().SharedStringTable = new S.SharedStringTable(
                new S.SharedStringItem(new S.Text("Name")),
                new S.SharedStringItem(new S.Text("Amount")),
                new S.SharedStringItem(new S.Text("Widget")));

            var sheet = workbook.AddNewPart<WorksheetPart>();
            sheet.Worksheet = new S.Worksheet(new S.SheetData(
                new S.Row(Shared("A1", 0), Shared("B1", 1)) { RowIndex = 1 },
                new S.Row(Shared("A2", 2), new S.Cell { CellReference = "B2", CellValue = new S.CellValue("12.5") }) { RowIndex = 2 },
                new S.Row(new S.Cell { CellReference = "D3", DataType = S.CellValues.Boolean, CellValue = new S.CellValue("1") }) { RowIndex = 3 },
                new S.Row(new S.Cell { CellReference = "A4", StyleIndex = 1, CellValue = new S.CellValue("45413.354166666664") }) { RowIndex = 4 },
                new S.Row(new S.Cell { CellReference = "A5", CellFormula = new S.CellFormula("1+1"), CellValue = new S.CellValue("2") }) { RowIndex = 5 },
                new S.Row(new S.Cell { CellReference = "C6", DataType = S.CellValues.InlineString, InlineString = new S.InlineString(new S.Text("Widget")) }) { RowIndex = 6 }));
            workbook.Workbook.AppendChild(new S.Sheets(new S.Sheet { Name = "Report", SheetId = 1, Id = workbook.GetIdOfPart(sheet) }));
        }

        Assert.Equal(
            [
                (1, XlsxReaderTests.J("Name", "Amount")),
                (2, XlsxReaderTests.J("Widget", "12.5")),
                (3, XlsxReaderTests.J("", "", "", "TRUE")),
                (4, XlsxReaderTests.J("2024-05-01T08:30:00")),
                (5, XlsxReaderTests.J("2")),
                (6, XlsxReaderTests.J("", "", "Widget")),
            ],
            XlsxReaderTests.Dump(stream.ToArray(), "Report"));

        static S.Cell Shared(string reference, int index) =>
            new() { CellReference = reference, DataType = S.CellValues.SharedString, CellValue = new S.CellValue(index.ToString(System.Globalization.CultureInfo.InvariantCulture)) };
    }
}
