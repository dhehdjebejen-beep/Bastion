package dev.bastionac.gui;

import com.mojang.authlib.GameProfile;
import dev.bastionac.core.TextFmt;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Shared item/text builders for the BastionAC admin panel. */
final class GuiItems {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

    static Text label(String s) {
        return TextFmt.literal(s).styled(st -> st.withItalic(false));
    }

    static ItemStack item(Item base, String name, String... lore) {
        ItemStack stack = new ItemStack(base);
        stack.set(DataComponentTypes.CUSTOM_NAME, label(name));
        if (lore.length > 0) {
            List<Text> lines = new ArrayList<>();
            for (String l : lore) lines.add(label(l == null || l.isEmpty() ? " " : l));
            stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        }
        return stack;
    }

    static ItemStack item(Item base, String name, List<String> lore) {
        return item(base, name, lore.toArray(new String[0]));
    }

    static ItemStack head(String playerName, String title, String... lore) {
        ItemStack stack = item(Items.PLAYER_HEAD, title, lore);
        GameProfile profile = new GameProfile(Uuids.getOfflinePlayerUuid(playerName), playerName);
        stack.set(DataComponentTypes.PROFILE, ProfileComponent.ofStatic(profile));
        return stack;
    }

    static ItemStack head(String playerName, String title, List<String> lore) {
        return head(playerName, title, lore.toArray(new String[0]));
    }

    static ItemStack filler() {
        return item(Items.GRAY_STAINED_GLASS_PANE, "&0");
    }

    static ItemStack back(String where) {
        return item(Items.ARROW, "&7← Назад", "&8" + where);
    }

    /** Compact "time ago" for history entries. */
    static String ago(long deltaMs) {
        long s = Math.max(0, deltaMs) / 1000;
        if (s < 60) return s + "с";
        long m = s / 60;
        if (m < 60) return m + "м";
        long h = m / 60;
        if (h < 24) return h + "ч";
        return (h / 24) + "д";
    }

    static String stamp(long epochMs) {
        return STAMP.format(Instant.ofEpochMilli(epochMs));
    }

    private GuiItems() {}
}
