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

import java.nio.charset.StandardCharsets;
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
 * Replaces the research logcat/attach-agent loop by talking directly to
 * local.meta.esenfc/.ApduRelayService over a bound Messenger.
 */
public class XiaomiNfcRelayBridge {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiNfcRelayBridge.class);

    public static final String RELAY_APP_ID = "com.meta.s4nativeprobe";
    public static final String RELAY_WATCHFACE_ID = "362991121";

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

    private static final long DEFAULT_FACE_SWITCH_SETTLE_MS = 6500L;

    private final RelayTransport transport;
    private final ApduBackend backend;
    private final long faceSwitchSettleMs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicLong incomingSeq = new AtomicLong(0L);
    private long ignoreRelayOpenUpToSeq = 0L;
    private long lastFaceTriggerNs = 0L;
    private UUID previousWatchface = null;
    private boolean switchedWatchface = false;

    public XiaomiNfcRelayBridge(final Context context, final RelayTransport transport) {
        this(transport, new BoundEseBackend(context), DEFAULT_FACE_SWITCH_SETTLE_MS);
    }

    XiaomiNfcRelayBridge(final RelayTransport transport, final ApduBackend backend) {
        this(transport, backend, DEFAULT_FACE_SWITCH_SETTLE_MS);
    }

    XiaomiNfcRelayBridge(final RelayTransport transport, final ApduBackend backend, final long faceSwitchSettleMs) {
        this.transport = transport;
        this.backend = backend;
        this.faceSwitchSettleMs = Math.max(0L, faceSwitchSettleMs);
    }

    public static boolean isRelayMessage(final String appId, final String payload) {
        return RELAY_APP_ID.equals(appId) && payload != null && payload.contains("\"relay\":\"s4\"");
    }

    public void handleWatchMessage(final String appId, final String payload) {
        if (disposed.get() || !isRelayMessage(appId, payload)) {
            return;
        }
        final long msgSeq = incomingSeq.incrementAndGet();
        executor.execute(() -> processMessage(appId, payload, msgSeq));
    }

    private void processMessage(final String appId, final String payload, final long msgSeq) {
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
                handleApdu(appId, msg);
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
        boolean launchedFace = false;
        if (ok && switchFace) {
            final UUID active = transport.getActiveWatchface();
            if (transport.hasWatchface(RELAY_WATCHFACE_ID)) {
                final String activeId = active != null ? XiaomiWatchfaceService.toWatchfaceId(active) : null;
                final boolean watchAlreadyActive = "active".equals(watchStatus) || "bg-launched".equals(watchStatus);
                final boolean needFaceTrigger = !RELAY_WATCHFACE_ID.equals(activeId)
                        || (!watchAlreadyActive && !recentlyTriggeredFace());
                if (needFaceTrigger) {
                    // Dismiss the foreground QuickApp first so the watch returns to the watchface screen
                    // and actually executes relay watchface main.lua (and so relaunchApp opens a fresh page).
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
                    // Give S4 Lua watchface ~6.5s AFTER the watch confirms the switch to complete
                    // discovery stop, insmod, and background NSH launch before relaunching QuickApp.
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
                    .put("ok", ok);
            if (!ok) {
                ack.put("error", "ese_unavailable");
            }
            final byte[] payload = ack.toString().getBytes(StandardCharsets.UTF_8);
            if (launchedFace) {
                transport.relaunchApp(appId);
            } else {
                transport.sendMessage(appId, payload);
            }
            LOG.info("S4 NFC relay-open ok={} switchedFace={} ms={}", ok, launchedFace,
                    (System.nanoTime() - startNs) / 1_000_000L);
        } catch (final Exception e) {
            LOG.warn("Failed sending relay-open-ack", e);
        }
    }

    private void handleApdu(final String appId, final JSONObject msg) {
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
        backend.close();
        restoreWatchfaceIfNeeded();
        try {
            final JSONObject ack = new JSONObject()
                    .put("relay", "s4")
                    .put("op", "relay-close-ack");
            transport.sendMessage(appId, ack.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final Exception ignored) { }
    }

    public void onDisconnect() {
        final long cutoff = incomingSeq.get();
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
            backend.close();
            executor.shutdownNow();
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
