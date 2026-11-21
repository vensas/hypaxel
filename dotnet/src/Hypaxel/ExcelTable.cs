using System.Text;
using Hypaxel.Internal;

namespace Hypaxel;

/// <summary>A built-in table style, as shown in Excel's table style gallery.</summary>
public readonly record struct TableStyle
{
    private enum Family : byte
    {
        None,
        Light,
        Medium,
        Dark,
    }

    private readonly Family _family;
    private readonly byte _number;

    private TableStyle(Family family, int number)
    {
        _family = family;
        _number = (byte)number;
    }

    /// <summary>No table formatting (filter buttons and structured references still work).</summary>
    public static TableStyle None => default;

    /// <summary><c>TableStyleMedium2</c>, Excel's default for new tables.</summary>
    public static TableStyle Default => Medium(2);

    /// <summary><c>TableStyleLight1</c> to <c>TableStyleLight21</c>.</summary>
    public static TableStyle Light(int number) => Create(Family.Light, number, 21);

    /// <summary><c>TableStyleMedium1</c> to <c>TableStyleMedium28</c>.</summary>
    public static TableStyle Medium(int number) => Create(Family.Medium, number, 28);

    /// <summary><c>TableStyleDark1</c> to <c>TableStyleDark11</c>.</summary>
    public static TableStyle Dark(int number) => Create(Family.Dark, number, 11);

    /// <summary>The style name used in the file (e.g. <c>TableStyleMedium2</c>), or null for <see cref="None"/>.</summary>
    public string? Name => _family == Family.None ? null : $"TableStyle{_family}{_number}";

    /// <inheritdoc />
    public override string ToString() => Name ?? "None";

    private static TableStyle Create(Family family, int number, int max)
    {
        if (number < 1 || number > max)
        {
            throw new ArgumentOutOfRangeException(nameof(number), number, $"{family} table styles are numbered 1 to {max}.");
        }

        return new TableStyle(family, number);
    }
}

/// <summary>
/// An Excel table (a <c>ListObject</c>): a named, styled range with a header row and filter buttons,
/// added with <see cref="Worksheet.AddTable"/>.
/// </summary>
/// <remarks>
/// With a header row (the default), <see cref="Columns"/> must list the header texts exactly as written
/// in the range's first row: Excel identifies table columns by those names and repairs the file otherwise.
/// </remarks>
/// <example>
/// <code>
/// await sheet.AddRowAsync("Product", "Units");
/// await sheet.AddRowAsync("Widget", 3);
/// await sheet.AddRowAsync("Gadget", 5);
/// sheet.AddTable(new ExcelTable(1, 1, sheet.RowNumber, 2) { Name = "Orders", Columns = ["Product", "Units"] });
/// </code>
/// </example>
public sealed record ExcelTable
{
    /// <summary>Creates a table over a range (1-based, inclusive) whose first row is the header.</summary>
    public ExcelTable(int firstRow, int firstColumn, int lastRow, int lastColumn)
    {
        FirstRow = firstRow;
        FirstColumn = firstColumn;
        LastRow = lastRow;
        LastColumn = lastColumn;
    }

#pragma warning disable CS1591
    public int FirstRow { get; init; }
    public int FirstColumn { get; init; }
    public int LastRow { get; init; }
    public int LastColumn { get; init; }
#pragma warning restore CS1591

    /// <summary>
    /// Workbook-unique name used in structured references (e.g. <c>Orders[Units]</c>); null gives
    /// <c>Table1</c>, <c>Table2</c>, …
    /// </summary>
    public string? Name { get; init; }

    /// <summary>Column names; required with a header row, otherwise <c>Column1</c>, <c>Column2</c>, … by default.</summary>
    public IReadOnlyList<string>? Columns { get; init; }

    /// <summary>Table style. Default: <see cref="TableStyle.Default"/> (<c>TableStyleMedium2</c>).</summary>
    public TableStyle Style { get; init; } = TableStyle.Default;

    /// <summary>The first row of the range holds the column names. Default: true.</summary>
    public bool ShowHeaderRow { get; init; } = true;

    /// <summary>Filter buttons in the header row. Default: true.</summary>
    public bool ShowAutoFilter { get; init; } = true;

