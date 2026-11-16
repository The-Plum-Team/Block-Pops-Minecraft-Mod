package com.theplumteam.worldcontent;

/**
 * Sine and cosine as fdlibm 5.3 computes them, which is the algorithm {@link StrictMath} is
 * specified to reproduce, written out in plain Java so that every host of the content core
 * gets the same bits. A host that hands these to its own maths library need not.
 *
 * <p>Only the medium range of fdlibm's argument reduction is here: an argument up to
 * 2^19 * pi/2, about 823,550, in size. The core calls it with the radians of angles of a few
 * thousand degrees at most; a larger argument throws instead of guessing.
 *
 * <p>Ported from fdlibm (e_rem_pio2.c, k_sin.c, k_cos.c, s_sin.c, s_cos.c):
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this software is freely granted,
 * provided that this notice is preserved.
 */
public final class CoreMath {
    private static final double HALF = 5.00000000000000000000e-01;
    private static final double INVPIO2 = 6.36619772367581382433e-01;
    private static final double PIO2_1 = 1.57079632673412561417e+00;
    private static final double PIO2_1T = 6.07710050650619224932e-11;
    private static final double PIO2_2 = 6.07710050630396597660e-11;
    private static final double PIO2_2T = 2.02226624879595063154e-21;
    private static final double PIO2_3 = 2.02226624871116645580e-21;
    private static final double PIO2_3T = 8.47842766036889956997e-32;
    private static final int[] NPIO2_HW = {
            0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C,
            0x4025FDBB, 0x402921FB, 0x402C463A, 0x402F6A7A, 0x4031475C, 0x4032D97C,
            0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
            0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C,
            0x4043A28C, 0x40446B9C, 0x404534AC, 0x4045FDBB, 0x4046C6CB, 0x40478FDB,
            0x404858EB, 0x404921FB};

    private static final double S1 = -1.66666666666666324348e-01;
    private static final double S2 = 8.33333333332248946124e-03;
    private static final double S3 = -1.98412698298579493134e-04;
    private static final double S4 = 2.75573137070700676789e-06;
    private static final double S5 = -2.50507602534068634195e-08;
    private static final double S6 = 1.58969099521155010221e-10;

    private static final double C1 = 4.16666666666666019037e-02;
    private static final double C2 = -1.38888888888741095749e-03;
    private static final double C3 = 2.48015872894767294178e-05;
    private static final double C4 = -2.75573143513906633035e-07;
    private static final double C5 = 2.08757232129817482790e-09;
    private static final double C6 = -1.13596475577881948265e-11;

    private CoreMath() {
    }

    public static double sin(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelSin(x, 0, 0);
        }
        if (ix >= 0x7ff00000) {
            return x - x;
        }
        double[] y = new double[2];
        int n = remPio2(x, y);
        switch (n & 3) {
            case 0:
                return kernelSin(y[0], y[1], 1);
            case 1:
                return kernelCos(y[0], y[1]);
            case 2:
                return -kernelSin(y[0], y[1], 1);
            default:
                return -kernelCos(y[0], y[1]);
        }
    }

    public static double cos(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            return kernelCos(x, 0);
        }
        if (ix >= 0x7ff00000) {
            return x - x;
        }
        double[] y = new double[2];
        int n = remPio2(x, y);
        switch (n & 3) {
            case 0:
                return kernelCos(y[0], y[1]);
            case 1:
                return -kernelSin(y[0], y[1], 1);
            case 2:
                return -kernelCos(y[0], y[1]);
            default:
                return kernelSin(y[0], y[1], 1);
        }
    }

    private static int hi(double x) {
        return (int) (Double.doubleToRawLongBits(x) >>> 32);
    }

    private static int remPio2(double x, double[] y) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            y[0] = x;
            y[1] = 0;
            return 0;
        }
        if (ix < 0x4002d97c) {
            double z;
            if (hx > 0) {
                z = x - PIO2_1;
                if (ix != 0x3ff921fb) {
                    y[0] = z - PIO2_1T;
                    y[1] = (z - y[0]) - PIO2_1T;
                } else {
                    z -= PIO2_2;
                    y[0] = z - PIO2_2T;
                    y[1] = (z - y[0]) - PIO2_2T;
                }
                return 1;
            }
            z = x + PIO2_1;
            if (ix != 0x3ff921fb) {
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
        if (ix > 0x413921fb) {
            throw new IllegalArgumentException("CoreMath argument out of the ported range");
        }
        double t = Math.abs(x);
        int n = (int) (t * INVPIO2 + HALF);
        double fn = n;
        double r = t - fn * PIO2_1;
        double w = fn * PIO2_1T;
        if (n < 32 && ix != NPIO2_HW[n - 1]) {
            y[0] = r - w;
        } else {
            int j = ix >> 20;
            y[0] = r - w;
            int i = j - ((hi(y[0]) >> 20) & 0x7ff);
            if (i > 16) {
                t = r;
                w = fn * PIO2_2;
                r = t - w;
                w = fn * PIO2_2T - ((t - r) - w);
                y[0] = r - w;
                i = j - ((hi(y[0]) >> 20) & 0x7ff);
                if (i > 49) {
                    t = r;
                    w = fn * PIO2_3;
                    r = t - w;
                    w = fn * PIO2_3T - ((t - r) - w);
                    y[0] = r - w;
                }
            }
        }
        y[1] = (r - y[0]) - w;
        if (hx < 0) {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        }
        return n;
    }

    private static double kernelSin(double x, double y, int iy) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return x;
        }
        double z = x * x;
        double v = z * x;
        double r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
        if (iy == 0) {
            return x + v * (S1 + z * r);
        }
        return x - ((z * (HALF * y - v * r) - y) - v * S1);
    }

    private static double kernelCos(double x, double y) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000 && (int) x == 0) {
            return 1.0;
        }
        double z = x * x;
        double r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
        if (ix < 0x3FD33333) {
            return 1.0 - (0.5 * z - (z * r - x * y));
        }
        double qx;
        if (ix > 0x3fe90000) {
            qx = 0.28125;
        } else {
            qx = Double.longBitsToDouble(((long) (ix - 0x00200000)) << 32);
        }
        double hz = 0.5 * z - qx;
        double a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }
}
