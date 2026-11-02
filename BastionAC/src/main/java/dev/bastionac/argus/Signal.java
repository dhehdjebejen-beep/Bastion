package dev.bastionac.argus;


import dev.bastionac.core.Lang;

import java.util.UUID;

/**
 * Something that makes Argus look at a player. A signal is never a verdict:
 * it opens (or feeds) a case, and the case is decided by reading the
 * player's telemetry.
 */
public record Signal(long at, UUID uuid, String name, Kind kind, String detail) {

    public enum Kind {
        /** A check reached its staff-alert threshold. */
        ALERT(Lang.k("алерт проверки"), 0.25),
        /** EDR: two different checks at alert level — the chain is watching. */
        CHAIN_WATCH(Lang.k("цепочка EDR: наблюдение"), 0.7),
        /** EDR: the chain burned (and banned through the normal ladder). */
        CHAIN_BURNED(Lang.k("цепочка EDR: сгорела"), 1.5),
        /** An X-ray probe (hidden ore, ratio, purity drop). */
        XRAY_PROBE(Lang.k("проба X-ray"), 0.15),
        /** The server kept refusing the player's moves (Phase/NoClip or a stuck client). */
        CORRECTION_LOOP(Lang.k("откаты сервера"), 0.8),
        /** A finished session the background scan found unusual. */
        SESSION_SCAN(Lang.k("фоновый разбор сессии"), 0.0),
        /** An admin asked for an investigation. */
        MANUAL(Lang.k("запрос администратора"), 0.0);

        private final String ru;
        /** How much prior evidence one such signal is worth (calibrated later from labels). */
        public final double prior;

        Kind(String ru, double prior) {
            this.ru = ru;
            this.prior = prior;
        }

        /** In the server language. */
        public String label() {
            return Lang.t(ru);
        }

        /** Raised by the checks themselves: what proactive mode reads within seconds. */
        public boolean fromChecks() {
            return this == ALERT || this == CHAIN_WATCH || this == CHAIN_BURNED
                    || this == XRAY_PROBE || this == CORRECTION_LOOP;
        }
    }
}
