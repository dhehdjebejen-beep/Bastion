package dev.bastionauth.core;

import net.minecraft.world.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpectatorGateTest {

    @Test
    void nonOperatorSpectatorFallsBackToSurvival() {
        assertEquals(GameMode.SURVIVAL, SpectatorGate.restoreTarget(GameMode.SPECTATOR, false));
        assertEquals(GameMode.SURVIVAL, SpectatorGate.restoreTarget(null, false));
        assertEquals(GameMode.CREATIVE, SpectatorGate.restoreTarget(GameMode.CREATIVE, false));
        assertEquals(GameMode.ADVENTURE, SpectatorGate.restoreTarget(GameMode.ADVENTURE, false));
    }

    @Test
    void operatorLevelTwoPlusKeepsSpectator() {
        assertEquals(GameMode.SPECTATOR, SpectatorGate.restoreTarget(GameMode.SPECTATOR, true));
        assertEquals(GameMode.CREATIVE, SpectatorGate.restoreTarget(GameMode.CREATIVE, true));
        assertEquals(GameMode.SURVIVAL, SpectatorGate.restoreTarget(GameMode.SURVIVAL, true));
        assertEquals(GameMode.SURVIVAL, SpectatorGate.restoreTarget(null, true));
    }
}
