package com.carbonflow.perf;

import java.util.Arrays;

/**
 * Latency and throughput arithmetic for the Phase 10.10 measurements.
 *
 * <p>Deliberately plain: the report needs percentiles, a mean, a spread and a
 * rate, all computed from the samples actually collected in this run. Nothing
 * here estimates, extrapolates or smooths — a synthetic reading would be
 * indistinguishable from a measured one in a table, so no such value is ever
 * produced.
 *
 * <h2>Percentile definition</h2>
 * <p>{@code percentile(p)} is the nearest-rank percentile of the sorted sample:
 * the value at index {@code ceil(p/100 * n) - 1}, 0-based, no interpolation.
 * Nearest-rank is used rather than an interpolating estimator because it is
 * always an <em>observed</em> measurement — no reported percentile can be a
 * number the system never produced. The cost is a slight upward bias at low
 * sample counts, which is stated rather than hidden.
 */
public final class PerfStats {

    private PerfStats() {
    }

    /** Immutable summary of one measured series of durations. */
    public record Summary(long samples, double minMs, double meanMs, double p50Ms,
                          double p90Ms, double p95Ms, double p99Ms, double maxMs) {

        /** Requests per second achievable at this concurrency, from the mean. */
        public double requestsPerSecond(double concurrency) {
            return meanMs <= 0 ? 0 : (concurrency * 1000.0) / meanMs;
        }
    }

    /**
     * Summarizes durations in milliseconds.
     *
     * @param durationsNanos per-request durations, in nanoseconds; copied
     * @return the summary, or {@code null} when there is nothing to summarise
     */
    public static Summary of(long[] durationsNanos) {
        if (durationsNanos == null || durationsNanos.length == 0) {
            return null;
        }
        long[] sorted = durationsNanos.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (long d : sorted) {
            sum += d;
        }
        return new Summary(
                sorted.length,
                ms(sorted[0]),
                sum / sorted.length / 1_000_000.0,
                ms(sorted[nearestRankIndex(sorted.length, 50)]),
                ms(sorted[nearestRankIndex(sorted.length, 90)]),
                ms(sorted[nearestRankIndex(sorted.length, 95)]),
                ms(sorted[nearestRankIndex(sorted.length, 99)]),
                ms(sorted[sorted.length - 1]));
    }

    /** Standard deviation in milliseconds, for spread reporting. */
    public static double stdDevMs(long[] durationsNanos) {
        if (durationsNanos == null || durationsNanos.length < 2) {
            return 0;
        }
        double mean = 0;
        for (long d : durationsNanos) {
            mean += d;
        }
        mean /= durationsNanos.length;
        double variance = 0;
        for (long d : durationsNanos) {
            double delta = d / 1_000_000.0 - mean;
            variance += delta * delta;
        }
        return Math.sqrt(variance / durationsNanos.length);
    }

    private static int nearestRankIndex(int n, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * n);
        if (rank < 1) {
            rank = 1;
        }
        if (rank > n) {
            rank = n;
        }
        return rank - 1;
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** Formats a duration for report tables without implying false precision. */
    public static String fmt(double millis) {
        if (millis >= 1000) {
            return String.format(java.util.Locale.ROOT, "%.2f s", millis / 1000.0);
        }
        if (millis >= 10) {
            return String.format(java.util.Locale.ROOT, "%.1f ms", millis);
        }
        return String.format(java.util.Locale.ROOT, "%.2f ms", millis);
    }
}
