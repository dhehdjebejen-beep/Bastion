package dev.bastionac.argus.features;

import dev.bastionac.core.Lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every feature Argus measures, with what it means and what "normal" looks
 * like before the server has learned it.
 *
 * <p>Each feature is one number per analysed window. The scorer compares it
 * with the population of honest sessions (median and MAD, robust against the
 * odd cheater in the pool) and, until that population exists, with the prior
 * written here — vanilla physics and protocol give most of them a hard
 * expectation anyway (a vanilla client never jumps without the jump key,
 * never ticks faster than 20 times a second).
 */
public final class Features {

    public enum Category {
        MOVEMENT(Lang.k("движение")), COMBAT(Lang.k("бой")), MINING(Lang.k("добыча")), PACKETS(Lang.k("пакеты")), CLIENT(Lang.k("клиент"));

        private final String ru;

        Category(String ru) {
            this.ru = ru;
        }

        /** In the server language. */
        public String label() {
            return Lang.t(ru);
        }
    }

    /** How the accumulated numbers become the feature's value. */
    public enum Agg { RATE, MEDIAN, P95, CV, LOG_CV, KURTOSIS, MAX, MIN, PER_HOUR, FLAG, Z }

    /**
     * @param direction   +1: higher is suspicious; -1: lower is suspicious
     * @param priorRef    the honest value before baselines exist
     * @param priorScale  how far from it is one "unit" of unusual
     * @param minScale    a baseline is never trusted to be tighter than this
     * @param halfSupport support (ticks, attacks, blocks…) at which the feature counts half
     * @param minSupport  below this the feature says nothing at all
     * @param weight      evidence multiplier (the calibration refines it)
     * @param hard        vanilla cannot produce it at all — may stand on its own
     * @param mul         display multiplier (100 for percentages)
     */
    public record Spec(String key, Category cat, Agg agg, int direction, double priorRef, double priorScale,
                       double minScale, double halfSupport, double minSupport, double weight, boolean hard,
                       String title, String unit, double mul) {

        /** Stored in Russian; read in the server language. */
        @Override
        public String title() {
            return Lang.t(title);
        }

        @Override
        public String unit() {
            return Lang.t(unit);
        }

        public String format(double v) {
            double x = v * mul;
            String u = unit();
            if (Math.abs(x) >= 100) return String.format(java.util.Locale.ROOT, "%.0f%s", x, u);
            if (Math.abs(x) >= 10) return String.format(java.util.Locale.ROOT, "%.1f%s", x, u);
            return String.format(java.util.Locale.ROOT, "%.2f%s", x, u);
        }
    }

    public static final Map<String, Spec> ALL = new LinkedHashMap<>();

    private static void add(Spec s) {
        ALL.put(s.key(), s);
    }

