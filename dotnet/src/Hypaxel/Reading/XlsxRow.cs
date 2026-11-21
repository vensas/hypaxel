using System.Collections;
using System.Text;
using Hypaxel.Internal.Reading;

namespace Hypaxel;

/// <summary>
/// One worksheet row that has at least one value. The same instance is reused by its
/// <see cref="WorksheetReader"/> for every row, so read what you need before advancing; copy values
/// (<see cref="GetValue"/>, <see cref="GetValues()"/>) to keep them.
/// </summary>
/// <remarks>
/// Columns are 1-based (A = 1). Shared strings, numbers, booleans and dates are returned without
/// allocating; inline strings and error texts allocate a string when converted to <see cref="string"/>
/// or <see cref="ExcelValue"/>. <see cref="TryGetText"/> reads any text without allocating.
/// </remarks>
public sealed class XlsxRow
{
    private readonly string[] _sharedStrings;
    private readonly bool _is1904;
    private RawCell[] _cells = new RawCell[16];

    internal XlsxRow(string[] sharedStrings, bool is1904)
    {
        _sharedStrings = sharedStrings;
        _is1904 = is1904;
    }

    /// <summary>1-based row number as shown in Excel.</summary>
    public int Number { get; internal set; }

    /// <summary>Outline (grouping) level, 0–7.</summary>
    public int OutlineLevel { get; private set; }

    /// <summary>True if the row is hidden (e.g. a row inside a collapsed group).</summary>
    public bool Hidden { get; private set; }

    /// <summary>True for the summary row of a collapsed group.</summary>
    public bool Collapsed { get; private set; }

    /// <summary>Height in points, or null if the row stores none.</summary>
    public double? Height { get; private set; }

    /// <summary>Number of cells with a value.</summary>
    public int Count { get; private set; }

    /// <summary>1-based column of the last cell with a value.</summary>
    public int LastColumn => Count == 0 ? 0 : _cells[Count - 1].Column;

    internal CharBuffer Text { get; } = new(1024);

    /// <summary>The value in a 1-based column; <see cref="ExcelValue.Empty"/> if there is none.</summary>
    public ExcelValue this[int column] => GetValue(column);

    /// <summary>The cells that have a value, in column order.</summary>
    public CellCollection Cells => new(this);

    /// <summary>The value in a 1-based column; <see cref="ExcelValue.Empty"/> if there is none.</summary>
    public ExcelValue GetValue(int column)
    {
        var i = IndexOf(column);
        return i < 0 ? default : Materialize(i);
    }

    /// <summary>The text of a string cell. Shared strings are returned without allocating.</summary>
    public bool TryGetString(int column, out string value)
    {
        var i = IndexOf(column);
        if (i >= 0)
        {
            ref readonly var cell = ref _cells[i];
            if (cell.Type == RawType.Shared)
            {
                value = _sharedStrings[cell.Start];
                return true;
            }

            if (cell.Type == RawType.Text)
            {
                value = cell.Length == 0 ? string.Empty : new string(Text.Slice(cell.Start, cell.Length));
                return true;
            }
        }

        value = string.Empty;
        return false;
    }

    /// <summary>
    /// The text of a string cell without allocating. The span is only valid until the reader advances to the
    /// next row.
    /// </summary>
    public bool TryGetText(int column, out ReadOnlySpan<char> text)
    {
        var i = IndexOf(column);
        if (i >= 0)
        {
            ref readonly var cell = ref _cells[i];
            if (cell.Type == RawType.Shared)
            {
                text = _sharedStrings[cell.Start];
                return true;
            }

            if (cell.Type == RawType.Text)
            {
                text = Text.Slice(cell.Start, cell.Length);
                return true;
            }
        }

        text = default;
        return false;
    }

    /// <summary>The number of a numeric cell; for date and time cells, the serial number.</summary>
    public bool TryGetDouble(int column, out double value)
    {
        var i = IndexOf(column);
        if (i >= 0 && _cells[i].Type is RawType.Number or RawType.Date)
        {
            value = _cells[i].Number;
            return true;
        }

        value = 0;
        return false;
    }

