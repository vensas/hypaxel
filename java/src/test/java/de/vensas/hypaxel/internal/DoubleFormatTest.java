package de.vensas.hypaxel.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DoubleFormatTest {
    private static String format(double value) {
        byte[] buffer = new byte[DoubleFormat.MAX_LENGTH];
        int length = DoubleFormat.write(value, buffer, 0);
        return new String(buffer, 0, length, StandardCharsets.US_ASCII);
    }

    private static int significantDigits(String text) {
        return new BigDecimal(text).stripTrailingZeros().precision();
    }

    private static void assertShortestRoundTrip(double value) {
        String text = format(value);
        assertEquals(value, Double.parseDouble(text), () -> "round trip of " + Double.toString(value) + " via " + text);
        // Double.toString is the shortest form from JDK 19 on and never shorter than it before.
        assertTrue(significantDigits(text) <= significantDigits(Double.toString(value)),
                () -> text + " is longer than " + Double.toString(value));
    }

    @Test
    void formatsKnownValues() {
        assertEquals("0", format(0.0));
        assertEquals("0.1", format(0.1));
        assertEquals("-0.1", format(-0.1));
        assertEquals("1.25", format(1.25));
        assertEquals("123456.789", format(123456.789));
        assertEquals("0.3333333333333333", format(1.0 / 3));
        assertEquals("14285.714285714286", format(100000 / 7.0));
        assertEquals("0.000001", format(1e-6));
        assertEquals("0.0000001", format(1e-7));
        assertEquals("1E-8", format(1e-8));
        assertEquals("1.5E-10", format(1.5e-10));
        assertEquals("100000000000000000000", format(1e20));
        assertEquals("1E21", format(1e21));
        assertEquals("1E300", format(1e300));
        assertEquals("1.7976931348623157E308", format(Double.MAX_VALUE));
        assertEquals("5E-324", format(Double.MIN_VALUE));
        assertEquals("2.2250738585072014E-308", format(Double.MIN_NORMAL));
        assertEquals("9007199254740994", format(9007199254740994.0));
    }

    @Test
    void roundTripsBoundaryValues() {
        double[] values = {
            Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE, Math.nextDown(Double.MIN_NORMAL), Math.nextUp(1.0),
            Math.nextDown(1.0), 5e-324, 1e23, 9.5e-5, 2.5, 0.5, 1e15 + 0.5, 4.35, 0.07, 1e-320, 123456789012345680.0,
            9007199254740992.0, 9007199254740994.0, 0.1 + 0.2, Math.PI, Math.E, 1e22, 1e-5, 299792458.123,
        };
        for (double value : values) {
            assertShortestRoundTrip(value);
            assertShortestRoundTrip(-value);
        }
        for (int exponent = -1074; exponent <= 1023; exponent++) {
            double power = Math.scalb(1.0, exponent);
            assertShortestRoundTrip(power);
            assertShortestRoundTrip(Math.nextUp(power));
            assertShortestRoundTrip(Math.nextDown(power));
        }
        for (int exponent = -323; exponent <= 308; exponent++) {
            assertShortestRoundTrip(Double.parseDouble("1E" + exponent));
            double nines = Double.parseDouble("9.999999999999999E" + exponent);
            if (Double.isFinite(nines)) {
                assertShortestRoundTrip(nines);
            }
        }
    }

    @Test
    void roundTripsRandomValues() {
        SplittableRandom random = new SplittableRandom(42);
        for (int i = 0; i < 300_000; i++) {
            double value = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(value)) {
                assertShortestRoundTrip(value);
            }
            assertShortestRoundTrip(random.nextInt(1_000_000) / 100.0);
            assertShortestRoundTrip(random.nextDouble() * 1e6);
            assertShortestRoundTrip(i / 7.0);
        }
    }
}