    /// <summary>Banded rows. Default: true.</summary>
    public bool ShowRowStripes { get; init; } = true;

    /// <summary>Banded columns. Default: false.</summary>
    public bool ShowColumnStripes { get; init; }

    /// <summary>Highlights the first column. Default: false.</summary>
    public bool HighlightFirstColumn { get; init; }

    /// <summary>Highlights the last column. Default: false.</summary>
    public bool HighlightLastColumn { get; init; }

    /// <summary>
    /// The last row of the range is the table's totals row. Reported by the reader; the writer cannot
    /// write totals rows and rejects a table that has one. Default: false.
    /// </summary>
    public bool HasTotalsRow { get; init; }

    /// <summary>
    /// Compares every property; <see cref="Columns"/> is compared name by name (ordinal), so a table read back
    /// from a file equals the table that was written. A null <see cref="Columns"/> equals only null.
    /// </summary>
    public bool Equals(ExcelTable? other) =>
        other is not null
        && FirstRow == other.FirstRow
        && FirstColumn == other.FirstColumn
        && LastRow == other.LastRow
        && LastColumn == other.LastColumn
        && string.Equals(Name, other.Name, StringComparison.Ordinal)
        && ColumnsEqual(Columns, other.Columns)
        && Style == other.Style
        && ShowHeaderRow == other.ShowHeaderRow
        && ShowAutoFilter == other.ShowAutoFilter
        && ShowRowStripes == other.ShowRowStripes
        && ShowColumnStripes == other.ShowColumnStripes
        && HighlightFirstColumn == other.HighlightFirstColumn
        && HighlightLastColumn == other.HighlightLastColumn
        && HasTotalsRow == other.HasTotalsRow;

    /// <inheritdoc/>
    public override int GetHashCode()
    {
        var hash = new HashCode();
        hash.Add(FirstRow);
        hash.Add(FirstColumn);
        hash.Add(LastRow);
        hash.Add(LastColumn);
        hash.Add(Name, StringComparer.Ordinal);
        if (Columns is { } columns)
        {
            hash.Add(columns.Count);
            foreach (var column in columns)
            {
                hash.Add(column, StringComparer.Ordinal);
            }
        }
        else
        {
            hash.Add(-1);
        }

        hash.Add(Style);
        hash.Add(ShowHeaderRow);
        hash.Add(ShowAutoFilter);
        hash.Add(ShowRowStripes);
        hash.Add(ShowColumnStripes);
        hash.Add(HighlightFirstColumn);
        hash.Add(HighlightLastColumn);
        hash.Add(HasTotalsRow);
        return hash.ToHashCode();
    }

    private static bool ColumnsEqual(IReadOnlyList<string>? a, IReadOnlyList<string>? b)
    {
        if (ReferenceEquals(a, b))
        {
            return true;
        }

        if (a is null || b is null || a.Count != b.Count)
        {
            return false;
        }

        for (var i = 0; i < a.Count; i++)
        {
            if (!string.Equals(a[i], b[i], StringComparison.Ordinal))
            {
                return false;
            }
        }

        return true;
    }
}

/// <summary>A validated table, kept until its worksheet completes.</summary>
internal sealed class TableDefinition
{
    private readonly string[] _columns;
    private readonly ExcelTable _table;

    private TableDefinition(ExcelTable table, int id, string name, string[] columns)
    {
        _table = table;
        _columns = columns;
        Id = id;
        Name = name;
        Range = new CellRange(table.FirstRow, table.FirstColumn, table.LastRow, table.LastColumn);
    }

    public int Id { get; }

    public string Name { get; }

    public CellRange Range { get; }