    static {
        // ---------------------------------------------------------- movement (physics replay)
        add(new Spec("move.air_gain", Category.MOVEMENT, Agg.RATE, 1, 0.003, 0.006, 0.003, 400, 150, 1.2, false,
                Lang.k("разгон в воздухе сверх ванильного"), "%", 100));
        add(new Spec("move.air_turn", Category.MOVEMENT, Agg.RATE, 1, 0.004, 0.008, 0.004, 400, 150, 1.0, false,
                Lang.k("смена направления в воздухе (strafe)"), "%", 100));
        add(new Spec("move.ground_gain", Category.MOVEMENT, Agg.RATE, 1, 0.004, 0.008, 0.004, 600, 200, 1.2, false,
                Lang.k("разгон по земле сверх ванильного"), "%", 100));
        add(new Spec("move.rise", Category.MOVEMENT, Agg.RATE, 1, 0.002, 0.005, 0.003, 400, 150, 1.3, false,
                Lang.k("подъём в воздухе против гравитации"), "%", 100));
        add(new Spec("move.fall_fast", Category.MOVEMENT, Agg.RATE, 1, 0.002, 0.005, 0.003, 300, 100, 1.0, false,
                Lang.k("падение быстрее гравитации"), "%", 100));
        add(new Spec("move.jump_nokey", Category.MOVEMENT, Agg.RATE, 1, 0.0, 0.02, 0.02, 20, 8, 1.5, true,
                Lang.k("прыжки без клавиши прыжка"), "%", 100));
        add(new Spec("move.move_nokey", Category.MOVEMENT, Agg.RATE, 1, 0.002, 0.006, 0.004, 300, 100, 1.2, false,
                Lang.k("разгон без клавиш движения"), "%", 100));
        add(new Spec("move.sprint_noforward", Category.MOVEMENT, Agg.RATE, 1, 0.01, 0.02, 0.01, 200, 60, 0.6, false,
                Lang.k("бег без клавиши «вперёд»"), "%", 100));
        add(new Spec("move.input_dir", Category.MOVEMENT, Agg.RATE, 1, 0.005, 0.01, 0.006, 400, 120, 1.2, false,
                Lang.k("разгон не в сторону нажатых клавиш (strafe)"), "%", 100));
        // 3.4: a single packet farther sideways than any foot covers in a tick — ClickTP.
        add(new Spec("move.teleport", Category.MOVEMENT, Agg.PER_HOUR, 1, 0.0, 0.5, 0.5, 0.3, 0.05, 1.5, true,
                Lang.k("телепорт пешком (больше 2 бл за тик)"), Lang.k("/ч"), 1));

        // ---------------------------------------------------------- packets & timing
        add(new Spec("pkt.tick_rate", Category.PACKETS, Agg.MEDIAN, 1, 20.0, 0.12, 0.08, 20, 6, 1.6, true,
                Lang.k("частота тиков клиента"), Lang.k("/с"), 1));
        add(new Spec("pkt.timer_episodes", Category.PACKETS, Agg.PER_HOUR, 1, 0.0, 2.0, 1.0, 0.25, 0.05, 1.2, false,
                Lang.k("выходы вперёд реального времени (таймер)"), Lang.k("/ч"), 1));
        add(new Spec("pkt.extra_moves", Category.PACKETS, Agg.RATE, 1, 0.0003, 0.002, 0.001, 2000, 600, 1.0, false,
                Lang.k("лишние пакеты движения в тике"), "%", 100));
        add(new Spec("pkt.blind_stall", Category.PACKETS, Agg.PER_HOUR, 1, 0.3, 1.0, 0.5, 0.5, 0.1, 1.0, false,
                Lang.k("паузы движения при живом соединении (Blink)"), Lang.k("/ч"), 1));
        add(new Spec("pkt.ping_gap", Category.PACKETS, Agg.MEDIAN, 1, 0, 40, 25, 6, 3, 1.0, false,
                Lang.k("keep-alive медленнее нашего пинга (подмена пинга)"), Lang.k(" мс"), 1));
        add(new Spec("pkt.phase_loops", Category.PACKETS, Agg.PER_HOUR, 1, 0.0, 0.5, 0.5, 0.5, 0.05, 1.2, false,
                Lang.k("уход в блоки после пересылки блоков (Phase)"), Lang.k("/ч"), 1));

        // ---------------------------------------------------------- combat
        add(new Spec("cmb.aim_center", Category.COMBAT, Agg.RATE, 1, 0.05, 0.06, 0.04, 40, 15, 1.2, false,
                Lang.k("удары точно в центр хитбокса"), "%", 100));
        add(new Spec("cmb.aim_error", Category.COMBAT, Agg.MEDIAN, -1, 9, 4, 2, 40, 15, 0.8, false,
                Lang.k("медианный промах прицела при ударе"), "°", 1));
        // Lag-compensated aim and reaction (3.3): against the target's track
        // as the player could have seen it, not where it is on the server now.
        add(new Spec("cmb.aim_lc_center", Category.COMBAT, Agg.RATE, 1, 0.03, 0.05, 0.03, 30, 15, 0.6, false,
                Lang.k("удары точно в цель, какой игрок её видел (с учётом лага)"), "%", 100));
        add(new Spec("cmb.aim_lc_error", Category.COMBAT, Agg.MEDIAN, -1, 6, 3, 1.5, 30, 15, 0.8, false,
                Lang.k("медианный промах с учётом лага"), "°", 1));
        add(new Spec("cmb.track_lag", Category.COMBAT, Agg.MEDIAN, -1, 3.5, 1.0, 0.7, 3, 2, 0.8, false,
                Lang.k("реакция прицела на рывки цели (за вычетом пинга)"), Lang.k(" т"), 1));
        add(new Spec("cmb.track_instant", Category.COMBAT, Agg.MEDIAN, 1, 0.05, 0.08, 0.05, 3, 2, 0.8, false,
                Lang.k("мгновенный ответ прицела на рывки цели (без реакции)"), "", 1));
        // The worst single fight (3.5 beta, a module switched on for one fight):
        // each value is the lower 99 % bound of one encounter's share, the
        // feature the highest of them (CombatFeatures.encounters).
        add(new Spec("enc.aim_lc_center", Category.COMBAT, Agg.MAX, 1, 0.02, 0.06, 0.04, 1, 1, 1.0, false,
                Lang.k("худшая стычка: удары точно в цель с учётом лага"), "%", 100));
        add(new Spec("enc.aim_lc_error", Category.COMBAT, Agg.MIN, -1, 4.0, 1.5, 0.8, 1, 1, 1.0, false,
                Lang.k("лучшая стычка: медианный промах с учётом лага"), "°", 1));
        add(new Spec("enc.aim_center", Category.COMBAT, Agg.MAX, 1, 0.03, 0.08, 0.05, 1, 1, 1.0, false,
                Lang.k("худшая стычка: удары точно в центр хитбокса"), "%", 100));
        add(new Spec("enc.reach_over", Category.COMBAT, Agg.MAX, 1, 0.01, 0.05, 0.04, 1, 1, 1.0, false,
                Lang.k("худшая стычка: удары дальше 3,1 блока"), "%", 100));
        add(new Spec("cmb.snap", Category.COMBAT, Agg.RATE, 1, 0.03, 0.05, 0.03, 40, 15, 1.0, false,
                Lang.k("рывок прицела к цели перед ударом"), "%", 100));
        add(new Spec("cmb.rot_const", Category.COMBAT, Agg.RATE, 1, 0.02, 0.03, 0.02, 300, 100, 1.0, false,
                Lang.k("одинаковые шаги поворота подряд (интерполяция)"), "%", 100));
        add(new Spec("cmb.gcd_off", Category.COMBAT, Agg.RATE, 1, 0.15, 0.12, 0.08, 200, 80, 0.8, false,
                Lang.k("повороты мимо сетки чувствительности"), "%", 100));
        // The rhythm without its pauses, on a log scale (3.3): near zero a
        // plain scale squeezes 0.04 (a machine) and 0.13 (a steady hand)
        // together. Honest hands sit around ln 0.3 ≈ −1.2. A new key, so norms
        // learned on the old linear value are not compared with it.
        add(new Spec("cmb.click_rhythm", Category.COMBAT, Agg.LOG_CV, -1, -1.2, 0.35, 0.25, 60, 25, 0.9, false,
                Lang.k("ровность кликов (ln вариации, без пауз)"), "", 1));
        add(new Spec("cmb.rhythm_kurt", Category.COMBAT, Agg.KURTOSIS, -1, 2.0, 1.5, 1.0, 60, 30, 0.6, false,
                Lang.k("форма распределения кликов (эксцесс)"), "", 1));
        // Weight 1.6 (was 1.2) since high-ping hits are set aside: a p95 well
        // past vanilla's 3 blocks on a low ping is now worth a look by itself.
        add(new Spec("cmb.reach", Category.COMBAT, Agg.P95, 1, 2.85, 0.12, 0.08, 30, 10, 1.6, false,
                Lang.k("дистанция удара по игрокам (95-й процентиль)"), Lang.k(" бл"), 1));
        add(new Spec("cmb.reach_lc", Category.COMBAT, Agg.P95, 1, 2.9, 0.1, 0.06, 30, 15, 1.2, false,
                Lang.k("дистанция удара с учётом лага (95-й процентиль)"), Lang.k(" бл"), 1));
        add(new Spec("cmb.reach_over", Category.COMBAT, Agg.RATE, 1, 0.01, 0.02, 0.015, 30, 15, 1.4, false,
                Lang.k("удары дальше 3,1 блока сверх отхода цели (любой пинг)"), "%", 100));
        add(new Spec("cmb.blind_hit", Category.COMBAT, Agg.RATE, 1, 0.01, 0.03, 0.02, 40, 15, 1.0, false,
                Lang.k("удары по целям вне поля зрения"), "%", 100));
        add(new Spec("cmb.yaw_wrap", Category.COMBAT, Agg.PER_HOUR, 1, 0.0, 0.5, 0.5, 0.3, 0.05, 1.4, true,
                Lang.k("поворот, перескочивший через 360° (подмена прицела)"), Lang.k("/ч"), 1));
        add(new Spec("cmb.yaw_grid", Category.COMBAT, Agg.RATE, 1, 0.001, 0.004, 0.003, 2000, 300, 0.8, false,
                Lang.k("взгляд ровно по 45° (бот, Baritone)"), "%", 100));

        // ---------------------------------------------------------- mining
        add(new Spec("mine.wall_breaks", Category.MINING, Agg.RATE, 1, 0.0, 0.004, 0.003, 150, 30, 1.6, true,
                Lang.k("ломание блоков, закрытых со всех сторон"), "%", 100));
        add(new Spec("mine.reveal_z", Category.MINING, Agg.Z, 1, 0, 1, 1, 6, 1, 1.4, false,
                Lang.k("руда, вскрытая туннелями, против случайного копания"), "σ", 1));
        add(new Spec("mine.diamond_reveal_z", Category.MINING, Agg.Z, 1, 0, 1, 1, 4, 0.5, 1.4, false,
                Lang.k("алмазы, вскрытые туннелями, против случайного копания"), "σ", 1));
        add(new Spec("mine.discovery_rate", Category.MINING, Agg.RATE, 1, 0.012, 0.012, 0.006, 300, 120, 1.0, false,
                Lang.k("находки руды на блок камня"), "%", 100));
        add(new Spec("mine.diamond_discovery", Category.MINING, Agg.RATE, 1, 0.004, 0.004, 0.003, 400, 150, 1.0, false,
                Lang.k("находки алмазов на блок глубинного камня"), "%", 100));

        // ---------------------------------------------------------- client identity
        add(new Spec("cli.cheat_channel", Category.CLIENT, Agg.FLAG, 1, 0, 1, 1, 1, 1, 1.5, true,
                Lang.k("канал известного чит-клиента"), "", 1));
        add(new Spec("cli.brand_spoof", Category.CLIENT, Agg.FLAG, 1, 0, 1, 1, 1, 1, 0.8, false,
                Lang.k("бренд «vanilla» при модовых каналах"), "", 1));
    }

    public static Spec spec(String key) {
        return ALL.get(key);
    }

    private Features() {}
}
