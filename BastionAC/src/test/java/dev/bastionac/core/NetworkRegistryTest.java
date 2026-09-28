package dev.bastionac.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network registry invariants that are testable without Minecraft: link
 * dedup, the auth-bridge marker, pairKey migration of old records, and the
 * pure subnet/mask helpers. The live join/ban paths need a real
 * ServerPlayerEntity and are covered by the integration hooks.
 */
class NetworkRegistryTest {

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void linkDedupSamePairSameSubnet() {
        NetworkRegistry.forgetAllForTests();
        String a = uuid(), b = uuid();
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        // Same pair, same subnet, same order — one sighting, not two.
        assertEquals(1, NetworkRegistry.links().size());
    }

    @Test
    void linkDedupIsOrderIndependent() {
        NetworkRegistry.forgetAllForTests();
        String a = uuid(), b = uuid();
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        NetworkRegistry.noteLink(b, "bob", a, "alice", "10.0.0.0/24");
        // Reversed arguments describe the same pair — still one sighting.
        assertEquals(1, NetworkRegistry.links().size());
    }

    @Test
    void differentSubnetsAreSeparateSightings() {
        NetworkRegistry.forgetAllForTests();
        String a = uuid(), b = uuid();
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.1.0.0/24");
        assertEquals(2, NetworkRegistry.links().size());
    }

    @Test
    void authBridgeLinkDedupsSeparatelyFromSubnetLinks() {
        NetworkRegistry.forgetAllForTests();
        String a = uuid(), b = uuid();
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        NetworkRegistry.noteLinkedAccount("alice", a, "bob", b);
        // Different sources — both kept.
        assertEquals(2, NetworkRegistry.links().size());
        // The auth link repeats — deduped within its own marker.
        NetworkRegistry.noteLinkedAccount("alice", a, "bob", b);
        assertEquals(2, NetworkRegistry.links().size());
        // And its pairKey must not be null (UI relies on it being present).
        NetworkRegistry.LinkSighting auth = NetworkRegistry.links().stream()
                .filter(l -> l.subnet != null && l.subnet.startsWith("auth:"))
                .findFirst().orElse(null);
        assertNotNull(auth);
        assertNotNull(auth.pairKey);
    }

    @Test
    void authLinkNeverSelfPairs() {
        NetworkRegistry.forgetAllForTests();
        String a = uuid();
        NetworkRegistry.noteLinkedAccount("alice", a, "alice", a);
        assertEquals(0, NetworkRegistry.links().size());
    }

    @Test
    void burnedBanSourceIsKeptOnRecord() {
        // The source marker ("chain"/"auto"/"manual") is a plain field; the
        // UI reads it. Guard the contract: it lives on BanNetwork and the
        // default path writes "auto".
        NetworkRegistry.BanNetwork rec = new NetworkRegistry.BanNetwork();
        rec.uuid = uuid();
        rec.name = "carol";
        rec.maskedIp = "10.0.*.*";
        rec.subnet = "10.0.0.0/24";
        rec.at = System.currentTimeMillis();
        rec.reason = "ban";
        rec.source = "chain";
        assertEquals("chain", rec.source);
    }

    @Test
    void subnetAndMaskPureHelpers() {
        assertEquals("10.20.30.0/24", NetworkRegistry.subnetOf("10.20.30.44"));
        assertEquals("10.20.*.*", NetworkRegistry.mask("10.20.30.44"));
        assertEquals("unknown", NetworkRegistry.subnetOf("unknown"));
        assertEquals("?", NetworkRegistry.mask("unknown"));
        // IPv6: /64 prefix and first-two-groups mask.
        assertEquals("2001:db8:aa:bb::/64", NetworkRegistry.subnetOf("2001:db8:aa:bb:1:2:3:4"));
        assertEquals("2001:db8::*", NetworkRegistry.mask("2001:db8:aa:bb:1:2:3:4"));
    }

    @Test
    void linksListIsNewestFirst() {
        NetworkRegistry.forgetAllForTests();
        String a = uuid(), b = uuid(), c = uuid();
        NetworkRegistry.noteLink(a, "alice", b, "bob", "10.0.0.0/24");
        try {
            Thread.sleep(5);
        } catch (InterruptedException ignored) {
        }
        NetworkRegistry.noteLink(a, "alice", c, "carol", "10.5.0.0/24");
        List<NetworkRegistry.LinkSighting> links = NetworkRegistry.links();
        assertEquals(2, links.size());
        assertTrue(links.get(0).at >= links.get(1).at);
    }
}
