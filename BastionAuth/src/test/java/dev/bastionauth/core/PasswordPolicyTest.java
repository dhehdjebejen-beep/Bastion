package dev.bastionauth.core;

import dev.bastionauth.config.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class PasswordPolicyTest {

    private static PasswordPolicy policy() {
        return new PasswordPolicy(new AuthConfig.PasswordRules(), Set.of("qwerty123", "parol123"));
    }

    @Test
    void acceptsReasonablePassword() {
        assertNull(policy().validate("Steve", "krepkiyParol42"));
    }

    @Test
    void rejectsSpaces() {
        assertEquals("password.noSpaces", policy().validate("Steve", "has space1").messageKey());
    }

    @Test
    void rejectsTooShort() {
        assertEquals("password.tooShort", policy().validate("Steve", "a1b2c3").messageKey());
    }

    @Test
    void rejectsTooLong() {
        assertEquals("password.tooLong", policy().validate("Steve", "a1".repeat(40)).messageKey());
    }

    @Test
    void rejectsWithoutLetterOrDigit() {
        assertEquals("password.needLetterDigit", policy().validate("Steve", "123456789").messageKey());
        assertEquals("password.needLetterDigit", policy().validate("Steve", "abcdefghij").messageKey());
    }

    @Test
    void rejectsNameAsPassword() {
        assertEquals("password.equalsName", policy().validate("Steve1234", "steve1234").messageKey());
    }

    @Test
    void rejectsCommonPasswords() {
        assertEquals("password.common", policy().validate("Steve", "Qwerty123").messageKey());
    }

    @Test
    void bundledCommonListLoads() {
        Set<String> bundled = PasswordPolicy.loadBundledCommonPasswords();
        assertFalse(bundled.isEmpty());
        assertEquals(true, bundled.contains("minecraft123"));
    }
}
