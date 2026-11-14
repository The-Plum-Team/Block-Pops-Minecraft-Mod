package com.theplumteam.remote;

import java.math.BigDecimal;

/**
 * A JSON number as the document wrote it, with its value worked out only when something asks.
 *
 * <p>Keeping the token means a number is never written again in another form, and that the
 * arbitrary-precision value, which costs far more than the token's length to build on some
 * hosts, is built only for the checks that need it. {@link #doubleValue} gives what
 * {@link BigDecimal#doubleValue} gives (so a zero is +0.0, as BigDecimal has no negative zero),
 * by one exact multiplication or division wherever that is enough.
 */
public final class JsonNumber extends Number {
    private final String token;
    private BigDecimal decimal;

    /**
     * @param eager build the BigDecimal now, so a token BigDecimal refuses fails the parse as
     *              before; the player profile has already bounded the token so that it cannot
     *              fail, and builds it only when a check needs it
     */
    public JsonNumber(String token, boolean eager) {
        this.token = token;
        if (eager) {
            this.decimal = new BigDecimal(token);
        }
    }

    public BigDecimal decimal() {
        if (decimal == null) {
            decimal = new BigDecimal(token);
        }
        return decimal;
    }

    /** Exact powers of ten: every one up to 1e22 is a double without rounding. */
    private static final double[] POWERS = {1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12,
            1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

    /**
     * The value {@link BigDecimal#doubleValue} gives. When the token has at most 15 significant
     * digits and its power of ten is within 1e22, both are doubles without rounding, and one
     * IEEE multiplication or division of them is correctly rounded, so every host agrees. Any
     * other token takes {@link BigDecimal#doubleValue}; a host's own string-to-double is never
     * used, as not every host rounds it correctly.
     */
    @Override
    public double doubleValue() {
        int n = token.length();
        int start = token.charAt(0) == '-' ? 1 : 0;
        int end = start;
        while (end < n && token.charAt(end) != 'e' && token.charAt(end) != 'E') {
            end++;
        }
        // Digit positions: the index of each digit among the mantissa's digits, '.' skipped
        int digits = 0;
        int afterPoint = 0;
        int firstNonZero = -1;
        int lastNonZero = -1;
        boolean point = false;
        for (int i = start; i < end; i++) {
            char c = token.charAt(i);
            if (c == '.') {
                point = true;
                continue;
            }
            if (c != '0') {
                if (firstNonZero < 0) {
                    firstNonZero = digits;
                }
                lastNonZero = digits;
            }
            digits++;
            if (point) {
                afterPoint++;
            }
        }
        if (firstNonZero < 0) {
            return 0.0;
        }
        if (lastNonZero - firstNonZero + 1 > 15) {
            return slow();
        }
        int exponent = 0;
        if (end < n) {
            int i = end + 1;
            int sign = 1;
            if (token.charAt(i) == '+' || token.charAt(i) == '-') {
                sign = token.charAt(i) == '-' ? -1 : 1;
                i++;
            }
            for (; i < n; i++) {
                exponent = exponent * 10 + (token.charAt(i) - '0');
                if (exponent > 400) {
                    return slow();
                }
            }
            exponent *= sign;
        }
        double mantissa = 0;
        int position = 0;
        for (int i = start; i < end && position <= lastNonZero; i++) {
            char c = token.charAt(i);
            if (c == '.') {
                continue;
            }
            if (position >= firstNonZero) {
                mantissa = mantissa * 10 + (c - '0');
            }
            position++;
        }
        int scale = exponent - afterPoint + (digits - 1 - lastNonZero);
        double value;
        if (scale == 0) {
            value = mantissa;
        } else if (scale > 0 && scale <= 22) {
            value = mantissa * POWERS[scale];
        } else if (scale < 0 && scale >= -22) {
            value = mantissa / POWERS[-scale];
        } else {
            return slow();
        }
        return start == 1 ? -value : value;
    }

    private double slow() {
        return decimal().doubleValue();
    }

    @Override
    public float floatValue() {
        return decimal().floatValue();
    }

    @Override
    public int intValue() {
        return decimal().intValue();
    }

    @Override
    public long longValue() {
        return decimal().longValue();
    }

    /** The token exactly as the document wrote it. */
    @Override
    public String toString() {
        return token;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonNumber && ((JsonNumber) other).token.equals(token);
    }

    @Override
    public int hashCode() {
        return token.hashCode();
    }
}
