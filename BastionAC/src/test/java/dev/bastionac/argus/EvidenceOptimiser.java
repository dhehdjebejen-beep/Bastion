package dev.bastionac.argus;

import dev.bastionac.argus.features.Stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Laboratory-only constrained logistic fit with a measured convergence condition. */
final class EvidenceOptimiser {
    static final double TOLERANCE = 1e-8;
    static final int MAX_ITERATIONS = 200_000;
    private static final double OBJECTIVE_ROUNDOFF = 1e-10;

    record Result(Calibration.Fit fit, int iterations, double projectedResidual) {}

    static Calibration.Fit optimise(List<Calibration.Sample> samples, Calibration.Prior prior,
                                    double lambda, Map<String, Double> caps) {
        Result result = solve(samples, prior, lambda, caps, MAX_ITERATIONS, TOLERANCE);
        System.out.printf(Locale.ROOT, "evidence optimiser: iterations=%d projectedResidual=%.12g%n",
                result.iterations(), result.projectedResidual());
        return result.fit();
    }

    static Result solve(List<Calibration.Sample> all, Calibration.Prior prior, double lambda,
                        Map<String, Double> caps, int iterationLimit, double tolerance) {
        if (!Double.isFinite(lambda) || lambda <= 0 || !Double.isFinite(tolerance)
                || tolerance <= 0 || iterationLimit < 1) {
            throw new IllegalArgumentException("Positive regularisation, tolerance and iteration limit required");
        }
        Problem problem = new Problem(all, prior, lambda, caps);
        double[] current = new double[problem.keys.size()];
        for (int k = 0; k < current.length; k++) current[k] = problem.bound(k, problem.centre[k]);
        if (problem.totalWeight <= 0) return problem.result(current, 0, 0);
        double[] extrapolated = current.clone();
        double[] next = new double[current.length];
        double[] gradient = new double[current.length];
        double momentum = 1;
        double objective = problem.evaluate(current, null);
        double residual = Double.POSITIVE_INFINITY;
        for (int iteration = 1; iteration <= iterationLimit; iteration++) {
            problem.evaluate(extrapolated, gradient);
            for (int k = 0; k < next.length; k++) {
                next[k] = problem.bound(k, extrapolated[k] + gradient[k] / problem.diagonal[k]);
            }
            double nextObjective = problem.evaluate(next, null);
            if (nextObjective > objective + OBJECTIVE_ROUNDOFF) {
                // Restart acceleration at the accepted point. The same diagonal
                // majorizer gives a descent step without changing the objective.
                momentum = 1;
                problem.evaluate(current, gradient);
                for (int k = 0; k < next.length; k++) {
                    next[k] = problem.bound(k, current[k] + gradient[k] / problem.diagonal[k]);
                }
                nextObjective = problem.evaluate(next, null);
            }
            double nextMomentum = .5 * (1 + Math.sqrt(1 + 4 * momentum * momentum));
            double extrapolation = (momentum - 1) / nextMomentum;
            for (int k = 0; k < current.length; k++) {
                double previous = current[k];
                current[k] = next[k];
                extrapolated[k] = current[k] + extrapolation * (current[k] - previous);
            }
            momentum = nextMomentum;
            objective = nextObjective;
            // Check the projected gradient at an accepted point, not merely the
            // change of an accelerated iterate or a small average gradient.
            if (iteration % 32 == 0 || iteration == iterationLimit) {
                problem.evaluate(current, gradient);
                residual = 0;
                for (int k = 0; k < current.length; k++) {
                    residual = Math.max(residual, Math.abs(problem.bound(k,
                            current[k] + gradient[k] / problem.diagonal[k]) - current[k]));
                }
                if (residual <= tolerance) return problem.result(current, iteration, residual);
            }
        }
        throw new IllegalStateException("Evidence optimisation did not converge after " + iterationLimit
                + " iterations; projected residual=" + residual);
    }

    private static final class Problem {
        final List<String> keys;
        final int[] starts, columns;
        final double[] values, sampleWeights, labels, centre, upper, diagonal;
        final double lambda, totalWeight;

