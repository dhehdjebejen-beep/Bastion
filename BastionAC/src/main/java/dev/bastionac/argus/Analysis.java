package dev.bastionac.argus;

import dev.bastionac.BastionAC;
import dev.bastionac.argus.features.ClientFeatures;
import dev.bastionac.argus.features.CombatFeatures;
import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.MiningFeatures;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.argus.features.PacketFeatures;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.TelemetryIO;
import dev.bastionac.telemetry.TelemetryReader;
import net.minecraft.block.Block;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * One analysis, start to finish, on the Argus thread: find the player's
 * session files that overlap the window, decode them, measure every feature,
 * read the world around the tunnels if there was digging, score.
 */
final class Analysis {

    /** What to analyse. */
    static final class Input {
        String uuid;
        String name;
        long from, to;
        boolean worldSample;
        double signalEvidence;
        /** A single file instead of a window (the background session scan). */
        Path file;
    }

    /** What came out. */
    static final class Output {
        final FeatureSet features = new FeatureSet();
        Scorer.Result result;
        final List<String> files = new ArrayList<>();
        double minutes;
        boolean sampled;
        String sampleNote;
        String brand;
        int protocol = -1;
        boolean keys;
        String cheatChannel;
        int flags;
        final Map<String, Integer> flagsByCheck = new LinkedHashMap<>();
        long attacks, broken, ores;
        String error;
    }

    private static final Map<Integer, Float> SLIP = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> CATEGORY = new ConcurrentHashMap<>();

    static final MovementFeatures.BlockInfo BLOCKS = raw -> SLIP.computeIfAbsent(raw, r -> {
        try {
            return Block.getStateFromRawId(r).getBlock().getSlipperiness();
        } catch (Throwable t) {
            return 0.6f;
        }
    });

    static final MiningFeatures.Classifier CLASSIFY = raw -> CATEGORY.computeIfAbsent(raw, r -> {
        try {
            return dev.bastionac.core.XrayTracker.contextOf(Block.getStateFromRawId(r));
        } catch (Throwable t) {
            return dev.bastionac.telemetry.TelemetryFormat.CTX_SOLID;
        }
    });

    private Analysis() {}

    static Output run(Input in, Baselines base, Calibration cal) {
        Output out = new Output();
        try {
            TelemetryIO.drain(10_000);   // chunks the server thread handed over must be on disk first
            List<SessionData> sessions = load(in, out);
            if (sessions.isEmpty()) {
                out.result = Scorer.score(out.features, base, in.uuid, cal, in.signalEvidence);
                return out;
            }
            for (SessionData s : sessions) {
                MovementFeatures.analyze(s, out.features, BLOCKS);
                PacketFeatures.analyze(s, out.features);
                CombatFeatures.analyze(s, out.features);
                out.minutes += s.durationMs() / 60_000.0;
                out.attacks += s.attacks.size();
                out.broken += s.brokenT.n;
                out.ores += s.oreContexts.size();
                for (int i = 0; i < s.flagT.n; i++) {
                    out.flags++;
                    out.flagsByCheck.merge(s.flagCheck.get(i), 1, Integer::sum);
                }
                if (s.hasInput) out.keys = true;
            }
            SessionData last = sessions.get(sessions.size() - 1);
            ClientFeatures.analyze(last, out.features);
            out.brand = last.brand;
            out.protocol = last.protocol;
            out.cheatChannel = ClientFeatures.cheatChannel(last);

            WorldSample sample = in.worldSample ? sample(sessions, out) : null;
            for (SessionData s : sessions) MiningFeatures.analyze(s, out.features, CLASSIFY, sample);
            out.result = Scorer.score(out.features, base, in.uuid, cal, in.signalEvidence);
        } catch (Throwable t) {
            out.error = t.toString();
            BastionAC.LOGGER.warn("Argus analysis of {} failed", in.name, t);
            if (out.result == null) out.result = Scorer.score(new FeatureSet(), base, in.uuid, cal, in.signalEvidence);
        }
        return out;
    }

