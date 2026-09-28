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

        ChestGui gui = new ChestGui(Fmt.text("&2🛡 Приваты &8» &7" + owner), 6);

        List<String> lore = new ArrayList<>();
        lore.add("&7Наиграно: &f" + hours + "ч");
        lore.add("&7Приватов: &f" + claims.size() + (maxClaims < 0 ? " &8(без лимита)" : "&8/&f" + maxClaims));
        lore.add(maxBlocks < 0 ? "&7Лимит блоков: &fбез лимита" : "&7Лимит блоков: &f" + maxBlocks);
        gui.set(4, item(Items.WRITABLE_BOOK, "&aТвои лимиты", lore.toArray(new String[0])), null);

        gui.set(0, item(Items.WOODEN_AXE, "&b🪓 Получить топор",
                        "&7Выделить область: ЛКМ + ПКМ", "&8➜ клик"),
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
                            "&aСоздать приват &2(во всю высоту)" + (twoDefault ? " &8★" : ""),
                            "&7Площадь основания: &f" + area + " &7блоков",
                            "&7От бедрока до неба — погреб и крыша",
                            "&7тоже под защитой",
                            "&8➜ клик — ввести название"),
                    pl -> AnvilGui.open(pl, "&2Название привата (во всю высоту)", "",
                            name -> finishCreate(pl, name, true)));
            gui.set(twoDefault ? 8 : 7, item(Items.EMERALD_BLOCK,
                            "&aСоздать приват &2(куб 3D)" + (twoDefault ? "" : " &8★"),
                            "&7Объём выделения: &f" + vol + " &7блоков",
                            "&7Ровно по двум точкам — выше и ниже",
                            "&cне защищено",
                            "&8➜ клик — ввести название"),
                    pl -> AnvilGui.open(pl, "&2Название привата (куб 3D)", "",
                            name -> finishCreate(pl, name, false)));
        } else {
            gui.set(8, item(Items.GRAY_DYE, "&7Область не выделена",
                    "&8Возьми топор и отметь 2 угла"), null);
        }

        int pages = Math.max(1, (claims.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.max(0, Math.min(page, pages - 1));
        int slot = 9;
        for (int i = shown * PER_PAGE; i < claims.size() && slot < 45; i++) {
            Claim c = claims.get(i);
            gui.set(slot++, claimIcon(c), pl -> openClaim(pl, c.id));
        }
        if (claims.isEmpty()) {
            gui.set(22, item(Items.BOOK, "&7У тебя пока нет приватов",
                    "&8Получи топор, отметь углы,", "&8затем нажми «Создать»"), null);
        }
        if (pages > 1) {
            int prev = shown - 1, next = shown + 1;
            if (shown > 0) {
                gui.set(46, item(Items.ARROW, "&7← Пред. страница",
                        "&8" + (shown + 1) + " из " + pages, "&8▸ нажми"), pl -> open(pl, prev));
            }
            if (next < pages) {
                gui.set(52, item(Items.ARROW, "&7След. страница →",
                        "&8" + (shown + 1) + " из " + pages, "&8▸ нажми"), pl -> open(pl, next));
            }
        }

        gui.set(49, item(Items.KNOWLEDGE_BOOK, "&e❓ Как пользоваться приватами",
                "&7Гайд: создание, флаги, лимиты, команды", "&8➜ открыть"), ClaimMenu::openHelp);

        if (maxCoreLoaded()) {
            gui.set(45, item(Items.COMPASS, "&9◀ Приложение МАХ",
                    "&7Вернуться в главное меню сервера", "&8▸ нажми"), ClaimMenu::backToApp);
        }

        if (ProtectionService.isOp(p)) {
            gui.set(53, item(Items.ENDER_EYE, "&c⚙ Все приваты сервера",
                    "&7Просмотр и телепорт (админ)", "&8➜ клик"), pl -> openAll(pl, 0));
        }

        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ help / guide

    public static void openHelp(ServerPlayerEntity p) {
        ChestGui gui = new ChestGui(Fmt.text("&2🛡 Приваты &8» &7Как пользоваться"), 6);

        gui.set(4, item(Items.WRITABLE_BOOK, "&aГайд по приватам",
                "&7Приват — это защищённая зона.",
                "&7Внутри строить и открывать сундуки",
                "&7можешь только ты и доверенные."), null);

        gui.set(19, item(Items.WOODEN_AXE, "&b1. Как создать приват",
                "&7• Возьми топор: &f/claim wand&7 (или кнопка)",
                "&7• &fЛКМ&7 по блоку — 1-й угол",
                "&7• &fПКМ&7 по блоку — 2-й угол",
                "&7• Нажми «Создать» в меню или &f/claim create <имя>"), null);

        gui.set(20, item(Items.GRASS_BLOCK, "&b2. Форма привата",
                "&aВо всю высоту&7 — от бедрока до неба,",
                "&7лимит по площади. Это то, что нужно почти всегда:",
                "&7погреб и крыша защищены тоже.",
                "&8   &f/claim create2d <имя>",
                "&aКуб 3D&7 — ровно по двум точкам, лимит по объёму.",
                "&8   &f/claim create3d <имя>",
                "&8Обычный &f/claim create&8 делает "
                        + (cfg().fullHeightByDefault ? "во всю высоту." : "куб 3D.")), null);

        List<String> limits = new ArrayList<>();
        limits.add("&7Лимиты растут с наигранным временем:");
        for (ClaimConfig.Tier t : cfg().progression.tiers) {
            limits.add("&f" + t.minHours + "ч+ &7→ &f" + t.maxClaims + " прив. &7по &f" + t.maxBlocksPerClaim + " &7блоков");
        }
        limits.add("&8У операторов лимитов нет.");
        gui.set(21, item(Items.CLOCK, "&b3. Лимиты и часы", limits.toArray(new String[0])), null);

        gui.set(22, item(Items.REDSTONE, "&b4. Флаги привата",
                "&fpvp&7 — можно ли драться внутри",
                "&fmobs&7 — грифинг мобами (криперы)",
                "&fmobdmg&7 — урон игроку от мобов",
                "&fexplosions&7 — взрывы ТНТ ломают блоки",
                "&ffluids&7 — жидкости затекают извне",
                "&fpistons&7 — поршни извне двигают блоки",
                "&fhoppers&7 — воронки извне лезут в сундуки",
                "&fcontainers&8/&fdoors&7 — доступ гостям",
                "&8Всё ВЫКЛ = приват защищён. Меню или /claim flag"), null);

        gui.set(23, head(p.getGameProfile().name(), "&b5. Пользователи привата",
                "&7Дай друзьям строить в привате:",
                "&f/claim add <ник>&7 (стоя внутри)",
                "&7или кнопка-голова в меню привата.",
                "&7Убрать: &f/claim remove <ник>",
                "&8Пользователь строит, но НЕ управляет приватом:",
                "&8удалить, переименовать и передать может только владелец."), null);

        gui.set(24, item(Items.ENDER_PEARL, "&b6. Управление",
                "&7Открой &f/claim&7 → выбери приват:",
                "&7телепорт, флаги, доверенные, удаление.",
                "&8Инфо о привате под ногами: &f/claim info"), null);

        gui.set(31, item(Items.PAPER, "&eВсе команды приватов",
                "&f/claim &7— меню",
                "&f/claim wand &7— топор для выделения",
                "&f/claim create <имя> &8/ &fcreate2d <имя>",
                "&f/claim create2d &8· &fcreate3d &8— форма привата",
                "&f/claim list &8· &finfo &8· &frename <имя>",
                "&f/claim add/remove <ник> &7— пользователи",
                "&f/claim addzone &8· &fzones &8· &fdelzone <n>",
                "&f/claim borders &7— показать границы частицами",
                "&f/claim flag <флаг> on|off",
                "&f/claim transfer <ник> &7— отдать приват насовсем",
                "&f/claim delete &7(с подтверждением)"), null);

        if (ProtectionService.isOp(p)) {
            gui.set(33, item(Items.DIAMOND_PICKAXE, "&c⚙ WorldEdit &8(только операторы)",
                    "&7Тот же топор, но для правки мира:",
                    "&f/we set <блок> &8· &freplace <из> на <в>",
                    "&f/we copy &8· &fpaste &8· &frotate &8· &fflip",
                    "&f/we stack <раз> &8· &fmove <блоков>",
                    "&f/we undo &8· &fredo",
                    "&8Полный список: &f/we"), null);
        }

        gui.set(49, item(Items.ARROW, "&7← Назад"), ClaimMenu::open);
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
        ChestGui gui = new ChestGui(Fmt.text("&2🛡 Приват &8» &7" + c.name), 4);

        gui.set(4, claimIcon(c), null);

        gui.set(9, item(Items.ENDER_PEARL, "&aТелепорт к привату",
                        "&7Безопасная точка внутри", "&8➜ клик"),
                pl -> {
                    pl.closeHandledScreen();
                    ServerWorld w = resolve(pl, c.dim);
                    if (w == null) return;
                    if (Teleports.toClaimSafely(pl, w, c)) {
                        pl.sendMessage(Fmt.text("&aТелепорт к привату &f" + c.name + " 🛡"), false);
                    } else {
                        pl.sendMessage(Fmt.text("&cВнутри привата негде встать — всё заполнено. "
                                + "&7Освободи место и повтори."), false);
                    }
                });

        // Row 2 — behaviour flags (ВКЛ = разрешено). Default is "protected" (ВЫКЛ).
        gui.set(10, flagIcon(c.allowFireSpread ? Items.CAMPFIRE : Items.FLINT_AND_STEEL, "Огонь", c.allowFireSpread),
                pl -> { ClaimManager.setFireSpread(c, !c.allowFireSpread); openClaim(pl, claimId); });
        gui.set(11, flagIcon(c.allowPvp ? Items.DIAMOND_SWORD : Items.SHIELD, "PvP", c.allowPvp),
                pl -> { ClaimManager.setPvp(c, !c.allowPvp); openClaim(pl, claimId); });
        gui.set(12, flagIcon(c.allowMobGriefing ? Items.TNT : Items.GRASS_BLOCK, "Мобгриф", c.allowMobGriefing),
                pl -> { ClaimManager.setMobGriefing(c, !c.allowMobGriefing); openClaim(pl, claimId); });
        gui.set(13, flagIcon(c.allowMobDamage ? Items.ZOMBIE_HEAD : Items.GOLDEN_APPLE, "Урон от мобов", c.allowMobDamage),
                pl -> { ClaimManager.setMobDamage(c, !c.allowMobDamage); openClaim(pl, claimId); });
        gui.set(14, flagIcon(Items.TNT_MINECART, "Взрывы (ТНТ)", c.allowExplosions),
                pl -> { ClaimManager.setExplosions(c, !c.allowExplosions); openClaim(pl, claimId); });
        gui.set(15, flagIcon(Items.WATER_BUCKET, "Затекание жидкостей", c.allowFluidFlow),
                pl -> { ClaimManager.setFluidFlow(c, !c.allowFluidFlow); openClaim(pl, claimId); });
        gui.set(16, flagIcon(Items.PISTON, "Поршни извне", c.allowPistons),
                pl -> { ClaimManager.setPistons(c, !c.allowPistons); openClaim(pl, claimId); });
        gui.set(17, flagIcon(Items.HOPPER, "Воронки извне", c.allowHoppers),
                pl -> { ClaimManager.setHoppers(c, !c.allowHoppers); openClaim(pl, claimId); });

        // Row 3 — guest access + trusted players.
        gui.set(20, flagIcon(Items.CHEST, "Гости: сундуки и станки", c.guestContainers),
                pl -> { ClaimManager.setGuestContainers(c, !c.guestContainers); openClaim(pl, claimId); });
        gui.set(21, flagIcon(Items.OAK_DOOR, "Гости: двери/кнопки", c.guestDoors),
                pl -> { ClaimManager.setGuestDoors(c, !c.guestDoors); openClaim(pl, claimId); });
        gui.set(22, flagIcon(Items.LEAD, "Гости: существа", c.guestEntities),
                pl -> { ClaimManager.setGuestEntities(c, !c.guestEntities); openClaim(pl, claimId); });
        gui.set(23, head(p.getGameProfile().name(), "&fПользователи привата",
                        "&7Сейчас: &f" + (c.members.isEmpty() ? "никого" : c.members.size()),
                        "&7Могут строить и открывать сундуки.",
                        "&8Управлять приватом — только владелец.",
                        "&8➜ клик — выбрать из онлайна"),
                pl -> openTrust(pl, claimId));

        // Zones: one claim can be several cuboids, so a base does not have to
        // spend a whole claim slot on every wing.
        Selection sel = Selection.of(p.getUuid());
        boolean ready = sel.complete() && c.dim.equals(sel.dim);
        gui.set(25, item(ready ? Items.EMERALD_BLOCK : Items.GRAY_DYE,
                        "&a➕ Добавить зону &8(зон: " + c.zoneCount() + "/" + cfg().maxZonesPerClaim + ")",
                        "&7Приват может состоять из нескольких кубов.",
                        "&7Выдели топором новый кусок и нажми здесь —",
                        "&7он станет частью ЭТОГО привата, а не новым.",
                        ready ? "&aВыделение готово: " + sel.sizeX() + "×" + sel.sizeY() + "×" + sel.sizeZ()
                              : "&cСначала выдели область топором в этом же мире",
                        "&8➜ клик"),
                pl -> {
                    Claim fresh = find(pl, claimId);
                    if (fresh == null) { open(pl); return; }
                    ClaimService.Result r = ClaimService.tryAddZone(pl, fresh);
                    pl.sendMessage(Fmt.text(r.message()), false);
                    openClaim(pl, claimId);
                });

        gui.set(27, item(Items.ARROW, "&7← Назад"), ClaimMenu::open);
        gui.set(31, item(Items.WRITABLE_BOOK, "&e📜 Передать приват",
                        "&7Отдать другому игроку насовсем.",
                        "&cТы потеряешь доступ.",
                        "&8➜ клик — выбрать игрока"),
                pl -> openTransfer(pl, claimId));
        gui.set(35, item(Items.BARRIER, "&cУдалить приват", "&8➜ клик — подтвердить"),
                pl -> openDeleteConfirm(pl, claimId));

        gui.fill(filler());
        gui.open(p);
    }

    private static void openDeleteConfirm(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text("&cУдалить приват &7" + c.name + "&c?"), 3);
        gui.set(11, item(Items.LIME_WOOL, "&aДа, удалить", "&8Отменить нельзя"),
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
        gui.set(15, item(Items.RED_WOOL, "&cНет, оставить"), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ transfer

    private static void openTransfer(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text("&e📜 Передать &8» &7" + c.name), 6);

        gui.set(4, item(Items.WRITABLE_BOOK, "&eКому передать приват «" + c.name + "»?",
                "&7Новый владелец получает всё:",
                "&7блоки, сундуки, флаги, доверенных.",
                "&cТы теряешь доступ полностью.",
                "&8Игрок должен быть в сети."), null);

        int slot = 9;
        for (ServerPlayerEntity online : p.getEntityWorld().getServer().getPlayerManager().getPlayerList()) {
            if (slot >= 45) break;
            if (online.getUuid().equals(p.getUuid())) continue;
            String n = online.getGameProfile().name();
            String problem = ClaimService.transferProblem(online, c);
            if (problem != null) {
                gui.set(slot++, head(n, "&8" + n, "&cНе может принять:", "&8" + Fmt.strip(problem)), null);
            } else {
                gui.set(slot++, head(n, "&f" + n, "&8➜ клик — передать этому игроку"),
                        pl -> openTransferConfirm(pl, claimId, n));
            }
        }
        if (slot == 9) {
            gui.set(22, item(Items.BOOK, "&7Больше никого нет в сети",
                    "&8Передать можно только игроку онлайн"), null);
        }

        gui.set(49, item(Items.ARROW, "&7← Назад"), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    private static void openTransferConfirm(ServerPlayerEntity p, String claimId, String targetName) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text("&eПередать &7" + c.name + "&e игроку " + targetName + "?"), 3);

        gui.set(11, item(Items.LIME_WOOL, "&aДа, передать",
                        "&cОтменить будет нельзя —", "&cтолько новый владелец сможет вернуть"),
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
        gui.set(15, item(Items.RED_WOOL, "&cНет, оставить себе"), pl -> openClaim(pl, claimId));
        gui.fill(filler());
        gui.open(p);
    }

    // ------------------------------------------------------------------ trust picker

    private static void openTrust(ServerPlayerEntity p, String claimId) {
        Claim c = find(p, claimId);
        if (c == null) { open(p); return; }
        ChestGui gui = new ChestGui(Fmt.text("&2🛡 Пользователи &8» &7" + c.name), 6);

        // Row 0: current users — click a head to remove.
        int slot = 0;
        for (String m : new ArrayList<>(c.members)) {
            if (slot >= 9) break;
            gui.set(slot++, head(m, "&a" + m, "&cЛКМ — убрать из пользователей"),
                    pl -> { ClaimManager.removeMember(c, m); openTrust(pl, claimId); });
        }

        // Rows 1+: online players who are not yet trusted — click to add.
        slot = 9;
        for (ServerPlayerEntity online : p.getEntityWorld().getServer().getPlayerManager().getPlayerList()) {
            if (slot >= 45) break;
            String n = online.getGameProfile().name();
            if (c.isOwner(online.getUuid()) || c.isMember(online.getUuid())) continue;
            gui.set(slot++, head(n, "&f" + n, "&aЛКМ — выдать доступ"),
                    pl -> { ClaimManager.addMember(c, n, online.getUuid()); openTrust(pl, claimId); });
        }
        if (slot == 9 && c.members.isEmpty()) {
            gui.set(22, item(Items.BOOK, "&7Нет игроков онлайн", "&8Добавить оффлайн: &f/claim add <ник>"), null);
        }

        gui.set(49, item(Items.ARROW, "&7← Назад"), pl -> openClaim(pl, claimId));
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

        ChestGui gui = new ChestGui(Fmt.text("&c⚙ Все приваты &8» &7стр. " + (page + 1) + "/" + pages), 6);
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < all.size(); i++) {
            Claim c = all.get(start + i);
            gui.set(i, item(Items.GRASS_BLOCK, "&a" + c.name + " &8· &f" + c.owner,
                            "&7" + (c.fullHeight ? "площадь " + c.area() + " (2D)" : "объём " + c.volume() + " (3D)"),
                            "&7" + c.minX + " " + c.minY + " " + c.minZ + " &8(" + c.dim.replace("minecraft:", "") + ")",
                            "&8➜ клик — телепорт"),
                    pl -> {
                        pl.closeHandledScreen();
                        ServerWorld w = resolve(pl, c.dim);
                        if (w == null) return;
                        if (Teleports.toClaimSafely(pl, w, c)) {
                            pl.sendMessage(Fmt.text("&aТелепорт к привату &f" + c.name + " &7(&f" + c.owner + "&7)"), false);
                        } else {
                            pl.sendMessage(Fmt.text("&cВнутри привата &f" + c.name + "&c негде встать."), false);
                        }
                    });
        }
        int prev = page - 1, next = page + 1;
        if (page > 0) gui.set(45, item(Items.ARROW, "&7← Пред. страница"), pl -> openAll(pl, prev));
        gui.set(49, item(Items.BARRIER, "&7← В меню"), ClaimMenu::open);
        if (next < pages) gui.set(53, item(Items.ARROW, "&7След. страница →"), pl -> openAll(pl, next));
        if (all.isEmpty()) gui.set(22, item(Items.BOOK, "&7На сервере нет приватов"), null);

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
            p.sendMessage(Fmt.text("&7Главное меню: &f/max"), false);
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
            return item(Items.IRON_BARS, "&4⛔ " + c.name + " &8(арест)",
                    "&cАрестован: &7" + (c.arrestReason == null || c.arrestReason.isBlank()
                            ? "по решению государства" : c.arrestReason),
                    "&7Вход, стройка и сундуки закрыты",
                    "&7до снятия ареста. Долг: &f/tax",
                    c.fullHeight ? "&7Площадь: &f" + c.area() + " &7блоков &8(во всю высоту)"
                                 : "&7Объём: &f" + c.volume() + " &7блоков &8(3D)",
                    "&8➜ управление");
        }
        return item(Items.GRASS_BLOCK, "&a" + c.name,
                c.fullHeight ? "&7Площадь: &f" + c.area() + " &7блоков &8(во всю высоту)"
                             : "&7Объём: &f" + c.volume() + " &7блоков &8(3D)",
                "&7Размер: &f" + c.sizeX() + "x" + c.sizeY() + "x" + c.sizeZ(),
                "&7" + c.minX + " " + c.minY + " " + c.minZ + " &8(" + c.dim.replace("minecraft:", "") + ")",
                "&8➜ управление");
    }

    private static ItemStack flagIcon(Item icon, String name, boolean on) {
        return item(icon, (on ? "&a" : "&c") + name + ": " + (on ? "&aВКЛ" : "&cВЫКЛ"),
                "&8➜ клик — переключить");
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
