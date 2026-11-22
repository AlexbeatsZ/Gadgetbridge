// SPDX-License-Identifier: AGPL-3.0-or-later
package research.xiaomi.tsm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Calls the official service as the real calling UID. No credential logging,
 * visibility changes, invalidation, account injection, or TSM card operations.
 * A successful credential result is deliberately discarded in this probe.
 */
public final class TsmAuthProbe implements ServiceConnection {
    private static final String TAG = "GBTsmAuthResearch";
    private static final String DESCRIPTOR = "com.xiaomi.passport.IPassportServiceTokenService";
    private static final String[] ERRORS = {"ERROR_UNKNOWN", "ERROR_NONE", "ERROR_NO_ACCOUNT",
        "ERROR_APP_PERMISSION_FORBIDDEN", "ERROR_IOERROR",
        "ERROR_OLD_MIUI_ACCOUNT_MANAGER_PERMISSION_ISSUE", "ERROR_CANCELLED",
        "ERROR_AUTHENTICATOR_ERROR", "ERROR_TIME_OUT", "ERROR_REMOTE_EXCEPTION",
        "ERROR_USER_INTERACTION_NEEDED"};
    private final Context context;
    private final ScheduledExecutorService worker = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean bound;

    private TsmAuthProbe(Context context) { this.context = context.getApplicationContext(); }

    public static void start(Context context) {
        TsmAuthProbe probe = new TsmAuthProbe(context);
        Log.i(TAG, "callerUid=" + Process.myUid() + " callerPackage=" + context.getPackageName());
        Intent service = new Intent("com.xiaomi.account.action.SERVICE_TOKEN_OP")
            .setPackage("com.xiaomi.account");
        try {
            probe.bound = probe.context.bindService(service, probe, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindAccepted=" + probe.bound);
            if (!probe.bound) probe.close();
            else probe.worker.schedule(() -> {
                if (!probe.closed.get()) Log.i(TAG, "result=SERVICE_TIMEOUT");
                probe.close();
            }, 20, TimeUnit.SECONDS);
        } catch (Exception failure) {
            Log.i(TAG, "result=BIND_FAILED exceptionType=" + failure.getClass().getSimpleName());
            probe.close();
        }
    }

    private static boolean feature(IBinder binder, int code) throws Exception {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            if (!binder.transact(code, request, reply, 0)) throw new IllegalStateException("unsupported transaction");
            reply.readException();
            return reply.readInt() != 0;
        } finally { request.recycle(); reply.recycle(); }
    }

    private void authenticate(IBinder binder) {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            if (!DESCRIPTOR.equals(binder.getInterfaceDescriptor()))
                throw new IllegalStateException("unexpected service descriptor");
            Log.i(TAG, "supportServiceTokenUIResponse=" + feature(binder, 3)
                + " supportAccessAccount=" + feature(binder, 5));
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeString("tsm-auth");
            long start = android.os.SystemClock.elapsedRealtime();
            if (!binder.transact(1, request, reply, 0)) throw new IllegalStateException("unsupported transaction");
            reply.readException();
            Log.i(TAG, "tokenRequestElapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start));
            if (reply.readInt() == 0) { Log.i(TAG, "result=NULL_RESPONSE"); return; }
            if (!"V2#".equals(reply.readString())) {
                Log.i(TAG, "result=UNSUPPORTED_PARCEL_VERSION"); return;
            }
            Bundle result = reply.readBundle(Intent.class.getClassLoader());
            if (result == null) { Log.i(TAG, "result=NULL_BUNDLE"); return; }
            int code = result.getInt("errorCode", -1);
            String error = code >= 0 && code < ERRORS.length ? ERRORS[code] : "UNRECOGNIZED_ERROR";
            // Only fixed enums, field presence and approved generic diagnostics.
            String message = result.getString("errorMessage", "");
            String category = message.equals("signature invalid") ? "signature_invalid"
                : message.equals("query too frequently") ? "rate_limited"
                : message.equals("error online fetching app white list") ? "whitelist_fetch"
                : message.isEmpty() ? "empty" : "redacted";
            Log.i(TAG, "result=" + error + " errorCode=" + code + " messageCategory=" + category
                + " interactionIntentPresent=" + (result.getParcelable("intent") != null)
                + " credentialFieldsPresent=" + result.containsKey("serviceToken")
                + " credentialValuesRead=false httpRequests=0");
            result.clear();
        } catch (Exception failure) {
            Log.i(TAG, "result=AUTH_PROBE_FAILED exceptionType=" + failure.getClass().getSimpleName());
        } finally { request.recycle(); reply.recycle(); close(); }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.i(TAG, "serviceConnected=" + name.flattenToShortString());
        if (!closed.get()) worker.execute(() -> authenticate(binder));
    }
    @Override public void onServiceDisconnected(ComponentName name) { close(); }
    @Override public void onNullBinding(ComponentName name) { Log.i(TAG, "result=NULL_BINDING"); close(); }
    @Override public void onBindingDied(ComponentName name) { Log.i(TAG, "result=BINDING_DIED"); close(); }
    private void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (bound) try { context.unbindService(this); } catch (IllegalArgumentException ignored) { }
        worker.shutdown();
        Log.i(TAG, "serviceUnbound=true");
    }
}
