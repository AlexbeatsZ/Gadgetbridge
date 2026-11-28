/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import org.json.JSONException;
import org.junit.Test;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.*;

public class XiaomiWakeGuardTest extends TestBase {
    private static final long GAP = XiaomiWakeGuardEngine.MAX_SAMPLE_AGE_MS + 30_000;
    private Rule rule(String id, int days, int start, int end, Mode mode) {
        return new Rule(id, id, true, days, start, end, mode, 1, 5);
    }
    private final ZonedDateTime monday = ZonedDateTime.parse("2026-11-23T09:00:00+08:00[Asia/Shanghai]");
    private XiaomiWakeGuardConfig config(Rule... rules) { return new XiaomiWakeGuardConfig(true, Arrays.asList(rules)); }
    private Rule weekday() { return rule("morning", 31, 8 * 60, 12 * 60, Mode.VIBRATE); }
    private Rule snapshot(XiaomiWakeGuardEngine engine, XiaomiWakeGuardConfig config, long elapsed,
                          boolean asleep, boolean worn, boolean charging) {
        engine.observe(asleep, worn, charging, elapsed);
        return engine.evaluate(config, monday.plusNanos(elapsed * 1_000_000), elapsed, true);
    }

    @Test public void weeklyBoundariesAndDisabledRules() {
        Rule r = weekday();
        assertTrue(r.matches(monday));
        assertFalse(r.matches(monday.withHour(7).withMinute(59)));
        assertTrue(r.matches(monday.withHour(8)));
        assertFalse(r.matches(monday.withHour(12)));
        assertFalse(r.matches(monday.plusDays(5)));
        assertNull(new XiaomiWakeGuardConfig(false, Collections.singletonList(r)).activeRule(monday));
        assertFalse(new Rule("off", "off", false, 127, 480, 720, Mode.RING, 1, 5).matches(monday));
    }

    @Test public void overnightBelongsToStartDayIncludingSundayRollover() {
        Rule mondayNight = rule("night", 1, 22 * 60, 2 * 60, Mode.VIBRATE);
        assertTrue(mondayNight.matches(monday.withHour(23)));
        assertTrue(mondayNight.matches(monday.plusDays(1).withHour(1)));
        assertFalse(mondayNight.matches(monday.withHour(1)));
        assertFalse(mondayNight.matches(monday.plusDays(1).withHour(2)));
        Rule sundayNight = rule("sunday", 64, 23 * 60, 60, Mode.VIBRATE);
        assertTrue(sundayNight.matches(monday.withHour(0)));
        assertEquals(sundayNight.occurrence(monday.minusDays(1).withHour(23)), sundayNight.occurrence(monday.withHour(0)));
    }

    @Test public void overlapChoosesRingThenStableListOrder() {
        Rule vibration = weekday();
        Rule ring = rule("ring", 127, 8 * 60, 13 * 60, Mode.RING);
        assertSame(ring, config(vibration, ring).activeRule(monday));
        assertSame(ring, config(ring, vibration).activeRule(monday));
        assertSame(vibration, config(vibration, weekday()).activeRule(monday));
    }

    @Test public void roundTripPreservesRulesAndRejectsCorruptConfiguration() throws Exception {
        Rule r = weekday();
        Rule copy = parseRules(serializeRules(Collections.singletonList(r))).get(0);
        assertEquals(r.days, copy.days); assertEquals(r.mode, copy.mode); assertEquals(r.name, copy.name);
        assertThrows(JSONException.class, () -> parseRules("broken"));
        assertThrows(JSONException.class, () -> parseRules(serializeRules(Arrays.asList(r, r))));
        assertThrows(IllegalArgumentException.class, () -> rule("equal", 127, 480, 480, Mode.RING));
        assertThrows(IllegalArgumentException.class, () -> rule("days", 128, 480, 720, Mode.RING));
    }

