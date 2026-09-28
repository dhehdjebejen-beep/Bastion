package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Subnet derivation and name-stem logic — the pure parts of the fingerprint. */
class DeviceFingerprintTest {

    @Test
    void ipv4CollapsesToSlash24() {
        assertEquals("203.0.113.0/24", DeviceFingerprint.subnetOf("203.0.113.4"));
        assertEquals("192.168.0.0/24", DeviceFingerprint.subnetOf("192.168.0.13"));
    }

    @Test
    void ipv6CollapsesToSlash64() {
        assertEquals("2001:db8:1234:5678::/64",
                DeviceFingerprint.subnetOf("2001:db8:1234:5678:9abc:def0:1122:3344"));
    }

    @Test
    void unknownAndNullAreRejected() {
        assertNull(DeviceFingerprint.subnetOf((String) "unknown"));
        assertNull(DeviceFingerprint.subnetOf((String) null));
    }

    @Test
    void malformedIpv4FallsBackToFullMask() {
        assertEquals("1.2.3.4.5/32", DeviceFingerprint.subnetOf("1.2.3.4.5"));
    }

    @Test
    void sameDeviceComparesConstantly() {
        assertTrue(DeviceFingerprint.sameDevice("abc", "abc"));
        assertFalse(DeviceFingerprint.sameDevice("abc", "abd"));
        assertFalse(DeviceFingerprint.sameDevice(null, "abc"));
    }
}
