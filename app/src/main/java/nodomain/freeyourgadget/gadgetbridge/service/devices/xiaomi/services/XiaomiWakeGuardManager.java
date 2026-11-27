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
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardEngine;

/** Shares the normal authenticated device connection; no new background service or sensor workout. */
public final class XiaomiWakeGuardManager {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiWakeGuardManager.class);
    private static final long POLL_MS = 30_000;
    private static final long RING_MS = 10_000;
    private final XiaomiSupport support;
    private final XiaomiSystemService system;
    private final Supplier<ZonedDateTime> wallClock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final XiaomiWakeGuardEngine engine = new XiaomiWakeGuardEngine();
    private XiaomiWakeGuardConfig config = new XiaomiWakeGuardConfig(false, Collections.emptyList());
    private boolean running, ringing, vibratingFromRule;
    private final Runnable poll = this::tick;
    private final Runnable stopRing = this::stopRinging;

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
        stopRinging();
        support.getRpkService().cancelWakeGuardVibration();
        engine.reset();
        try {
            config = new XiaomiWakeGuardConfig(prefs().getBoolean(XiaomiWakeGuardConfig.PREF_ENABLED, false),
                    XiaomiWakeGuardConfig.parseRules(prefs().getString(XiaomiWakeGuardConfig.PREF_RULES, "[]")));
        } catch (JSONException e) {
            LOG.warn("Invalid wake guard configuration, suspending without replacing saved rules", e);
            config = new XiaomiWakeGuardConfig(false, Collections.emptyList());
            setStatus("invalid");
            handler.removeCallbacks(poll);
            return;
        }
        handler.removeCallbacks(poll);
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
        String status = engine.status(config, now, SystemClock.elapsedRealtime());
        engine.evaluate(config, now, SystemClock.elapsedRealtime(), false);
        if (!"suspected".equals(status)) stopRinging();
        if (!connected()) {
            engine.reset();
            stopRinging();
            setStatus("disconnected");
        } else {
            setStatus(status);
            if (config.activeRule(now) != null) {
                support.sendCommand("wake guard live state", XiaomiSystemService.COMMAND_TYPE,
                        XiaomiSystemService.CMD_DEVICE_STATE_GET);
            }
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
        String status = engine.status(config, now, elapsed);
        setStatus(status);
        if (asleep != null) prefs().edit().putLong(XiaomiWakeGuardConfig.PREF_SAMPLE_AT, java.lang.System.currentTimeMillis()).apply();
        if (!"suspected".equals(status)) stopRinging();
        if (rule != null && connected()) {
            LOG.info("Wake guard reminder: rule={}, mode={}", rule.id, rule.mode);
            alert(rule.mode);
            vibratingFromRule = rule.mode == XiaomiWakeGuardConfig.Mode.VIBRATE;
        }
    }

    /** Only invoked by an explicit action on the device settings screen. */
    public synchronized void test(String action) {
        stopRinging();
        support.getRpkService().cancelWakeGuardVibration();
        if (!connected() || XiaomiWakeGuardConfig.STOP_RING.equals(action)) return;
        alert(XiaomiWakeGuardConfig.TEST_RING.equals(action)
                ? XiaomiWakeGuardConfig.Mode.RING : XiaomiWakeGuardConfig.Mode.VIBRATE);
    }

    private void alert(XiaomiWakeGuardConfig.Mode mode) {
        if (!system.canWakeGuardAlert()) return;
        if (mode == XiaomiWakeGuardConfig.Mode.RING) {
            system.sendWakeGuardFindWatch(true);
            ringing = true;
            handler.removeCallbacks(stopRing);
            handler.postDelayed(stopRing, RING_MS);
        } else {
            support.getRpkService().requestWakeGuardVibration();
        }
    }

    private void setStatus(String status) {
        if (!status.equals(prefs().getString(XiaomiWakeGuardConfig.PREF_STATUS, ""))) {
            prefs().edit().putString(XiaomiWakeGuardConfig.PREF_STATUS, status).apply();
        }
    }

    private synchronized void stopRinging() {
        if (vibratingFromRule) {
            vibratingFromRule = false;
            support.getRpkService().cancelWakeGuardVibration();
        }
        handler.removeCallbacks(stopRing);
        if (ringing) {
            ringing = false;
            if (connected()) system.sendWakeGuardFindWatch(false);
        }
    }

    public synchronized void dispose() {
        running = false;
        handler.removeCallbacks(poll);
        stopRinging();
        support.getRpkService().cancelWakeGuardVibration();
        engine.reset();
        if (config.enabled) setStatus("disconnected");
    }

    /** A user dismissing find-watch must stop the owned burst as well. */
    public synchronized void onFindWatchStopped() {
        ringing = false;
        handler.removeCallbacks(stopRing);
    }
}
