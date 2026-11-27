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
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.PhoneAlarm;
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

    private void emptyNativeAlarms() {
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0)
                .setSchedule(Schedule.newBuilder().setAlarms(Alarms.newBuilder().setMaxAlarms(10))).build());
    }

    @Test public void wakeGuardIsTransientAndRealPhoneAlarmTakesOverWithoutDismiss() {
        emptyNativeAlarms();
        Runnable ended = mock(Runnable.class);
        assertTrue(service.startWakeGuardAlarm("Stay awake", ended));
        final XiaomiProto.Command command = sentCommands().get(0);
        assertEquals(16, command.getSubtype());
        assertEquals("Stay awake", command.getSchedule().getPhoneAlarmOperation().getPhoneAlarm().getLabel());
        assertTrue(service.startWakeGuardAlarm("Other", ended));
        NotificationSpec real = new NotificationSpec(42, System.currentTimeMillis());
        real.setType(NotificationType.GENERIC_ALARM_CLOCK);
        service.onPhoneAlarm(real);
        verify(ended).run();
        service.stopWakeGuardAlarm(true);
        assertEquals(3, sentCommands().size());
        assertFalse(service.startWakeGuardAlarm("Stay awake", ended));
    }

    @Test public void wakeGuardDismissesOnlyOwnedCurrentAlarmAndDisconnectDoesNotQueue() {
        emptyNativeAlarms();
        Runnable ended = mock(Runnable.class);
        assertTrue(service.startWakeGuardAlarm("Stay awake", ended));
        service.stopWakeGuardAlarm(true);
        assertEquals(PhoneAlarm.OpCode.DISMISS, sentCommands().get(1).getSchedule().getPhoneAlarmOperation().getOpCode());
        service.stopWakeGuardAlarm(true);
        assertEquals(2, sentCommands().size());
        assertTrue(service.startWakeGuardAlarm("Stay awake", ended));
        service.onDisconnect();
        verify(ended, never()).run();
        service.stopWakeGuardAlarm(false);
        assertEquals(3, sentCommands().size());
        assertFalse(service.startWakeGuardAlarm("Stay awake", ended));
    }

    @Test public void nativeWatchDismissReleasesOnlyMatchingWakeGuard() {
        emptyNativeAlarms();
        Runnable ended = mock(Runnable.class);
        assertTrue(service.startWakeGuardAlarm("Stay awake", ended));
        PhoneAlarm alarm = sentCommands().get(0).getSchedule().getPhoneAlarmOperation().getPhoneAlarm();
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(16)
                .setSchedule(Schedule.newBuilder().setPhoneAlarmOperation(PhoneAlarm.Operation.newBuilder()
                        .setPhoneAlarm(alarm).setOpCode(PhoneAlarm.OpCode.DISMISS))).build());
        verify(ended).run();
        service.stopWakeGuardAlarm(true);
        assertEquals(1, sentCommands().size());
    }

    @Test public void wakeGuardDoesNotOverlapNearbyLocalAlarmIncludingS4ZeroId() {
        final java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0)
                .setSchedule(Schedule.newBuilder().setAlarms(Alarms.newBuilder().addAlarm(
                        AlarmInfo.newBuilder().setId(0).setAlarmDetails(AlarmDetails.newBuilder()
                                .setTime(WearCommon.HourMinute.newBuilder().setHour(now.getHour()).setMinute(now.getMinute()))
                                .setEnabled(false))))).build());
        assertFalse(service.startWakeGuardAlarm("Stay awake", mock(Runnable.class)));
        verify(support, never()).sendCommand(anyString(), any(XiaomiProto.Command.class));
    }

    @Test public void s4ZeroSlotImportsAndTogglesTheSameIdWithoutDeletingOtherAlarms() {
        service.handleCommand(XiaomiProto.Command.newBuilder().setType(17).setSubtype(0)
                .setSchedule(Schedule.newBuilder().setAlarms(Alarms.newBuilder().addAlarm(
                        AlarmInfo.newBuilder().setId(0).setAlarmDetails(AlarmDetails.newBuilder()
                                .setTime(WearCommon.HourMinute.newBuilder().setHour(7).setMinute(30))
                                .setEnabled(true).setRepeatMode(0).setSmart(2))))).build());
        ArrayList<Alarm> alarms = new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        assertFalse(alarms.get(0).getUnused()); alarms.get(0).setEnabled(false);
        service.onSetAlarms(alarms);
        assertEquals(5, sentCommands().get(0).getSubtype());
        assertEquals(0, sentCommands().get(0).getSchedule().getEditAlarm().getId());
        assertEquals(1, sentCommands().size());
    }

    @Test public void nonS4AlarmsRetainOneBasedMapping() {
        device = new GBDevice("C4:57:81:00:00:02", "Xiaomi Watch S3", null, null, DeviceType.XIAOMI_WATCH_S3);
        when(support.getDevice()).thenReturn(device);
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit().putInt("alarm_slots", 10).commit();
        service.handleCommand(listReply(5,31)); // raw id 3 is GB position 2
        ArrayList<Alarm> alarms = new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        assertFalse(alarms.get(2).getUnused()); alarms.get(2).setEnabled(false);
        service.onSetAlarms(alarms);
        assertEquals(3, sentCommands().get(0).getSchedule().getEditAlarm().getId());
    }

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
        assertEquals(3, command.getSchedule().getEditAlarm().getId());
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

    @Test
    public void phoneRingingAndDismissUseNativePhoneAlarmProtocol() {
        final NotificationSpec alarm = new NotificationSpec(42, 1794900000000L);
        alarm.setType(NotificationType.GENERIC_ALARM_CLOCK);
        alarm.setTitle("Phone alarm");
        assertTrue(service.onPhoneAlarm(alarm));
        assertTrue(service.onDeletePhoneAlarm(42));
        final List<XiaomiProto.Command> commands = sentCommands();
        assertEquals(2, commands.size());
        for (final XiaomiProto.Command command : commands) {
            assertEquals(17, command.getType());
            assertEquals(16, command.getSubtype());
            assertEquals(42, command.getSchedule().getPhoneAlarmOperation().getPhoneAlarm().getId());
        }
        final PhoneAlarm.Operation alert = commands.get(0).getSchedule().getPhoneAlarmOperation();
        assertEquals(PhoneAlarm.OpCode.ALERT, alert.getOpCode());
        assertEquals(1794900000, alert.getPhoneAlarm().getAlertTime());
        assertEquals("Phone alarm", alert.getPhoneAlarm().getLabel());
        assertEquals(PhoneAlarm.OpCode.DISMISS, commands.get(1).getSchedule().getPhoneAlarmOperation().getOpCode());
        assertFalse(service.onDeletePhoneAlarm(42));
    }

    @Test
    public void ordinaryNotificationsStayOnNotificationService() {
        final NotificationSpec notification = new NotificationSpec();
        notification.setType(NotificationType.GENERIC_SMS);
        assertFalse(service.onPhoneAlarm(notification));
        assertFalse(service.onDeletePhoneAlarm(notification.getId()));
        verify(support, never()).sendCommand(anyString(), any(XiaomiProto.Command.class));
    }

    @Test
    public void phoneAlarmStopStillWorksAfterWatchReinitializes() {
        final NotificationSpec alarm = new NotificationSpec();
        alarm.setType(NotificationType.GENERIC_ALARM_CLOCK);
        assertTrue(service.onPhoneAlarm(alarm));
        service.initialize();
        assertTrue(service.onDeletePhoneAlarm(alarm.getId()));
        assertEquals(PhoneAlarm.OpCode.DISMISS,
                sentCommands().get(1).getSchedule().getPhoneAlarmOperation().getOpCode());
    }

    private XiaomiProto.Command listReply(final int repeatMode, final int repeatFlags) {
        return XiaomiProto.Command.newBuilder().setType(17).setSubtype(0).setSchedule(Schedule.newBuilder()
                .setAlarms(Alarms.newBuilder().setMaxAlarms(10).setSupportsSmartWakeup(false)
                        .addAlarm(AlarmInfo.newBuilder().setId(3).setAlarmDetails(AlarmDetails.newBuilder()
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
