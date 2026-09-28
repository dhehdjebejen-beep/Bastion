package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.Action;
import dev.bastionac.core.CheckType;
import dev.bastionac.core.PlayerData;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Set;

/**
 * Client-side facts the protocol gives away for free, judged without any
 * physics: what the client says it is, how it paces its own ticks, and
 * whether it touches the inventory while running.
 *
 * <h2>BrandSpoof</h2>
 * The server accepts vanilla clients, so a brand of {@code vanilla} is not
 * suspicious by itself — most players are exactly that. What is impossible is
 * the <em>combination</em>: a client that calls itself vanilla while also
 * declaring modded plugin channels it can receive. A real vanilla client
 * registers no channels at all (verified against the live server with a raw
 * protocol client), and a modded client cannot speak Fabric's channels
 * without a mod loader. Cheat clients with a "server spoof" module rewrite
 * the brand string and forget the channels, which is precisely this
 * contradiction. An honest modded client (Sodium, Lithium) reports its real
 * brand and is never touched by this rule.
 *
 * <h2>PacketOrder</h2>
 * Since 1.21.2 the client closes every one of its ticks with
 * {@code ClientTickEnd}. Between two of those, a vanilla client sends at most
 * one movement packet — it moves once per tick by construction. Two or more
 * inside a single client tick is what Blink flushes, forced Criticals
 * (micro-hop plus settle in one tick) and NoFall packet tricks all look like.
 * This is an exact protocol invariant, not a timing heuristic, so it survives
 * any amount of lag: a lagging client sends its ticks late, never twice as
 * many moves inside one.
 *
 * <h2>InvMove</h2>
 * Vanilla zeroes movement input while a screen is open — you cannot walk and
 * sort your chest. A stream of inventory clicks arriving while the player is
 * genuinely running is the InvMove module.
 */
public final class ClientChecks {

    /** Ticks after join before brand/channels have settled enough to judge. */
    private static final long BRAND_DELAY_TICKS = 140;
    /** Channel namespaces every Fabric client has; their presence alone is the tell. */
    private static final Set<String> VANILLA_BRANDS = Set.of("vanilla", "minecraft");

    // ------------------------------------------------------------------ brand

    /** Called once per player from the tick loop, a few seconds after they join. */
    public static void checkBrand(ServerPlayerEntity player, PlayerData d) {
        if (d.brandChecked) return;
        long tick = BastionAC.serverTick();
        if (d.brandCheckTick < 0) {
            d.brandCheckTick = tick + BRAND_DELAY_TICKS;
            return;
        }
        if (tick < d.brandCheckTick) return;
        d.brandChecked = true;
        if (!BastionAC.config().check(CheckType.BRANDSPOOF.key).enabled) return;

        String brand = d.brand;
        if (brand == null || !VANILLA_BRANDS.contains(brand)) return; // honest modded client, or brand not seen

        Set<String> channels = sendableChannels(player);
        if (channels.isEmpty()) return;   // a genuine vanilla client: no channels at all

        BastionAC.flag(player, d, CheckType.BRANDSPOOF, String.format(
                "бренд «%s» при %d модовых каналах (%s)",
                brand, channels.size(), preview(channels)));
    }

    /** Plugin channels the client declared it can receive, minus vanilla's own. */
    private static Set<String> sendableChannels(ServerPlayerEntity player) {
        try {
            var ids = net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.getSendable(player);
            if (ids == null) return Set.of();
            java.util.TreeSet<String> out = new java.util.TreeSet<>();
            for (net.minecraft.util.Identifier id : ids) {
                String s = id.toString();
                if (s.startsWith("minecraft:")) continue;
                out.add(s);
            }
            return out;
        } catch (Throwable t) {
            return Set.of();
        }
    }

