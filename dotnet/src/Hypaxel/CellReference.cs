using System.Globalization;

namespace Hypaxel;

/// <summary>Helpers for A1-style cell references.</summary>
public static class CellReference
{
    /// <summary>Maximum number of rows in an XLSX worksheet.</summary>
    public const int MaxRows = 1_048_576;

    /// <summary>Maximum number of columns in an XLSX worksheet.</summary>
    public const int MaxColumns = 16_384;

    private static readonly byte[][] s_columnNames = BuildColumnNames();

    private static byte[][] BuildColumnNames()
    {
        // 16384 entries * ~3 bytes: tiny, and avoids any per-cell arithmetic.
        var names = new byte[MaxColumns + 1][];
        names[0] = [];
        Span<byte> tmp = stackalloc byte[3];
        for (var column = 1; column <= MaxColumns; column++)
        {
            var n = column;
            var len = 0;
            while (n > 0)
            {
                n--;
                tmp[2 - len] = (byte)('A' + (n % 26));
                n /= 26;
                len++;
            }

            names[column] = tmp[(3 - len)..].ToArray();
        }

        return names;
    }

    internal static ReadOnlySpan<byte> ColumnNameUtf8(int column) => s_columnNames[column];

    /// <summary>Returns the column letters for a 1-based column index (1 → "A", 27 → "AA").</summary>
    public static string ColumnName(int column)
    {
        ValidateColumn(column);
        return System.Text.Encoding.ASCII.GetString(s_columnNames[column]);
    }

    /// <summary>Returns an A1-style reference for a 1-based row and column.</summary>
    public static string ToA1(int row, int column)
    {
        ValidateRow(row);
        return ColumnName(column) + row.ToString(CultureInfo.InvariantCulture);
    }

    /// <summary>Returns an A1-style range reference, e.g. "A1:C10".</summary>
    public static string ToRange(int firstRow, int firstColumn, int lastRow, int lastColumn)
    {
        if (lastRow < firstRow || lastColumn < firstColumn)
        {
            throw new ArgumentException("The range end must not precede its start.");
        }

        return ToA1(firstRow, firstColumn) + ":" + ToA1(lastRow, lastColumn);
    }

    /// <summary>Returns an absolute A1-style range reference, e.g. "$A$1:$C$10".</summary>
    internal static string ToAbsoluteRange(int firstRow, int firstColumn, int lastRow, int lastColumn)
    {
        return string.Create(
            CultureInfo.InvariantCulture,
            $"${ColumnName(firstColumn)}${firstRow}:${ColumnName(lastColumn)}${lastRow}");
    }

    internal static void ValidateRow(int row)
    {
        if ((uint)(row - 1) >= MaxRows)
        {
            throw new ArgumentOutOfRangeException(nameof(row), row, $"Row must be between 1 and {MaxRows}.");
        }
    }

    internal static void ValidateColumn(int column)
    {
        if ((uint)(column - 1) >= MaxColumns)
        {
            throw new ArgumentOutOfRangeException(nameof(column), column, $"Column must be between 1 and {MaxColumns}.");
        }
    }
}