    /// <summary>Validates <paramref name="table"/>; <paramref name="isTaken"/> reports whether a name is already used in the workbook.</summary>
    public static TableDefinition Create(ExcelTable table, int id, Func<string, bool> isTaken)
    {
        ArgumentNullException.ThrowIfNull(table);
        var range = CellRange.Create(table.FirstRow, table.FirstColumn, table.LastRow, table.LastColumn);
        if (table.ShowHeaderRow && range.FirstRow == range.LastRow)
        {
            throw new ArgumentException("A table with a header row needs at least one data row.", nameof(table));
        }

        if (table.HasTotalsRow)
        {
            throw new ArgumentException("Tables with a totals row can be read but not written.", nameof(table));
        }

        string name;
        if (table.Name is { } requested)
        {
            ValidateName(requested);
            name = requested;
        }
        else
        {
            name = $"Table{id}";
        }

        if (isTaken(name))
        {
            throw new ArgumentException($"A table named '{name}' already exists (table names are unique in the workbook and case-insensitive).", nameof(table));
        }

        var width = range.LastColumn - range.FirstColumn + 1;
        string[] columns;
        if (table.Columns is not { Count: > 0 })
        {
            if (table.ShowHeaderRow)
            {
                throw new ArgumentException("A table with a header row needs its column names (ExcelTable.Columns).", nameof(table));
            }

            columns = new string[width];
            for (var i = 0; i < width; i++)
            {
                columns[i] = $"Column{i + 1}";
            }
        }
        else
        {
            columns = [.. table.Columns];
        }

        if (columns.Length != width)
        {
            throw new ArgumentException($"The number of table column names ({columns.Length}) must equal the width of the range ({width}).", nameof(table));
        }

        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var column in columns)
        {
            if (string.IsNullOrEmpty(column) || column.Length > 255)
            {
                throw new ArgumentException("Table column names must be 1 to 255 characters.", nameof(table));
            }

            if (!seen.Add(column))
            {
                throw new ArgumentException($"Table column names must be unique (case-insensitive); '{column}' is repeated.", nameof(table));
            }
        }

