package dev.bastionac.core;

import dev.bastionac.BastionAC;
import dev.bastionac.telemetry.Telemetry;
import dev.bastionac.telemetry.TelemetryFormat;
import net.minecraft.block.Block;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * Vanilla refusing a client's move — and what a loop of it means.
 *
 * <p>When a client claims a position the server will not accept (it moved
 * into a block the server has, or too far in one packet), vanilla teleports
 * it back. A live kick on 2026-10-08 was one such loop: standing still, the
 * client let him fall into the floor, the server put him back, his client
 * fell again — three ticks a cycle, for eleven seconds. Each put-back made
 * the client answer with an extra move packet, and PacketOrder (which then
 * still lost track of short teleports) read the pairs as cheating.
 *
 * <p>The loop itself has exactly two causes, and the server can tell them
 * apart by asking:
 * <ul>
 *   <li><b>a desynced block</b> — the client is missing a block the server
 *       has (a lost update, a mis-predicted break). Honest, and it ends the
 *       moment the client is told what is really there;</li>
 *   <li><b>NoClip / Phase / VClip</b> — the client knows the block and walks
 *       through it on purpose. Telling it again changes nothing.</li>
 * </ul>
 * So after three refusals at one spot the blocks around the player are sent
 * again; if the client keeps falling into them after that has had time to
 * arrive, it is not a desync. That moment goes to the analyzer (Argus) as a
 * suspicious episode — never a ban by itself, the owner's 50/50 call stands:
 * the analyzer looks at the whole session around it.
 */
public final class CorrectionTracker {

    /** Refusals at one spot before the blocks are re-sent. */
    static final int RESYNC_AFTER = 3;
    /** Refusals into a block after the re-send has arrived that make it an episode. */
    static final int EPISODE_AFTER_RESYNC = 6;
    /** One spot: refusals within this radius (blocks) and this gap (ms) form one loop. */
    static final double SPOT_RADIUS = 1.5;
    static final long LOOP_GAP_MS = 2_000;

    private CorrectionTracker() {}

    /** Server thread, at vanilla's own requestTeleport inside onPlayerMove. */
    public static void onVanillaCorrection(ServerPlayerEntity player, PlayerData d, PlayerMoveC2SPacket packet) {
        try {
            long now = System.currentTimeMillis();
            World world = player.getEntityWorld();
            double cx = packet.getX(player.getX()), cy = packet.getY(player.getY()), cz = packet.getZ(player.getZ());
            Box claimed = player.getDimensions(player.getPose()).getBoxAt(cx, cy, cz).contract(1.0E-5);
            boolean collides = !world.isSpaceEmpty(player, claimed);
            BlockPos feet = BlockPos.ofFloored(cx, cy - 0.01, cz);
            int feetState = Block.getRawIdFromState(world.getBlockState(feet));
            Telemetry.correction(player, collides ? TelemetryFormat.CORR_COLLIDE : TelemetryFormat.CORR_OTHER,
                    cx, cy, cz, feetState);

            double sx = player.getX(), sy = player.getY(), sz = player.getZ();
            double ddx = sx - d.corrX, ddy = sy - d.corrY, ddz = sz - d.corrZ;
            boolean sameLoop = now - d.corrLastMs <= LOOP_GAP_MS
                    && ddx * ddx + ddy * ddy + ddz * ddz <= SPOT_RADIUS * SPOT_RADIUS;
            if (!sameLoop) {
                d.corrStreak = 0;
                d.corrCollideStreak = 0;
                d.corrResynced = false;
                d.corrAfterResync = 0;
                d.corrX = sx;
                d.corrY = sy;
                d.corrZ = sz;
            }
            d.corrLastMs = now;
            d.corrStreak++;
            if (collides) d.corrCollideStreak++;

            if (collides && !d.corrResynced && d.corrCollideStreak >= RESYNC_AFTER) {
                resync(player, claimed, sx, sy, sz);
                d.corrResynced = true;
                int ping = player.networkHandler == null ? 0 : Math.max(0, player.networkHandler.getLatency());
                d.corrResyncAtMs = now + ping + 200;
                Telemetry.acAction(player.getUuid(), TelemetryFormat.ACT_RESYNC,
                        "блоки у ног отправлены заново после " + d.corrCollideStreak + " откатов");
            } else if (collides && d.corrResynced && now >= d.corrResyncAtMs) {
                d.corrAfterResync++;
                if (d.corrAfterResync == EPISODE_AFTER_RESYNC && now - d.corrReportedMs > 60_000) {
                    d.corrReportedMs = now;
                    String block = world.getBlockState(feet).getBlock().getName().getString();
                    String detail = String.format(java.util.Locale.ROOT,
                            "клиент %d раз подряд уходит в блок (%s) у %d %d %d, хотя блоки ему отправлены заново — Phase/NoClip или зависший клиент",
                            d.corrCollideStreak, block, MathHelper.floor(sx), MathHelper.floor(sy), MathHelper.floor(sz));
                    AcJournal.write("episode", d.name, player.getUuidAsString(), "phase", 0, detail, null);
                    AttackChainDetector.probe(new ChainPlayerAdapter(player), "phase", detail);
                    dev.bastionac.argus.Argus.signal(player, dev.bastionac.argus.Signal.Kind.CORRECTION_LOOP, detail);
                }
            }
            // A long loop that is not about blocks at all (moved too quickly,
            // over and over): worth a look, not a verdict.
            if (!collides && d.corrStreak == 40 && now - d.corrReportedMs > 60_000) {
                d.corrReportedMs = now;
                String detail = "сервер 40 раз подряд отклонил движение («слишком быстро/неверно»), не из-за блоков";
                AcJournal.write("episode", d.name, player.getUuidAsString(), "correction", 0, detail, null);
                dev.bastionac.argus.Argus.signal(player, dev.bastionac.argus.Signal.Kind.CORRECTION_LOOP, detail);
            }
        } catch (Throwable t) {
            BastionAC.LOGGER.debug("CorrectionTracker failed: {}", t.toString());
        }
    }

    /** Sends the client the real state of every block around its feet and the box it claimed. */
    private static void resync(ServerPlayerEntity player, Box claimed, double sx, double sy, double sz) {
        if (player.networkHandler == null) return;
        World world = player.getEntityWorld();
        Box around = claimed.union(player.getBoundingBox()).expand(0.5, 1.0, 0.5);
        int x0 = MathHelper.floor(around.minX), x1 = MathHelper.floor(around.maxX);
        int y0 = MathHelper.floor(around.minY), y1 = MathHelper.floor(around.maxY);
        int z0 = MathHelper.floor(around.minZ), z1 = MathHelper.floor(around.maxZ);
        int sent = 0;
        for (BlockPos pos : BlockPos.iterate(x0, y0, z0, x1, y1, z1)) {
            if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos.toImmutable(), world.getBlockState(pos)));
            if (++sent >= 64) break;
        }
    }
}
