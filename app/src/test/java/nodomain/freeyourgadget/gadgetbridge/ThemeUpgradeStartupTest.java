package nodomain.freeyourgadget.gadgetbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import com.google.android.material.color.MaterialColors;

import nodomain.freeyourgadget.gadgetbridge.activities.AbstractGBActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.SettingsActivity;
import nodomain.freeyourgadget.gadgetbridge.prefs.GBPrefsMigrator;

@RunWith(RobolectricTestRunner.class)
@Config(application = ThemeUpgradeStartupTest.UpgradeApplication.class, sdk = 35)
public class ThemeUpgradeStartupTest {
    public static class UpgradeApplication extends GBApplication {
        @Override
        public void onCreate() {
            GBEnvironment.setupEnvironment(GBEnvironment.createLocalTestEnvironment());
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putString(GBPrefsMigrator.PREFS_VERSION, "72")
                    .putString("pref_key_theme", "system")
                    .putBoolean("pref_key_theme_amoled_black", false)
                    .apply();
            super.onCreate();
        }
    }

    @Test
    public void startupMigratesSavedSystemThemeAndFollowsSystemBrightness() {
        final GBApplication application = (GBApplication) RuntimeEnvironment.getApplication();
        assertEquals("dynamic", GBApplication.getPrefs().getString("pref_key_theme", null));
        assertEquals("73", GBApplication.getPrefs().getString(GBPrefsMigrator.PREFS_VERSION, null));
        assertTrue(GBApplication.areDynamicColorsEnabled());

        final Configuration configuration = new Configuration(application.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        application.getResources().updateConfiguration(configuration, application.getResources().getDisplayMetrics());
        assertTrue(GBApplication.isDarkThemeEnabled());
        assertEquals(R.style.GadgetbridgeThemeDynamicDark, AbstractGBActivity.getThemeResource(0, true));

        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        application.getResources().updateConfiguration(configuration, application.getResources().getDisplayMetrics());
        assertFalse(GBApplication.isDarkThemeEnabled());
        assertEquals(R.style.GadgetbridgeThemeDynamicLight, AbstractGBActivity.getThemeResource(0, false));
    }

    @Test
    public void settingsEntryUsesRoundedRowsForExistingXmlLayouts() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            final SettingsActivity activity = controller.get();
            activity.getSupportFragmentManager().executePendingTransactions();
            final PreferenceFragmentCompat fragment = (PreferenceFragmentCompat) activity.getSupportFragmentManager()
                    .findFragmentById(R.id.settings_container);
            final Preference general = fragment.findPreference("pref_screen_general");
            final Preference theme = fragment.findPreference("pref_key_theme");
            final Preference legacySwitch = fragment.findPreference("pref_key_theme_amoled_black");
            assertEquals(R.layout.preference_settings, general.getLayoutResource());
            assertEquals(R.layout.preference_settings, theme.getLayoutResource());
            final TypedValue settingsLayout = new TypedValue();
            final TypedValue legacyLayout = new TypedValue();
            activity.getResources().getValue(R.layout.preference_settings, settingsLayout, true);
            activity.getResources().getValue(legacySwitch.getLayoutResource(), legacyLayout, true);
            assertEquals(settingsLayout.string.toString(), legacyLayout.string.toString());

            final View row = LayoutInflater.from(activity).inflate(legacySwitch.getLayoutResource(), null);
            final GradientDrawable surface = (GradientDrawable) ((RippleDrawable) row.getBackground()).getDrawable(0);
            assertEquals(20 * activity.getResources().getDisplayMetrics().density, surface.getCornerRadius(), 0.01f);
            assertEquals(MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurfaceContainerLow),
                    surface.getColor().getDefaultColor());
        }
    }
}
