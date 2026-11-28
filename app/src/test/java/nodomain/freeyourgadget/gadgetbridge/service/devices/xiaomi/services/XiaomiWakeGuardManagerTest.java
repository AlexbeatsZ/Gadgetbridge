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
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.*;

public class XiaomiWakeGuardManagerTest extends TestBase {
    private XiaomiSupport support;
    private XiaomiSystemService system;
    private XiaomiScheduleService schedule;
    private XiaomiWakeGuardManager manager;
    private SharedPreferences prefs;
    private GBDevice device;
    private ZonedDateTime now;
    @Before @Override public void setUp() throws Exception {
        super.setUp();
        support = mock(XiaomiSupport.class); system = mock(XiaomiSystemService.class);
        when(system.canWakeGuardAlert()).thenReturn(true);
        device = createDummyGDevice("00:11:22:33:44:59"); device.setState(GBDevice.State.INITIALIZED);
        when(support.getDevice()).thenReturn(device); when(support.getContext()).thenReturn(getContext());
        schedule = mock(XiaomiScheduleService.class); when(support.getScheduleService()).thenReturn(schedule);
        when(schedule.canStartWakeGuardAlert()).thenReturn(true);
        when(schedule.startWakeGuardAlarm(anyString(), any())).thenReturn(true);
        prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()); prefs.edit().clear().commit();
        now = ZonedDateTime.parse("2026-11-23T09:00:00+08:00[Asia/Shanghai]");
        manager = new XiaomiWakeGuardManager(support, system, () -> now);
    }
    private void enable(Mode mode) throws Exception {
        Rule r = new Rule("morning", "Morning", true, 31, 480, 720, mode, 1, 5);
        prefs.edit().putBoolean(PREF_ENABLED,true).putString(PREF_RULES,serializeRules(Collections.singletonList(r))).commit();
        manager.initialize();
    }
    private void idle(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private void confirmSleep() {
        manager.observe(true,true,false); idle(30_000); manager.observe(true,true,false);
        idle(30_000); manager.observe(true,true,false);
    }
    @Test public void defaultOffDoesNotPollOrRing() {
        manager.initialize();idle(120_000);manager.observe(true,true,false);
        verifyNoInteractions(system,schedule);
        verify(support,never()).sendCommand(anyString(),anyInt(),anyInt());
        assertEquals("disabled",prefs.getString(PREF_STATUS,""));manager.dispose();
    }
    @Test public void continuedFreshSleepRequestsRingAgainWithoutWaitingForDismissal() throws Exception {
        when(schedule.requestWakeGuardRing()).thenReturn(true);
        enable(Mode.RING);confirmSleep();verify(schedule).requestWakeGuardRing();
        for(int i=0;i<10;i++){idle(30_000);manager.observe(true,true,false);}
        verify(schedule,times(2)).requestWakeGuardRing();
        verify(system,never()).sendWakeGuardFindWatch(anyBoolean());
        manager.dispose();verify(schedule,never()).stopWakeGuardAlarm(anyBoolean());
    }
    @Test public void awakeStopsNewRequestsWithoutStoppingTheCurrentAlarm() throws Exception {
        enable(Mode.RING);confirmSleep();
        for(int i=0;i<12;i++){idle(30_000);manager.observe(false,true,false);}
        verify(schedule,times(1)).requestWakeGuardRing();
        verify(schedule,never()).stopWakeGuardAlarm(anyBoolean());manager.dispose();
    }
    @Test public void disablingAndExitingScheduleDoNotStopOrDeleteWatchAlarm() throws Exception {
        enable(Mode.RING);confirmSleep();now=now.withHour(12);idle(30_000);
        prefs.edit().putBoolean(PREF_ENABLED,false).commit();manager.configure();manager.dispose();
        verify(schedule,never()).stopWakeGuardAlarm(anyBoolean());
        verify(schedule,times(1)).requestWakeGuardRing();
    }
    @Test public void vibrationUsesNativeAlarmAndRetriesWithoutRpk() throws Exception {
        enable(Mode.VIBRATE);confirmSleep();
        for(int i=0;i<10;i++){idle(30_000);manager.observe(true,true,false);}
        verify(schedule,times(2)).startWakeGuardAlarm(anyString(),any());
        verify(system,never()).sendWakeGuardFindWatch(anyBoolean());manager.dispose();
    }
    @Test public void repeatedExplicitTestsUseNativeRequests() {
        manager.initialize();manager.test(TEST_RING);manager.test(TEST_RING);manager.test(TEST_VIBRATE);
        verify(schedule,times(2)).requestWakeGuardRing();
        verify(schedule).startWakeGuardAlarm(anyString(),any());manager.dispose();
    }
    @Test public void restartRequiresFreshSleepConfirmation() throws Exception {
        enable(Mode.RING);confirmSleep();manager.dispose();
        manager=new XiaomiWakeGuardManager(support,system,()->now);manager.initialize();
        manager.observe(true,true,false);idle(30_000);verify(schedule,times(1)).requestWakeGuardRing();
        manager.observe(true,true,false);idle(30_000);manager.observe(true,true,false);
        verify(schedule,times(2)).requestWakeGuardRing();manager.dispose();
    }
    @Test public void staleDisconnectedAndInvalidDataNeverTrigger() throws Exception {
        enable(Mode.RING);manager.observe(true,true,false);idle(12 * 60_000);
        assertEquals("stale",prefs.getString(PREF_STATUS,""));
        device.setState(GBDevice.State.NOT_CONNECTED);idle(30_000);
        assertEquals("disconnected",prefs.getString(PREF_STATUS,""));
        prefs.edit().putString(PREF_RULES,"{broken").commit();manager.configure();
        assertEquals("invalid",prefs.getString(PREF_STATUS,""));
        assertEquals("{broken",prefs.getString(PREF_RULES,""));
        verify(schedule,never()).requestWakeGuardRing();manager.dispose();
    }
    @Test public void externalAlarmBlocksNewRequests() throws Exception {
        when(system.canWakeGuardAlert()).thenReturn(false);enable(Mode.RING);confirmSleep();
        verify(schedule,never()).requestWakeGuardRing();manager.dispose();
    }
    @Test public void advancingWatchSleepFileRingsWithoutAckingIt() throws Exception {
        XiaomiHealthService health = mock(XiaomiHealthService.class);
        nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileFetcher fetcher =
                mock(nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileFetcher.class);
        when(support.getHealthService()).thenReturn(health); when(health.getActivityFetcher()).thenReturn(fetcher);
        when(schedule.requestWakeGuardRing()).thenReturn(true);
        long bed = now.toEpochSecond() - 7 * 3600;
        nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId sleep =
                new nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId(
                        new java.util.Date(bed * 1000L), 32, 0, 8, 1, 4);
        ArgumentCaptor<java.util.function.Consumer> list = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        ArgumentCaptor<java.util.function.Consumer> read = ArgumentCaptor.forClass(java.util.function.Consumer.class);
        when(health.listFilesForWakeGuard(any())).thenReturn(true);
        when(fetcher.probe(any(), any())).thenReturn(true);
        enable(Mode.RING); idle(1);
        for (int i = 0; i < 3; i++) {
            if (i > 0) { now = now.plusMinutes(5); idle(5 * 60_000); }
            verify(health, times(i + 1)).listFilesForWakeGuard(list.capture());
            list.getValue().accept(java.util.Collections.singletonList(sleep));
            verify(fetcher, times(i + 1)).probe(eq(sleep), read.capture());
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(40).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            b.put(sleep.toBytes()).put((byte) 0).put((byte) 0).put((byte) 0)
                    .putInt((int) bed).putInt((int) (now.toEpochSecond() - 30));
            read.getValue().accept(b.array());
        }
        verify(schedule, times(1)).requestWakeGuardRing();
        verify(health, never()).ackRecordedData(any());
        manager.dispose();
    }
    @Test public void probeLogUsesContextResolvedAfterConstruction() {
        when(support.getContext()).thenReturn(null);
        XiaomiWakeGuardSleepProbe probe = new XiaomiWakeGuardSleepProbe(support::getContext);
        when(support.getContext()).thenReturn(getContext());
        probe.log("hello");
        assertTrue(new java.io.File(getContext().getFilesDir(), "wake-guard-probe/probe.log").length() > 0);
    }
}
