package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnFitTest {
    private final XmlBuffer scratch = new XmlBuffer(32);

    private float px(String code, double value) {
        return ColumnFit.numberPx(ColumnFit.customShape(code), value, scratch);
    }

    private static float text(String value) {
        return ColumnFit.textPx(value);
    }

    @Test
    void measuresText() {
        assertEquals(0, text(""));
        assertEquals(70, text("0123456789"));
        assertEquals(text("longer line"), text("ab\nlonger line"));
        assertEquals(text("b"), text("a\r\nb"));
        assertTrue(text("WWW") > text("iii"));
        assertEquals(30, text("日本"));
        assertEquals(15, text("😀"));
        assertEquals(8, text("é"));
    }

    @Test
    void measuresNumberFormats() {
        assertEquals(text("1,234,567.89"), px("#,##0.00", 1234567.891));
        assertEquals(text("25.6%"), px("0.0%", 0.256));
        assertEquals(text("-42"), px("0", -42));
        assertEquals(text("007"), px("000", 7));
        assertEquals(text("$1,500"), px("\"$\"#,##0", 1500));
        assertEquals(text("€ 10.00"), px("[$€-407] #,##0.00", 10));
        assertEquals(text("2026-12-31"), px("yyyy-mm-dd", 45000));
        assertEquals(text("31 September 2026"), px("dd mmmm yyyy", 45000));
        assertEquals(text("Wed, Sep 31 26"), px("ddd, mmm dd yy", 45000));
        assertEquals(text("12:00 PM"), px("h:mm AM/PM", 0.5));
        assertEquals(text("3.25"), px("General", 3.25));
        assertEquals(text("0.333333333"), px("@", 1.0 / 3.0));
        assertEquals(text("0.333333333"), px("", 1.0 / 3.0));
        assertEquals(text("100:00:00"), px("[h]:mm:ss", 4.2));
        assertEquals(7 * 5 + text(".E+"), px("0.00E+00", 12345));
        assertEquals(text("12 x"), px("0\\ \\x", 12));
        assertEquals(text("12 )"), px("0_)* ", 12) + text(" "));
        assertEquals(text("12"), px("[Red]0;[Blue]-0", 12));
        assertEquals(px("0", 0), text("0"));
    }

    @Test
    void measuresBuiltinFormats() {
        assertEquals(text("1,234.00"), ColumnFit.numberPx(ColumnFit.builtinShape(4), 1234, scratch));
        assertEquals(text("50%"), ColumnFit.numberPx(ColumnFit.builtinShape(9), 0.5, scratch));
        assertEquals(text("12/31/2026"), ColumnFit.numberPx(ColumnFit.builtinShape(14), 45000, scratch));
        assertEquals(text("42"), ColumnFit.numberPx(ColumnFit.builtinShape(49), 42, scratch));
        for (int id : new int[] {1, 2, 3, 10, 11, 21, 22, 46}) {
            assertTrue(ColumnFit.numberPx(ColumnFit.builtinShape(id), 1, scratch) > 0);
        }
    }

    @Test
    void measuresGeneralNumbers() {
        assertEquals(text("42"), ColumnFit.generalPx(42, scratch));
        assertEquals(text("1.23457E+11"), ColumnFit.generalPx(1e15, scratch));
        assertEquals(text("-1.23457E+11"), ColumnFit.generalPx(-1e-12, scratch));
        assertEquals(text("#NUM!"), ColumnFit.generalPx(Double.NaN, scratch));
        assertEquals(text("0"), ColumnFit.generalPx(0, scratch));
    }
}
