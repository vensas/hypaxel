# Test data

Workbooks saved by other applications, read by the reader tests of every implementation. The tests find
them relative to the repository root, so they need a checkout of the whole repository and do not
run from a published package.

The files contain generated data only and no author information. Keep it that way when adding one.

| File | Origin |
| --- | --- |
| `reader/excel.xlsx` | Written by Hypaxel (two tables, grouped, hidden and collapsed rows, row heights, column widths, a hidden column), then opened and saved by Microsoft Excel for Mac. The author fields and add-in references were removed from the package afterwards. |
| `reader/libreoffice.xlsx` | The same Hypaxel workbook, converted to XLSX by LibreOffice Calc 25.2 (headless). LibreOffice drops the table styles and stores a height on every row. |
| `reader/excel-totals.xlsx` | A table of 1,000 rows with a totals row (label, average, sum and count), saved by Microsoft Excel for Mac. The cell values, column names and sheet name were then replaced with generated data, leaving the structure as Excel wrote it; Excel opens the result without repair. |
| `reader/google-sheets.xlsx` | The generated inventory table imported into Google Sheets and downloaded as XLSX, unchanged: a table with a custom style, no filter buttons and a totals row whose functions are stored as custom formulas; numbers written as `250.0`, a height on every row but the first. |
