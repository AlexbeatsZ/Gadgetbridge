/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import java.time.ZonedDateTime;
import java.util.Objects;

/** Uses live firmware sleep classification, never historical sleep files or inactivity alone. */
public final class XiaomiWakeGuardEngine {
    public static final long MAX_SAMPLE_AGE_MS = 90_000;
    private Boolean asleep, worn, charging;
    private long sleepAt = -1, wornAt = -1, chargingAt = -1;
    private long candidateAt = -1, lastAlertAt = -1;
    private String candidateOccurrence;
    private int positiveSamples;

    public void reset() {
        asleep = worn = charging = null;
        sleepAt = wornAt = chargingAt = -1;
        clearCandidate();
    }

    private void clearCandidate() {
        candidateAt = lastAlertAt = -1;
        candidateOccurrence = null;
        positiveSamples = 0;
    }

    public void observe(Boolean asleep, Boolean worn, Boolean charging, long elapsed) {
        if (candidateAt >= 0 && (!fresh(sleepAt, elapsed) || !fresh(wornAt, elapsed) || !fresh(chargingAt, elapsed))) {
            clearCandidate();
        }
        if (asleep != null) { this.asleep = asleep; sleepAt = elapsed; }
        if (worn != null) { this.worn = worn; wornAt = elapsed; }
        if (charging != null) { this.charging = charging; chargingAt = elapsed; }
    }

    private boolean fresh(long at, long elapsed) {
        return at >= 0 && elapsed >= at && elapsed - at <= MAX_SAMPLE_AGE_MS;
    }

    public String status(XiaomiWakeGuardConfig config, ZonedDateTime now, long elapsed) {
        if (!config.enabled) return "disabled";
        if (config.activeRule(now) == null) return "outside";
        if (!fresh(sleepAt, elapsed) || !fresh(wornAt, elapsed) || !fresh(chargingAt, elapsed)) return "stale";
        if (!Boolean.TRUE.equals(worn)) return "not_worn";
        if (!Boolean.FALSE.equals(charging)) return "charging";
        return Boolean.TRUE.equals(asleep) ? "suspected" : "awake";
    }

    /** Call once per incoming sleep observation; timers may invalidate but cannot invent evidence. */
    public XiaomiWakeGuardConfig.Rule evaluate(XiaomiWakeGuardConfig config, ZonedDateTime now,
                                               long elapsed, boolean sleepObservation) {
        if (!"suspected".equals(status(config, now, elapsed))) {
            clearCandidate();
            return null;
        }
        XiaomiWakeGuardConfig.Rule rule = config.activeRule(now);
        String occurrence = rule.occurrence(now);
        if (!Objects.equals(candidateOccurrence, occurrence) || candidateAt > elapsed) {
            clearCandidate();
            candidateOccurrence = occurrence;
            candidateAt = elapsed;
        }
        if (sleepObservation) positiveSamples++;
        if (!sleepObservation || positiveSamples < 2 || elapsed - candidateAt < rule.confirmationMinutes * 60_000L
                || (lastAlertAt >= 0 && elapsed - lastAlertAt < rule.repeatMinutes * 60_000L)) return null;
        lastAlertAt = elapsed;
        return rule;
    }
}
