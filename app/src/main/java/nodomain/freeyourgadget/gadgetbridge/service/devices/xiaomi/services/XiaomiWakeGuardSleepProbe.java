/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;

/**
 * S4 never answers System/78 nor pushes System/79, so live sleep comes from the watch's own sleep
 * file instead. The file is only listed and read, never acked, so the watch keeps it for the
 * regular sync. Sleep counts as ongoing only while its wakeup time keeps advancing between reads.
 */
public final class XiaomiWakeGuardSleepProbe {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiWakeGuardSleepProbe.class);
    static final long MAX_SLEEP_START_AGE_SEC = 16 * 3600;
    static final long MAX_WAKEUP_LAG_SEC = 30 * 60;
    static final long MAX_STALL_SEC = 20 * 60;
    private static final long MAX_LOG_BYTES = 512 * 1024;
    private static final int MAX_RAW_FILES = 60;

    public static final class Header {
        public final int isAwakeFlag;
        public final long bedTime;
        public final long wakeupTime;

        Header(int isAwakeFlag, long bedTime, long wakeupTime) {
            this.isAwakeFlag = isAwakeFlag;
            this.bedTime = bedTime;
            this.wakeupTime = wakeupTime;
        }
    }

    private final java.util.function.Supplier<Context> context;
    private long lastBedTime = -1, lastWakeupTime = -1, lastAdvanceSec = -1;

    /** The context is resolved lazily: XiaomiSupport has none yet when services are constructed. */
    public XiaomiWakeGuardSleepProbe(@Nullable java.util.function.Supplier<Context> context) {
        this.context = context;
    }

    @Nullable
    private File dir() {
        Context c = context == null ? null : context.get();
        return c == null ? null : new File(c.getFilesDir(), "wake-guard-probe");
    }

    public void reset() {
        lastBedTime = lastWakeupTime = lastAdvanceSec = -1;
    }

    /** Newest sleep file that may still describe last night, or null. */
    @Nullable
    static XiaomiActivityFileId pickSleepFile(List<XiaomiActivityFileId> ids, long nowSec) {
        XiaomiActivityFileId best = null;
        for (XiaomiActivityFileId id : ids) {
            if (id.getSubtype() != XiaomiActivityFileId.Subtype.ACTIVITY_SLEEP) continue;
            long ts = id.getTimestamp().getTime() / 1000L;
            if (nowSec - ts > MAX_SLEEP_START_AGE_SEC || ts - nowSec > 3600) continue;
            if (best == null || ts > best.getTimestamp().getTime() / 1000L) best = id;
        }
        return best;
    }

    /** Same header layout as SleepDetailsParser. */
    @Nullable
    static Header parseHeader(XiaomiActivityFileId fileId, byte[] bytes) {
        int headerSize;
        switch (fileId.getVersion()) {
            case 1: case 2: case 3: case 4: headerSize = 1; break;
            case 5: headerSize = 2; break;
            default: return null;
        }
        if (bytes == null || bytes.length < 8 + headerSize + 9) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8 + headerSize);
        int isAwake = buf.get() & 0xff;
        long bed = buf.getInt() & 0xffffffffL;
        long wake = buf.getInt() & 0xffffffffL;
        return new Header(isAwake, bed, wake);
    }

    /**
     * TRUE: same night and the record's wakeup time moved forward within the last
     * {@link #MAX_STALL_SEC} while staying close to now. FALSE: no sleep file, or the record stopped
     * advancing. NULL: first read of this night, no evidence yet.
     */
    @Nullable
    Boolean judge(@Nullable Header header, long nowSec) {
        if (header == null) { reset(); return false; }
        boolean sameNight = header.bedTime == lastBedTime;
        if (!sameNight) {
            lastBedTime = header.bedTime;
            lastWakeupTime = header.wakeupTime;
            lastAdvanceSec = -1;
            return nowSec - header.wakeupTime <= MAX_WAKEUP_LAG_SEC ? null : false;
        }
        if (header.wakeupTime > lastWakeupTime) lastAdvanceSec = nowSec;
        lastWakeupTime = header.wakeupTime;
        return lastAdvanceSec >= 0 && nowSec - lastAdvanceSec <= MAX_STALL_SEC
                && nowSec - header.wakeupTime <= MAX_WAKEUP_LAG_SEC;
    }

    void log(String message) {
        LOG.info("Wake guard probe: {}", message);
        File dir = dir();
        if (dir == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File file = new File(dir, "probe.log");
            if (file.length() > MAX_LOG_BYTES) {
                File old = new File(dir, "probe.log.1");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date()) + " " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOG.warn("Failed to write wake guard probe log", e);
        }
    }

    void saveRaw(XiaomiActivityFileId fileId, byte[] bytes) {
        File dir = dir();
        if (dir == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name = new SimpleDateFormat("MMdd-HHmmss", Locale.ROOT).format(new Date())
                    + "_sleep_v" + fileId.getVersion() + ".bin";
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(bytes);
            }
            File[] raws = dir.listFiles((d, n) -> n.endsWith(".bin"));
            if (raws != null && raws.length > MAX_RAW_FILES) {
                Arrays.sort(raws);
                for (int i = 0; i < raws.length - MAX_RAW_FILES; i++) //noinspection ResultOfMethodCallIgnored
                    raws[i].delete();
            }
        } catch (IOException e) {
            LOG.warn("Failed to save wake guard probe bytes", e);
        }
    }
}
