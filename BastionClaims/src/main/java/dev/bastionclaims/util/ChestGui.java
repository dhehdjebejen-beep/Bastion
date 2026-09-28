package dev.bastionclaims.util;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Server-side chest menu: items are buttons, every click is cancelled and
 * dispatched to a handler, nothing can ever be taken out. Works with any
 * vanilla-protocol client — no client mod required. (Ported from MaxCore.)
 */
public final class ChestGui {

    @FunctionalInterface
    public interface Click {
        void onClick(ServerPlayerEntity player);
    }

    private final Text title;
    private final int rows;
    private final ItemStack[] items;
    private final Click[] handlers;

    public ChestGui(Text title, int rows) {
        this.title = title;
        this.rows = Math.max(1, Math.min(6, rows));
        this.items = new ItemStack[this.rows * 9];
        this.handlers = new Click[this.rows * 9];
    }

    public ChestGui set(int slot, ItemStack item, Click onClick) {
        if (slot >= 0 && slot < items.length) {
            items[slot] = item;
            handlers[slot] = onClick;
        }
        return this;
    }

    /** Fills every empty slot with a decorative filler item. */
    public ChestGui fill(ItemStack filler) {
        for (int i = 0; i < items.length; i++) {
            if (items[i] == null) items[i] = filler.copy();
        }
        return this;
    }

    public void open(ServerPlayerEntity player) {
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
        private final ChestGui gui;

        Handler(int syncId, PlayerInventory playerInventory, ChestGui gui) {
            super(typeFor(gui.rows), syncId, playerInventory, new SimpleInventory(gui.rows * 9), gui.rows);
            this.gui = gui;
            for (int i = 0; i < gui.items.length; i++) {
                if (gui.items[i] != null) getInventory().setStack(i, gui.items[i]);
            }
        }

        @Override
        public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
            // Static GUI: never let vanilla move anything; undo client prediction.
            this.updateToClient();
            if (player instanceof ServerPlayerEntity sp
                    && slotIndex >= 0 && slotIndex < gui.handlers.length
                    && actionType != SlotActionType.QUICK_CRAFT) {
                ChestGui.Click h = gui.handlers[slotIndex];
                if (h != null) h.onClick(sp);
            }
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        /**
         * Polled by vanilla every tick. A menu belonging to a player who is no
         * longer in the world must not keep accepting clicks — every handler
         * re-resolves the claim through {@code ClaimMenu.find}, which re-checks
         * ownership, so this only has to cover the player themselves.
         */
        @Override
        public boolean canUse(PlayerEntity player) {
            return player instanceof ServerPlayerEntity sp && !sp.isRemoved();
        }
    }
}
