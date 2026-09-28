package dev.bastionauth.core;

import dev.bastionauth.config.AuthConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Validates candidate passwords against the configured policy. */
public final class PasswordPolicy {

    public record Violation(String messageKey, Object[] args) {
        public static Violation of(String key, Object... args) {
            return new Violation(key, args);
        }
    }

    private final Set<String> commonPasswords;
    private volatile AuthConfig.PasswordRules rules;

    public PasswordPolicy(AuthConfig.PasswordRules rules, Set<String> commonPasswords) {
        this.rules = rules;
        this.commonPasswords = commonPasswords;
    }

    public void reconfigure(AuthConfig.PasswordRules rules) {
        this.rules = rules;
    }

    /** @return {@code null} when the password is acceptable, otherwise the violation to report. */
    public Violation validate(String username, String password) {
        AuthConfig.PasswordRules r = this.rules;
        if (password.chars().anyMatch(Character::isWhitespace)) return Violation.of("password.noSpaces");
        if (password.length() < r.minLength) return Violation.of("password.tooShort", r.minLength);
        if (password.length() > r.maxLength) return Violation.of("password.tooLong", r.maxLength);
        if (r.requireLetterAndDigit) {
            boolean letter = password.chars().anyMatch(Character::isLetter);
            boolean digit = password.chars().anyMatch(Character::isDigit);
            if (!letter || !digit) return Violation.of("password.needLetterDigit");
        }
        if (r.denyPasswordEqualToName && password.equalsIgnoreCase(username)) return Violation.of("password.equalsName");
        if (r.denyCommonPasswords && commonPasswords.contains(password.toLowerCase(Locale.ROOT))) {
            return Violation.of("password.common");
        }
        return null;
    }

    public static Set<String> loadBundledCommonPasswords() {
        Set<String> out = new HashSet<>();
        try (InputStream in = PasswordPolicy.class.getResourceAsStream("/bastionauth/common_passwords.txt")) {
            if (in == null) return out;
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String s = line.trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
            }
        } catch (Exception ignored) {
            // A missing bundled list only weakens one optional check; never fail startup over it.
        }
        return out;
    }
}
