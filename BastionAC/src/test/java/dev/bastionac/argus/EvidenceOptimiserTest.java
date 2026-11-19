package dev.bastionac.argus;

import dev.bastionac.argus.features.Stats;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceOptimiserTest {
    private static final double LAMBDA = .02;

    @Test
    void anInteriorOptimumMatchesAnIndependentScalarDerivativeRoot() {
        List<Calibration.Sample> samples = List.of(
                new Calibration.Sample(Map.of("scalar", 2.0), 0, true),
                new Calibration.Sample(Map.of("scalar", 1.5), 0, false, 3.0));
        double lower = Calibration.MIN_WEIGHT, upper = Calibration.MAX_WEIGHT;
        for (int iteration = 0; iteration < 100; iteration++) {
            double beta = .5 * (lower + upper);
            double derivative = -2 * LAMBDA * (beta - 1)
                    + 2 * (1 - Stats.sigmoid(-3 + 2 * beta))
                    - 4.5 * Stats.sigmoid(-3 + 1.5 * beta);
            if (derivative > 0) lower = beta;
            else upper = beta;
        }
        var result = EvidenceOptimiser.solve(samples, Calibration.Prior.NEUTRAL, LAMBDA,
                Map.of(), EvidenceOptimiser.MAX_ITERATIONS, EvidenceOptimiser.TOLERANCE);
        assertEquals(.5 * (lower + upper), result.fit().weights().get("scalar"), 1e-6);
        assertTrue(result.projectedResidual() <= EvidenceOptimiser.TOLERANCE);
        assertTrue(result.iterations() < EvidenceOptimiser.MAX_ITERATIONS);
        assertEquals(Calibration.DEFAULT_BIAS, result.fit().bias());
    }

    @Test
    void boundaryOptimaRespectBothTheHonestCapAndTheWeightFloor() {
        var capped = EvidenceOptimiser.solve(List.of(
                        new Calibration.Sample(Map.of("capped", 2.0), 0, true)),
                Calibration.Prior.NEUTRAL, LAMBDA, Map.of("capped", 1.4),
                EvidenceOptimiser.MAX_ITERATIONS, EvidenceOptimiser.TOLERANCE);
        assertEquals(1.4, capped.fit().weights().get("capped"));
        assertEquals(0, capped.projectedResidual());
        var floor = EvidenceOptimiser.solve(List.of(
                        new Calibration.Sample(Map.of("floor", 4.0), 0, false)),
                Calibration.Prior.NEUTRAL, LAMBDA, Map.of(),
                EvidenceOptimiser.MAX_ITERATIONS, EvidenceOptimiser.TOLERANCE);
        assertEquals(Calibration.MIN_WEIGHT, floor.fit().weights().get("floor"));
        assertEquals(0, floor.projectedResidual());
    }

    @Test
    void anIterationGuardReportsAnUnfinishedFit() {
        var failure = assertThrows(IllegalStateException.class, () -> EvidenceOptimiser.solve(List.of(
                        new Calibration.Sample(Map.of("unfinished", 2.0), 0, true)),
                Calibration.Prior.NEUTRAL, LAMBDA, Map.of(), 1, 1e-15));
        assertTrue(failure.getMessage().contains("did not converge"));
        assertTrue(failure.getMessage().contains("projected residual"));
    }

    @Test
    void finiteInputsWhoseCurvatureOverflowsCannotPretendToHaveConverged() {
        var failure = assertThrows(IllegalArgumentException.class, () -> EvidenceOptimiser.solve(List.of(
                        new Calibration.Sample(Map.of("large", 4.0), 0, true, 1e308)),
                Calibration.Prior.NEUTRAL, LAMBDA, Map.of(),
                EvidenceOptimiser.MAX_ITERATIONS, EvidenceOptimiser.TOLERANCE));
        assertTrue(failure.getMessage().contains("finite"));
    }

    @Test
    void nonfinitePriorsAndCapsAreRejected() {
        var samples = List.of(new Calibration.Sample(Map.of("invalid", 2.0), 0, true));
        assertThrows(IllegalArgumentException.class, () -> EvidenceOptimiser.solve(samples,
                new Calibration.Prior("invalid", -3, Map.of("invalid", Double.NaN)), LAMBDA, Map.of(),
                EvidenceOptimiser.MAX_ITERATIONS, EvidenceOptimiser.TOLERANCE));
        assertThrows(IllegalArgumentException.class, () -> EvidenceOptimiser.solve(samples,
                Calibration.Prior.NEUTRAL, LAMBDA, Map.of("invalid", Double.POSITIVE_INFINITY),
                EvidenceOptimiser.MAX_ITERATIONS, EvidenceOptimiser.TOLERANCE));
    }
}
