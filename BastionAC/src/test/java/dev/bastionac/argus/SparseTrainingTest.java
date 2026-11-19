package dev.bastionac.argus;

import dev.bastionac.argus.LabSimulator.Cheat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SparseTrainingTest {
    @Test
    void unobservedPositiveSessionsDoNotDiluteObservedEvidence() {
        List<Calibration.Sample> observed = List.of(
                new Calibration.Sample(Map.of("isolated", 0.6), 0, false, 0.2),
                new Calibration.Sample(Map.of(), 0, false, 1.0),
                new Calibration.Sample(Map.of("isolated", 4.0), 0, true, 3.0));
        List<Calibration.Sample> withUnobserved = new ArrayList<>(observed);
        for (int i = 0; i < 100; i++) {
            withUnobserved.add(new Calibration.Sample(Map.of("unused", 0.0), 0, true, 5.0));
        }
        var reference = ArgusLab.trainEvidenceBagged(List.of(observed), "sparse-test");
        var actual = ArgusLab.trainEvidenceBagged(List.of(withUnobserved), "sparse-test");
        assertEquals(reference.bias(), actual.bias());
        assertEquals(reference.weights(), actual.weights());
        assertTrue(actual.weight("isolated") * 0.6 <= ArgusLab.HONEST_BUDGET);
    }

    @Test
    void isolatedJumpTrainingCoversThreeSparseRateBands() {
        int per = 5;
        List<ArgusLab.Scenario> scenarios = ArgusLab.trainingScenarios(37, per);
        int originalCount = (Cheat.values().length + 4) * per;
        assertEquals(originalCount + 3 * per, scenarios.size());
        double[] lower = {0.025, 0.05, 0.10};
        for (int band = 0; band < lower.length; band++) {
            for (int k = 0; k < per; k++) {
                var scenario = scenarios.get(originalCount + band * per + k);
                assertEquals(Cheat.CLICK_TP, scenario.cheat());
                assertTrue(scenario.extra().isEmpty());
                assertTrue(scenario.level() >= lower[band] && scenario.level() < 2 * lower[band]);
            }
        }
    }
}