    /// <summary>The value of a boolean cell.</summary>
    public bool TryGetBoolean(int column, out bool value)
    {
        var i = IndexOf(column);
        if (i >= 0 && _cells[i].Type == RawType.Boolean)
        {
            value = _cells[i].Number != 0;
            return true;
        }

        value = false;
        return false;
    }

    /// <summary>
    /// A date, time or duration cell as a <see cref="DateTime"/>. False for other cells and for serials that
    /// are not a representable date.
    /// </summary>
    public bool TryGetDateTime(int column, out DateTime value)
    {
        var i = IndexOf(column);
        if (i >= 0 && _cells[i].Type == RawType.Date)
        {
            return new ExcelDateTime(_cells[i].Number, _cells[i].DateKind, _is1904).TryGetDateTime(out value);
        }

        value = default;
        return false;
    }

    /// <summary>The kind of value in a 1-based column, without materializing it.</summary>
    public ExcelValueKind GetKind(int column)
    {
        var i = IndexOf(column);
        return i < 0 ? ExcelValueKind.Empty : _cells[i].Type switch
        {
            RawType.Shared or RawType.Text => ExcelValueKind.String,
            RawType.Number => ExcelValueKind.Number,
            RawType.Boolean => ExcelValueKind.Boolean,
            RawType.Date => ExcelValueKind.DateTime,
            _ => ExcelValueKind.Error,
        };
    }

    /// <summary>The values of columns 1 to <see cref="LastColumn"/>, with <see cref="ExcelValue.Empty"/> for gaps.</summary>
    public ExcelValue[] GetValues()
    {
        var values = new ExcelValue[LastColumn];
        GetValues(values);
        return values;
    }

    /// <summary>
    /// Fills <paramref name="destination"/> with the values of columns 1 to <c>destination.Length</c>, using
    /// <see cref="ExcelValue.Empty"/> for gaps. Reuse one array for every row to avoid allocating it.
    /// </summary>
    public void GetValues(Span<ExcelValue> destination)
    {
        destination.Clear();
        for (var i = 0; i < Count; i++)
        {
            var column = _cells[i].Column;
            if (column > destination.Length)
            {
                break;
            }

            destination[column - 1] = Materialize(i);
        }
    }

    /// <summary>The row as tab-separated values, for debugging.</summary>
    public override string ToString()
    {
        var text = new StringBuilder();
        text.Append(Number).Append(':');
        foreach (var cell in Cells)
        {
            text.Append(' ').Append(CellReference.ToA1(Number, cell.Column)).Append('=').Append(cell.Value.ToString());
        }

        return text.ToString();
    }

    internal void Reset(int number, int outlineLevel, bool hidden, bool collapsed, double? height)
    {
        Number = number;
        OutlineLevel = outlineLevel;
        Hidden = hidden;
        Collapsed = collapsed;
        Height = height;
        Count = 0;
        Text.Clear();
    }

    internal void Add(in RawCell cell)
    {
        if (Count == _cells.Length)
        {
            Array.Resize(ref _cells, _cells.Length * 2);
        }

        _cells[Count++] = cell;
    }

    /// <summary>Sorts cells written out of column order; for duplicate columns the last cell wins.</summary>
    internal void SortAndDeduplicate()
    {
        var cells = _cells.AsSpan(0, Count);
        for (var i = 0; i < cells.Length; i++)
        {
            cells[i].Order = i;
        }

        cells.Sort(static (a, b) => a.Column != b.Column ? a.Column.CompareTo(b.Column) : a.Order.CompareTo(b.Order));
        var write = 0;
        for (var i = 0; i < cells.Length; i++)
        {
            if (write > 0 && cells[write - 1].Column == cells[i].Column)
            {
                cells[write - 1] = cells[i];
            }
            else
            {
                cells[write++] = cells[i];
            }
        }

        Count = write;
    }

