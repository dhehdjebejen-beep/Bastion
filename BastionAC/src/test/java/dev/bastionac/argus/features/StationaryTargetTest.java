package dev.bastionac.argus.features;

import dev.bastionac.telemetry.SessionData;
import org.junit.jupiter.api.Test;
import static dev.bastionac.telemetry.TelemetryFormat.*;
import static org.junit.jupiter.api.Assertions.*;

class StationaryTargetTest {
    @Test void observerMovementCannotMasqueradeAsTargetMotion() {
        SessionData s=new SessionData();
        SessionData.Attack a=new SessionData.Attack();a.t=1500;a.targetId=7;
        for(int i=0;i<10;i++) {
            s.moveT.add(1000+i*50);s.moveFlags.add(MV_POS);s.moveX.add(i*.1);s.moveY.add(64);s.moveZ.add(0);
            s.targetT.add(1000+i*50);s.targetId.add(7);s.targetDX.add((float)(3-i*.1));s.targetDZ.add(0);
        }
        assertFalse(CombatFeatures.movingTarget(s,a));
        for(int i=0;i<10;i++)s.targetDZ.a[i]=i*.05f;
        assertTrue(CombatFeatures.movingTarget(s,a));
    }
}
