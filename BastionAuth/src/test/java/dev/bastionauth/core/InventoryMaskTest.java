package dev.bastionauth.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where the agreement volumes are drawn in the masked view. Strings stand in
 * for item stacks: the layout is pure, and the slot numbers are what matters.
 */
class InventoryMaskTest {

    /** The player screen handler: 5 crafting, 4 armour, 27 backpack, 9 hotbar, 1 offhand. */
    private static final int PLAYER_SLOTS = 46;

    @Test
    void volumesTakeTheFirstHotbarSlotsWhateverThePlayerCarries() {
        List<String> view = InventoryMask.layout(PLAYER_SLOTS, "-", List.of("I", "II", "III"));
        assertEquals(PLAYER_SLOTS, view.size());
        // Hotbar slot 1 on screen is handler slot 36 — not 0, which is the
        // crafting output. Getting this wrong puts the books in the crafting grid.
        assertEquals("I", view.get(36));
        assertEquals("II", view.get(37));
        assertEquals("III", view.get(38));
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            if (i < 36 || i > 38) assertEquals("-", view.get(i), "slot " + i + " must be blank");
        }
    }

    @Test
    void withoutVolumesEverythingIsBlank() {
        List<String> view = InventoryMask.layout(PLAYER_SLOTS, "-", List.of());
        for (String s : view) assertEquals("-", s);
    }

    @Test
    void neverSpillsPastTheHotbar() {
        List<String> many = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        List<String> view = InventoryMask.layout(PLAYER_SLOTS, "-", many);
        assertEquals("9", view.get(44), "the ninth hotbar slot");
        assertEquals("-", view.get(45), "the offhand stays blank");
    }

    @Test
    void theHotbarConstantMatchesMinecraft() {
        // The literal exists so the layout is testable without Minecraft; this
        // is the one line that ties it to the real handler.
        assertEquals(net.minecraft.screen.PlayerScreenHandler.HOTBAR_START, InventoryMask.HOTBAR_START);
    }
}
