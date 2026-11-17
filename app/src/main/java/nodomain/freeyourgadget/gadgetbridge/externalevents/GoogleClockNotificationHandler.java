package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.Notification;
import android.service.notification.StatusBarNotification;

import java.util.HashMap;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.apps.NotificationAppRepository;
import nodomain.freeyourgadget.gadgetbridge.externalevents.notifications.apps.NotificationAppRules;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;

/** Google Clock 9.2 protects its alarm broadcasts with a signature permission. */
final class GoogleClockNotificationHandler {
    private final Map<String, Integer> activeAlarms = new HashMap<>();

    boolean onPosted(final StatusBarNotification sbn) {
        if (!AlarmClockReceiver.PACKAGE_CLOCK_GOOGLE.equals(sbn.getPackageName())) {
            return false;
        }
        final Notification notification = sbn.getNotification();
        final NotificationAppRepository repository = NotificationAppRepository.INSTANCE;
        final boolean ringing = Notification.CATEGORY_ALARM.equals(notification.category) &&
                notification.fullScreenIntent != null &&
                NotificationAppRules.INSTANCE.shouldSend(repository.get(sbn.getPackageName()), repository.getDefaultAction());
        if (!ringing) {
            return onRemoved(sbn);
        }
        if (activeAlarms.containsKey(sbn.getKey())) {
            return true;
        }
        final NotificationSpec alarm = new NotificationSpec(-1, sbn.getPostTime());
        alarm.setType(NotificationType.GENERIC_ALARM_CLOCK);
        alarm.setSourceAppId(sbn.getPackageName());
        alarm.setSourceName(GBApplication.getContext().getString(R.string.menuitem_alarm));
        final CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        alarm.setTitle(title == null ? alarm.getSourceName() : title.toString());
        activeAlarms.put(sbn.getKey(), alarm.getId());
        GBApplication.deviceService().onNotification(alarm);
        return true;
    }

    boolean onRemoved(final StatusBarNotification sbn) {
        final Integer id = activeAlarms.remove(sbn.getKey());
        if (id == null) {
            return false;
        }
        GBApplication.deviceService().onDeleteNotification(id);
        return true;
    }

    void clear() {
        for (final int id : activeAlarms.values()) {
            GBApplication.deviceService().onDeleteNotification(id);
        }
        activeAlarms.clear();
    }
}