        Problem(List<Calibration.Sample> all, Calibration.Prior prior, double lambda,
                Map<String, Double> caps) {
            this.lambda = lambda;
            List<Calibration.Sample> samples = all.stream()
                    .filter(s -> !s.cheater() || s.informative()).toList();
            TreeSet<String> ordered = new TreeSet<>();
            for (var sample : samples) ordered.addAll(sample.evidence().keySet());
            ordered.add(Calibration.SIG);
            keys = List.copyOf(ordered);
            Map<String, Integer> index = new HashMap<>();
            centre = new double[keys.size()];
            upper = new double[keys.size()];
            diagonal = new double[keys.size()];
            Arrays.fill(diagonal, 2 * lambda);
            for (int k = 0; k < keys.size(); k++) {
                String key = keys.get(k);
                index.put(key, k);
                centre[k] = prior.weight(key);
                double cap = caps.getOrDefault(key, Calibration.MAX_WEIGHT);
                if (!Double.isFinite(centre[k]) || !Double.isFinite(cap)) {
                    throw new IllegalArgumentException("Prior weights and caps must be finite");
                }
                upper[k] = Math.max(Calibration.MIN_WEIGHT, Math.min(Calibration.MAX_WEIGHT,
                        cap));
            }
            starts = new int[samples.size() + 1];
            sampleWeights = new double[samples.size()];
            labels = new double[samples.size()];
            List<Integer> sparseColumns = new ArrayList<>();
            List<Double> sparseValues = new ArrayList<>();
            double total = 0;
            for (int row = 0; row < samples.size(); row++) {
                var sample = samples.get(row);
                if (!Double.isFinite(sample.weight()) || sample.weight() < 0) {
                    throw new IllegalArgumentException("Sample weights must be finite and nonnegative");
                }
                sampleWeights[row] = sample.weight();
                labels[row] = sample.cheater() ? 1 : 0;
                total += sample.weight();
                starts[row] = sparseColumns.size();
                for (var entry : sample.evidence().entrySet()) {
                    if (entry.getValue() != 0) {
                        sparseColumns.add(index.get(entry.getKey()));
                        sparseValues.add(entry.getValue());
                    }
                }
                if (sample.signals() != 0) {
                    sparseColumns.add(index.get(Calibration.SIG));
                    sparseValues.add(sample.signals());
                }
                // Logistic curvature is at most 1/4. Weighted Cauchy-Schwarz
                // bounds its Hessian by this diagonal, including the fixed
                // intercept in the row norm for a conservative majorizer.
                double rowNorm = 1;
                for (int term = starts[row]; term < sparseColumns.size(); term++) {
                    rowNorm += Math.abs(sparseValues.get(term));
                }
                if (!Double.isFinite(rowNorm)) {
                    throw new IllegalArgumentException("Evidence row norms must be finite");
                }
                for (int term = starts[row]; term < sparseColumns.size(); term++) {
                    diagonal[sparseColumns.get(term)] += .25 * sample.weight()
                            * Math.abs(sparseValues.get(term)) * rowNorm;
                }
            }
            if (!Double.isFinite(total)) {
                throw new IllegalArgumentException("Total sample weight must be finite");
            }
            for (double curvature : diagonal) {
                if (!Double.isFinite(curvature)) {
                    throw new IllegalArgumentException("Curvature majorizers must be finite");
                }
            }
            starts[samples.size()] = sparseColumns.size();
            columns = new int[sparseColumns.size()];
            values = new double[sparseValues.size()];
            for (int term = 0; term < columns.length; term++) {
                columns[term] = sparseColumns.get(term);
                values[term] = sparseValues.get(term);
                if (!Double.isFinite(values[term])) {
                    throw new IllegalArgumentException("Evidence values must be finite");
                }
            }
            totalWeight = total;
        }

        double bound(int key, double value) {
            return Math.max(Calibration.MIN_WEIGHT, Math.min(upper[key], value));
        }

        double evaluate(double[] beta, double[] gradient) {
            double objective = 0;
            for (int k = 0; k < beta.length; k++) {
                double displacement = beta[k] - centre[k];
                objective += lambda * displacement * displacement;
                if (gradient != null) gradient[k] = -2 * lambda * displacement;
            }
            for (int row = 0; row < sampleWeights.length; row++) {
                double logit = Calibration.DEFAULT_BIAS;
                for (int term = starts[row]; term < starts[row + 1]; term++) {
                    logit += beta[columns[term]] * values[term];
                }
                if (!Double.isFinite(logit)) {
                    throw new IllegalStateException("Evidence optimisation logit is not finite");
                }
                double softplus = logit > 0 ? logit + Math.log1p(Math.exp(-logit))
                        : Math.log1p(Math.exp(logit));
                objective += sampleWeights[row] * (softplus - labels[row] * logit);
                if (gradient != null) {
                    double error = sampleWeights[row] * (labels[row] - Stats.sigmoid(logit));
                    for (int term = starts[row]; term < starts[row + 1]; term++) {
                        gradient[columns[term]] += error * values[term];
                    }
                }
            }
            if (!Double.isFinite(objective)) {
                throw new IllegalStateException("Evidence optimisation objective is not finite");
            }
            return objective;
        }

        Result result(double[] beta, int iterations, double residual) {
            Map<String, Double> weights = new TreeMap<>();
            for (int k = 0; k < beta.length; k++) weights.put(keys.get(k), beta[k]);
            return new Result(new Calibration.Fit(Calibration.DEFAULT_BIAS, Map.copyOf(weights)),
                    iterations, residual);
        }
    }
}
