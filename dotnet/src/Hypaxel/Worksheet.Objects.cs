using Hypaxel.Mapping;
using Hypaxel.Styling;

namespace Hypaxel;

/// <summary>Options for writing a sequence of objects as rows.</summary>
public sealed class ObjectWriteOptions
{
    /// <summary>Writes a header row with the column names. Default: true.</summary>
    public bool WriteHeader { get; set; } = true;

    /// <summary>Style of the header row. Null applies a bold font with a light gray fill.</summary>
    public StyleId? HeaderStyle { get; set; }

    /// <summary>Adds an auto filter over the written range (requires <see cref="WriteHeader"/>). Default: false.</summary>
    public bool AutoFilter { get; set; }
}

public sealed partial class Worksheet
{
    /// <summary>
    /// Writes one row per item, one column per public property (see <see cref="ExcelColumnAttribute"/>
    /// and <see cref="ExcelIgnoreAttribute"/>). Rows start below any rows already written.
    /// </summary>
    public async ValueTask WriteObjectsAsync<T>(IEnumerable<T> items, ObjectWriteOptions? options = null, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(items);
        var ctx = BeginObjects<T>(options);
        foreach (var item in items)
        {
            WriteObjectRow(ctx, item);
            await _writer.DrainIfNeededAsync(cancellationToken).ConfigureAwait(false);
        }

        EndObjects(ctx, options);
    }

    /// <summary>Asynchronous-stream variant of <see cref="WriteObjectsAsync{T}(IEnumerable{T}, ObjectWriteOptions?, CancellationToken)"/>, e.g. for EF Core <c>AsAsyncEnumerable()</c>.</summary>
    public async ValueTask WriteObjectsAsync<T>(IAsyncEnumerable<T> items, ObjectWriteOptions? options = null, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(items);
        var ctx = BeginObjects<T>(options);
        await foreach (var item in items.WithCancellation(cancellationToken).ConfigureAwait(false))
        {
            WriteObjectRow(ctx, item);
            await _writer.DrainIfNeededAsync(cancellationToken).ConfigureAwait(false);
        }

        EndObjects(ctx, options);
    }

    /// <summary>Synchronous variant of <see cref="WriteObjectsAsync{T}(IEnumerable{T}, ObjectWriteOptions?, CancellationToken)"/>.</summary>
    public void WriteObjects<T>(IEnumerable<T> items, ObjectWriteOptions? options = null)
    {
        ArgumentNullException.ThrowIfNull(items);
        var ctx = BeginObjects<T>(options);
        foreach (var item in items)
        {
            WriteObjectRow(ctx, item);
            _writer.DrainIfNeeded();
        }

        EndObjects(ctx, options);
    }

    private sealed class ObjectContext<T>(TypeMap<T> map, StyleId[] styles, int firstRow)
    {
        public TypeMap<T> Map { get; } = map;
        public StyleId[] Styles { get; } = styles;
        public int FirstRow { get; } = firstRow;
    }

    private ObjectContext<T> BeginObjects<T>(ObjectWriteOptions? options)
    {
        EnsureWritable();
        var map = TypeMap<T>.Instance;
        if (map.Headers.Length == 0)
        {
            throw new InvalidOperationException($"Type {typeof(T)} has no public readable properties to export.");
        }

        var styles = new StyleId[map.Headers.Length];
        for (var i = 0; i < styles.Length; i++)
        {
            if (map.NumberFormats[i] is { } format)
            {
                styles[i] = _writer.Styles.Add(new CellStyle { NumberFormat = format });
            }
        }

        var firstRow = NextRowNumber;
        if (options?.WriteHeader ?? true)
        {
            var headerStyle = options?.HeaderStyle ?? _writer.Styles.Add(DefaultHeaderStyle);
            BeginNextRow();
            foreach (var header in map.Headers)
            {
                WriteString(header, headerStyle.Index);
            }

            CloseRow();
        }

        return new ObjectContext<T>(map, styles, firstRow);
    }

    private void WriteObjectRow<T>(ObjectContext<T> ctx, T item)
    {
        BeginNextRow();
        if (item is not null)
        {
            var writers = ctx.Map.Writers;
            var styles = ctx.Styles;
            for (var i = 0; i < writers.Length; i++)
            {
                writers[i](this, item, styles[i]);
            }
        }

        CloseRow();
    }

    private void EndObjects<T>(ObjectContext<T> ctx, ObjectWriteOptions? options)
    {
        if (options is { AutoFilter: true, WriteHeader: true })
        {
            SetAutoFilter(ctx.FirstRow, 1, Math.Max(_row, ctx.FirstRow), ctx.Map.Headers.Length);
        }
    }

    internal static readonly CellStyle DefaultHeaderStyle = new()
    {
        Font = new Font { Bold = true },
        Fill = new Fill(ExcelColor.LightGray),
        Border = new Border { Bottom = new BorderLine(BorderLineStyle.Thin) },
    };
}
