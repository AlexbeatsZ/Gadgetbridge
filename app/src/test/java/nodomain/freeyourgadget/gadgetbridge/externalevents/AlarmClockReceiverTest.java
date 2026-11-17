package nodomain.freeyourgadget.gadgetbridge.externalevents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import android.content.Intent;
import android.app.Notification;
import android.app.PendingIntent;
import android.os.Process;
import android.service.notification.StatusBarNotification;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.apps.NotificationAppRepository;
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.apps.NotificationDefaultAction;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceService;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class AlarmClockReceiverTest extends TestBase {
    private Field serviceField;
    private DeviceService originalService;
    private DeviceService service;
    private AlarmClockReceiver receiver;

    @Before
    public void setupReceiver() throws Exception {
        serviceField = GBApplication.class.getDeclaredField("deviceService");
        serviceField.setAccessible(true);
        originalService = (DeviceService) serviceField.get(null);
        service = mock(DeviceService.class);
        serviceField.set(null, service);
        receiver = new AlarmClockReceiver();
        NotificationAppRepository.INSTANCE.setDefaultAction(NotificationDefaultAction.SEND);
    }

    @After
    public void restoreService() throws Exception {
        serviceField.set(null, originalService);
        NotificationAppRepository.INSTANCE.setDefaultAction(NotificationDefaultAction.SEND);
    }

    @Test
    public void googleClockAlertAndDoneUseTheSameNotificationId() {
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_ALERT_ACTION);
        final ArgumentCaptor<NotificationSpec> captor = ArgumentCaptor.forClass(NotificationSpec.class);
        verify(service).onNotification(captor.capture());
        assertEquals(NotificationType.GENERIC_ALARM_CLOCK, captor.getValue().getType());
        assertEquals(AlarmClockReceiver.PACKAGE_CLOCK_GOOGLE, captor.getValue().getSourceAppId());
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_DONE_ACTION);
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_DONE_ACTION);
        verify(service, times(1)).onDeleteNotification(captor.getValue().getId());
    }

    @Test
    public void stoppingStillWorksAfterNotificationRulesChange() {
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_ALERT_ACTION);
        NotificationAppRepository.INSTANCE.setDefaultAction(NotificationDefaultAction.DO_NOT_SEND);
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_DONE_ACTION);
        verify(service).onDeleteNotification(anyInt());
    }

    @Test
    public void otherClockCannotStopTheActiveAlarm() {
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_ALERT_ACTION);
        receive(AlarmClockReceiver.ALARM_DONE_ACTION);
        verify(service, never()).onDeleteNotification(anyInt());
    }

    @Test
    public void blockedClockCannotStartAnAlarm() {
        NotificationAppRepository.INSTANCE.setDefaultAction(NotificationDefaultAction.DO_NOT_SEND);
        receive(AlarmClockReceiver.GOOGLE_CLOCK_ALARM_ALERT_ACTION);
        verify(service, never()).onNotification(any());
    }

    @Test
    public void googleClockRingingNotificationIsForwardedOnlyOnce() {
        final GoogleClockNotificationHandler handler = new GoogleClockNotificationHandler();
        final StatusBarNotification alarm = clockNotification(true);
        assertTrue(handler.onPosted(alarm));
        assertTrue(handler.onPosted(alarm));
        final ArgumentCaptor<NotificationSpec> captor = ArgumentCaptor.forClass(NotificationSpec.class);
        verify(service, times(1)).onNotification(captor.capture());
        assertEquals("Wake up", captor.getValue().getTitle());
        assertEquals(NotificationType.GENERIC_ALARM_CLOCK, captor.getValue().getType());
        assertTrue(handler.onRemoved(alarm));
        assertFalse(handler.onRemoved(alarm));
        verify(service).onDeleteNotification(captor.getValue().getId());
    }

    @Test
    public void upcomingAndSnoozedNotificationsDoNotRing() {
        final GoogleClockNotificationHandler handler = new GoogleClockNotificationHandler();
        assertFalse(handler.onPosted(clockNotification(false)));
        verify(service, never()).onNotification(any());
        assertTrue(handler.onPosted(clockNotification(true)));
        assertTrue(handler.onPosted(clockNotification(false)));
        verify(service).onDeleteNotification(anyInt());
    }

    @Test
    public void clockNotificationRemovalStillStopsAfterRulesChange() {
        final GoogleClockNotificationHandler handler = new GoogleClockNotificationHandler();
        handler.onPosted(clockNotification(true));
        NotificationAppRepository.INSTANCE.setDefaultAction(NotificationDefaultAction.DO_NOT_SEND);
        assertTrue(handler.onRemoved(clockNotification(true)));
        verify(service).onDeleteNotification(anyInt());
    }

    @Test
    public void listenerShutdownEndsForwardedAlarms() {
        final GoogleClockNotificationHandler handler = new GoogleClockNotificationHandler();
        handler.onPosted(clockNotification(true));
        handler.clear();
        handler.clear();
        verify(service, times(1)).onDeleteNotification(anyInt());
    }

    private StatusBarNotification clockNotification(final boolean ringing) {
        final Notification.Builder builder = new Notification.Builder(getContext())
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Wake up");
        if (ringing) {
            builder.setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(
                    PendingIntent.getActivity(getContext(), 0, new Intent(), PendingIntent.FLAG_IMMUTABLE), true);
        }
        return new StatusBarNotification(AlarmClockReceiver.PACKAGE_CLOCK_GOOGLE,
                AlarmClockReceiver.PACKAGE_CLOCK_GOOGLE, 7, null, 1000, 0, 0,
                builder.build(), Process.myUserHandle(), 1794900000000L);
    }

    private void receive(final String action) {
        receiver.onReceive(getContext(), new Intent(action));
    }
}
