package dev.bastionclaims.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.core.BlockNames;
import dev.bastionclaims.core.Clipboard;
import dev.bastionclaims.core.Directions;
import dev.bastionclaims.core.MaxCoreBridge;
import dev.bastionclaims.core.ProtectionService;
import dev.bastionclaims.core.Selection;
import dev.bastionclaims.core.WorldEdit;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import dev.bastionclaims.util.Worlds;
import dev.bastionclaims.wand.ClaimWand;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Operator-only WorldEdit-style commands ({@code /we}) over the wand selection:
 * fill and replace, selection maths, and a full clipboard (copy / cut / paste /
 * rotate / flip / stack / move) with multi-level undo.
 *
 * <p>Sub-commands keep their English WorldEdit names so muscle memory carries
 * over; everything the operator types as <em>content</em> — block names and
 * directions — is Russian, with English ids still accepted.
 */
public final class WorldEditCommands {

    private static final SuggestionProvider<ServerCommandSource> BLOCKS =
            (ctx, b) -> CommandSource.suggestMatching(BlockNames.suggestions(), b);

    private static final SuggestionProvider<ServerCommandSource> DIRS =
            (ctx, b) -> CommandSource.suggestMatching(
                    List.of("вперёд", "назад", "вверх", "вниз", "север", "юг", "запад", "восток"), b);

    private static final SuggestionProvider<ServerCommandSource> AXES =
            (ctx, b) -> CommandSource.suggestMatching(List.of("x", "z", "y"), b);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("we")
                // Operators always; builders in MaxCore's build mode too, but
                // limited to the blocks their creative menu allows.
                .requires(src -> CommandManager.ADMINS_CHECK.allows(src.getPermissions())
                        || MaxCoreBridge.isBuilding(src.getPlayer()))
                .executes(ctx -> help(ctx.getSource().getPlayerOrThrow()))

                // ---- selection ----
                .then(literal("wand").executes(WorldEditCommands::wand))
                .then(literal("pos1").executes(c -> posHere(c, 1)))
                .then(literal("pos2").executes(c -> posHere(c, 2)))
                .then(literal("desel").executes(WorldEditCommands::desel))
                .then(literal("count").executes(WorldEditCommands::count))
                .then(literal("expand")
                        .then(literal("vert").executes(WorldEditCommands::expandVert))
                        .then(argument("блоков", integer(1, 100_000)).executes(c -> resize(c, 1, false))
                                .then(argument("направление", word()).suggests(DIRS)
                                        .executes(c -> resize(c, 1, false)))))
                .then(literal("contract")
                        .then(argument("блоков", integer(1, 100_000)).executes(c -> resize(c, -1, false))
                                .then(argument("направление", word()).suggests(DIRS)
                                        .executes(c -> resize(c, -1, false)))))
                .then(literal("shift")
                        .then(argument("блоков", integer(1, 100_000)).executes(c -> resize(c, 1, true))
                                .then(argument("направление", word()).suggests(DIRS)
                                        .executes(c -> resize(c, 1, true)))))

                // ---- history ----
                .then(literal("undo")
                        .executes(c -> undo(c, 1))
                        .then(argument("шагов", integer(1, 50)).executes(c -> undo(c, getInteger(c, "шагов")))))
                .then(literal("redo")
                        .executes(c -> redo(c, 1))
                        .then(argument("шагов", integer(1, 50)).executes(c -> redo(c, getInteger(c, "шагов")))))

                // ---- fills ----
                .then(literal("set")
                        .then(argument("блок", greedyString()).suggests(BLOCKS).executes(WorldEditCommands::set)))
                .then(literal("walls")
                        .then(argument("блок", greedyString()).suggests(BLOCKS).executes(WorldEditCommands::walls)))
                .then(literal("faces")
                        .then(argument("блок", greedyString()).suggests(BLOCKS).executes(WorldEditCommands::faces)))
                .then(literal("hollow").executes(WorldEditCommands::hollow))
                .then(literal("replace")
                        .then(argument("выражение", greedyString()).suggests(BLOCKS).executes(WorldEditCommands::replace)))

