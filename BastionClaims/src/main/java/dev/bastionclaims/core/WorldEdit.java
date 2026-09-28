package dev.bastionclaims.core;

import dev.bastionclaims.BastionClaims;
import dev.bastionclaims.model.Claim;
import dev.bastionclaims.util.Fmt;
import dev.bastionclaims.util.Worlds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator-only bulk block editing over the wand selection.
 *
 * <p>Work is <b>spread across ticks</b>: an operation becomes a job that writes
 * at most {@code worldEdit.blocksPerTick} blocks per server tick, so filling a
 * million blocks no longer freezes the server for the whole operation. Writes
 * skip neighbour physics (sand and water never cascade) and skip drops (blanking
 * a base full of chests does not vomit thousands of item entities onto the
 * floor). Every finished job goes onto a multi-level undo stack.
 */
public final class WorldEdit {

    // NOTIFY_LISTENERS — push the change to clients.
    // FORCE_STATE     — no neighbour updates, so nothing cascades or pops off.
    // SKIP_DROPS      — no block drops.
    // SKIP_BLOCK_ENTITY_REPLACED_CALLBACK — since 1.21.5 a replaced container
    //   scatters its items from BlockEntity.onBlockReplaced, and SKIP_DROPS no
    //   longer stops that: /we set over a base threw every chest's contents on
    //   the floor. Now they stay in the undo record, and an undo puts them back.
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS
            | Block.SKIP_BLOCK_ENTITY_REPLACED_CALLBACK;

    /** How many recorded changes one undo step may hold before we give up on it. */
    private static final int UNDO_CHANGE_CAP = 2_000_000;
    /** Across the whole stack of one player — the oldest steps are dropped first. */
    private static final long UNDO_TOTAL_CAP = 3_000_000L;

    public record Result(boolean ok, String message) {}

    /**
     * One overwritten cell. {@code data} is what the block entity held — a
     * chest's items, a sign's text, a spawner's mob — or null for a plain block.
     * Without it an undo put back an empty chest: the base came back, the
     * things in it did not.
     */
    private record Change(long pos, BlockState old, NbtCompound data) {}

