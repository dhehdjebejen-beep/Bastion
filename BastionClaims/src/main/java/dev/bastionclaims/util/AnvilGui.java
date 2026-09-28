package dev.bastionclaims.util;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.function.Consumer;

/**
 * Server-side text input via a vanilla anvil: the player types in the rename
 * field and clicks the result to confirm. Works on any vanilla client — no mod
 * required. (Ported from MaxCore.)
 *
 * <p>Nothing in this window is an item anybody should own. Until 1.17.3 naming
 * a claim handed the player a free paper: the name field opens empty, so the
 * input prop had no name, and the close handler told props from real items by
 * the name. The props now carry a tag ({@link #MARKER_KEY}, shared with MaxCore
 * and BastionAC — MaxCore's join sweep clears strays from all three), and the
 * window is static: every way of touching the result slot confirms and moves
 * nothing, every other click is refused.
 */
public final class AnvilGui extends AnvilScreenHandler {

    public static final String MARKER_KEY = "bastion_anvil_marker";

    private final Consumer<String> onConfirm;
    private String text = "";
    private boolean confirmed;

    private AnvilGui(int syncId, PlayerInventory inv, String initial, Consumer<String> onConfirm) {
        super(syncId, inv);
        this.onConfirm = onConfirm;
        this.text = initial == null ? "" : initial;

        ItemStack paper = prop();
        if (!this.text.isEmpty()) {
            paper.set(DataComponentTypes.CUSTOM_NAME, plain(this.text));
        }
        this.input.setStack(0, paper);
        updateResult();
    }

    public static void open(ServerPlayerEntity player, String title, String initial, Consumer<String> onConfirm) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inv, p) -> new AnvilGui(syncId, inv, initial, onConfirm), Fmt.text(title)));
    }

    private static ItemStack prop() {
        ItemStack paper = new ItemStack(Items.PAPER);
        NbtCompound tag = new NbtCompound();
        tag.putBoolean(MARKER_KEY, true);
        paper.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
        return paper;
    }

    private static boolean isProp(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isOf(Items.PAPER)) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.copyNbt().contains(MARKER_KEY);
    }

    @Override
    public boolean setNewItemName(String newName) {
        this.text = newName == null ? "" : newName;
        updateResult();
        sendContentUpdates();
        return true;
    }

    @Override
    public void updateResult() {
        String cur = text == null ? "" : text;
        ItemStack result = prop();
        String shown = cur.isEmpty() ? "&8(введите название)" : "&f" + cur;
        result.set(DataComponentTypes.CUSTOM_NAME, Fmt.text("&a✔ Создать: " + shown).styled(s -> s.withItalic(false)));
        if (this.output != null) {
            this.output.setStack(0, result);
        }
    }

    @Override
    protected boolean canTakeOutput(PlayerEntity player, boolean present) {
        return true;
    }

    /** The result slot confirms whatever the gesture; everything else is refused and resynced. */
    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex == getResultSlotIndex() && actionType != SlotActionType.QUICK_CRAFT
                && player instanceof ServerPlayerEntity sp) {
            confirm(sp);
            return;
        }
        syncState();
    }

    private void confirm(ServerPlayerEntity sp) {
        if (confirmed) return;
        confirmed = true;
        setCursorStack(ItemStack.EMPTY);
        if (this.output != null) this.output.setStack(0, ItemStack.EMPTY);
        sp.closeHandledScreen();
        onConfirm.accept(text == null ? "" : text.trim());
    }

    /** Shift-click must not move the marker items around (it used to hand them out). */
    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    /** Unreachable with the click model above; kept so no vanilla path can skip the confirm. */
    @Override
    protected void onTakeOutput(PlayerEntity player, ItemStack stack) {
        if (player instanceof ServerPlayerEntity sp) confirm(sp);
    }

    /** Props are discarded on close; anything else in the inputs goes back to the player. */
    @Override
    public void onClosed(PlayerEntity player) {
        if (isProp(getCursorStack())) setCursorStack(ItemStack.EMPTY);
        if (player instanceof ServerPlayerEntity sp) {
            for (int i = 0; i < this.input.size(); i++) {
                ItemStack left = this.input.removeStack(i);
                if (!left.isEmpty() && !isProp(left)) sp.getInventory().offerOrDrop(left);
            }
            if (this.output != null) this.output.setStack(0, ItemStack.EMPTY);
            var inv = sp.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                if (isProp(inv.getStack(i))) inv.setStack(i, ItemStack.EMPTY);
            }
        }
        super.onClosed(player);
    }

    /** Any player may name their own claim — the menu that opens this is not staff-only. */
    @Override
    public boolean canUse(PlayerEntity player) {
        return player instanceof ServerPlayerEntity sp && !sp.isRemoved();
    }

    private static net.minecraft.text.Text plain(String s) {
        return Fmt.text("&f" + s).styled(st -> st.withItalic(false));
    }
}
