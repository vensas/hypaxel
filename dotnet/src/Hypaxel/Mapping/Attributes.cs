namespace Hypaxel.Mapping;

/// <summary>Customizes how a property is exported by the object-mapping APIs.</summary>
[AttributeUsage(AttributeTargets.Property | AttributeTargets.Field, AllowMultiple = false)]
public sealed class ExcelColumnAttribute : Attribute
{
    /// <summary>Creates the attribute.</summary>
    public ExcelColumnAttribute()
    {
    }

    /// <summary>Creates the attribute with a header text.</summary>
    public ExcelColumnAttribute(string name) => Name = name;

    /// <summary>Header text. Defaults to the member name.</summary>
    public string? Name { get; set; }

    /// <summary>Sort key for column order. Columns without an order keep declaration order, after ordered ones.</summary>
    public int Order { get; set; } = int.MaxValue;

    /// <summary>Excel number format for the column's cells, e.g. "#,##0.00" or "yyyy-mm-dd".</summary>
    public string? NumberFormat { get; set; }

    /// <summary>Column width in characters; 0 means "estimate from the header".</summary>
    public double Width { get; set; }
}

/// <summary>Excludes a property from the object-mapping APIs.</summary>
[AttributeUsage(AttributeTargets.Property | AttributeTargets.Field, AllowMultiple = false)]
public sealed class ExcelIgnoreAttribute : Attribute
{
}
