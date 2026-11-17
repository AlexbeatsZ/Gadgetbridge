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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic;

/**
 * Shared between Gadgetbridge and the hook running inside the assistant's process, so it must not pull in
 * anything else from Gadgetbridge.
 */
public final class VoiceMicContract {
    public static final String ASSISTANT_PACKAGE = "com.google.android.googlequicksearchbox";
    public static final String AUTHORITY_SUFFIX = ".voicemic";
    public static final String PATH_STREAM = "stream";
    /** {@code ContentResolver.call} method: the assistant stopped a recording that was fed. */
    public static final String METHOD_RECORDING_STOPPED = "recording_stopped";
    /** {@code ContentResolver.call} method: the assistant reports which read overload it uses. */
    public static final String METHOD_REPORT = "report";
    /** {@code ContentResolver.call} method: whether a band voice session is handed over; result key {@link #KEY_ACTIVE}. */
    public static final String METHOD_IS_ACTIVE = "is_active";
    public static final String KEY_ACTIVE = "active";
    /** {@code ContentResolver.call} method: the assistant shows an answer text, passed as arg. */
    public static final String METHOD_ANSWER = "answer";
    /** View id (entry name) of the assistant's answer text in the Google app's floating panel. */
    public static final String ANSWER_VIEW_ID = "assistant_robin_text";
    /** Prefix of the floating panel's view ids whose texts are reported. */
    public static final String PANEL_VIEW_PREFIX = "assistant_robin";
    /** {@code ContentResolver.call} method: another panel text changed; arg is view id, then {@link #SEPARATOR}, then text. */
    public static final String METHOD_PANEL_TEXT = "panel_text";
    public static final char SEPARATOR = '\u0001';

    public static final String ACTION_CANCEL = "nodomain.freeyourgadget.gadgetbridge.voicemic.CANCEL";
    public static final String CANCEL_PERMISSION_SUFFIX = ".permission.CANCEL_PHONE_ASSISTANT";
    public static final String EXTRA_CANCEL_TIME = "cancel_elapsed_realtime";

    /** Stream format: 16 kHz, mono, signed 16 bit little endian, as decoded from the band. */
    public static final int SAMPLE_RATE = 16000;

    private VoiceMicContract() {
    }
}
