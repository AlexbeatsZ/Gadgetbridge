package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.preference.Preference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsHandler;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class XiaomiTransitCardMigrationTest {
    @Test
    public void opensOfficialAppWhenInstalled() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            final Activity activity = controller.get();
            final ComponentName launcher = new ComponentName(
                    "com.mi.health", "com.xiaomi.fitness.login.SplashActivity");
            final AlertDialog dialog = openMigrationDialog(activity,
                    new Intent(Intent.ACTION_MAIN).setComponent(launcher));

            assertEquals(activity.getString(R.string.xiaomi_transit_open_mi_fitness),
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertNull(shadowOf(activity).getNextStartedActivity());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(launcher, shadowOf(activity).getNextStartedActivity().getComponent());
        }
    }

    @Test
    public void opensOfficialDownloadWhenAppUnavailable() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            final Activity activity = controller.get();
            final AlertDialog dialog = openMigrationDialog(activity, null);

            assertEquals(activity.getString(R.string.xiaomi_transit_download_mi_fitness),
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            assertNull(shadowOf(activity).getNextStartedActivity());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            final Intent destination = shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, destination.getAction());
            assertEquals("https://hlth.io.mi.com/download?appId=mifitness", destination.getDataString());
        }
    }

    private static AlertDialog openMigrationDialog(final Activity activity, final Intent officialApp) {
        final PackageManager packages = mock(PackageManager.class);
        when(packages.getLaunchIntentForPackage("com.mi.health")).thenReturn(officialApp);
        final ContextThemeWrapper context = new ContextThemeWrapper(activity, R.style.GadgetbridgeTheme) {
            @Override
            public PackageManager getPackageManager() {
                return packages;
            }
        };
        final Preference migration = new Preference(context);
        final DeviceSpecificSettingsHandler handler = mock(DeviceSpecificSettingsHandler.class);
        when(handler.findPreference("xiaomi_transit_card_migration")).thenReturn(migration);
        when(handler.getContext()).thenReturn(context);

        new XiaomiSettingsCustomizer().customizeSettings(handler, null, null);
        migration.performClick();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }
}
