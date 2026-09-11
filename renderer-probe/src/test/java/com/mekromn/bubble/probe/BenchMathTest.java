package com.mekromn.bubble.probe;
import org.junit.Test;
import static org.junit.Assert.*;
public class BenchMathTest {
    @Test public void ratesAreIntervalBased() { assertEquals(120, BenchMath.rate(new double[]{1000.0/120,1000.0/120}), .00001); }
    @Test public void emptyIsUnknownNotZero() { assertTrue(Double.isNaN(BenchMath.rate(new double[]{}))); }
    @Test public void tailIsNotMean() { assertEquals(50, BenchMath.percentile(new double[]{8,8,8,8,50},.95),0); }
    @Test public void budgetDependsOnTarget() { assertEquals(.5, BenchMath.missedBudgetFraction(new double[]{8,16},120),0); }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidIntervals() { BenchMath.rate(new double[]{0}); }
}
