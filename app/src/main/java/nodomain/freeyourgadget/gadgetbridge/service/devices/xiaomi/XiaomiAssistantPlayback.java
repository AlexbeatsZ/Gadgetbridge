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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.BuildConfig;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.VoiceMicContract;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.VoiceMicHub;

/** Owns the watch's SCO output only during a reply to a watch request. */
final class XiaomiAssistantPlayback implements VoiceMicHub.PlaybackListener {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiAssistantPlayback.class);
    private static final long CAPTURE_RELEASE_MS = 450;
    private static final long WAIT_FOR_SPEECH_MS = 10000;
    private static final long BETWEEN_TRACKS_MS = 800;
    private static final long MAX_PLAYBACK_MS = 90000;
    // how long a band request may hold the assistant's speech; each play() waits at most 4 s in the hook
    static final long HOLD_WINDOW_MS = 60000;
    // audio routing follows the SCO connection shortly after the headset reports it
    private static final long ROUTE_SETTLE_MS = 200;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoiceMicHub hub = VoiceMicHub.getInstance();
    private final Set<String> tracks = new HashSet<>();
    private final Runnable stopWhenIdle = this::stop;
    private final Runnable stopAtLimit = this::stop;
    private Route route;
    private Context holdContext;

    /**
     * Asks the assistant's processes to hold its speech until the watch's audio route is open. Only band requests
     * hold; the assistant used on the phone is never affected.
     */
    synchronized void hold(final Context context) {
        holdContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        sendHold(holdContext, HOLD_WINDOW_MS);
    }

    /** Lets the assistant's held speech play, wherever it is routed now. */
    synchronized void release() {
        if (holdContext != null) {
            sendHold(holdContext, 0);
            holdContext = null;
        }
    }

    private static void sendHold(final Context context, final long holdMs) {
        try {
            context.sendBroadcast(new Intent(VoiceMicContract.ACTION_PLAYBACK_HOLD)
                            .setPackage(VoiceMicContract.ASSISTANT_PACKAGE)
                            .putExtra(VoiceMicContract.KEY_HOLD_MS, holdMs),
                    BuildConfig.APPLICATION_ID + VoiceMicContract.CANCEL_PERMISSION_SUFFIX);
            LOG.debug("Assistant speech hold {} ms", holdMs);
        } catch (final RuntimeException e) {
            LOG.warn("Could not send the assistant speech hold", e);
        }
    }

    synchronized void arm(final Context context, final String address) {
        stopRoute();
        final BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        final AudioManager audio = context.getSystemService(AudioManager.class);
        final BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || audio == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return;
        }
        final Route next = new Route(context, adapter, audio, adapter.getRemoteDevice(address));
        route = next;
        handler.postDelayed(next.open, CAPTURE_RELEASE_MS);
        handler.postDelayed(stopWhenIdle, WAIT_FOR_SPEECH_MS);
        handler.postDelayed(stopAtLimit, MAX_PLAYBACK_MS);
        // last: speech that already plays is replayed here and cancels the idle stop
        hub.setPlaybackListener(this);
    }

    @Override
    public synchronized void onPlaybackChanged(final String trackId, final boolean playing) {
        if (route == null) {
            return;
        }
        if (playing) {
            tracks.add(trackId);
            handler.removeCallbacks(stopWhenIdle);
        } else if (tracks.remove(trackId) && tracks.isEmpty()) {
            // Streaming answers can play through more than one AudioTrack.
            handler.postDelayed(stopWhenIdle, BETWEEN_TRACKS_MS);
        }
    }

    /** Returns whether a reply was still waiting for or using the audio route. Releases any speech hold. */
    synchronized boolean stop() {
        release();
        return stopRoute();
    }

    private synchronized boolean stopRoute() {
        final Route previous = route;
        if (previous == null) {
            return false;
        }
        route = null;
        previous.unregisterAudioState();
        hub.clearPlaybackListener(this);
        handler.removeCallbacks(previous.open);
        handler.removeCallbacks(previous.ready);
        handler.removeCallbacks(stopWhenIdle);
        handler.removeCallbacks(stopAtLimit);
        tracks.clear();
        if (previous.headset != null) {
            try {
                if (previous.openedSco) {
                    previous.headset.stopVoiceRecognition(previous.device);
                    LOG.info("Released SCO for the watch assistant reply");
                }
            } catch (final RuntimeException e) {
                LOG.warn("Could not close SCO for the watch assistant reply", e);
            }
            closeProfile(previous.adapter, previous.headset);
        }
        return true;
    }

    private static void closeProfile(final BluetoothAdapter adapter, final BluetoothProfile proxy) {
        try {
            adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy);
        } catch (final RuntimeException e) {
            LOG.warn("Could not close the watch headset profile", e);
        }
    }

    private synchronized void open(final Route next) {
        if (route != next) {
            return;
        }
        try {
            if (!canOpen(next) || !next.adapter.getProfileProxy(next.context, next, BluetoothProfile.HEADSET)) {
                stop();
            }
        } catch (final RuntimeException e) {
            LOG.warn("Could not get the watch headset profile", e);
            stop();
        }
    }

    private boolean canOpen(final Route next) {
        return !hub.isActive() && next.audio.getMode() == AudioManager.MODE_NORMAL
                && !next.audio.isBluetoothScoOn();
    }

    private synchronized void routeReady(final Route ready) {
        if (route == ready) {
            LOG.info("SCO for the watch assistant reply is connected, releasing the assistant's speech");
            release();
        }
    }

    private final class Route implements BluetoothProfile.ServiceListener {
        private final Context context;
        private final BluetoothAdapter adapter;
        private final AudioManager audio;
        private final BluetoothDevice device;
        private final Runnable open = () -> XiaomiAssistantPlayback.this.open(this);
        private final Runnable ready = () -> XiaomiAssistantPlayback.this.routeReady(this);
        private BluetoothHeadset headset;
        private boolean openedSco;
        private BroadcastReceiver audioState;

        private void watchAudioState() {
            audioState = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context c, final Intent intent) {
                    final BluetoothDevice changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (changed != null && changed.getAddress().equalsIgnoreCase(device.getAddress())
                            && intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                        handler.removeCallbacks(ready);
                        handler.postDelayed(ready, ROUTE_SETTLE_MS);
                    }
                }
            };
            // only the Bluetooth stack sends this protected broadcast
            ContextCompat.registerReceiver(receiverContext(), audioState,
                    new IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
        }

        private Context receiverContext() {
            return context.getApplicationContext() != null ? context.getApplicationContext() : context;
        }

        private void unregisterAudioState() {
            if (audioState != null) {
                try {
                    receiverContext().unregisterReceiver(audioState);
                } catch (final IllegalArgumentException e) {
                    LOG.warn("Audio state receiver was not registered", e);
                }
                audioState = null;
            }
        }

        private Route(final Context context, final BluetoothAdapter adapter,
                      final AudioManager audio, final BluetoothDevice device) {
            this.context = context;
            this.adapter = adapter;
            this.audio = audio;
            this.device = device;
        }

        @Override
        public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
            synchronized (XiaomiAssistantPlayback.this) {
                if (route != this) {
                    closeProfile(adapter, proxy);
                    return;
                }
                headset = (BluetoothHeadset) proxy;
                try {
                    if (canOpen(this) && headset.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
                        openedSco = headset.startVoiceRecognition(device);
                    }
                } catch (final RuntimeException e) {
                    LOG.warn("Could not open SCO for the watch assistant reply", e);
                }
                if (openedSco) {
                    LOG.info("Requested SCO for the watch assistant reply");
                    watchAudioState();
                    if (headset.isAudioConnected(device)) {
                        handler.postDelayed(ready, ROUTE_SETTLE_MS);
                    }
                } else {
                    stop();
                }
            }
        }

        @Override
        public void onServiceDisconnected(final int profile) {
            synchronized (XiaomiAssistantPlayback.this) {
                if (route == this) {
                    stop();
                }
            }
        }
    }
}
