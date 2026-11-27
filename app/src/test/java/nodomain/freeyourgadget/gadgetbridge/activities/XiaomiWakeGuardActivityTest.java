/* SPDX-License-Identifier: AGPL-3.0-or-later */

package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import org.junit.Test;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.xiaomi.XiaomiWakeGuardActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.*;
import static nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.*;

@Config(sdk = {23, 35})
public class XiaomiWakeGuardActivityTest extends TestBase {
    @Test public void nativeSettingsOpenAndRenderSavedWeeklyRules() throws Exception {
        GBDevice device = createDummyGDevice("00:11:22:33:44:60");
        Rule rule = new Rule("morning", "Morning", true, 31, 480, 720, Mode.VIBRATE, 1, 5);
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putString(PREF_RULES, serializeRules(Collections.singletonList(rule))).commit();
        Intent intent = new Intent(getContext(), XiaomiWakeGuardActivity.class).putExtra(GBDevice.EXTRA_DEVICE, device);
        try (ActivityController<XiaomiWakeGuardActivity> controller = Robolectric.buildActivity(XiaomiWakeGuardActivity.class, intent)) {
            XiaomiWakeGuardActivity activity = controller.setup().get();
            activity.getSupportFragmentManager().executePendingTransactions();
            XiaomiWakeGuardActivity.RulesFragment fragment = (XiaomiWakeGuardActivity.RulesFragment)
                    activity.getSupportFragmentManager().findFragmentById(R.id.wake_guard_preferences);
            assertNotNull(fragment);
            PreferenceScreen screen = fragment.getPreferenceScreen();
            assertNotNull(fragment.findPreference(PREF_STATUS));
            assertTrue(containsTitle(screen, "Morning"));
            assertTrue(containsTitle(screen, activity.getString(R.string.wake_guard_test_vibrate)));
            assertTrue(containsTitle(screen, activity.getString(R.string.wake_guard_test_ring)));
            assertTrue(containsTitle(screen, activity.getString(R.string.wake_guard_stop_ring)));
        }
    }

    private boolean containsTitle(androidx.preference.PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (title.contentEquals(p.getTitle() == null ? "" : p.getTitle())) return true;
            if (p instanceof androidx.preference.PreferenceGroup && containsTitle((androidx.preference.PreferenceGroup) p, title)) return true;
        }
        return false;
    }
}
