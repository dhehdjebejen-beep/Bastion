package dev.bastionac.telemetry;

import java.util.Arrays;

/** Growable primitive lists for decoded sessions — no boxing on hundreds of thousands of samples. */
public final class Prim {

    public static final class Longs {
        public long[] a = new long[256];
        public int n;

        public void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        public long get(int i) {
            return a[i];
        }

        public long last() {
            return a[n - 1];
        }
    }

    public static final class Ints {
        public int[] a = new int[256];
        public int n;

        public void add(int v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        public int get(int i) {
            return a[i];
        }
    }

    public static final class Doubles {
        public double[] a = new double[256];
        public int n;

        public void add(double v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        public double get(int i) {
            return a[i];
        }

        public double[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }

    public static final class Floats {
        public float[] a = new float[256];
        public int n;

        public void add(float v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        public float get(int i) {
            return a[i];
        }
    }

    private Prim() {}
}
