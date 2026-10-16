package dev.bastionac.argus;

import dev.bastionac.argus.features.FeatureSet;
import dev.bastionac.argus.features.Features;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plain Russian: the findings for the panel and the full text report for the owner. */
final class Report {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private Report() {}

    /** Strongest evidence first; at most a dozen lines. */
    static List<String> findings(Scorer.Result r, Analysis.Output o) {
        List<String> out = new ArrayList<>();
        for (Scorer.Row row : r.rows()) {
            if (row.evidence() <= 0.05) continue;
            String when = when(o.features, row.spec().key());
            out.add(line(row) + (when == null ? "" : " [" + when + "]"));
            if (out.size() >= 10) break;
        }
        if (o.cheatChannel != null) out.add(0, "клиент объявил канал «" + o.cheatChannel + "»");
        double[] rv = revealCounts(o.features);
        if (rv != null && rv[4] > 0) {
            out.add(String.format(Locale.ROOT, "туннели вскрыли руды: %.0f при ожидаемых %.1f; алмазов %.0f при %.2f (вскрыто %.0f блоков)",
                    rv[0], rv[1], rv[2], rv[3], rv[4]));
        }
        if (r.signalEvidence() > 0) {
            out.add(String.format(Locale.ROOT, "сигналы античита добавили %.1f", r.signalEvidence()));
        }
        if (out.isEmpty()) out.add(r.enoughData() ? "ничего необычного" : "данных слишком мало для вывода");
        return out;
    }

    /** When a feature's suspicious events happened, from the accumulated moments (3.4). */
    static String when(FeatureSet fs, String key) {
        FeatureSet.Acc a = fs == null ? null : fs.raw().get(key);
        if (a == null || a.moments.n == 0) return null;
        return CaseFile.when(CaseFile.sampleMoments(a.moments.a, a.moments.n), a.momentTotal);
    }

    static String line(Scorer.Row row) {
        Features.Spec s = row.spec();
        String val = s.format(row.value());
        String ref = s.agg() == Features.Agg.FLAG ? "" : s.agg() == Features.Agg.Z ? ""
                : " (норма " + s.format(row.ref()) + ")";
        return String.format(Locale.ROOT, "%s: %s%s, z=%.1f → +%.1f", s.title(), val, ref, row.z(), row.evidence());
    }

    /** Summed [ores found, ores expected, diamonds found, diamonds expected, cells revealed] or null. */
    static double[] revealCounts(FeatureSet fs) {
        if (!fs.has("mine.reveal_detail")) return null;
        FeatureSet.Acc a = fs.acc("mine.reveal_detail");
        double[] sum = new double[5];
        for (int i = 0; i + 4 < a.samples.n; i += 5) {
            for (int k = 0; k < 5; k++) sum[k] += a.samples.a[i + k];
        }
        return sum;
    }

    /** The full text report written next to the case. */
    static String full(CaseFile c, Analysis.Output o, Scorer.Result r, Calibration cal, Baselines base) {
        StringBuilder sb = new StringBuilder();
        sb.append("АРГУС — дело #").append(c.id).append('\n');
        sb.append("Игрок: ").append(c.name).append(" (").append(c.uuid).append(")\n");
        sb.append("Открыто: ").append(TIME.format(Instant.ofEpochMilli(c.openedAt)))
                .append(", разбор №").append(c.analyses).append(": ").append(TIME.format(Instant.now())).append('\n');
        sb.append("\nВЕРДИКТ: ").append(c.verdict.label.toUpperCase(Locale.ROOT))
                .append(String.format(Locale.ROOT, "  (оценка %.3f, улик %.2f, сигналы %.2f)%n", r.score(), r.evidence(), r.signalEvidence()));
        if (!r.categories().isEmpty()) {
            sb.append("По категориям:");
            r.categories().forEach((k, v) -> sb.append(String.format(Locale.ROOT, "  %s %.0f%%", k.label, v * 100)));
            sb.append('\n');
        }
        sb.append("\nПОЧЕМУ ОТКРЫТО\n");
        for (CaseFile.SignalEntry e : c.signals) {
            String kind;
            try {
                kind = Signal.Kind.valueOf(e.kind).label;
            } catch (IllegalArgumentException ex) {
                kind = e.kind;
            }
            sb.append("  ").append(TIME.format(Instant.ofEpochMilli(e.at))).append("  ").append(kind)
                    .append(e.detail == null ? "" : " — " + e.detail).append('\n');
        }
        sb.append("\nДАННЫЕ\n");
        sb.append(String.format(Locale.ROOT, "  %.1f мин телеметрии, файлов %d; ударов %d, сломано блоков %d, руд с контекстом %d%n",
                o.minutes, o.files.size(), o.attacks, o.broken, o.ores));
        sb.append("  клиент: ").append(o.brand == null ? "?" : o.brand).append(", протокол ")
                .append(o.protocol).append(o.keys ? ", клавиши известны (1.21.2+)" : ", клавиши неизвестны").append('\n');
        if (o.sampleNote != null) sb.append("  мир: ").append(o.sampleNote).append('\n');
        if (!o.flagsByCheck.isEmpty()) sb.append("  флаги проверок: ").append(o.flagsByCheck).append('\n');
        for (String f : o.files) sb.append("  файл: ").append(f).append('\n');
        if (o.error != null) sb.append("  ОШИБКА РАЗБОРА: ").append(o.error).append('\n');

        sb.append("\nВЫВОДЫ\n");
        for (String f : findings(r, o)) sb.append("  • ").append(f).append('\n');

        sb.append("\nВСЕ ПРИЗНАКИ (значение | норма ± масштаб | данных | z | улика | откуда норма)\n");
        for (Scorer.Row row : r.rows()) {
            Features.Spec s = row.spec();
            sb.append(String.format(Locale.ROOT, "  %-22s %-12s | %s ± %s | %.0f | %5.1f | %.2f | %s%n",
                    s.key(), s.format(row.value()), s.format(row.ref()), s.format(row.scale()), row.support(), row.z(),
                    row.evidence(), row.source()));
            String when = when(o.features, s.key());
            if (when != null) sb.append("        когда: ").append(when).append('\n');
        }
        sb.append("\nНе хватило данных: ");
        List<String> missing = new ArrayList<>();
        for (Features.Spec s : Features.ALL.values()) {
            boolean used = r.rows().stream().anyMatch(x -> x.spec() == s);
            if (!used) missing.add(s.key());
        }
        sb.append(missing.isEmpty() ? "—" : String.join(", ", missing)).append('\n');

        sb.append("\nМОДЕЛЬ\n");
        sb.append(String.format(Locale.ROOT, "  смещение %.2f, меток: читеров %d, чистых %d; макс. оценка у чистых %.3f; норм в базе %d сессий%n",
                cal.bias, cal.cheaters, cal.clean, cal.maxCleanScore, base.sessions()));
        Map<String, Double> w = cal.weights;
        if (!w.isEmpty()) sb.append("  веса: ").append(w).append('\n');
        if (c.label != CaseFile.Label.NONE) {
            sb.append("\nРЕШЕНИЕ ВЛАДЕЛЬЦА: ").append(c.label.label).append(" (").append(c.labelBy).append(")\n");
        }
        return sb.toString();
    }
}
