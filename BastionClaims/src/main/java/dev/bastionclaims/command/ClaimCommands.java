package dev.bastionclaims.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.core.ClaimEvents;
import dev.bastionclaims.core.ClaimManager;
import dev.bastionclaims.core.ClaimPresence;
import dev.bastionclaims.core.ClaimService;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.core.Selection;
import dev.bastionclaims.gui.ClaimMenu;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import dev.bastionclaims.wand.ClaimWand;
import net.minecraft.command.CommandSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** The player-facing {@code /claim} command tree (with a Russian {@code /приват} alias). */
public final class ClaimCommands {

    private static final int MAX_NAME_LEN = 24;
    private static final long CONFIRM_TTL_MS = 60_000;

    private record Pending(String claimId, long askedAtMs) {}

    private record PendingTransfer(String claimId, String target, long askedAtMs) {}

    private static final Map<UUID, Pending> PENDING_DELETE = new ConcurrentHashMap<>();
    private static final Map<UUID, PendingTransfer> PENDING_TRANSFER = new ConcurrentHashMap<>();

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralCommandNode<ServerCommandSource> root = dispatcher.register(literal("claim")
                .executes(ClaimCommands::menu)
                .then(literal("wand").executes(ClaimCommands::wand))
                .then(literal("pos1").executes(c -> posHere(c, 1)))
                .then(literal("pos2").executes(c -> posHere(c, 2)))
                // Plain "create" follows config.fullHeightByDefault (2D out of the
                // box): marking two corners and claiming the land is what people
                // mean, and a 3D box silently leaves their cellar and roof open.
                // The explicit forms stay for anyone who wants the other shape.
                .then(literal("create")
                        .then(argument("название", greedyString()).executes(c -> create(c, null))))
                .then(literal("create2d")
                        .then(argument("название", greedyString()).executes(c -> create(c, true))))
                .then(literal("create3d")
                        .then(argument("название", greedyString()).executes(c -> create(c, false))))
                .then(literal("list").executes(ClaimCommands::list))
                .then(literal("info").executes(ClaimCommands::info))
                .then(literal("rename")
                        .then(argument("новое", greedyString()).executes(ClaimCommands::rename)))
                // "add"/"remove" are the names people reach for; trust/untrust
                // stay as aliases so nobody's muscle memory breaks. Note it is
                // deliberately NOT "delete" — that already deletes the claim.
                .then(literal("add")
                        .then(argument("игрок", word()).suggests(onlinePlayers())
                                .executes(ClaimCommands::trust)))
                .then(literal("remove")
                        .then(argument("игрок", word()).suggests(members())
                                .executes(ClaimCommands::untrust)))
                .then(literal("trust")
                        .then(argument("игрок", word()).suggests(onlinePlayers())
                                .executes(ClaimCommands::trust)))
                .then(literal("untrust")
                        .then(argument("игрок", word()).suggests(members())
                                .executes(ClaimCommands::untrust)))
                .then(literal("borders").executes(ClaimCommands::borders))
                .then(literal("why").executes(ClaimCommands::why))
                .then(literal("почему").executes(ClaimCommands::why))
                .then(literal("trace").executes(ClaimCommands::trace))
                .then(literal("flag")
                        .then(argument("флаг", word()).suggests(flagNames())
                                .then(argument("значение", word()).suggests(onOff())
                                        .executes(ClaimCommands::flag))))
                .then(literal("addzone").executes(ClaimCommands::addZone))
                .then(literal("зона").executes(ClaimCommands::addZone))
                .then(literal("zones").executes(ClaimCommands::zones))
                .then(literal("delzone")
                        .then(argument("номер", integer(1, 64)).executes(ClaimCommands::delZone)))
                // Same toggle as /claims bypass. It lives here too because
                // "/claim bypass" is what everyone actually types, and the
                // missing "s" just answered "unknown command".
                .then(literal("bypass")
                        .requires(net.minecraft.server.command.CommandManager.requirePermissionLevel(
                                net.minecraft.server.command.CommandManager.ADMINS_CHECK))
                        .executes(ClaimCommands::bypass))
                .then(literal("transfer")
                        .then(literal("confirm").executes(ClaimCommands::transferConfirm))
                        .then(argument("игрок", word()).suggests(onlinePlayers())
                                .executes(ClaimCommands::transferPrompt)))
                .then(literal("delete")
                        .executes(ClaimCommands::deletePrompt)
                        .then(literal("confirm").executes(ClaimCommands::deleteConfirm))));

