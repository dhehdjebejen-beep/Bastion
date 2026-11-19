package dev.bastionauth.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The term of /банip: Russian and English units, forever by default. */
class IpBanTermTest {

    @Test
    void termsAndReasons() {
        assertEquals(new IpBanCommands.Term(-1, ""), IpBanCommands.Term.parse(""));
        assertEquals(new IpBanCommands.Term(-1, "читы"), IpBanCommands.Term.parse("навсегда читы"));
        assertEquals(new IpBanCommands.Term(3 * 1440, "мульт"), IpBanCommands.Term.parse("3д мульт"));
        assertEquals(new IpBanCommands.Term(12 * 60, ""), IpBanCommands.Term.parse("12ч"));
        assertEquals(new IpBanCommands.Term(30, "спам"), IpBanCommands.Term.parse("30м спам"));
        assertEquals(new IpBanCommands.Term(43200, "x"), IpBanCommands.Term.parse("1мес x"));
        assertEquals(new IpBanCommands.Term(2 * 10080, "y"), IpBanCommands.Term.parse("2w y"));
        // No term in front: the whole text is the reason, the ban is forever.
        assertEquals(new IpBanCommands.Term(-1, "реклама хостинга"), IpBanCommands.Term.parse("реклама хостинга"));
    }
}
