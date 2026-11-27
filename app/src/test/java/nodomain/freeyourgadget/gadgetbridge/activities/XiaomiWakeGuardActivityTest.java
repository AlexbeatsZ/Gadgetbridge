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
                    activity.getSupportFragmentManager().findFragmentById(R.id.settings_container);
            assertNotNull(fragment);
            assertEquals(activity.getString(R.string.wake_guard_title),
                    ((android.widget.TextView) activity.findViewById(R.id.settings_page_title)).getText().toString());

            PreferenceScreen screen = fragment.getPreferenceScreen();
            assertNotNull(fragment.findPreference(PREF_STATUS));
            assertTrue(containsTitle(screen, "Morning"));
            assertTrue(containsTitle(screen, activity.getString(R.string.wake_guard_test_vibrate)));
            assertTrue(containsTitle(screen, activity.getString(R.string.wake_guard_test_ring)));
            assertNull(fragment.findPreference(STOP_RING));
            fragment.addPreferencesFromResource(R.xml.devicesettings_xiaomi_wake_guard);
            assertNotNull(fragment.findPreference(PREF_SCREEN).getIcon());
        }
    }

    @Test public void editorUsesSharedPixelRowsAndBackDoesNotSaveDraft() throws Exception {
        GBDevice device = createDummyGDevice("00:11:22:33:44:61");
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit().remove(PREF_RULES).commit();
        Intent intent = new Intent(getContext(), XiaomiWakeGuardActivity.class).putExtra(GBDevice.EXTRA_DEVICE, device);
        try (ActivityController<XiaomiWakeGuardActivity> controller = Robolectric.buildActivity(XiaomiWakeGuardActivity.class, intent)) {
            XiaomiWakeGuardActivity activity = controller.setup().get();
            activity.getSupportFragmentManager().executePendingTransactions();
            XiaomiWakeGuardActivity.RuleFragment editor = new XiaomiWakeGuardActivity.RuleFragment();
            editor.setArguments(new android.os.Bundle());
            activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, editor)
                    .addToBackStack("edit").commit();
            activity.getSupportFragmentManager().executePendingTransactions();
            assertEquals(activity.getString(R.string.wake_guard_add),
                    ((android.widget.TextView) activity.findViewById(R.id.settings_page_title)).getText().toString());
            for (String key : new String[]{"name", "enabled", "days", "start", "end", "mode", "confirm", "repeat"}) {
                Preference p = editor.findPreference("wake_guard_draft_" + key);
                assertNotNull(p);
                assertEquals(key, R.layout.preference_settings, p.getLayoutResource());
                assertFalse(p.isPersistent());
            }
            androidx.preference.EditTextPreference name = editor.findPreference("wake_guard_draft_name");
            assertTrue(name.callChangeListener("Draft")); name.setText("Draft");
            // The actual shared settings dialog router must select the Material implementation.
            editor.onDisplayPreferenceDialog(name);
            activity.getSupportFragmentManager().executePendingTransactions();
            androidx.fragment.app.Fragment dialog = activity.getSupportFragmentManager()
                    .findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG");
            assertTrue(dialog instanceof nodomain.freeyourgadget.gadgetbridge.util.dialogs.MaterialEditTextPreferenceDialogFragment);
            ((androidx.fragment.app.DialogFragment) dialog).dismissNow();
            androidx.preference.MultiSelectListPreference days = editor.findPreference("wake_guard_draft_days");
            assertEquals(new java.util.HashSet<>(java.util.Arrays.asList("0", "1", "2", "3", "4")), days.getValues());
            assertTrue(days.getSummary().toString().contains(activity.getResources().getStringArray(R.array.wake_guard_weekdays)[0]));
            assertFalse(days.callChangeListener(Collections.emptySet()));
            activity.getSupportFragmentManager().popBackStackImmediate();
            assertEquals("[]", GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).getString(PREF_RULES, "[]"));
        }
    }

    @Test public void savingEditorPersistsRuleAndReturnsToList() throws Exception {
        GBDevice device = createDummyGDevice("00:11:22:33:44:62");
        android.content.SharedPreferences prefs = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        prefs.edit().remove(PREF_RULES).commit();
        Intent intent = new Intent(getContext(), XiaomiWakeGuardActivity.class).putExtra(GBDevice.EXTRA_DEVICE, device);
        try (ActivityController<XiaomiWakeGuardActivity> controller = Robolectric.buildActivity(XiaomiWakeGuardActivity.class, intent)) {
            XiaomiWakeGuardActivity activity = controller.setup().get();
            activity.getSupportFragmentManager().executePendingTransactions();
            XiaomiWakeGuardActivity.RuleFragment editor = new XiaomiWakeGuardActivity.RuleFragment();
            editor.setArguments(new android.os.Bundle());
            activity.getSupportFragmentManager().beginTransaction().replace(R.id.settings_container, editor)
                    .addToBackStack("edit").commit();
            activity.getSupportFragmentManager().executePendingTransactions();
            Preference name = editor.findPreference("wake_guard_draft_name");
            assertTrue(name.callChangeListener("Weekday"));
            androidx.appcompat.view.menu.MenuBuilder menu = new androidx.appcompat.view.menu.MenuBuilder(activity);
            editor.onCreateMenu(menu, activity.getMenuInflater());
            assertTrue(editor.onMenuItemSelected(menu.getItem(0)));
            activity.getSupportFragmentManager().executePendingTransactions();
            Rule rule = parseRules(prefs.getString(PREF_RULES, "[]")).get(0);
            assertEquals("Weekday", rule.name);
            assertEquals(31, rule.days);
            assertEquals(Mode.VIBRATE, rule.mode);
            assertEquals(1, rule.confirmationMinutes);
            assertEquals(5, rule.repeatMinutes);
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
