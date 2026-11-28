/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;

import static org.junit.Assert.*;

public class XiaomiWakeGuardSleepProbeTest {
    private static final long BED = 1_764_262_680L; // 2026-11-28 00:58 +08:00

    private static XiaomiActivityFileId id(long ts, int subtype, int version) {
        return new XiaomiActivityFileId(new Date(ts * 1000L), 32, 0, subtype, 1, version);
    }

    private static byte[] file(XiaomiActivityFileId id, int awake, long bed, long wake) {
        int header = id.getVersion() >= 5 ? 2 : 1;
        ByteBuffer b = ByteBuffer.allocate(8 + header + 9 + 16).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.toBytes()).put((byte) 0).put(new byte[header]).put((byte) awake).putInt((int) bed).putInt((int) wake);
        return b.array();
    }

    private static XiaomiWakeGuardSleepProbe.Header h(long wake) {
        XiaomiActivityFileId sleep = id(BED, 8, 4);
        return XiaomiWakeGuardSleepProbe.parseHeader(sleep, file(sleep, 0, BED, wake));
    }

    @Test public void picksNewestRecentSleepFileOnly() {
        long now = BED + 6 * 3600;
        XiaomiActivityFileId old = id(BED - 20 * 3600, 8, 4), daily = id(BED, 0, 4), last = id(BED, 8, 4);
        assertSame(last, XiaomiWakeGuardSleepProbe.pickSleepFile(Arrays.asList(old, daily, last), now));
        assertNull(XiaomiWakeGuardSleepProbe.pickSleepFile(Arrays.asList(old, daily), now));
    }

    @Test public void parsesHeaderForV4AndV5() {
        for (int v : new int[]{4, 5}) {
            XiaomiActivityFileId sleep = id(BED, 8, v);
            XiaomiWakeGuardSleepProbe.Header header = XiaomiWakeGuardSleepProbe.parseHeader(sleep, file(sleep, 1, BED, BED + 100));
            assertNotNull(header);
            assertEquals(1, header.isAwakeFlag);
            assertEquals(BED, header.bedTime);
            assertEquals(BED + 100, header.wakeupTime);
        }
        assertNull(XiaomiWakeGuardSleepProbe.parseHeader(id(BED, 8, 9), new byte[40]));
        assertNull(XiaomiWakeGuardSleepProbe.parseHeader(id(BED, 8, 4), new byte[10]));
    }

    @Test public void advancingRecordMeansAsleepAndStallMeansAwake() {
        XiaomiWakeGuardSleepProbe p = new XiaomiWakeGuardSleepProbe(null);
        long t = BED + 6 * 3600;
        assertNull(p.judge(h(t - 60), t));                     // first read: no evidence
        assertEquals(Boolean.TRUE, p.judge(h(t + 240), t + 300)); // advanced
        assertEquals(Boolean.TRUE, p.judge(h(t + 240), t + 600)); // not yet stalled
        assertEquals(Boolean.FALSE, p.judge(h(t + 240), t + 300 + 21 * 60)); // stalled
    }

    @Test public void finishedOldRecordAndMissingFileNeverCountAsSleep() {
        XiaomiWakeGuardSleepProbe p = new XiaomiWakeGuardSleepProbe(null);
        long t = BED + 9 * 3600;
        assertEquals(Boolean.FALSE, p.judge(h(t - 3600), t));
        assertEquals(Boolean.FALSE, p.judge(h(t - 3600), t + 300));
        assertEquals(Boolean.FALSE, p.judge(null, t + 600));
        assertNull(p.judge(h(t + 500), t + 600)); // record restarted: needs fresh evidence again
    }
}
