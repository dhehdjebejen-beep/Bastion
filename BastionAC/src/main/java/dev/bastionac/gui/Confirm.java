package dev.bastionac.gui;

import dev.bastionac.core.TextFmt;
import dev.bastionac.util.ChestGui;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/** Reusable "are you sure?" screen for destructive moderation actions. */
final class Confirm {

    static void open(ServerPlayerEntity admin, String title, String question,
                     String yesLabel, Runnable onYes, Runnable onBack) {
        ChestGui gui = new ChestGui(TextFmt.literal(title), 3);
        gui.button(13, GuiItems.item(Items.PAPER, "&e" + question, "&8Действие требует подтверждения"), () -> {});
        gui.button(11, GuiItems.item(Items.LIME_WOOL, "&a✔ " + yesLabel, "&8клик — подтвердить"), onYes);
        gui.button(15, GuiItems.item(Items.RED_WOOL, "&c✖ Отмена", "&8клик — назад"), onBack);
        gui.fill(GuiItems.filler());
        gui.open(admin);
    }

    private Confirm() {}
}
