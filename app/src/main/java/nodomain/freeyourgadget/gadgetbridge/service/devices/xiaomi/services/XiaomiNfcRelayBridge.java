/*  Copyright (C) 2026 Meta

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import nodomain.freeyourgadget.gadgetbridge.util.GB;

/**
 * In-process Bluetooth Classic SPP <-> K70 eSE1 APDU relay bridge for Watch S4.
 * Supports both:
 *   1. Always-armed native C direct-SPP daemon on the watch (S4Q1:/S4R1:/S4CTL:),
 *      requiring no foreground QuickApp and auto-restoring the user's daily watchface.
 *   2. Legacy JSON QuickApp protocol ("relay":"s4") for manual diagnostics/compatibility.
 */
public class XiaomiNfcRelayBridge {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiNfcRelayBridge.class);

    public static final String RELAY_APP_ID = "com.meta.s4nativeprobe";
    public static final String RELAY_WATCHFACE_ID = "362991121";

    static final String DIRECT_REQ_PREFIX = "S4Q1:";
    static final String DIRECT_RSP_PREFIX = "S4R1:";
    static final String DIRECT_CTL_PREFIX = "S4CTL:";

    static final String ADAPTER_PACKAGE = "local.meta.esenfc";
    static final String RELAY_SERVICE = "local.meta.esenfc.ApduRelayService";
    static final int PROTOCOL_VERSION = 1;
    static final int RELAY_OPEN = 10;
    static final int RELAY_OPEN_RESULT = 11;
    static final int RELAY_TRANSCEIVE = 12;
    static final int RELAY_RESPONSE = 13;
    static final int RELAY_CLOSE = 14;
    static final String KEY_PROTOCOL = "protocol";
    static final String KEY_ERROR = "error";
    static final String KEY_APDU = "relay_apdu";
    static final String KEY_RAPDU = "relay_rapdu";
    static final String KEY_SEQ = "relay_seq";
    static final String KEY_EPOCH = "relay_epoch";
    static final String KEY_OK = "relay_ok";

    interface RelayTransport {
        void sendMessage(String appId, byte[] payload);
        void sendDirectMessage(String appId, byte[] payload);
        void relaunchApp(String appId);
        UUID getActiveWatchface();
        boolean hasWatchface(String watchfaceId);
        UUID getFallbackWatchface(String excludedWatchfaceId);
        boolean setWatchfaceAndWait(String watchfaceId, long timeoutMs);
        void setWatchface(UUID watchfaceUuid);
    }

    interface ApduBackend {
        boolean open();
        byte[] transceive(int seq, int epoch, byte[] capdu);
        void close();
    }

    static final class PongStatus {
        final int phase;
        final int uvInited;
        final int halHooked;
        final int txCount;

        PongStatus(final int phase, final int uvInited, final int halHooked, final int txCount) {
            this.phase = phase;
            this.uvInited = uvInited;
            this.halHooked = halHooked;
            this.txCount = txCount;
        }

        boolean isReady() {
            return phase == 4 && uvInited == 1 && halHooked == 1;
        }

        @Override
        public String toString() {
            return "phase=" + phase + " uv=" + uvInited + " hal=" + halHooked + " tx=" + txCount;
        }
    }

    static final class DiagStatus {
        final int routePower;
        final int activations;
        final int lastActPacked;
        final int capduCount;
        final int txCount;

        DiagStatus(final int routePower, final int activations, final int lastActPacked, final int capduCount, final int txCount) {
            this.routePower = routePower;
            this.activations = activations;
            this.lastActPacked = lastActPacked;
            this.capduCount = capduCount;
            this.txCount = txCount;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "routePower=0x%02X acts=%d lastAct=0x%06X capdus=%d tx=%d",
                    routePower & 0xFF, activations, lastActPacked & 0xFFFFFF, capduCount, txCount);
        }
    }

    static final class SelftestStatus {
        final boolean ok;
        final int rapduLen;
        final int sw;
        final int phase;
        final int uvInited;

        SelftestStatus(final boolean ok, final int rapduLen, final int sw, final int phase, final int uvInited) {
            this.ok = ok;
            this.rapduLen = rapduLen;
            this.sw = sw;
            this.phase = phase;
            this.uvInited = uvInited;
        }
    }

    private static final long DEFAULT_FACE_SWITCH_SETTLE_MS = 6500L;
    private static final long DEFAULT_DAEMON_CONNECT_DELAY_MS = 1500L;
    private static final long DEFAULT_DAEMON_ARM_SETTLE_MS = 3500L;
    private static final long DEFAULT_PING_TIMEOUT_MS = 2000L;
    static final String TRACE_FILE_NAME = "s4-nfc-relay.log";
    private static final long DEFAULT_MAX_TRACE_BYTES = 64L * 1024L;

    private final RelayTransport transport;
    private final ApduBackend backend;
    private final File traceFile;
    private final long maxTraceBytes;
    private final long faceSwitchSettleMs;
    private final long connectDelayMs;
    private final long daemonArmSettleMs;
    private final long pingTimeoutMs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService armExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean armInFlight = new AtomicBoolean(false);
    private final AtomicLong incomingSeq = new AtomicLong(0L);
    private final Object ctlLock = new Object();
    private final Object traceLock = new Object();

    private CountDownLatch pendingPongLatch = null;
    private volatile PongStatus lastPong = null;
    private CountDownLatch pendingDiagLatch = null;
    private volatile DiagStatus lastDiag = null;
    private CountDownLatch pendingSelftestLatch = null;
    private volatile SelftestStatus lastSelftest = null;
    private volatile boolean daemonArmed = false;
    private volatile boolean eseOpen = false;
    private long lastDirectApduNs = 0L;

    private long ignoreRelayOpenUpToSeq = 0L;
    private long lastFaceTriggerNs = 0L;
    private UUID previousWatchface = null;
    private boolean switchedWatchface = false;

    public XiaomiNfcRelayBridge(final Context context, final RelayTransport transport) {
        this(transport, new BoundEseBackend(context), resolveTraceFile(context), DEFAULT_MAX_TRACE_BYTES,
                DEFAULT_FACE_SWITCH_SETTLE_MS, DEFAULT_DAEMON_CONNECT_DELAY_MS,
                DEFAULT_DAEMON_ARM_SETTLE_MS, DEFAULT_PING_TIMEOUT_MS);
    }

    XiaomiNfcRelayBridge(final RelayTransport transport, final ApduBackend backend) {
        this(transport, backend, null, DEFAULT_MAX_TRACE_BYTES, DEFAULT_FACE_SWITCH_SETTLE_MS, 0L, 0L, 200L);
    }

    XiaomiNfcRelayBridge(final RelayTransport transport, final ApduBackend backend, final long faceSwitchSettleMs) {
        this(transport, backend, null, DEFAULT_MAX_TRACE_BYTES, faceSwitchSettleMs, 0L, 0L, 200L);
    }

    XiaomiNfcRelayBridge(final RelayTransport transport, final ApduBackend backend,
                         final long faceSwitchSettleMs, final long connectDelayMs,
                         final long daemonArmSettleMs, final long pingTimeoutMs) {
        this(transport, backend, null, DEFAULT_MAX_TRACE_BYTES,
                faceSwitchSettleMs, connectDelayMs, daemonArmSettleMs, pingTimeoutMs);
    }

    XiaomiNfcRelayBridge(final RelayTransport transport, final ApduBackend backend,
                         final File traceFile, final long maxTraceBytes,
                         final long faceSwitchSettleMs, final long connectDelayMs,
                         final long daemonArmSettleMs, final long pingTimeoutMs) {
        this.transport = transport;
        this.backend = backend;
        this.traceFile = traceFile;
        this.maxTraceBytes = Math.max(512L, maxTraceBytes);
        this.faceSwitchSettleMs = Math.max(0L, faceSwitchSettleMs);
        this.connectDelayMs = Math.max(0L, connectDelayMs);
        this.daemonArmSettleMs = Math.max(0L, daemonArmSettleMs);
        this.pingTimeoutMs = Math.max(50L, pingTimeoutMs);
    }

    private static File resolveTraceFile(final Context context) {
        if (context == null) {
            return null;
        }
        final File dir = context.getExternalFilesDir(null);
        return dir != null ? new File(dir, TRACE_FILE_NAME) : new File(context.getFilesDir(), TRACE_FILE_NAME);
    }

    public static boolean isDirectDaemonMessage(final String appId, final String payload) {
        return RELAY_APP_ID.equals(appId) && payload != null
                && (payload.startsWith(DIRECT_REQ_PREFIX) || payload.startsWith(DIRECT_CTL_PREFIX));
    }

    public static boolean isRelayMessage(final String appId, final String payload) {
        return RELAY_APP_ID.equals(appId) && payload != null
                && (payload.startsWith(DIRECT_REQ_PREFIX)
                || payload.startsWith(DIRECT_CTL_PREFIX)
                || payload.contains("\"relay\":\"s4\""));
    }

    public boolean isDaemonArmed() {
        return daemonArmed;
    }

    public void onWatchConnected() {
        if (disposed.get() || !armInFlight.compareAndSet(false, true)) {
            return;
        }
        armExecutor.execute(() -> {
            try {
                ensureDaemonArmed();
            } catch (final Exception e) {
                LOG.warn("S4 NFC daemon auto-arm failed", e);
            } finally {
                armInFlight.set(false);
            }
        });
    }

    public void handleWatchMessage(final String appId, final String payload) {
        if (disposed.get() || !isRelayMessage(appId, payload)) {
            return;
        }
        if (payload.startsWith(DIRECT_CTL_PREFIX)) {
            handleControlMessageInline(payload);
            return;
        }
        if (payload.startsWith(DIRECT_REQ_PREFIX)) {
            executor.execute(() -> handleDirectApdu(appId, payload));
            return;
        }
        final long msgSeq = incomingSeq.incrementAndGet();
        executor.execute(() -> processJsonMessage(appId, payload, msgSeq));
    }

    private void handleControlMessageInline(final String payload) {
        final String[] parts = payload.split(":");
        if (payload.startsWith("S4CTL:PONG:") && parts.length >= 6) {
            try {
                final PongStatus status = new PongStatus(
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]));
                final CountDownLatch latch;
                synchronized (ctlLock) {
                    lastPong = status;
                    latch = pendingPongLatch;
                }
                if (status.isReady()) {
                    daemonArmed = true;
                }
                LOG.info("S4 NFC daemon PONG {}", status);
                appendTrace("PONG", status.toString());
                if (latch != null) {
                    latch.countDown();
                }
            } catch (final NumberFormatException e) {
                LOG.warn("Malformed S4CTL:PONG payload: {}", payload);
            }
        } else if (payload.startsWith("S4CTL:DIAG:") && parts.length >= 6) {
            try {
                final int counts = Integer.parseInt(parts[5]);
                final DiagStatus diag = new DiagStatus(
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        (counts >>> 16) & 0xFFFF,
                        counts & 0xFFFF);
                final CountDownLatch latch;
                synchronized (ctlLock) {
                    lastDiag = diag;
                    latch = pendingDiagLatch;
                }
                LOG.info("S4 NFC daemon DIAG {}", diag);
                appendTrace("DIAG", diag.toString());
                if (latch != null) {
                    latch.countDown();
                }
            } catch (final NumberFormatException e) {
                LOG.warn("Malformed S4CTL:DIAG payload: {}", payload);
            }
        } else if (payload.startsWith("S4CTL:SELFTEST_OK:") && parts.length >= 6) {
            try {
                final SelftestStatus st = new SelftestStatus(
                        true,
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]));
                final CountDownLatch latch;
                synchronized (ctlLock) {
                    lastSelftest = st;
                    latch = pendingSelftestLatch;
                }
                final String swHex = String.format(Locale.ROOT, "%04X", st.sw & 0xFFFF);
                LOG.info("S4 NFC daemon SELFTEST_OK len={} sw={} phase={} uv={}",
                        st.rapduLen, swHex, st.phase, st.uvInited);
                appendTrace("SELFTEST_OK", "len=" + st.rapduLen + " sw=" + swHex
                        + " phase=" + st.phase + " uv=" + st.uvInited);
                if (latch != null) {
                    latch.countDown();
                }
            } catch (final NumberFormatException e) {
                LOG.warn("Malformed S4CTL:SELFTEST_OK payload: {}", payload);
            }
        } else if (payload.startsWith("S4CTL:SELFTEST_ERR:")) {
            final SelftestStatus st = new SelftestStatus(false, 0, 0, 0, 0);
            final CountDownLatch latch;
            synchronized (ctlLock) {
                lastSelftest = st;
                latch = pendingSelftestLatch;
            }
            LOG.warn("S4 NFC daemon {}", payload);
            appendTrace("SELFTEST_ERR", payload);
            if (latch != null) {
                latch.countDown();
            }
        } else if (payload.equals("S4CTL:RF") || payload.startsWith("S4CTL:RF:")) {
            LOG.info("S4 NFC daemon RF wake {}", payload);
            appendTrace("RF_WAKE", payload);
            if (!eseOpen && !disposed.get()) {
                executor.execute(() -> {
                    if (!disposed.get() && !eseOpen) {
                        eseOpen = backend.open();
                    }
                });
            }
        } else if (payload.startsWith("S4CTL:STOPPED:")) {
            daemonArmed = false;
            LOG.info("S4 NFC daemon {}", payload);
            appendTrace("STOPPED", payload);
        } else {
            LOG.debug("S4 NFC daemon control: {}", payload);
        }
    }

    private PongStatus pingDaemon(final long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (ctlLock) {
            lastPong = null;
            pendingPongLatch = latch;
        }
        try {
            transport.sendDirectMessage(RELAY_APP_ID, "S4CTL:PING".getBytes(StandardCharsets.US_ASCII));
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return lastPong;
            }
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            synchronized (ctlLock) {
                if (pendingPongLatch == latch) {
                    pendingPongLatch = null;
                }
            }
        }
    }

    private DiagStatus queryDaemonDiag(final long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (ctlLock) {
            lastDiag = null;
            pendingDiagLatch = latch;
        }
        try {
            transport.sendDirectMessage(RELAY_APP_ID, "S4CTL:DIAG".getBytes(StandardCharsets.US_ASCII));
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return lastDiag;
            }
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            synchronized (ctlLock) {
                if (pendingDiagLatch == latch) {
                    pendingDiagLatch = null;
                }
            }
        }
    }

    private SelftestStatus runDaemonSelftest(final long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (ctlLock) {
            lastSelftest = null;
            pendingSelftestLatch = latch;
        }
        final long startNs = System.nanoTime();
        try {
            transport.sendDirectMessage(RELAY_APP_ID, "S4CTL:SELFTEST".getBytes(StandardCharsets.US_ASCII));
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                final SelftestStatus st = lastSelftest;
                if (st != null && st.ok) {
                    final long rttMs = (System.nanoTime() - startNs) / 1_000_000L;
                    LOG.info("S4 NFC daemon selftest roundtrip OK len={} sw={} rttMs={}",
                            st.rapduLen, String.format(Locale.ROOT, "%04X", st.sw & 0xFFFF), rttMs);
                    appendTrace("SELFTEST_RTT", "len=" + st.rapduLen
                            + " sw=" + String.format(Locale.ROOT, "%04X", st.sw & 0xFFFF)
                            + " rttMs=" + rttMs);
                }
                return st;
            }
            LOG.warn("S4 NFC daemon selftest timed out after {}ms", timeoutMs);
            appendTrace("SELFTEST_TIMEOUT", "timeoutMs=" + timeoutMs);
            return null;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            synchronized (ctlLock) {
                if (pendingSelftestLatch == latch) {
                    pendingSelftestLatch = null;
                }
            }
        }
    }

    private void ensureDaemonArmed() {
        if (disposed.get()) {
            return;
        }
        sleepQuietly(connectDelayMs);
        if (disposed.get()) {
            return;
        }

        final PongStatus existing = pingDaemon(pingTimeoutMs);
        if (existing != null) {
            if (existing.isReady()) {
                final DiagStatus diag = queryDaemonDiag(Math.min(pingTimeoutMs, 1200L));
                if (diag != null) {
                    daemonArmed = true;
                    eseOpen = backend.open();
                    LOG.info("S4 NFC daemon already armed v3 ({}, {}), eseOpen={}", existing, diag, eseOpen);
                    appendTrace("ARMED_EXISTING", existing + " " + diag + " eseOpen=" + eseOpen);
                    if (eseOpen) {
                        runDaemonSelftest(Math.max(pingTimeoutMs * 2L, 3000L));
                    }
                    return;
                }
            }
            // Either an older v2 daemon (no S4CTL:DIAG) or an unhooked daemon (hal=0) answered PONG;
            // send S4CTL:STOP first so relay_worker/uv_async/pb_recv_hook detach cleanly before reloading.
            LOG.info("S4 NFC daemon needs reload ({}); requesting S4CTL:STOP", existing);
            appendTrace("ARM_RELOAD", existing.toString());
            transport.sendDirectMessage(RELAY_APP_ID, "S4CTL:STOP".getBytes(StandardCharsets.US_ASCII));
            sleepQuietly(Math.min(daemonArmSettleMs, 1800L));
        }

        final UUID active = transport.getActiveWatchface();
        if (!transport.hasWatchface(RELAY_WATCHFACE_ID)) {
            LOG.debug("S4 NFC relay watchface {} not installed; skipping daemon auto-arm", RELAY_WATCHFACE_ID);
            return;
        }

        final String activeId = active != null ? XiaomiWatchfaceService.toWatchfaceId(active) : null;
        final UUID restoreFace;
        if (RELAY_WATCHFACE_ID.equals(activeId)) {
            restoreFace = transport.getFallbackWatchface(RELAY_WATCHFACE_ID);
            if (restoreFace != null) {
                transport.setWatchfaceAndWait(XiaomiWatchfaceService.toWatchfaceId(restoreFace), 4000);
                sleepQuietly(Math.min(daemonArmSettleMs, 800L));
            }
        } else {
            restoreFace = active;
        }

        LOG.info("S4 NFC daemon arming via face {} (will restore {})",
                RELAY_WATCHFACE_ID, restoreFace != null ? XiaomiWatchfaceService.toWatchfaceId(restoreFace) : "none");
        transport.setWatchfaceAndWait(RELAY_WATCHFACE_ID, 14000);
        sleepQuietly(daemonArmSettleMs);

        if (restoreFace != null) {
            transport.setWatchfaceAndWait(XiaomiWatchfaceService.toWatchfaceId(restoreFace), 5000);
        }

        PongStatus armedPong = pingDaemon(Math.max(pingTimeoutMs, 3000L));
        if ((armedPong == null || !armedPong.isReady()) && !disposed.get()) {
            sleepQuietly(Math.min(daemonArmSettleMs, 1500L));
            armedPong = pingDaemon(Math.max(pingTimeoutMs, 2500L));
        }

        if (armedPong != null && armedPong.isReady()) {
            daemonArmed = true;
            final DiagStatus diag = queryDaemonDiag(Math.min(pingTimeoutMs, 1500L));
            eseOpen = backend.open();
            LOG.info("S4 NFC daemon armed and daily watchface restored ({}, {}), eseOpen={}",
                    armedPong, diag, eseOpen);
            appendTrace("ARMED_COLD", armedPong + " " + diag + " eseOpen=" + eseOpen);
            if (eseOpen) {
                runDaemonSelftest(Math.max(pingTimeoutMs * 2L, 4000L));
            }
        } else {
            LOG.warn("S4 NFC daemon did not confirm PONG after arming (last={})", armedPong);
            appendTrace("ARM_FAILED", "last=" + armedPong);
        }
    }

    private void handleDirectApdu(final String appId, final String payload) {
        if (disposed.get()) {
            return;
        }
        final String[] parts = payload.split(":", 4);
        if (parts.length != 4) {
            LOG.warn("Rejected malformed direct APDU frame: {}", payload);
            return;
        }
        final int seq;
        final int epoch;
        try {
            seq = Integer.parseInt(parts[1]);
            epoch = Integer.parseInt(parts[2]);
        } catch (final NumberFormatException e) {
            LOG.warn("Rejected non-numeric direct APDU header: {}", payload);
            return;
        }
        final String apduHex = parts[3];
        if (seq <= 0 || epoch <= 0 || epoch > 65535 || apduHex.length() < 8
                || (apduHex.length() & 1) != 0 || apduHex.length() > 522) {
            LOG.warn("Rejected invalid direct APDU seq={} epoch={} len={}", seq, epoch, apduHex.length());
            appendTrace("APDU_REJECT", "seq=" + seq + " epoch=" + epoch + " hexLen=" + apduHex.length());
            return;
        }
        final byte[] capdu;
        try {
            capdu = GB.hexStringToByteArray(apduHex);
        } catch (final Exception e) {
            LOG.warn("Rejected non-hex direct APDU seq={} epoch={}", seq, epoch);
            appendTrace("APDU_REJECT", "seq=" + seq + " epoch=" + epoch + " reason=non_hex");
            return;
        }

        final long startNs = System.nanoTime();
        final long prevNs = lastDirectApduNs;
        lastDirectApduNs = startNs;
        final long gapMs = (prevNs > 0L && (startNs - prevNs) < TimeUnit.SECONDS.toNanos(60))
                ? (startNs - prevNs) / 1_000_000L : -1L;

        if (!eseOpen) {
            eseOpen = backend.open();
        }
        final long eseStartNs = System.nanoTime();
        final byte[] rapdu = backend.transceive(seq, epoch, capdu);
        final long eseMs = (System.nanoTime() - eseStartNs) / 1_000_000L;
        final byte[] safeRapdu = (rapdu != null && rapdu.length >= 2) ? rapdu : new byte[]{(byte) 0x6F, 0x00};
        final String reply = DIRECT_RSP_PREFIX + seq + ":" + epoch + ":" + toLowerHex(safeRapdu);
        transport.sendDirectMessage(appId, reply.getBytes(StandardCharsets.US_ASCII));
        final long totalMs = (System.nanoTime() - startNs) / 1_000_000L;
        final String swHex = String.format(Locale.ROOT, "%02X%02X",
                safeRapdu[safeRapdu.length - 2] & 0xFF, safeRapdu[safeRapdu.length - 1] & 0xFF);
        final String summary = summarizeCapdu(capdu);
        LOG.info("S4 NFC direct APDU seq={} epoch={} {} inLen={} outLen={} sw={} eseMs={} totalMs={} gapMs={}",
                seq, epoch, summary, capdu.length, safeRapdu.length, swHex, eseMs, totalMs, gapMs);
        appendTrace("APDU", "seq=" + seq + " epoch=" + epoch + " " + summary
                + " inLen=" + capdu.length + " outLen=" + safeRapdu.length
                + " sw=" + swHex + " eseMs=" + eseMs + " totalMs=" + totalMs + " gapMs=" + gapMs);
    }

    private void processJsonMessage(final String appId, final String payload, final long msgSeq) {
        if (disposed.get()) {
            return;
        }
        final JSONObject msg;
        try {
            msg = new JSONObject(payload);
        } catch (final Exception ignored) {
            return;
        }
        if (!"s4".equals(msg.optString("relay"))) {
            return;
        }
        final String op = msg.optString("op");
        switch (op) {
            case "relay-open":
                handleRelayOpen(appId, msg.optBoolean("switch_face", true), msg.optString("watch_status", ""), msgSeq);
                break;
            case "apdu":
                handleJsonApdu(appId, msg);
                break;
            case "relay-close":
                handleRelayClose(appId);
                break;
            case "watch-status":
                LOG.info("S4 watch relay status={}", msg.optString("status", ""));
                break;
            default:
                LOG.debug("Ignoring unknown relay op {}", op);
                break;
        }
    }

    private boolean recentlyTriggeredFace() {
        return lastFaceTriggerNs > 0L && (System.nanoTime() - lastFaceTriggerNs) < TimeUnit.SECONDS.toNanos(25);
    }

    private static void sleepQuietly(final long ms) {
        if (ms <= 0L) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleRelayOpen(final String appId, final boolean switchFace, final String watchStatus, final long msgSeq) {
        if (msgSeq <= ignoreRelayOpenUpToSeq) {
            LOG.debug("Ignoring stale queued relay-open seq={} (cutoff={})", msgSeq, ignoreRelayOpenUpToSeq);
            return;
        }
        final long startNs = System.nanoTime();
        final boolean ok = backend.open();
        eseOpen = ok;
        boolean launchedFace = false;
        if (ok && switchFace && !daemonArmed) {
            final UUID active = transport.getActiveWatchface();
            if (transport.hasWatchface(RELAY_WATCHFACE_ID)) {
                final String activeId = active != null ? XiaomiWatchfaceService.toWatchfaceId(active) : null;
                final boolean watchAlreadyActive = "active".equals(watchStatus) || "bg-launched".equals(watchStatus);
                final boolean needFaceTrigger = !RELAY_WATCHFACE_ID.equals(activeId)
                        || (!watchAlreadyActive && !recentlyTriggeredFace());
                if (needFaceTrigger) {
                    try {
                        final byte[] yieldPayload = new JSONObject()
                                .put("relay", "s4")
                                .put("op", "yield-for-face")
                                .toString()
                                .getBytes(StandardCharsets.UTF_8);
                        transport.sendMessage(appId, yieldPayload);
                    } catch (final Exception e) {
                        LOG.warn("Failed sending yield-for-face", e);
                    }
                    sleepQuietly(Math.min(faceSwitchSettleMs, 600L));
                    if (RELAY_WATCHFACE_ID.equals(activeId)) {
                        final UUID fallback = previousWatchface != null
                                ? previousWatchface
                                : transport.getFallbackWatchface(RELAY_WATCHFACE_ID);
                        if (fallback != null) {
                            previousWatchface = fallback;
                            transport.setWatchfaceAndWait(XiaomiWatchfaceService.toWatchfaceId(fallback), 3000);
                            sleepQuietly(Math.min(faceSwitchSettleMs, 800L));
                        }
                    } else {
                        previousWatchface = active;
                    }
                    switchedWatchface = true;
                    launchedFace = true;
                    lastFaceTriggerNs = System.nanoTime();
                    transport.setWatchfaceAndWait(RELAY_WATCHFACE_ID, 5000);
                    sleepQuietly(faceSwitchSettleMs);
                }
            }
        }
        if (ok) {
            ignoreRelayOpenUpToSeq = Math.max(ignoreRelayOpenUpToSeq, incomingSeq.get());
        }
        try {
            final JSONObject ack = new JSONObject()
                    .put("relay", "s4")
                    .put("op", "relay-open-ack")
                    .put("ok", ok)
                    .put("daemon", daemonArmed);
            if (!ok) {
                ack.put("error", "ese_unavailable");
            }
            final byte[] payload = ack.toString().getBytes(StandardCharsets.UTF_8);
            if (launchedFace) {
                transport.relaunchApp(appId);
            } else {
                transport.sendMessage(appId, payload);
            }
            LOG.info("S4 NFC relay-open ok={} daemon={} switchedFace={} ms={}", ok, daemonArmed, launchedFace,
                    (System.nanoTime() - startNs) / 1_000_000L);
        } catch (final Exception e) {
            LOG.warn("Failed sending relay-open-ack", e);
        }
    }

    private void handleJsonApdu(final String appId, final JSONObject msg) {
        final int seq = msg.optInt("seq", 0);
        final int epoch = msg.optInt("epoch", 0);
        final String apduHex = msg.optString("apdu", "");
        if (seq <= 0 || epoch <= 0 || epoch > 65535 || apduHex.isEmpty() || (apduHex.length() & 1) != 0 || apduHex.length() > 522) {
            LOG.warn("Rejected invalid relay APDU request seq={} epoch={} len={}", seq, epoch, apduHex.length());
            return;
        }
        final byte[] capdu;
        try {
            capdu = GB.hexStringToByteArray(apduHex);
        } catch (final Exception e) {
            LOG.warn("Rejected non-hex relay APDU seq={} epoch={}", seq, epoch);
            return;
        }

        final long startNs = System.nanoTime();
        final byte[] rapdu = backend.transceive(seq, epoch, capdu);
        final byte[] safeRapdu = (rapdu != null && rapdu.length >= 2) ? rapdu : new byte[]{(byte) 0x6F, 0x00};
        final String rapduHex = toLowerHex(safeRapdu);
        try {
            final JSONObject reply = new JSONObject()
                    .put("relay", "s4")
                    .put("op", "response")
                    .put("seq", seq)
                    .put("epoch", epoch)
                    .put("rapdu", rapduHex);
            transport.sendMessage(appId, reply.toString().getBytes(StandardCharsets.UTF_8));
            LOG.info("S4 NFC relay APDU seq={} epoch={} inLen={} outLen={} sw={} ms={}",
                    seq, epoch, capdu.length, safeRapdu.length,
                    String.format(Locale.ROOT, "%02X%02X", safeRapdu[safeRapdu.length - 2] & 0xFF,
                            safeRapdu[safeRapdu.length - 1] & 0xFF),
                    (System.nanoTime() - startNs) / 1_000_000L);
        } catch (final Exception e) {
            LOG.warn("Failed sending relay APDU response", e);
        }
    }

    private void handleRelayClose(final String appId) {
        ignoreRelayOpenUpToSeq = Math.max(ignoreRelayOpenUpToSeq, incomingSeq.get());
        lastFaceTriggerNs = 0L;
        if (!daemonArmed) {
            eseOpen = false;
            backend.close();
            restoreWatchfaceIfNeeded();
        }
        try {
            final JSONObject ack = new JSONObject()
                    .put("relay", "s4")
                    .put("op", "relay-close-ack");
            transport.sendMessage(appId, ack.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final Exception ignored) { }
    }

    public void onDisconnect() {
        final long cutoff = incomingSeq.get();
        daemonArmed = false;
        eseOpen = false;
        appendTrace("DISCONNECT", "cutoff=" + cutoff);
        executor.execute(() -> {
            ignoreRelayOpenUpToSeq = Math.max(ignoreRelayOpenUpToSeq, cutoff);
            lastFaceTriggerNs = 0L;
            backend.close();
            switchedWatchface = false;
            previousWatchface = null;
        });
    }

    public void dispose() {
        if (disposed.compareAndSet(false, true)) {
            daemonArmed = false;
            eseOpen = false;
            backend.close();
            executor.shutdownNow();
            armExecutor.shutdownNow();
        }
    }

    private void restoreWatchfaceIfNeeded() {
        if (switchedWatchface) {
            final UUID prev = previousWatchface;
            switchedWatchface = false;
            previousWatchface = null;
            if (prev != null) {
                transport.setWatchface(prev);
            }
        }
    }

    /**
     * Metadata-only summary of a C-APDU (CLA/INS/P1/P2 plus public AID for 00 A4 04 SELECT).
     * Never emits command payload bytes for non-SELECT APDUs or any R-APDU card data.
     */
    static String summarizeCapdu(final byte[] capdu) {
        if (capdu == null || capdu.length < 4) {
            return "hdr=INVALID";
        }
        final StringBuilder sb = new StringBuilder(48);
        sb.append(String.format(Locale.ROOT, "hdr=%02X%02X%02X%02X",
                capdu[0] & 0xFF, capdu[1] & 0xFF, capdu[2] & 0xFF, capdu[3] & 0xFF));
        if ((capdu[0] & 0xFC) == 0 && capdu[1] == (byte) 0xA4 && capdu[2] == 0x04 && capdu.length >= 5) {
            final int lc = capdu[4] & 0xFF;
            if (lc >= 5 && lc <= 16 && capdu.length >= 5 + lc) {
                sb.append(" aid=");
                for (int i = 0; i < lc; i++) {
                    sb.append(String.format(Locale.ROOT, "%02X", capdu[5 + i] & 0xFF));
                }
            }
        }
        return sb.toString();
    }

    private void appendTrace(final String event, final String detail) {
        if (traceFile == null) {
            return;
        }
        synchronized (traceLock) {
            try {
                final File parent = traceFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                if (traceFile.exists() && traceFile.length() >= maxTraceBytes) {
                    final File backup = new File(traceFile.getParentFile(), traceFile.getName() + ".1");
                    if (backup.exists()) {
                        backup.delete();
                    }
                    if (!traceFile.renameTo(backup)) {
                        traceFile.delete();
                    }
                }
                final String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
                final String line = ts + " " + event + " " + (detail != null ? detail : "") + "\n";
                try (FileOutputStream fos = new FileOutputStream(traceFile, true)) {
                    fos.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (final IOException ignored) {
                // Tracing must never interfere with low-latency APDU relay.
            }
        }
    }

    private static String toLowerHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static final class BoundEseBackend implements ApduBackend {
        private final Context context;
        private HandlerThread replyThread;
        private Messenger replyMessenger;
        private Messenger serviceMessenger;
        private ServiceConnection connection;
        private CountDownLatch pendingOpen;
        private volatile boolean openResult;
        private CountDownLatch pendingApdu;
        private volatile int expectedSeq;
        private volatile int expectedEpoch;
        private volatile byte[] lastRapdu;

        BoundEseBackend(final Context context) {
            this.context = context.getApplicationContext();
        }

        private synchronized void ensureThread() {
            if (replyThread != null) {
                return;
            }
            replyThread = new HandlerThread("XiaomiNfcRelayReply");
            replyThread.start();
            replyMessenger = new Messenger(new Handler(replyThread.getLooper(), msg -> {
                final Bundle data = msg.getData();
                if (msg.what == RELAY_OPEN_RESULT) {
                    openResult = data.getBoolean(KEY_OK, false);
                    final CountDownLatch latch = pendingOpen;
                    if (latch != null) {
                        latch.countDown();
                    }
                } else if (msg.what == RELAY_RESPONSE) {
                    if (data.getInt(KEY_SEQ, -1) == expectedSeq && data.getInt(KEY_EPOCH, -1) == expectedEpoch) {
                        lastRapdu = data.getByteArray(KEY_RAPDU);
                        final CountDownLatch latch = pendingApdu;
                        if (latch != null) {
                            latch.countDown();
                        }
                    }
                }
                return true;
            }));
        }

        private boolean ensureBound() {
            if (serviceMessenger != null) {
                return true;
            }
            ensureThread();
            final CountDownLatch connected = new CountDownLatch(1);
            final ServiceConnection conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(final ComponentName name, final IBinder binder) {
                    serviceMessenger = new Messenger(binder);
                    connected.countDown();
                }

                @Override
                public void onServiceDisconnected(final ComponentName name) {
                    serviceMessenger = null;
                }

                @Override
                public void onBindingDied(final ComponentName name) {
                    unbindQuietly();
                }
            };
            final Intent intent = new Intent().setComponent(new ComponentName(ADAPTER_PACKAGE, RELAY_SERVICE));
            final boolean bound = context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (!bound) {
                LOG.warn("Failed to bind {}", RELAY_SERVICE);
                return false;
            }
            connection = conn;
            try {
                if (!connected.await(3, TimeUnit.SECONDS) || serviceMessenger == null) {
                    unbindQuietly();
                    return false;
                }
                return true;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                unbindQuietly();
                return false;
            }
        }

        @Override
        public boolean open() {
            if (!ensureBound()) {
                return false;
            }
            openResult = false;
            final CountDownLatch latch = new CountDownLatch(1);
            pendingOpen = latch;
            final Message msg = Message.obtain(null, RELAY_OPEN);
            msg.arg1 = PROTOCOL_VERSION;
            msg.replyTo = replyMessenger;
            try {
                serviceMessenger.send(msg);
                if (!latch.await(6, TimeUnit.SECONDS)) {
                    return false;
                }
                return openResult;
            } catch (final RemoteException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                unbindQuietly();
                return false;
            } finally {
                pendingOpen = null;
            }
        }

        @Override
        public byte[] transceive(final int seq, final int epoch, final byte[] capdu) {
            if (!ensureBound()) {
                return new byte[]{(byte) 0x6F, 0x00};
            }
            expectedSeq = seq;
            expectedEpoch = epoch;
            lastRapdu = null;
            final CountDownLatch latch = new CountDownLatch(1);
            pendingApdu = latch;
            final Bundle bundle = new Bundle();
            bundle.putByteArray(KEY_APDU, capdu);
            bundle.putInt(KEY_SEQ, seq);
            bundle.putInt(KEY_EPOCH, epoch);
            final Message msg = Message.obtain(null, RELAY_TRANSCEIVE);
            msg.arg1 = PROTOCOL_VERSION;
            msg.setData(bundle);
            msg.replyTo = replyMessenger;
            try {
                serviceMessenger.send(msg);
                if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
                    return new byte[]{(byte) 0x6F, 0x00};
                }
                return lastRapdu != null ? lastRapdu : new byte[]{(byte) 0x6F, 0x00};
            } catch (final RemoteException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                unbindQuietly();
                return new byte[]{(byte) 0x6F, 0x00};
            } finally {
                pendingApdu = null;
            }
        }

        @Override
        public void close() {
            final Messenger m = serviceMessenger;
            if (m != null) {
                try {
                    final Message msg = Message.obtain(null, RELAY_CLOSE);
                    msg.arg1 = PROTOCOL_VERSION;
                    m.send(msg);
                } catch (final RemoteException ignored) { }
            }
            unbindQuietly();
        }

        private void unbindQuietly() {
            final ServiceConnection conn = connection;
            connection = null;
            serviceMessenger = null;
            if (conn != null) {
                try {
                    context.unbindService(conn);
                } catch (final Exception ignored) { }
            }
        }
    }
}
