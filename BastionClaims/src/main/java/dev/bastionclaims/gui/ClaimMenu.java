package dev.bastionclaims.gui;

import com.mojang.authlib.GameProfile;
import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.command.ClaimCommands;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.core.ClaimManager;
import dev.bastionclaims.core.ClaimService;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.core.Selection;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.AnvilGui;
import dev.bastionclaims.util.ChestGui;
import dev.bastionclaims.util.Fmt;
import dev.bastionclaims.util.Lang;
import dev.bastionclaims.util.Teleports;
import dev.bastionclaims.wand.ClaimWand;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Uuids;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Chest-GUI front-end for claims: overview, per-claim management, trust picker, admin browser. */
public final class ClaimMenu {

    private static ClaimConfig cfg() {
        return BastionClaims.config();
    }

    // ------------------------------------------------------------------ overview

    /** Claim slots on the overview screen: rows 1–4. */
    private static final int PER_PAGE = 36;

    public static void open(ServerPlayerEntity p) {
        open(p, 0);
    }

    public static void open(ServerPlayerEntity p, int page) {
        String owner = p.getGameProfile().name();
        List<Claim> claims = ClaimManager.claimsOf(p.getUuid());
        boolean unlimited = ProtectionService.isOp(p) && cfg().opBypassLimits;
        int hours = ProtectionService.playHours(p);
        int maxClaims = unlimited ? -1 : cfg().maxClaimsFor(hours);
        long maxBlocks = unlimited ? -1 : cfg().maxBlocksFor(hours);

        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&2🛡 Приваты &8» &7") + owner), 6);

        List<String> lore = new ArrayList<>();
        lore.add(Lang.t("&7Наиграно: &f") + hours + Lang.t("ч"));
        lore.add(Lang.t("&7Приватов: &f") + claims.size() + (maxClaims < 0 ? Lang.t(" &8(без лимита)") : "&8/&f" + maxClaims));
        lore.add(maxBlocks < 0 ? Lang.t("&7Лимит блоков: &fбез лимита") : Lang.t("&7Лимит блоков: &f") + maxBlocks);
        gui.set(4, item(Items.WRITABLE_BOOK, Lang.t("&aТвои лимиты"), lore.toArray(new String[0])), null);

        gui.set(0, item(Items.WOODEN_AXE, Lang.t("&b🪓 Получить топор"),
                        Lang.t("&7Выделить область: ЛКМ + ПКМ"), Lang.t("&8➜ клик")),
                pl -> {
                    ClaimWand.Give result = ClaimWand.give(pl, cfg());
                    pl.sendMessage(Fmt.text(result.given()
                            ? cfg().message("wand.given") : result.message()), false);
                    pl.closeHandledScreen();
                });

        Selection sel = Selection.of(p.getUuid());
        if (sel.complete()) {
            long area = (long) (Math.abs(sel.x2 - sel.x1) + 1) * (Math.abs(sel.z2 - sel.z1) + 1);
            long vol = area * (Math.abs(sel.y2 - sel.y1) + 1);
            boolean twoDefault = cfg().fullHeightByDefault;
            // The full-height button sits in the primary slot when it is the
            // default, so the recommended shape is the one under the cursor.
            gui.set(twoDefault ? 7 : 8, item(Items.GRASS_BLOCK,
                            Lang.t("&aСоздать приват &2(во всю высоту)") + (twoDefault ? " &8★" : ""),
                            Lang.t("&7Площадь основания: &f") + area + Lang.t(" &7блоков"),
                            Lang.t("&7От бедрока до неба — погреб и крыша"),
                            Lang.t("&7тоже под защитой"),
                            Lang.t("&8➜ клик — ввести название")),
                    pl -> AnvilGui.open(pl, Lang.t("&2Название привата (во всю высоту)"), "",
                            name -> finishCreate(pl, name, true)));
            gui.set(twoDefault ? 8 : 7, item(Items.EMERALD_BLOCK,
                            Lang.t("&aСоздать приват &2(куб 3D)") + (twoDefault ? "" : " &8★"),
                            Lang.t("&7Объём выделения: &f") + vol + Lang.t(" &7блоков"),
                            Lang.t("&7Ровно по двум точкам — выше и ниже"),
                            Lang.t("&cне защищено"),
                            Lang.t("&8➜ клик — ввести название")),
                    pl -> AnvilGui.open(pl, Lang.t("&2Название привата (куб 3D)"), "",
                            name -> finishCreate(pl, name, false)));
        } else {
            gui.set(8, item(Items.GRAY_DYE, Lang.t("&7Область не выделена"),
                    Lang.t("&8Возьми топор и отметь 2 угла")), null);
        }

        int pages = Math.max(1, (claims.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.max(0, Math.min(page, pages - 1));
        int slot = 9;
        for (int i = shown * PER_PAGE; i < claims.size() && slot < 45; i++) {
            Claim c = claims.get(i);
            gui.set(slot++, claimIcon(c), pl -> openClaim(pl, c.id));
        }
        if (claims.isEmpty()) {
            gui.set(22, item(Items.BOOK, Lang.t("&7У тебя пока нет приватов"),
                    Lang.t("&8Получи топор, отметь углы,"), Lang.t("&8затем нажми «Создать»")), null);
        }
        if (pages > 1) {
            int prev = shown - 1, next = shown + 1;
            if (shown > 0) {
                gui.set(46, item(Items.ARROW, Lang.t("&7← Пред. страница"),
                        "&8" + (shown + 1) + Lang.t(" из ") + pages, Lang.t("&8▸ нажми")), pl -> open(pl, prev));
            }
            if (next < pages) {
                gui.set(52, item(Items.ARROW, Lang.t("&7След. страница →"),
                        "&8" + (shown + 1) + Lang.t(" из ") + pages, Lang.t("&8▸ нажми")), pl -> open(pl, next));
            }
        }

        gui.set(49, item(Items.KNOWLEDGE_BOOK, Lang.t("&e❓ Как пользоваться приватами"),
                Lang.t("&7Гайд: создание, флаги, лимиты, команды"), Lang.t("&8➜ открыть")), ClaimMenu::openHelp);

        if (maxCoreLoaded()) {
            gui.set(45, item(Items.COMPASS, Lang.t("&9◀ Приложение МАХ"),
                    Lang.t("&7Вернуться в главное меню сервера"), Lang.t("&8▸ нажми")), ClaimMenu::backToApp);
        }

        if (ProtectionService.isOp(p)) {
            gui.set(53, item(Items.ENDER_EYE, Lang.t("&c⚙ Все приваты сервера"),
                    Lang.t("&7Управление любым приватом (админ)"), Lang.t("&8➜ клик")), pl -> openAll(pl, 0));
            gui.set(51, item(Items.GLOBE_BANNER_PATTERN, Lang.t("&b🌐 Язык: &f") + Lang.displayName(Lang.code()),
                    Lang.t("&7Для всех сообщений и меню приватов"), Lang.t("&7на всём сервере."),
                    Lang.t("&8➜ клик — следующий язык")), pl -> {
                        try {
                            BastionClaims.setLanguage(Lang.next());
                        } catch (Exception e) {
                            BastionClaims.LOGGER.warn("Could not save the language to config.json", e);
                        }
                        open(pl);
                    });
        }

        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ help / guide

    public static void openHelp(ServerPlayerEntity p) {
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&2🛡 Приваты &8» &7Как пользоваться")), 6);

        gui.set(4, item(Items.WRITABLE_BOOK, Lang.t("&aГайд по приватам"),
                Lang.t("&7Приват — это защищённая зона."),
                Lang.t("&7Внутри строить и открывать сундуки"),
                Lang.t("&7можешь только ты и доверенные.")), null);

        gui.set(19, item(Items.WOODEN_AXE, Lang.t("&b1. Как создать приват"),
                Lang.t("&7• Возьми топор: &f/claim wand&7 (или кнопка)"),
                Lang.t("&7• &fЛКМ&7 по блоку — 1-й угол"),
                Lang.t("&7• &fПКМ&7 по блоку — 2-й угол"),
                Lang.t("&7• Нажми «Создать» в меню или &f/claim create <имя>")), null);

        gui.set(20, item(Items.GRASS_BLOCK, Lang.t("&b2. Форма привата"),
                Lang.t("&aВо всю высоту&7 — от бедрока до неба,"),
                Lang.t("&7лимит по площади. Это то, что нужно почти всегда:"),
                Lang.t("&7погреб и крыша защищены тоже."),
                Lang.t("&8   &f/claim create2d <имя>"),
                Lang.t("&aКуб 3D&7 — ровно по двум точкам, лимит по объёму."),
                Lang.t("&8   &f/claim create3d <имя>"),
                Lang.t("&8Обычный &f/claim create&8 делает ")
                        + (cfg().fullHeightByDefault ? Lang.t("во всю высоту.") : Lang.t("куб 3D."))), null);

        List<String> limits = new ArrayList<>();
        limits.add(Lang.t("&7Лимиты растут с наигранным временем:"));
        for (ClaimConfig.Tier t : cfg().progression.tiers) {
            limits.add("&f" + t.minHours + Lang.t("ч+ &7→ &f") + t.maxClaims + Lang.t(" прив. &7по &f") + t.maxBlocksPerClaim + Lang.t(" &7блоков"));
        }
        limits.add(Lang.t("&8У операторов лимитов нет."));
        gui.set(21, item(Items.CLOCK, Lang.t("&b3. Лимиты и часы"), limits.toArray(new String[0])), null);

        gui.set(22, item(Items.REDSTONE, Lang.t("&b4. Флаги привата"),
                Lang.t("&fpvp&7 — можно ли драться внутри"),
                Lang.t("&fmobs&7 — грифинг мобами (криперы)"),
                Lang.t("&fmobdmg&7 — урон игроку от мобов"),
                Lang.t("&fexplosions&7 — взрывы ТНТ ломают блоки"),
                Lang.t("&ffluids&7 — жидкости затекают извне"),
                Lang.t("&fpistons&7 — поршни извне двигают блоки"),
                Lang.t("&fhoppers&7 — воронки извне лезут в сундуки"),
                Lang.t("&fcontainers&8/&fdoors&7 — доступ гостям"),
                Lang.t("&8Всё ВЫКЛ = приват защищён. Меню или /claim flag")), null);

        gui.set(23, head(p.getGameProfile().name(), Lang.t("&b5. Пользователи привата"),
                Lang.t("&7Дай друзьям строить в привате:"),
                Lang.t("&f/claim add <ник>&7 (стоя внутри)"),
                Lang.t("&7или кнопка-голова в меню привата."),
                Lang.t("&7Убрать: &f/claim remove <ник>"),
                Lang.t("&8Пользователь строит, но НЕ управляет приватом:"),
                Lang.t("&8удалить, переименовать и передать может только владелец.")), null);

        gui.set(24, item(Items.ENDER_PEARL, Lang.t("&b6. Управление"),
                Lang.t("&7Открой &f/claim&7 → выбери приват:"),
                Lang.t("&7телепорт, флаги, доверенные, удаление."),
                Lang.t("&8Инфо о привате под ногами: &f/claim info")), null);

        gui.set(31, item(Items.PAPER, Lang.t("&eВсе команды приватов"),
                Lang.t("&f/claim &7— меню"),
                Lang.t("&f/claim wand &7— топор для выделения"),
                Lang.t("&f/claim create <имя> &8/ &fcreate2d <имя>"),
                Lang.t("&f/claim create2d &8· &fcreate3d &8— форма привата"),
                Lang.t("&f/claim list &8· &finfo &8· &frename <имя>"),
                Lang.t("&f/claim add/remove <ник> &7— пользователи"),
                "&f/claim addzone &8· &fzones &8· &fdelzone <n>",
                Lang.t("&f/claim borders &7— показать границы частицами"),
                Lang.t("&f/claim flag <флаг> on|off"),
                Lang.t("&f/claim transfer <ник> &7— отдать приват насовсем"),
                Lang.t("&f/claim delete &7(с подтверждением)")), null);

        if (ProtectionService.isOp(p)) {
            gui.set(33, item(Items.DIAMOND_PICKAXE, Lang.t("&c⚙ WorldEdit &8(только операторы)"),
                    Lang.t("&7Тот же топор, но для правки мира:"),
                    Lang.t("&f/we set <блок> &8· &freplace <из> на <в>"),
                    "&f/we copy &8· &fpaste &8· &frotate &8· &fflip",
                    Lang.t("&f/we stack <раз> &8· &fmove <блоков>"),
                    "&f/we undo &8· &fredo",
                    Lang.t("&8Полный список: &f/we")), null);
            gui.set(29, item(Items.COMMAND_BLOCK, Lang.t("&c⚙ Чужие приваты &8(только операторы)"),
                    Lang.t("&f/claims here &7— управление приватом под ногами"),
                    Lang.t("&f/claims manage <ник> <название>"),
                    Lang.t("&f/claims flag <ник> <флаг> on|off <название>"),
                    Lang.t("&f/claims delete <ник> <название>"),
                    Lang.t("&7Или: меню → «Все приваты сервера» → клик")), null);
        }

        gui.set(49, item(Items.ARROW, Lang.t("&7← Назад")), ClaimMenu::open);
        gui.fill(filler());
        gui.open(p);
    }

    private static void finishCreate(ServerPlayerEntity p, String name, boolean fullHeight) {
        ClaimService.Result r = ClaimService.tryCreate(p, name, fullHeight);
        p.sendMessage(Fmt.text(r.message()), false);
        open(p);
    }

    // ------------------------------------------------------------------ per-claim

    public static void openClaim(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        boolean foreign = !c.isOwner(p.getUuid());
        ChestGui gui = new ChestGui(Fmt.text((foreign ? Lang.t("&c⚙ Чужой приват &8» &7") : Lang.t("&2🛡 Приват &8» &7")) + c.name), 4);

        gui.set(4, claimIcon(c), null);
        if (foreign) {
            // An operator managing someone else's land: every button below acts
            // on the stranger's claim, so the screen says so in plain words.
            gui.set(0, head(c.owner, Lang.t("&c⚠ Владелец: &f") + c.owner,
                    Lang.t("&7Ты управляешь чужим приватом"),
                    Lang.t("&7правами оператора: флаги, пользователи,"),
                    Lang.t("&7передача и удаление действуют на него.")), null);
        }

        gui.set(9, item(Items.ENDER_PEARL, Lang.t("&aТелепорт к привату"),
                        Lang.t("&7Безопасная точка внутри"), Lang.t("&8➜ клик")),
                pl -> {
                    pl.closeHandledScreen();
                    ServerWorld w = resolve(pl, c.dim);
                    if (w == null) return;
                    if (Teleports.toClaimSafely(pl, w, c)) {
                        pl.sendMessage(Fmt.text(Lang.t("&aТелепорт к привату &f") + c.name + " 🛡"), false);
                    } else {
                        pl.sendMessage(Fmt.text(Lang.t("&cВнутри привата негде встать — всё заполнено. "
                                + "&7Освободи место и повтори.")), false);
                    }
                });

        // Row 2 — behaviour flags (ВКЛ = разрешено). Default is "protected" (ВЫКЛ).
        gui.set(10, flagIcon(p, "fire", c.allowFireSpread ? Items.CAMPFIRE : Items.FLINT_AND_STEEL, Lang.t("Огонь"), c.allowFireSpread),
                pl -> { if (flagLocked(pl, "fire")) return; ClaimManager.setFireSpread(c, !c.allowFireSpread); openClaim(pl, claimId); });
        gui.set(11, flagIcon(p, "pvp", c.allowPvp ? Items.DIAMOND_SWORD : Items.SHIELD, "PvP", c.allowPvp),
                pl -> { if (flagLocked(pl, "pvp")) return; ClaimManager.setPvp(c, !c.allowPvp); openClaim(pl, claimId); });
        gui.set(12, flagIcon(p, "mobgrief", c.allowMobGriefing ? Items.TNT : Items.GRASS_BLOCK, Lang.t("Мобгриф"), c.allowMobGriefing),
                pl -> { if (flagLocked(pl, "mobgrief")) return; ClaimManager.setMobGriefing(c, !c.allowMobGriefing); openClaim(pl, claimId); });
        gui.set(13, flagIcon(p, "mobdmg", c.allowMobDamage ? Items.ZOMBIE_HEAD : Items.GOLDEN_APPLE, Lang.t("Урон от мобов"), c.allowMobDamage),
                pl -> { if (flagLocked(pl, "mobdmg")) return; ClaimManager.setMobDamage(c, !c.allowMobDamage); openClaim(pl, claimId); });
        gui.set(14, flagIcon(p, "explosions", Items.TNT_MINECART, Lang.t("Взрывы (ТНТ)"), c.allowExplosions),
                pl -> { if (flagLocked(pl, "explosions")) return; ClaimManager.setExplosions(c, !c.allowExplosions); openClaim(pl, claimId); });
        gui.set(15, flagIcon(p, "fluids", Items.WATER_BUCKET, Lang.t("Затекание жидкостей"), c.allowFluidFlow),
                pl -> { if (flagLocked(pl, "fluids")) return; ClaimManager.setFluidFlow(c, !c.allowFluidFlow); openClaim(pl, claimId); });
        gui.set(16, flagIcon(p, "pistons", Items.PISTON, Lang.t("Поршни извне"), c.allowPistons),
                pl -> { if (flagLocked(pl, "pistons")) return; ClaimManager.setPistons(c, !c.allowPistons); openClaim(pl, claimId); });
        gui.set(17, flagIcon(p, "hoppers", Items.HOPPER, Lang.t("Воронки извне"), c.allowHoppers),
                pl -> { if (flagLocked(pl, "hoppers")) return; ClaimManager.setHoppers(c, !c.allowHoppers); openClaim(pl, claimId); });

        // Row 3 — guest access + trusted players.
        gui.set(20, flagIcon(p, "containers", Items.CHEST, Lang.t("Гости: сундуки и станки"), c.guestContainers),
                pl -> { if (flagLocked(pl, "containers")) return; ClaimManager.setGuestContainers(c, !c.guestContainers); openClaim(pl, claimId); });
        gui.set(21, flagIcon(p, "doors", Items.OAK_DOOR, Lang.t("Гости: двери/кнопки"), c.guestDoors),
                pl -> { if (flagLocked(pl, "doors")) return; ClaimManager.setGuestDoors(c, !c.guestDoors); openClaim(pl, claimId); });
        gui.set(22, flagIcon(p, "entities", Items.LEAD, Lang.t("Гости: существа"), c.guestEntities),
                pl -> { if (flagLocked(pl, "entities")) return; ClaimManager.setGuestEntities(c, !c.guestEntities); openClaim(pl, claimId); });
        gui.set(23, head(p.getGameProfile().name(), Lang.t("&fПользователи привата"),
                        Lang.t("&7Сейчас: &f") + (c.members.isEmpty() ? Lang.t("никого") : c.members.size()),
                        Lang.t("&7Могут строить и открывать сундуки."),
                        Lang.t("&8Управлять приватом — только владелец."),
                        Lang.t("&8➜ клик — выбрать из онлайна")),
                pl -> openTrust(pl, claimId));

        int roleCount = c.roles == null ? 0 : c.roles.size();
        gui.set(24, item(Items.NAME_TAG, Lang.t("&fРоли привата &8(") + roleCount + ")",
                        Lang.t("&7Наборы прав для пользователей:"),
                        Lang.t("&7стройка, сундуки, двери, существа."),
                        Lang.t("&8Без роли пользователь может всё."),
                        Lang.t("&8➜ клик — роли")),
                pl -> openRoles(pl, claimId));

        // Zones: one claim can be several cuboids, so a base does not have to
        // spend a whole claim slot on every wing.
        Selection sel = Selection.of(p.getUuid());
        boolean ready = sel.complete() && c.dim.equals(sel.dim);
        gui.set(25, item(ready ? Items.EMERALD_BLOCK : Items.GRAY_DYE,
                        Lang.t("&a➕ Добавить зону &8(зон: ") + c.zoneCount() + "/" + cfg().maxZonesPerClaim + ")",
                        Lang.t("&7Приват может состоять из нескольких кубов."),
                        Lang.t("&7Выдели топором новый кусок и нажми здесь —"),
                        Lang.t("&7он станет частью ЭТОГО привата, а не новым."),
                        ready ? Lang.t("&aВыделение готово: ") + sel.sizeX() + "×" + sel.sizeY() + "×" + sel.sizeZ()
                              : Lang.t("&cСначала выдели область топором в этом же мире"),
                        Lang.t("&8➜ клик")),
                pl -> {
                    Claim fresh = find(pl, claimId);
                    if (fresh == null) { open(pl); return; }
                    ClaimService.Result r = ClaimService.tryAddZone(pl, fresh);
                    pl.sendMessage(Fmt.text(r.message()), false);
                    openClaim(pl, claimId);
                });

        gui.set(27, item(Items.ARROW, Lang.t("&7← Назад")), ClaimMenu::open);
        gui.set(31, item(Items.WRITABLE_BOOK, Lang.t("&e📜 Передать приват"),
                        Lang.t("&7Отдать другому игроку насовсем."),
                        Lang.t("&cТы потеряешь доступ."),
                        Lang.t("&8➜ клик — выбрать игрока")),
                pl -> openTransfer(pl, claimId));
        gui.set(35, item(Items.BARRIER, Lang.t("&cУдалить приват"), Lang.t("&8➜ клик — подтвердить")),
                pl -> openDeleteConfirm(pl, claimId));

        gui.fill(filler());
        gui.open(p);
    }

    private static void openDeleteConfirm(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&cУдалить приват &7") + c.name + "&c?"), 3);
        gui.set(11, item(Items.LIME_WOOL, Lang.t("&aДа, удалить"), Lang.t("&8Отменить нельзя")),
                pl -> {
                    // Re-resolve instead of using the captured claim: ownership
                    // can change between opening this screen and confirming.
                    Claim fresh = find(pl, claimId);
                    if (fresh == null) { open(pl); return; }
                    String name = fresh.name;
                    ClaimManager.remove(fresh);
                    pl.sendMessage(Fmt.text(cfg().message("delete.success", name)), false);
                    open(pl);
                });
        gui.set(15, item(Items.RED_WOOL, Lang.t("&cНет, оставить")), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ transfer

    private static void openTransfer(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&e📜 Передать &8» &7") + c.name), 6);

        gui.set(4, item(Items.WRITABLE_BOOK, Lang.t("&eКому передать приват «") + c.name + "»?",
                Lang.t("&7Новый владелец получает всё:"),
                Lang.t("&7блоки, сундуки, флаги, доверенных."),
                Lang.t("&cТы теряешь доступ полностью."),
                Lang.t("&8Игрок должен быть в сети.")), null);

        int slot = 9;
        for (ServerPlayerEntity online : p.getEntityWorld().getServer().getPlayerManager().getPlayerList()) {
            if (slot >= 45) break;
            if (online.getUuid().equals(p.getUuid())) continue;
            String n = online.getGameProfile().name();
            String problem = ClaimService.transferProblem(online, c);
            if (problem != null) {
                gui.set(slot++, head(n, "&8" + n, Lang.t("&cНе может принять:"), "&8" + Fmt.strip(problem)), null);
            } else {
                gui.set(slot++, head(n, "&f" + n, Lang.t("&8➜ клик — передать этому игроку")),
                        pl -> openTransferConfirm(pl, claimId, n));
            }
        }
        if (slot == 9) {
            gui.set(22, item(Items.BOOK, Lang.t("&7Больше никого нет в сети"),
                    Lang.t("&8Передать можно только игроку онлайн")), null);
        }

        gui.set(49, item(Items.ARROW, Lang.t("&7← Назад")), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    private static void openTransferConfirm(ServerPlayerEntity p, String claimId, String targetName) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&eПередать &7") + c.name + Lang.t("&e игроку ") + targetName + "?"), 3);

        gui.set(11, item(Items.LIME_WOOL, Lang.t("&aДа, передать"),
                        Lang.t("&cОтменить будет нельзя —"), Lang.t("&cтолько новый владелец сможет вернуть")),
                pl -> {
                    Claim fresh = find(pl, claimId);
                    ServerPlayerEntity target = pl.getEntityWorld().getServer()
                            .getPlayerManager().getPlayer(targetName);
                    if (fresh == null || target == null) {
                        pl.sendMessage(Fmt.text(cfg().message("transfer.offline")), false);
                        pl.closeHandledScreen();
                        return;
                    }
                    String problem = ClaimService.transferProblem(target, fresh);
                    if (problem != null) {
                        pl.sendMessage(Fmt.text(problem), false);
                        pl.closeHandledScreen();
                        return;
                    }
                    ClaimCommands.doTransfer(pl, fresh, target);
                    pl.closeHandledScreen();
                });
        gui.set(15, item(Items.RED_WOOL, Lang.t("&cНет, оставить себе")), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ trust picker

    private static void openTrust(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&2🛡 Пользователи &8» &7") + c.name), 6);

        // Row 0: current users — click a head to pick a role or remove.
        int slot = 0;
        for (String m : new ArrayList<>(c.members)) {
            if (slot >= 9) break;
            String role = c.roleOf(ClaimManager.memberUuid(c, m));
            gui.set(slot++, head(m, "&a" + m,
                            Lang.t("&7Роль: &f") + (role == null ? Lang.t("полный пользователь") : role),
                            Lang.t("&8ЛКМ — роль или убрать")),
                    pl -> openMember(pl, claimId, m));
        }

        // Rows 1+: online players who are not yet trusted — click to add.
        slot = 9;
        for (ServerPlayerEntity online : p.getEntityWorld().getServer().getPlayerManager().getPlayerList()) {
            if (slot >= 45) break;
            String n = online.getGameProfile().name();
            if (c.isOwner(online.getUuid()) || c.isMember(online.getUuid())) continue;
            gui.set(slot++, head(n, "&f" + n, Lang.t("&aЛКМ — выдать доступ")),
                    pl -> { ClaimManager.addMember(c, n, online.getUuid()); openTrust(pl, claimId); });
        }
        if (slot == 9 && c.members.isEmpty()) {
            gui.set(22, item(Items.BOOK, Lang.t("&7Нет игроков онлайн"), Lang.t("&8Добавить оффлайн: &f/claim add <ник>")), null);
        }

        gui.set(49, item(Items.ARROW, Lang.t("&7← Назад")), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ roles

    private static void openMember(ServerPlayerEntity p, String claimId, String member) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        java.util.UUID uuid = ClaimManager.memberUuid(c, member);
        String current = c.roleOf(uuid);
        ChestGui gui = new ChestGui(Fmt.text("&2🛡 " + member + " &8» &7" + c.name), 3);
        gui.set(4, head(member, "&f" + member, Lang.t("&7Роль: &f") + (current == null ? Lang.t("полный пользователь") : current)), null);
        gui.set(9, item(current == null ? Items.NETHER_STAR : Items.EMERALD, Lang.t("&aПолный пользователь"),
                        Lang.t("&7Может всё, что и владелец,"), Lang.t("&7кроме управления приватом."),
                        current == null ? Lang.t("&a✔ выбрано") : Lang.t("&8➜ клик")),
                pl -> { ClaimManager.setMemberRole(c, uuid, null); openMember(pl, claimId, member); });
        int slot = 10;
        if (c.roles != null) {
            for (var e : c.roles.entrySet()) {
                if (slot > 17) break;
                String r = e.getKey();
                boolean on = r.equals(current);
                gui.set(slot++, item(on ? Items.NETHER_STAR : Items.NAME_TAG, "&f" + r,
                                Fmt.strip(ClaimCommands.permsText(e.getValue())), on ? Lang.t("&a✔ выбрано") : Lang.t("&8➜ клик — выдать")),
                        pl -> { ClaimManager.setMemberRole(c, uuid, r); openMember(pl, claimId, member); });
            }
        }
        gui.set(22, item(Items.BARRIER, Lang.t("&cУбрать из пользователей"), Lang.t("&8➜ клик")),
                pl -> { ClaimManager.removeMember(c, member); openTrust(pl, claimId); });
        gui.set(18, item(Items.ARROW, Lang.t("&7← Назад")), pl -> openTrust(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    private static void openRoles(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&2🛡 Роли &8» &7") + c.name), 4);
        gui.set(4, item(Items.WRITABLE_BOOK, Lang.t("&aКак работают роли"),
                Lang.t("&7Роль — набор прав внутри привата."),
                Lang.t("&7Пользователь с ролью может только то,"),
                Lang.t("&7что она разрешает (и что гостям)."),
                Lang.t("&7Без роли — полный пользователь."),
                Lang.t("&8/claim add <ник> <роль>")), null);
        int slot = 9;
        if (c.roles != null) {
            for (var e : c.roles.entrySet()) {
                if (slot > 26) break;
                String r = e.getKey();
                long holders = c.memberRoles == null ? 0 : c.memberRoles.values().stream().filter(r::equals).count();
                gui.set(slot++, item(Items.NAME_TAG, "&f" + r,
                                Fmt.strip(ClaimCommands.permsText(e.getValue())),
                                Lang.t("&7Пользователей: &f") + holders, Lang.t("&8➜ клик — настроить")),
                        pl -> openRole(pl, claimId, r));
            }
        }
        boolean full = c.roles != null && c.roles.size() >= ClaimManager.MAX_ROLES;
        gui.set(31, item(full ? Items.GRAY_DYE : Items.EMERALD_BLOCK, full ? Lang.t("&7Ролей максимум") : Lang.t("&a➕ Создать роль"),
                        full ? Lang.t("&8Удали лишнюю") : Lang.t("&8➜ клик — ввести название")),
                full ? null : pl -> AnvilGui.open(pl, Lang.t("&2Название роли"), "", name -> {
                    String clean = ClaimCommands.cleanRoleNamePublic(name);
                    Claim fresh = find(pl, claimId);
                    if (fresh == null) { open(pl); return; }
                    if (clean == null || !ClaimManager.createRole(fresh, clean)) {
                        pl.sendMessage(Fmt.text(Lang.t("&cНе вышло: 1–16 символов (буквы, цифры, _ -), без повторов, не больше ")
                                + ClaimManager.MAX_ROLES + Lang.t(" ролей.")), false);
                        openRoles(pl, claimId);
                        return;
                    }
                    openRole(pl, claimId, clean);
                }));
        gui.set(27, item(Items.ARROW, Lang.t("&7← Назад")), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    private static void openRole(ServerPlayerEntity p, String claimId, String role) {
        Claim c = find(p, claimId);
        String r = c == null ? null : c.findRole(role);
        if (r == null) { if (c != null) openRoles(p, claimId); else open(p); return; }
        List<String> perms = c.roleGrants(r);
        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&2🛡 Роль &8» &f") + r), 3);
        int slot = 10;
        Item[] icons = {Items.IRON_PICKAXE, Items.CHEST, Items.OAK_DOOR, Items.LEAD};
        int i = 0;
        for (Claim.Perm perm : Claim.Perm.values()) {
            boolean on = perms != null && perms.contains(perm.key);
            gui.set(slot, flagIcon(icons[i++], Lang.t(perm.label), on),
                    pl -> { ClaimManager.setRolePerm(c, r, perm, !on); openRole(pl, claimId, r); });
            slot += 2;
        }
        gui.set(22, item(Items.BARRIER, Lang.t("&cУдалить роль"), Lang.t("&7Её пользователи снова станут полными"), Lang.t("&8➜ клик")),
                pl -> { ClaimManager.deleteRole(c, r); openRoles(pl, claimId); });
        gui.set(18, item(Items.ARROW, Lang.t("&7← К ролям")), pl -> openRoles(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ admin: all claims

    private static void openAll(ServerPlayerEntity p, int page) {
        if (!ProtectionService.isOp(p)) { open(p); return; }
        List<Claim> all = ClaimManager.all();
        all.sort((a, b) -> a.owner.compareToIgnoreCase(b.owner));
        int perPage = 45;
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(page, pages - 1));

        ChestGui gui = new ChestGui(Fmt.text(Lang.t("&c⚙ Все приваты &8» &7стр. ") + (page + 1) + "/" + pages), 6);
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < all.size(); i++) {
            Claim c = all.get(start + i);
            // The admin browser used to be teleport-only: an operator could
            // look at every claim on the server but change none of them from
            // here. A click now opens the same management screen the owner
            // gets (flags, users, transfer, delete); teleport is its first button.
            gui.set(i, item(c.arrested ? Items.IRON_BARS : Items.GRASS_BLOCK, "&a" + c.name + " &8· &f" + c.owner,
                            "&7" + (c.fullHeight ? Lang.t("площадь ") + c.area() + " (2D)" : Lang.t("объём ") + c.volume() + " (3D)"),
                            "&7" + c.minX + " " + c.minY + " " + c.minZ + " &8(" + c.dim.replace("minecraft:", "") + ")",
                            Lang.t("&8➜ клик — управление (флаги, удаление, телепорт)")),
                    pl -> openClaim(pl, c.id));
        }
        int prev = page - 1, next = page + 1;
        if (page > 0) gui.set(45, item(Items.ARROW, Lang.t("&7← Пред. страница")), pl -> openAll(pl, prev));
        gui.set(49, item(Items.BARRIER, Lang.t("&7← В меню")), ClaimMenu::open);
        if (next < pages) gui.set(53, item(Items.ARROW, Lang.t("&7След. страница →")), pl -> openAll(pl, next));
        if (all.isEmpty()) gui.set(22, item(Items.BOOK, Lang.t("&7На сервере нет приватов")), null);

        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ МАХ app

    /**
     * MaxCore hosts the server's one menu; claims are a section inside it. The
     * link back is resolved reflectively so this mod still builds and runs on a
     * server without MaxCore — the button simply does not appear.
     */
    private static boolean maxCoreLoaded() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("maxcore");
    }

    private static void backToApp(ServerPlayerEntity p) {
        try {
            Class.forName("dev.maxcore.gui.MaxMenus")
                    .getMethod("openHome", ServerPlayerEntity.class)
                    .invoke(null, p);
        } catch (ReflectiveOperationException e) {
            p.closeHandledScreen();
            p.sendMessage(Fmt.text(Lang.t("&7Главное меню: &f/max")), false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Claim find(ServerPlayerEntity p, String claimId) {
        for (Claim c : ClaimManager.claimsOf(p.getUuid())) {
            if (c.id.equals(claimId)) return c;
        }
        if (ProtectionService.isOp(p)) {
            for (Claim c : ClaimManager.all()) if (c.id.equals(claimId)) return c;
        }
        return null;
    }

    private static ItemStack claimIcon(Claim c) {
        if (c.arrested) {
            return item(Items.IRON_BARS, "&4⛔ " + c.name + Lang.t(" &8(арест)"),
                    Lang.t("&cАрестован: &7") + (c.arrestReason == null || c.arrestReason.isBlank()
                            ? Lang.t("по решению государства") : c.arrestReason),
                    Lang.t("&7Вход, стройка и сундуки закрыты"),
                    Lang.t("&7до снятия ареста. Долг: &f/tax"),
                    c.fullHeight ? Lang.t("&7Площадь: &f") + c.area() + Lang.t(" &7блоков &8(во всю высоту)")
                                 : Lang.t("&7Объём: &f") + c.volume() + Lang.t(" &7блоков &8(3D)"),
                    Lang.t("&8➜ управление"));
        }
        return item(Items.GRASS_BLOCK, "&a" + c.name,
                c.fullHeight ? Lang.t("&7Площадь: &f") + c.area() + Lang.t(" &7блоков &8(во всю высоту)")
                             : Lang.t("&7Объём: &f") + c.volume() + Lang.t(" &7блоков &8(3D)"),
                Lang.t("&7Размер: &f") + c.sizeX() + "x" + c.sizeY() + "x" + c.sizeZ(),
                "&7" + c.minX + " " + c.minY + " " + c.minZ + " &8(" + c.dim.replace("minecraft:", "") + ")",
                Lang.t("&8➜ управление"));
    }

    /** A toggle anyone who reached the screen may switch (role permissions). */
    private static ItemStack flagIcon(Item icon, String name, boolean on) {
        return item(icon, (on ? "&a" : "&c") + name + ": " + (on ? Lang.t("&aВКЛ") : Lang.t("&cВЫКЛ")),
                Lang.t("&8➜ клик — переключить"));
    }

    private static ItemStack flagIcon(ServerPlayerEntity viewer, String key, Item icon, String name, boolean on) {
        String state = (on ? "&a" : "&c") + name + ": " + (on ? Lang.t("&aВКЛ") : Lang.t("&cВЫКЛ"));
        if (!ClaimCommands.maySetFlag(viewer, key)) {
            return item(icon, state, Lang.t("&8🔒 Меняет только администрация"));
        }
        return item(icon, state, Lang.t("&8➜ клик — переключить"));
    }

    /** Refuses, with a message, a flag the player may not switch. */
    private static boolean flagLocked(ServerPlayerEntity p, String key) {
        if (ClaimCommands.maySetFlag(p, key)) return false;
        p.sendMessage(Fmt.text(Lang.t("&cЭтот флаг меняет только администрация.")), false);
        return true;
    }

    private static ServerWorld resolve(ServerPlayerEntity p, String dim) {
        try {
            ServerWorld w = p.getEntityWorld().getServer()
                    .getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(dim)));
            if (w != null) return w;
        } catch (Exception ignored) {
        }
        return p.getEntityWorld().getServer().getOverworld();
    }

    private static ItemStack item(Item base, String name, String... lore) {
        ItemStack stack = new ItemStack(base);
        stack.set(DataComponentTypes.CUSTOM_NAME, Fmt.label(name));
        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            for (String l : lore) lines.add(Fmt.label(l.isEmpty() ? " " : l));
            stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        return stack;
    }

    private static ItemStack head(String playerName, String title, String... lore) {
        ItemStack stack = item(Items.PLAYER_HEAD, title, lore);
        GameProfile profile = new GameProfile(Uuids.getOfflinePlayerUuid(playerName), playerName);
        stack.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(profile));
        return stack;
    }

    private static ItemStack filler() {
        return item(Items.GRAY_STAINED_GLASS_PANE, "&0");
    }

    private ClaimMenu() {}
}
