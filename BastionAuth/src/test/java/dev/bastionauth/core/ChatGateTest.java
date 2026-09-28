package dev.bastionauth.core;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.text.Text;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** What the outbound gate holds, drops and lets through for a player who has not logged in. */
class ChatGateTest {

    @Test
    void chatLinesAreHeldForAfterLogin() {
        assertEquals(ChatGate.Kind.HOLD, ChatGate.kind(new GameMessageS2CPacket(Text.literal("Вам перевод 500 ₽"), false)));
    }

    @Test
    void momentaryLinesAreDroppedNotReplayed() {
        assertEquals(ChatGate.Kind.DROP, ChatGate.kind(new GameMessageS2CPacket(Text.literal("баланс"), true)));
        assertEquals(ChatGate.Kind.DROP, ChatGate.kind(new TitleS2CPacket(Text.literal("Майор"))));
        assertEquals(ChatGate.Kind.DROP, ChatGate.kind(new SubtitleS2CPacket(Text.literal("визит"))));
        assertEquals(ChatGate.Kind.DROP, ChatGate.kind(new OverlayMessageS2CPacket(Text.literal("hud"))));
    }

    @Test
    void titleTimingAndClearingPass() {
        // BastionAuth's own title needs its fade timing, and clearing a title reveals nothing.
        assertEquals(ChatGate.Kind.PASS, ChatGate.kind(new TitleFadeS2CPacket(10, 160, 10)));
        assertEquals(ChatGate.Kind.PASS, ChatGate.kind(new ClearTitleS2CPacket(true)));
    }

    @Test
    void aLongWaitKeepsTheNewestLinesAndCountsTheRest() {
        ChatGate.Held held = new ChatGate.Held();
        int total = ChatGate.MAX_HELD + 15;
        for (int i = 0; i < total; i++) held.add(new GameMessageS2CPacket(Text.literal("#" + i), false));
        assertEquals(ChatGate.MAX_HELD, held.packets.size());
        assertEquals(15, held.dropped);
        Packet<?> first = held.packets.peekFirst();
        assertEquals("#15", ((GameMessageS2CPacket) first).content().getString());
        assertEquals("#" + (total - 1), ((GameMessageS2CPacket) held.packets.peekLast()).content().getString());
    }

    @Test
    void speakingScopesNestAndRestore() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        UUID none = ChatGate.enter(outer);
        assertNull(none);
        UUID restoreTo = ChatGate.enter(inner);
        assertSame(outer, restoreTo);
        ChatGate.exit(restoreTo);
        assertSame(outer, ChatGate.enter(outer));   // back to the outer scope
        ChatGate.exit(outer);
        ChatGate.exit(none);
        assertNull(ChatGate.enter(null));
        ChatGate.exit(null);
    }
}
