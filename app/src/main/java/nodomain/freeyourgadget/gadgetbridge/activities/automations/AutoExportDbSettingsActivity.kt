/*  Copyright (C) 2025 José Rebelo

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.activities.automations

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.database.DatabaseUploader
import nodomain.freeyourgadget.gadgetbridge.database.PeriodicDbExporter
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import java.util.Date

class AutoExportDbSettingsActivity : AbstractSettingsActivityV2() {
    override fun newFragment(): PreferenceFragmentCompat {
        return AutoExportDbSettingsFragment()
    }

    companion object {
        class AutoExportDbSettingsFragment : AbstractAutoExportSettingsFragment(PeriodicDbExporter) {
            private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == DatabaseUploader.PREF_LAST_SUCCESS || key == DatabaseUploader.PREF_LAST_ERROR) {
                    updateUploadStatus()
                }
            }

            override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
                super.onCreatePreferences(savedInstanceState, rootKey)
                addPreferencesFromResource(R.xml.auto_export_upload_settings)

                findPreference<EditTextPreference>(DatabaseUploader.PREF_URL)?.apply {
                    if (text.isNullOrBlank()) text = DatabaseUploader.DEFAULT_URL
                    summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                    setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    }
                }
                findPreference<EditTextPreference>(DatabaseUploader.PREF_TOKEN)?.apply {
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                        getString(
                            if (pref.text.isNullOrBlank()) R.string.pref_summary_auto_export_upload_token_unset
                            else R.string.pref_summary_auto_export_upload_token_set
                        )
                    }
                    setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                }
                updateUploadStatus()
            }

            override fun onResume() {
                super.onResume()
                GBApplication.getPrefs().preferences.registerOnSharedPreferenceChangeListener(prefsListener)
                updateUploadStatus()
            }

            override fun onPause() {
                GBApplication.getPrefs().preferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
                super.onPause()
            }

            private fun updateUploadStatus() {
                val pref = findPreference<Preference>(DatabaseUploader.PREF_STATUS) ?: return
                val prefs = GBApplication.getPrefs()
                val error = prefs.getString(DatabaseUploader.PREF_LAST_ERROR, "") ?: ""
                val success = prefs.getLong(DatabaseUploader.PREF_LAST_SUCCESS, 0)
                pref.summary = when {
                    error.isNotEmpty() -> {
                        val time = error.substringBefore('|').toLongOrNull() ?: 0
                        getString(
                            R.string.auto_export_upload_status_failed,
                            DateTimeUtils.formatDateTime(Date(time)),
                            error.substringAfter('|')
                        )
                    }
                    success > 0 -> getString(
                        R.string.auto_export_upload_status_success,
                        DateTimeUtils.formatDateTime(Date(success))
                    )
                    else -> getString(R.string.unknown)
                }
            }
        }
    }
}
