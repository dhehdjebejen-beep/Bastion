package dev.bastionac.argus;

import dev.bastionac.argus.features.Features;
import dev.bastionac.telemetry.TelemetryReader;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LabClockTest {
    @Test void automaticMouseClockRetainsPrecisionAcrossMovementTicks() throws Exception {
        for(double level:new double[]{.25,.5,1}) {
            var profile=new LabSimulator.Profile();profile.seed=971;profile.minutes=2;
            profile.fight=1;profile.dig=0;profile.jitterMs=18;profile.lagOneIn=160;
            var session=TelemetryReader.readRaw(new LabSimulator(profile,
                    Map.of(LabSimulator.Cheat.AUTOCLICKER,level),null).play());
            var features=ArgusLab.measure(session,null,false,false);
            var rhythm=features.value(Features.spec("cmb.click_rhythm"));
            assertNotNull(rhythm);assertTrue(rhythm.support()>200);
            assertTrue(rhythm.value()<Math.log(.025),"oscillator CV at "+level+": "+rhythm.value());
            assertTrue(session.swingT.n>session.attacks.size());
        }
    }
    @Test void impulseFlightDoesNotRetainGroundSupportInTheAir() throws Exception {
        var profile=new LabSimulator.Profile();profile.seed=891;
        var session=TelemetryReader.readRaw(new LabSimulator(profile,
                Map.of(LabSimulator.Cheat.VELOCITY_KB,.25),null).play());
        int unsupported=0;
        for(int i=1;i<session.moveT.n;i++)if(session.moveY.a[i-1]>64.15) {
            assertEquals(0,session.moveEnv.a[i]&12);
            unsupported++;
        }
        assertTrue(unsupported>40);
    }
}
