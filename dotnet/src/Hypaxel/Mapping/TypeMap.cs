using System.Linq.Expressions;
using System.Reflection;
using Hypaxel.Styling;

namespace Hypaxel.Mapping;

/// <summary>
/// Per-type column accessors, compiled once into delegates that call the strongly typed
/// <see cref="Worksheet"/> overloads directly (no boxing of value-type properties).
/// </summary>
internal sealed class TypeMap<T>
{
    private TypeMap()
    {
        var members = typeof(T)
            .GetMembers(BindingFlags.Public | BindingFlags.Instance)
            .Where(m => m is PropertyInfo { CanRead: true } p && p.GetIndexParameters().Length == 0 && p.GetMethod!.IsPublic
                        || m is FieldInfo)
            .Where(m => m.GetCustomAttribute<ExcelIgnoreAttribute>() is null)
            .Select(m => (Member: m, Attr: m.GetCustomAttribute<ExcelColumnAttribute>()))
            .OrderBy(m => m.Attr?.Order ?? int.MaxValue)
            .ThenBy(m => m.Member is FieldInfo ? 1 : 0) // tokens only reflect declaration order within one kind
            .ThenBy(m => m.Member.MetadataToken)
            .ToArray();

        Headers = new string[members.Length];
        NumberFormats = new string?[members.Length];
        Widths = new double[members.Length];
        Writers = new Action<Worksheet, T, StyleId>[members.Length];

        for (var i = 0; i < members.Length; i++)
        {
            var (member, attr) = members[i];
            Headers[i] = attr?.Name ?? member.Name;
            NumberFormats[i] = attr?.NumberFormat;
            Widths[i] = attr is { Width: > 0 } ? attr.Width : EstimateWidth(Headers[i], MemberType(member));
            Writers[i] = Compile(member);
        }
    }

    public string[] Headers { get; }

    public string?[] NumberFormats { get; }

    public double[] Widths { get; }

    public Action<Worksheet, T, StyleId>[] Writers { get; }

    private static Type MemberType(MemberInfo member) => member switch
    {
        PropertyInfo p => p.PropertyType,
        FieldInfo f => f.FieldType,
        _ => throw new NotSupportedException(),
    };

    private static double EstimateWidth(string header, Type type)
    {
        var underlying = Nullable.GetUnderlyingType(type) ?? type;
        var typical = underlying == typeof(DateTime) || underlying == typeof(DateTimeOffset) ? 18
            : underlying == typeof(DateOnly) ? 12
            : underlying == typeof(string) ? 20
            : underlying == typeof(Guid) ? 38
            : 12;
        return Math.Min(Math.Max(header.Length + 3, typical), 60);
    }

    private static readonly Type[] s_directTypes =
    [
        typeof(string), typeof(int), typeof(long), typeof(double), typeof(float), typeof(decimal), typeof(bool),
        typeof(DateTime), typeof(DateTimeOffset), typeof(DateOnly), typeof(TimeOnly), typeof(TimeSpan),
    ];

    private static Action<Worksheet, T, StyleId> Compile(MemberInfo member)
    {
        var sheet = Expression.Parameter(typeof(Worksheet), "sheet");
        var item = Expression.Parameter(typeof(T), "item");
        var style = Expression.Parameter(typeof(StyleId), "style");

        Expression access = member switch
        {
            PropertyInfo p => Expression.Property(item, p),
            FieldInfo f => Expression.Field(item, f),
            _ => throw new NotSupportedException(),
        };

        var type = access.Type;
        var underlying = Nullable.GetUnderlyingType(type);
        Expression body;
        if (underlying is not null)
        {
            var value = Expression.Variable(type, "value");
            body = Expression.Block(
                [value],
                Expression.Assign(value, access),
                Expression.IfThenElse(
                    Expression.Property(value, "HasValue"),
                    WriteCall(sheet, Expression.Call(value, type.GetMethod("GetValueOrDefault", Type.EmptyTypes)!), style),
                    WriteNull(sheet, style)));
        }
        else
        {
            body = WriteCall(sheet, access, style);
        }

        return Expression.Lambda<Action<Worksheet, T, StyleId>>(body, sheet, item, style).Compile();
    }

    private static MethodCallExpression WriteNull(Expression sheet, Expression style) =>
        Expression.Call(sheet, WriteMethod(typeof(string)), Expression.Constant(null, typeof(string)), style);

    private static Expression WriteCall(Expression sheet, Expression value, Expression style)
    {
        var type = value.Type;
        if (Array.IndexOf(s_directTypes, type) >= 0)
        {
            return Expression.Call(sheet, WriteMethod(type), value, style);
        }

        if (type == typeof(short) || type == typeof(ushort) || type == typeof(byte) || type == typeof(sbyte))
        {
            return Expression.Call(sheet, WriteMethod(typeof(int)), Expression.Convert(value, typeof(int)), style);
        }

        if (type == typeof(uint))
        {
            return Expression.Call(sheet, WriteMethod(typeof(long)), Expression.Convert(value, typeof(long)), style);
        }

        if (type == typeof(ulong))
        {
            return Expression.Call(sheet, WriteMethod(typeof(double)), Expression.Convert(value, typeof(double)), style);
        }

        if (type.IsEnum || type == typeof(Guid) || type == typeof(char))
        {
            var toString = Expression.Call(value, type.GetMethod(nameof(ToString), Type.EmptyTypes)!);
            return Expression.Call(sheet, WriteMethod(typeof(string)), toString, style);
        }

        var writeValue = typeof(Worksheet).GetMethod(nameof(Worksheet.WriteValue), [typeof(object), typeof(StyleId)])!;
        return Expression.Call(sheet, writeValue, Expression.Convert(value, typeof(object)), style);
    }

    private static MethodInfo WriteMethod(Type type) =>
        typeof(Worksheet).GetMethod(nameof(Worksheet.Write), [type, typeof(StyleId)])
        ?? throw new InvalidOperationException($"No Worksheet.Write overload for {type}.");

    // Declared last: static fields initialize in textual order and the constructor relies on the ones above.
    public static readonly TypeMap<T> Instance = new();
}
