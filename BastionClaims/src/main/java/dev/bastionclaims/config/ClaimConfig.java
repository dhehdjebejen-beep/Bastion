package dev.bastionclaims.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
 * {@code config/bastionclaims/config.json}. Missing keys fall back to defaults
 * and are written back; a corrupted file is backed up and replaced with
 * defaults so the server keeps running. Reloadable at runtime via
 * {@code /claims reload} (see {@code loadOrCreate}).
 */
public final class ClaimConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public Progression progression = new Progression();

    /** Operators (permission level >= 2) ignore claim-count and size limits. */
    public boolean opBypassLimits = true;
    /**
     * Operators ignore other players' claim protection everywhere, always,
     * without asking. Off by default since 1.17.2: an operator who wants to
     * walk through a claim says {@code /claim bypass} for the session. With it
     * on, every operator silently passed every chest and door on the server,
     * and testing protection from an op account was impossible.
     */
    public boolean opBypassProtection = false;
    /**
     * Bumped when a default changes meaning. Absent in files written before
     * 1.17.2 (Gson leaves it 0), which is how {@link #loadOrCreate} knows to
     * migrate them.
     */
    public int configVersion = 0;

    public Protection protection = new Protection();
    public WorldEdit worldEdit = new WorldEdit();

    /** Reject claims whose shortest horizontal side is below this (anti-microclaim). */
    public int minSideLength = 5;
    /** How many cuboids one claim may be built from (the first one included). */
    public int maxZonesPerClaim = 8;
    /** A new zone must be at most this far from a zone the claim already has. */
    public int zoneMaxDistance = 256;
    /** Item used as the selection wand. */
    public String wandItem = "minecraft:wooden_axe";
    /**
     * Custom names marking server NPCs that anyone may right-click, wherever
     * they stand. Matched as a substring of the entity's display name.
     */
    public List<String> serviceEntityNames = new ArrayList<>();
    /**
     * Blocks that open a screen but have no inventory of their own. They are
     * treated as containers, so the "guests: containers" flag covers them.
     */
    public List<String> workstationBlocks = new ArrayList<>();
    /**
     * What plain {@code /claim create} and the main "create" button produce.
     * True = a full-height 2D claim (bedrock to sky, costed by footprint area),
     * which is what people almost always mean by "I claimed this land": a 3D box
     * leaves your own cellar and roof unprotected. {@code /claim create3d} still
     * makes an exact cuboid for anyone who wants one.
     */
    public boolean fullHeightByDefault = true;
    /** Wands handed out back-to-back before the cooldown starts applying. */
    public int wandFreeBurst = 3;
    /** Seconds between wands once the burst is used up. */
    public int wandCooldownSeconds = 15;
    /** Idle time after which the burst allowance is restored. */
    public int wandBurstResetMinutes = 5;

    public Map<String, String> messages = new LinkedHashMap<>();

    // ------------------------------------------------------------------ tiers

    /** One progression step: unlocked at {@code minHours} of play-time. */
    public static final class Tier {
        public int minHours;
        public int maxClaims;
        public long maxBlocksPerClaim;

        public Tier() {}

        public Tier(int minHours, int maxClaims, long maxBlocksPerClaim) {
            this.minHours = minHours;
            this.maxClaims = maxClaims;
            this.maxBlocksPerClaim = maxBlocksPerClaim;
        }
    }

    public static final class Progression {
        public List<Tier> tiers = new ArrayList<>();
    }

    /** Operator-only WorldEdit-style block editing with the same wand/selection. */
    public static final class WorldEdit {
        public boolean enabled = true;
        /** Hard cap on blocks changed by one /we operation (protects the server). */
        public int maxBlocks = 100_000;
        /** Blocks written per server tick — the operation is spread over ticks. */
        public int blocksPerTick = 20_000;
        /** How many operations back {@code /we undo} can go. */
        public int undoDepth = 10;
        /** Refuse to edit through another player's claim unless /claims bypass is on. */
        public boolean respectClaims = true;
        /**
         * Builders (MaxCore build mode, not operators) may only edit inside a
         * claim they own — the whole selection, corner to corner. Outside it
         * the world is not theirs to fill or clear.
         */
        public boolean buildersOnlyInsideOwnClaim = true;
        /** Hard cap on blocks per /we operation for builders (operators use maxBlocks). */
        public int builderMaxBlocks = 20_000;
    }

    public static final class Protection {
        /** Non-members cannot break/place blocks inside a claim. */
        public boolean blockProtection = true;
        /** Non-members cannot open containers, doors, buttons, use items on blocks. */
        public boolean interactProtection = true;
        /** Explosions do not destroy blocks inside claims. */
        public boolean explosionProtection = true;
        /** PvP inside claims is off by default (per-claim flag can re-enable it). */
        public boolean pvpProtection = true;
        /** Mob griefing (e.g. creeper explosions) inside claims is off by default. */
        public boolean mobGriefProtection = true;
        /** Hostile mobs cannot damage players inside claims (per-claim flag re-enables). */
        public boolean mobDamageProtection = true;
        /** Liquids cannot flow into a claim from blocks outside its border, and
         *  non-members cannot spill/scoop liquids inside it. */
        public boolean fluidProtection = true;
        /** Pistons outside a claim cannot move or break blocks inside it. */
        public boolean pistonProtection = true;
        /** Hoppers/hopper-minecarts outside a claim cannot pull from or push into
         *  its containers. */
        public boolean hopperProtection = true;
        /** Fire does not tick or spread inside claims (per-claim flag re-enables). */
        public boolean fireProtection = true;
        /** Saplings outside a claim refuse to grow when the tree could reach into one. */
        public boolean treeProtection = true;
        /** Horizontal reach of the biggest trees, in blocks. */
        public int treeGuardRadius = 6;
        /** How far up a tree may reach from the sapling. */
        public int treeGuardHeight = 32;
        /**
         * Non-members cannot damage the claim's entities — armour stands, item
         * frames, paintings, animals, minecarts — by any means. Melee was already
         * covered by the attack event; this is what stops the bow, the splash
         * potion and the TNT charge outside the border.
         */
        public boolean entityProtection = true;
    }

    /** How long a WorldEdit session (selection, clipboard, undo) outlives a disconnect. */
    public int worldEditSessionKeepMinutes = 30;

    // ------------------------------------------------------------------ lookups

    /**
     * The tier applying to a player with {@code playHours} of play-time: the
     * highest tier whose {@code minHours} the player has reached. Never null once
     * defaults are filled.
     */
    public Tier tierFor(int playHours) {
        Tier best = null;
        for (Tier t : progression.tiers) {
            if (playHours >= t.minHours && (best == null || t.minHours >= best.minHours)) {
                best = t;
            }
        }
        return best != null ? best : new Tier(0, 1, 50_000);
    }

    public int maxClaimsFor(int playHours) {
        return tierFor(playHours).maxClaims;
    }

    public long maxBlocksFor(int playHours) {
        return tierFor(playHours).maxBlocksPerClaim;
    }

    // ------------------------------------------------------------------ load/save

    public static ClaimConfig loadOrCreate(Path file, Consumer<String> warn) throws IOException {
        ClaimConfig cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClaimConfig.class);
            } catch (RuntimeException e) {
                Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                warn.accept("config.json is not valid JSON; backed it up to " + backup.getFileName()
                        + " and applied defaults. Parse error: " + e.getMessage());
            }
        }
        boolean existed = cfg != null;
        if (cfg == null) cfg = new ClaimConfig();
        if (cfg.configVersion < 2) {
            if (existed && cfg.opBypassProtection) {
                warn.accept("config.json: opBypassProtection switched to false (1.17.2) — operators now"
                        + " need /claim bypass to ignore other players' claims. Set it back to true"
                        + " and /claims reload to restore the old behaviour.");
            }
            cfg.opBypassProtection = false;
        }
        cfg.configVersion = 2;
        cfg.fillDefaults();
        cfg.clamp();
        Files.writeString(file, GSON.toJson(cfg) + System.lineSeparator(), StandardCharsets.UTF_8);
        return cfg;
    }

    private void fillDefaults() {
        if (progression == null) progression = new Progression();
        if (progression.tiers == null) progression.tiers = new ArrayList<>();
        if (progression.tiers.isEmpty()) {
            progression.tiers.add(new Tier(0, 1, 50_000));
            progression.tiers.add(new Tier(10, 3, 100_000));
            progression.tiers.add(new Tier(20, 6, 120_000));
        }
        if (protection == null) protection = new Protection();
        if (worldEdit == null) worldEdit = new WorldEdit();
        if (wandItem == null || wandItem.isBlank()) wandItem = "minecraft:wooden_axe";
        if (serviceEntityNames == null) serviceEntityNames = new ArrayList<>();
        if (serviceEntityNames.isEmpty()) {
            serviceEntityNames.addAll(List.of("Паспортист", "Товарищ Майор"));
        }
        if (workstationBlocks == null) workstationBlocks = new ArrayList<>();
        if (workstationBlocks.isEmpty()) {
            workstationBlocks.addAll(List.of(
                    "crafting_table", "enchanting_table", "anvil", "chipped_anvil", "damaged_anvil",
                    "loom", "smithing_table", "stonecutter", "grindstone", "cartography_table",
                    "fletching_table", "composter", "cauldron", "water_cauldron", "lava_cauldron",
                    "powder_snow_cauldron", "respawn_anchor", "bell"));
        }

        Map<String, String> merged = defaultMessages();
        if (messages != null) merged.putAll(messages);
        messages = merged;
        migrateMessages();
    }

    /**
     * Replaces message templates that shipped broken. Only exact matches of the
     * old default are touched, so a phrasing the admin edited is left alone.
     */
    private void migrateMessages() {
        // The old template had a second %s for a claim name the command never
        // took, so it printed "/claim delete  confirm" — a command that fails.
        String oldDelete = "&eУдалить приват &f%s&e? Повтори: &f/claim delete %s confirm";
        if (oldDelete.equals(messages.get("delete.confirm"))) {
            messages.put("delete.confirm", defaultMessages().get("delete.confirm"));
        }
        // "Доверенные" became "пользователи" — refresh the wording unless the
        // admin has written their own.
        Map<String, String> defaults = defaultMessages();
        replaceIfUntouched("info.guest", "&7Гостям: сундуки &f%s&7, двери &f%s", defaults);
        replaceIfUntouched("info.members", "&7Доверенные: &f%s", defaults);
        replaceIfUntouched("trust.notMember", "&cИгрок &f%s&c не в списке доверенных.", defaults);
    }

    private void replaceIfUntouched(String key, String oldDefault, Map<String, String> defaults) {
        if (oldDefault.equals(messages.get(key))) messages.put(key, defaults.get(key));
    }

    private void clamp() {
        minSideLength = Math.max(1, Math.min(64, minSideLength));
        maxZonesPerClaim = Math.max(1, Math.min(64, maxZonesPerClaim));
        zoneMaxDistance = Math.max(0, Math.min(30_000, zoneMaxDistance));
        if (worldEdit == null) worldEdit = new WorldEdit();
        worldEdit.maxBlocks = Math.max(1, Math.min(5_000_000, worldEdit.maxBlocks));
        worldEdit.blocksPerTick = Math.max(64, Math.min(1_000_000, worldEdit.blocksPerTick));
        worldEdit.undoDepth = Math.max(1, Math.min(50, worldEdit.undoDepth));
        // 0 means the key was absent from an older config file.
        if (worldEditSessionKeepMinutes <= 0) worldEditSessionKeepMinutes = 30;
        worldEditSessionKeepMinutes = Math.max(1, Math.min(1440, worldEditSessionKeepMinutes));
        if (wandFreeBurst <= 0) wandFreeBurst = 3;
        wandFreeBurst = Math.max(1, Math.min(64, wandFreeBurst));
        if (wandCooldownSeconds <= 0) wandCooldownSeconds = 15;
        wandCooldownSeconds = Math.max(1, Math.min(3600, wandCooldownSeconds));
        if (wandBurstResetMinutes <= 0) wandBurstResetMinutes = 5;
        wandBurstResetMinutes = Math.max(1, Math.min(1440, wandBurstResetMinutes));
        for (Tier t : progression.tiers) {
            t.minHours = Math.max(0, t.minHours);
            t.maxClaims = Math.max(0, Math.min(1000, t.maxClaims));
            t.maxBlocksPerClaim = Math.max(1, t.maxBlocksPerClaim);
        }
    }

    /** Message template for the key with {@code String.format} args applied. */
    public String message(String key, Object... args) {
        String template = messages != null ? messages.getOrDefault(key, key) : key;
        if (args == null || args.length == 0) return template;
        try {
            return String.format(template, args);
        } catch (RuntimeException e) {
            return template;
        }
    }

    private static Map<String, String> defaultMessages() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("wand.given", "&aВыдан приват-топор 🪓 &7ЛКМ — точка 1, ПКМ — точка 2.");
        m.put("wand.pos1", "&aТочка 1: &f%s %s %s &8(осталось выбрать точку 2 — ПКМ)");
        m.put("wand.pos2", "&aТочка 2: &f%s %s %s &8(создать: &f/claim create <название>&8)");
        m.put("wand.selInfo", "&7Выделение: &f%s×%s×%s &8= &f%s &7блоков");
        m.put("sel.none", "&cСначала выдели область топором: &f/claim wand");
        m.put("sel.diffWorld", "&cОбе точки должны быть в одном мире.");
        m.put("create.success", "&aПриват &f%s&a создан! &7Объём: &f%s &7блоков.");
        m.put("create.success3d", "&aПриват &f%s&a создан &8(3D)&a! &7Объём: &f%s &7блоков.");
        m.put("create.success2d", "&aПриват &f%s&a создан &8(во всю высоту)&a! &7Площадь: &f%s &7блоков.");
        m.put("create.noName", "&cУкажи название: &f/claim create <название>");
        m.put("create.nameTaken", "&cУ тебя уже есть приват с таким названием.");
        m.put("create.tooSmall", "&cСлишком маленькая область: минимум %s блоков по стороне.");
        m.put("create.overlap", "&cОбласть пересекается с чужим приватом &f%s&c (владелец &f%s&c).");
        m.put("create.limitClaims", "&cЛимит приватов для тебя: &f%s&c. Часов сыграно: &f%s&c.");
        m.put("create.limitBlocks", "&cСлишком большой приват: &f%s&c > лимита &f%s&c блоков (твой тир).");
        m.put("limit.progress", "&7Наиграй больше часов, чтобы поднять лимит: &f10ч&7 → 3 привата, &f20ч&7 → 6.");
        m.put("delete.success", "&aПриват &f%s&a удалён 🗑");
        m.put("delete.notFound", "&cПривата &f%s&c нет.");
        m.put("delete.confirm", "&eУдалить приват &f%s&e? Подтверди: &f/claim delete confirm &8(60 секунд)");
        m.put("codeword.required", "&cПередача и удаление привата защищены кодовым словом. &7Назовите его: &f/слово <слово>");
        m.put("rename.success", "&aПриват переименован: &f%s &7→ &f%s");
        m.put("zone.added", "&aЗона добавлена к привату &f%s&a! Теперь зон: &f%s&a, всего блоков: &f%s");
        m.put("zone.removed", "&7Зона &f#%s&7 убрана из привата &f%s&7.");
        m.put("zone.limit", "&cУ привата уже максимум зон: &f%s&c.");
        m.put("zone.tooFar", "&cЗона слишком далеко от привата: &f%s&c блоков (максимум &f%s&c). "
                + "&7Приват должен оставаться единым куском, а не разбросом по карте.");
        m.put("zone.overlapSelf", "&cЭта зона налезает на уже существующую зону этого же привата.");
        m.put("zone.notFound", "&cЗоны &f#%s&c нет. Список: &f/claim zones");
        m.put("zone.primary", "&cЗону &f#1&c убрать нельзя — это основа привата. "
                + "&7Удалить целиком: &f/claim delete");
        m.put("presence.enterOwn", "&a🛡 Ваш приват &f%s");
        m.put("presence.enterMember", "&a🛡 Приват &f%s&a — у вас есть доступ");
        m.put("presence.enterOther", "&c🛡 Приват &f%s&c — владелец &f%s");
        m.put("presence.leave", "&7Вы покинули приват &f%s");
        m.put("presence.arrested", "&8[&4⛔ ФССП&8] &cПриват &f%s &cарестован: &7%s&c. Вход закрыт до снятия ареста.");
        m.put("transfer.arrested", "&cАрестованный приват передать нельзя — сначала снимите арест (погасите долг).");
        m.put("borders.on", "&aГраницы ваших приватов показаны. &8Выключить: &f/claim borders");
        m.put("borders.off", "&7Границы скрыты.");
        m.put("zone.header", "&6Зоны привата &f%s &8(&f%s&8):");
        m.put("zone.entry", "&8• &f#%s &7— %sx%sx%s &8(%s %s %s) &7%s блоков");
        m.put("transfer.confirm", "&eПередать приват &f%s&e игроку &f%s&e? "
                + "&cТы полностью потеряешь к нему доступ.&e Подтверди: &f/claim transfer confirm &8(60 секунд)");
        m.put("transfer.done", "&aПриват &f%s&a передан игроку &f%s&a. Доступа у тебя больше нет.");
        m.put("transfer.received", "&a🛡 Тебе передали приват &f%s&a — от игрока &f%s&a. Он теперь твой.");
        m.put("transfer.renamed", "&7У нового владельца было такое название, приват стал &f%s&7.");
        m.put("transfer.self", "&cЭто и так твой приват.");
        m.put("transfer.offline", "&cНовый владелец должен быть в сети — передавать приват вслепую нельзя.");
        m.put("trust.added", "&aИгрок &f%s&a добавлен в приват &f%s&a.");
        m.put("trust.removed", "&7Игрок &f%s&7 убран из привата &f%s&7.");
        m.put("trust.notMember", "&cИгрок &f%s&c не является пользователем привата.");
        m.put("trust.self", "&cТы и так владелец этого привата.");
        m.put("flag.set", "&aФлаг &f%s&a привата &f%s&a: &f%s");
        m.put("flag.unknown", "&cНеизвестный флаг. Доступно: &fpvp&7, &fmobs&7, &fmobdmg&7, "
                + "&fexplosions&7, &ffluids&7, &fpistons&7, &fhoppers&7, &fcontainers&7, &fdoors&7, &ffire");
        m.put("info.header", "&6▎ Приват &f%s &8(владелец &f%s&8)");
        m.put("info.bounds", "&7Границы: &f%s %s %s &7→ &f%s %s %s &8(%s)");
        m.put("info.volume", "&7Объём: &f%s &7блоков &8• размер &f%sx%sx%s");
        m.put("info.members", "&7Пользователи: &f%s");
        m.put("info.flags", "&7Флаги: PvP &f%s&7, мобгриф &f%s&7, урон-мобов &f%s&7, взрывы &f%s");
        m.put("info.external", "&7Извне: жидкости &f%s&7, поршни &f%s&7, воронки &f%s");
        m.put("info.guest", "&7Гостям: сундуки &f%s&7, двери &f%s&7, существа &f%s");
        m.put("list.header", "&6Твои приваты &8(&f%s&8/&f%s&8):");
        m.put("list.entry", "&8• &f%s &7— %s блоков &8(%s %s %s)");
        m.put("list.empty", "&7У тебя нет приватов. Выдели топором и создай: &f/claim wand");
        m.put("protect.denied", "&cЭто чужой приват (&f%s&c). Строить нельзя.");
        m.put("protect.deniedInteract", "&cЭто чужой приват (&f%s&c). Взаимодействовать нельзя.");
        m.put("protect.deniedEntity", "&cСущества в чужом привате (&f%s&c) трогать нельзя. "
                + "&7Владелец может открыть доступ: &f/claim flag entities on");
        m.put("protect.deniedFluid", "&cЭто чужой приват (&f%s&c). Разливать жидкости нельзя.");
        m.put("protect.pvp", "&cВ этом привате PvP отключено.");
        m.put("notPlayer", "&cКоманда только для игроков.");
        m.put("noPermission", "&cНедостаточно прав.");
        m.put("admin.reloaded", "&aКонфигурация BastionClaims перезагружена.");
        m.put("admin.reloadFailed", "&cНе удалось перезагрузить конфиг: %s");
        m.put("admin.deleted", "&aПриват &f%s&a игрока &f%s&a удалён.");
        m.put("admin.listHeader", "&6Приваты игрока &f%s &8(&f%s&8):");
        m.put("admin.bypassOn", "&eРежим обхода защиты &aВКЛ&e — ты игнорируешь чужие приваты до выхода с сервера.");
        m.put("admin.bypassOff", "&aРежим обхода защиты &fВЫКЛ&a — чужие приваты действуют на тебя как на обычного игрока.");
        return m;
    }
}