    private static String preview(Set<String> channels) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String c : channels) {
            if (n++ >= 3) { sb.append(", …"); break; }
            if (n > 1) sb.append(", ");
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ packet order

    /** Called on every movement packet that carries a position or rotation. */
    public static void onMovePacket(ServerPlayerEntity player, PlayerData d) {
        if (!d.sendsTickEnd) return;   // pre-1.21.2 client, or none seen yet: no invariant to test
        d.movesThisClientTick++;
    }

    /**
     * Called on every position-bearing move packet, before vanilla applies it:
     * where this client tick's packets went relative to where it started.
     */
    public static void onMovePosition(ServerPlayerEntity player, PlayerData d, double newX, double newY, double newZ) {
        if (Double.isNaN(d.tickBaseY)) d.tickBaseY = player.getY();
        if (d.tickDyCount < d.tickDys.length) d.tickDys[d.tickDyCount++] = newY - d.tickBaseY;
        if (newX == player.getX() && newY == player.getY() && newZ == player.getZ()) d.zeroMovesThisClientTick++;
    }

    /**
     * NCP step: the client climbs a full block in one tick and, to satisfy a
     * packet-by-packet checker, first reports the two heights a real jump
     * would pass through — +0.42 and +0.753 (times the step height). Wurst,
     * LiquidBounce and their forks all send exactly this pair. A vanilla
     * client sends one position a tick, so the pair itself is the signature.
     */
    static double stepHeight(double[] dys, int n) {
        if (n < 2) return 0;
        double a = dys[0], b = dys[1];
        if (a <= 0.3 || b <= a) return 0;
        double k = a / 0.42;
        double kr = Math.round(k * 2) / 2.0;   // whole or half blocks
        if (kr < 1 || Math.abs(k - kr) > 0.01) return 0;
        if (Math.abs(b - 0.753 * kr) > 0.01 * kr && Math.abs(b - 0.75 * kr) > 0.01 * kr) return 0;
        return kr;
    }

    /** Called on ClientTickEnd: closes the client's tick and judges what it contained. */
    public static void onClientTickEnd(ServerPlayerEntity player, PlayerData d) {
        long tick = BastionAC.serverTick();
        boolean first = !d.sendsTickEnd;
        d.sendsTickEnd = true;
        int moves = d.movesThisClientTick;
        d.movesThisClientTick = 0;
        int dyCount = d.tickDyCount;
        d.tickDyCount = 0;
        d.tickBaseY = Double.NaN;
        d.zeroMovesThisClientTick = 0;
        if (first) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick) || BastionAC.isAuthFrozen(player)) return;
        if (d.isMovementExempt(tick)) return;

        double step = stepHeight(d.tickDys, dyCount);
        if (step > 0) {
            BastionAC.flag(player, d, CheckType.STEP, String.format(java.util.Locale.ROOT,
                    "подъём на %.1f бл за тик через пакеты +%.3f/+%.3f (NCP-step)", step, d.tickDys[0], d.tickDys[1]));
        }

        if (moves > 1) {
            // Two moves in one client tick is already impossible; a flush of ten
            // is a Blink burst. Weight the evidence by how far past one it went.
            d.packetOrderBuffer += moves >= 4 ? 2 : 1;
            if (d.packetOrderBuffer >= 4) {
                d.packetOrderBuffer = 1;
                BastionAC.flag(player, d, CheckType.PACKETORDER,
                        moves + " move-пакетов за один клиентский тик");
            }
        } else {
            d.packetOrderBuffer = Math.max(0, d.packetOrderBuffer - 0.25);
        }
    }

    // ------------------------------------------------------------------ inventory move

    /** Called on every slot click. Returns CANCEL when the click must be refused. */
    public static Action onSlotClick(ServerPlayerEntity player, PlayerData d, ClickSlotC2SPacket packet) {
        long tick = BastionAC.serverTick();
        d.lastSlotClickTick = tick;
        if (player.isCreative() || player.isSpectator()) return Action.NONE;
        if (d.isFullyExempt(tick) || BastionAC.isServerLagging(tick) || BastionAC.isAuthFrozen(player)) return Action.NONE;
        if (d.isMovementExempt(tick) || player.hasVehicle()) return Action.NONE;
        if (!BastionAC.config().check(CheckType.INVMOVE.key).enabled) return Action.NONE;

        // Sprinting is the unambiguous half: vanilla cancels the sprint the
        // moment a screen opens, so a sprinting player with an open screen is
        // not a vanilla client. Plain walking is measured instead, and only
        // well above the speed a player keeps from released-key inertia.
        boolean sprinting = player.isSprinting();
        double moved = horizontalSince(d, player);
        if (!sprinting && moved < 0.12) {
            d.invMoveBuffer = Math.max(0, d.invMoveBuffer - 0.5);
            return Action.NONE;
        }

        d.invMoveBuffer += sprinting ? 1.5 : 1;
        if (d.invMoveBuffer >= 6) {
            d.invMoveBuffer = 2;
            if (BastionAC.flag(player, d, CheckType.INVMOVE, String.format(
                    "клик в инвентаре на ходу (%.2f бл/тик%s)", moved, sprinting ? ", спринт" : ""))) {
                return Action.CANCEL;
            }
        }
        return Action.NONE;
    }

    // ------------------------------------------------------------------ rotation range

    /**
     * A vanilla client clamps its pitch to ±90° before it ever builds a
     * packet; the server only wraps it. Wurst's Tired sends pitch up to 99°,
     * hand-made packets anything at all. One flag a second at most.
     */
    public static void onRotation(ServerPlayerEntity player, PlayerData d, float pitch) {
        if (Math.abs(pitch) <= 90.0f + 1.0e-3f) return;
        long tick = BastionAC.serverTick();
        if (player.isCreative() || player.isSpectator() || d.isFullyExempt(tick)) return;
        if (tick - d.lastBadRotationFlagTick < 20) return;
        d.lastBadRotationFlagTick = tick;
        BastionAC.flag(player, d, CheckType.BADROTATION,
                String.format(java.util.Locale.ROOT, "наклон головы %.1f° (клиент не может больше 90°)", pitch));
    }

    // ------------------------------------------------------------------ auto totem

    /** Once per tick per player: notices a totem popping by the server's own use statistic. */
    public static void tickTotem(ServerPlayerEntity player, PlayerData d) {
        int used = player.getStatHandler().getStat(
                net.minecraft.stat.Stats.USED.getOrCreateStat(net.minecraft.item.Items.TOTEM_OF_UNDYING));
        if (d.totemsUsed >= 0 && used > d.totemsUsed) {
            d.totemPopMs = System.currentTimeMillis();
            if (BastionAC.config().general.debugChecks) BastionAC.LOGGER.info("[DEBUG autototem] {} totem popped ({} used)", d.name, used);
        }
        d.totemsUsed = used;
    }

    /** Before a slot click is applied: was the off hand already a totem? */
    public static void beforeSlotClick(ServerPlayerEntity player, PlayerData d) {
        d.offhandTotemBeforeClick = player.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING);
    }

    /**
     * After a slot click: a totem that just landed in the off hand, shortly
     * after one popped. A hand has to see the pop, open the inventory, find
     * a totem and click twice — hundreds of milliseconds on top of the ping.
     * AutoTotem does it in the same tick the pop arrives.
     */
    public static void afterSlotClick(ServerPlayerEntity player, PlayerData d) {
        if (BastionAC.config().general.debugChecks) {
            BastionAC.LOGGER.info("[DEBUG autototem] {} click: before={} after={} popMs={}", d.name,
                    d.offhandTotemBeforeClick, player.getOffHandStack().getItem(), d.totemPopMs);
        }
        if (d.offhandTotemBeforeClick || d.totemPopMs < 0) return;
        if (!player.getOffHandStack().isOf(net.minecraft.item.Items.TOTEM_OF_UNDYING)) return;
        long gap = System.currentTimeMillis() - d.totemPopMs;
        d.totemPopMs = -1;   // one verdict per pop
        if (player.isCreative() || player.isSpectator()) return;
        int ping = Math.max(0, player.networkHandler.getLatency());
        long reaction = gap - ping;
        if (gap > 3_000 || reaction >= 150) {
            d.autoTotemBuffer = Math.max(0, d.autoTotemBuffer - 0.5);
            return;
        }
        d.autoTotemBuffer += 1;
        if (d.autoTotemBuffer >= 2) {
            d.autoTotemBuffer = 0.5;
            BastionAC.flag(player, d, CheckType.AUTOTOTEM, String.format(java.util.Locale.ROOT,
                    "тотем в левой руке через %d мс после срабатывания (пинг %d мс)", gap, ping));
        }
    }

    /** Horizontal distance of the player's last accepted movement sample. */
    private static double horizontalSince(PlayerData d, ServerPlayerEntity player) {
        double dx = player.getX() - d.lastX;
        double dz = player.getZ() - d.lastZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private ClientChecks() {}
}
