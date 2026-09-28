package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import net.minecraft.network.message.MessageType;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a connection that has not logged in may read: BastionAuth's own lines,
 * and nothing else.
 *
 * <p>The firewall has always stopped what a frozen player <em>sends</em>. What
 * the server sent <em>to</em> them went out untouched: a transfer notice, the
 * bank's interest statement, a reply to a report, the news, every other
 * player's chat. On an offline server the login screen is all that separates
 * "typed somebody's name" from "is that person", so all of it was readable by
 * whoever typed the name, before any password. Other mods cannot be expected
 * to check — there are dozens of places that broadcast or notify — so the one
 * gate sits here, on the way out of the connection.
 *
 * <ul>
 *   <li><b>Chat lines are held, not lost.</b> System messages and chat from
 *       other players are queued per connection and delivered, in order, the
 *       moment the account is proven. If the connection never logs in, they
 *       are dropped with it.</li>
 *   <li><b>Player chat is stopped before vanilla numbers it.</b> Each chat
 *       packet carries a running index the client checks; cancelling the
 *       packet after it was numbered would kick the player at the next chat
 *       line. The held copy goes out later as an unsigned chat line with the
 *       same chat type, so it looks the same.</li>
 *   <li><b>Titles and the action bar are dropped.</b> They are momentary by
 *       nature — a title from a minute ago replayed after login would only
 *       confuse.</li>
 *   <li><b>BastionAuth's own lines pass.</b> Everything BastionAuth tells a
 *       player goes through {@link #say} / {@link #packet}, and a whitelisted
 *       command run by a frozen player (its own feedback and errors) runs
 *       inside {@link #enter}. Only lines addressed to <em>that</em> player
 *       pass: a broadcast that happens during someone's {@code /login} still
 *       waits for everyone else who is frozen.</li>
 * </ul>
 */
public final class ChatGate {

    /** Per connection; a player who waits long enough to exceed it loses the oldest lines. */
    static final int MAX_HELD = 200;

    enum Kind { PASS, HOLD, DROP }

    private static final Map<UUID, Held> HELD = new ConcurrentHashMap<>();

    /** The player BastionAuth is addressing on this thread; lines to them pass. */
    private static final ThreadLocal<UUID> SPEAKING_TO = new ThreadLocal<>();

    private ChatGate() {}

    // ------------------------------------------------------------ BastionAuth's own voice

    /** A BastionAuth line to a player who may still be frozen. */
    public static void say(ServerPlayerEntity player, Text text, boolean overlay) {
        speak(player, () -> player.sendMessage(text, overlay));
    }

    /** A BastionAuth packet (title, subtitle) to a player who may still be frozen. */
    public static void packet(ServerPlayerEntity player, Packet<?> packet) {
        speak(player, () -> player.networkHandler.sendPacket(packet));
    }

    public static void speak(ServerPlayerEntity player, Runnable action) {
        UUID previous = enter(player.getUuid());
        try {
            action.run();
        } finally {
            exit(previous);
        }
    }

    /** Opens a scope in which lines to {@code who} pass; returns what {@link #exit} restores. */
    public static UUID enter(UUID who) {
        UUID previous = SPEAKING_TO.get();
        SPEAKING_TO.set(who);
        return previous;
    }

    public static void exit(UUID previous) {
        if (previous == null) SPEAKING_TO.remove();
        else SPEAKING_TO.set(previous);
    }

    // ------------------------------------------------------------ the gate

    /**
     * Outbound hook for every packet to a player. Returns true when the packet
     * must not go out now (it was held or dropped).
     */
    public static boolean intercept(ServerPlayerEntity player, Packet<?> packet) {
        Kind kind = kind(packet);
        if (kind == Kind.PASS) return false;
        if (!BastionAuth.isBlocked(player)) return false;
        if (player.getUuid().equals(SPEAKING_TO.get())) return false;
        if (kind == Kind.HOLD) hold(player.getUuid(), packet);
        return true;
    }

    /**
     * Player chat, before vanilla numbers the packet. Returns true when it was
     * held; the caller cancels vanilla's send.
     */
    public static boolean interceptChat(ServerPlayerEntity player, Text content, MessageType.Parameters params) {
        if (!BastionAuth.isBlocked(player)) return false;
        if (player.getUuid().equals(SPEAKING_TO.get())) return false;
        hold(player.getUuid(), new ProfilelessChatMessageS2CPacket(content, params));
        return true;
    }

    static Kind kind(Packet<?> packet) {
        if (packet instanceof GameMessageS2CPacket message) return message.overlay() ? Kind.DROP : Kind.HOLD;
        if (packet instanceof ProfilelessChatMessageS2CPacket) return Kind.HOLD;
        if (packet instanceof TitleS2CPacket || packet instanceof SubtitleS2CPacket
                || packet instanceof OverlayMessageS2CPacket) {
            return Kind.DROP;
        }
        return Kind.PASS;
    }

    private static void hold(UUID uuid, Packet<?> packet) {
        HELD.computeIfAbsent(uuid, k -> new Held()).add(packet);
    }

    // ------------------------------------------------------------ after login

    /**
     * Delivers what was held, in order, after a header line. Called the moment
     * the account is proven (server thread), after the "you are in" line.
     * {@code header} gets the number of lines and the number that did not fit.
     */
    public static void release(ServerPlayerEntity player, Function<int[], List<Text>> header) {
        Held held = HELD.remove(player.getUuid());
        if (held == null) return;
        List<Packet<?>> packets;
        int dropped;
        synchronized (held) {
            packets = new ArrayList<>(held.packets);
            dropped = held.dropped;
        }
        if (packets.isEmpty()) return;
        for (Text line : header.apply(new int[] {packets.size(), dropped})) {
            player.networkHandler.sendPacket(new GameMessageS2CPacket(line, false));
        }
        for (Packet<?> packet : packets) player.networkHandler.sendPacket(packet);
    }

    /**
     * Safety net, once a second: a player who became authenticated by a path
     * that did not call {@link #release} still gets their lines.
     */
    public static void releaseStragglers(MinecraftServer server, Function<int[], List<Text>> header) {
        if (HELD.isEmpty()) return;
        for (UUID uuid : List.copyOf(HELD.keySet())) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p == null) {
                HELD.remove(uuid);
            } else if (!BastionAuth.isBlocked(p)) {
                release(p, header);
            }
        }
    }

    /** The connection is gone; what it did not log in to read goes with it. */
    public static void forget(UUID uuid) {
        HELD.remove(uuid);
    }

    /** For tests: how many lines are waiting for this player. */
    static int heldCount(UUID uuid) {
        Held held = HELD.get(uuid);
        if (held == null) return 0;
        synchronized (held) {
            return held.packets.size();
        }
    }

    static final class Held {
        final ArrayDeque<Packet<?>> packets = new ArrayDeque<>();
        int dropped;

        synchronized void add(Packet<?> packet) {
            if (packets.size() >= MAX_HELD) {
                packets.pollFirst();
                dropped++;
            }
            packets.addLast(packet);
        }
    }
}