    /** What a cell's block entity holds right now, or null. */
    static NbtCompound dataAt(ServerWorld world, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) return null;
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return null;
        try {
            return be.createNbtWithIdentifyingData(world.getRegistryManager());
        } catch (Exception e) {
            BastionClaims.LOGGER.warn("WorldEdit: could not read block entity at {}: {}", pos, e.toString());
            return null;
        }
    }

    /** Puts saved contents into the block entity now standing at {@code pos}. */
    static void restoreData(ServerWorld world, BlockPos pos, NbtCompound data) {
        if (data == null) return;
        BlockEntity be = world.getBlockEntity(pos);
        if (be == null) return;
        try {
            be.read(net.minecraft.storage.NbtReadView.create(net.minecraft.util.ErrorReporter.EMPTY,
                    world.getRegistryManager(), data));
            be.markDirty();
            BlockState st = world.getBlockState(pos);
            world.updateListeners(pos, st, st, Block.NOTIFY_LISTENERS);
        } catch (Exception e) {
            BastionClaims.LOGGER.warn("WorldEdit: could not restore block entity at {}: {}", pos, e.toString());
        }
    }

    /**
     * Reads a cell's contents and empties it on the spot; the block stays.
     * Used where contents travel (a move): the job that removes the old blocks
     * runs over several ticks, and a chest left full in the meantime could be
     * emptied by hand while its copy was already on its way.
     */
    static NbtCompound takeContents(ServerWorld world, BlockPos pos, BlockState state) {
        NbtCompound nbt = dataAt(world, pos, state);
        if (nbt == null) return null;
        BlockEntity be = world.getBlockEntity(pos);
        if (be instanceof net.minecraft.util.Clearable c) {
            c.clear();
            be.markDirty();
        }
        return nbt;
    }

    /** A refused move: the contents taken out go back where they were. */
    public static void putBack(ServerWorld world, BlockPos pos, NbtCompound data) {
        restoreData(world, pos, data);
    }

    /** Contents to write into a cell along with its new state (a move). */
    @FunctionalInterface
    public interface DataOp {
        NbtCompound at(int x, int y, int z);
    }

    /**
     * Contents that travelled with a move: when the recorded step is replayed,
     * whatever stands at {@code from[i]} at that moment goes into the cell
     * {@code to[i]} — not what was recorded. A player who emptied a moved
     * chest and then had the move undone must get back an empty chest, not the
     * original contents a second time.
     */
    public record Carry(long[] from, long[] to) {
        Carry reversed() {
            return new Carry(to, from);
        }
    }

    private record Snapshot(String dim, String label, List<Change> changes, Carry carry) {}

    /** Decides the new state of one cell. {@code null} leaves the cell alone. */
    @FunctionalInterface
    public interface CellOp {
        BlockState apply(int x, int y, int z, BlockState current);
    }

    /**
     * Per player, a queue of pending jobs. It is a queue rather than a single
     * slot so {@code /we undo 5} can roll back five recorded steps in a row,
     * each still its own step for the purposes of redo.
     */
    private static final Map<UUID, Deque<Job>> JOBS = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Snapshot>> UNDO = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Snapshot>> REDO = new ConcurrentHashMap<>();

    // ================================================================== jobs

    private abstract static class Job {
        final UUID owner;
        final ServerWorld world;
        final String label;
        final List<Change> changes = new ArrayList<>();
        final long total;
        long processed;
        long lastProgressMs = System.currentTimeMillis();
        boolean undoTooBig;
        /** Where the reverse changes go: undo stack for edits, redo stack for an undo. */
        final boolean toRedoStack;
        /** A brand-new edit invalidates the redo chain; replaying history does not. */
        final boolean freshEdit;
        /** The carry of the step this job leaves on the stack, if contents travelled. */
        Carry produces;
        /** Overwritten containers that still held something. */
        int containersWithItems;

        Job(UUID owner, ServerWorld world, String label, long total, boolean toRedoStack, boolean freshEdit) {
            this.owner = owner;
            this.world = world;
            this.label = label;
            this.total = total;
            this.toRedoStack = toRedoStack;
            this.freshEdit = freshEdit;
        }

        /** Processes up to {@code budget} cells. @return true when finished. */
        abstract boolean step(int budget);

        final void write(BlockPos.Mutable m, BlockState next) {
            write(m, next, null);
        }

        /** {@code data}: contents for the block entity of {@code next}, or null for none. */
        final void write(BlockPos.Mutable m, BlockState next, NbtCompound data) {
            BlockState old = world.getBlockState(m);
            if (next == null) return;
            if (next == old && data == null) return;
            if (next != old && world.getBlockEntity(m) instanceof net.minecraft.inventory.Inventory inv
                    && !inv.isEmpty()) {
                containersWithItems++;
            }
            if (changes.size() < UNDO_CHANGE_CAP) {
                changes.add(new Change(m.asLong(), old, dataAt(world, m, old)));
            } else {
                undoTooBig = true;
            }
            if (next != old) world.setBlockState(m, next, FLAGS);
            if (data != null) restoreData(world, m.toImmutable(), data);
        }
    }

    /** Walks a cuboid, cell by cell, applying a {@link CellOp}. */
    private static final class BoxJob extends Job {
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final CellOp op;
        final DataOp data;
        int cx, cy, cz;

        BoxJob(UUID owner, ServerWorld world, String label,
               int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CellOp op, DataOp data) {
            super(owner, world, label, volume(minX, minY, minZ, maxX, maxY, maxZ), false, true);
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
            this.op = op;
            this.data = data;
            this.cx = minX; this.cy = minY; this.cz = minZ;
        }

        @Override
        boolean step(int budget) {
            BlockPos.Mutable m = new BlockPos.Mutable();
            int n = 0;
            while (n < budget) {
                if (cy > maxY) return true;
                m.set(cx, cy, cz);
                write(m, op.apply(cx, cy, cz, world.getBlockState(m)), data == null ? null : data.at(cx, cy, cz));
                n++;
                processed++;
                if (++cx > maxX) {
                    cx = minX;
                    if (++cz > maxZ) {
                        cz = minZ;
                        cy++;
                    }
                }
            }
            return cy > maxY;
        }
    }

    /** Replays a recorded change list backwards (undo / redo). */
    private static final class RestoreJob extends Job {
        final List<Change> source;
        final Carry carry;
        boolean carried;
        int cursor;

        RestoreJob(UUID owner, ServerWorld world, String label, Snapshot s, boolean toRedoStack) {
            super(owner, world, label, s.changes().size(), toRedoStack, false);
            this.source = s.changes();
            this.carry = s.carry();
            this.produces = carry == null ? null : carry.reversed();
            this.cursor = source.size() - 1;
        }

        @Override
        boolean step(int budget) {
            // On the first tick of this job, not when it was queued: with
            // «/we undo 3» the steps before this one change the world first.
            if (!carried) {
                carried = true;
                if (carry != null) carryInto(world, source, carry);
            }
            BlockPos.Mutable m = new BlockPos.Mutable();
            int n = 0;
            while (n < budget) {
                if (cursor < 0) return true;
                Change c = source.get(cursor--);
                m.set(BlockPos.fromLong(c.pos()));
                write(m, c.old(), c.data());
                n++;
                processed++;
            }
            return cursor < 0;
        }
    }

    /** Takes the live contents at each {@code from} into the recorded change for its {@code to}. */
    private static void carryInto(ServerWorld world, List<Change> changes, Carry carry) {
        Map<Long, Integer> at = new HashMap<>();
        for (int i = 0; i < changes.size(); i++) at.put(changes.get(i).pos(), i);
        for (int i = 0; i < carry.from().length; i++) {
            Integer idx = at.get(carry.to()[i]);
            if (idx == null) continue;
            Change c = changes.get(idx);
            BlockPos from = BlockPos.fromLong(carry.from()[i]);
            BlockState live = world.getBlockState(from);
            // Broken or replaced since the move: its items already went
            // wherever breaking sends them, the recorded (empty) state stands.
            if (!live.isOf(c.old().getBlock())) continue;
            NbtCompound nbt = takeContents(world, from, live);
            if (nbt != null) changes.set(idx, new Change(c.pos(), c.old(), nbt));
        }
    }

    // ================================================================== driving

    /** Called once per server tick from the mod entry point. */
    public static void tick(MinecraftServer server) {
        sweepSessions();
        if (JOBS.isEmpty()) return;
        int budget = Math.max(64, BastionClaims.config().worldEdit.blocksPerTick);
        for (Iterator<Map.Entry<UUID, Deque<Job>>> it = JOBS.entrySet().iterator(); it.hasNext(); ) {
            Deque<Job> queue = it.next().getValue();
            Job job = queue.peekFirst();
            if (job == null) {
                it.remove();
                continue;
            }
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(job.owner);
            boolean finished;
            try {
                finished = job.step(budget);
            } catch (Exception ex) {
                BastionClaims.LOGGER.error("WorldEdit job '{}' failed", job.label, ex);
                queue.clear();
                it.remove();
                if (p != null) p.sendMessage(Fmt.text("&cОперация прервана из-за ошибки, смотри консоль."), false);
                continue;
            }
            if (finished) {
                queue.removeFirst();
                complete(job, p);
                if (queue.isEmpty()) it.remove();
            } else if (p != null) {
                progress(job, p);
            }
        }
    }

    private static void enqueue(UUID owner, Job job) {
        JOBS.computeIfAbsent(owner, k -> new ArrayDeque<>()).addLast(job);
    }

    private static void progress(Job job, ServerPlayerEntity p) {
        long now = System.currentTimeMillis();
        if (now - job.lastProgressMs < 1000) return;
        job.lastProgressMs = now;
        int pct = job.total <= 0 ? 100 : (int) (job.processed * 100 / job.total);
        p.sendMessage(Fmt.text("&e" + job.label + ": &f" + pct + "%&7 (" + job.processed + " / " + job.total + ")"), true);
    }

    private static void complete(Job job, ServerPlayerEntity p) {
        Deque<Snapshot> target = (job.toRedoStack ? REDO : UNDO)
                .computeIfAbsent(job.owner, k -> new ArrayDeque<>());
        if (job.undoTooBig) {
            target.clear();
        } else {
            String dim = job.world.getRegistryKey().getValue().toString();
            target.push(new Snapshot(dim, job.label, job.changes, job.produces));
            int depth = Math.max(1, BastionClaims.config().worldEdit.undoDepth);
            while (target.size() > depth) target.removeLast();
            // Sessions survive reconnects, so bound the memory by total size too.
            long held = 0;
            for (Snapshot s : target) held += s.changes().size();
            while (target.size() > 1 && held > UNDO_TOTAL_CAP) {
                held -= target.removeLast().changes().size();
            }
        }
        if (job.freshEdit) {
            Deque<Snapshot> redo = REDO.get(job.owner);
            if (redo != null) redo.clear();
        }
        if (p == null) return;
        String tail = job.undoTooBig
                ? " &8(слишком много изменений — отменить нельзя)"
                : " &8(/we undo — отменить)";
        p.sendMessage(Fmt.text("&a" + job.label + ": изменено &f" + job.changes.size() + "&a блоков." + tail), false);
        if (job.containersWithItems > 0) {
            p.sendMessage(Fmt.text(job.undoTooBig
                    ? "&c⚠ Затёрто контейнеров с вещами: &f" + job.containersWithItems
                            + "&c. Шаг слишком большой для истории — их содержимое не вернуть."
                    : "&e Затёрто контейнеров с вещами: &f" + job.containersWithItems
                            + "&e. Вещи не выпали — они в истории, &f/we undo&e вернёт их вместе с блоками."), false);
        }
    }

    public static boolean busy(UUID player) {
        Deque<Job> q = JOBS.get(player);
        return q != null && !q.isEmpty();
    }

    /**
     * Queues a cuboid operation. The caller has already validated the selection.
     *
     * <p>Builder policy lives here, on the one path every fill, paste, stack,
     * move and cut goes through, so no command can forget it: a builder
     * (build mode, not an operator) edits only inside a claim they own, only
     * up to {@code builderMaxBlocks}, and every block the operation produces
     * is filtered through the same rules as their creative menu — a pasted
     * diamond ore is refused exactly like a picked one.
     */
    public static Result run(ServerPlayerEntity p, ServerWorld world, String label,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CellOp op) {
        return run(p, world, label, minX, minY, minZ, maxX, maxY, maxZ, op, null);
    }

    public static Result run(ServerPlayerEntity p, ServerWorld world, String label,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CellOp op, DataOp data) {
        return run(p, world, label, minX, minY, minZ, maxX, maxY, maxZ, op, data, null);
    }

    /** {@code data}/{@code carry}: contents that travel with the blocks (a move) and how its undo carries them back. */
    public static Result run(ServerPlayerEntity p, ServerWorld world, String label,
                             int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                             CellOp op, DataOp data, Carry carry) {
        if (busy(p.getUuid())) {
            return new Result(false, "&cПодожди: предыдущая операция ещё выполняется.");
        }
        long vol = volume(minX, minY, minZ, maxX, maxY, maxZ);
        var cfg = BastionClaims.config().worldEdit;
        boolean builder = !ProtectionService.isOp(p);
        int max = builder ? Math.min(cfg.builderMaxBlocks, cfg.maxBlocks) : cfg.maxBlocks;
        if (vol > max) return tooBig(vol, max);
        if (builder) {
            if (!MaxCoreBridge.isBuilding(p)) {
                return new Result(false, "&cWorldEdit доступен только операторам и строителям в режиме стройки.");
            }
            String dim = world.getRegistryKey().getValue().toString();
            if (cfg.buildersOnlyInsideOwnClaim && !insideOwnClaim(p, dim, minX, minY, minZ, maxX, maxY, maxZ)) {
                return new Result(false, "&cВ режиме стройки WorldEdit работает только внутри твоего привата — "
                        + "вся область, от угла до угла.");
            }
            CellOp inner = op;
            op = (x, y, z, cur) -> {
                BlockState next = inner.apply(x, y, z, cur);
                if (next == null) return null;
                return MaxCoreBridge.mayUseBlock(p, next) ? next : null;
            };
        }
        BoxJob job = new BoxJob(p.getUuid(), world, label, minX, minY, minZ, maxX, maxY, maxZ, op, data);
        job.produces = carry;
        enqueue(p.getUuid(), job);
        return new Result(true, "&7" + label + ": обрабатываю &f" + vol + "&7 блоков…");
    }

    /** True when one zone of a claim the player owns contains the whole cuboid. */
    public static boolean insideOwnClaim(ServerPlayerEntity p, String dim,
                                         int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (Claim c : ClaimManager.claimsOf(p.getUuid())) {
            if (!c.sameDim(dim)) continue;
            for (Claim.Box b : c.zones()) {
                if (b.contains(minX, minY, minZ) && b.contains(maxX, maxY, maxZ)) return true;
            }
        }
        return false;
    }

    // ================================================================== undo / redo

    public static Result undo(ServerPlayerEntity p, MinecraftServer server, int times) {
        return pop(p, server, UNDO, true, "Возврат", "&cНечего отменять.", times);
    }

    public static Result redo(ServerPlayerEntity p, MinecraftServer server, int times) {
        return pop(p, server, REDO, false, "Повтор", "&cНечего повторять.", times);
    }

    /**
     * Takes up to {@code times} recorded steps off the stack and queues one job
     * per step, newest first. Each stays a separate step, so undoing five and
     * then redoing once puts exactly one of them back.
     */
    private static Result pop(ServerPlayerEntity p, MinecraftServer server, Map<UUID, Deque<Snapshot>> from,
                              boolean toRedoStack, String label, String emptyMessage, int times) {
        if (busy(p.getUuid())) return new Result(false, "&cПодожди: предыдущая операция ещё выполняется.");
        Deque<Snapshot> stack = from.get(p.getUuid());
        if (stack == null || stack.isEmpty()) return new Result(false, emptyMessage);

        int queued = 0;
        long blocks = 0;
        String skippedWorld = null;
        while (queued < times && !stack.isEmpty()) {
            Snapshot s = stack.peek();
            ServerWorld world = Worlds.resolve(server, s.dim());
            if (world == null) {
                skippedWorld = s.dim();
                break; // stop at the first step we cannot apply, keep the rest
            }
            stack.pop();
            enqueue(p.getUuid(), new RestoreJob(p.getUuid(), world,
                    label + " «" + s.label() + "»", s, toRedoStack));
            queued++;
            blocks += s.changes().size();
        }
        if (queued == 0) {
            return new Result(false, skippedWorld == null ? emptyMessage
                    : "&cМир того изменения больше не существует (" + skippedWorld.replace("minecraft:", "") + ").");
        }
        String tail = stack.isEmpty() ? "" : " &8(в запасе ещё " + stack.size() + ")";
        return new Result(true, "&7" + label + ": шагов &f" + queued + "&7, блоков &f" + blocks + "&7…" + tail);
    }

    public static int undoDepthOf(UUID player) {
        Deque<Snapshot> d = UNDO.get(player);
        return d == null ? 0 : d.size();
    }

    public static int redoDepthOf(UUID player) {
        Deque<Snapshot> d = REDO.get(player);
        return d == null ? 0 : d.size();
    }

    // ================================================================== guards

    /**
     * The first claim inside the box that the operator does not own, or
     * {@code null}. Operators with {@code /claims bypass} on are trusted to know
     * what they are doing and get past this.
     */
    public static Claim foreignClaimIn(ServerPlayerEntity p, String dim,
                                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (!BastionClaims.config().worldEdit.respectClaims) return null;
        if (ProtectionService.hasExplicitBypass(p)) return null;
        return ClaimManager.firstForeignOverlap(dim, minX, minY, minZ, maxX, maxY, maxZ,
                p.getGameProfile().name());
    }

    // ================================================================== helpers

    public static long volume(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    private static Result tooBig(long vol, int maxBlocks) {
        return new Result(false, "&cВыделение слишком большое: &f" + vol + "&c > лимита &f" + maxBlocks
                + "&c. Уменьши область или подними worldEdit.maxBlocks в конфиге.");
    }

    public static void forget(UUID player) {
        JOBS.remove(player);
        UNDO.remove(player);
        REDO.remove(player);
        Clipboard.forget(player);
        Selection.clear(player);
        OFFLINE_SINCE.remove(player);
    }

    // ================================================================== sessions

    /** When each currently-offline player left. Absent while they are online. */
    private static final Map<UUID, Long> OFFLINE_SINCE = new ConcurrentHashMap<>();
    private static long lastSweepMs;

    public static void markOnline(UUID player) {
        OFFLINE_SINCE.remove(player);
    }

    public static void markOffline(UUID player) {
        OFFLINE_SINCE.put(player, System.currentTimeMillis());
    }

    /**
     * Drops the sessions of players who have been gone a while.
     *
     * <p>Deliberately time-based rather than cleared on disconnect: losing a
     * selection because the client dropped for ten seconds was the single most
     * annoying thing about the old behaviour, and the clipboard and undo stack
     * are worth just as much. But an undo stack is capped at {@link
     * #UNDO_TOTAL_CAP} recorded changes — tens of megabytes for one player — and
     * nothing ever released it, so operators who never came back kept that
     * memory for the lifetime of the process.
     */
    private static void sweepSessions() {
        long now = System.currentTimeMillis();
        if (now - lastSweepMs < 60_000L) return;
        lastSweepMs = now;
        long keepMs = BastionClaims.config().worldEditSessionKeepMinutes * 60_000L;
        for (Map.Entry<UUID, Long> e : OFFLINE_SINCE.entrySet()) {
            if (now - e.getValue() > keepMs) {
                forget(e.getKey()); // also removes the OFFLINE_SINCE entry
            }
        }
    }

    private WorldEdit() {}
}
