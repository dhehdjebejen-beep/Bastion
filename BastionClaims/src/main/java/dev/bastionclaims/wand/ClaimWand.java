package dev.bastionclaims.wand;

import dev.bastionclaims.config.ClaimConfig;
import dev.bastionclaims.util.Fmt;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The selection wand: a named wooden axe carrying a private NBT marker so that
 * ordinary wooden axes (used for chopping or fighting) are never mistaken for
 * a selection tool. Left-click a block = corner 1, right-click = corner 2.
 */
public final class ClaimWand {

    private static final String MARKER = "bastionclaims_wand";

    public static ItemStack create(ClaimConfig cfg) {
        Item base = Items.WOODEN_AXE;
        try {
            Item resolved = Registries.ITEM.get(Identifier.of(cfg.wandItem));
            if (resolved != Items.AIR) base = resolved;
        } catch (Exception ignored) {
        }
        ItemStack stack = new ItemStack(base);
        stack.set(DataComponentTypes.CUSTOM_NAME, Fmt.label("&b🪓 Приват-топор"));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                Fmt.label("&7ЛКМ по блоку — &fточка 1"),
                Fmt.label("&7ПКМ по блоку — &fточка 2"),
                Fmt.label("&8затем &f/claim create <название>"))));

        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean(MARKER, true);
        stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));
        return stack;
    }

    public static boolean isWand(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.copyNbt().contains(MARKER);
    }

    /** Whether the player already carries a wand somewhere in their inventory. */
    public static boolean has(ServerPlayerEntity player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (isWand(inv.getStack(i))) return true;
        }
        return false;
    }

    /**
     * Result of asking for a wand: either it was handed over, or the reason it
     * was not. Rate limiting lives here rather than in the command so the GUI
     * button and {@code /claim wand} cannot drift apart.
     */
    public record Give(boolean given, String message) {}

    private record Budget(int used, long lastMs) {}

    private static final Map<UUID, Budget> BUDGETS = new ConcurrentHashMap<>();

    /**
     * Hands out a wand, subject to two limits. The wand is a normal item, so
     * without them {@code /claim wand} in a loop fills an inventory (and then
     * the floor) with axes.
     *
     * <ul>
     *   <li>Already holding one? Nothing is created — a second wand does nothing
     *       a first one cannot.</li>
     *   <li>Otherwise a short burst is free (losing a wand in lava twice in a row
     *       must not be punished), after which one wand per cooldown. The burst
     *       refills once the player has gone quiet for a while.</li>
     * </ul>
     */
    public static Give give(ServerPlayerEntity player, ClaimConfig cfg) {
        if (has(player)) {
            return new Give(false, "&7Топор уже есть в инвентаре &8(ЛКМ — точка 1, ПКМ — точка 2)");
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUuid();
        Budget b = BUDGETS.get(id);
        long resetMs = cfg.wandBurstResetMinutes * 60_000L;
        int used = (b == null || now - b.lastMs() > resetMs) ? 0 : b.used();

        if (used >= cfg.wandFreeBurst) {
            long waitMs = cfg.wandCooldownSeconds * 1000L - (now - b.lastMs());
            if (waitMs > 0) {
                long seconds = (waitMs + 999) / 1000;
                return new Give(false, "&cСлишком часто. Следующий топор через &f" + seconds + "&c с.");
            }
        }
        BUDGETS.put(id, new Budget(used + 1, now));
        player.getInventory().offerOrDrop(create(cfg));
        return new Give(true, null);
    }

    public static void forget(UUID player) {
        BUDGETS.remove(player);
    }

    private ClaimWand() {}
}
