package nodomain.freeyourgadget.gadgetbridge.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class ThemePreferenceMigrationTest {
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("theme_upgrade", Context.MODE_PRIVATE);
        preferences.edit().clear().putString(GBPrefsMigrator.PREFS_VERSION, "72").apply();
    }

    @Test
    public void existingSystemThemeMigratesThroughRegisteredMigration() {
        preferences.edit()
                .putString("pref_key_theme", "system")
                .putBoolean("pref_key_theme_amoled_black", false)
                .putString("datetime_timezone", "Asia/Shanghai")
                .putBoolean("auto_export_upload_enabled", true)
                .apply();

        GBPrefsMigrator.migratePrefsIfNeeded(preferences);

        assertEquals("dynamic", preferences.getString("pref_key_theme", null));
        assertEquals("73", preferences.getString(GBPrefsMigrator.PREFS_VERSION, null));
        assertEquals("Asia/Shanghai", preferences.getString("datetime_timezone", null));
        assertEquals(true, preferences.getBoolean("auto_export_upload_enabled", false));
        assertFalse(preferences.getBoolean("pref_key_theme_amoled_black", true));
    }

    @Test
    public void explicitThemesAndAccentChoicesSurviveUpgrade() {
        for (final String theme : new String[]{"light", "dark", "dynamic"}) {
            preferences.edit()
                    .putString(GBPrefsMigrator.PREFS_VERSION, "72")
                    .putString("pref_key_theme", theme)
                    .putString("pref_key_accent_color", "coral")
                    .putBoolean("pref_key_theme_amoled_black", true)
                    .apply();

            GBPrefsMigrator.migratePrefsIfNeeded(preferences);

            assertEquals(theme, preferences.getString("pref_key_theme", null));
            assertEquals("coral", preferences.getString("pref_key_accent_color", null));
            assertEquals(true, preferences.getBoolean("pref_key_theme_amoled_black", false));
        }
    }

    @Test
    public void manualSystemChoiceAfterMigrationSurvivesLaterStarts() {
        preferences.edit().putString("pref_key_theme", "system").apply();
        GBPrefsMigrator.migratePrefsIfNeeded(preferences);
        assertEquals("dynamic", preferences.getString("pref_key_theme", null));

        preferences.edit().putString("pref_key_theme", "system").apply();
        GBPrefsMigrator.migratePrefsIfNeeded(preferences);
        GBPrefsMigrator.migratePrefsIfNeeded(preferences);

        assertEquals("system", preferences.getString("pref_key_theme", null));
        assertEquals("73", preferences.getString(GBPrefsMigrator.PREFS_VERSION, null));
    }

    @Test
    public void unsetThemeContinuesToUseDynamicDefault() {
        GBPrefsMigrator.migratePrefsIfNeeded(preferences);

        assertFalse(preferences.contains("pref_key_theme"));
        assertEquals("dynamic", preferences.getString("pref_key_theme", "dynamic"));
    }
}
