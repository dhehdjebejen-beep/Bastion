package dev.bastionac.argus;

import dev.bastionac.argus.LabSimulator.Cheat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Argus Lab in small: a few dozen synthetic players through the live feature
 * code, scored with the prior that ships. The full bench (thousands of
 * sessions, the report, retraining the prior) is {@code ArgusLab.main}.
 */
class ArgusLabTest {

    private static final WorldSample ROCK = LabSimulator.rock(4242);

    @AfterEach
    void shippedPrior() {
        Calibration.Prior.override(null);
    }

    @Test
    void thePriorShipsAndKeepsNothingFoundAtTheOldOdds() {
        Calibration.Prior p = Calibration.Prior.get();
        assertTrue(p.version().startsWith("lab-"), "the trained prior is on the classpath: " + p.version());
        assertTrue(p.bias() <= Calibration.DEFAULT_BIAS, "no evidence is worth no more than in 3.2");
        p.weights().forEach((k, w) -> assertTrue(w <= ArgusLab.LAB_MAX_WEIGHT + 1e-9, k + " = " + w));
        // A fresh model is the prior.
        Calibration c = new Calibration();
        assertEquals(p.bias(), c.bias);
        assertEquals(p.weight("pkt.blind_stall"), c.weight("pkt.blind_stall"));
        assertTrue(c.onCurrentPrior());
    }

    @Test
    void honestPlayersStayCleanUnderTheShippedPrior() {
        Baselines norms = ArgusLab.baselines(ArgusLab.corpus(1, 40, 0, List.of(), ROCK));
        List<ArgusLab.Played> test = new ArrayList<>(ArgusLab.corpus(2_000_000, 40, 0, List.of(), ROCK));
        test.addAll(ArgusLab.stressCorpus(3_000_000, 30, ROCK));
        Calibration model = new Calibration();
        for (Baselines b : new Baselines[]{norms, new Baselines()}) {
            ArgusLab.Bench bench = ArgusLab.benchmark(test, b, model);
            assertEquals(0, bench.honestSuspect, bench.report("honest"));
            assertTrue(bench.honestMax < 0.3, bench.report("honest"));
        }
    }

    @Test
    void blatantCheatsAreCaught() {
        Baselines norms = ArgusLab.baselines(ArgusLab.corpus(1, 40, 0, List.of(), ROCK));
        List<ArgusLab.Scenario> blatant = new ArrayList<>();
        for (Cheat c : new Cheat[]{Cheat.AURA, Cheat.AURA_SMOOTH, Cheat.SPEED, Cheat.STRAFE, Cheat.BHOP,
                Cheat.TIMER, Cheat.BLINK, Cheat.XRAY, Cheat.PING_SPOOF}) {
            blatant.add(new ArgusLab.Scenario(c, 1.0));
        }
        List<ArgusLab.Played> test = ArgusLab.corpus(2_000_000, 0, 3, blatant, ROCK);
        ArgusLab.Bench bench = ArgusLab.benchmark(test, norms, new Calibration());
        for (ArgusLab.Scenario sc : blatant) {
            int[] r = bench.byScenario.get(sc.name());
            assertTrue(r[1] >= 2, sc.name() + " caught " + r[1] + " of " + r[0] + "\n" + bench.report("blatant"));
        }
    }

    @Test
    void trainingRespectsTheHonestBudgetAndTheBias() {
        WorldSample rock = ROCK;
        Baselines norms = ArgusLab.baselines(ArgusLab.corpus(1, 30, 0, List.of(), rock));
        List<ArgusLab.Played> train = new ArrayList<>(ArgusLab.corpus(1_000_000, 30, 1,
                ArgusLab.trainingScenarios(7, 2), rock));
        train.addAll(ArgusLab.stressCorpus(1_500_000, 15, rock));
        Calibration.Prior p = ArgusLab.trainPrior(train, norms, "test");
        assertTrue(p.bias() <= Calibration.DEFAULT_BIAS);
        Calibration model = ArgusLab.model(p);
        for (Calibration.Sample s : ArgusLab.samples(train, norms)) {
            if (s.cheater()) continue;
            assertTrue(model.score(s.evidence(), 0) <= ArgusLab.HONEST_CEILING + 1e-6, "honest training session over the ceiling");
            for (Map.Entry<String, Double> e : s.evidence().entrySet()) {
                assertTrue(model.weight(e.getKey()) * e.getValue() <= ArgusLab.HONEST_BUDGET + 1e-6,
                        e.getKey() + " gives an honest player " + model.weight(e.getKey()) * e.getValue());
            }
        }
    }
}
