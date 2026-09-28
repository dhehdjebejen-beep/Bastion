package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccountLinker grading: the composite scoring model and the name-similarity
 * heuristics that caught the "builder / builderr" twin pattern in the logs.
 */
class AccountLinkerTest {

    private final AccountLinker linker = new AccountLinker();

    // ------------------------------------------------------------------ grades

    @Test
    void noSignalsIsNone() {
        assertEquals(AccountLinker.Grade.NONE, linker.grade(linker.score(false, false, false, false, false)));
    }

    @Test
    void deviceAloneIsPossible() {
        // 60 points: the family-PC case — never a conviction on its own.
        assertEquals(AccountLinker.Grade.POSSIBLE, linker.grade(linker.score(true, false, false, false, false)));
    }

    @Test
    void devicePlusIpIsLikely() {
        // 60 + 25 = 85: device AND the same IP — strong but human-decided.
        assertEquals(AccountLinker.Grade.LIKELY, linker.grade(linker.score(true, true, false, false, false)));
    }

    @Test
    void twinPatternIsConfirmed() {
        // 60 + 25 + 15 = 100: device + IP + name decoration — the exact
        // signature from the July-August logs (same machine, same network,
        // name clone after each ban).
        assertEquals(AccountLinker.Grade.CONFIRMED,
                linker.grade(linker.score(true, true, true, false, false)));
    }

    @Test
    void evasionTimingPushesOver() {
        // 60 + 20 = 80: device + registered 30 min after the other's ban.
        assertEquals(AccountLinker.Grade.LIKELY,
                linker.grade(linker.score(true, false, false, true, false)));
    }

    @Test
    void passwordReuseOnTopOfLikelyIsConfirmed() {
        // 60 + 25 + 40 = 125.
        assertEquals(AccountLinker.Grade.CONFIRMED,
                linker.grade(linker.score(true, true, false, false, true)));
    }

    @Test
    void nothingAloneReachesLikely() {
        // Every single signal by itself stays at POSSIBLE or below — by design.
        assertEquals(AccountLinker.Grade.POSSIBLE, linker.grade(linker.score(true, false, false, false, false)));
        assertEquals(AccountLinker.Grade.NONE, linker.grade(linker.score(false, true, false, false, false)));
        assertEquals(AccountLinker.Grade.NONE, linker.grade(linker.score(false, false, true, false, false)));
        assertEquals(AccountLinker.Grade.NONE, linker.grade(linker.score(false, false, false, true, false)));
    }

    // ------------------------------------------------------------------ name similarity

    @Test
    void sameStemDifferentDecorationIsTwin() {
        assertTrue(AccountLinker.similarNames("steve_builder", "steve_builderr"));
        assertTrue(AccountLinker.similarNames("AlexMiner", "AlexxMiner"));
        assertTrue(AccountLinker.similarNames("vasya", "vasya2"));
    }

    @Test
    void identicalNamesAreNotASignal() {
        // Same name = same account, not a link between two accounts.
        assertFalse(AccountLinker.similarNames("vasya", "vasya"));
    }

    @Test
    void unrelatedNamesAreNotSimilar() {
        assertFalse(AccountLinker.similarNames("Steve", "Herobrine"));
        assertFalse(AccountLinker.similarNames("miner", "builder"));
    }

    @Test
    void closeSpellingIsSimilar() {
        assertTrue(AccountLinker.similarNames("xXkillerXx", "xXkillerrXx"));
    }

    @Test
    void shortStemsNeverMatch() {
        // A 1-2 char stem matches half the server by Levenshtein alone.
        assertFalse(AccountLinker.similarNames("ab", "ac"));
        assertFalse(AccountLinker.similarNames("ab1", "ab2"));
    }

    // ------------------------------------------------------------------ stem

    @Test
    void stemStripsDigitsAndUnderscores() {
        assertEquals("steve_builder", AccountLinker.stem("steve_builder2"));
        assertEquals("vasya", AccountLinker.stem("vasya_99"));
        assertEquals("vasya", AccountLinker.stem("VASYA"));
    }

    @Test
    void levenshteinBasics() {
        assertEquals(0, AccountLinker.levenshtein("builder", "builder"));
        assertEquals(1, AccountLinker.levenshtein("builder", "builderr"));
        // kitten → sitten is ONE substitution (k→s); the old expectation of
        // 2 was simply wrong and hid the correct implementation.
        assertEquals(1, AccountLinker.levenshtein("kitten", "sitten"));
        assertEquals(2, AccountLinker.levenshtein("builder", "buildebb"));
    }
}