    @Test public void initialSleepSnapshotConfirmsWithoutWaitingForStateTransition() {
        XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
        Rule rule = weekday(); XiaomiWakeGuardConfig c = config(rule);
        assertNull(snapshot(engine, c, 0, true, true, false));
        assertNull(snapshot(engine, c, 30_000, true, true, false));
        assertSame(rule, snapshot(engine, c, 60_000, true, true, false));
        assertNull(snapshot(engine, c, 90_000, true, true, false));
        for (long t = 120_000; t < 360_000; t += 30_000) assertNull(snapshot(engine, c, t, true, true, false));
        assertSame(rule, snapshot(engine, c, 360_000, true, true, false));
    }

    @Test public void timersAndStaleDataCannotCreateSleepEvidence() {
        XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
        XiaomiWakeGuardConfig c = config(weekday());
        engine.observe(true, true, false, 0);
        assertNull(engine.evaluate(c, monday, 0, true));
        assertNull(engine.evaluate(c, monday.plusMinutes(1), 60_000, false));
        assertNull(engine.evaluate(c, monday.plusMinutes(2), GAP, false));
        assertEquals("stale", engine.status(c, monday, GAP));
        assertNull(snapshot(engine, c, GAP, true, true, false));
        assertNotNull(snapshot(engine, c, GAP + 60_000, true, true, false));
    }

    @Test public void gapWithoutTimerTickStillRestartsConfirmation() {
        XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
        XiaomiWakeGuardConfig c = config(weekday());
        assertNull(snapshot(engine, c, 0, true, true, false));
        assertNull(snapshot(engine, c, GAP, true, true, false));
        assertNotNull(snapshot(engine, c, GAP + 60_000, true, true, false));
    }

    @Test public void awakeUnwornAndChargingEachResetConfirmation() {
        for (int condition = 0; condition < 3; condition++) {
            XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
            XiaomiWakeGuardConfig c = config(weekday());
            assertNull(snapshot(engine, c, 0, true, true, false));
            assertNull(snapshot(engine, c, 30_000, condition != 0, condition != 1, condition == 2));
            assertNull(snapshot(engine, c, 60_000, true, true, false));
            assertNotNull(snapshot(engine, c, GAP, true, true, false));
        }
    }

    @Test public void partialStateCannotRefreshOldSleepOrWearingEvidence() {
        XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
        XiaomiWakeGuardConfig c = config(weekday());
        engine.observe(true, null, null, 0);
        assertNull(engine.evaluate(c, monday, 0, true));
        assertEquals("stale", engine.status(c, monday, 0));
        snapshot(engine, c, 10_000, true, true, false);
        engine.observe(true, null, null, GAP);
        assertNull(engine.evaluate(c, monday, GAP, true));
        assertEquals("stale", engine.status(c, monday, GAP));
    }

    @Test public void scheduleExitAndNextOccurrenceRestartConfirmation() {
        XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
        XiaomiWakeGuardConfig c = config(weekday());
        snapshot(engine, c, 0, true, true, false);
        engine.observe(true, true, false, 60_000);
        assertNull(engine.evaluate(c, monday.withHour(12), 60_000, true));
        assertEquals("outside", engine.status(c, monday.withHour(12), 60_000));
        engine.observe(true, true, false, 90_000);
        assertNull(engine.evaluate(c, monday.plusDays(1), 90_000, true));
    }

    @Test public void timeZoneAndDaylightSavingUseWallClockPeriods() {
        Rule r = rule("dst", 64, 60, 180, Mode.VIBRATE);
        assertTrue(r.matches(ZonedDateTime.parse("2026-11-01T01:30:00-07:00[America/Los_Angeles]")));
        assertTrue(r.matches(ZonedDateTime.parse("2026-11-01T01:30:00-08:00[America/Los_Angeles]")));
        assertFalse(r.matches(ZonedDateTime.parse("2026-11-01T03:00:00-08:00[America/Los_Angeles]")));
    }
}
