/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.util;

import android.content.Context;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.externalevents.TimeChangeReceiver;

/**
 * Handles the user-configured time zone override. When set, it replaces the default time zone
 * of the whole app process, so that everything that relies on {@link TimeZone#getDefault()} or
 * {@link java.time.ZoneId#systemDefault()} (including the time sync with the devices) uses it.
 */
public final class AppTimeZone {
    private static final Logger LOG = LoggerFactory.getLogger(AppTimeZone.class);

    public static final String PREF_KEY = "datetime_timezone";

    private static boolean isOverridden = false;

    private AppTimeZone() {
    }

    /**
     * @return the configured time zone id, or null if the system time zone should be used
     */
    @Nullable
    public static String getOverrideId() {
        final Prefs prefs = GBApplication.getPrefs();
        if (prefs == null) {
            return null;
        }
        final String id = prefs.getString(PREF_KEY, "");
        if (id == null || id.isEmpty() || !Arrays.asList(TimeZone.getAvailableIDs()).contains(id)) {
            return null;
        }
        return id;
    }

    /**
     * Applies the configured time zone to the app process. Needs to be called again after
     * the system time zone changes, since Android resets the process default in that case.
     */
    public static synchronized void apply() {
        final String overrideId = getOverrideId();
        if (overrideId == null) {
            if (isOverridden) {
                LOG.info("Reverting to the system time zone");
                TimeZone.setDefault(null);
                isOverridden = false;
            }
            return;
        }

        if (!overrideId.equals(TimeZone.getDefault().getID())) {
            LOG.info("Applying time zone override {}", overrideId);
            TimeZone.setDefault(TimeZone.getTimeZone(overrideId));
        }
        isOverridden = true;
    }

    /**
     * Persists and applies a new time zone override, and syncs the time to the connected devices.
     *
     * @param timeZoneId the time zone id, or null to use the system time zone
     */
    public static void setOverride(final Context context, @Nullable final String timeZoneId) {
        GBApplication.getPrefs().getPreferences().edit()
                .putString(PREF_KEY, timeZoneId != null ? timeZoneId : "")
                .apply();
        apply();

        // The next DST transition depends on the time zone
        TimeChangeReceiver.ifEnabledScheduleNextDstChangeOrPeriodicSync(context);
        if (GBApplication.getPrefs().syncTime()) {
            GBApplication.deviceService().onSetTime();
        }
    }
}
