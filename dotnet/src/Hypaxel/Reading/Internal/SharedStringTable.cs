namespace Hypaxel.Internal.Reading;

/// <summary>The shared string table, materialized once so that reading shared strings never allocates.</summary>
internal static class SharedStringTableReader
{
    public static string[] Parse(Utf8XmlReader xml, long maxBytes)
    {
        var strings = new List<string>();
        var text = new CharBuffer(1024);
        long bytes = 0;
        while (true)
        {
            var token = xml.Next();
            if (token == XmlToken.Eof)
            {
                break;
            }

            if (token is not (XmlToken.Start or XmlToken.Empty) || !xml.Name.SequenceEqual("si"u8))
            {
                continue;
            }

            if (token == XmlToken.Empty)
            {
                strings.Add(string.Empty);
                continue;
            }

            text.Clear();
            ReadItem(xml, text, maxBytes - bytes, maxBytes);
            bytes += System.Text.Encoding.UTF8.GetByteCount(text.Span);
            if (bytes > maxBytes)
            {
                throw TooLarge(xml, maxBytes);
            }

            strings.Add(text.Length == 0 ? string.Empty : text.Span.ToString());
        }

        return [.. strings];
    }

    private static XlsxReadException TooLarge(Utf8XmlReader xml, long maxBytes) => XlsxReadException.LimitExceeded(
        xml.Part,
        $"The shared string table exceeds {maxBytes} bytes (XlsxReaderOptions.MaxSharedStringsBytes).");

    /// <summary>Reads one <c>&lt;si&gt;</c> item: plain <c>&lt;t&gt;</c> or rich-text runs, without phonetic runs.</summary>
    private static void ReadItem(Utf8XmlReader xml, CharBuffer text, long remainingBytes, long maxBytes)
    {
        var target = xml.Depth - 1;
        while (true)
        {
            var token = xml.Next();
            if (token == XmlToken.End && xml.Depth == target)
            {
                return;
            }

            if (token != XmlToken.Start)
            {
                continue;
            }

            var name = xml.Name;
            if (name.SequenceEqual("t"u8))
            {
                xml.ReadTextInto(text, excelEscapes: true);

                // Every UTF-16 code unit takes at least one UTF-8 byte.
                if (text.Length > remainingBytes)
                {
                    throw TooLarge(xml, maxBytes);
                }
            }
            else if (name.SequenceEqual("rPh"u8) || name.SequenceEqual("rPr"u8) || name.SequenceEqual("phoneticPr"u8))
            {
                xml.SkipElement();
            }
        }
    }
}
