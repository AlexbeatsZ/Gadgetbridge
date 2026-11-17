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

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Aivs;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstruction;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstructionList;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiVoiceHandler;

/**
 * Hands the band's voice assistant over to the phone's default assistant.
 * <p>
 * When the band starts streaming voice, its own assistant is told to stop capturing, the band is used as a
 * Bluetooth headset (HFP voice recognition opens the SCO audio link) and the phone's voice command activity is
 * started, the same one Android starts for a headset's voice button. The assistant then listens through the
 * band's microphone and answers through its speaker.
 */
public class XiaomiVoiceAssistantBridge implements XiaomiVoiceHandler.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiVoiceAssistantBridge.class);

    public static final String PREF_VOICE_ASSISTANT_HANDOFF = "xiaomi_voice_assistant_handoff";

    public static final int COMMAND_TYPE_AIVS = 14;
    public static final int CMD_AIVS_INSTRUCTIONS = 0;
    public static final int RESULT_STOP_CAPTURE = 3;
    public static final int RESULT_DIALOG_FINISH = 4;

    // let the band release its microphone before the headset link is opened
    private static final long HANDOFF_DELAY_MS = 300;
    // the band may restart a session right away while its assistant winds down
    private static final long HANDOFF_DEBOUNCE_MS = 5000;
    // voice recognition holds the band in headset audio mode; long answers still fit
    private static final long VOICE_RECOGNITION_TIMEOUT_MS = 60000;

    private final XiaomiSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Nullable
    private BluetoothHeadset headset;
    @Nullable
    private BluetoothDevice voiceRecognitionDevice;
    private long lastHandoff = -HANDOFF_DEBOUNCE_MS;

    public XiaomiVoiceAssistantBridge(final XiaomiSupport support) {
        this.support = support;
    }

    private boolean isEnabled() {
        final GBDevice device = support.getDevice();
        return device != null && GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                .getBoolean(PREF_VOICE_ASSISTANT_HANDOFF, false);
    }

    @Override
    public void onVoiceStart(final int sessionId) {
        if (!isEnabled()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (now - lastHandoff < HANDOFF_DEBOUNCE_MS) {
            LOG.debug("Voice session {} within debounce, not handing off again", sessionId);
            return;
        }
        lastHandoff = now;
        LOG.info("Handing voice session {} over to the phone assistant", sessionId);
        support.sendCommand("aivs stop capture", buildStopCaptureCommand(sessionId));
        handler.postDelayed(this::handOff, HANDOFF_DELAY_MS);
    }

    @Override
    public void onVoicePcm(final int sessionId, final short[] pcm, final int samples) {
        // the phone assistant listens through the headset link instead
    }

    @Override
    public void onVoiceEnd(final int sessionId, final boolean quit) {
    }

    private void handOff() {
        final Context context = support.getContext();
        final GBDevice gbDevice = support.getDevice();
        final BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || gbDevice == null) {
            launchAssistant(context);
            return;
        }
        final BluetoothDevice device = adapter.getRemoteDevice(gbDevice.getAddress());
        if (headset != null) {
            startVoiceRecognition(context, device);
            return;
        }
        final boolean requested = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
                headset = (BluetoothHeadset) proxy;
                startVoiceRecognition(context, device);
            }

            @Override
            public void onServiceDisconnected(final int profile) {
                headset = null;
            }
        }, BluetoothProfile.HEADSET);
        if (!requested) {
            LOG.warn("Headset profile unavailable, the assistant will use the phone microphone");
            launchAssistant(context);
        }
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is required to connect to the band at all
    private void startVoiceRecognition(final Context context, final BluetoothDevice device) {
        try {
            final int state = headset.getConnectionState(device);
            if (state != BluetoothProfile.STATE_CONNECTED) {
                LOG.warn("Band not connected as headset (state {}), the assistant will use the phone microphone", state);
            } else if (headset.startVoiceRecognition(device)) {
                LOG.info("Headset voice recognition started on the band");
                voiceRecognitionDevice = device;
                handler.removeCallbacks(this::stopVoiceRecognition);
                handler.postDelayed(this::stopVoiceRecognition, VOICE_RECOGNITION_TIMEOUT_MS);
            } else {
                LOG.warn("Headset voice recognition did not start");
            }
        } catch (final SecurityException e) {
            LOG.error("No permission for headset voice recognition", e);
        }
        launchAssistant(context);
    }

    @SuppressLint("MissingPermission")
    private void stopVoiceRecognition() {
        if (headset != null && voiceRecognitionDevice != null) {
            try {
                LOG.info("Stopping headset voice recognition: {}", headset.stopVoiceRecognition(voiceRecognitionDevice));
            } catch (final SecurityException e) {
                LOG.error("No permission to stop headset voice recognition", e);
            }
        }
        voiceRecognitionDevice = null;
    }

    private static void launchAssistant(final Context context) {
        // what Android starts for a headset's voice button; resolves to the default assistant
        final Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            LOG.info("Started the phone assistant");
        } catch (final ActivityNotFoundException | SecurityException e) {
            LOG.error("Could not start the phone assistant", e);
        }
    }

    public void dispose() {
        handler.removeCallbacksAndMessages(null);
        stopVoiceRecognition();
        if (headset != null) {
            final BluetoothManager manager = (BluetoothManager) support.getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            if (manager != null && manager.getAdapter() != null) {
                manager.getAdapter().closeProfileProxy(BluetoothProfile.HEADSET, headset);
            }
            headset = null;
        }
    }

    static XiaomiProto.Command buildStopCaptureCommand(final int sessionId) {
        // sessions seen without a START (e.g. after a reconnect) are reported as -1
        final int session = Math.max(sessionId, 0);
        return XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE_AIVS)
                .setSubtype(CMD_AIVS_INSTRUCTIONS)
                .setAivs(Aivs.newBuilder().setInstructionList(AivsInstructionList.newBuilder()
                        .addInstruction(AivsInstruction.newBuilder().setResultType(RESULT_STOP_CAPTURE).setSessionId(session))
                        .addInstruction(AivsInstruction.newBuilder().setResultType(RESULT_DIALOG_FINISH).setSessionId(session))))
                .build();
    }
}
