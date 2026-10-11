package dev.bastionac.telemetry;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static dev.bastionac.telemetry.TelemetryFormat.*;

/**
 * Decodes a session file into {@link SessionData}. A file that is still being
 * written (or whose server died) simply ends early: the reader keeps what it
 * got and marks the data {@link SessionData#truncated}.
 *
 * <p>Records outside {@code [fromMs, toMs]} are decoded — the delta encodings
 * need every one of them — but not kept.
 */
public final class TelemetryReader {

    private final DataInputStream in;
    private final SessionData out = new SessionData();
    private final long fromMs, toMs;
    private long ts;
    private long qx, qy, qz;
    private boolean havePos;
    private int input, env, ground = -1, tickIndex, serverTickIndex, world;
    private float speed = Float.NaN;
    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;

    private TelemetryReader(InputStream raw, long fromMs, long toMs) {
        this.in = new DataInputStream(raw);
        this.fromMs = fromMs;
        this.toMs = toMs;
    }

    public static SessionData read(Path file) throws IOException {
        return read(file, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static SessionData read(Path file, long fromMs, long toMs) throws IOException {
        try (InputStream raw = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file), 64 * 1024), 64 * 1024)) {
            return new TelemetryReader(raw, fromMs, toMs).run();
        }
    }

    /** Raw (already un-gzipped) bytes — tests. */
    public static SessionData readRaw(byte[] bytes) throws IOException {
        return new TelemetryReader(new ByteArrayInputStream(bytes), Long.MIN_VALUE, Long.MAX_VALUE).run();
    }

    private SessionData run() throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("not a BastionAC session file");
        int version = in.readUnsignedByte();
        if (version > VERSION) throw new IOException("session format v" + version + " is newer than this reader");
        out.headerJson = str();
        parseHeader(out.headerJson);
        ts = out.startMs;
        try {
            while (true) {
                int type;
                try {
                    type = in.readUnsignedByte();
                } catch (EOFException end) {
                    break;
                }
                ts += zigzag();
                record(type);
                out.records++;
            }
        } catch (EOFException | java.util.zip.ZipException cut) {
            // A live or crashed session: the gzip trailer is missing or a chunk is half-written.
            out.truncated = true;
        } catch (IOException cut) {
            if (String.valueOf(cut.getMessage()).contains("Unexpected end")) out.truncated = true;
            else throw cut;
        }
        out.lastWorld = world;
        return out;
    }

    private void parseHeader(String json) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            out.uuid = o.has("uuid") ? o.get("uuid").getAsString() : "";
            out.name = o.has("name") ? o.get("name").getAsString() : "";
            out.startMs = o.has("start") ? o.get("start").getAsLong() : 0L;
        } catch (RuntimeException ignored) {
            // A damaged header still leaves the records readable.
        }
    }

    private boolean inWindow() {
        boolean in = ts >= fromMs && ts <= toMs;
        if (in) {
            if (ts < out.firstT) out.firstT = ts;
            if (ts > out.lastT) out.lastT = ts;
        }
        return in;
    }

    private void record(int type) throws IOException {
        switch (type) {
            case R_MOVE -> {
                int flags = in.readUnsignedByte();
                if ((flags & MV_POS) != 0) {
                    long dx = zigzag(), dy = zigzag(), dz = zigzag();
                    if (!havePos) {
                        qx = 0;
                        qy = 0;
                        qz = 0;
                        havePos = true;
                    }
                    qx += dx;
                    qy += dy;
                    qz += dz;
                    lastX = qx / Q;
                    lastY = qy / Q;
                    lastZ = qz / Q;
                }
                float yaw = Float.NaN, pitch = Float.NaN;
                if ((flags & MV_LOOK) != 0) {
                    yaw = in.readFloat();
                    pitch = in.readFloat();
                }
                if (inWindow()) {
                    out.moveT.add(ts);
                    out.moveFlags.add(flags);
                    out.moveX.add(lastX);
                    out.moveY.add(lastY);
                    out.moveZ.add(lastZ);
                    out.moveYaw.add(yaw);
                    out.movePitch.add(pitch);
                    out.moveInput.add(input);
                    out.moveEnv.add(env);
                    out.moveGround.add(ground);
                    out.moveSpeed.add(speed);
                    out.moveTick.add(tickIndex);
                    out.moveServerTick.add(serverTickIndex);
                }
            }
            case R_TICK_END -> {
                tickIndex++;
                if (inWindow()) out.tickEndT.add(ts);
            }
            case R_SERVER_TICK -> {
                serverTickIndex++;
                if (inWindow()) out.serverTickT.add(ts);
            }
            case R_INPUT -> {
                input = in.readUnsignedByte();
                out.hasInput = true;
                if (inWindow()) {
                    out.inputT.add(ts);
                    out.inputBits.add(input);
                }
            }
            case R_ENV -> env = varint();
            case R_GROUND -> ground = varint();
            case R_SPEED -> speed = in.readFloat();
            case R_TP_SENT -> {
                int id = varint();
                double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
                in.readUnsignedByte();
                if (inWindow()) {
                    out.tpSentT.add(ts);
                    out.tpSentId.add(id);
                    out.tpX.add(x);
                    out.tpY.add(y);
                    out.tpZ.add(z);
                }
            }
            case R_TP_CONFIRM -> {
                int id = varint();
                int valid = in.readUnsignedByte();
                if (inWindow()) {
                    out.tpConfT.add(ts);
                    out.tpConfId.add(id);
                    out.tpConfValid.add(valid);
                }
            }
            case R_CORRECTION -> {
                int reason = in.readUnsignedByte();
                double cx = in.readDouble(), cy = in.readDouble(), cz = in.readDouble();
                double sx = in.readDouble(), sy = in.readDouble(), sz = in.readDouble();
                int block = varint();
                if (inWindow()) {
                    out.corrT.add(ts);
                    out.corrReason.add(reason);
                    out.corrCX.add(cx);
                    out.corrCY.add(cy);
                    out.corrCZ.add(cz);
                    out.corrSX.add(sx);
                    out.corrSY.add(sy);
                    out.corrSZ.add(sz);
                    out.corrBlock.add(block);
                }
            }
            case R_VELOCITY -> {
                float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
                int ex = in.readUnsignedByte();
                if (inWindow()) {
                    out.velT.add(ts);
                    out.velX.add(x);
                    out.velY.add(y);
                    out.velZ.add(z);
                    out.velExplosion.add(ex);
                }
            }
            case R_STATE -> {
                SessionData.State s = new SessionData.State();
                s.t = ts;
                s.serverTick = varlong();
                s.mspt = in.readFloat();
                s.lastTickMs = in.readFloat();
                s.ping = varint();
                s.timerBalance = in.readFloat();
                s.timerWorst = in.readFloat();
                s.x = in.readDouble();
                s.y = in.readDouble();
                s.z = in.readDouble();
                s.yaw = in.readFloat();
                s.pitch = in.readFloat();
                s.health = in.readFloat();
                s.food = in.readUnsignedByte();
                s.gameMode = in.readUnsignedByte();
                s.flags = varint();
                s.speedAttr = in.readFloat();
                s.gravity = in.readFloat();
                s.jumpStrength = in.readFloat();
                s.stepHeight = in.readFloat();
                s.world = in.readUnsignedByte();
                world = s.world;
                s.blockAtFeet = varint();
                s.vehicleType = varint();
                int n = in.readUnsignedByte();
                s.effects = new int[n * 3];
                for (int i = 0; i < n; i++) {
                    s.effects[i * 3] = varint();
                    s.effects[i * 3 + 1] = in.readUnsignedByte();
                    s.effects[i * 3 + 2] = varint();
                }
                if (inWindow()) out.states.add(s);
            }
            case R_ACTION -> {
                int action = in.readUnsignedByte();
                long pos = in.readLong();
                int face = in.readUnsignedByte();
                varint();   // sequence
                int state = varint();
                float breakTicks = in.readFloat();
                int tool = varint();
                int exposed = in.readUnsignedByte();
                if (inWindow()) {
                    out.actionT.add(ts);
                    out.actionKind.add(action);
                    out.actionPos.add(pos);
                    out.actionFace.add(face);
                    out.actionState.add(state);
                    out.actionBreakTicks.add(breakTicks);
                    out.actionTool.add(tool);
                    out.actionExposed.add(exposed);
                }
            }
            case R_BROKEN -> {
                long pos = in.readLong();
                int state = varint();
                int exposed = in.readUnsignedByte();
                if (inWindow()) {
                    out.brokenT.add(ts);
                    out.brokenPos.add(pos);
                    out.brokenState.add(state);
                    out.brokenExposed.add(exposed);
                    out.brokenWorld.add(world);
                }
            }
            case R_ORE_CONTEXT -> {
                long pos = in.readLong();
                byte[] cells = new byte[126];
                for (int i = 0; i < 126; i += 2) {
                    int b = in.readUnsignedByte();
                    cells[i] = (byte) (b >>> 4);
                    cells[i + 1] = (byte) (b & 0xF);
                }
                if (inWindow()) out.oreContexts.add(new SessionData.OreContext(ts, pos, java.util.Arrays.copyOf(cells, 125)));
            }
            case R_PLACE -> {
                in.readUnsignedByte();
                long pos = in.readLong();
                int face = in.readUnsignedByte();
                float hx = in.readFloat(), hy = in.readFloat(), hz = in.readFloat();
                in.readUnsignedByte();
                int item = varint();
                varint();
                if (inWindow()) {
                    out.placeT.add(ts);
                    out.placePos.add(pos);
                    out.placeFace.add(face);
                    out.placeHX.add(hx);
                    out.placeHY.add(hy);
                    out.placeHZ.add(hz);
                    out.placeItem.add(item);
                }
            }
            case R_USE_ITEM -> {
                in.readUnsignedByte();
                varint();
                in.readFloat();
                in.readFloat();
                if (inWindow()) out.useT.add(ts);
            }
            case R_ATTACK -> {
                SessionData.Attack a = new SessionData.Attack();
                a.t = ts;
                a.targetId = varint();
                a.targetType = varint();
                a.flags = in.readUnsignedByte();
                a.eyeX = in.readDouble();
                a.eyeY = in.readDouble();
                a.eyeZ = in.readDouble();
                a.cx = in.readDouble();
                a.cy = in.readDouble();
                a.cz = in.readDouble();
                a.halfWidth = in.readFloat();
                a.height = in.readFloat();
                a.yaw = in.readFloat();
                a.pitch = in.readFloat();
                a.distance = in.readFloat();
                a.aimError = in.readFloat();
                a.cooldown = in.readFloat();
                a.tvx = in.readFloat();
                a.tvy = in.readFloat();
                a.tvz = in.readFloat();
                a.ping = varint();
                if (inWindow()) out.attacks.add(a);
            }
            case R_SWING -> {
                in.readUnsignedByte();
                if (inWindow()) out.swingT.add(ts);
            }
            case R_SLOT -> {
                int slot = in.readUnsignedByte();
                if (inWindow()) {
                    out.slotT.add(ts);
                    out.slotValue.add(slot);
                }
            }
            case R_CLICK -> {
                varint();
                zigzag();
                in.readUnsignedByte();
                in.readUnsignedByte();
                int open = in.readUnsignedByte();
                if (inWindow()) {
                    out.clickT.add(ts);
                    out.clickScreenOpen.add(open);
                }
            }
            case R_CLOSE -> {
                varint();
                if (inWindow()) out.closeT.add(ts);
            }
            case R_TARGET -> {
                int id = varint();
                float dx = in.readFloat(), dy = in.readFloat(), dz = in.readFloat();
                float hw = in.readFloat(), h = in.readFloat();
                if (inWindow()) {
                    out.targetT.add(ts);
                    out.targetId.add(id);
                    out.targetDX.add(dx);
                    out.targetDY.add(dy);
                    out.targetDZ.add(dz);
                    out.targetHW.add(hw);
                    out.targetH.add(h);
                }
            }
            case R_DAMAGE -> {
                float amount = in.readFloat();
                String dt = str();
                varint();
                if (inWindow()) {
                    out.damageT.add(ts);
                    out.damageAmount.add(amount);
                    out.damageType.add(dt);
                }
            }
            case R_PONG -> {
                int rtt = varint();
                if (inWindow()) {
                    out.pongT.add(ts);
                    out.pongRtt.add(rtt);
                }
            }
            case R_KEEPALIVE -> {
                int rtt = varint();
                if (inWindow()) {
                    out.keepAliveT.add(ts);
                    out.keepAliveRtt.add(rtt);
                }
            }
            case R_CLIENT -> {
                inWindow();
                out.brand = str();
                out.protocol = varint();
                out.locale = str();
                out.viewDistance = in.readUnsignedByte();
                in.readUnsignedByte();
                in.readUnsignedByte();
                in.readUnsignedByte();
                int n = varint();
                out.channels.clear();
                for (int i = 0; i < n; i++) out.channels.add(str());
            }
            case R_FLAG -> {
                String check = str();
                float vl = in.readFloat();
                String details = str();
                if (inWindow()) {
                    out.flagT.add(ts);
                    out.flagCheck.add(check);
                    out.flagVl.add(vl);
                    out.flagDetails.add(details);
                }
            }
            case R_AC_ACTION -> {
                int kind = in.readUnsignedByte();
                String text = str();
                if (inWindow()) {
                    out.actT.add(ts);
                    out.actKind.add(kind);
                    out.actText.add(text);
                }
            }
            case R_LIFE -> {
                int kind = in.readUnsignedByte();
                int w = in.readUnsignedByte();
                if (kind == LIFE_WORLD || kind == LIFE_RESPAWN) world = w;
                in.readDouble();
                in.readDouble();
                in.readDouble();
                if (kind == LIFE_RESPAWN || kind == LIFE_WORLD) havePos = false;
                if (inWindow()) {
                    out.lifeT.add(ts);
                    out.lifeKind.add(kind);
                    out.lifeWorld.add(w);
                }
            }
            case R_NOTE -> {
                String text = str();
                if (inWindow()) {
                    out.noteT.add(ts);
                    out.notes.add(text);
                }
            }
            case R_VEHICLE -> {
                int mounted = in.readUnsignedByte();
                varint();
                varint();
                if (inWindow()) {
                    out.vehicleT.add(ts);
                    out.vehicleMounted.add(mounted);
                }
            }
            default -> throw new IOException("unknown record type " + type + " at record " + out.records);
        }
    }

    // ---------------------------------------------------------------- primitives

    private int varint() throws IOException {
        int v = 0, shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 35) throw new IOException("varint too long");
        }
    }

    private long varlong() throws IOException {
        long v = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
            shift += 7;
            if (shift > 70) throw new IOException("varlong too long");
        }
    }

    private long zigzag() throws IOException {
        long v = varlong();
        return (v >>> 1) ^ -(v & 1);
    }

    private String str() throws IOException {
        int n = varint();
        byte[] b = new byte[n];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
