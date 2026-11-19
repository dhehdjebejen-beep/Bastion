package dev.bastionac.argus;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HitPropertyBudgetTest {
    @Test
    void duplicateTimeScalesShareBudgetWhileIndependentEvidenceKeepsItsMargin() {
        for(var group:ArgusLab.HIT_PROPERTY_GROUPS) {
            var hand=new Calibration.Sample(Map.of(group.get(0),.8,group.get(1),.7),0,false);
            var positive=new Calibration.Sample(Map.of(group.get(0),2.0,group.get(1),2.0,
                    "independent",4.0),0,true);
            var prior=ArgusLab.trainEvidenceBagged(List.of(List.of(hand,positive)),"lab-4.0.0",List.of(positive));
            double shared=group.stream().mapToDouble(k->prior.weight(k)*hand.evidence().get(k)).sum();
            assertTrue(shared<=ArgusLab.HONEST_BUDGET,group+" shared contribution "+shared);
            assertTrue(prior.weight("independent")>1, "independent positive evidence remains trainable");
            double positiveSum=positive.evidence().entrySet().stream()
                    .mapToDouble(e->prior.weight(e.getKey())*e.getValue()).sum();
            assertTrue(positiveSum+prior.bias()>=Math.log(.95/.05));
            prior.weights().values().forEach(w->assertTrue(w>=.25&&w<=2.5));
        }
    }

    @Test
    void aSparseEncounterDoesNotDisableTheSessionEstimate() {
        var hand=new Calibration.Sample(Map.of("cmb.aim_lc_center",.6),0,false);
        var positive=new Calibration.Sample(Map.of("cmb.aim_lc_center",4.0),0,true);
        var prior=ArgusLab.trainEvidenceBagged(List.of(List.of(hand,positive)),"lab-4.0.0",List.of(positive));
        assertTrue(prior.weight("cmb.aim_lc_center")*.6<=ArgusLab.HONEST_BUDGET);
        assertTrue(prior.weight("cmb.aim_lc_center")*4+prior.bias()>=Math.log(.95/.05));
    }
}
