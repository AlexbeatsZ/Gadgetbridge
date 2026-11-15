package nodomain.freeyourgadget.gadgetbridge.activities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.preference.CheckBoxPreference;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.materialswitch.MaterialSwitch;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import nodomain.freeyourgadget.gadgetbridge.R;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {23, 35})
public class SettingsAppearanceTest {
    private Context context;

    @Before
    public void setUp() {
        context = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.GadgetbridgeTheme);
        context.getTheme().applyStyle(R.style.AppPreferenceThemeOverlay, true);
    }

    @Test
    public void nativePreferenceTypesUseSettingsRows() {
        final Preference[] preferences = {
                new Preference(context),
                new ListPreference(context),
                new MultiSelectListPreference(context),
                new EditTextPreference(context),
                new DropDownPreference(context),
                new CheckBoxPreference(context),
                new SwitchPreferenceCompat(context)
        };

        for (final Preference preference : preferences) {
            assertEquals(preference.getClass().getSimpleName(),
                    R.layout.preference_settings, preference.getLayoutResource());
        }
    }

    @Test
    public void rowBindsTitleSummaryIconAndSwitch() {
        final SwitchPreferenceCompat preference = new SwitchPreferenceCompat(context);
        preference.setPersistent(false);
        preference.setTitle("Automatic export");
        preference.setSummary("Every hour");
        preference.setIcon(R.drawable.ic_contrast);
        preference.setChecked(true);

        final FrameLayout parent = new FrameLayout(context);
        final LayoutInflater inflater = LayoutInflater.from(context);
        final View row = inflater.inflate(preference.getLayoutResource(), parent, false);
        final ViewGroup widgetFrame = row.findViewById(android.R.id.widget_frame);
        inflater.inflate(preference.getWidgetLayoutResource(), widgetFrame);
        preference.onBindViewHolder(PreferenceViewHolder.createInstanceForTests(row));

        assertEquals("Automatic export", ((TextView) row.findViewById(android.R.id.title)).getText());
        assertEquals("Every hour", ((TextView) row.findViewById(android.R.id.summary)).getText());
        assertEquals(View.VISIBLE, row.findViewById(R.id.icon_frame).getVisibility());
        assertEquals(Math.round(20 * context.getResources().getDisplayMetrics().density), row.getPaddingStart());

        final MaterialSwitch toggle = row.findViewById(R.id.switchWidget);
        assertNotNull(toggle);
        assertTrue(toggle.isChecked());
        assertTrue(row.performClick());
        assertFalse(preference.isChecked());
    }

    @Test
    public void settingsPageKeepsTitleAndFragmentContainer() {
        final View page = LayoutInflater.from(context).inflate(R.layout.activity_settings, null);
        assertNotNull(page.findViewById(R.id.settings_page_title));
        assertNotNull(page.findViewById(R.id.settings_container));
        assertEquals(Math.round(16 * context.getResources().getDisplayMetrics().density), page.getPaddingStart());
    }

    @Test
    public void launcherAndWelcomeArtworkResolveOnSupportedApis() {
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher));
        assertNotNull(context.getDrawable(R.mipmap.ic_launcher_round));
        assertNotNull(context.getDrawable(R.drawable.splash_icon));
    }
}
