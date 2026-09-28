package dev.bastionac.checks;

import dev.bastionac.BastionAC;
import dev.bastionac.core.BanManager;
import dev.bastionac.core.PlayerData;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protocol-level abuse filters: things a vanilla client can never produce, so
 * they are judged without heuristics and can act on the spot.
 *
 * <ul>
 *   <li><b>Book/NBT bombs</b> — an edit-book packet outside the limits the
 *       vanilla client itself enforces ({@code WritableBookContentComponent}:
 *       at most 100 pages of at most 1024 characters). A legitimate 100-page
 *       diary is ~200 KB on the wire and passes; a single page over the
 *       client's own cap is a crafted packet. The old rule capped the whole
 *       book at 32 KB and permanently banned anyone who filled 17 pages.</li>
 *   <li><b>Book flood</b> — more than a handful of book edits in a short
 *       window is a BookBot: the vanilla screen cannot be operated that fast.
 *       Kick, not ban: it is spam, not a crash.</li>
 *   <li><b>Creative-slot smuggling</b> — a SetCreativeModeSlot packet from a
 *       player who is not in creative. A vanilla client never sends it outside
 *       creative — <em>except</em> for the ping's worth of packets already in
 *       flight when the server changes the game mode under it. Those are
 *       dropped silently inside a grace window after any game-mode change;
 *       outside it the packet is a crafted one.</li>
 *   <li><b>Packet flood</b> — a rolling per-player rate over the tapped C2S
 *       play packets. Sustained over a window, it is a DoS attempt regardless
 *       of content.</li>
 * </ul>
 */
public final class PacketGuard {

    /** Vanilla client limits (WritableBookContentComponent). */
    private static final int BOOK_MAX_PAGES = 100;
    private static final int BOOK_MAX_PAGE_CHARS = 1024;
    /** Book edits inside the window that mean a bot (vanilla: one per screen close). */
    private static final long BOOK_FLOOD_WINDOW_MS = 10_000;
    private static final int BOOK_FLOOD_COUNT = 6;
    /** Ticks after a game-mode change during which creative packets are dropped without judgement. */
    private static final long GAMEMODE_GRACE_TICKS = 100;
    /** Flood window (ms) and the packet count within it that means an attack. */
    private static final long FLOOD_WINDOW_MS = 2_000;
    private static final int FLOOD_PACKETS = 400;

    /** Arrival times of play packets, per player, for the flood window. */
    private static final Map<UUID, Deque<Long>> ARRIVALS = new ConcurrentHashMap<>();
    /** Arrival times of book edits, per player, for the BookBot window. */
    private static final Map<UUID, Deque<Long>> BOOK_EDITS = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ api

    /** Called for every tapped play C2S packet from the handler mixin (server thread). */
    public static void onAnyPacket(ServerPlayerEntity player) {
        if (player == null) return;
        Deque<Long> hits = ARRIVALS.computeIfAbsent(player.getUuid(), u -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (hits) {
            hits.addLast(now);
            while (!hits.isEmpty() && now - hits.peekFirst() > FLOOD_WINDOW_MS) hits.pollFirst();
            if (hits.size() > FLOOD_PACKETS) {
                hits.clear();
                BastionAC.LOGGER.warn("[PacketGuard] flood from {}: >{} packets/{}ms — dropping connection",
                        player.getGameProfile().name(), FLOOD_PACKETS, FLOOD_WINDOW_MS);
                BanManager.catastrophicBan(player, "packet-flood",
                        FLOOD_PACKETS + "+ packets in " + FLOOD_WINDOW_MS + "ms");
            }
        }
    }

    /**
     * Book edit. Two rules: the vanilla client's own page/length caps (a
     * crafted packet if exceeded — catastrophic ladder), and an edit rate no
     * human can reach (BookBot — kick + alert).
     * @return true to cancel the packet
     */
    public static boolean onBookUpdate(ServerPlayerEntity player, BookUpdateC2SPacket packet) {
        if (player == null || packet == null) return false;
        List<String> pages = packet.pages();
        int pageCount = pages == null ? 0 : pages.size();
        int longest = 0;
        long total = 0;
        if (pages != null) {
            for (String page : pages) {
                int len = page == null ? 0 : page.length();
                longest = Math.max(longest, len);
                total += len;
            }
        }
        if (pageCount > BOOK_MAX_PAGES || longest > BOOK_MAX_PAGE_CHARS) {
            BastionAC.LOGGER.warn("[PacketGuard] book bomb from {}: {} pages, longest page {} chars (~{} chars total) — permanent ban",
                    player.getGameProfile().name(), pageCount, longest, total);
            BanManager.catastrophicBan(player, "book-nbt-bomb",
                    pageCount + " pages, longest " + longest + " chars (vanilla caps: 100 × 1024)");
            return true;
        }
        Deque<Long> edits = BOOK_EDITS.computeIfAbsent(player.getUuid(), u -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        boolean flood;
        synchronized (edits) {
            edits.addLast(now);
            while (!edits.isEmpty() && now - edits.peekFirst() > BOOK_FLOOD_WINDOW_MS) edits.pollFirst();
            flood = edits.size() > BOOK_FLOOD_COUNT;
            if (flood) edits.clear();
        }
        if (flood) {
            BastionAC.LOGGER.warn("[PacketGuard] book flood from {}: >{} edits/{}s — kicked (BookBot)",
                    player.getGameProfile().name(), BOOK_FLOOD_COUNT, BOOK_FLOOD_WINDOW_MS / 1000);
            BanManager.kickWithAlert(player, "book-flood",
                    BOOK_FLOOD_COUNT + "+ book edits in " + BOOK_FLOOD_WINDOW_MS / 1000 + "s");
            return true;
        }
        return false;
    }

    /**
     * Creative-slot packet outside creative mode.
     * @return true to cancel the packet
     */
    public static boolean onCreativeSlot(ServerPlayerEntity player, CreativeInventoryActionC2SPacket packet) {
        if (player == null || packet == null) return false;
        if (player.isCreative()) return false;   // legal use
        // A vanilla client keeps sending creative packets for a ping's worth
        // of ticks after the server switched its mode (another admin's
        // /gamemode, build mode ending, a plugin). Drop those without a
        // verdict; only a packet well outside that window is crafted.
        PlayerData d = BastionAC.data(player);
        long tick = BastionAC.serverTick();
        if (d == null || tick - d.gameModeChangedTick <= GAMEMODE_GRACE_TICKS || d.isFullyExempt(tick)) {
            return true;
        }
        BastionAC.LOGGER.warn("[PacketGuard] creative-slot smuggle from {} outside creative mode — permanent ban",
                player.getGameProfile().name());
        BanManager.catastrophicBan(player, "creative-slot-smuggle",
                "SetCreativeModeSlot outside creative");
        return true;
    }

    /** Estimated serialized size of an item stack's components, for callers
     *  that want a cheap sanity check before deeper processing. */
    public static int roughStackSize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        int nbt = 0;
        var comps = stack.getComponents();
        if (comps != null) {
            nbt = comps.size() * 64;   // rough upper bound per component key
        }
        return nbt + 16;
    }

    public static void forget(UUID id) {
        ARRIVALS.remove(id);
        BOOK_EDITS.remove(id);
    }

    private PacketGuard() {}
}
