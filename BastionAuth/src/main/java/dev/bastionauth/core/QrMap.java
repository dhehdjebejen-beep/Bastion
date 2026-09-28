package dev.bastionauth.core;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.EnumMap;
import java.util.Map;

/**
 * A QR code drawn on a vanilla map. The client renders a filled map from
 * the colour bytes the server sends, so the QR needs no client mod, no
 * resource pack and no web page — the authenticator app scans the screen.
 * The map is locked: the world never repaints it with terrain.
 */
public final class QrMap {

    private static final int SIZE = 128;

    /** Hands the player a map item showing {@code payload} as a QR code; false when it could not be drawn. */
    public static boolean give(ServerPlayerEntity player, String payload, String title) {
        try {
            if (!(player.getEntityWorld() instanceof ServerWorld world)) return false;
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);

            MapState state = MapState.of((byte) 0, true, world.getRegistryKey());
            byte black = MapColor.BLACK.getRenderColorByte(MapColor.Brightness.NORMAL);
            byte white = MapColor.WHITE.getRenderColorByte(MapColor.Brightness.HIGH);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    state.setColor(x, y, matrix.get(x, y) ? black : white);
                }
            }
            MapIdComponent id = world.increaseAndGetMapId();
            world.putMapState(id, state);

            ItemStack map = new ItemStack(Items.FILLED_MAP);
            map.set(DataComponentTypes.MAP_ID, id);
            map.set(DataComponentTypes.CUSTOM_NAME, Text.literal(title));
            if (!player.getInventory().insertStack(map)) player.dropItem(map, false);
            return true;
        } catch (Throwable t) {
            dev.bastionauth.BastionAuth.LOGGER.warn("QR map failed: {}", t.toString());
            return false;
        }
    }

    private QrMap() {}
}
