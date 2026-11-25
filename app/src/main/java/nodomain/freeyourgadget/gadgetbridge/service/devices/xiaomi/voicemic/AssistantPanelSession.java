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

import android.os.SystemClock;

/** Tracks the phone panel separately from the audio and watch display of the same request. */
public final class AssistantPanelSession {
    private static final long START_WINDOW_MS = 12000;
    private static final long CANCELLED_START_WINDOW_MS = 5000;
    private static final AssistantPanelSession INSTANCE = new AssistantPanelSession();

    private long token;
    private long attachBefore;
    private boolean attached;
    private boolean cancelled;

    public static AssistantPanelSession getInstance() {
        return INSTANCE;
    }

    public synchronized long begin() {
        token = Math.max(token + 1, SystemClock.elapsedRealtimeNanos());
        attachBefore = SystemClock.elapsedRealtime() + START_WINDOW_MS;
        attached = false;
        cancelled = false;
        return token;
    }

    public synchronized long attach() {
        if (token == 0 || attached || SystemClock.elapsedRealtime() > attachBefore) {
            return 0;
        }
        attached = true;
        return token;
    }

    public synchronized boolean cancel(final long requested) {
        if (requested == 0 || requested != token || cancelled
                || (!attached && SystemClock.elapsedRealtime() > attachBefore)) {
            return false;
        }
        cancelled = true;
        attachBefore = Math.min(attachBefore, SystemClock.elapsedRealtime() + CANCELLED_START_WINDOW_MS);
        return true;
    }

    public synchronized boolean isCancelled(final long requested) {
        return requested != 0 && requested == token && cancelled;
    }

    public synchronized void detach(final long requested) {
        if (requested != 0 && requested == token) {
            // Only the first panel opened for a request may claim it.
            attached = false;
            attachBefore = -1;
        }
    }
}
