using ClosedXML.Excel;
using Hypaxel;

/// <summary>
/// One write case per process, for peak RSS: <c>dotnet run -c Release -- --write-rss hypaxel 1000000 out.xlsx</c>.
/// Rows are generated lazily and written to a file, so the process holds neither the dataset nor the output.
/// Cases: baseline (generate the rows only), hypaxel, hypaxel-addrow, hypaxel-objects, hypaxel-shared,
/// spreadcheetah, closedxml.
/// </summary>
public static class WriteRss
{
    public static async Task RunAsync(string name, int rows, string path)
    {
        var sw = System.Diagnostics.Stopwatch.StartNew();
        if (name == "baseline")
        {
            long sum = 0;
            foreach (var r in Data.Stream(rows))
            {
                sum += r.Id + r.Name.Length + r.Comment.Length;
            }

            Console.WriteLine($"{name} rows={rows:N0} checksum={sum} {sw.ElapsedMilliseconds:N0} ms");
            return;
        }

        await using (var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None, 1, useAsync: false))
        {
            await WriteAsync(name, rows, file);
        }

        Console.WriteLine($"{name} rows={rows:N0} {sw.ElapsedMilliseconds:N0} ms {new FileInfo(path).Length / 1024.0 / 1024.0:N1} MB file");
    }

    private static async Task WriteAsync(string name, int rows, Stream stream)
    {
        switch (name)
        {
            case "hypaxel":
            {
                await using var writer = new XlsxWriter(stream);
                var sheet = writer.AddWorksheet("Data");
                foreach (var r in Data.Stream(rows))
                {
                    sheet.Write(r.Id);
                    sheet.Write(r.Name);
                    sheet.Write(r.Category);
                    sheet.Write(r.Price);
                    sheet.Write(r.Amount);
                    sheet.Write(r.Created);
                    sheet.Write(r.Active);
                    sheet.Write(r.Quantity);
                    sheet.Write(r.Comment);
                    sheet.Write(r.Ratio);
                    await sheet.EndRowAsync();
                }

                await writer.CompleteAsync();
                break;
            }

            case "hypaxel-addrow":
            case "hypaxel-shared":
            {
                var options = new XlsxWriterOptions { StringStorage = name == "hypaxel-shared" ? StringStorage.SharedStrings : StringStorage.Inline };
                await using var writer = new XlsxWriter(stream, options);
                var sheet = writer.AddWorksheet("Data");
                foreach (var r in Data.Stream(rows))
                {
                    await sheet.AddRowAsync(r.Id, r.Name, r.Category, r.Price, r.Amount, r.Created, r.Active, r.Quantity, r.Comment, r.Ratio);
                }

                await writer.CompleteAsync();
                break;
            }

            case "hypaxel-objects":
            {
                await using var writer = new XlsxWriter(stream);
                await writer.WriteWorksheetAsync("Data", Data.Stream(rows));
                await writer.CompleteAsync();
                break;
            }

            case "spreadcheetah":
            {
                await using var spreadsheet = await SpreadCheetah.Spreadsheet.CreateNewAsync(stream);
                await spreadsheet.StartWorksheetAsync("Data");
                var cells = new SpreadCheetah.DataCell[10];
                foreach (var r in Data.Stream(rows))
                {
                    cells[0] = new(r.Id);
                    cells[1] = new(r.Name);
                    cells[2] = new(r.Category);
                    cells[3] = new(r.Price);
                    cells[4] = new(r.Amount);
                    cells[5] = new(r.Created);
                    cells[6] = new(r.Active);
                    cells[7] = new(r.Quantity);
                    cells[8] = new(r.Comment);
                    cells[9] = new(r.Ratio);
                    await spreadsheet.AddRowAsync(cells);
                }

                await spreadsheet.FinishAsync();
                break;
            }

            case "closedxml":
            {
                using var workbook = new XLWorkbook();
                var sheet = workbook.AddWorksheet("Data");
                var rowIndex = 1;
                foreach (var r in Data.Stream(rows))
                {
                    var row = sheet.Row(rowIndex++);
                    row.Cell(1).Value = r.Id;
                    row.Cell(2).Value = r.Name;
                    row.Cell(3).Value = r.Category;
                    row.Cell(4).Value = r.Price;
                    row.Cell(5).Value = r.Amount;
                    row.Cell(6).Value = r.Created;
                    row.Cell(7).Value = r.Active;
                    row.Cell(8).Value = r.Quantity;
                    row.Cell(9).Value = r.Comment;
                    row.Cell(10).Value = r.Ratio;
                }

                workbook.SaveAs(stream);
                break;
            }

            default:
                throw new ArgumentException($"Unknown case '{name}'.", nameof(name));
        }
    }
}
