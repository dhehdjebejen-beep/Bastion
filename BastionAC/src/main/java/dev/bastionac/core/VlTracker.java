package dev.bastionac.core;

import java.util.EnumMap;
import java.util.Map;

/**
 * Violation levels per check with time-based decay. Pure Java, unit-testable.
 *
 * <p>The engine philosophy: individual suspicious ticks only bump small
 * per-check buffers inside the checks themselves; a confirmed violation adds
 * {@code weight} to the VL here. Alerts and mitigation trigger on VL
 * thresholds, and VL constantly decays — so isolated noise never surfaces,
 * while sustained cheating climbs quickly.
 */
public final class VlTracker {
    private final Map<CheckType, Double> vls = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> lastAlertMillis = new EnumMap<>(CheckType.class);

    /** Adds weight to the check's VL. @return the new VL. */
    public double add(CheckType type, double weight) {
        double vl = vls.merge(type, weight, Double::sum);
        return vl;
    }

    public double get(CheckType type) {
        return vls.getOrDefault(type, 0.0);
    }

    public void reset(CheckType type) {
        vls.remove(type);
    }

    /** Clears every check's VL (admin "reset VL" action). */
    public void clearAll() {
        vls.clear();
    }

    /** Applies decay for the elapsed time; call about once a second. */
    public void decay(double perSecond, double seconds, CheckType type) {
        Double vl = vls.get(type);
        if (vl == null) return;
        double next = vl - perSecond * seconds;
        if (next <= 0) {
            vls.remove(type);
        } else {
            vls.put(type, next);
        }
    }

    /** True when an alert may fire now (and records the alert time). */
    public boolean tryAlert(CheckType type, long nowMillis, long cooldownMillis) {
        Long last = lastAlertMillis.get(type);
        if (last != null && nowMillis - last < cooldownMillis) return false;
        lastAlertMillis.put(type, nowMillis);
        return true;
    }

    public Map<CheckType, Double> snapshot() {
        return new EnumMap<>(vls);
    }
}
