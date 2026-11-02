package dev.bastionclaims.core;

import dev.bastionclaims.util.Lang;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Direction;

import java.util.Locale;

/**
 * Direction words for the {@code /we} commands, in Russian and English. When no
 * direction is given the player's own facing is used — looking steeply up or
 * down counts as UP/DOWN, exactly like WorldEdit does.
 */
public final class Directions {

    /** Where the player is looking, snapped to one of the six block faces. */
    public static Direction facing(ServerPlayerEntity p) {
        float pitch = p.getPitch();
        if (pitch < -60.0f) return Direction.UP;
        if (pitch > 60.0f) return Direction.DOWN;
        return p.getHorizontalFacing();
    }

    /** Parses a direction word, falling back to the player's facing. */
    public static Direction parse(ServerPlayerEntity p, String word) {
        if (word == null || word.isBlank()) return facing(p);
        return switch (word.trim().toLowerCase(Locale.ROOT)) {
            case "вверх", "верх", "up", "u", "+y" -> Direction.UP;
            case "вниз", "низ", "down", "d", "-y" -> Direction.DOWN;
            case "север", "north", "n" -> Direction.NORTH;
            case "юг", "south", "s" -> Direction.SOUTH;
            case "запад", "west", "w" -> Direction.WEST;
            case "восток", "east", "e" -> Direction.EAST;
            case "вперёд", "вперед", "forward", "me", "я", "сюда" -> facing(p);
            case "назад", "back" -> facing(p).getOpposite();
            default -> null;
        };
    }

    /** Russian name for messages. */
    public static String ru(Direction d) {
        return switch (d) {
            case UP -> Lang.t("вверх");
            case DOWN -> Lang.t("вниз");
            case NORTH -> Lang.t("на север");
            case SOUTH -> Lang.t("на юг");
            case WEST -> Lang.t("на запад");
            case EAST -> Lang.t("на восток");
        };
    }

    public static String help() {
        return Lang.t("вверх, вниз, север, юг, запад, восток, вперёд, назад");
    }

    private Directions() {}
}
