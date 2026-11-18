namespace Hypaxel.Internal;

internal static class Xml
{
    public static ReadOnlySpan<byte> Declaration => "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"u8;
    public static ReadOnlySpan<byte> SpreadsheetMlNamespace => "http://schemas.openxmlformats.org/spreadsheetml/2006/main"u8;
    public static ReadOnlySpan<byte> RelationshipsNamespace => "http://schemas.openxmlformats.org/officeDocument/2006/relationships"u8;
}
