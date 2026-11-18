namespace Hypaxel.Internal;

/// <summary>De-duplicating string table serialized to <c>xl/sharedStrings.xml</c>.</summary>
internal sealed class SharedStringTable
{
    private readonly Dictionary<string, int> _ids = new(StringComparer.Ordinal);
    private readonly List<string> _strings = [];

    public int UniqueCount => _strings.Count;

    public long ReferenceCount { get; private set; }

    public int GetOrAdd(string value)
    {
        ReferenceCount++;
        ref var slot = ref System.Runtime.InteropServices.CollectionsMarshal.GetValueRefOrAddDefault(_ids, value, out var exists);
        if (!exists)
        {
            slot = _strings.Count;
            _strings.Add(value);
        }

        return slot;
    }

    public void WriteTo(Utf8XmlBuffer x)
    {
        x.WriteRaw(Xml.Declaration);
        x.WriteRaw("<sst xmlns=\""u8);
        x.WriteRaw(Xml.SpreadsheetMlNamespace);
        x.WriteRaw("\" count=\""u8);
        x.Write(ReferenceCount);
        x.WriteRaw("\" uniqueCount=\""u8);
        x.Write(_strings.Count);
        x.WriteRaw("\">"u8);
        foreach (var s in _strings)
        {
            x.WriteRaw("<si>"u8);
            CellText.Write(x, s);
            x.WriteRaw("</si>"u8);
        }

        x.WriteRaw("</sst>"u8);
    }
}

internal static class CellText
{
    /// <summary>Writes a <c>&lt;t&gt;</c> element, preserving leading/trailing whitespace when present.</summary>
    public static void Write(Utf8XmlBuffer x, string s)
    {
        if (s.Length > 0 && (IsXmlWhitespace(s[0]) || IsXmlWhitespace(s[^1])))
        {
            x.WriteRaw("<t xml:space=\"preserve\">"u8);
        }
        else
        {
            x.WriteRaw("<t>"u8);
        }

        x.WriteEscaped(s);
        x.WriteRaw("</t>"u8);
    }

    private static bool IsXmlWhitespace(char c) => c is ' ' or '\t' or '\n' or '\r';
}