        dispatcher.register(literal("приват").redirect(root));
    }

    private static ClaimConfig cfg() {
        return BastionClaims.config();
    }

    // ------------------------------------------------------------------ menu / wand / pos

    private static int menu(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ClaimMenu.open(ctx.getSource().getPlayerOrThrow());
        return 1;
    }

    private static int wand(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        ClaimWand.Give result = ClaimWand.give(p, cfg());
        p.sendMessage(Fmt.text(result.given() ? cfg().message("wand.given") : result.message()), false);
        return result.given() ? 1 : 0;
    }

    private static int posHere(CommandContext<ServerCommandSource> ctx, int corner) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String dim = ProtectionService.dimOf(p);
        int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
        Selection sel = Selection.of(p.getUuid());
        if (corner == 1) {
            sel.setPos1(dim, x, y, z);
            p.sendMessage(Fmt.text(cfg().message("wand.pos1", x, y, z)), false);
        } else {
            sel.setPos2(dim, x, y, z);
            p.sendMessage(Fmt.text(cfg().message("wand.pos2", x, y, z)), false);
        }
        if (sel.complete()) {
            p.sendMessage(Fmt.text(cfg().message("wand.selInfo",
                    sel.sizeX(), sel.sizeY(), sel.sizeZ(), sel.volume())), false);
            p.sendMessage(Fmt.text(ClaimService.selectionHint()), false);
        }
        return 1;
    }

    // ------------------------------------------------------------------ create

    /** @param fullHeight {@code null} means "whatever the config default is". */
    private static int create(CommandContext<ServerCommandSource> ctx, Boolean fullHeight) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean twoD = fullHeight != null ? fullHeight : cfg().fullHeightByDefault;
        ClaimService.Result r = ClaimService.tryCreate(p, getString(ctx, "название"), twoD);
        p.sendMessage(Fmt.text(r.message()), false);
        if (r.ok()) ClaimPresence.flash(p, 15_000);   // show what was just claimed
        return r.ok() ? 1 : 0;
    }

    // ------------------------------------------------------------------ list / info

    private static int list(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        List<Claim> claims = ClaimManager.claimsOf(p.getUuid());
        if (claims.isEmpty()) {
            p.sendMessage(Fmt.text(cfg().message("list.empty")), false);
            return 0;
        }
        int hours = ProtectionService.playHours(p);
        int maxClaims = ProtectionService.isOp(p) && cfg().opBypassLimits ? claims.size() : cfg().maxClaimsFor(hours);
        p.sendMessage(Fmt.text(cfg().message("list.header", claims.size(), maxClaims)), false);
        for (Claim c : claims) {
            p.sendMessage(Fmt.text(cfg().message("list.entry", c.name, c.volume(), c.minX, c.minY, c.minZ)), false);
        }
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = claimAtFeet(p);
        if (c == null) {
            p.sendMessage(Fmt.text("&7Здесь нет привата."), false);
            return 0;
        }
        p.sendMessage(Fmt.text(cfg().message("info.header", c.name, c.owner)), false);
        p.sendMessage(Fmt.text(cfg().message("info.bounds",
                c.minX, c.minY, c.minZ, c.maxX, c.maxY, c.maxZ, c.dim.replace("minecraft:", ""))), false);
        String costLine = c.fullHeight
                ? cfg().message("info.volume", c.area(), c.sizeX(), "∞", c.sizeZ()) + " &8(2D)"
                : cfg().message("info.volume", c.volume(), c.sizeX(), c.sizeY(), c.sizeZ());
        p.sendMessage(Fmt.text(costLine), false);
        String members = c.members.isEmpty() ? "&8—" : String.join("&7, &f", c.members);
        p.sendMessage(Fmt.text(cfg().message("info.members", members)), false);
        p.sendMessage(Fmt.text(cfg().message("info.flags",
                onOff(c.allowPvp), onOff(c.allowMobGriefing), onOff(c.allowMobDamage), onOff(c.allowExplosions))), false);
        p.sendMessage(Fmt.text(cfg().message("info.external",
                onOff(c.allowFluidFlow), onOff(c.allowPistons), onOff(c.allowHoppers))), false);
        p.sendMessage(Fmt.text(cfg().message("info.guest",
                onOff(c.guestContainers), onOff(c.guestDoors), onOff(c.guestEntities))), false);
        return 1;
    }

    // ------------------------------------------------------------------ rename / trust / flag

    private static int rename(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        String old = c.name;
        // Same anti-spoofing rule as creation — see Fmt.sanitize.
        String newName = Fmt.sanitize(getString(ctx, "новое"));
        if (newName.isEmpty()) return fail(p, "create.noName");
        if (newName.length() > MAX_NAME_LEN) newName = newName.substring(0, MAX_NAME_LEN).trim();
        if (newName.isEmpty()) return fail(p, "create.noName");
        // Names are unique per owner — check against the claim's owner, which is
        // not necessarily the player typing (an operator may be renaming).
        if (ClaimManager.nameTaken(c.owner, newName)) return fail(p, "create.nameTaken");
        ClaimManager.rename(c, newName);
        p.sendMessage(Fmt.text(cfg().message("rename.success", old, newName)), false);
        return 1;
    }

    private static int trust(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        String target = getString(ctx, "игрок");
        if (c.isOwner(target)) return fail(p, "trust.self");
        ServerPlayerEntity onlineTarget = ctx.getSource().getServer().getPlayerManager().getPlayer(target);
        // The UUID mirror is what protection checks go through; the name is
        // display-only. When the player is online their exact UUID is used.
        ClaimManager.addMember(c, target, onlineTarget != null ? onlineTarget.getUuid() : null);
        p.sendMessage(Fmt.text(cfg().message("trust.added", target, c.name)), false);

        ServerPlayerEntity online = onlineTarget;
        if (online != null) {
            // Being handed the keys to someone's base is worth a notification.
            online.sendMessage(Fmt.text("&a🛡 &f" + p.getGameProfile().name()
                    + " &aдал тебе доступ к привату &f" + c.name), false);
        } else {
            // Offline names cannot be verified, so at least say so out loud —
            // a typo used to add a player who does not exist, silently.
            p.sendMessage(Fmt.text("&7Игрок &f" + target + "&7 не в сети — ник не проверить. "
                    + "&8Если ошибся: &f/claim untrust " + target), false);
        }
        return 1;
    }

    private static int untrust(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        String target = getString(ctx, "игрок");
        if (ClaimManager.removeMember(c, target)) {
            p.sendMessage(Fmt.text(cfg().message("trust.removed", target, c.name)), false);
            return 1;
        }
        return fail(p, "trust.notMember", target);
    }

    private static int flag(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        String flag = getString(ctx, "флаг").toLowerCase(Locale.ROOT);
        boolean on = parseOnOff(getString(ctx, "значение"));
        switch (flag) {
            case "pvp" -> ClaimManager.setPvp(c, on);
            case "mobs", "mobgrief" -> ClaimManager.setMobGriefing(c, on);
            case "mobdmg", "mobdamage" -> ClaimManager.setMobDamage(c, on);
            case "explosions", "tnt" -> ClaimManager.setExplosions(c, on);
            case "fluids", "liquids", "water" -> ClaimManager.setFluidFlow(c, on);
            case "pistons", "piston" -> ClaimManager.setPistons(c, on);
            case "hoppers", "hopper" -> ClaimManager.setHoppers(c, on);
            case "fire", "огонь" -> ClaimManager.setFireSpread(c, on);
            case "containers", "chests" -> ClaimManager.setGuestContainers(c, on);
            case "doors", "buttons" -> ClaimManager.setGuestDoors(c, on);
            case "entities", "mobs2", "существа" -> ClaimManager.setGuestEntities(c, on);
            default -> {
                return fail(p, "flag.unknown");
            }
        }
        p.sendMessage(Fmt.text(cfg().message("flag.set", flag, c.name, on ? "&aВКЛ" : "&cВЫКЛ")), false);
        return 1;
    }

    // ------------------------------------------------------------------ zones

    private static int addZone(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        ClaimService.Result r = ClaimService.tryAddZone(p, c);
        p.sendMessage(Fmt.text(r.message()), false);
        if (r.ok()) ClaimPresence.flash(p, 15_000);
        return r.ok() ? 1 : 0;
    }

    private static int zones(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = claimAtFeet(p);
        if (c == null) {
            p.sendMessage(Fmt.text("&7Здесь нет привата."), false);
            return 0;
        }
        p.sendMessage(Fmt.text(cfg().message("zone.header", c.name, c.zoneCount())), false);
        List<Claim.Box> boxes = c.zones();
        for (int i = 0; i < boxes.size(); i++) {
            Claim.Box b = boxes.get(i);
            p.sendMessage(Fmt.text(cfg().message("zone.entry", i + 1,
                    b.sizeX(), c.fullHeight ? "∞" : String.valueOf(b.sizeY()), b.sizeZ(),
                    b.minX, b.minY, b.minZ, c.fullHeight ? b.area() : b.volume())), false);
        }
        p.sendMessage(Fmt.text("&8Добавить: выдели топором и &f/claim addzone"), false);
        return 1;
    }

    private static int delZone(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        int index = getInteger(ctx, "номер");
        if (index == 1) return fail(p, "zone.primary");
        if (!ClaimManager.removeZone(c, index)) return fail(p, "zone.notFound", index);
        p.sendMessage(Fmt.text(cfg().message("zone.removed", index, c.name)), false);
        return 1;
    }

    /**
     * Explains, for the block being looked at, exactly why it can or cannot be
     * used: which claim owns it, what the player counts as there, which category
     * the block falls into, which flag governs it and what the verdict is.
     * Guessing at "the flags are on but it does not work" from a description is
     * hopeless; this turns it into one line of fact.
     */
    private static int why(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        HitResult hr = p.raycast(p.getBlockInteractionRange(), 1.0f, false);
        BlockPos pos = hr.getType() == HitResult.Type.BLOCK
                ? ((BlockHitResult) hr).getBlockPos() : p.getBlockPos();
        String dim = ProtectionService.dimOf(p);
        Identifier blockId = Registries.BLOCK.getId(p.getEntityWorld().getBlockState(pos).getBlock());

        p.sendMessage(Fmt.text("&6▎ Разбор доступа"), false);
        p.sendMessage(Fmt.text("&7Блок: &f" + blockId + " &8("
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + ")"), false);

        Claim c = ClaimManager.claimAt(dim, pos.getX(), pos.getY(), pos.getZ());
        if (c == null) {
            p.sendMessage(Fmt.text("&aЗдесь нет привата — ограничений нет."), false);
            return 1;
        }
        String me = p.getGameProfile().name();
        p.sendMessage(Fmt.text("&7Приват: &f" + c.name + " &8(владелец " + c.owner
                + ", зон " + c.zoneCount() + ")"), false);
        p.sendMessage(Fmt.text("&7Ты здесь: " + (c.isOwner(p.getUuid()) ? "&aвладелец"
                : c.isMember(p.getUuid()) ? "&aпользователь" : "&cгость")), false);
        String bypassWhy = ProtectionService.bypassReason(p);
        if (bypassWhy != null) {
            p.sendMessage(Fmt.text("&e⚠ У тебя активен обход защиты — ты видишь НЕ то, "
                    + "что видит обычный игрок."), false);
            p.sendMessage(Fmt.text("&e   Почему: &f" + bypassWhy), false);
        }

        ProtectionService.Interaction cat = ClaimEvents.classify(p.getEntityWorld(), pos);
        String catRu = switch (cat) {
            case CONTAINER -> "сундук/станок";
            case DOOR -> "дверь/кнопка";
            case ENTITY -> "существо";
            case OTHER -> "обычный блок";
        };
        String flagLine = switch (cat) {
            case CONTAINER -> "containers = " + onOff(c.guestContainers);
            case DOOR -> "doors = " + onOff(c.guestDoors);
            case ENTITY -> "entities = " + onOff(c.guestEntities);
            case OTHER -> "&8флагом не открывается — только владелец и пользователи";
        };
        p.sendMessage(Fmt.text("&7Категория: &f" + catRu), false);
        p.sendMessage(Fmt.text("&7Решает флаг: &f" + flagLine), false);
        p.sendMessage(Fmt.text("&7Защита в конфиге: блоки " + onOff(cfg().protection.blockProtection)
                + "&7, взаимодействия " + onOff(cfg().protection.interactProtection)), false);
        p.sendMessage(Fmt.text("&8Все флаги гостей: сундуки " + onOff(c.guestContainers)
                + "&8, двери " + onOff(c.guestDoors) + "&8, существа " + onOff(c.guestEntities)), false);

        Claim blocked = ProtectionService.blockingInteract(p, dim, pos.getX(), pos.getY(), pos.getZ(), cat);
        Claim building = ProtectionService.blockingBuild(p, dim, pos.getX(), pos.getY(), pos.getZ());
        p.sendMessage(Fmt.text(blocked == null
                ? "&a✔ Приваты, использовать: РАЗРЕШЕНО"
                : "&c✖ Приваты, использовать: ЗАПРЕЩЕНО"), false);
        p.sendMessage(Fmt.text(building == null
                ? "&a✔ Приваты, ломать и ставить: РАЗРЕШЕНО"
                : "&c✖ Приваты, ломать и ставить: ЗАПРЕЩЕНО"), false);

        // Everything below is outside this mod's control but decides the same
        // click, so it belongs in the same report.
        p.sendMessage(Fmt.text("&7Режим игры: &f" + p.getGameMode()
                + "&7, оператор: " + onOff(ProtectionService.isOp(p))), false);
        p.sendMessage(Fmt.text(dev.bastionclaims.core.MaxCoreBridge.stateLine(p)), false);

        // Vanilla's own spawn protection: a radius around the world spawn where
        // non-operators cannot touch anything. It cancels the click in silence —
        // no message, just the arm swing — which is indistinguishable from a
        // broken claim flag and is exactly what it gets mistaken for.
        if (p.getEntityWorld().getServer().isSpawnProtected(
                (net.minecraft.server.world.ServerWorld) p.getEntityWorld(), pos, p)) {
            p.sendMessage(Fmt.text("&c✖ ВАНИЛЬНАЯ ЗАЩИТА СПАВНА блокирует этот блок!"), false);
            p.sendMessage(Fmt.text("&7Это не приват и не мод. Лечится в &fserver.properties&7: "
                    + "&fspawn-protection=0&7, затем перезапуск."), false);
            return 1;
        }

        if (blocked == null) {
            p.sendMessage(Fmt.text("&8Приваты разрешают и защита спавна не мешает. "
                    + "Если клик всё равно не работает — &f/claim trace&8 и нажми ещё раз."), false);
        }
        return 1;
    }

    private static int trace(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean on = ClaimEvents.toggleTrace(p);
        p.sendMessage(Fmt.text(on
                ? "&aТрассировка включена — каждый правый клик пишет своё решение."
                : "&7Трассировка выключена."), false);
        return 1;
    }

    private static int borders(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean on = ClaimPresence.toggleBorders(p);
        p.sendMessage(Fmt.text(cfg().message(on ? "borders.on" : "borders.off")), false);
        return 1;
    }

    private static int bypass(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        boolean on = ProtectionService.toggleBypass(p);
        p.sendMessage(Fmt.text(cfg().message(on ? "admin.bypassOn" : "admin.bypassOff")), false);
        return 1;
    }

    // ------------------------------------------------------------------ transfer

    private static int transferPrompt(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        String wanted = getString(ctx, "игрок");

        // The new owner must be online: handing a base to a mistyped name would
        // put it beyond anyone's reach.
        ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(wanted);
        if (target == null) return fail(p, "transfer.offline");
        if (target.getUuid().equals(p.getUuid())) return fail(p, "transfer.self");
        if (c.arrested) return fail(p, "transfer.arrested");
        // The codeword (BastionAuth 1.6): a second lock on giving land away.
        if (dev.bastionclaims.core.AuthBridge.codewordProtects(p.getUuid())) return fail(p, "codeword.required");

        String problem = ClaimService.transferProblem(target, c);
        if (problem != null) {
            p.sendMessage(Fmt.text(problem), false);
            return 0;
        }
        PENDING_TRANSFER.put(p.getUuid(),
                new PendingTransfer(c.id, target.getGameProfile().name(), System.currentTimeMillis()));
        p.sendMessage(Fmt.text(cfg().message("transfer.confirm", c.name, target.getGameProfile().name())), false);
        return 1;
    }

    private static int transferConfirm(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        PendingTransfer pending = PENDING_TRANSFER.remove(p.getUuid());
        if (pending == null) {
            p.sendMessage(Fmt.text("&7Нечего подтверждать. Встань в свой приват: &f/claim transfer <ник>"), false);
            return 0;
        }
        if (System.currentTimeMillis() - pending.askedAtMs() > CONFIRM_TTL_MS) {
            p.sendMessage(Fmt.text("&7Подтверждение просрочено. Повтори &f/claim transfer <ник>&7."), false);
            return 0;
        }
        Claim c = ClaimManager.byId(pending.claimId());
        if (c == null) return fail(p, "delete.notFound", "?");
        ServerPlayerEntity target = ctx.getSource().getServer().getPlayerManager().getPlayer(pending.target());
        if (target == null) return fail(p, "transfer.offline");

        // Limits are re-checked: the target may have claimed something in the
        // minute between the prompt and the confirmation.
        String problem = ClaimService.transferProblem(target, c);
        if (problem != null) {
            p.sendMessage(Fmt.text(problem), false);
            return 0;
        }
        return doTransfer(p, c, target);
    }

    /** Shared by the command and the GUI button. */
    public static int doTransfer(ServerPlayerEntity from, Claim c, ServerPlayerEntity target) {
        String oldName = c.name;
        String newOwner = target.getGameProfile().name();
        String finalName = ClaimManager.setOwner(c, newOwner, MAX_NAME_LEN);

        from.sendMessage(Fmt.text(cfg().message("transfer.done", oldName, newOwner)), false);
        if (!oldName.equals(finalName)) {
            from.sendMessage(Fmt.text(cfg().message("transfer.renamed", finalName)), false);
        }
        target.sendMessage(Fmt.text(cfg().message("transfer.received", finalName, from.getGameProfile().name())), false);
        return 1;
    }

    // ------------------------------------------------------------------ delete

    private static int deletePrompt(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Claim c = ownClaimAtFeet(p);
        if (c == null) return 0;
        if (dev.bastionclaims.core.AuthBridge.codewordProtects(p.getUuid())) return fail(p, "codeword.required");
        PENDING_DELETE.put(p.getUuid(), new Pending(c.id, System.currentTimeMillis()));
        p.sendMessage(Fmt.text(cfg().message("delete.confirm", c.name)), false);
        return 1;
    }

    private static int deleteConfirm(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Pending pending = PENDING_DELETE.remove(p.getUuid());
        if (pending == null) {
            p.sendMessage(Fmt.text("&7Нечего подтверждать. Встань в свой приват и напиши &f/claim delete"), false);
            return 0;
        }
        // A confirmation typed an hour later is almost certainly not the one the
        // player meant — make them ask again.
        if (System.currentTimeMillis() - pending.askedAtMs() > CONFIRM_TTL_MS) {
            p.sendMessage(Fmt.text("&7Подтверждение просрочено. Повтори &f/claim delete&7."), false);
            return 0;
        }
        String id = pending.claimId();
        // Look the claim up by id, not through the player's own list: an operator
        // may legitimately be confirming the deletion of someone else's claim.
        Claim c = ClaimManager.byId(id);
        if (c == null) return fail(p, "delete.notFound", "?");
        // Re-check ownership at confirm time: the claim may have been
        // transferred to someone else between the prompt and the confirm, and
        // the pending entry must not outlive that change. Operators keep their
        // explicit cross-owner path.
        if (!c.isOwner(p.getUuid()) && !ProtectionService.isOp(p)) {
            p.sendMessage(Fmt.text("&cВладение приватом изменилось — подтверждение отменено."), false);
            return 0;
        }
        String name = c.name;
        ClaimManager.remove(c);
        p.sendMessage(Fmt.text(cfg().message("delete.success", name)), false);
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static Claim claimAtFeet(ServerPlayerEntity p) {
        return ClaimManager.claimAt(ProtectionService.dimOf(p), p.getBlockX(), p.getBlockY(), p.getBlockZ());
    }

    /**
     * Claim at the player's feet that they may manage, or null (with a message).
     * Operators may manage anyone's claim, but never silently: acting on someone
     * else's land always says whose it is, so nobody deletes a stranger's base
     * thinking it was their own.
     */
    private static Claim ownClaimAtFeet(ServerPlayerEntity p) {
        Claim c = claimAtFeet(p);
        if (c == null) {
            p.sendMessage(Fmt.text("&7Встань внутри своего привата и повтори команду."), false);
            return null;
        }
        if (!c.isOwner(p.getUuid())) {
            if (!ProtectionService.isOp(p)) {
                p.sendMessage(Fmt.text("&cЭто не твой приват (владелец &f" + c.owner + "&c)."), false);
                return null;
            }
            p.sendMessage(Fmt.text("&e⚠ Это ЧУЖОЙ приват &f" + c.name + "&e — владелец &f" + c.owner
                    + "&e. Действуешь правами оператора."), false);
        }
        return c;
    }

    private static int fail(ServerPlayerEntity p, String key, Object... args) {
        p.sendMessage(Fmt.text(cfg().message(key, args)), false);
        return 0;
    }

    private static boolean parseOnOff(String s) {
        String v = s.toLowerCase(Locale.ROOT);
        return v.equals("on") || v.equals("вкл") || v.equals("true") || v.equals("да") || v.equals("1");
    }

    private static String onOff(boolean on) {
        return on ? "&aВКЛ" : "&cВЫКЛ";
    }

    // ------------------------------------------------------------------ suggestions

    private static SuggestionProvider<ServerCommandSource> onlinePlayers() {
        return (ctx, b) -> CommandSource.suggestMatching(
                ctx.getSource().getServer().getPlayerManager().getPlayerList().stream()
                        .map(pl -> pl.getGameProfile().name()), b);
    }

    private static SuggestionProvider<ServerCommandSource> members() {
        return (ctx, b) -> {
            try {
                Claim c = claimAtFeet(ctx.getSource().getPlayerOrThrow());
                return c != null ? CommandSource.suggestMatching(c.members, b) : b.buildFuture();
            } catch (Exception e) {
                return b.buildFuture();
            }
        };
    }

    private static SuggestionProvider<ServerCommandSource> flagNames() {
        return (ctx, b) -> CommandSource.suggestMatching(
                List.of("pvp", "mobs", "mobdmg", "explosions", "fluids", "pistons", "hoppers",
                        "containers", "doors", "entities", "fire"), b);
    }

    private static SuggestionProvider<ServerCommandSource> onOff() {
        return (ctx, b) -> CommandSource.suggestMatching(List.of("on", "off"), b);
    }

    private ClaimCommands() {}
}
