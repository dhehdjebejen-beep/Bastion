package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;
import dev.bastionac.argus.features.MovementFeatures;
import dev.bastionac.argus.features.PacketFeatures;
import dev.bastionac.telemetry.SessionData;
import dev.bastionac.telemetry.TelemetryReader;

import java.nio.file.Path;

/**
 * Offline look at one session file: java -cp … dev.bastionac.argus.OfflineAnalyze FILE [fromMs toMs]
 * Prints every movement violation with its context, then the feature values.
 */
public final class OfflineAnalyze {
    public static void main(String[] args) throws Exception {
        long from = args.length > 2 ? Long.parseLong(args[1]) : Long.MIN_VALUE;
        long to = args.length > 2 ? Long.parseLong(args[2]) : Long.MAX_VALUE;
        SessionData s = TelemetryReader.read(Path.of(args[0]), from, to);
        MovementFeatures.debug = System.out::println;
        FeatureSet fs = new FeatureSet();
        MovementFeatures.analyze(s, fs, raw -> 0.6f);
        PacketFeatures.analyze(s, fs);
        for (Features.Spec spec : Features.ALL.values()) {
            FeatureSet.Value v = fs.value(spec);
            if (v != null) System.out.printf("%-22s %s (support %.0f)%n", spec.key(), spec.format(v.value()), v.support());
        }
    }
}
