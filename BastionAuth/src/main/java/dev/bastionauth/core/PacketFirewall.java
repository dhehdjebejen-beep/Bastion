package dev.bastionauth.core;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.ClientOptionsC2SPacket;
import net.minecraft.network.packet.c2s.common.CommonPongC2SPacket;
import net.minecraft.network.packet.c2s.common.CookieResponseC2SPacket;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.c2s.common.KeepAliveC2SPacket;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.AcknowledgeChunksC2SPacket;
import net.minecraft.network.packet.c2s.play.AcknowledgeReconfigurationC2SPacket;
import net.minecraft.network.packet.c2s.play.AdvancementTabC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientTickEndC2SPacket;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.MessageAcknowledgmentC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerLoadedC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerSessionC2SPacket;
import net.minecraft.network.packet.c2s.play.RecipeBookDataC2SPacket;
import net.minecraft.network.packet.c2s.play.RecipeCategoryOptionsC2SPacket;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.Set;

/**
 * The pre-login packet policy, inverted from the original design: instead of
 * naming the packets a frozen player may NOT send (and missing every handler
 * vanilla gates on permission level alone — NBT queries, difficulty, game
 * mode, structure blocks…), this names the handful a frozen connection needs
 * to stay alive and log in. Everything else is dropped on the netty thread
 * before any handler runs, so a new packet in a future Minecraft version is
 * blocked by default rather than open by default.
 *
 * <p>What stays open and why:
 * <ul>
 *   <li>keep-alive / pong / cookie / resource-pack status — connection health;</li>
 *   <li>teleport confirm / chunk acks / player-loaded / tick-end — the
 *       handshakes vanilla insists on, all state-less for the world;</li>
 *   <li>chat message + acknowledgments + session — the signature chain
 *       desyncs (and kicks the player) if these are dropped; the message
 *       itself is refused by {@code ALLOW_CHAT_MESSAGE};</li>
 *   <li>both command packets — {@code /login} and {@code /register} travel
 *       here; every other command is refused by {@code CommandManagerMixin};</li>
 *   <li>client options / custom payload (brand, Fabric registry) — needed
 *       for the device fingerprint and harmless;</li>
 *   <li>custom click action — the agreement dialog's buttons;</li>
 *   <li>selected slot / close screen / recipe book / advancement tab —
 *       pure client-side UI state with no world effect;</li>
 *   <li>client status — the "Respawn" button and the statistics request. A
 *       player who quit on the death screen rejoins dead; with this packet
 *       dropped the button did nothing, and the death screen has no chat,
 *       so {@code /login} could never be typed: a lockout that only ended
 *       when an IP session happened to resume;</li>
 *   <li>use-item, but only while an agreement volume is in that hand — the
 *       written-book screen is opened by the server in reply to this packet,
 *       so dropping it made the agreement unreadable before registration
 *       (the very thing it asks players to do).</li>
 * </ul>
 * Movement is deliberately absent: the move packet is dropped AND the player
 * is snapped back to the join anchor by the caller.
 */
public final class PacketFirewall {

    private static final Set<Class<?>> ALLOWED_WHILE_FROZEN = Set.of(
            KeepAliveC2SPacket.class,
            CommonPongC2SPacket.class,
            CookieResponseC2SPacket.class,
            ResourcePackStatusC2SPacket.class,
            ClientOptionsC2SPacket.class,
            CustomPayloadC2SPacket.class,
            CustomClickActionC2SPacket.class,
            TeleportConfirmC2SPacket.class,
            AcknowledgeChunksC2SPacket.class,
            AcknowledgeReconfigurationC2SPacket.class,
            PlayerLoadedC2SPacket.class,
            ClientTickEndC2SPacket.class,
            ChatMessageC2SPacket.class,
            MessageAcknowledgmentC2SPacket.class,
            PlayerSessionC2SPacket.class,
            ChatCommandSignedC2SPacket.class,
            CommandExecutionC2SPacket.class,
            UpdateSelectedSlotC2SPacket.class,
            CloseHandledScreenC2SPacket.class,
            RecipeBookDataC2SPacket.class,
            RecipeCategoryOptionsC2SPacket.class,
            AdvancementTabC2SPacket.class,
            ClientStatusC2SPacket.class);

    private PacketFirewall() {}

    /** True when a frozen (not yet authenticated) player may send this packet. */
    public static boolean allowedWhileFrozen(Packet<?> packet, ServerPlayerEntity player) {
        if (packet == null) return false;
        // Closing a screen makes vanilla resynchronise the player's inventory,
        // which would put the real contents back on a client that is still
        // frozen. The packet stays allowed — it is harmless UI state — but the
        // mask is re-asserted after the handler has run.
        if (packet instanceof CloseHandledScreenC2SPacket && player != null) {
            var manager = dev.bastionauth.BastionAuth.manager();
            if (manager != null) manager.remaskSoon(player.getUuid());
        }
        if (ALLOWED_WHILE_FROZEN.contains(packet.getClass())) return true;
        if (packet instanceof PlayerInteractItemC2SPacket use) {
            // While the agreement is owed, the volumes exist only in the client's
            // view (see InventoryMask). "Open the book in this hand" makes the
            // client open its own stack — the volume it is holding — so nothing
            // real needs to be in the hand. An empty hand opens nothing.
            var manager = dev.bastionauth.BastionAuth.manager();
            if (manager != null && player != null && manager.showsAgreementBooks(player.getUuid())) {
                player.networkHandler.sendPacket(
                        new net.minecraft.network.packet.s2c.play.OpenWrittenBookS2CPacket(use.getHand()));
                return false;
            }
            return isAgreementBookUse(player, use.getHand());
        }
        return false;
    }

    /** Right-clicking one of the agreement volumes: the one item a frozen player may use. */
    public static boolean isAgreementBookUse(ServerPlayerEntity player, Hand hand) {
        return player != null && hand != null && AgreementManager.isAgreementBook(player.getStackInHand(hand));
    }
}
