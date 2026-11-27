/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;
import android.content.SharedPreferences;
import org.junit.Before;
import org.junit.Test;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.*;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import static org.junit.Assert.*;

public class XiaomiWakeGuardClockTest extends TestBase {
    private SharedPreferences prefs;
    private XiaomiWakeGuardClock clock;
    private List<XiaomiProto.Command> commands;
    @Before public void setupClock(){
        prefs=GBApplication.getDeviceSpecificSharedPrefs("00:11:22:33:44:80");prefs.edit().clear().commit();
        commands=new ArrayList<>(); restart();
    }
    private void restart(){clock=new XiaomiWakeGuardClock(prefs,commands::add,
            ()->ZonedDateTime.parse("2026-11-27T09:59:45+08:00"),()->{});}
    private Alarms list(AlarmInfo... alarms){return Alarms.newBuilder().setMaxAlarms(10).addAllAlarm(List.of(alarms)).build();}
    private AlarmInfo created(int id){return AlarmInfo.newBuilder().setId(id).setAlarmDetails(AlarmDetails.newBuilder()
            .setTime(WearCommon.HourMinute.newBuilder().setHour(10).setMinute(0)).setRepeatMode(0)
            .setSmart(2).setEnabled(true)).build();}
    private void ack(int op){assertTrue(clock.onAck(XiaomiProto.Command.newBuilder().setType(17).setSubtype(op)
            .setSchedule(Schedule.newBuilder().setAckId(0)).build()));}
    private void firstCreate(){
        assertTrue(clock.request());clock.onAlarms(list());ack(1);clock.onAlarms(list(created(0)));
    }
    @Test public void firstCreateAcceptsIdZeroAndRepeatedRequestEditsSameSlot(){
        firstCreate();assertEquals("scheduled",prefs.getString("xiaomi_wake_guard_ring_status",""));
        assertTrue(prefs.contains(XiaomiWakeGuardClock.OWNER));
        clock.request();clock.onAlarms(list(created(0).toBuilder().setAlarmDetails(created(0).getAlarmDetails()
                .toBuilder().setEnabled(false)).build()));
        XiaomiProto.Command edit=commands.get(commands.size()-1);
        assertEquals(2,edit.getSubtype());assertEquals(0,edit.getSchedule().getEditAlarm().getId());
        ack(2);clock.onAlarms(list(created(0)));
        assertEquals(1,commands.stream().filter(c->c.getSubtype()==1).count());
        assertEquals(0,commands.stream().filter(c->c.getSubtype()==4).count());
    }
    @Test public void createHasNextMinuteTimeAndNeverDeletesOrDismisses(){
        firstCreate();AlarmDetails details=commands.get(1).getSchedule().getCreateAlarm();
        assertEquals(10,details.getTime().getHour());assertEquals(0,details.getTime().getMinute());
        assertEquals(0,details.getRepeatMode());assertTrue(details.getEnabled());
        assertTrue(commands.stream().allMatch(c->c.getSubtype()==0||c.getSubtype()==1));
    }
    @Test public void crashBeforeAckRecoversOneCreatedSlotInsteadOfCreatingDuplicates(){
        clock.request();clock.onAlarms(list());clock.disconnect();restart();
        clock.request();clock.onAlarms(list(created(0)));
        assertEquals(2,commands.get(commands.size()-1).getSubtype());
        assertEquals(1,commands.stream().filter(c->c.getSubtype()==1).count());
    }
    @Test public void fullWatchFailsWithoutDeletingUserAlarms(){
        clock.request();clock.onAlarms(Alarms.newBuilder().setMaxAlarms(1).addAlarm(created(4).toBuilder().setAlarmDetails(created(4).getAlarmDetails().toBuilder()
                .setTime(WearCommon.HourMinute.newBuilder().setHour(8).setMinute(0)))).build());
        assertEquals(1,commands.size());assertEquals("full",prefs.getString("xiaomi_wake_guard_ring_status",""));
    }
    @Test public void editedOwnedAlarmIsPreservedAndNewSlotAllocated(){
        firstCreate();AlarmInfo userEdit=created(0).toBuilder().setAlarmDetails(created(0).getAlarmDetails().toBuilder()
                .setTime(WearCommon.HourMinute.newBuilder().setHour(8).setMinute(0))).build();
        clock.request();clock.onAlarms(list(userEdit));assertEquals(1,commands.get(commands.size()-1).getSubtype());
        assertEquals(0,commands.stream().filter(c->c.getSubtype()==2||c.getSubtype()==4).count());
    }
    @Test public void inFlightRequestIsCoalesced(){clock.request();assertFalse(clock.request());assertEquals(1,commands.size());}
    @Test public void failureAndDisconnectDoNotQueueOrDelete(){
        clock.request();clock.onAlarms(list());
        clock.onAck(XiaomiProto.Command.newBuilder().setType(17).setSubtype(1).setStatus(1).build());
        assertEquals("failed",prefs.getString("xiaomi_wake_guard_ring_status",""));
        assertFalse(prefs.contains(XiaomiWakeGuardClock.DRAFT));clock.disconnect();assertEquals(2,commands.size());
    }
}
