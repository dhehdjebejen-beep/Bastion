package dev.bastionac.argus.features;

import java.util.Arrays;

/** Small, dependency-free statistics: robust location/scale, moments, Poisson tails. */
public final class Stats {

    private Stats() {}

    /** Linear-interpolated percentile, p in [0,1]. Sorts a copy. */
    public static double percentile(double[] v, double p) {
        if (v.length == 0) return Double.NaN;
        double[] s = v.clone();
        Arrays.sort(s);
        double pos = p * (s.length - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return lo == hi ? s[lo] : s[lo] + (s[hi] - s[lo]) * (pos - lo);
    }

    public static double median(double[] v) {
        return percentile(v, 0.5);
    }

    /** Median absolute deviation (not yet scaled to a σ). */
    public static double mad(double[] v) {
        if (v.length == 0) return Double.NaN;
        double m = median(v);
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) d[i] = Math.abs(v[i] - m);
        return median(d);
    }

    public static double mean(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return v.length == 0 ? Double.NaN : s / v.length;
    }

    public static double std(double[] v) {
        if (v.length < 2) return 0;
        double m = mean(v), s = 0;
        for (double x : v) s += (x - m) * (x - m);
        return Math.sqrt(s / (v.length - 1));
    }

    public static double cv(double[] v) {
        double m = mean(v);
        return m == 0 ? 0 : std(v) / m;
    }

    /** Excess kurtosis (0 for a normal distribution, −1.2 for a uniform one). */
    public static double excessKurtosis(double[] v) {
        int n = v.length;
        if (n < 4) return 0;
        double m = mean(v), m2 = 0, m4 = 0;
        for (double x : v) {
            double d = x - m;
            m2 += d * d;
            m4 += d * d * d * d;
        }
        m2 /= n;
        m4 /= n;
        return m2 == 0 ? 0 : m4 / (m2 * m2) - 3.0;
    }

    public static double max(double[] v) {
        double m = Double.NEGATIVE_INFINITY;
        for (double x : v) m = Math.max(m, x);
        return m;
    }

    // ---------------------------------------------------------------- Poisson tail

    /**
     * How unusual it is to see {@code observed} events when {@code expected}
     * were due, as a one-sided normal score: 0 when observed ≤ expected, up
     * to 12. P(X ≥ k | λ) is summed exactly in log space.
     */
    public static double poissonUpperZ(double observed, double expected) {
        if (expected <= 0) return observed > 0 ? 12 : 0;
        int k = (int) Math.round(observed);
        if (k <= expected) return 0;
        double tail = poissonUpperTail(k, expected);
        if (tail <= 0) return 12;
        return Math.min(12, Math.max(0, inverseNormalUpper(tail)));
    }

    /** P(X ≥ k) for X ~ Poisson(λ). */
    public static double poissonUpperTail(int k, double lambda) {
        double logL = Math.log(lambda);
        double sum = 0;
        double first = Double.NEGATIVE_INFINITY;
        for (int i = k; i < k + 2000; i++) {
            double lp = i * logL - lambda - logGamma(i + 1);
            if (i == k) first = lp;
            double t = Math.exp(lp - first);
            sum += t;
            if (t < 1e-12 * sum) break;
        }
        return Math.exp(first) * sum;
    }

    /** Lanczos approximation of ln Γ(x), x > 0. */
    public static double logGamma(double x) {
        double[] c = {76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5};
        double y = x, tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (double cj : c) ser += cj / ++y;
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /** z such that P(Z ≥ z) = p (Acklam's inverse normal), for 0 &lt; p &lt; 1. */
    public static double inverseNormalUpper(double p) {
        return -inverseNormal(p);
    }

    public static double inverseNormal(double p) {
        if (p <= 0) return Double.NEGATIVE_INFINITY;
        if (p >= 1) return Double.POSITIVE_INFINITY;
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double pl = 0.02425;
        if (p < pl) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - pl) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    public static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
