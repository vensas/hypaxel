using System.Globalization;
using System.Runtime.CompilerServices;
using Hypaxel.Internal;
using Hypaxel.Styling;

namespace Hypaxel;

/// <summary>
/// A worksheet being written. Rows are written strictly top to bottom and cells left to right; every
/// cell is serialized immediately, so memory stays flat no matter how many rows are written.
/// </summary>
/// <remarks>
/// Cell methods are synchronous and only touch in-memory buffers. Call <see cref="EndRowAsync"/> (or
/// <see cref="AddRowAsync(ReadOnlySpan{CellValue}, CancellationToken)"/>) to end rows: those are the
/// points where buffered output is pushed to the destination stream asynchronously.
/// </remarks>
public sealed partial class Worksheet
{
    private const int MaxCellTextLength = 32_767;

    private readonly XlsxWriter _writer;
    private readonly Utf8XmlBuffer _x;
    private readonly byte[] _rowRef = new byte[8];
    private int _rowRefLength;
    private int _row;
    private int _column;
    private int _lastCellColumn;
    private bool _rowOpen;
    private bool _completed;
    private List<string>? _mergedRanges;
    private (int FirstRow, int FirstColumn, int LastRow, int LastColumn)? _autoFilter;

    internal Worksheet(XlsxWriter writer, Utf8XmlBuffer buffer, string name, int index, WorksheetOptions? options)
    {
        _writer = writer;
        _x = buffer;
        Name = name;
        Index = index;
        Hidden = options?.Hidden ?? false;
        WriteHeader(options);
    }

    /// <summary>The worksheet name.</summary>
    public string Name { get; }

    /// <summary>Zero-based position of the worksheet in the workbook.</summary>
    public int Index { get; }

    internal bool Hidden { get; }

    /// <summary>1-based number of the current (or last written) row; 0 before the first row.</summary>
    public int RowNumber => _row;

    /// <summary>1-based column of the last written cell in the current row; 0 at the start of a row.</summary>
    public int ColumnNumber => _column;

    internal (int FirstRow, int FirstColumn, int LastRow, int LastColumn)? AutoFilter => _autoFilter;

    // ------------------------------------------------------------------ rows

    /// <summary>Starts the next row. Any open row is ended first.</summary>
    /// <param name="height">Row height in points, or null for the default height.</param>
    /// <param name="style">Default format for the row's empty cells.</param>
    public void BeginRow(double? height = null, StyleId style = default) => BeginRow(_row + 1, height, style);

    /// <summary>Starts the row with the given 1-based number, which must be greater than any row written so far.</summary>
    public void BeginRow(int rowNumber, double? height = null, StyleId style = default)
    {
        EnsureWritable();
        if (_rowOpen)
        {
            CloseRow();
        }

        CellReference.ValidateRow(rowNumber);
        if (rowNumber <= _row)
        {
            throw new InvalidOperationException($"Rows must be written in ascending order. Row {rowNumber} follows row {_row}.");
        }

        if (!style.IsDefault)
        {
            _writer.Styles.Validate(style);
        }

        _row = rowNumber;
        _column = 0;
        _lastCellColumn = 0;
        _rowOpen = true;
        rowNumber.TryFormat(_rowRef, out _rowRefLength, default, CultureInfo.InvariantCulture);

        var x = _x;
        x.WriteRaw("<row r=\""u8);
        x.WriteRaw(_rowRef.AsSpan(0, _rowRefLength));
        x.WriteByte((byte)'"');
        if (height is { } h)
        {
            if (h is < 0 or > 409)
            {
                throw new ArgumentOutOfRangeException(nameof(height), "Row height must be between 0 and 409 points.");
            }

            x.WriteRaw(" ht=\""u8);
            x.Write(h);
            x.WriteRaw("\" customHeight=\"1\""u8);
        }

        if (!style.IsDefault)
        {
            x.WriteRaw(" s=\""u8);
            x.Write(style.Index);
            x.WriteRaw("\" customFormat=\"1\""u8);
        }

        x.WriteByte((byte)'>');
    }

    /// <summary>Leaves <paramref name="count"/> empty rows; the next row written will be after them.</summary>
    public void SkipRows(int count)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        EnsureWritable();
        if (_rowOpen)
        {
            CloseRow();
        }

