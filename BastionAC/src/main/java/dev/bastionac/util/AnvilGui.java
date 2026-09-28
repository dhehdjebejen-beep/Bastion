package dev.bastionac.util;

import dev.bastionac.core.Alerts;
import dev.bastionac.core.TextFmt;
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
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * Server-side text input via a vanilla anvil: the player types in the rename
 * field and clicks the result to confirm. Used for exact numeric entry in the
 * settings editor and for link notes. No client mod required.
 *
 * <p>Staff-only, re-checked every tick like {@link ChestGui}. The handler runs
 * on an EMPTY forging context (there is no real anvil block), which means none
 * of vanilla's inventory-cleanup paths fire.
 *
 * <p>Nothing in this window is an item anybody should own. The two papers are
 * props, tagged with {@link #MARKER_KEY} (the same key MaxCore's and
 * BastionClaims' windows use, so MaxCore's join sweep clears all of them), and
 * the window is static: every way of touching the result slot — click, number
 * key, Q, shift — confirms and moves nothing, every other click is refused. The
 * result used to be takeable straight into the hotbar with a number key, and
 * the input paper could be picked up with the cursor.
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
                (syncId, inv, p) -> new AnvilGui(syncId, inv, initial, onConfirm), TextFmt.literal(title)));
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
    public boolean canUse(PlayerEntity player) {
        return player instanceof ServerPlayerEntity sp && !sp.isRemoved() && Alerts.isAdmin(sp);
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
        String shown = cur.isEmpty() ? "&8(введите значение)" : "&f" + cur;
        result.set(DataComponentTypes.CUSTOM_NAME, TextFmt.literal("&a✔ Применить: " + shown).styled(s -> s.withItalic(false)));
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
                && player instanceof ServerPlayerEntity sp && canUse(sp)) {
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

    /** Shift-click must not move the marker items around. */
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
                ItemStack stack = this.input.removeStack(i);
                if (!stack.isEmpty() && !isProp(stack)) sp.getInventory().offerOrDrop(stack);
            }
            if (this.output != null) this.output.setStack(0, ItemStack.EMPTY);
            var inv = sp.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                if (isProp(inv.getStack(i))) inv.setStack(i, ItemStack.EMPTY);
            }
        }
        super.onClosed(player);
    }

    private static Text plain(String s) {
        return TextFmt.literal("&f" + s).styled(st -> st.withItalic(false));
    }
}
