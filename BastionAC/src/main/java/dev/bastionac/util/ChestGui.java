package dev.bastionac.util;

import dev.bastionac.core.Alerts;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.function.Predicate;

/**
 * Server-side chest menu: items are buttons, every click is cancelled and
 * dispatched to a handler, nothing can ever be taken out. Click handlers get a
 * {@link ClickType} so a single slot can do +/- (left/right) and big steps
 * (shift). Works on any vanilla client — no client mod required.
 *
 * <p>Permission is re-checked on every click and every tick, not just when the
 * menu is opened. The command that opens the panel gates on op, but an open
 * screen handler outlives a {@code /deop}: without the {@link #canUse} check
 * below, a demoted admin kept a fully functional moderation panel (clear own
 * VL, disable checks, ban players) for as long as they left the window open.
 *
 * <h2>Why a redraw does not open a new window</h2>
 * Every panel here is rebuilt and reopened after each click, which is how a
 * menu shows the result of pressing its own button. Done the obvious way —
 * {@code player.openHandledScreen(...)} — vanilla first closes the window it is
 * about to replace:
 *
 * <pre>
 *   ServerPlayerEntity.openHandledScreen:
 *       if (currentScreenHandler != playerScreenHandler) closeHandledScreen();
 *       ... then send OpenScreen
 * </pre>
 *
 * <p>The client answers that CLOSE with {@code setScreen(null)} →
 * {@code Mouse.lockCursor()}, and the following OPEN with
 * {@code Mouse.unlockCursor()}, which parks the hardware cursor in the middle of
 * the window. Every click through a panel therefore threw the mouse back to the
 * centre of the screen. {@code unlockCursor()} returns immediately when the
 * cursor is already unlocked, so the jump comes from the CLOSE and nothing else,
 * and the cure is to never send one:
 *
 * <ul>
 *   <li>same size and caption — the live handler keeps its syncId and only its
 *       slots are rewritten;</li>
 *   <li>same size, new caption — OPEN is re-sent with <b>the same syncId</b> and
 *       no CLOSE, then the state is force-synced into the fresh client handler;</li>
 *   <li>a different number of rows — a genuinely different window.</li>
 * </ul>
 */
public final class ChestGui {

    public enum ClickType {
        LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT;

        public boolean left()  { return this == LEFT || this == SHIFT_LEFT; }
        public boolean shift() { return this == SHIFT_LEFT || this == SHIFT_RIGHT; }
    }

    @FunctionalInterface
    public interface Click {
        void onClick(ServerPlayerEntity player, ClickType type);
    }

    private final Text title;
    private final int rows;
    private final ItemStack[] items;
    private final Click[] handlers;
    /** Who may keep this menu open and click in it. Every BastionAC menu is staff-only. */
    private Predicate<ServerPlayerEntity> access = Alerts::isAdmin;

    public ChestGui(Text title, int rows) {
        this.title = title;
        this.rows = Math.max(1, Math.min(6, rows));
        this.items = new ItemStack[this.rows * 9];
        this.handlers = new Click[this.rows * 9];
    }

    /** Overrides the default staff-only access rule. */
    public ChestGui access(Predicate<ServerPlayerEntity> access) {
        if (access != null) this.access = access;
        return this;
    }

    /** Simple button that ignores the click type. */
    public ChestGui button(int slot, ItemStack item, Runnable onClick) {
        return set(slot, item, (p, t) -> onClick.run());
    }

    public ChestGui set(int slot, ItemStack item, Click onClick) {
        if (slot >= 0 && slot < items.length) {
            items[slot] = item;
            handlers[slot] = onClick;
        }
        return this;
    }

    public ChestGui fill(ItemStack filler) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) items[i] = filler.copy();
        }
        return this;
    }

    /**
     * Shows the panel, reusing the window the player already has open whenever
     * that is possible — see the class comment for why that matters.
     */
    public void open(ServerPlayerEntity player) {
        if (player.currentScreenHandler instanceof Handler live && live.fits(this)) {
            live.adopt(this, player);
            return;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, playerInv, p) -> new Handler(syncId, playerInv, this), title));
    }

    private static ScreenHandlerType<GenericContainerScreenHandler> typeFor(int rows) {
        return switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            default -> ScreenHandlerType.GENERIC_9X6;
        };
    }

    private static final class Handler extends GenericContainerScreenHandler {
        /** Not final: a redraw swaps in the newly built panel behind the same window. */
        private ChestGui gui;

        Handler(int syncId, PlayerInventory playerInventory, ChestGui gui) {
            super(typeFor(gui.rows), syncId, playerInventory, new SimpleInventory(gui.rows * 9), gui.rows);
            this.gui = gui;
            writeSlots(gui);
        }

        /** Whether {@code next} can be shown in this window instead of a new one. */
        boolean fits(ChestGui next) {
            return gui.rows == next.rows;
        }

        void writeSlots(ChestGui source) {
            Inventory inv = getInventory();
            for (int i = 0; i < inv.size(); i++) {
                ItemStack stack = i < source.items.length ? source.items[i] : null;
                inv.setStack(i, stack == null ? ItemStack.EMPTY : stack.copy());
            }
        }

        /**
         * Takes over the window for a freshly built panel. The caption is the
         * only thing a slot update cannot change, so a new one costs a re-sent
         * OPEN — with this window's own syncId, and without the CLOSE that used
         * to move the cursor.
         */
        void adopt(ChestGui next, ServerPlayerEntity player) {
            boolean retitle = !gui.title.equals(next.title);
            gui = next;
            writeSlots(next);
            if (retitle) {
                player.networkHandler.sendPacket(new OpenScreenS2CPacket(syncId, getType(), next.title));
                syncState();
            } else {
                sendContentUpdates();
            }
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            this.updateToClient();
            if (!(player instanceof ServerPlayerEntity sp)) return;
            // Re-authorise on every click: permissions can be revoked while the
            // screen is open, and a raw ClickSlot packet needs no re-open.
            if (!canUse(sp)) {
                sp.closeHandledScreen();
                return;
            }
            if (slotIndex < 0 || slotIndex >= gui.handlers.length) return;
            if (actionType == SlotActionType.QUICK_CRAFT) return;
            Click h = gui.handlers[slotIndex];
            if (h == null) return;
            boolean shift = actionType == SlotActionType.QUICK_MOVE;
            boolean left = button == 0;
            ClickType type = shift ? (left ? ClickType.SHIFT_LEFT : ClickType.SHIFT_RIGHT)
                                   : (left ? ClickType.LEFT : ClickType.RIGHT);
            h.onClick(sp, type);
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        /**
         * Polled by vanilla every tick ({@code PlayerEntity.tick}) and by us on
         * every click, so losing permission closes the menu by itself.
         */
        @Override
        public boolean canUse(PlayerEntity player) {
            return player instanceof ServerPlayerEntity sp && !sp.isRemoved() && gui.access.test(sp);
        }
    }
}
