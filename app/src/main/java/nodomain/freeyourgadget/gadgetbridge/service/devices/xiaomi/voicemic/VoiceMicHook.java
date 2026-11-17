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

import android.app.AndroidAppHelper;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.os.Bundle;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaSyncEvent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import nodomain.freeyourgadget.gadgetbridge.BuildConfig;

/**
 * Runs inside the assistant's processes (Xposed scope: Google app). While Gadgetbridge has a band voice session,
 * every recording the assistant starts gets the band's audio in place of what its microphone read. Without a
 * session nothing changes.
 * <p>
 * Only Android and Xposed classes may be used here, Gadgetbridge itself is not initialised in this process.
 */
public class VoiceMicHook implements IXposedHookLoadPackage {
    private static final String TAG = "GBVoiceMic";

    private static final Map<AudioRecord, Feed> FEEDS = Collections.synchronizedMap(new WeakHashMap<>());
    // read overloads call each other (read(byte[], int, int) calls the one with a read mode); only the
    // outermost call on a thread may consume band audio, or it is drained twice as fast
    private static final ThreadLocal<int[]> READ_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ExecutorService CALLS = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "gb-voice-mic-call");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!VoiceMicContract.ASSISTANT_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        final XC_MethodHook start = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                onStart((AudioRecord) param.thisObject);
            }
        };
        XposedHelpers.findAndHookMethod(AudioRecord.class, "startRecording", start);
        XposedHelpers.findAndHookMethod(AudioRecord.class, "startRecording", MediaSyncEvent.class, start);

        final XC_MethodHook stop = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                onStop((AudioRecord) param.thisObject);
            }
        };
        XposedHelpers.findAndHookMethod(AudioRecord.class, "stop", stop);
        XposedHelpers.findAndHookMethod(AudioRecord.class, "release", stop);

        // The band's headset microphone is silent outside calls, and opening its headset audio while the band's
        // own assistant captures cuts the band's voice stream. During a session the assistant is refused the
        // headset; it then records from the phone microphone, which is replaced anyway.
        XposedHelpers.findAndHookMethod(BluetoothHeadset.class, "startVoiceRecognition", BluetoothDevice.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(final MethodHookParam param) {
                        if (sessionActive()) {
                            log("refused headset voice recognition during a band voice session");
                            param.setResult(false);
                        }
                    }
                });

        hookAnswerText();

        hookRead("byte[]", byte[].class, int.class, int.class);
        hookRead("byte[]", byte[].class, int.class, int.class, int.class);
        hookRead("short[]", short[].class, int.class, int.class);
        hookRead("short[]", short[].class, int.class, int.class, int.class);
        hookRead("float[]", float[].class, int.class, int.class, int.class);
        hookRead("ByteBuffer", ByteBuffer.class, int.class);
        hookRead("ByteBuffer", ByteBuffer.class, int.class, int.class);
        log("hooks installed in " + lpparam.processName);
    }

    private static void hookRead(final String kind, final Object... parameterTypes) {
        final Object[] args = new Object[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
        args[parameterTypes.length] = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(final MethodHookParam param) {
                READ_DEPTH.get()[0]++;
            }

            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                if (--READ_DEPTH.get()[0] > 0) {
                    return;
                }
                final Feed feed = FEEDS.get((AudioRecord) param.thisObject);
                if (feed == null || !(param.getResult() instanceof Integer)) {
                    return;
                }
                final int count = (Integer) param.getResult();
                if (count > 0) {
                    feed.inject(kind, (AudioRecord) param.thisObject, param.args, count);
                }
            }
        };
        XposedHelpers.findAndHookMethod(AudioRecord.class, "read", args);
    }

    private static Uri streamUri() {
        return Uri.parse("content://" + BuildConfig.APPLICATION_ID + VoiceMicContract.AUTHORITY_SUFFIX + "/"
                + VoiceMicContract.PATH_STREAM);
    }

    private static final Map<Integer, String> VIEW_NAMES = Collections.synchronizedMap(new java.util.HashMap<>());

    private static String viewName(final View view) {
        final int id = view.getId();
        if (id == android.view.View.NO_ID) {
            return "";
        }
        String name = VIEW_NAMES.get(id);
        if (name == null) {
            try {
                name = view.getResources().getResourceEntryName(id);
            } catch (final Exception e) {
                name = "";
            }
            VIEW_NAMES.put(id, name);
        }
        return name;
    }

    /**
     * Transcripts are TextViews, but tool results can be composed cards whose descendants have no resource IDs.
     * Observe the response container after layout so both kinds of result include their complete visible content.
     */
    private static void hookAnswerText() {
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                final View view = (View) param.thisObject;
                if (!AssistantResponseReader.isResponseContainer(viewName(view))) {
                    return;
                }
                ResponseObserver observer = (ResponseObserver) XposedHelpers.getAdditionalInstanceField(view,
                        "gb-response-observer");
                if (observer == null) {
                    observer = new ResponseObserver(view);
                    XposedHelpers.setAdditionalInstanceField(view, "gb-response-observer", observer);
                }
                view.removeCallbacks(observer);
                view.post(observer);
            }
        });
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, TextView.BufferType.class,
                boolean.class, int.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        final TextView view = (TextView) param.thisObject;
                        final boolean observedResponse = markResponseChanged(view);
                        final String name = viewName(view);
                        if (!name.startsWith(VoiceMicContract.PANEL_VIEW_PREFIX)) {
                            return;
                        }
                        final CharSequence text = (CharSequence) param.args[0];
                        if (text == null || text.toString().trim().length() == 0) {
                            return;
                        }
                        final Context appContext = view.getContext().getApplicationContext();
                        if (VoiceMicContract.ANSWER_VIEW_ID.equals(name) && !observedResponse) {
                            // Keep plain answers working in layouts that have no known response container.
                            call(appContext, VoiceMicContract.METHOD_ANSWER, text.toString().trim());
                        } else if (name.contains("query")) {
                            call(appContext, VoiceMicContract.METHOD_PANEL_TEXT,
                                    name + VoiceMicContract.SEPARATOR + text.toString().trim());
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(View.class, "setContentDescription", CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(final MethodHookParam param) {
                        markResponseChanged((View) param.thisObject);
                    }
                });
    }

    private static boolean markResponseChanged(final View view) {
        View current = view;
        while (current != null) {
            final ResponseObserver observer = (ResponseObserver) XposedHelpers.getAdditionalInstanceField(current,
                    "gb-response-observer");
            if (observer != null) {
                observer.dirty = true;
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private static final class ResponseObserver implements Runnable {
        private final View view;
        private String lastText = "";
        private boolean dirty;

        ResponseObserver(final View view) {
            this.view = view;
        }

        @Override
        public void run() {
            if (!view.isAttachedToWindow()) {
                return;
            }
            final Context context = view.getContext().getApplicationContext();
            CALLS.execute(() -> {
                long session = 0;
                try {
                    final Bundle state = context.getContentResolver().call(streamUri(),
                            VoiceMicContract.METHOD_RESPONSE_SESSION, null, null);
                    session = state != null ? state.getLong(VoiceMicContract.KEY_SESSION) : 0;
                } catch (final Throwable t) {
                    log("response session lookup failed: " + t);
                }
                final long responseSession = session;
                view.post(() -> read(context, responseSession));
            });
        }

        private void read(final Context context, final long session) {
            if (!view.isAttachedToWindow()) {
                return;
            }
            if (!view.isShown()) {
                view.postDelayed(this, 500);
                return;
            }
            try {
                final String text = AssistantResponseReader.read(view);
                final boolean changed = dirty || !text.equals(lastText);
                lastText = text;
                dirty = false;
                if (session != 0 && changed) {
                    CALLS.execute(() -> {
                        try {
                            final Bundle extras = new Bundle();
                            extras.putLong(VoiceMicContract.KEY_SESSION, session);
                            context.getContentResolver().call(streamUri(), VoiceMicContract.METHOD_RESPONSE, text, extras);
                        } catch (final Throwable t) {
                            log("response snapshot failed: " + t);
                        }
                    });
                }
            } catch (final Throwable t) {
                log("response view could not be read: " + t);
            }
            view.postDelayed(this, 500);
        }
    }

    private static boolean sessionActive() {
        final Context context = AndroidAppHelper.currentApplication();
        if (context == null) {
            return false;
        }
        try {
            final Bundle result = context.getContentResolver().call(streamUri(), VoiceMicContract.METHOD_IS_ACTIVE, null, null);
            return result != null && result.getBoolean(VoiceMicContract.KEY_ACTIVE);
        } catch (final Throwable t) {
            return false;
        }
    }

    private static void onStart(final AudioRecord record) {
        if (FEEDS.containsKey(record)) {
            return;
        }
        final Context context = AndroidAppHelper.currentApplication();
        if (context == null) {
            return;
        }
        try {
            final ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(streamUri(), "r");
            if (pfd != null) {
                FEEDS.put(record, new Feed(context, pfd));
                log("feeding recording: rate " + record.getSampleRate() + ", channels " + record.getChannelCount()
                        + ", encoding " + record.getAudioFormat() + ", source " + record.getAudioSource());
            }
        } catch (final FileNotFoundException | SecurityException e) {
            // no band voice session, the real microphone is used
        } catch (final Throwable t) {
            log("could not open the band voice: " + t);
        }
    }

    private static void onStop(final AudioRecord record) {
        final Feed feed = FEEDS.remove(record);
        if (feed != null) {
            feed.close();
            call(feed.context, VoiceMicContract.METHOD_RECORDING_STOPPED, null);
        }
    }

    private static void call(final Context context, final String method, final String arg) {
        CALLS.execute(() -> {
            try {
                context.getContentResolver().call(streamUri(), method, arg, null);
            } catch (final Throwable t) {
                log("call " + method + " failed: " + t);
            }
        });
    }

    private static void log(final String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    /** Band audio for one recording: a reader thread fills a ring buffer the read hooks drain. */
    private static final class Feed implements Runnable {
        private final Context context;
        private final ParcelFileDescriptor pfd;
        private final short[] ring = new short[VoiceMicContract.SAMPLE_RATE * 30];
        private int head;
        private int size;
        private volatile boolean closed;
        private boolean reported;
        // per-second statistics for the log
        private long statsStart;
        private int fedSamples;
        private int silentSamples;
        // resampling from 16 kHz by repeating or skipping samples
        private int phase;
        private short current;
        // jitter buffer: the band sends 100 ms packets with uneven timing while the assistant reads every 20 ms;
        // a gap of silence in the middle of speech hurts recognition more than a little extra latency
        private static final int START_CUSHION = VoiceMicContract.SAMPLE_RATE * 3 / 10;
        private static final int RESUME_CUSHION = VoiceMicContract.SAMPLE_RATE / 10;
        private boolean playing;
        private boolean startedOnce;

        Feed(final Context context, final ParcelFileDescriptor pfd) {
            this.context = context;
            this.pfd = pfd;
            final Thread thread = new Thread(this, "gb-voice-mic-feed");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void run() {
            final byte[] buf = new byte[4096];
            int pending = -1;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                int n;
                while (!closed && (n = in.read(buf)) > 0) {
                    synchronized (this) {
                        for (int i = 0; i < n; i++) {
                            final int b = buf[i] & 0xff;
                            if (pending < 0) {
                                pending = b;
                                continue;
                            }
                            push((short) (pending | (b << 8)));
                            pending = -1;
                        }
                    }
                }
            } catch (final IOException e) {
                // closed
            }
        }

        private void push(final short sample) {
            if (size == ring.length) {
                // drop the oldest rather than block the pipe
                head = (head + 1) % ring.length;
                size--;
            }
            ring[(head + size) % ring.length] = sample;
            size++;
        }

        /** Next band sample at the recording's rate; silence while none is buffered. */
        private short next(final int rate) {
            phase += VoiceMicContract.SAMPLE_RATE;
            while (phase >= rate) {
                phase -= rate;
                if (!playing && size >= (startedOnce ? RESUME_CUSHION : START_CUSHION)) {
                    playing = true;
                    startedOnce = true;
                }
                if (playing && size == 0) {
                    playing = false;
                }
                if (playing) {
                    current = ring[head];
                    head = (head + 1) % ring.length;
                    size--;
                    fedSamples++;
                } else {
                    current = 0;
                    silentSamples++;
                }
            }
            return current;
        }

        synchronized void inject(final String kind, final AudioRecord record, final Object[] args, final int count) {
            final int rate = record.getSampleRate() > 0 ? record.getSampleRate() : VoiceMicContract.SAMPLE_RATE;
            final int channels = Math.max(1, record.getChannelCount());
            final int encoding = record.getAudioFormat();
            if (!reported) {
                reported = true;
                call(context, VoiceMicContract.METHOD_REPORT, "read " + kind + ", rate " + rate + ", channels "
                        + channels + ", encoding " + encoding + ", first read " + count + ", buffered " + size);
            }
            final long now = android.os.SystemClock.elapsedRealtime();
            if (statsStart == 0) {
                statsStart = now;
            } else if (now - statsStart >= 1000) {
                log("fed " + fedSamples + " band samples, " + silentSamples + " silent, buffered " + size);
                statsStart = now;
                fedSamples = 0;
                silentSamples = 0;
            }
            switch (kind) {
                case "short[]": {
                    final short[] out = (short[]) args[0];
                    final int offset = (Integer) args[1];
                    for (int i = 0; i + channels <= count; i += channels) {
                        final short s = next(rate);
                        for (int c = 0; c < channels; c++) out[offset + i + c] = s;
                    }
                    break;
                }
                case "float[]": {
                    final float[] out = (float[]) args[0];
                    final int offset = (Integer) args[1];
                    for (int i = 0; i + channels <= count; i += channels) {
                        final float s = next(rate) / 32768f;
                        for (int c = 0; c < channels; c++) out[offset + i + c] = s;
                    }
                    break;
                }
                case "byte[]":
                case "ByteBuffer": {
                    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        fillFloatBytes(kind, args, count, channels, rate);
                    } else if (encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_DEFAULT) {
                        fillPcm16Bytes(kind, args, count, channels, rate);
                    }
                    break;
                }
            }
        }

        private void fillPcm16Bytes(final String kind, final Object[] args, final int count, final int channels, final int rate) {
            final int frameBytes = 2 * channels;
            for (int i = 0; i + frameBytes <= count; i += frameBytes) {
                final short s = next(rate);
                for (int c = 0; c < channels; c++) {
                    putByte(kind, args, i + 2 * c, (byte) s);
                    putByte(kind, args, i + 2 * c + 1, (byte) (s >> 8));
                }
            }
        }

        private void fillFloatBytes(final String kind, final Object[] args, final int count, final int channels, final int rate) {
            final int frameBytes = 4 * channels;
            for (int i = 0; i + frameBytes <= count; i += frameBytes) {
                final int bits = Float.floatToIntBits(next(rate) / 32768f);
                for (int c = 0; c < channels; c++) {
                    for (int b = 0; b < 4; b++) {
                        putByte(kind, args, i + 4 * c + b, (byte) (bits >> (8 * b)));
                    }
                }
            }
        }

        private static void putByte(final String kind, final Object[] args, final int index, final byte value) {
            if ("ByteBuffer".equals(kind)) {
                final ByteBuffer buffer = (ByteBuffer) args[0];
                buffer.put(buffer.position() + index, value);
            } else {
                ((byte[]) args[0])[(Integer) args[1] + index] = value;
            }
        }

        void close() {
            closed = true;
            try {
                pfd.close();
            } catch (final IOException ignored) {
                // closing anyway
            }
        }
    }
}
