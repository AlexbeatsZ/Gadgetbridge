package nodomain.freeyourgadget.gadgetbridge.prefs.migrators;

import android.content.SharedPreferences;

import nodomain.freeyourgadget.gadgetbridge.prefs.AbstractPreferenceMigrator;

public class PreferenceMigrator73 extends AbstractPreferenceMigrator {
    @Override
    public void migrate(final int oldVersion, final SharedPreferences sharedPrefs, final SharedPreferences.Editor editor) {
        // Apply the new automatic palette to existing installations once during upgrade.
        if ("system".equals(sharedPrefs.getString("pref_key_theme", null))) {
            editor.putString("pref_key_theme", "dynamic");
        }
    }
}
