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
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.VoiceMicHub;

/** Owns the watch's SCO output only during a reply to a watch request. */
final class XiaomiAssistantPlayback implements VoiceMicHub.PlaybackListener {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiAssistantPlayback.class);
    private static final long CAPTURE_RELEASE_MS = 450;
    private static final long WAIT_FOR_SPEECH_MS = 10000;
    private static final long BETWEEN_TRACKS_MS = 800;
    private static final long MAX_PLAYBACK_MS = 90000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoiceMicHub hub = VoiceMicHub.getInstance();
    private final Set<String> tracks = new HashSet<>();
    private final Runnable stopWhenIdle = this::stop;
    private final Runnable stopAtLimit = this::stop;
    private Route route;

    synchronized void arm(final Context context, final String address) {
        stop();
        final BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        final AudioManager audio = context.getSystemService(AudioManager.class);
        final BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || audio == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return;
        }
        final Route next = new Route(context, adapter, audio, adapter.getRemoteDevice(address));
        route = next;
        hub.setPlaybackListener(this);
        handler.postDelayed(next.open, CAPTURE_RELEASE_MS);
        handler.postDelayed(stopWhenIdle, WAIT_FOR_SPEECH_MS);
        handler.postDelayed(stopAtLimit, MAX_PLAYBACK_MS);
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

    /** Returns whether a reply was still waiting for or using the audio route. */
    synchronized boolean stop() {
        final Route previous = route;
        if (previous == null) {
            return false;
        }
        route = null;
        hub.clearPlaybackListener(this);
        handler.removeCallbacks(previous.open);
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

    private final class Route implements BluetoothProfile.ServiceListener {
        private final Context context;
        private final BluetoothAdapter adapter;
        private final AudioManager audio;
        private final BluetoothDevice device;
        private final Runnable open = () -> XiaomiAssistantPlayback.this.open(this);
        private BluetoothHeadset headset;
        private boolean openedSco;

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
