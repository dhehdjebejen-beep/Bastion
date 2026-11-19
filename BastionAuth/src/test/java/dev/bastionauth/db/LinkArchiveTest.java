package dev.bastionauth.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The recount's archive (12.11): an open pair leaves the ledger, a human's decision never does. */
class LinkArchiveTest {

    @TempDir
    Path dir;

    @Test
    void openPairsMoveDecidedPairsStay() throws Exception {
        Database db = new Database(dir.resolve("auth.db"));
        long now = System.currentTimeMillis();
        db.upsertLink("a", "A", "b", "B", 90, "LIKELY", "launcher-uuid+timing", now);
        db.upsertLink("c", "C", "d", "D", 0, "NONE", "", now);
        db.upsertLink("e", "E", "f", "F", 80, "LIKELY", "ip+name", now);
        db.upsertLink("g", "G", "h", "H", 35, "NONE", "profile", now);
        assertTrue(db.setLinkVerdict("c", "d", "WATCH", "admin", "", now));
        assertTrue(db.setLinkVerdict("e", "f", "TRUSTED", "admin", "", now));
        assertTrue(db.setLinkVerdict("g", "h", "ALT", "admin", "", now));

        assertTrue(db.archiveOpenLink("b", "a", "recount 90->0", now), "NEW moves, either order of the pair");
        assertTrue(db.archiveOpenLink("c", "d", "recount 0->0", now), "WATCH moves");
        assertFalse(db.archiveOpenLink("e", "f", "recount", now), "TRUSTED stays");
        assertFalse(db.archiveOpenLink("g", "h", "recount", now), "ALT stays");
        assertFalse(db.archiveOpenLink("a", "b", "again", now), "a moved pair is not moved twice");

        assertNull(db.linkBetween("a", "b"));
        assertNull(db.linkBetween("c", "d"));
        assertNotNull(db.linkBetween("e", "f"));
        assertNotNull(db.linkBetween("g", "h"));
        assertEquals(2, db.archivedLinkCount());
        assertEquals(2, db.allLinks().size());
        db.close();
    }

    @Test
    void theRecountRescoresAWatchedPairAndKeepsTheMark() throws Exception {
        Database db = new Database(dir.resolve("auth2.db"));
        long now = System.currentTimeMillis();
        db.upsertLink("a", "A", "b", "B", 85, "LIKELY", "device+host+timing", now);
        db.setLinkVerdict("a", "b", "WATCH", "admin", "note", now);
        db.rescoreOpenLink("a", "b", 80, "LIKELY", "device+timing", now);
        Database.LinkLedgerRow row = db.linkBetween("a", "b");
        assertEquals(80, row.bestScore());
        assertEquals("device+timing", row.bestSignals());
        assertEquals("WATCH", row.verdict());
        db.setLinkVerdict("a", "b", "TRUSTED", "admin", "", now);
        db.rescoreOpenLink("a", "b", 10, "NONE", "", now);
        assertEquals(80, db.linkBetween("a", "b").bestScore(), "a decided pair keeps its numbers");
        db.close();
    }
}
