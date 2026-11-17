package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AlarmDetails;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AlarmInfo;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Alarms;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Schedule;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.WearCommon;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class XiaomiScheduleServiceTest extends TestBase {
    private XiaomiSupport support;
    private XiaomiScheduleService service;
    private GBDevice device;

    @Before
    public void setupService() {
        device = new GBDevice("C4:57:81:00:00:01", "Xiaomi Watch S4 0001", null, null, DeviceType.XIAOMI_WATCH_S4);
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit().putInt("alarm_slots", 10).commit();
        support = mock(XiaomiSupport.class);
        when(support.getDevice()).thenReturn(device);
        when(support.getContext()).thenReturn(getContext());
        service = new XiaomiScheduleService(support);
    }

    @Test
    public void watchAlarmsAreImportedIntoPreviouslyEmptyDatabase() {
        service.handleCommand(listReply(5, 31));
        final Alarm alarm = DBHelper.getAlarmsWithDefaults(device).get(3);
        assertFalse(alarm.getUnused());
        assertEquals("Class", alarm.getTitle());
        assertEquals(31, alarm.getRepetition());
    }

    @Test
    public void failedOrMissingListPreservesDatabaseAndSlots() {
        service.handleCommand(listReply(5, 31));
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).setStatus(1).build());
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).build());
        assertFalse(DBHelper.getAlarmsWithDefaults(device).get(3).getUnused());
        assertEquals(10, device.getDeviceCoordinator().getAlarmSlotCount(device));
    }

    @Test
    public void toggleUsesOfficialEnableCommandAndReadback() {
        service.handleCommand(listReply(5, 31));
        final ArrayList<Alarm> alarms = new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        alarms.get(3).setEnabled(false);
        service.onSetAlarms(alarms);
        final XiaomiProto.Command command = sentCommands().get(0);
        assertEquals(5, command.getSubtype());
        assertEquals(4, command.getSchedule().getEditAlarm().getId());
        assertFalse(command.getSchedule().getEditAlarm().getAlarmDetails().getEnabled());
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(5).setStatus(0).build());
        verify(support).sendCommand("get alarms", 17, 0);
    }

    @Test
    public void holidayAwareRepetitionSurvivesTimeEdit() {
        service.handleCommand(listReply(2, 31));
        final ArrayList<Alarm> alarms = new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        alarms.get(3).setHour(8);
        service.onSetAlarms(alarms);
        final AlarmDetails details = sentCommands().get(0).getSchedule().getEditAlarm().getAlarmDetails();
        assertEquals(2, details.getRepeatMode());
        assertEquals(31, details.getRepeatFlags());
        assertEquals("Class", details.getLabel());
        assertFalse(details.hasNextRingTimestamp());
    }

    @Test
    public void eachSupportedRepeatPatternRoundTrips() {
        for (final int repetition : new int[]{0, 127, 1, 2, 31, 96, 85}) {
            final Alarm alarm = new Alarm();
            alarm.setHour(7);
            alarm.setMinute(30);
            alarm.setEnabled(true);
            alarm.setSmartWakeup(false);
            alarm.setRepetition(repetition);
            final AlarmDetails details = XiaomiScheduleService.buildAlarmDetails(alarm, null, null);
            assertEquals(repetition == 0 ? 0 : repetition == 127 ? 1 : 5, details.getRepeatMode());
            if (details.getRepeatMode() == 5) assertEquals(repetition, details.getRepeatFlags());
        }
    }

    @Test
    public void failedMutationWaitsForOtherAcksBeforeReadback() {
        service.handleCommand(listReply(5, 31));
        final ArrayList<Alarm> alarms = new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        alarms.get(3).setHour(8);
        alarms.get(0).setUnused(false);
        service.onSetAlarms(alarms);
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(2)
                .setSchedule(Schedule.newBuilder().setClockError(3)).build());
        verify(support, never()).sendCommand("get alarms", 17, 0);
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(1).setStatus(0).build());
        verify(support).sendCommand("get alarms", 17, 0);
    }

    @Test
    public void weeklyReminderUsesDayBitInsteadOfDayNumber() {
        final nodomain.freeyourgadget.gadgetbridge.entities.Reminder reminder =
                new nodomain.freeyourgadget.gadgetbridge.entities.Reminder();
        reminder.setReminderId("new");
        reminder.setMessage("Weekly reminder");
        reminder.setDate(Date.from(Instant.parse("2026-11-18T08:00:00Z")));
        reminder.setRepetition(nodomain.freeyourgadget.gadgetbridge.model.Reminder.EVERY_WEEK);
        service.onSetReminders(new ArrayList<>(List.of(reminder)));
        assertEquals(4, sentCommands().get(0).getSchedule().getCreateReminder().getRepeatFlags());
    }

    @Test
    public void replacementDeletesBeforeCreatingToFreeSlots() {
        service.handleCommand(listReply(5, 31));
        final ArrayList<Alarm> alarms = new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        alarms.get(3).setUnused(true);
        alarms.get(0).setUnused(false);
        service.onSetAlarms(alarms);
        assertEquals(4, sentCommands().get(0).getSubtype());
        assertEquals(1, sentCommands().get(1).getSubtype());
    }

    @Test
    public void savingAfterFailedInitialReadRetriesTheList() {
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).setStatus(1).build());
        service.onSetAlarms(new ArrayList<>());
        verify(support).sendCommand("get alarms", 17, 0);
    }

    private XiaomiProto.Command listReply(final int repeatMode, final int repeatFlags) {
        return XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).setSchedule(Schedule.newBuilder()
                .setAlarms(Alarms.newBuilder().setMaxAlarms(10).setSupportsSmartWakeup(false)
                        .addAlarm(AlarmInfo.newBuilder().setId(4).setAlarmDetails(AlarmDetails.newBuilder()
                                .setTime(WearCommon.HourMinute.newBuilder().setHour(7).setMinute(30))
                                .setEnabled(true).setSmart(2).setLabel("Class")
                                .setNextRingTimestamp(1794900000L)
                                .setRepeatMode(repeatMode).setRepeatFlags(repeatFlags))))).build();
    }

    private List<XiaomiProto.Command> sentCommands() {
        final ArgumentCaptor<XiaomiProto.Command> captor = ArgumentCaptor.forClass(XiaomiProto.Command.class);
        verify(support, atLeastOnce()).sendCommand(anyString(), captor.capture());
        return captor.getAllValues();
    }
}
