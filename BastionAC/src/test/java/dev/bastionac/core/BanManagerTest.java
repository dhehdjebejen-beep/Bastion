package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BanManagerTest {

    private static final List<Integer> STEPS = List.of(15, 60, 360, 1440);

    @Test
    void escalatesThroughTheLadder() {
        assertEquals(15, BanManager.banMinutes(0, STEPS));
        assertEquals(60, BanManager.banMinutes(1, STEPS));
        assertEquals(360, BanManager.banMinutes(2, STEPS));
        assertEquals(1440, BanManager.banMinutes(3, STEPS));
    }

    @Test
    void clampsBeyondLadderToLast() {
        assertEquals(1440, BanManager.banMinutes(4, STEPS));
        assertEquals(1440, BanManager.banMinutes(99, STEPS));
    }

    @Test
    void handlesEmptyOrNullSteps() {
        assertEquals(15, BanManager.banMinutes(0, List.of()));
        assertEquals(15, BanManager.banMinutes(3, null));
    }

    @Test
    void formatsMinutesHumanly() {
        assertEquals("15 мин.", BanManager.formatMinutes(15));
        assertEquals("1 ч.", BanManager.formatMinutes(60));
        assertEquals("1 дн.", BanManager.formatMinutes(1440));
    }

    @Test
    void formatsDurationHumanly() {
        assertEquals("1 ч.", BanManager.formatDuration(1));
        assertEquals("6 ч.", BanManager.formatDuration(6));
        assertEquals("1 дн.", BanManager.formatDuration(24));
        assertEquals("1 нед.", BanManager.formatDuration(168));
    }
}
