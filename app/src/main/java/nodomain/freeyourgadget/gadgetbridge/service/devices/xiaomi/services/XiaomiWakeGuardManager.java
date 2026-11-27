/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.function.Supplier;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardEngine;

/** Repeated native reminders while fresh firmware observations continue to confirm sleep. */
public final class XiaomiWakeGuardManager {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiWakeGuardManager.class);
    private static final long POLL_MS = 30_000;
    private final XiaomiSupport support;
    private final XiaomiSystemService system;
    private final Supplier<ZonedDateTime> wallClock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
    private XiaomiWakeGuardConfig config = new XiaomiWakeGuardConfig(false, Collections.emptyList());
    private boolean running;
    private final Runnable poll = this::tick;

    public XiaomiWakeGuardManager(XiaomiSupport support, XiaomiSystemService system) {
        this(support, system, ZonedDateTime::now);
    }
    XiaomiWakeGuardManager(XiaomiSupport support, XiaomiSystemService system, Supplier<ZonedDateTime> wallClock) {
        this.support = support;
        this.system = system;
        this.wallClock = wallClock;
    }
    private SharedPreferences prefs() {
        return GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress());
    }
    public synchronized void initialize() {
        running = true;
        configure();
    }
    public synchronized void configure() {
        engine.reset();
        handler.removeCallbacks(poll);
        try {
            config = new XiaomiWakeGuardConfig(prefs().getBoolean(XiaomiWakeGuardConfig.PREF_ENABLED, false),
                    XiaomiWakeGuardConfig.parseRules(prefs().getString(XiaomiWakeGuardConfig.PREF_RULES, "[]")));
        } catch (JSONException e) {
            LOG.warn("Invalid wake guard configuration; preserving saved rules", e);
            config = new XiaomiWakeGuardConfig(false, Collections.emptyList());
            setStatus("invalid");
            return;
        }
        setStatus(config.enabled ? "stale" : "disabled");
        if (running && config.enabled) handler.post(poll);
    }
    private boolean connected() {
        return support.getDevice() != null
                && support.getDevice().getState().equalsOrHigherThan(GBDevice.State.INITIALIZED)
                && !support.getDevice().isBusy();
    }
    private synchronized void tick() {
        if (!running || !config.enabled) return;
        ZonedDateTime now = wallClock.get();
        if (!connected()) { engine.reset(); setStatus("disconnected"); }
        else {
            long elapsed = SystemClock.elapsedRealtime();
            engine.evaluate(config, now, elapsed, false);
            setStatus(engine.status(config, now, elapsed));
            if (config.activeRule(now) != null) support.sendCommand("wake guard live state",
                    XiaomiSystemService.COMMAND_TYPE, XiaomiSystemService.CMD_DEVICE_STATE_GET);
        }
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_MS);
    }
    public synchronized void observe(Boolean asleep, Boolean worn, Boolean charging) {
        if (!running || !config.enabled) return;
        long elapsed = SystemClock.elapsedRealtime();
        engine.observe(asleep, worn, charging, elapsed);
        ZonedDateTime now = wallClock.get();
        XiaomiWakeGuardConfig.Rule rule = engine.evaluate(config, now, elapsed, asleep != null);
        setStatus(engine.status(config, now, elapsed));
        if (asleep != null) prefs().edit().putLong(XiaomiWakeGuardConfig.PREF_SAMPLE_AT, System.currentTimeMillis()).apply();
        if (rule != null && connected()) alert(rule.mode);
    }
    /** Explicit tests use the same native paths as automatic reminders. */
    public synchronized void test(String action) {
        if (!connected()) return;
        if (XiaomiWakeGuardConfig.STOP_RING.equals(action)) {
            support.getScheduleService().stopWakeGuardAlarm(true);
            return;
        }
        alert(XiaomiWakeGuardConfig.TEST_RING.equals(action)
                ? XiaomiWakeGuardConfig.Mode.RING : XiaomiWakeGuardConfig.Mode.VIBRATE);
    }
    private void alert(XiaomiWakeGuardConfig.Mode mode) {
        String key = mode == XiaomiWakeGuardConfig.Mode.RING
                ? XiaomiWakeGuardConfig.PREF_RING_STATUS : XiaomiWakeGuardConfig.PREF_VIBRATION_STATUS;
        if (!system.canWakeGuardAlert()) { prefs().edit().putString(key,"busy").apply(); return; }
        boolean started = mode == XiaomiWakeGuardConfig.Mode.RING
                ? support.getScheduleService().requestWakeGuardRing()
                : support.getScheduleService().startWakeGuardAlarm(
                        support.getContext().getString(R.string.wake_guard_title), () -> {});
        if (!started) prefs().edit().putString(key,"busy").apply();
        else if (mode == XiaomiWakeGuardConfig.Mode.VIBRATE) prefs().edit().putString(key,"native_sent").apply();
        LOG.info("Wake guard native {} request accepted={}", mode, started);
    }
    private void setStatus(String status) {
        if (!status.equals(prefs().getString(XiaomiWakeGuardConfig.PREF_STATUS, "")))
            prefs().edit().putString(XiaomiWakeGuardConfig.PREF_STATUS, status).apply();
    }
    public synchronized void dispose() {
        running = false;
        handler.removeCallbacks(poll);
        engine.reset();
        if (config.enabled) setStatus("disconnected");
    }
    /** Native reminders are not stopped by unrelated find-watch events. */
    public synchronized void onFindWatchStopped() { }
}