        return new TableDefinition(table, id, name, columns);
    }

    public void WriteTo(Utf8XmlBuffer x)
    {
        var range = Range.ToString();
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<table xmlns=\""u8);
        x.WriteRaw(Xml.SpreadsheetMlNamespace);
        x.WriteRaw("\" id=\""u8);
        x.Write(Id);
        x.WriteRaw("\" name=\""u8);
        x.WriteEscaped(Name);
        x.WriteRaw("\" displayName=\""u8);
        x.WriteEscaped(Name);
        x.WriteRaw("\" ref=\""u8);
        x.WriteEscaped(range);
        x.WriteByte((byte)'"');
        if (!_table.ShowHeaderRow)
        {
            x.WriteRaw(" headerRowCount=\"0\""u8);
        }

        x.WriteRaw(" totalsRowShown=\"0\">"u8);
        if (_table.ShowAutoFilter && _table.ShowHeaderRow)
        {
            x.WriteRaw("<autoFilter ref=\""u8);
            x.WriteEscaped(range);
            x.WriteRaw("\"/>"u8);
        }

        x.WriteRaw("<tableColumns count=\""u8);
        x.Write(_columns.Length);
        x.WriteRaw("\">"u8);
        for (var i = 0; i < _columns.Length; i++)
        {
            x.WriteRaw("<tableColumn id=\""u8);
            x.Write(i + 1);
            x.WriteRaw("\" name=\""u8);
            WriteColumnName(x, _columns[i]);
            x.WriteRaw("\"/>"u8);
        }

        x.WriteRaw("</tableColumns><tableStyleInfo"u8);
        if (_table.Style.Name is { } style)
        {
            x.WriteRaw(" name=\""u8);
            x.WriteEscaped(style);
            x.WriteByte((byte)'"');
        }

        x.WriteRaw(_table.HighlightFirstColumn ? " showFirstColumn=\"1\""u8 : " showFirstColumn=\"0\""u8);
        x.WriteRaw(_table.HighlightLastColumn ? " showLastColumn=\"1\""u8 : " showLastColumn=\"0\""u8);
        x.WriteRaw(_table.ShowRowStripes ? " showRowStripes=\"1\""u8 : " showRowStripes=\"0\""u8);
        x.WriteRaw(_table.ShowColumnStripes ? " showColumnStripes=\"1\""u8 : " showColumnStripes=\"0\""u8);
        x.WriteRaw("/></table>"u8);
    }

    /// <summary>
    /// Writes line breaks and tabs (which attribute normalization would turn into spaces) as
    /// <c>_xHHHH_</c> escapes, as Excel does for table column names.
    /// </summary>
    private static void WriteColumnName(Utf8XmlBuffer x, ReadOnlySpan<char> name)
    {
        while (true)
        {
            var index = name.IndexOfAny('\n', '\r', '\t');
            if (index < 0)
            {
                x.WriteEscaped(name);
                return;
            }

            x.WriteEscaped(name[..index]);
            x.WriteRaw(name[index] switch
            {
                '\n' => "_x000A_"u8,
                '\r' => "_x000D_"u8,
                _ => "_x0009_"u8,
            });
            name = name[(index + 1)..];
        }
    }

    /// <summary>
    /// Table names follow Excel's defined-name rules: they start with a letter, <c>_</c> or <c>\</c>,
    /// contain only letters, digits, <c>_</c>, <c>.</c> and <c>\</c>, are at most 255 characters and
    /// cannot look like a cell reference.
    /// </summary>
    internal static void ValidateName(string name)
    {
        var valid = name.Length is > 0 and <= 255 && !LooksLikeReference(name);
        var first = true;
        foreach (var rune in name.EnumerateRunes())
        {
            if (!valid)
            {
                break;
            }

            valid = first
                ? Rune.IsLetter(rune) || rune.Value is '_' or '\\'
                : Rune.IsLetterOrDigit(rune) || rune.Value is '_' or '.' or '\\';
            first = false;
        }

        if (!valid)
        {
            throw new ArgumentException(
                $"'{name}' is not a valid table name: names start with a letter, '_' or '\\', contain only letters, digits, '_' and '.', and cannot look like a cell reference.",
                nameof(name));
        }
    }

    /// <summary>A1-style (<c>XFD1048576</c>) and R1C1-style (<c>R</c>, <c>C</c>, <c>R2</c>, <c>RC3</c>, …) references are not valid names.</summary>
    private static bool LooksLikeReference(string name)
    {
        var upper = string.Create(name.Length, name, static (span, source) =>
        {
            for (var i = 0; i < span.Length; i++)
            {
                var c = source[i];
                span[i] = char.IsAsciiLetterLower(c) ? (char)(c - 32) : c;
            }
        });
        var letters = 0;
        while (letters < upper.Length && char.IsAsciiLetterUpper(upper[letters]))
        {
            letters++;
        }

        var digits = upper.AsSpan(letters);
        if (letters is >= 1 and <= 3 && !digits.IsEmpty && !digits.ContainsAnyExceptInRange('0', '9'))
        {
            var column = 0;
            foreach (var c in upper.AsSpan(0, letters))
            {
                column = (column * 26) + (c - 'A' + 1);
            }

            if (column <= CellReference.MaxColumns
                && long.TryParse(digits, out var row)
                && row is >= 1 and <= CellReference.MaxRows)
            {
                return true;
            }
        }

        var i = 0;
        if (i < upper.Length && upper[i] == 'R')
        {
            i++;
            while (i < upper.Length && char.IsAsciiDigit(upper[i]))
            {
                i++;
            }
        }

        if (i < upper.Length && upper[i] == 'C')
        {
            i++;
            while (i < upper.Length && char.IsAsciiDigit(upper[i]))
            {
                i++;
            }
        }

        return i > 0 && i == upper.Length;
    }
}

/// <summary>An inclusive, validated range of cells.</summary>
internal readonly record struct CellRange(int FirstRow, int FirstColumn, int LastRow, int LastColumn)
{
    public static CellRange Create(int firstRow, int firstColumn, int lastRow, int lastColumn)
    {
        CellReference.ValidateRow(firstRow);
        CellReference.ValidateColumn(firstColumn);
        CellReference.ValidateRow(lastRow);
        CellReference.ValidateColumn(lastColumn);
        if (lastRow < firstRow || lastColumn < firstColumn)
        {
            throw new ArgumentException("The range end must not precede its start.");
        }

        return new CellRange(firstRow, firstColumn, lastRow, lastColumn);
    }

    /// <summary>True when the two ranges share at least one cell.</summary>
    public bool Overlaps(CellRange other) =>
        FirstRow <= other.LastRow && other.FirstRow <= LastRow && FirstColumn <= other.LastColumn && other.FirstColumn <= LastColumn;

    public override string ToString() => CellReference.ToRange(FirstRow, FirstColumn, LastRow, LastColumn);
}
