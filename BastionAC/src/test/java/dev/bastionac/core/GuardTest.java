package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** An anti-cheat failure stays in the anti-cheat; running out of memory does not. */
class GuardTest {

    @Test
    void aFailureIsSwallowedAndTheFallbackAnswers() {
        Guard.run("test", () -> {
            throw new IllegalStateException("boom");
        });
        assertEquals("vanilla", Guard.call("test", () -> {
            throw new NullPointerException();
        }, "vanilla"));
        assertEquals("shield", Guard.call("test", () -> "shield", "vanilla"));
    }

    @Test
    void outOfMemoryStillStopsTheServer() {
        assertThrows(OutOfMemoryError.class, () -> Guard.run("test", () -> {
            throw new OutOfMemoryError();
        }));
    }
}