    private int IndexOf(int column)
    {
        // Dense rows (no gaps before the column) need no search.
        var guess = column - 1;
        if ((uint)guess < (uint)Count && _cells[guess].Column == column)
        {
            return guess;
        }

        int low = 0, high = Count - 1;
        while (low <= high)
        {
            var mid = (low + high) >>> 1;
            var c = _cells[mid].Column;
            if (c == column)
            {
                return mid;
            }

            if (c < column)
            {
                low = mid + 1;
            }
            else
            {
                high = mid - 1;
            }
        }

        return -1;
    }

    private ExcelValue Materialize(int index)
    {
        ref readonly var cell = ref _cells[index];
        return cell.Type switch
        {
            RawType.Shared => ExcelValue.Text(_sharedStrings[cell.Start]),
            RawType.Text => ExcelValue.Text(cell.Length == 0 ? string.Empty : new string(Text.Slice(cell.Start, cell.Length))),
            RawType.Number => ExcelValue.FromNumber(cell.Number),
            RawType.Boolean => ExcelValue.FromBoolean(cell.Number != 0),
            RawType.Date => ExcelValue.Date(cell.Number, cell.DateKind, _is1904),
            _ => ExcelValue.Error(new string(Text.Slice(cell.Start, cell.Length))),
        };
    }

    /// <summary>The cells of a row that have a value, in column order.</summary>
    public readonly struct CellCollection : IEnumerable<XlsxCell>
    {
        private readonly XlsxRow _row;

        internal CellCollection(XlsxRow row) => _row = row;

        /// <summary>Number of cells with a value.</summary>
        public int Count => _row.Count;

        /// <summary>The cell at a 0-based position (not a column).</summary>
        public XlsxCell this[int index]
        {
            get
            {
                ArgumentOutOfRangeException.ThrowIfGreaterThanOrEqual((uint)index, (uint)_row.Count, nameof(index));
                return _row.CellAt(index);
            }
        }

        /// <summary>Returns an allocation-free enumerator.</summary>
        public Enumerator GetEnumerator() => new(_row);

        IEnumerator<XlsxCell> IEnumerable<XlsxCell>.GetEnumerator() => GetEnumerator();

        IEnumerator IEnumerable.GetEnumerator() => GetEnumerator();

        /// <summary>Enumerates the cells of a row.</summary>
        public struct Enumerator : IEnumerator<XlsxCell>
        {
            private readonly XlsxRow _row;
            private int _index;

            internal Enumerator(XlsxRow row)
            {
                _row = row;
                _index = -1;
            }

            /// <inheritdoc />
            public readonly XlsxCell Current => _row.CellAt(_index);

            readonly object IEnumerator.Current => Current;

            /// <inheritdoc />
            public bool MoveNext() => ++_index < _row.Count;

            /// <inheritdoc />
            public void Reset() => _index = -1;

            /// <inheritdoc />
            public readonly void Dispose()
            {
            }
        }
    }

    private XlsxCell CellAt(int index)
    {
        ref readonly var cell = ref _cells[index];
        return new XlsxCell(cell.Column, Materialize(index), cell.Type == RawType.Shared ? cell.Start : -1);
    }
}

/// <summary>A cell with a value.</summary>
/// <param name="Column">1-based column (A = 1).</param>
/// <param name="Value">The value.</param>
/// <param name="SharedStringIndex">
/// For text from the shared string table, its index in that table, otherwise -1. Equal indices mean equal
/// text, so callers can convert each shared string once and reuse the result.
/// </param>
public readonly record struct XlsxCell(int Column, ExcelValue Value, int SharedStringIndex);

internal enum RawType : byte
{
    Shared,
    Text,
    Number,
    Boolean,
    Date,
    Error,
}

internal struct RawCell
{
    public int Column;
    public int Order;
    public RawType Type;
    public DateKind DateKind;

    /// <summary>Shared string index, or the start of the text in the row's text buffer.</summary>
    public int Start;
    public int Length;
    public double Number;
}
