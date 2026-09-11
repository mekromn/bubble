package com.mekromn.bubble.probe;

import java.util.Arrays;

/** Interval statistics, not a claim that a produced frame reached the physical panel. */
public final class BenchMath {
    private BenchMath() {}
    public static double percentile(double[] values, double p) {
        if (values.length == 0) return Double.NaN;
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1))];
    }
    public static double rate(double[] intervalsMs) {
        double total = 0;
        for (double value : intervalsMs) {
            if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid interval");
            total += value;
        }
        return total > 0 ? 1000.0 * intervalsMs.length / total : Double.NaN;
    }
    public static double missedBudgetFraction(double[] intervalsMs, double hz) {
        if (intervalsMs.length == 0 || hz <= 0) return Double.NaN;
        int missed = 0;
        for (double value : intervalsMs) if (value > 1.5 * 1000.0 / hz) missed++;
        return missed / (double) intervalsMs.length;
    }
}
