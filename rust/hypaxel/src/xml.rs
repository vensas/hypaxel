//! Low-level UTF-8 XML helpers. Markup is appended as byte literals; there is no XML library.

pub(crate) const DECLARATION: &[u8] = b"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
pub(crate) const NS_MAIN: &[u8] = b"http://schemas.openxmlformats.org/spreadsheetml/2006/main";
pub(crate) const NS_REL: &[u8] = b"http://schemas.openxmlformats.org/officeDocument/2006/relationships";

/// Bytes that need attention when escaping: XML specials, `_` (Excel's `_xHHHH_` escape), C0
/// control characters and 0xEF (lead byte of U+FFFE / U+FFFF, which XML 1.0 forbids).
static SPECIAL: [bool; 256] = {
    let mut table = [false; 256];
    let mut i = 0;
    while i < 0x20 {
        table[i] = !matches!(i, 0x09 | 0x0A | 0x0D);
        i += 1;
    }
    table[b'&' as usize] = true;
    table[b'<' as usize] = true;
    table[b'>' as usize] = true;
    table[b'"' as usize] = true;
    table[b'_' as usize] = true;
    table[0xEF] = true;
    table
};

/// Appends `text` escaped for element content or a double-quoted attribute value.
///
/// Characters XML 1.0 cannot represent are written as Excel's `_xHHHH_` escape; literal text that
/// looks like such an escape is protected with `_x005F_` so it round-trips unchanged.
#[inline]
pub(crate) fn escape_into(out: &mut Vec<u8>, text: &str) {
    let bytes = text.as_bytes();
    // Fast path: most cell text contains nothing to escape.
    let Some(first) = bytes.iter().position(|&b| SPECIAL[b as usize]) else {
        out.extend_from_slice(bytes);
        return;
    };
    escape_slow(out, bytes, first);
}

#[cold]
fn escape_slow(out: &mut Vec<u8>, bytes: &[u8], first: usize) {
    out.extend_from_slice(&bytes[..first]);
    let mut i = first;
    let mut plain = first;
    while i < bytes.len() {
        let b = bytes[i];
        if !SPECIAL[b as usize] {
            i += 1;
            continue;
        }

        let replacement: Option<&[u8]> = match b {
            b'&' => Some(b"&amp;"),
            b'<' => Some(b"&lt;"),
            b'>' => Some(b"&gt;"),
            b'"' => Some(b"&quot;"),
            b'_' if looks_like_excel_escape(&bytes[i..]) => Some(b"_x005F_"),
            b'_' => None,
            0xEF if i + 2 < bytes.len() && bytes[i + 1] == 0xBF && (bytes[i + 2] == 0xBE || bytes[i + 2] == 0xBF) => {
                out.extend_from_slice(&bytes[plain..i]);
                out.extend_from_slice(if bytes[i + 2] == 0xBE { b"_xFFFE_" } else { b"_xFFFF_" });
                i += 3;
                plain = i;
                continue;
            }
            0xEF => None,
            control => {
                out.extend_from_slice(&bytes[plain..i]);
                const HEX: &[u8; 16] = b"0123456789ABCDEF";
                out.extend_from_slice(b"_x00");
                out.push(HEX[(control >> 4) as usize]);
                out.push(HEX[(control & 0xF) as usize]);
                out.push(b'_');
                i += 1;
                plain = i;
                continue;
            }
        };

        if let Some(replacement) = replacement {
            out.extend_from_slice(&bytes[plain..i]);
            out.extend_from_slice(replacement);
            plain = i + 1;
        }
        i += 1;
    }
    out.extend_from_slice(&bytes[plain..]);
}

fn looks_like_excel_escape(s: &[u8]) -> bool {
    s.len() >= 7 && s[1] == b'x' && s[2..6].iter().all(u8::is_ascii_hexdigit) && s[6] == b'_'
}

/// Appends a `<t>` element, preserving leading/trailing whitespace.
#[inline]
pub(crate) fn text_element(out: &mut Vec<u8>, text: &str) {
    let b = text.as_bytes();
    let preserve = matches!(b.first(), Some(b' ' | b'\t' | b'\n' | b'\r')) || matches!(b.last(), Some(b' ' | b'\t' | b'\n' | b'\r'));
    out.extend_from_slice(if preserve { b"<t xml:space=\"preserve\">" } else { b"<t>" });
    escape_into(out, text);
    out.extend_from_slice(b"</t>");
}

#[inline]
pub(crate) fn push_int<I: itoa::Integer>(out: &mut Vec<u8>, value: I) {
    let mut buf = itoa::Buffer::new();
    out.extend_from_slice(buf.format(value).as_bytes());
}

/// Shortest round-trip representation (zmij); integral values skip the float formatter.
#[inline]
pub(crate) fn push_f64(out: &mut Vec<u8>, value: f64) {
    if value == value.trunc() && value.abs() < 9_007_199_254_740_992.0 {
        push_int(out, value as i64);
    } else {
        let mut buf = zmij::Buffer::new();
        out.extend_from_slice(buf.format_finite(value).as_bytes());
    }
}

/// Writes an Excel date serial from whole days plus nanoseconds of the day using integer arithmetic
/// only: up to 10 fractional digits (1e-10 day ≈ 8.6 µs, finer than Excel's 1 ms resolution).
pub(crate) fn push_serial(out: &mut Vec<u8>, mut day: i64, nanos_of_day: u64) {
    // 1e-10 day = 8640 ns.
    let mut fraction = (nanos_of_day + 4320) / 8640;
    if fraction >= 10_000_000_000 {
        day += 1;
        fraction -= 10_000_000_000;
    }
    push_int(out, day);
    if fraction == 0 {
        return;
    }
    let mut digits = [b'0'; 10];
    for slot in digits.iter_mut().rev() {
        *slot = b'0' + (fraction % 10) as u8;
        fraction /= 10;
    }
    let len = 10 - digits.iter().rev().take_while(|&&d| d == b'0').count();
    out.push(b'.');
    out.extend_from_slice(&digits[..len]);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn esc(s: &str) -> String {
        let mut v = Vec::new();
        escape_into(&mut v, s);
        String::from_utf8(v).unwrap()
    }

    #[test]
    fn escaping() {
        assert_eq!(esc("plain ünïcødé 😀"), "plain ünïcødé 😀");
        assert_eq!(esc("a&b<c>d\"e'f"), "a&amp;b&lt;c&gt;d&quot;e'f");
        assert_eq!(esc("tab\tnl\ncr\r"), "tab\tnl\ncr\r");
        assert_eq!(esc("\u{1}x\u{1f}"), "_x0001_x_x001F_");
        assert_eq!(esc("a_x0041_b snake_case"), "a_x005F_x0041_b snake_case");
        assert_eq!(esc("\u{FFFE}\u{FFFF}\u{FF01}"), "_xFFFE__xFFFF_\u{FF01}");
    }

    #[test]
    fn numbers() {
        let mut v = Vec::new();
        push_f64(&mut v, 1.0);
        v.push(b' ');
        push_f64(&mut v, -0.1);
        v.push(b' ');
        push_f64(&mut v, 1e300);
        assert_eq!(String::from_utf8(v).unwrap(), "1 -0.1 1e+300");
    }

    #[test]
    fn serials() {
        let mut v = Vec::new();
        push_serial(&mut v, 45292, 0);
        v.push(b' ');
        push_serial(&mut v, 45292, 12 * 3600 * 1_000_000_000);
        v.push(b' ');
        push_serial(&mut v, 45292, 86_400_000_000_000 - 1);
        assert_eq!(String::from_utf8(v).unwrap(), "45292 45292.5 45293");
    }
}