    /** The player's session files overlapping the window, decoded inside it. */
    private static List<SessionData> load(Input in, Output out) throws java.io.IOException {
        List<Path> files = new ArrayList<>();
        if (in.file != null) {
            files.add(in.file);
        } else {
            List<Path> all = TelemetryIO.filesOf(in.uuid);
            for (int i = 0; i < all.size(); i++) {
                long start = startOf(all.get(i));
                long nextStart = i + 1 < all.size() ? startOf(all.get(i + 1)) : Long.MAX_VALUE;
                if (start <= in.to && nextStart >= in.from) files.add(all.get(i));
            }
        }
        List<SessionData> sessions = new ArrayList<>();
        for (Path f : files) {
            SessionData s = in.file != null ? TelemetryReader.read(f) : TelemetryReader.read(f, in.from, in.to);
            if (s.moveT.n == 0 && s.brokenT.n == 0 && s.attacks.isEmpty() && s.tickEndT.n == 0) continue;
            sessions.add(s);
            out.files.add(f.getFileName().toString());
        }
        return sessions;
    }

    /** Start time encoded in a session file name ("…__yyyyMMdd-HHmmss.btl.gz"). */
    static long startOf(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf("__");
        try {
            java.time.LocalDateTime t = java.time.LocalDateTime.parse(n.substring(i + 2, i + 17),
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            return t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The rock around the tunnels: the box of every overworld block the player
     * broke, grown by a few blocks — only when there was real digging, and
     * only as much as the sampler allows (the most recent digging first).
     */
    private static WorldSample sample(List<SessionData> sessions, Output out) {
        List<long[]> dug = new ArrayList<>();
        for (SessionData s : sessions) {
            for (int i = 0; i < s.brokenT.n; i++) {
                if (s.brokenWorld.a[i] != 0) continue;
                long p = s.brokenPos.a[i];
                dug.add(new long[]{MiningFeatures.unpackX(p), MiningFeatures.unpackY(p), MiningFeatures.unpackZ(p)});
            }
        }
        if (dug.size() < 40) {
            out.sampleNote = "копания мало — мир не читался";
            return null;
        }
        // Most recent digging first, until the box would be too large.
        int from = Math.max(0, dug.size() - 1500);
        long[] box = null;
        for (int i = dug.size() - 1; i >= from; i--) {
            long[] d = dug.get(i);
            long[] nb = box == null ? new long[]{d[0], d[1], d[2], d[0], d[1], d[2]} : box.clone();
            nb[0] = Math.min(nb[0], d[0]);
            nb[1] = Math.min(nb[1], d[1]);
            nb[2] = Math.min(nb[2], d[2]);
            nb[3] = Math.max(nb[3], d[0]);
            nb[4] = Math.max(nb[4], d[1]);
            nb[5] = Math.max(nb[5], d[2]);
            long vol = (nb[3] - nb[0] + 13) * (nb[4] - nb[1] + 9) * (nb[5] - nb[2] + 13);
            if (vol > WorldSampler.MAX_CELLS) break;
            box = nb;
        }
        if (box == null) return null;
        try {
            WorldSample w = WorldSampler.request((int) box[0] - 6, (int) box[1] - 4, (int) box[2] - 6,
                    (int) box[3] + 6, (int) box[4] + 4, (int) box[5] + 6).get(90, TimeUnit.SECONDS);
            out.sampled = w != null;
            out.sampleNote = w == null ? "мир не прочитан" : "прочитано " + w.volume() + " блоков вокруг туннелей";
            return w;
        } catch (Exception e) {
            out.sampleNote = "мир не прочитан: " + e.getClass().getSimpleName();
            return null;
        }
    }

    /** Feature values with enough data behind them — what a finished session adds to the baselines. */
    static Map<String, Double> baselineValues(FeatureSet fs) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Features.Spec spec : Features.ALL.values()) {
            if (spec.agg() == Features.Agg.FLAG || spec.agg() == Features.Agg.Z) continue;
            FeatureSet.Value v = fs.value(spec);
            if (v != null && Double.isFinite(v.value()) && v.support() >= spec.minSupport()) m.put(spec.key(), v.value());
        }
        return m;
    }
}