                // ---- clipboard ----
                .then(literal("copy").executes(c -> copy(c, false)))
                .then(literal("cut").executes(c -> copy(c, true)))
                .then(literal("paste")
                        .executes(c -> paste(c, false))
                        .then(literal("все").executes(c -> paste(c, true)))
                        .then(literal("all").executes(c -> paste(c, true))))
                .then(literal("rotate")
                        .then(argument("градусы", integer(-270, 270)).executes(WorldEditCommands::rotate)))
                .then(literal("flip")
                        .executes(c -> flip(c, null))
                        .then(argument("ось", word()).suggests(AXES)
                                .executes(c -> flip(c, getString(c, "ось")))))
                .then(literal("stack")
                        .then(argument("раз", integer(1, 100)).executes(c -> stack(c, null))
                                .then(argument("направление", word()).suggests(DIRS)
                                        .executes(c -> stack(c, getString(c, "направление"))))))
                .then(literal("move")
                        .then(argument("блоков", integer(1, 10_000)).executes(c -> move(c, null))
                                .then(argument("направление", word()).suggests(DIRS)
                                        .executes(c -> move(c, getString(c, "направление")))))));
    }

    // ================================================================== plumbing

    private record Box(ServerWorld world, String dim,
                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int sizeX() { return maxX - minX + 1; }
        int sizeY() { return maxY - minY + 1; }
        int sizeZ() { return maxZ - minZ + 1; }
        long volume() { return (long) sizeX() * sizeY() * sizeZ(); }
    }

    /** Validates config + selection and returns the normalised box, or null. */
    private static Box prep(ServerPlayerEntity p) {
        ClaimConfig cfg = BastionClaims.config();
        if (cfg == null || !cfg.worldEdit.enabled) {
            p.sendMessage(Fmt.text("&cWorldEdit выключен в конфиге."), false);
            return null;
        }
        if (WorldEdit.busy(p.getUuid())) {
            p.sendMessage(Fmt.text("&cПодожди: предыдущая операция ещё выполняется."), false);
            return null;
        }
        Selection s = Selection.of(p.getUuid());
        if (!s.complete()) {
            p.sendMessage(Fmt.text("&cСначала выдели область топором: &fЛКМ&c + &fПКМ&c по двум углам."), false);
            return null;
        }
        ServerWorld w = Worlds.resolve(p.getEntityWorld().getServer(), s.dim);
        if (w == null) {
            p.sendMessage(Fmt.text("&cМир выделения больше не существует."), false);
            return null;
        }
        return new Box(w, s.dim,
                Math.min(s.x1, s.x2), Math.min(s.y1, s.y2), Math.min(s.z1, s.z2),
                Math.max(s.x1, s.x2), Math.max(s.y1, s.y2), Math.max(s.z1, s.z2));
    }

    /** Refuses to touch another player's claim unless /claims bypass is on. */
    private static boolean guard(ServerPlayerEntity p, Box b) {
        Claim c = WorldEdit.foreignClaimIn(p, b.dim, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        if (c == null) return true;
        p.sendMessage(Fmt.text("&cОбласть задевает приват &f" + c.name + "&c (владелец &f" + c.owner + "&c)."), false);
        p.sendMessage(Fmt.text("&7Если это осознанно — включи &f/claims bypass&7 и повтори."), false);
        return false;
    }

    private static int run(ServerPlayerEntity p, Box b, String label, WorldEdit.CellOp op) {
        return run(p, b, label, op, null, null);
    }

    private static int run(ServerPlayerEntity p, Box b, String label, WorldEdit.CellOp op,
                           WorldEdit.DataOp data, WorldEdit.Carry carry) {
        if (!guard(p, b)) return 0;
        WorldEdit.Result r = WorldEdit.run(p, b.world, label,
                b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, op, data, carry);
        p.sendMessage(Fmt.text(r.message()), false);
        return r.ok() ? 1 : 0;
    }

    private static void setSelection(ServerPlayerEntity p, String dim,
                                     int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        Selection s = Selection.of(p.getUuid());
        s.setPos1(dim, minX, minY, minZ);
        s.setPos2(dim, maxX, maxY, maxZ);
    }

    // ================================================================== selection

    private static int wand(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        ClaimWand.give(p, BastionClaims.config());
        p.sendMessage(Fmt.text("&aВыдан топор. &7ЛКМ — точка 1, ПКМ — точка 2, затем &f/we set <блок>"), false);
        return 1;
    }

    private static int posHere(CommandContext<ServerCommandSource> ctx, int corner) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        String dim = p.getEntityWorld().getRegistryKey().getValue().toString();
        Selection s = Selection.of(p.getUuid());
        if (corner == 1) s.setPos1(dim, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        else s.setPos2(dim, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        p.sendMessage(Fmt.text("&aТочка " + corner + ": &f"
                + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ()), false);
        return 1;
    }

    private static int desel(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Selection.clear(p.getUuid());
        p.sendMessage(Fmt.text("&7Выделение снято."), false);
        return 1;
    }

    private static int count(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        p.sendMessage(Fmt.text("&7Выделение: &f" + b.sizeX() + "×" + b.sizeY() + "×" + b.sizeZ()
                + " &8= &f" + b.volume() + " &7блоков &8(лимит "
                + BastionClaims.config().worldEdit.maxBlocks + ")"), false);
        p.sendMessage(Fmt.text("&7От &f" + b.minX + " " + b.minY + " " + b.minZ
                + " &7до &f" + b.maxX + " " + b.maxY + " " + b.maxZ), false);
        Clipboard cb = Clipboard.get(p.getUuid());
        p.sendMessage(Fmt.text("&7Буфер: " + (cb == null ? "&8пуст"
                : "&f" + cb.sizeX + "×" + cb.sizeY + "×" + cb.sizeZ + " &8(" + cb.volume() + " блоков)")
                + " &8· отмен: &f" + WorldEdit.undoDepthOf(p.getUuid())
                + " &8· повторов: &f" + WorldEdit.redoDepthOf(p.getUuid())), false);
        return 1;
    }

    /** {@code expand} / {@code contract} (sign ±1) and {@code shift} over one face. */
    private static int resize(CommandContext<ServerCommandSource> ctx, int sign, boolean shift)
            throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        int n = getInteger(ctx, "блоков");
        String word = hasArg(ctx, "направление") ? getString(ctx, "направление") : null;
        Direction d = Directions.parse(p, word);
        if (d == null) {
            p.sendMessage(Fmt.text("&cНеизвестное направление. Можно: &f" + Directions.help()), false);
            return 0;
        }

        int minX = b.minX, minY = b.minY, minZ = b.minZ, maxX = b.maxX, maxY = b.maxY, maxZ = b.maxZ;
        int dx = d.getOffsetX() * n, dy = d.getOffsetY() * n, dz = d.getOffsetZ() * n;

        if (shift) {
            minX += dx; maxX += dx;
            minY += dy; maxY += dy;
            minZ += dz; maxZ += dz;
        } else {
            // Expanding moves the face the player is looking at; contracting pulls it back.
            int ex = dx * sign, ey = dy * sign, ez = dz * sign;
            if (ex > 0) maxX += ex; else minX += ex;
            if (ey > 0) maxY += ey; else minY += ey;
            if (ez > 0) maxZ += ez; else minZ += ez;
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                p.sendMessage(Fmt.text("&cТак выделение схлопнется в ничто."), false);
                return 0;
            }
        }
        int bottom = b.world.getBottomY();
        int top = bottom + b.world.getHeight() - 1;
        minY = Math.max(bottom, minY);
        maxY = Math.min(top, maxY);

        setSelection(p, b.dim, minX, minY, minZ, maxX, maxY, maxZ);
        String verb = shift ? "Сдвинуто" : sign > 0 ? "Расширено" : "Сжато";
        p.sendMessage(Fmt.text("&a" + verb + " на &f" + n + "&a блоков " + Directions.ru(d)
                + " &8→ &f" + (maxX - minX + 1) + "×" + (maxY - minY + 1) + "×" + (maxZ - minZ + 1)), false);
        return 1;
    }

    private static int expandVert(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        int bottom = b.world.getBottomY();
        int top = bottom + b.world.getHeight() - 1;
        setSelection(p, b.dim, b.minX, bottom, b.minZ, b.maxX, top, b.maxZ);
        p.sendMessage(Fmt.text("&aВыделение растянуто по высоте: &f" + bottom + " … " + top), false);
        return 1;
    }

    // ================================================================== history

    private static int undo(CommandContext<ServerCommandSource> ctx, int times) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        WorldEdit.Result r = WorldEdit.undo(p, ctx.getSource().getServer(), times);
        p.sendMessage(Fmt.text(r.message()), false);
        return r.ok() ? 1 : 0;
    }

    private static int redo(CommandContext<ServerCommandSource> ctx, int times) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        WorldEdit.Result r = WorldEdit.redo(p, ctx.getSource().getServer(), times);
        p.sendMessage(Fmt.text(r.message()), false);
        return r.ok() ? 1 : 0;
    }

    // ================================================================== fills

    private static int set(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        BlockState st = block(p, getString(ctx, "блок"));
        if (st == null) return 0;
        return run(p, b, "Заливка", (x, y, z, cur) -> st);
    }

    private static int walls(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        BlockState st = block(p, getString(ctx, "блок"));
        if (st == null) return 0;
        return run(p, b, "Стены", (x, y, z, cur) ->
                (x == b.minX || x == b.maxX || z == b.minZ || z == b.maxZ) ? st : null);
    }

    private static int faces(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        BlockState st = block(p, getString(ctx, "блок"));
        if (st == null) return 0;
        return run(p, b, "Коробка", (x, y, z, cur) ->
                (x == b.minX || x == b.maxX || y == b.minY || y == b.maxY || z == b.minZ || z == b.maxZ)
                        ? st : null);
    }

    private static int hollow(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        BlockState air = Blocks.AIR.getDefaultState();
        return run(p, b, "Выдалбливание", (x, y, z, cur) -> {
            boolean shell = x == b.minX || x == b.maxX || y == b.minY || y == b.maxY || z == b.minZ || z == b.maxZ;
            return shell || cur.isAir() ? null : air;
        });
    }

    private static int replace(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        String[] parts = splitExpr(getString(ctx, "выражение"));
        if (parts == null) {
            p.sendMessage(Fmt.text("&cФормат: &f/we replace <из> на <в>&c  (или &fиз>в&c). "
                    + "&8Напр.: &fкамень на воздух"), false);
            return 0;
        }
        BlockState from = block(p, parts[0]);
        if (from == null) return 0;
        BlockState to = block(p, parts[1]);
        if (to == null) return 0;
        return run(p, b, "Замена", (x, y, z, cur) -> cur.isOf(from.getBlock()) ? to : null);
    }

    // ================================================================== clipboard

    private static int copy(CommandContext<ServerCommandSource> ctx, boolean cut) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        var wcfg = BastionClaims.config().worldEdit;
        boolean builder = !ProtectionService.isOp(p);
        int max = builder ? Math.min(wcfg.builderMaxBlocks, wcfg.maxBlocks) : wcfg.maxBlocks;
        if (b.volume() > max) {
            p.sendMessage(Fmt.text("&cВыделение слишком большое для буфера: &f" + b.volume()
                    + "&c > &f" + max), false);
            return 0;
        }
        // Copying reads someone else's claim just as cut would empty it — a
        // copy of a base is the base's layout, and for a builder a pasted
        // copy is how a block they may not pick would enter their inventory.
        if (!guard(p, b)) return 0;
        if (builder && wcfg.buildersOnlyInsideOwnClaim
                && !WorldEdit.insideOwnClaim(p, b.dim, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
            p.sendMessage(Fmt.text("&cВ режиме стройки копировать можно только внутри своего привата."), false);
            return 0;
        }

        // The buffer holds blocks, never contents (see Clipboard). A cut's
        // contents stay in its own undo record, so undoing the paste and then
        // the cut puts the base back whole.
        Clipboard cb = Clipboard.capture(b.world, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, p.getBlockPos());
        Clipboard.put(p.getUuid(), cb);
        p.sendMessage(Fmt.text("&aВ буфер: &f" + cb.sizeX + "×" + cb.sizeY + "×" + cb.sizeZ
                + " &8(" + cb.volume() + " блоков)&a. Встань куда надо и &f/we paste"), false);
        p.sendMessage(Fmt.text(cut
                ? "&8Содержимое сундуков в буфер не попадает — оно в истории вырезания (/we undo вернёт). "
                        + "Перенести постройку вместе с вещами: /we move"
                : "&8Содержимое сундуков не копируется."), false);

        if (!cut) return 1;
        BlockState air = Blocks.AIR.getDefaultState();
        return run(p, b, "Вырезание", (x, y, z, cur) -> cur.isAir() ? null : air);
    }

    private static int paste(CommandContext<ServerCommandSource> ctx, boolean withAir) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Clipboard cb = Clipboard.get(p.getUuid());
        if (cb == null) {
            p.sendMessage(Fmt.text("&cБуфер пуст. Сначала &f/we copy"), false);
            return 0;
        }
        if (WorldEdit.busy(p.getUuid())) {
            p.sendMessage(Fmt.text("&cПодожди: предыдущая операция ещё выполняется."), false);
            return 0;
        }
        ServerWorld w = (ServerWorld) p.getEntityWorld();
        BlockPos anchor = p.getBlockPos();
        int minX = anchor.getX() + cb.offX, minY = anchor.getY() + cb.offY, minZ = anchor.getZ() + cb.offZ;
        int maxX = minX + cb.sizeX - 1, maxY = minY + cb.sizeY - 1, maxZ = minZ + cb.sizeZ - 1;

        int bottom = w.getBottomY(), top = bottom + w.getHeight() - 1;
        if (minY < bottom || maxY > top) {
            p.sendMessage(Fmt.text("&cВставка не влезает по высоте мира (&f" + bottom + "…" + top + "&c)."), false);
            return 0;
        }
        Box target = new Box(w, w.getRegistryKey().getValue().toString(), minX, minY, minZ, maxX, maxY, maxZ);
        return run(p, target, "Вставка", (x, y, z, cur) -> {
            BlockState st = cb.at(x - minX, y - minY, z - minZ);
            if (st == null) return null;
            return (!withAir && st.isAir()) ? null : st;
        });
    }

    private static int rotate(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Clipboard cb = Clipboard.get(p.getUuid());
        if (cb == null) {
            p.sendMessage(Fmt.text("&cБуфер пуст. Сначала &f/we copy"), false);
            return 0;
        }
        int deg = ((getInteger(ctx, "градусы") % 360) + 360) % 360;
        BlockRotation r = switch (deg) {
            case 90 -> BlockRotation.CLOCKWISE_90;
            case 180 -> BlockRotation.CLOCKWISE_180;
            case 270 -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> null;
        };
        if (r == null) {
            p.sendMessage(Fmt.text("&cПоворот только на 90, 180 или 270 градусов."), false);
            return 0;
        }
        Clipboard.put(p.getUuid(), cb.rotated(r));
        p.sendMessage(Fmt.text("&aБуфер повёрнут на &f" + deg + "°&a. Вставить: &f/we paste"), false);
        return 1;
    }

    private static int flip(CommandContext<ServerCommandSource> ctx, String axisWord) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Clipboard cb = Clipboard.get(p.getUuid());
        if (cb == null) {
            p.sendMessage(Fmt.text("&cБуфер пуст. Сначала &f/we copy"), false);
            return 0;
        }
        String axis = axisWord == null ? null : axisWord.trim().toLowerCase(Locale.ROOT);
        if (axis == null) {
            // No axis given: mirror across the axis the player faces.
            Direction d = Directions.facing(p);
            axis = (d.getAxis() == Direction.Axis.X) ? "x" : "z";
        }
        switch (axis) {
            case "x", "х", "запад-восток" -> {
                Clipboard.put(p.getUuid(), cb.mirrored(BlockMirror.FRONT_BACK));
                p.sendMessage(Fmt.text("&aБуфер отражён по оси &fX &8(запад↔восток)"), false);
            }
            case "z", "з", "север-юг" -> {
                Clipboard.put(p.getUuid(), cb.mirrored(BlockMirror.LEFT_RIGHT));
                p.sendMessage(Fmt.text("&aБуфер отражён по оси &fZ &8(север↔юг)"), false);
            }
            case "y", "у", "верх-низ" -> {
                Clipboard.put(p.getUuid(), cb.flippedVertically());
                p.sendMessage(Fmt.text("&aБуфер перевёрнут сверху вниз. "
                        + "&8Ступени и плиты останутся своей половиной."), false);
            }
            default -> {
                p.sendMessage(Fmt.text("&cОсь: &fx&c, &fz&c или &fy&c."), false);
                return 0;
            }
        }
        return 1;
    }

    private static int stack(CommandContext<ServerCommandSource> ctx, String dirWord) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        int times = getInteger(ctx, "раз");
        Direction d = Directions.parse(p, dirWord);
        if (d == null) {
            p.sendMessage(Fmt.text("&cНеизвестное направление. Можно: &f" + Directions.help()), false);
            return 0;
        }

        Direction.Axis axis = d.getAxis();
        int span = switch (axis) {
            case X -> b.sizeX();
            case Y -> b.sizeY();
            case Z -> b.sizeZ();
        };
        boolean positive = d.getDirection() == Direction.AxisDirection.POSITIVE;
        int step = positive ? span : -span;

        // Snapshot the source first: the copies would otherwise read cells the
        // job has already overwritten.
        Clipboard src = Clipboard.capture(b.world, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                new BlockPos(b.minX, b.minY, b.minZ));

        int fx = b.minX + (axis == Direction.Axis.X ? Math.min(0, step * times) : 0);
        int fz = b.minZ + (axis == Direction.Axis.Z ? Math.min(0, step * times) : 0);
        int fy = b.minY + (axis == Direction.Axis.Y ? Math.min(0, step * times) : 0);
        int tx = b.maxX + (axis == Direction.Axis.X ? Math.max(0, step * times) : 0);
        int tz = b.maxZ + (axis == Direction.Axis.Z ? Math.max(0, step * times) : 0);
        int ty = b.maxY + (axis == Direction.Axis.Y ? Math.max(0, step * times) : 0);

        int bottom = b.world.getBottomY(), top = bottom + b.world.getHeight() - 1;
        if (fy < bottom || ty > top) {
            p.sendMessage(Fmt.text("&cСтолько копий не влезет по высоте мира."), false);
            return 0;
        }

        Box target = new Box(b.world, b.dim, fx, fy, fz, tx, ty, tz);
        return run(p, target, "Размножение ×" + times, (x, y, z, cur) -> {
            int a = switch (axis) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            };
            int aMin = switch (axis) {
                case X -> b.minX;
                case Y -> b.minY;
                case Z -> b.minZ;
            };
            int delta = positive ? (a - aMin) : (aMin - a);
            if (delta < 0) return null;
            int copy = delta / span;
            if (copy < 1 || copy > times) return null;      // 0 is the original
            int srcA = positive ? a - copy * span : a + copy * span;
            int sx = (axis == Direction.Axis.X ? srcA : x) - b.minX;
            int sy = (axis == Direction.Axis.Y ? srcA : y) - b.minY;
            int sz = (axis == Direction.Axis.Z ? srcA : z) - b.minZ;
            return src.at(sx, sy, sz);
        });
    }

    private static int move(CommandContext<ServerCommandSource> ctx, String dirWord) throws CommandSyntaxException {
        ServerPlayerEntity p = ctx.getSource().getPlayerOrThrow();
        Box b = prep(p);
        if (b == null) return 0;
        int n = getInteger(ctx, "блоков");
        Direction d = Directions.parse(p, dirWord);
        if (d == null) {
            p.sendMessage(Fmt.text("&cНеизвестное направление. Можно: &f" + Directions.help()), false);
            return 0;
        }
        int dx = d.getOffsetX() * n, dy = d.getOffsetY() * n, dz = d.getOffsetZ() * n;
        int bottom = b.world.getBottomY(), top = bottom + b.world.getHeight() - 1;
        if (b.minY + dy < bottom || b.maxY + dy > top) {
            p.sendMessage(Fmt.text("&cПосле сдвига область выйдет за высоту мира."), false);
            return 0;
        }

        // The contents move with the blocks: a base moved three blocks over
        // used to arrive with every chest empty.
        if (!guard(p, b)) return 0;
        // A builder's move writes the target through the build-mode filter; a
        // refused block would vanish from the source and never arrive, with
        // whatever it held. Refuse the whole move up front instead.
        if (!ProtectionService.isOp(p) && MaxCoreBridge.isBuilding(p)) {
            BlockPos.Mutable c = new BlockPos.Mutable();
            for (int y = b.minY; y <= b.maxY; y++) {
                for (int z = b.minZ; z <= b.maxZ; z++) {
                    for (int x = b.minX; x <= b.maxX; x++) {
                        BlockState st = b.world.getBlockState(c.set(x, y, z));
                        if (!st.isAir() && !MaxCoreBridge.mayUseBlock(p, st)) {
                            p.sendMessage(Fmt.text("&cВ области есть блок, который в режиме стройки ставить нельзя: &f"
                                    + st.getBlock().getName().getString() + "&c (" + x + " " + y + " " + z
                                    + "). Перенос отменён — иначе он бы пропал."), false);
                            return 0;
                        }
                    }
                }
            }
        }
        Clipboard src = Clipboard.capture(b.world, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ,
                new BlockPos(b.minX, b.minY, b.minZ), true);
        // Its undo carries whatever is in the moved containers then back to where they stood.
        WorldEdit.Carry carry = null;
        if (src.hasData()) {
            List<Long> from = new ArrayList<>(), to = new ArrayList<>();
            for (int y = 0; y < src.sizeY; y++) {
                for (int z = 0; z < src.sizeZ; z++) {
                    for (int x = 0; x < src.sizeX; x++) {
                        if (src.dataAt(x, y, z) == null) continue;
                        from.add(BlockPos.asLong(b.minX + dx + x, b.minY + dy + y, b.minZ + dz + z));
                        to.add(BlockPos.asLong(b.minX + x, b.minY + y, b.minZ + z));
                    }
                }
            }
            carry = new WorldEdit.Carry(from.stream().mapToLong(Long::longValue).toArray(),
                    to.stream().mapToLong(Long::longValue).toArray());
        }
        BlockState air = Blocks.AIR.getDefaultState();

        int tMinX = b.minX + dx, tMinY = b.minY + dy, tMinZ = b.minZ + dz;
        int tMaxX = b.maxX + dx, tMaxY = b.maxY + dy, tMaxZ = b.maxZ + dz;
        Box union = new Box(b.world, b.dim,
                Math.min(b.minX, tMinX), Math.min(b.minY, tMinY), Math.min(b.minZ, tMinZ),
                Math.max(b.maxX, tMaxX), Math.max(b.maxY, tMaxY), Math.max(b.maxZ, tMaxZ));

        int r = run(p, union, "Перенос", (x, y, z, cur) -> {
            boolean inTarget = x >= tMinX && x <= tMaxX && y >= tMinY && y <= tMaxY && z >= tMinZ && z <= tMaxZ;
            if (inTarget) return src.at(x - tMinX, y - tMinY, z - tMinZ);
            boolean inSource = x >= b.minX && x <= b.maxX && y >= b.minY && y <= b.maxY && z >= b.minZ && z <= b.maxZ;
            return inSource ? air : null;
        }, (x, y, z) -> {
            boolean inTarget = x >= tMinX && x <= tMaxX && y >= tMinY && y <= tMaxY && z >= tMinZ && z <= tMaxZ;
            return inTarget ? src.dataAt(x - tMinX, y - tMinY, z - tMinZ) : null;
        }, carry);
        if (r != 1 && src.hasData()) {
            // The move was refused after the contents were taken: put them back.
            BlockPos.Mutable m = new BlockPos.Mutable();
            for (int y = 0; y < src.sizeY; y++) {
                for (int z = 0; z < src.sizeZ; z++) {
                    for (int x = 0; x < src.sizeX; x++) {
                        if (src.dataAt(x, y, z) == null) continue;
                        m.set(b.minX + x, b.minY + y, b.minZ + z);
                        WorldEdit.putBack(b.world, m.toImmutable(), src.dataAt(x, y, z));
                    }
                }
            }
        }
        if (r == 1) setSelection(p, b.dim, tMinX, tMinY, tMinZ, tMaxX, tMaxY, tMaxZ);
        return r;
    }

    // ================================================================== helpers

    private static boolean hasArg(CommandContext<ServerCommandSource> ctx, String name) {
        try {
            getString(ctx, name);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static BlockState block(ServerPlayerEntity p, String name) {
        BlockState st = BlockNames.resolve(name);
        if (st == null) {
            p.sendMessage(Fmt.text("&cНеизвестный блок: &f" + name
                    + "&c. Примеры: &fкамень&7, &fвоздух&7, &fдинамит&7, &fстекло&7, &fземля"), false);
            return null;
        }
        // The Russian name has already become a real block by this point, so the
        // build-mode rules are checked against the block id, not the wording.
        if (!MaxCoreBridge.mayUseBlock(p, st)) {
            p.sendMessage(Fmt.text("&cВ режиме стройки этот блок недоступен: &f" + name), false);
            p.sendMessage(Fmt.text("&7Строительные блоки — можно, руда, механизмы и"
                    + " хранилища — нет."), false);
            return null;
        }
        return st;
    }

    /** Splits "камень на воздух" / "камень>воздух" / "камень->воздух" into two names. */
    private static String[] splitExpr(String expr) {
        String low = expr.toLowerCase(Locale.ROOT);
        for (String d : new String[]{"->", ">", " на "}) {
            int i = low.indexOf(d);
            if (i > 0 && i + d.length() < expr.length()) {
                String a = expr.substring(0, i).trim();
                String c = expr.substring(i + d.length()).trim();
                if (!a.isEmpty() && !c.isEmpty()) return new String[]{a, c};
            }
        }
        return null;
    }

    private static int help(ServerPlayerEntity p) {
        p.sendMessage(Fmt.text("&6▎ WorldEdit &7— работает с выделением топора &8(только операторы)"), false);
        p.sendMessage(Fmt.text("&e Выделение: &f/we wand &8· &fpos1 &8· &fpos2 &8· &fdesel &8· &fcount"), false);
        p.sendMessage(Fmt.text("&f  /we expand <n> [куда] &8· &fcontract &8· &fshift &8· &fexpand vert"), false);
        p.sendMessage(Fmt.text("&e Заливка: &f/we set <блок> &8· &freplace <из> на <в>"), false);
        p.sendMessage(Fmt.text("&f  /we walls <блок> &8(4 стены) &8· &ffaces <блок> &8(все 6) &8· &fhollow"), false);
        p.sendMessage(Fmt.text("&e Буфер: &f/we copy &8· &fcut &8· &fpaste &8(&fpaste все&8 — с воздухом)"), false);
        p.sendMessage(Fmt.text("&f  /we rotate 90|180|270 &8· &fflip x|z|y"), false);
        p.sendMessage(Fmt.text("&f  /we stack <раз> [куда] &8· &fmove <блоков> [куда]"), false);
        p.sendMessage(Fmt.text("&e История: &f/we undo [шагов] &8· &fredo [шагов] &8(помнит "
                + BastionClaims.config().worldEdit.undoDepth + " шагов)"), false);
        p.sendMessage(Fmt.text("&8  Содержимое сундуков, текст табличек и спавнеры: при затирании не выпадают,"
                + " /we undo возвращает их; /we move переносит вместе с блоками. copy/paste/stack их не копируют — это был бы дюп."), false);
        p.sendMessage(Fmt.text("&8  &f/we undo 5&8 — откатить пять последних операций разом."), false);
        p.sendMessage(Fmt.text("&8Направления: " + Directions.help() + ". Без указания — куда смотришь."), false);
        p.sendMessage(Fmt.text("&8Блоки — по-русски («камень», «динамит»), английские id тоже работают."), false);
        return 1;
    }

    private WorldEditCommands() {}
}
