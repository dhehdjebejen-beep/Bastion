package dev.bastionauth.core;

import dev.bastionauth.db.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The link register: a staff decision about a pair must change what the gates
 * answer, must survive the analysis re-scoring the pair on every join, and must
 * land on databases created before the columns existed.
 */
class LinkVerdictTest {

    private static final AccountLinker.Grade LIKELY = AccountLinker.Grade.LIKELY;

    @TempDir
    Path dir;
    Database db;

    private final String a = "00000000-0000-0000-0000-00000000000a";
    private final String b = "00000000-0000-0000-0000-00000000000b";
    private final String c = "00000000-0000-0000-0000-00000000000c";

    @BeforeEach
    void setUp() throws Exception {
        db = new Database(dir.resolve("verdict.db"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    // ------------------------------------------------------------------ the gate

    @Test
    void withoutADecisionTheGradeDecidesAsBefore() {
        assertTrue(AccountLinker.linkedByLedger("NEW", "CONFIRMED", LIKELY));
        assertTrue(AccountLinker.linkedByLedger("NEW", "LIKELY", LIKELY));
        assertFalse(AccountLinker.linkedByLedger("NEW", "POSSIBLE", LIKELY));
        assertTrue(AccountLinker.linkedByLedger(null, "CONFIRMED", LIKELY), "old rows have no verdict");
    }

    @Test
    void trustedClearsEvenAConfirmedPair() {
        assertFalse(AccountLinker.linkedByLedger("TRUSTED", "CONFIRMED", LIKELY));
    }

    @Test
    void altBindsEvenAPairTheModelNeverScored() {
        assertTrue(AccountLinker.linkedByLedger("ALT", "NONE", LIKELY));
        assertTrue(AccountLinker.linkedByLedger("ALT", "POSSIBLE", LIKELY));
    }

    @Test
    void watchKeepsTheScoreInCharge() {
        assertTrue(AccountLinker.linkedByLedger("WATCH", "CONFIRMED", LIKELY));
        assertFalse(AccountLinker.linkedByLedger("WATCH", "POSSIBLE", LIKELY));
    }

    @Test
    void trustStaysBetweenTheTwoAndDoesNotReachStateBenefits() {
        // Paying, trading, duelling each other: cleared.
        assertFalse(AccountLinker.linkedByLedger("TRUSTED", "CONFIRMED", LIKELY));
        // A referral bonus or the loan limit: still one person.
        assertTrue(AccountLinker.linkedForStateBenefits("TRUSTED", "CONFIRMED", LIKELY));
        assertFalse(AccountLinker.linkedForStateBenefits("TRUSTED", "POSSIBLE", LIKELY),
                "trust does not bind a weak pair either");
        assertTrue(AccountLinker.linkedForStateBenefits("ALT", "NONE", LIKELY));
        assertTrue(AccountLinker.linkedForStateBenefits("NEW", "LIKELY", LIKELY));
    }

    @Test
    void garbageNeverThrowsAndNeverClears() {
        assertEquals(LinkVerdict.NEW, LinkVerdict.of("nonsense"));
        assertTrue(AccountLinker.linkedByLedger("nonsense", "CONFIRMED", LIKELY));
        assertFalse(AccountLinker.linkedByLedger("NEW", "bogus-grade", LIKELY));
    }

    @Test
    void staffSpellingsParse() {
        assertEquals(LinkVerdict.TRUSTED, LinkVerdict.parse("доверять"));
        assertEquals(LinkVerdict.WATCH, LinkVerdict.parse("Наблюдать"));
        assertEquals(LinkVerdict.ALT, LinkVerdict.parse("мульти"));
        assertEquals(LinkVerdict.NEW, LinkVerdict.parse("сброс"));
        assertNull(LinkVerdict.parse("удалить"));
    }

    // ------------------------------------------------------------------ the ledger

    @Test
    void verdictSurvivesTheAnalysisRescoringThePair() {
        long now = System.currentTimeMillis();
        db.upsertLink(a, "alpha", b, "beta", 120, "CONFIRMED", "device+ip", now);
        assertTrue(db.setLinkVerdict(a, b, "TRUSTED", "admin", "мои аккаунты", now));
        // Next join: the analysis files the same pair again, stronger.
        db.upsertLink(b, "beta", a, "alpha", 160, "CONFIRMED", "device+ip+password", now + 1000);

        Database.LinkLedgerRow row = db.linkBetween(a, b);
        assertNotNull(row);
        assertEquals("TRUSTED", row.verdict(), "a re-score must never undo a human decision");
        assertEquals("admin", row.verdictBy());
        assertEquals("мои аккаунты", row.verdictNote());
        assertEquals(160, row.bestScore(), "the evidence keeps accumulating underneath");
        assertFalse(AccountLinker.linkedByLedger(row.verdict(), row.bestGrade(), LIKELY));
    }

    @Test
    void declaringAPairTheModelNeverFoundCreatesAManualRow() {
        long now = System.currentTimeMillis();
        assertFalse(db.setLinkVerdict(b, a, "ALT", "admin", "", now), "no row yet");
        assertTrue(db.declareLink(b, "beta", a, "alpha", "ALT", "admin", "", now));

        Database.LinkLedgerRow row = db.linkBetween(a, b);
        assertNotNull(row);
        assertEquals("manual", row.bestSignals());
        assertEquals(0, row.bestScore());
        // Names must follow their uuids even though the key reordered the pair.
        assertEquals("alpha", row.otherName(b));
        assertEquals("beta", row.otherName(a));
        assertTrue(AccountLinker.linkedByLedger(row.verdict(), row.bestGrade(), LIKELY));
    }

    @Test
    void bulkVerdictCoversEveryPairOfTheAccount() {
        long now = System.currentTimeMillis();
        db.upsertLink(a, "alpha", b, "beta", 100, "CONFIRMED", "device", now);
        db.upsertLink(a, "alpha", c, "gamma", 90, "LIKELY", "device", now);
        db.upsertLink(b, "beta", c, "gamma", 90, "LIKELY", "device", now);

        assertEquals(2, db.setVerdictForAllLinksOf(a, "TRUSTED", "admin", "", now));
        assertEquals("TRUSTED", db.linkBetween(a, b).verdict());
        assertEquals("TRUSTED", db.linkBetween(a, c).verdict());
        assertEquals("NEW", db.linkBetween(b, c).verdict(), "a pair without A is untouched");
    }

    @Test
    void registerListsUndecidedPairsFirst() {
        long now = System.currentTimeMillis();
        db.upsertLink(a, "alpha", b, "beta", 160, "CONFIRMED", "device", now);
        db.upsertLink(a, "alpha", c, "gamma", 80, "LIKELY", "device", now);
        db.setLinkVerdict(a, b, "TRUSTED", "admin", "", now);

        List<Database.LinkLedgerRow> all = db.allLinks(10);
        assertEquals(2, all.size());
        assertEquals("NEW", all.get(0).verdict(), "the weaker but undecided pair comes first");
        assertEquals(1, db.unreviewedLinkCount());
    }

    // ------------------------------------------------------------------ migration

    @Test
    void preExistingLedgerGainsTheColumnsWithEveryRowAtNew() throws Exception {
        Path old = dir.resolve("old.db");
        // The 1.7 schema, verbatim, with one link already in it.
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + old.toAbsolutePath());
             Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE account_links (
                      pair_key TEXT PRIMARY KEY, uuid_a TEXT NOT NULL, uuid_b TEXT NOT NULL,
                      name_a TEXT, name_b TEXT, best_score INTEGER NOT NULL, best_grade TEXT NOT NULL,
                      best_signals TEXT, last_score INTEGER NOT NULL, last_signals TEXT,
                      first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, times INTEGER NOT NULL DEFAULT 1)""");
            st.executeUpdate("INSERT INTO account_links VALUES('" + Database.pairKey(a, b) + "','" + a + "','" + b
                    + "','alpha','beta',120,'CONFIRMED','device',120,'device',1,2,3)");
        }
        try (Database migrated = new Database(old)) {
            Database.LinkLedgerRow row = migrated.linkBetween(a, b);
            assertNotNull(row, "the old link must still be readable");
            assertEquals("NEW", row.verdict());
            assertTrue(AccountLinker.linkedByLedger(row.verdict(), row.bestGrade(), LIKELY),
                    "nothing changes for old links until someone decides");
            assertTrue(migrated.setLinkVerdict(a, b, "TRUSTED", "admin", "", 5));
            assertEquals("TRUSTED", migrated.linkBetween(a, b).verdict());
        }
    }

    @Test
    void uuidsInTheseTestsAreValid() {
        // Guards the fixtures: the bridge parses these with UUID.fromString.
        UUID.fromString(a);
        UUID.fromString(b);
        UUID.fromString(c);
    }
}
