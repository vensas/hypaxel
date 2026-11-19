package de.vensas.hypaxel.internal;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Parsing of cell numbers from UTF-8 bytes, correctly rounded like {@link Double#parseDouble}.
 *
 * <p>Three tiers: Clinger's fast path for up to 15 digits without an exponent, the Eisel-Lemire
 * algorithm (D. Lemire, "Number Parsing at a Gigabyte per Second", 2021) for up to 19 significant
 * digits, and {@link Double#parseDouble} for the rare inputs neither can decide.
 */
final class Numbers {
    /** Exact powers of ten as doubles (10^22 is the largest one). */
    private static final double[] POWERS_OF_TEN = new double[23];

    private static final int MIN_EXPONENT = -348;
    private static final int MAX_EXPONENT = 347;
    /** 128-bit approximations of 10^e, rounded down and normalized to a set top bit, as (high, low) pairs. */
    private static final long[] POWERS = new long[(MAX_EXPONENT - MIN_EXPONENT + 1) * 2];

    static {
        double p = 1;
        for (int i = 0; i < POWERS_OF_TEN.length; i++) {
            POWERS_OF_TEN[i] = p;
            p *= 10;
        }
        BigInteger mask = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        for (int e = MIN_EXPONENT; e <= MAX_EXPONENT; e++) {
            BigInteger m;
            if (e >= 0) {
                BigInteger power = BigInteger.TEN.pow(e);
                int shift = power.bitLength() - 128;
                m = shift <= 0 ? power.shiftLeft(-shift) : power.shiftRight(shift);
            } else {
                BigInteger divisor = BigInteger.TEN.pow(-e);
                m = BigInteger.ONE.shiftLeft(divisor.bitLength() + 127).divide(divisor);
            }
            int index = (e - MIN_EXPONENT) * 2;
            POWERS[index] = m.shiftRight(64).longValue();
            POWERS[index + 1] = m.and(mask).longValue();
        }
    }

    private Numbers() {
    }

    /**
     * True if {@code s} only contains characters of a decimal number. {@link Double#parseDouble}
     * also accepts {@code NaN}, {@code Infinity}, hexadecimal and type suffixes such as {@code 1d},
     * which are not cell numbers.
     */
    static boolean isDecimalSyntax(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses a finite decimal number from {@code b[from..to)}, without surrounding whitespace.
     * Returns NaN if the text is not a finite number.
     */
    static double parse(byte[] b, int from, int to) {
        if (from >= to) {
            return Double.NaN;
        }
        boolean negative = b[from] == '-';
        int i = negative || b[from] == '+' ? from + 1 : from;
        long mantissa = 0;
        int digits = 0;
        int fractionDigits = 0;
        int dropped = 0;
        boolean point = false;
        boolean any = false;
        for (; i < to; i++) {
            int c = b[i];
            if (c >= '0' && c <= '9') {
                any = true;
                if (digits < 19) {
                    if (mantissa != 0 || c != '0') {
                        mantissa = mantissa * 10 + (c - '0');
                        digits++;
                    }
                    if (point) {
                        fractionDigits++;
                    }
                } else {
                    // A 20th significant digit: Eisel-Lemire would need it, so defer to the JDK.
                    if (c != '0') {
                        return parseSlow(b, from, to);
                    }
                    if (!point) {
                        dropped++;
                    }
                }
            } else if (c == '.' && !point) {
                point = true;
            } else {
                break;
            }
        }
        if (!any) {
            return Double.NaN;
        }
        long exponent = 0;
        if (i < to) {
            int c = b[i];
            if (c != 'e' && c != 'E') {
                return Double.NaN;
            }
            i++;
            boolean negativeExponent = false;
            if (i < to && (b[i] == '-' || b[i] == '+')) {
                negativeExponent = b[i] == '-';
                i++;
            }
            if (i == to) {
                return Double.NaN;
            }
            for (; i < to; i++) {
                c = b[i];
                if (c < '0' || c > '9') {
                    return Double.NaN;
                }
                if (exponent < 100_000) {
                    exponent = exponent * 10 + (c - '0');
                }
            }
            if (negativeExponent) {
                exponent = -exponent;
            }
        }
        long exp10 = exponent - fractionDigits + dropped;
        if (mantissa == 0) {
            return negative ? -0.0 : 0.0;
        }
        if (exp10 <= 0 && exp10 >= -22 && (mantissa >>> 53) == 0) {
            double value = mantissa / POWERS_OF_TEN[(int) -exp10];
            return negative ? -value : value;
        }
        if (exp10 > 0 && exp10 <= 22 && (mantissa >>> 53) == 0) {
            double value = mantissa * POWERS_OF_TEN[(int) exp10];
            return negative ? -value : value;
        }
        if (exp10 < MIN_EXPONENT) {
            return negative ? -0.0 : 0.0;
        }
        if (exp10 > MAX_EXPONENT) {
            return Double.NaN;
        }
        long bits = eiselLemire(mantissa, (int) exp10);
        if (bits == -1) {
            return parseSlow(b, from, to);
        }
        if (bits == -2) {
            return Double.NaN;
        }
        double value = Double.longBitsToDouble(bits);
        return negative ? -value : value;
    }

    private static long unsignedMultiplyHigh(long a, long b) {
        return Math.multiplyHigh(a, b) + ((a >> 63) & b) + ((b >> 63) & a);
    }

    /**
     * Returns the bits of {@code mantissa * 10^exp10} (positive, mantissa != 0), -1 if the result
     * cannot be decided this way (or is subnormal) and -2 if it overflows.
     */
    static long eiselLemire(long mantissa, int exp10) {
        int index = (exp10 - MIN_EXPONENT) * 2;
        long powerHigh = POWERS[index];
        long powerLow = POWERS[index + 1];
        int clz = Long.numberOfLeadingZeros(mantissa);
        long man = mantissa << clz;
        long exp2 = ((217_706L * exp10) >> 16) + 64 + 1023 - clz;

        long xHi = unsignedMultiplyHigh(man, powerHigh);
        long xLo = man * powerHigh;
        if ((xHi & 0x1FF) == 0x1FF && Long.compareUnsigned(xLo + man, man) < 0) {
            long yHi = unsignedMultiplyHigh(man, powerLow);
            long yLo = man * powerLow;
            long mergedHi = xHi;
            long mergedLo = xLo + yHi;
            if (Long.compareUnsigned(mergedLo, xLo) < 0) {
                mergedHi++;
            }
            if ((mergedHi & 0x1FF) == 0x1FF && mergedLo + 1 == 0 && Long.compareUnsigned(yLo + man, man) < 0) {
                return -1;
            }
            xHi = mergedHi;
            xLo = mergedLo;
        }

        long msb = xHi >>> 63;
        long result = xHi >>> (msb + 9);
        exp2 -= 1 ^ msb;
        if (xLo == 0 && (xHi & 0x1FF) == 0 && (result & 3) == 1) {
            return -1;
        }
        result += result & 1;
        result >>>= 1;
        if (result >>> 53 > 0) {
            result >>>= 1;
            exp2++;
        }
        if (exp2 >= 0x7FF) {
            return -2;
        }
        if (exp2 <= 0) {
            return -1;
        }
        return exp2 << 52 | result & 0x000F_FFFF_FFFF_FFFFL;
    }

    private static double parseSlow(byte[] b, int from, int to) {
        String s = new String(b, from, to - from, StandardCharsets.ISO_8859_1);
        if (!isDecimalSyntax(s)) {
            return Double.NaN;
        }
        try {
            double value = Double.parseDouble(s);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