        CellReference.ValidateRow(_row + count == 0 ? 1 : _row + count);
        _row += count;
    }

    /// <summary>Ends the current row and pushes buffered output to the destination if the flush threshold is reached.</summary>
    public ValueTask EndRowAsync(CancellationToken cancellationToken = default)
    {
        EndRowCore();
        return _writer.DrainIfNeededAsync(cancellationToken);
    }

    /// <summary>Synchronous variant of <see cref="EndRowAsync"/>; only use with streams that allow synchronous writes.</summary>
    public void EndRow()
    {
        EndRowCore();
        _writer.DrainIfNeeded();
    }

    /// <summary>Writes a complete row.</summary>
    public ValueTask AddRowAsync(params ReadOnlySpan<CellValue> cells) => AddRowAsync(cells, CancellationToken.None);

    /// <summary>Writes a complete row.</summary>
    public ValueTask AddRowAsync(ReadOnlySpan<CellValue> cells, CancellationToken cancellationToken)
    {
        WriteRowCore(cells);
        return _writer.DrainIfNeededAsync(cancellationToken);
    }

    /// <summary>Synchronous variant of <see cref="AddRowAsync(ReadOnlySpan{CellValue}, CancellationToken)"/>.</summary>
    public void AddRow(params ReadOnlySpan<CellValue> cells)
    {
        WriteRowCore(cells);
        _writer.DrainIfNeeded();
    }

    private void WriteRowCore(ReadOnlySpan<CellValue> cells)
    {
        BeginRow();
        foreach (ref readonly var cell in cells)
        {
            Write(in cell);
        }

        CloseRow();
    }

    private void EndRowCore()
    {
        EnsureWritable();
        if (!_rowOpen)
        {
            // An empty row: emit nothing, but advance the row counter so the next row lands below it.
            CellReference.ValidateRow(_row + 1);
            _row++;
            return;
        }

        CloseRow();
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private void CloseRow()
    {
        _x.WriteRaw("</row>"u8);
        _rowOpen = false;
    }

    // ------------------------------------------------------------------ cells

    /// <summary>Leaves <paramref name="count"/> cells of the current row empty.</summary>
    public void SkipCells(int count = 1)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(count);
        EnsureRow();
        if (_column + count > CellReference.MaxColumns)
        {
            throw new InvalidOperationException($"A row cannot have more than {CellReference.MaxColumns} columns.");
        }

        _column += count;
    }

    /// <summary>Writes a string cell. A null value leaves the cell empty.</summary>
    public void Write(string? value, StyleId style = default)
    {
        if (value is null)
        {
            if (style.IsDefault)
            {
                SkipCells(1);
            }
            else
            {
                WriteBlank(style);
            }

            return;
        }

        WriteString(value, style.Index);
    }

    /// <summary>Writes a numeric cell.</summary>
    public void Write(int value, StyleId style = default) => Write((long)value, style);

    /// <summary>Writes a numeric cell. Values beyond ±2^53 lose precision in Excel; write them as text if exactness matters.</summary>
    public void Write(long value, StyleId style = default)
    {
        var span = BeginCell(style.Index, out var p);
        "><v>"u8.CopyTo(span[p..]);
        p += 4;
        value.TryFormat(span[p..], out var written, default, CultureInfo.InvariantCulture);
        p += written;
        "</v></c>"u8.CopyTo(span[p..]);
        _x.Advance(p + 8);
    }

    /// <summary>Writes a numeric cell. NaN and infinities are written as the <c>#NUM!</c> error.</summary>
    public void Write(double value, StyleId style = default)
    {
        var span = BeginCell(style.Index, out var p);
        if (!double.IsFinite(value))
        {
            " t=\"e\"><v>#NUM!</v></c>"u8.CopyTo(span[p..]);
            _x.Advance(p + 23);
            return;
        }

        "><v>"u8.CopyTo(span[p..]);
        p += 4;
        p += Utf8XmlBuffer.FormatDouble(value, span[p..]);
        "</v></c>"u8.CopyTo(span[p..]);
        _x.Advance(p + 8);
    }

    /// <summary>Writes a numeric cell.</summary>
    public void Write(float value, StyleId style = default) => Write((double)value, style);

    /// <summary>Writes a numeric cell. Excel stores numbers as doubles (15 significant digits).</summary>
    public void Write(decimal value, StyleId style = default)
    {
        var span = BeginCell(style.Index, out var p);
        "><v>"u8.CopyTo(span[p..]);
        p += 4;
        value.TryFormat(span[p..], out var written, default, CultureInfo.InvariantCulture);
        p += written;
        "</v></c>"u8.CopyTo(span[p..]);
        _x.Advance(p + 8);
    }

    /// <summary>Writes a boolean cell.</summary>
    public void Write(bool value, StyleId style = default)
    {
        var span = BeginCell(style.Index, out var p);
        (value ? " t=\"b\"><v>1</v></c>"u8 : " t=\"b\"><v>0</v></c>"u8).CopyTo(span[p..]);
        _x.Advance(p + 19);
    }

    /// <summary>
    /// Writes a date/time cell. Without a style (or with a style lacking a number format) the
    /// viewer's locale-specific date+time format is applied.
    /// </summary>
    public void Write(DateTime value, StyleId style = default) => WriteTemporal(value.Ticks, ValueFormatKind.DateTime, style.Index);

    /// <summary>Writes a date/time cell using the offset's local clock time (Excel has no time zones).</summary>
    public void Write(DateTimeOffset value, StyleId style = default) => WriteTemporal(value.Ticks, ValueFormatKind.DateTime, style.Index);

    /// <summary>Writes a date cell (locale-specific short date format unless styled).</summary>
    public void Write(DateOnly value, StyleId style = default) =>
        WriteTemporal(value.DayNumber * TimeSpan.TicksPerDay, ValueFormatKind.Date, style.Index);

    /// <summary>Writes a time-of-day cell (h:mm:ss unless styled).</summary>
    public void Write(TimeOnly value, StyleId style = default)
    {
        var index = _writer.Styles.GetValueFormatVariant(style.Index, ValueFormatKind.Time);
        Write(value.Ticks / (double)TimeSpan.TicksPerDay, new StyleId(index));
    }

    /// <summary>Writes a duration cell ([h]:mm:ss unless styled).</summary>
    public void Write(TimeSpan value, StyleId style = default)
    {
        var index = _writer.Styles.GetValueFormatVariant(style.Index, ValueFormatKind.Duration);
        Write(value.Ticks / (double)TimeSpan.TicksPerDay, new StyleId(index));
    }

    /// <summary>Writes a formula cell, e.g. <c>"SUM(A1:A10)"</c> (a leading '=' is optional). Excel computes it on open.</summary>
    public void WriteFormula(string formula, StyleId style = default)
    {
        ArgumentNullException.ThrowIfNull(formula);
        var span = formula.AsSpan();
        if (span.Length > 0 && span[0] == '=')
        {
            span = span[1..];
        }

        _writer.UsesFormulas = true;
        StartCell(style.Index);
        _x.WriteRaw("><f>"u8);
        _x.WriteEscaped(span);
        _x.WriteRaw("</f></c>"u8);
    }

    /// <summary>Writes an empty cell that carries a format (e.g. a border or fill).</summary>
    public void WriteBlank(StyleId style)
    {
        StartCell(style.Index);
        _x.WriteRaw("/>"u8);
    }

    /// <summary>Writes a <see cref="CellValue"/>.</summary>
    public void Write(in CellValue value)
    {
        var style = value.StyleValue;
        switch (value.KindValue)
        {
            case CellValueKind.Empty:
                if (style.IsDefault)
                {
                    SkipCells(1);
                }
                else
                {
                    WriteBlank(style);
                }

                break;
            case CellValueKind.String:
                WriteString(value.Text!, style.Index);
                break;
            case CellValueKind.Number:
                Write(value.Number, style);
                break;
            case CellValueKind.Integer:
                Write(value.Bits, style);
                break;
            case CellValueKind.Boolean:
                Write(value.Number != 0, style);
                break;
            case CellValueKind.DateTime:
                WriteTemporal(value.Bits, ValueFormatKind.DateTime, style.Index);
                break;
            case CellValueKind.Date:
                WriteTemporal(value.Bits, ValueFormatKind.Date, style.Index);
                break;
            case CellValueKind.Time:
                Write(new TimeOnly(value.Bits), style);
                break;
            case CellValueKind.Duration:
                Write(new TimeSpan(value.Bits), style);
                break;
            case CellValueKind.Formula:
                WriteFormula(value.Text!, style);
                break;
            default:
                throw new ArgumentOutOfRangeException(nameof(value));
        }
    }

    /// <summary>
    /// Writes an arbitrary value, dispatching on its runtime type. Unknown types are written using
    /// <see cref="object.ToString"/>. Prefer the typed overloads in hot paths: this one may box.
    /// </summary>
    public void WriteValue(object? value, StyleId style = default)
    {
        switch (value)
        {
            case null: Write((string?)null, style); break;
            case string s: WriteString(s, style.Index); break;
            case int i: Write(i, style); break;
            case long l: Write(l, style); break;
            case double d: Write(d, style); break;
            case decimal m: Write(m, style); break;
            case float f: Write(f, style); break;
            case bool b: Write(b, style); break;
            case DateTime dt: Write(dt, style); break;
            case DateTimeOffset dto: Write(dto, style); break;
            case DateOnly date: Write(date, style); break;
            case TimeOnly time: Write(time, style); break;
            case TimeSpan ts: Write(ts, style); break;
            case short s16: Write(s16, style); break;
            case ushort u16: Write(u16, style); break;
            case byte u8: Write(u8, style); break;
            case sbyte s8: Write(s8, style); break;
            case uint u32: Write(u32, style); break;
            case ulong u64: Write((double)u64, style); break;
            case CellValue cv: Write(style.IsDefault ? cv : cv.WithStyle(style)); break;
            case IFormattable formattable: WriteString(formattable.ToString(null, CultureInfo.InvariantCulture), style.Index); break;
            default: Write(value.ToString(), style); break;
        }
    }

    private void WriteString(string value, int style)
    {
        if (value.Length > MaxCellTextLength)
        {
            throw new ArgumentException($"Cell text cannot exceed {MaxCellTextLength} characters (got {value.Length}).", nameof(value));
        }

        if (_writer.SharedStrings is { } sst)
        {
            var span = BeginCell(style, out var p);
            " t=\"s\"><v>"u8.CopyTo(span[p..]);
            p += 10;
            sst.GetOrAdd(value).TryFormat(span[p..], out var written, default, CultureInfo.InvariantCulture);
            p += written;
            "</v></c>"u8.CopyTo(span[p..]);
            _x.Advance(p + 8);
        }
        else
        {
            var x = _x;
            StartCell(style);
            x.WriteRaw(" t=\"inlineStr\"><is>"u8);
            CellText.Write(x, value);
            x.WriteRaw("</is></c>"u8);
        }
    }

    private void WriteTemporal(long ticks, ValueFormatKind kind, int style)
    {
        if (!_writer.TryGetSerialDay(ticks, out var day))
        {
            // Outside Excel's date range (before 1900/1904): keep the information as ISO text.
            var text = kind == ValueFormatKind.Date
                ? new DateTime(ticks).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)
                : new DateTime(ticks).ToString("yyyy-MM-dd'T'HH:mm:ss.FFFFFFF", CultureInfo.InvariantCulture);
            WriteString(text, style);
            return;
        }

        var index = _writer.Styles.GetValueFormatVariant(style, kind);
        var span = BeginCell(index, out var p);
        "><v>"u8.CopyTo(span[p..]);
        p += 4;
        p += FormatSerial(day, ticks % TimeSpan.TicksPerDay, span[p..]);
        "</v></c>"u8.CopyTo(span[p..]);
        _x.Advance(p + 8);
    }

    /// <summary>
    /// Formats an Excel date serial using integer arithmetic only: whole days, then up to 10 fractional
    /// digits (1e-10 day ≈ 8.6 µs, well below Excel's millisecond resolution). Much cheaper than the
    /// general shortest-round-trip double formatter.
    /// </summary>
    private static int FormatSerial(long day, long timeTicks, Span<byte> destination)
    {
        // timeTicks / TicksPerDay in units of 1e-10 day, rounded: TicksPerDay = 864e9, so divide by 86.4.
        var fraction = ((timeTicks * 10) + 432) / 864;
        if (fraction >= 10_000_000_000L)
        {
            day++;
            fraction -= 10_000_000_000L;
        }

        day.TryFormat(destination, out var p, default, CultureInfo.InvariantCulture);
        if (fraction == 0)
        {
            return p;
        }

        destination[p++] = (byte)'.';
        var digits = destination.Slice(p, 10);
        for (var i = 9; i >= 0; i--)
        {
            digits[i] = (byte)('0' + (int)(fraction % 10));
            fraction /= 10;
        }

        var length = 10;
        while (digits[length - 1] == (byte)'0')
        {
            length--;
        }

        return p + length;
    }

    // "<c r=\"XFD1048576\" s=\"2147483647\"" + type attribute + "><v>" + longest number + "</v></c>" fits easily.
    private const int CellReserve = 128;

    /// <summary>Opens the cell element in the XML buffer and commits it (the caller writes the rest).</summary>
    private void StartCell(int style)
    {
        BeginCell(style, out var length);
        _x.Advance(length);
    }

    /// <summary>
    /// Writes <c>&lt;c</c>, the cell reference when needed and the style attribute into a span of at
    /// least <see cref="CellReserve"/> bytes, without committing it. <paramref name="length"/> receives
    /// the bytes used; the caller appends the value and calls <see cref="Utf8XmlBuffer.Advance"/>.
    /// </summary>
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private Span<byte> BeginCell(int style, out int length)
    {
        EnsureRow();
        var column = ++_column;
        if (column > CellReference.MaxColumns)
        {
            _column--;
            throw new InvalidOperationException($"A row cannot have more than {CellReference.MaxColumns} columns.");
        }

        if (style != 0 && (uint)style >= (uint)_writer.Styles.Count)
        {
            _column--;
            throw new ArgumentException("The style id does not belong to this writer.", nameof(style));
        }

        var span = _x.GetSpan(CellReserve);
        int p;
        if (column == _lastCellColumn + 1 && !_writer.WriteCellReferences)
        {
            // References are optional; readers infer consecutive cells. This trims ~20% off the XML.
            span[0] = (byte)'<';
            span[1] = (byte)'c';
            p = 2;
        }
        else
        {
            "<c r=\""u8.CopyTo(span);
            p = 6;
            var name = CellReference.ColumnNameUtf8(column);
            name.CopyTo(span[p..]);
            p += name.Length;
            _rowRef.AsSpan(0, _rowRefLength).CopyTo(span[p..]);
            p += _rowRefLength;
            span[p++] = (byte)'"';
        }

        _lastCellColumn = column;
        if (style != 0)
        {
            " s=\""u8.CopyTo(span[p..]);
            p += 4;
            style.TryFormat(span[p..], out var written, default, CultureInfo.InvariantCulture);
            p += written;
            span[p++] = (byte)'"';
        }

        length = p;
        return span;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    private void EnsureRow()
    {
        if (!_rowOpen)
        {
            BeginRow();
        }
    }

    // ------------------------------------------------------------------ sheet features

    /// <summary>Merges a rectangular range of cells. Ranges must not overlap.</summary>
    public void MergeCells(int firstRow, int firstColumn, int lastRow, int lastColumn)
    {
        EnsureNotCompleted();
        var range = CellReference.ToRange(firstRow, firstColumn, lastRow, lastColumn);
        if (firstRow == lastRow && firstColumn == lastColumn)
        {
            throw new ArgumentException("A merged range must span more than one cell.");
        }

        (_mergedRanges ??= []).Add(range);
    }

    /// <summary>Adds an auto filter (drop-down filter buttons) to a range whose first row is the header.</summary>
    public void SetAutoFilter(int firstRow, int firstColumn, int lastRow, int lastColumn)
    {
        EnsureNotCompleted();
        _ = CellReference.ToRange(firstRow, firstColumn, lastRow, lastColumn); // validates
        _autoFilter = (firstRow, firstColumn, lastRow, lastColumn);
    }

    // ------------------------------------------------------------------ serialization

    private void WriteHeader(WorksheetOptions? options)
    {
        var x = _x;
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<worksheet xmlns=\""u8);
        x.WriteRaw(Xml.SpreadsheetMlNamespace);
        x.WriteRaw("\" xmlns:r=\""u8);
        x.WriteRaw(Xml.RelationshipsNamespace);
        x.WriteRaw("\">"u8);

        if (options?.TabColor is { } tab)
        {
            x.WriteRaw("<sheetPr><tabColor rgb=\""u8);
            x.WriteEscaped(tab.ToArgbHex());
            x.WriteRaw("\"/></sheetPr>"u8);
        }

        x.WriteRaw("<sheetViews><sheetView workbookViewId=\"0\""u8);
        if (Index == 0)
        {
            x.WriteRaw(" tabSelected=\"1\""u8);
        }

        if (options is { ShowGridLines: false })
        {
            x.WriteRaw(" showGridLines=\"0\""u8);
        }

        var frozenRows = options?.FrozenRows ?? 0;
        var frozenColumns = options?.FrozenColumns ?? 0;
        if (frozenRows < 0 || frozenRows >= CellReference.MaxRows)
        {
            throw new ArgumentOutOfRangeException(nameof(options), "FrozenRows is out of range.");
        }

        if (frozenColumns < 0 || frozenColumns >= CellReference.MaxColumns)
        {
            throw new ArgumentOutOfRangeException(nameof(options), "FrozenColumns is out of range.");
        }

        if (frozenRows > 0 || frozenColumns > 0)
        {
            var pane = (frozenRows > 0, frozenColumns > 0) switch
            {
                (true, true) => "bottomRight"u8,
                (true, false) => "bottomLeft"u8,
                _ => "topRight"u8,
            };

            x.WriteRaw("><pane"u8);
            if (frozenColumns > 0)
            {
                x.WriteRaw(" xSplit=\""u8);
                x.Write(frozenColumns);
                x.WriteByte((byte)'"');
            }

            if (frozenRows > 0)
            {
                x.WriteRaw(" ySplit=\""u8);
                x.Write(frozenRows);
                x.WriteByte((byte)'"');
            }

            x.WriteRaw(" topLeftCell=\""u8);
            x.WriteRaw(CellReference.ColumnNameUtf8(frozenColumns + 1));
            x.Write(frozenRows + 1);
            x.WriteRaw("\" activePane=\""u8);
            x.WriteRaw(pane);
            x.WriteRaw("\" state=\"frozen\"/><selection pane=\""u8);
            x.WriteRaw(pane);
            x.WriteRaw("\"/></sheetView></sheetViews>"u8);
        }
        else
        {
            x.WriteRaw("/></sheetViews>"u8);
        }

        x.WriteRaw("<sheetFormatPr defaultRowHeight=\"15\"/>"u8);

        if (options is { Columns.Count: > 0 })
        {
            x.WriteRaw("<cols>"u8);
            foreach (var (column, definition) in options.Columns)
            {
                x.WriteRaw("<col min=\""u8);
                x.Write(column);
                x.WriteRaw("\" max=\""u8);
                x.Write(column);
                x.WriteRaw("\" width=\""u8);
                x.Write(definition.Width is { } w ? Math.Clamp(w, 0, 255) : 9.140625);
                x.WriteByte((byte)'"');
                if (definition.Width is not null)
                {
                    x.WriteRaw(" customWidth=\"1\""u8);
                }

                if (!definition.Style.IsDefault)
                {
                    _writer.Styles.Validate(definition.Style);
                    x.WriteRaw(" style=\""u8);
                    x.Write(definition.Style.Index);
                    x.WriteByte((byte)'"');
                }

                if (definition.Hidden)
                {
                    x.WriteRaw(" hidden=\"1\""u8);
                }

                x.WriteRaw("/>"u8);
            }

            x.WriteRaw("</cols>"u8);
        }

        x.WriteRaw("<sheetData>"u8);
    }

    internal void Complete()
    {
        if (_completed)
        {
            return;
        }

        if (_rowOpen)
        {
            CloseRow();
        }

        var x = _x;
        x.WriteRaw("</sheetData>"u8);
        if (_autoFilter is { } f)
        {
            x.WriteRaw("<autoFilter ref=\""u8);
            x.WriteEscaped(CellReference.ToRange(f.FirstRow, f.FirstColumn, f.LastRow, f.LastColumn));
            x.WriteRaw("\"/>"u8);
        }

        if (_mergedRanges is { Count: > 0 } merges)
        {
            x.WriteRaw("<mergeCells count=\""u8);
            x.Write(merges.Count);
            x.WriteRaw("\">"u8);
            foreach (var range in merges)
            {
                x.WriteRaw("<mergeCell ref=\""u8);
                x.WriteEscaped(range);
                x.WriteRaw("\"/>"u8);
            }

            x.WriteRaw("</mergeCells>"u8);
        }

        x.WriteRaw("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/></worksheet>"u8);
        _completed = true;
    }

    private void EnsureWritable()
    {
        EnsureNotCompleted();
        _writer.EnsureCurrent(this);
    }

    private void EnsureNotCompleted()
    {
        if (_completed)
        {
            throw new InvalidOperationException($"Worksheet '{Name}' is complete; worksheets are written one at a time and cannot be reopened.");
        }
    }
}
