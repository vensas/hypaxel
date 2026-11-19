package de.vensas.hypaxel.internal;

import java.math.BigInteger;

/**
 * Shortest decimal representation of a double that reads back as the same value, written straight
 * into a byte array. Implements the Schubfach algorithm (Raffaello Giulietti, "The Schubfach way
 * to render doubles", 2020) so that the write loop does not create a {@code String} per number as
 * {@link Double#toString(double)} would.
 */
public final class DoubleFormat {
    /** Longest output: sign, 17 digits, decimal point and up to 20 padding zeros. */
    static final int MAX_LENGTH = 40;

    private static final int MIN_POWER = -292;
    private static final int MAX_POWER = 324;
    private static final int MAX_PLAIN_EXPONENT = 20;
    private static final int MIN_PLAIN_EXPONENT = -7;

    /** 128-bit approximations of 10^k for k in MIN_POWER..MAX_POWER, as (high, low) pairs. */
    private static final long[] POWERS = new long[(MAX_POWER - MIN_POWER + 1) * 2];

    static {
        BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        for (int k = MIN_POWER; k <= MAX_POWER; k++) {
            BigInteger g;
            if (k >= 0) {
                BigInteger power = BigInteger.TEN.pow(k);
                int shift = power.bitLength() - 128;
                g = shift <= 0 ? power.shiftLeft(-shift) : ceilDivide(power, BigInteger.ONE.shiftLeft(shift));
            } else {
                BigInteger divisor = BigInteger.TEN.pow(-k);
                g = ceilDivide(BigInteger.ONE.shiftLeft(divisor.bitLength() + 127), divisor);
            }
            int index = (k - MIN_POWER) * 2;
            POWERS[index] = g.shiftRight(64).longValue();
            POWERS[index + 1] = g.and(mask).longValue();
        }
    }

    private DoubleFormat() {
    }

    private static BigInteger ceilDivide(BigInteger dividend, BigInteger divisor) {
        BigInteger[] parts = dividend.divideAndRemainder(divisor);
        return parts[1].signum() == 0 ? parts[0] : parts[0].add(BigInteger.ONE);
    }

    /** Returns the shortest text that reads back as {@code value}, as Excel's General format shows it. */
    public static String toString(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        byte[] buf = new byte[MAX_LENGTH];
        int length = write(value, buf, 0);
        return new String(buf, 0, length, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /**
     * Writes a finite value at {@code pos} and returns the position after it. The caller provides
     * at least {@link #MAX_LENGTH} bytes.
     */
    static int write(double value, byte[] buf, int pos) {
        long bits = Double.doubleToRawLongBits(value);
        long fraction = bits & 0xF_FFFF_FFFF_FFFFL;
        int biasedExponent = (int) (bits >>> 52) & 0x7FF;
        if (biasedExponent == 0 && fraction == 0) {
            buf[pos] = '0';
            return pos + 1;
        }
        if (bits < 0) {
            buf[pos++] = '-';
        }

        long c;
        int q;
        if (biasedExponent != 0) {
            c = fraction | 0x10_0000_0000_0000L;
            q = biasedExponent - 1075;
        } else {
            c = fraction;
            q = -1074;
        }

        boolean even = (c & 1) == 0;
        boolean lowerBoundaryCloser = fraction == 0 && biasedExponent > 1;
        long cbl = 4 * c - 2 + (lowerBoundaryCloser ? 1 : 0);
        long cb = 4 * c;
        long cbr = 4 * c + 2;

        int k = lowerBoundaryCloser ? (q * 1262611 - 524031) >> 22 : (q * 1262611) >> 22;
        int h = q + ((-k * 1741647) >> 19) + 1;
        int index = (-k - MIN_POWER) * 2;
        long g1 = POWERS[index];
        long g0 = POWERS[index + 1];

        long vbl = roundToOdd(g1, g0, cbl << h);
        long vb = roundToOdd(g1, g0, cb << h);
        long vbr = roundToOdd(g1, g0, cbr << h);
        long lower = vbl + (even ? 0 : 1);
        long upper = vbr - (even ? 0 : 1);

        long s = vb >>> 2;
        long digits;
        int exponent;
        if (s >= 10 && shorterInside(s / 10, lower, upper)) {
            long sp = s / 10;
            digits = sp + (40 * sp + 40 <= upper ? 1 : 0);
            exponent = k + 1;
        } else {
            boolean uInside = lower <= 4 * s;
            boolean wInside = 4 * s + 4 <= upper;
            if (uInside != wInside) {
                digits = s + (wInside ? 1 : 0);
            } else {
                long mid = 4 * s + 2;
                boolean roundUp = vb > mid || (vb == mid && (s & 1) != 0);
                digits = s + (roundUp ? 1 : 0);
            }
            exponent = k;
        }
        while (digits % 10 == 0) {
            digits /= 10;
            exponent++;
        }
        return render(digits, exponent, buf, pos);
    }

    private static boolean shorterInside(long sp, long lower, long upper) {
        boolean upInside = lower <= 40 * sp;
        boolean wpInside = 40 * sp + 40 <= upper;
        return upInside != wpInside;
    }

    /** Upper 64 bits of the 192-bit product g * cp, with any lost lower bits folded into bit 0. */
    private static long roundToOdd(long g1, long g0, long cp) {
        long x1 = unsignedMultiplyHigh(g0, cp);
        long y0 = g1 * cp;
        long y1 = unsignedMultiplyHigh(g1, cp);
        y0 += x1;
        if (Long.compareUnsigned(y0, x1) < 0) {
            y1++;
        }
        return y1 | (y0 >>> 1 != 0 ? 1 : 0);
    }

    private static long unsignedMultiplyHigh(long a, long b) {
        return Math.multiplyHigh(a, b) + ((a >> 63) & b) + ((b >> 63) & a);
    }

    private static int render(long digits, int exponent, byte[] buf, int pos) {
        int count = 1;
        for (long limit = 10; count < 18 && digits >= limit; limit *= 10) {
            count++;
        }
        int scientificExponent = exponent + count - 1;

        if (exponent >= 0 && scientificExponent <= MAX_PLAIN_EXPONENT) {
            pos = putDigits(digits, count, buf, pos);
            for (int i = 0; i < exponent; i++) {
                buf[pos++] = '0';
            }
            return pos;
        }
        if (exponent < 0 && scientificExponent >= 0) {
            int end = putDigits(digits, count, buf, pos);
            int point = pos + scientificExponent + 1;
            System.arraycopy(buf, point, buf, point + 1, end - point);
            buf[point] = '.';
            return end + 1;
        }
        if (exponent < 0 && scientificExponent >= MIN_PLAIN_EXPONENT) {
            buf[pos++] = '0';
            buf[pos++] = '.';
            for (int i = -1; i > scientificExponent; i--) {
                buf[pos++] = '0';
            }
            return putDigits(digits, count, buf, pos);
        }

        int end = putDigits(digits, count, buf, pos);
        if (count > 1) {
            System.arraycopy(buf, pos + 1, buf, pos + 2, end - pos - 1);
            buf[pos + 1] = '.';
            end++;
        }
        buf[end++] = 'E';
        if (scientificExponent < 0) {
            buf[end++] = '-';
            scientificExponent = -scientificExponent;
        }
        return putDigits(scientificExponent, scientificExponent >= 100 ? 3 : scientificExponent >= 10 ? 2 : 1, buf, end);
    }

    private static int putDigits(long value, int count, byte[] buf, int pos) {
        for (int i = pos + count - 1; i >= pos; i--) {
            buf[i] = (byte) ('0' + value % 10);
            value /= 10;
        }
        return pos + count;
    }
}
