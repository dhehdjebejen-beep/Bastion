package dev.bastionac.telemetry;

import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VelocityPacketsTest {
    @Test
    void directAndBundledMotionRecordOnlyTheRecipientsVelocityOnce() {
        Vec3d issued = new Vec3d(1.2, 0.25, -0.4);
        var own = new EntityVelocityUpdateS2CPacket(42, issued);
        var other = new EntityVelocityUpdateS2CPacket(7, new Vec3d(3, 2, 1));
        List<Vec3d> seen = new ArrayList<>();

        VelocityPackets.visit(own, 42, (v, explosion) -> {
            assertFalse(explosion);
            seen.add(v);
        });
        assertEquals(List.of(issued), seen);

        seen.clear();
        VelocityPackets.visit(new BundleS2CPacket(List.of(other, own)), 42, (v, explosion) -> {
            assertFalse(explosion);
            seen.add(v);
        });
        assertEquals(List.of(issued), seen);

        seen.clear();
        VelocityPackets.visit(other, 42, (v, explosion) -> seen.add(v));
        assertTrue(seen.isEmpty());
    }
}
