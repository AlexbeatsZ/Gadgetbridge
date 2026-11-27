/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** S4 rejects System/59. Use Vela's local motor, with a fresh two-phase handshake. */
final class XiaomiWakeGuardVibration {
    static final String APP = "org.dayweave.mobile";
    static final String PROTOCOL = "gadgetbridge.wake_guard.v1";
    static final String PREF_STATUS = nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiWakeGuardConfig.PREF_VIBRATION_STATUS;
    static final long TIMEOUT_MS = 10_000;

    interface Transport {
        void prepare(byte[] payload, boolean forceLaunch);
        void direct(byte[] payload);
        void clearPending();
    }

    private final Transport transport;
    private final Consumer<String> status;
    private final LongSupplier elapsed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String id;
    private long deadline;
    private boolean sent;
    private final Runnable timeout = () -> finish("failed");
    private final Runnable retry = this::retryPrepare;

    XiaomiWakeGuardVibration(Transport transport, Consumer<String> status) {
        this(transport, status, SystemClock::elapsedRealtime);
    }

    XiaomiWakeGuardVibration(Transport transport, Consumer<String> status, LongSupplier elapsed) {
        this.transport = transport;
        this.status = status;
        this.elapsed = elapsed;
    }

    synchronized void request() {
        cancel();
        id = UUID.randomUUID().toString();
        deadline = elapsed.getAsLong() + TIMEOUT_MS;
        sent = false;
        status.accept("pending");
        transport.prepare(payload("prepare"), false);
        handler.postDelayed(retry, 2_000);
        handler.postDelayed(timeout, TIMEOUT_MS);
    }

    private synchronized void retryPrepare() {
        // An app closed within RpkService's open window needs one explicit relaunch.
        if (id != null && !sent && elapsed.getAsLong() < deadline) transport.prepare(payload("prepare"), true);
    }

    static boolean isMessage(String app, String payload) {
        if (!APP.equals(app)) return false;
        try { return PROTOCOL.equals(new JSONObject(payload).optString("protocol")); }
        catch (JSONException e) { return false; }
    }

    synchronized void receive(String payload) {
        try {
            JSONObject reply = new JSONObject(payload);
            if (id == null || !id.equals(reply.optString("id")) || !PROTOCOL.equals(reply.optString("protocol"))) return;
            if (elapsed.getAsLong() >= deadline) { finish("failed"); return; }
            switch (reply.optString("op")) {
                case "ready":
                    if (!sent) {
                        sent = true;
                        handler.removeCallbacks(retry);
                        // Never queue a motor command for a later launch/reconnect.
                        transport.direct(payload("vibrate"));
                    }
                    break;
                case "executed":
                    if (sent) finish("ok");
                    break;
                case "failed": finish("failed"); break;
            }
        } catch (JSONException ignored) { }
    }

    synchronized void cancel() {
        if (id != null && sent) transport.direct(payload("cancel"));
        if (id != null) finish("cancelled");
    }

    private synchronized void finish(String value) {
        if (id == null) return;
        handler.removeCallbacks(retry);
        handler.removeCallbacks(timeout);
        transport.clearPending();
        id = null;
        sent = false;
        status.accept(value);
    }

    private byte[] payload(String op) {
        try {
            return new JSONObject().put("protocol", PROTOCOL).put("id", id).put("op", op)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
