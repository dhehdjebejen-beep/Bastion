package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BanManagerTest {

    private static final List<Integer> STEPS = List.of(1, 6, 24, 168);

    @Test
    void escalatesThroughTheLadder() {
        assertEquals(1, BanManager.banHours(0, STEPS));   // first ban → 1h
        assertEquals(6, BanManager.banHours(1, STEPS));   // second → 6h
        assertEquals(24, BanManager.banHours(2, STEPS));  // third → 1 day
        assertEquals(168, BanManager.banHours(3, STEPS)); // fourth → 1 week
    }

    @Test
    void clampsBeyondLadderToLast() {
        assertEquals(168, BanManager.banHours(4, STEPS));
        assertEquals(168, BanManager.banHours(99, STEPS));
    }

    @Test
    void handlesEmptyOrNullSteps() {
        assertEquals(1, BanManager.banHours(0, List.of()));
        assertEquals(1, BanManager.banHours(3, null));
    }

    @Test
    void formatsDurationHumanly() {
        assertEquals("1 ч.", BanManager.formatDuration(1));
        assertEquals("6 ч.", BanManager.formatDuration(6));
        assertEquals("1 дн.", BanManager.formatDuration(24));
        assertEquals("1 нед.", BanManager.formatDuration(168));
    }
}
