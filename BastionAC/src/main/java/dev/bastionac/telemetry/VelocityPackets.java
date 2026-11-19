package dev.bastionac.telemetry;

import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.util.function.BiConsumer;

/** Extracts only the recipient's issued motion, including packets inside a bundle. */
public final class VelocityPackets {
    private VelocityPackets() {}

    public static void visit(Packet<?> packet, int recipientId, BiConsumer<Vec3d, Boolean> sink) {
        if (packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> child : bundle.getPackets()) visit(child, recipientId, sink);
        } else if (packet instanceof EntityVelocityUpdateS2CPacket velocity) {
            if (velocity.getEntityId() == recipientId) sink.accept(velocity.getVelocity(), false);
        } else if (packet instanceof ExplosionS2CPacket explosion) {
            explosion.playerKnockback().ifPresent(vec -> sink.accept(vec, true));
        }
    }
}
