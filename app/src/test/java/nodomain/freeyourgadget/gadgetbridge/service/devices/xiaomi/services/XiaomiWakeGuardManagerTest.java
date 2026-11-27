/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.SharedPreferences;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.robolectric.Shadows;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.*;

public class XiaomiWakeGuardManagerTest extends TestBase {
    private XiaomiSupport support;
    private XiaomiSystemService system;
    private XiaomiWakeGuardManager manager;
    private SharedPreferences prefs;
    private GBDevice device;
    private ZonedDateTime now;

    @Before @Override public void setUp() throws Exception {
        super.setUp();
        support = mock(XiaomiSupport.class);
        system = mock(XiaomiSystemService.class);
        when(system.canWakeGuardAlert()).thenReturn(true);
        device = createDummyGDevice("00:11:22:33:44:59");
        device.setState(GBDevice.State.INITIALIZED);
        when(support.getDevice()).thenReturn(device);
        when(support.getRpkService()).thenReturn(mock(XiaomiRpkService.class));
        now = ZonedDateTime.parse("2026-11-23T09:00:00+08:00[Asia/Shanghai]");
        manager = new XiaomiWakeGuardManager(support, system, () -> now);
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        prefs.edit().clear().commit();
    }

    private void enable(Mode mode) throws Exception {
        Rule r = new Rule("morning", "Morning", true, 31, 480, 720, mode, 1, 5);
        prefs.edit().putBoolean(PREF_ENABLED, true).putString(PREF_RULES, serializeRules(Collections.singletonList(r))).commit();
        manager.initialize();
    }
    private void idle(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }
    private void confirmSleep() {
        manager.observe(true, true, false);
        idle(30_000); manager.observe(true, true, false);
        idle(30_000); manager.observe(true, true, false);
    }

    @Test public void disabledByDefaultDoesNotPollOrRing() {
        manager.initialize(); idle(120_000); manager.observe(true, true, false);
        verify(system, never()).sendWakeGuardFindWatch(anyBoolean());
        verify(support, never()).sendCommand(anyString(), anyInt(), anyInt());
        assertEquals("disabled", prefs.getString(PREF_STATUS, ""));
        manager.dispose();
    }

    @Test public void confirmedSleepRingsForTenSecondsAndCleansUp() throws Exception {
        enable(Mode.RING); confirmSleep();
        verify(system).sendWakeGuardFindWatch(true);
        idle(9_999); verify(system, never()).sendWakeGuardFindWatch(false);
        idle(1); verify(system).sendWakeGuardFindWatch(false);
        manager.dispose();
    }

    @Test public void vibrationUsesVelaWithoutFindWatchOrUnsupportedSystemCommand() throws Exception {
        enable(Mode.VIBRATE); confirmSleep();
        verify(support.getRpkService()).requestWakeGuardVibration();
        verify(system, never()).sendWakeGuardFindWatch(anyBoolean());
        verify(support, never()).sendCommand(anyString(), any(XiaomiProto.Command.class));
        manager.observe(false, true, false);
        verify(support.getRpkService(), atLeastOnce()).cancelWakeGuardVibration();
        manager.dispose();
    }

    @Test public void awakeChargingUnwornAndDisableStopOwnedRinging() throws Exception {
        for (int condition = 0; condition < 4; condition++) {
            clearInvocations(system); enable(Mode.RING); confirmSleep();
            if (condition == 3) {
                prefs.edit().putBoolean(PREF_ENABLED, false).commit(); manager.configure();
            } else manager.observe(condition != 0, condition != 1, condition == 2);
            verify(system).sendWakeGuardFindWatch(false);
            manager.dispose();
        }
    }

    @Test public void scheduleExitStopsRingAndStopsStatusPolling() throws Exception {
        enable(Mode.RING); confirmSleep();
        clearInvocations(support);
        now = now.withHour(12);
        idle(30_000);
        verify(system).sendWakeGuardFindWatch(false);
        verify(support, never()).sendCommand(anyString(), anyInt(), anyInt());
        assertEquals("outside", prefs.getString(PREF_STATUS, ""));
        manager.dispose();
    }

    @Test public void disconnectAndMissingTelemetryNeverTriggerReminder() throws Exception {
        enable(Mode.RING); manager.observe(true, true, false); idle(120_000);
        verify(system, never()).sendWakeGuardFindWatch(anyBoolean());
        assertEquals("stale", prefs.getString(PREF_STATUS, ""));
        device.setState(GBDevice.State.NOT_CONNECTED);
        idle(30_000);
        assertEquals("disconnected", prefs.getString(PREF_STATUS, ""));
        manager.dispose();
    }

    @Test public void invalidConfigurationIsPreservedAndSuspendsMonitoring() {
        prefs.edit().putBoolean(PREF_ENABLED, true).putString(PREF_RULES, "{broken").commit();
        manager.initialize(); idle(120_000);
        assertEquals("invalid", prefs.getString(PREF_STATUS, ""));
        assertEquals("{broken", prefs.getString(PREF_RULES, ""));
        verify(system, never()).sendWakeGuardFindWatch(anyBoolean());
        manager.dispose();
    }

    @Test public void externalFindWatchReleasesOwnedStopTimer() throws Exception {
        enable(Mode.RING); confirmSleep();
        manager.onFindWatchStopped(); idle(10_000);
        verify(system, never()).sendWakeGuardFindWatch(false);
        manager.dispose();
    }

    @Test public void existingFindWatchOrSleepAsAndroidAlarmIsNotOverridden() throws Exception {
        when(system.canWakeGuardAlert()).thenReturn(false);
        enable(Mode.RING); confirmSleep();
        verify(system, never()).sendWakeGuardFindWatch(anyBoolean());
        manager.dispose();
    }
}
