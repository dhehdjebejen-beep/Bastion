package dev.bastionauth.core;

import dev.bastionauth.BastionAuth;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides a frozen player's inventory from the client until they prove the
 * account is theirs.
 *
 * <h2>The leak</h2>
 * On an offline-mode server the login screen is the only thing between typing
 * a name and being that player. Everything else about the account was already
 * on screen before the password: pressing E showed the real inventory — every
 * tool, every stack of diamonds, the shulker box someone was carrying home. An
 * impostor did not need to guess a password to learn what was worth taking; a
 * curious one did not need to do anything at all.
 *
 * <h2>The fix, and why it is only a view</h2>
 * The server's inventory is never touched. What changes is the one packet the
 * client renders from: a masked copy is sent once, with every slot empty, and
 * the real contents are re-synced the moment authentication succeeds. Nothing
 * is moved, nothing can be lost, and a connection that never logs in simply
 * disconnects with the mask still in place.
 *
 * <p>The mask holds because {@code sendContentUpdates} only transmits slots
 * that differ from what the handler <em>believes</em> the client has — and the
 * handler still believes it sent the real ones. So no later tick quietly
 * reveals them.
 *
 * <h2>The agreement volumes live only here</h2>
 * Until the agreement is accepted the view carries the volumes in the first
 * hotbar slots — and they exist nowhere else. They used to be real items put in
 * the first free slots of the real inventory, which for anyone carrying things
 * meant somewhere in the backpack: the mask then showed three books scattered
 * among empty slots, a frozen player cannot move items or select a backpack
 * slot, and a book that cannot be held cannot be read. Drawing them into the
 * view instead puts volume I in slot 1 for everybody, touches nothing the
 * player owns, and never drops a book at the feet of someone with a full bag.
 *
 * <p>Reading works without a real item because of how the client opens a
 * written book: the server only says "open the book in this hand", and the
 * client opens the stack <em>it</em> has there — which is the one from this
 * view. {@link PacketFirewall} sends exactly that on a right-click.
 */
public final class InventoryMask {

    /**
     * Blanks the view for one frozen connection, with the agreement volumes in
     * the first hotbar slots while the agreement is still owed.
     */
    public static void apply(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null) return;
        try {
            var handler = player.playerScreenHandler;
            AuthManager manager = BastionAuth.manager();
            List<ItemStack> books = manager != null && manager.showsAgreementBooks(player.getUuid())
                    ? AgreementManager.bookSet() : List.of();
            List<ItemStack> masked = layout(handler.slots.size(), ItemStack.EMPTY, books);
            for (int i = 0; i < masked.size(); i++) {
                if (!masked.get(i).isEmpty()) masked.set(i, masked.get(i).copy());
            }
            player.networkHandler.sendPacket(new InventoryS2CPacket(
                    handler.syncId, handler.getRevision(), masked, ItemStack.EMPTY));
        } catch (Throwable t) {
            // A failure here must never cost anybody their login; it costs
            // privacy, which is worth a loud line and nothing more.
            BastionAuth.LOGGER.warn("Could not mask the inventory of {}: {}",
                    player.getGameProfile().name(), t.toString());
        }
    }

    /**
     * Puts the real inventory back on screen. Called the moment the player is
     * authenticated — including on a resumed session, which skips the password
     * but never skips this.
     */
    public static void reveal(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null) return;
        try {
            player.playerScreenHandler.syncState();
        } catch (Throwable t) {
            BastionAuth.LOGGER.warn("Could not restore the inventory view of {}: {}",
                    player.getGameProfile().name(), t.toString());
        }
    }

    /**
     * The masked slot list, pure: {@code empty} everywhere, the given items from
     * the first hotbar slot on. Slot numbers are the player screen handler's —
     * the hotbar starts at {@link PlayerScreenHandler#HOTBAR_START}, not at 0
     * (0–8 are crafting and armour).
     */
    static <T> List<T> layout(int slotCount, T empty, List<T> hotbarItems) {
        List<T> out = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) out.add(empty);
        for (int i = 0; i < hotbarItems.size() && i < 9; i++) {
            int slot = HOTBAR_START + i;
            if (slot < slotCount) out.set(slot, hotbarItems.get(i));
        }
        return out;
    }

    /** {@link PlayerScreenHandler#HOTBAR_START}, kept as a literal so {@link #layout} is testable without Minecraft. */
    static final int HOTBAR_START = 36;

    private InventoryMask() {}
}
