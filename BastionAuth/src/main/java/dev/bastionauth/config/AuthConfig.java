package dev.bastionauth.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bastionauth.core.Lang;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Mod configuration, persisted as pretty-printed JSON in
 * {@code config/bastionauth/config.json}.
 *
 * <p>Unknown keys are ignored, missing keys fall back to defaults and are
 * written back to the file. A corrupted file is backed up and replaced with
 * defaults: the server keeps running with secure settings rather than
 * without authentication (fail-safe, never fail-open).
 */
public final class AuthConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Language of every line the mod writes: "ru" (default) or "en", or any
     * {@code <code>.json} placed in {@code config/bastionauth/lang}. Switched
     * live with {@code /auth lang <code>}. The user agreement is not part of
     * it: it stays the text shipped in the jar.
     */
    public String language = "ru";

    public General general = new General();
    public PasswordRules passwordRules = new PasswordRules();
    public Hashing hashing = new Hashing();
    public BruteForce bruteForce = new BruteForce();
    public Session session = new Session();
    public Protection protection = new Protection();
    public Linking linking = new Linking();
    public TwoFactorSection twoFactor = new TwoFactorSection();
    public CodewordSection codeword = new CodewordSection();
    public Map<String, String> messages = new LinkedHashMap<>();

    public static final class TwoFactorSection {
        /** Players may enable a TOTP second factor (/2fa включить). */
        public boolean enabled = true;
        /** Seconds to type the code after the password was accepted. */
        public int codeTimeoutSeconds = 90;
        /** Wrong codes per connection before the player is disconnected. */
        public int maxTries = 5;
        /** Accepted steps around the current one (±1 = the app's clock may be 30 s off). */
        public int window = 1;
        /** Backup codes handed out at setup. */
        public int backupCodes = 8;
        /** Issuer name shown in the authenticator app. */
        public String issuer = "MaxCora";
        /** Tell operators without a second factor about it at login. */
        public boolean remindOpsWithout2fa = true;
    }

    public static final class CodewordSection {
        /** Players may set a codeword that guards sensitive actions (/слово). */
        public boolean enabled = true;
        /** Minutes a spoken word keeps the guarded actions open. */
        public int unlockMinutes = 5;
        public int minLength = 4;
        public int maxLength = 32;
    }

    public static final class General {
        /** Allow /register for new players. Turn off to freeze the player base. */
        public boolean allowRegistration = true;
        /** Seconds a player may stay connected without logging in before being kicked. */
        public int loginTimeoutSeconds = 60;
        /**
         * Seconds a player has to accept the user agreement before being
         * kicked. Separate from the login timeout on purpose: the agreement
         * is three books long, and a minute is not reading time. The login
         * clock starts only once the agreement is accepted.
         */
        public int agreementTimeoutSeconds = 600;
        /** Wrong passwords allowed within one connection before the player is kicked. */
        public int maxLoginTriesPerConnection = 3;
        /** Usernames must match this pattern or the connection is refused. */
        public String usernameRegex = "^[A-Za-z0-9_]{3,16}$";
        /** Refuse joins whose name differs from the registered name only by letter case. */
        public boolean enforceExactNameCase = true;
    }

    public static final class PasswordRules {
        public int minLength = 8;
        public int maxLength = 64;
        public boolean requireLetterAndDigit = true;
        public boolean denyCommonPasswords = true;
        public boolean denyPasswordEqualToName = true;
    }

    public static final class Hashing {
        /** Argon2id memory cost in KiB (65536 = 64 MiB). */
        public int memoryKib = 65536;
        public int iterations = 3;
        public int parallelism = 1;
        /** Hash computations allowed to run at the same time (DoS guard). */
        public int maxConcurrentHashes = 2;
        /** Queued auth operations beyond which new ones are rejected with "busy". */
        public int maxQueuedOperations = 64;
        /** HMAC passwords with the secret.key pepper before hashing. */
        public boolean usePepper = true;
        /**
         * 1.13: hashes made by 1.13 say whether they are peppered, and a wrong
         * password costs one Argon2 run. A hash from an older build does not
         * say; with this on, it is also tried in the other form (as every
         * build up to 1.12 did on every failure) — only for a server that
         * switched {@link #usePepper} at some point before 1.13.
         */
        public boolean legacyPepperFallback = false;
    }

    public static final class BruteForce {
        /** Failed logins on one account before it is temporarily locked. */
        public int maxFailedPerAccount = 5;
        public int accountLockMinutes = 15;
        /** Failed logins from one IP (any account) within the window before the IP is blocked. */
        public int ipMaxFailures = 10;
        public int ipFailureWindowMinutes = 5;
        public int ipBlockMinutes = 15;
        /** Join attempts allowed per IP per minute (bot-flood guard). */
        public int maxJoinsPerIpPerMinute = 6;
        /** Accounts that may be registered from one IP straight away. */
        public int maxAccountsPerIp = 5;
        /**
         * Past {@link #maxAccountsPerIp}, allow further accounts with a growing
         * wait instead of refusing for good: the next one opens
         * {@link #extraAccountWaitDays} after the latest registration from the
         * IP, the one after it twice that, and so on. Off = the old hard limit.
         */
        public boolean extraAccountsWithWait = true;
        /** The waiting step for accounts past the limit, in days. */
        public int extraAccountWaitDays = 7;
        /** Absolute ceiling of accounts per IP even with waiting; 0 = none. */
        public int maxAccountsPerIpHard = 0;
        /**
         * Accounts nobody has logged into for this many days no longer count
         * towards the per-IP quota (nor towards its waiting time). Behind a
         * carrier NAT the quota is shared by strangers: one person registering
         * throwaway accounts used to make every newcomer on that IP wait for
         * weeks. 0 = count every account ever registered.
         */
        public int quotaIgnoresDormantDays = 30;
        /**
         * Whether a temporarily blocked IP is refused at the connection stage
         * too. Off: the block only refuses {@code /login}, so ten wrong
         * passwords behind a carrier NAT do not lock every neighbour out of
         * the server for a quarter of an hour.
         */
        public boolean blockJoinsFromBlockedIp = false;
    }

    public static final class Session {
        /** Re-login automatically when the same account reconnects from the same IP shortly after. */
        public boolean enabled = true;
        /** Session lifetime; sessions also survive server restarts (stored in auth.db). */
        public int ttlMinutes = 360;
        /**
         * Everyday client settings (render distance, skin parts, particles,
         * chat options) that may differ between the session and the rejoin,
         * 0–4. The install itself — brand, language, main hand and the
         * launcher's own UUID — must always match.
         */
        public int settingChangesAllowed = 1;
        /**
         * 1.13: an address another account has joined from during the session
         * is not a secret — a carrier NAT, a dormitory, a brother on the same
         * Wi-Fi. A session never resumes from it; the password is asked.
         */
        public boolean refuseSharedAddress = true;
        /**
         * Operators never resume by IP. Behind a carrier NAT the IP is shared
         * with strangers; a stranger who knows an operator's name must not
         * inherit an operator session.
         */
        public boolean disableForOps = true;
        /**
         * 1.9: with three people online, typing the password on every rejoin
         * was the owner's least favourite security feature. When true (the
         * default) each operator decides for themselves with
         * {@code /auth strict on|off} and is reminded of the choice at every
         * login; {@link #disableForOps} is then ignored. False brings back the
         * old server-wide rule.
         */
        public boolean opStrictOptional = true;
    }

    public static final class Linking {
        /** Hours after a candidate's last sighting within which a new account counts as "registered right after". */
        public int timingWindowHours = 72;
        /** Minimum grade (POSSIBLE / LIKELY / CONFIRMED) that is reported to staff and to BastionAC. */
        public String reportGrade = "LIKELY";
        /** Re-run the analysis with the plugin-channel list this many seconds after login. */
        public int channelsDelaySeconds = 5;
    }

    public static final class Protection {
        /** Hide unauthenticated players with an invisibility effect. */
        public boolean invisibleWhileUnauthenticated = true;
        /** Prevent unauthenticated players from vacuuming up dropped items. */
        public boolean blockItemPickup = true;
        /** Extra commands (without slash) allowed before login, e.g. "rules". */
        public List<String> extraCommandWhitelist = new ArrayList<>();
        /**
         * Before the password the client sees a plain account: no effects,
         * armour, absorption, XP or flight, survival, no F3 coordinates — and
         * it cannot move. See StateMask.
         */
        public boolean hideAccountState = true;
        /** Before the password the player waits above their world's spawn, not at their base. See Limbo. */
        public boolean hideLocation = true;
        /** Limbo: this many blocks below the world's top, over an invisible client-side platform. */
        public int limboBelowTop = 2;
        /**
         * Before the password the player is a spectator (once the agreement is
         * on file) and gets their own game mode back on login — survival when
         * that is unknown. See SpectatorGate.
         */
        public boolean spectatorBeforeLogin = true;
    }

    public static AuthConfig loadOrCreate(Path file, Consumer<String> warn) throws IOException {
        AuthConfig cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AuthConfig.class);
            } catch (RuntimeException e) {
                Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                warn.accept("config.json is not valid JSON; backed it up to " + backup.getFileName()
                        + " and applied secure defaults. Parse error: " + e.getMessage());
            }
        }
        if (cfg == null) cfg = new AuthConfig();
        cfg.fillDefaults();
        cfg.clamp();
        Files.writeString(file, GSON.toJson(cfg) + System.lineSeparator(), StandardCharsets.UTF_8);
        return cfg;
    }

    private void fillDefaults() {
        if (general == null) general = new General();
        if (passwordRules == null) passwordRules = new PasswordRules();
        if (hashing == null) hashing = new Hashing();
        if (bruteForce == null) bruteForce = new BruteForce();
        if (session == null) session = new Session();
        if (protection == null) protection = new Protection();
        if (linking == null) linking = new Linking();
        if (language == null || language.isBlank()) language = "ru";
        if (linking.reportGrade == null || linking.reportGrade.isBlank()) linking.reportGrade = "LIKELY";
        if (protection.extraCommandWhitelist == null) protection.extraCommandWhitelist = new ArrayList<>();
        if (general.usernameRegex == null || general.usernameRegex.isBlank()) {
            general.usernameRegex = "^[A-Za-z0-9_]{3,16}$";
        }
        Map<String, String> merged = defaultMessages();
        if (messages != null) merged.putAll(messages);
        // 1.8.3: a trusted pair is no longer one borrower. The 1.8.1 line
        // said otherwise and was saved into config.json; replace it unless the
        // admin rewrote it.
        if (OLD_TRUSTED_NOTE.equals(merged.get("admin.links.trustedNote"))) {
            merged.put("admin.links.trustedNote", defaultMessages().get("admin.links.trustedNote"));
        }
        // 1.9.1: the 1.9.0 line used a {0} placeholder that String.format
        // never fills, so ops saw a literal "{0}". Fix the saved copy too.
        String strictOff = merged.get("opstrict.off");
        if (strictOff != null && strictOff.contains("{0}")) merged.put("opstrict.off", strictOff.replace("{0}", "%s"));
        messages = merged;
    }

    /** Writes the config back, e.g. after {@code /auth lang} changed the language. */
    public synchronized void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(this) + System.lineSeparator(), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Only the {@code language} of a config file, read before the commands are
     * registered (their argument names are translated once). "ru" when the
     * file is missing or unreadable.
     */
    public static String peekLanguage(Path file) {
        try {
            if (!Files.exists(file)) return "ru";
            com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (e.isJsonObject() && e.getAsJsonObject().has("language")) {
                return e.getAsJsonObject().get("language").getAsString();
            }
        } catch (Exception ignored) {
            // loadOrCreate reports a broken file properly a moment later
        }
        return "ru";
    }

    private static final String OLD_TRUSTED_NOTE =
            "&8Доверенная пара: переводы, дуэли, аукцион, обмен и ЗАГС между ними открыты; рефералы и лимит МФО по-прежнему считают их одним лицом.";

    private void clamp() {
        general.loginTimeoutSeconds = clampInt(general.loginTimeoutSeconds, 15, 600);
        general.agreementTimeoutSeconds = clampInt(general.agreementTimeoutSeconds, 60, 3600);
        general.maxLoginTriesPerConnection = clampInt(general.maxLoginTriesPerConnection, 1, 10);
        passwordRules.minLength = clampInt(passwordRules.minLength, 4, 32);
        passwordRules.maxLength = clampInt(passwordRules.maxLength, passwordRules.minLength, 128);
        hashing.memoryKib = clampInt(hashing.memoryKib, 8 * 1024, 1024 * 1024);
        hashing.iterations = clampInt(hashing.iterations, 1, 20);
        hashing.parallelism = clampInt(hashing.parallelism, 1, 8);
        hashing.maxConcurrentHashes = clampInt(hashing.maxConcurrentHashes, 1, 8);
        hashing.maxQueuedOperations = clampInt(hashing.maxQueuedOperations, 8, 1024);
        bruteForce.maxFailedPerAccount = clampInt(bruteForce.maxFailedPerAccount, 2, 50);
        bruteForce.accountLockMinutes = clampInt(bruteForce.accountLockMinutes, 1, 1440);
        bruteForce.ipMaxFailures = clampInt(bruteForce.ipMaxFailures, 3, 200);
        bruteForce.ipFailureWindowMinutes = clampInt(bruteForce.ipFailureWindowMinutes, 1, 120);
        bruteForce.ipBlockMinutes = clampInt(bruteForce.ipBlockMinutes, 1, 1440);
        bruteForce.maxJoinsPerIpPerMinute = clampInt(bruteForce.maxJoinsPerIpPerMinute, 2, 60);
        bruteForce.maxAccountsPerIp = clampInt(bruteForce.maxAccountsPerIp, 1, 100);
        bruteForce.extraAccountWaitDays = clampInt(bruteForce.extraAccountWaitDays <= 0 ? 7 : bruteForce.extraAccountWaitDays, 1, 365);
        bruteForce.maxAccountsPerIpHard = clampInt(bruteForce.maxAccountsPerIpHard, 0, 1000);
        bruteForce.quotaIgnoresDormantDays = clampInt(bruteForce.quotaIgnoresDormantDays, 0, 3650);
        session.ttlMinutes = clampInt(session.ttlMinutes, 1, 1440);
        session.settingChangesAllowed = clampInt(session.settingChangesAllowed, 0, 4);
        linking.timingWindowHours = clampInt(linking.timingWindowHours, 1, 24 * 365);
        linking.channelsDelaySeconds = clampInt(linking.channelsDelaySeconds, 1, 120);
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Message template for the key with {@code String.format} args applied. */
    public String message(String key, Object... args) {
        // Templates are stored in Russian; an untouched one is shown in the
        // server language, one the admin rewrote is shown as written.
        String template = Lang.t(messages != null ? messages.getOrDefault(key, key) : key);
        if (args == null || args.length == 0) return template;
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            return template;
        }
    }

    /** Translates {@code &c}-style colour codes to §-codes. */
    public static String colorize(String s) {
        return s == null ? "" : s.replaceAll("(?i)&([0-9a-fk-or])", "§$1");
    }

    private static Map<String, String> defaultMessages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("join.title", Lang.k("&6&lАВТОРИЗАЦИЯ"));
        m.put("join.subtitle.register", Lang.k("&7Придумайте пароль: &f/register <пароль> <пароль>"));
        m.put("join.subtitle.login", Lang.k("&7Введите пароль: &f/login <пароль>"));
        m.put("reminder.register", Lang.k("&e→ &7Регистрация: &f/register <пароль> <пароль>"));
        m.put("reminder.login", Lang.k("&e→ &7Вход: &f/login <пароль> &8(осталось %s сек.)"));
        m.put("reminder.agreement", Lang.k("&e→ &7Примите соглашение: &f/agreement accept &8(осталось %s мин)"));
        m.put("agreement.button", Lang.k("&a&l[ ✔ ПРИНЯТЬ СОГЛАШЕНИЕ ]"));
        m.put("agreement.buttonHover", Lang.k("&7Нажмите — это то же самое, что ввести /agreement accept"));
        m.put("agreement.syntax", Lang.k("&eПринять соглашение: &f/agreement accept &7(или нажмите кнопку в чате)"));
        m.put("agreement.acceptedNext", Lang.k("&e→ &7Теперь войдите: &f/login <пароль> &8(на это даётся %s сек.)"));
        m.put("timeout.kick", Lang.k("&cВремя на вход истекло. Подключитесь снова."));
        m.put("register.disabled", Lang.k("&cРегистрация новых игроков закрыта."));
        m.put("register.already", Lang.k("&cВы уже зарегистрированы. Вход: &f/login <пароль>"));
        m.put("register.nameTaken", Lang.k("&cЭтот ник уже занят (другим написанием букв)."));
        m.put("register.mismatch", Lang.k("&cПароли не совпадают, попробуйте ещё раз."));
        m.put("register.ipLimit", Lang.k("&cС вашего IP уже зарегистрировано слишком много аккаунтов."));
        m.put("register.ipWait", Lang.k("&cС вашего IP уже &f%s&c аккаунтов. Следующий можно будет зарегистрировать &f%s &7(через %s)&c."));
        m.put("register.success", Lang.k("&aРегистрация успешна. Добро пожаловать!"));
        m.put("login.notRegistered", Lang.k("&cВы не зарегистрированы. Используйте &f/register <пароль> <пароль>"));
        m.put("login.already", Lang.k("&aВы уже вошли."));
        m.put("login.wrong", Lang.k("&cНеверный пароль. Осталось попыток: &f%s"));
        m.put("login.tooManyTries", Lang.k("&cСлишком много неверных попыток."));
        m.put("login.locked", Lang.k("&cАккаунт временно заблокирован после неверных паролей. Подождите ~%s мин."));
        m.put("login.ipBlocked", Lang.k("&cСлишком много неверных попыток с вашего IP. Попробуйте позже."));
        m.put("login.success", Lang.k("&aВход выполнен. Приятной игры!"));
        m.put("login.lastSeen", Lang.k("&7Прошлый вход: &f%s &7с IP &f%s"));
        m.put("session.restored", Lang.k("&aСессия восстановлена (тот же IP). Приятной игры!"));
        m.put("logout.done", Lang.k("&7Вы вышли из аккаунта. Сессия закрыта — для игры войдите снова."));
        m.put("changepw.wrongOld", Lang.k("&cСтарый пароль неверен."));
        m.put("changepw.success", Lang.k("&aПароль изменён."));
        m.put("password.noSpaces", Lang.k("&cПароль не должен содержать пробелы."));
        m.put("password.tooShort", Lang.k("&cПароль слишком короткий: минимум %s символов."));
        m.put("password.tooLong", Lang.k("&cПароль слишком длинный: максимум %s символов."));
        m.put("password.needLetterDigit", Lang.k("&cПароль должен содержать и буквы, и цифры."));
        m.put("password.equalsName", Lang.k("&cПароль не должен совпадать с ником."));
        m.put("password.common", Lang.k("&cЭтот пароль слишком популярен — придумайте другой."));
        m.put("mustLogin", Lang.k("&cСначала войдите: &f/login <пароль>"));
        m.put("chat.blocked", Lang.k("&cЧат недоступен до входа."));
        m.put("command.blocked", Lang.k("&cКоманды недоступны до входа: &f/login <пароль>"));
        m.put("busy", Lang.k("&eСервер сейчас занят, попробуйте через пару секунд."));
        m.put("inProgress", Lang.k("&eПодождите — предыдущий запрос ещё обрабатывается."));
        m.put("join.denied.badName", Lang.k("Ник не подходит: 3–16 символов, только A-Z, a-z, 0-9 и _"));
        m.put("join.denied.caseMismatch", Lang.k("Этот ник зарегистрирован как «%s».\nЗайдите с точно таким же написанием букв."));
        m.put("join.denied.online", Lang.k("Игрок с этим ником уже находится на сервере.\nЕсли это вы и у вас сменился IP — подождите полминуты и зайдите снова."));
        m.put("join.denied.throttle", Lang.k("Слишком частые подключения с вашего IP. Подождите минуту."));
        m.put("join.denied.ipBlocked", Lang.k("Слишком много неверных попыток входа с вашего IP. Зайдите позже."));
        m.put("admin.unregistered", Lang.k("&aАккаунт %s удалён. Игрок сможет зарегистрироваться заново."));
        m.put("admin.notFound", Lang.k("&cАккаунт %s не найден."));
        m.put("admin.dbError", Lang.k("&cОшибка базы данных, ничего не изменено."));
        m.put("admin.unlocked", Lang.k("&aБлокировка и счётчик неудач для %s сброшены."));
        m.put("admin.sessionsCleared", Lang.k("&aВсе активные IP-сессии сброшены (%s шт.)."));
        m.put("admin.reloaded", Lang.k("&aКонфигурация BastionAuth перезагружена."));
        m.put("admin.reloadFailed", Lang.k("&cНе удалось перезагрузить конфиг: %s"));
        m.put("admin.status", Lang.k("&7Аккаунт &f%s&7: создан %s, посл. вход %s (IP %s), неудач: %s, блокировка: %s"));
        m.put("agreement.title", Lang.k("&6&lПОЛЬЗОВАТЕЛЬСКОЕ СОГЛАШЕНИЕ MAXCORA"));
        m.put("agreement.booksHotbar", Lang.k("&7Тома соглашения — в первых &f%s &7слотах хотбара. Возьмите том в руку и нажмите &fПКМ&7, чтобы читать."));
        m.put("agreement.books", Lang.k("&7Выдано книг: &f%s&7. Откройте и прочитайте — это обязательно до регистрации."));
        m.put("agreement.ready", Lang.k("&e→ &7Согласны? Введите &f/agreement accept &7или нажмите:"));
        m.put("agreement.required", Lang.k("&cСначала примите пользовательское соглашение: &f/agreement accept"));
        m.put("agreement.accepted", Lang.k("&aСоглашение принято. Теперь можно зарегистрироваться или войти."));
        m.put("agreement.already", Lang.k("&7Вы уже приняли действующую редакцию."));
        m.put("agreement.uiFallback", Lang.k("&eНе удалось выдать книги. Текст: &f/agreement read"));
        m.put("agreement.newRevision", Lang.k("&eДействует новая редакция &f%s&e — её нужно принять заново."));
        m.put("admin.links.none", Lang.k("&7У &f%s&7 связок с другими аккаунтами не найдено."));
        m.put("admin.links.header", Lang.k("&6Связки &f%s&6 (%s шт., лучшая оценка за всё время):"));
        m.put("admin.links.line", Lang.k("&8• &f%s &8— &e%s &7(%s) &8сигналы: &7%s &8· замечено %s раз, последний %s"));
        m.put("admin.links.device", Lang.k("&8  устройства: &7%s"));
        // 1.8: the staff verdict on a pair
        m.put("admin.links.verdict", Lang.k("&8  статус: %s &8· кем: &7%s &8· заметка: &7%s"));
        m.put("admin.links.trustedNote", Lang.k("&8Доверенная пара: переводы, дуэли, аукцион, обмен, ЗАГС открыты, просрочка одного не закрывает займы другому; реферальный бонус по-прежнему считает их одним лицом."));
        m.put("admin.links.set", Lang.k("&aСтатус связи &f%s &a↔ &f%s&a: %s"));
        m.put("admin.links.setAll", Lang.k("&aСтатус %s &aпоставлен на все связи &f%s &a(&f%s&a шт.)"));
        m.put("admin.links.setFailed", Lang.k("&cНе удалось записать статус связи."));
        m.put("admin.links.badVerdict", Lang.k("&cНеизвестный статус. Доступны: &fдоверять&c, &fнаблюдать&c, &fмульти&c, &fсброс&c."));
        m.put("admin.links.sameAccount", Lang.k("&cЭто один и тот же аккаунт."));
        m.put("admin.links.queue", Lang.k("&6Связей без решения: &f%s&6. Разобрать: &f/связи очередь"));
        m.put("admin.links.queueEmpty", Lang.k("&7Связей без решения нет."));
        m.put("admin.links.queueLine", Lang.k("&8• &f%s &8↔ &f%s &8— &e%s &7(%s) &8сигналы: &7%s"));
        // 1.8.2: what the server said while the player was on the login screen
        m.put("gate.held", Lang.k("&8┃ &7Пока вы входили, для вас пришло сообщений: &f%s"));
        m.put("gate.heldDropped", Lang.k("&8┃ &7Ещё &f%s &7старых не поместились и не показаны."));
        // 1.6: second factor
        m.put("reminder.code", Lang.k("&e→ &7Код из приложения: &f/2fa <код> &8(осталось %s сек.)"));
        m.put("twofa.needCode", Lang.k("&aПароль верный. &7Теперь код из приложения: &f/2fa <код> &8(или резервный код)"));
        m.put("twofa.notAwaiting", Lang.k("&7Код сейчас не нужен. Сначала &f/login <пароль>&7."));
        m.put("twofa.wrong", Lang.k("&cНеверный код. &7Осталось попыток: &f%s"));
        m.put("twofa.tooMany", Lang.k("&cСлишком много неверных кодов. Подключитесь снова."));
        m.put("twofa.backupUsed", Lang.k("&eИспользован резервный код. &7Осталось: &f%s&7. Новые: &f/2fa коды <код>"));
        m.put("twofa.disabledByConfig", Lang.k("&cДвухфакторная защита на сервере отключена."));
        m.put("twofa.alreadyOn", Lang.k("&7Двухфакторная защита уже включена. Выключить: &f/2fa выключить <код>"));
        m.put("twofa.status.on", Lang.k("&a2FA включена &8(с %s)&7. Резервных кодов: &f%s&7. Выключить: &f/2fa выключить <код>&7, новые коды: &f/2fa коды <код>"));
        m.put("twofa.status.off", Lang.k("&72FA выключена. Включить: &f/2fa включить &7— нужно приложение (Google Authenticator, Aegis, Яндекс Ключ)."));
        m.put("twofa.setup.head", Lang.k("&6▎ Двухфакторная защита — шаг 1 из 2"));
        m.put("twofa.setup.secret", Lang.k("&7Ключ для ручного ввода в приложение: &f%s"));
        m.put("twofa.setup.map", Lang.k("&7В руках — карта с QR-кодом: отсканируйте её приложением прямо с экрана."));
        m.put("twofa.setup.noMap", Lang.k("&7Введите ключ в приложение вручную (карту выдать не удалось)."));
        m.put("twofa.setup.confirm", Lang.k("&e→ &7Затем подтвердите первым кодом: &f/2fa подтвердить <код> &8(15 минут)"));
        m.put("twofa.noPending", Lang.k("&7Сначала &f/2fa включить&7."));
        m.put("twofa.confirmWrong", Lang.k("&cКод не подошёл. Проверьте время на телефоне и повторите."));
        m.put("twofa.enabled", Lang.k("&a✔ Двухфакторная защита включена. &7При входе после пароля будет нужен код: &f/login <пароль> <код>"));
        m.put("twofa.disabled", Lang.k("&eДвухфакторная защита выключена."));
        m.put("twofa.backup.head", Lang.k("&6Резервные коды &7— на случай потери телефона, каждый работает один раз:"));
        m.put("twofa.backup.tail", Lang.k("&7Они же — в книге у вас в инвентаре. Спрячьте её в сундук."));
        m.put("twofa.mapTitle", Lang.k("QR для двухфакторной защиты"));
        m.put("opstrict.on", Lang.k("&a🔒 Строгий вход оператора &lвключён&r&a: после выхода снова нужен пароль, сессия по IP не используется."));
        m.put("opstrict.off", Lang.k("&e🔓 Строгий вход оператора &lвыключен&r&e: зайдёте с того же IP в течение %s мин — пароль не спросят."));
        m.put("opstrict.buttonOn", Lang.k("&8[&a Включить строгий вход &8]"));
        m.put("opstrict.buttonOff", Lang.k("&8[&7 Выключить &8]"));
        m.put("opstrict.hoverOn", Lang.k("&7Каждый вход — только с паролем. Защищает права оператора, если ваш IP общий (мобильный интернет, общежитие)."));
        m.put("opstrict.hoverOff", Lang.k("&7Вход по сессии: тот же IP и не дольше времени жизни сессии."));
        m.put("opstrict.setOn", Lang.k("&aСтрогий вход включён. Ваша текущая сессия по IP удалена."));
        m.put("opstrict.setOff", Lang.k("&eСтрогий вход выключен. С этого IP вас будут пускать без пароля, пока жива сессия."));
        m.put("opstrict.forced", Lang.k("&7Строгий вход для операторов включён на весь сервер (config.json: session.opStrictOptional = false)."));
        m.put("opstrict.notOp", Lang.k("&cЭта настройка только для операторов."));
        m.put("twofa.opReminder", Lang.k("&e⚠ У вас права оператора, а двухфакторной защиты нет: &f/2fa включить"));
        m.put("device.new", Lang.k("&eВход с нового устройства. &7Если это не вы — &f/auth lock &7закроет аккаунт до разбора."));
        m.put("device.newNo2fa", Lang.k("&eВход с нового устройства. &7Пароль — единственная защита аккаунта; включите &f/2fa&7."));
        m.put("login.panicLocked", Lang.k("&cАккаунт закрыт вами (/auth lock). Открыть может только администрация."));
        m.put("lock.done", Lang.k("&cАккаунт закрыт по вашей команде. Обратитесь к администрации, чтобы открыть."));
        m.put("history.head", Lang.k("&6▎ История аккаунта &8(последние события)"));
        m.put("history.empty", Lang.k("&7Пока пусто."));
        m.put("admin.twofaReset", Lang.k("&a2FA у %s снята."));
        m.put("admin.twofaNone", Lang.k("&7У %s не было 2FA."));
        // 1.6: codeword
        m.put("codeword.required", Lang.k("&cДействие защищено кодовым словом. &7Назовите его: &f/слово <слово> &8(откроет на несколько минут)"));
        m.put("codeword.status.off", Lang.k("&7Кодовое слово не задано. Задать: &f/слово установить <слово>&7 — оно будет нужно для смены пароля, отключения 2FA, передачи привата и крупных операций с деньгами."));
        m.put("codeword.status.open", Lang.k("&aКодовое слово названо, защищённые действия открыты ещё &f%s с&a."));
        m.put("codeword.status.locked", Lang.k("&7Кодовое слово задано. Назовите его перед защищённым действием: &f/слово <слово> &8(откроет на %s мин)"));
        m.put("codeword.disabledByConfig", Lang.k("&cКодовые слова на сервере отключены."));
        m.put("codeword.tooShort", Lang.k("&cСлишком коротко: от %s до %s символов."));
        m.put("codeword.tooLong", Lang.k("&cСлишком длинно: от %s до %s символов."));
        m.put("codeword.equalsName", Lang.k("&cКодовое слово не должно совпадать с ником."));
        m.put("codeword.set", Lang.k("&a✔ Кодовое слово задано &8(и названо — защищённые действия открыты на %s мин)&a. &7Снять: &f/слово снять <слово>"));
        m.put("codeword.wrong", Lang.k("&cНеверное кодовое слово."));
        m.put("codeword.unlocked", Lang.k("&aКодовое слово принято. &7Защищённые действия открыты на %s мин."));
        m.put("codeword.removed", Lang.k("&eКодовое слово снято."));
        return m;
    }
}
