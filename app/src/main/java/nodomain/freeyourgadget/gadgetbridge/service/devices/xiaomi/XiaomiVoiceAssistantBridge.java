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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Aivs;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstruction;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstructionList;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsText;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiVoiceHandler;

/**
 * Hands the band's voice assistant over to the phone's default assistant and keeps both in step.
 * <p>
 * When the band starts streaming voice, its own assistant stops capturing (its screen stays, showing that it is
 * listening) and the phone's voice command activity is started, the same one Android starts for a headset's voice
 * button. The assistant opens the band's headset (HFP) audio itself, listens through the band's microphone and
 * answers through its speaker. Opening that link here first makes the assistant's own request close it again,
 * leaving it on the phone microphone, so it is left to the assistant.
 * <ul>
 * <li>When the assistant closes the headset audio, the band's dialog is finished.</li>
 * <li>When the dialog is left on the band, headset voice recognition is stopped, which ends the assistant's
 * listening.</li>
 * </ul>
 */
public class XiaomiVoiceAssistantBridge implements XiaomiVoiceHandler.Listener {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiVoiceAssistantBridge.class);

    public static final String PREF_VOICE_ASSISTANT_HANDOFF = "xiaomi_voice_assistant_handoff";

    public static final int COMMAND_TYPE_AIVS = 14;
    public static final int CMD_AIVS_INSTRUCTIONS = 0;
    public static final int RESULT_STOP_CAPTURE = 3;
    public static final int RESULT_DIALOG_FINISH = 4;
    public static final int RESULT_TEMPLATE_TOAST = 256;

    // the band may restart a session right away while its assistant winds down
    private static final long HANDOFF_DEBOUNCE_MS = 5000;
    // finish the band's dialog if the assistant never opens the headset audio
    private static final long NO_HEADSET_FINISH_MS = 30000;
    // and in any case after this long
    private static final long MAX_DIALOG_MS = 120000;

    private final XiaomiSupport support;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishTimeout = () -> finishDialog("timeout");

    private long lastHandoff = -HANDOFF_DEBOUNCE_MS;
    // session handed over and not finished yet
    private int activeSession = -1;
    private boolean headsetAudioOpen;
    @Nullable
    private BroadcastReceiver audioStateReceiver;
    @Nullable
    private BluetoothHeadset headset;

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
        activeSession = Math.max(sessionId, 0);
        headsetAudioOpen = false;
        LOG.info("Handing voice session {} over to the phone assistant", sessionId);
        // frees the band's microphone for the headset link; without a result the band says it did not catch that
        support.sendCommand("aivs stop capture", buildStopCaptureCommand(activeSession,
                support.getContext().getString(R.string.xiaomi_voice_phone_assistant_listening)));
        registerAudioStateReceiver();
        requestHeadsetProxy();
        handler.removeCallbacks(finishTimeout);
        handler.postDelayed(finishTimeout, NO_HEADSET_FINISH_MS);
        launchAssistant(support.getContext());
    }

    @Override
    public void onVoicePcm(final int sessionId, final short[] pcm, final int samples) {
        // the phone assistant listens through the headset link instead
    }

    @Override
    public void onVoiceEnd(final int sessionId, final boolean quit) {
        if (!quit || activeSession < 0) {
            return;
        }
        LOG.info("Voice session {} left on the band, stopping the phone assistant", sessionId);
        activeSession = -1;
        stopHeadsetVoiceRecognition();
        cleanup();
    }

    private void registerAudioStateReceiver() {
        if (audioStateReceiver != null) {
            return;
        }
        audioStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (isBand(device)) {
                    onHeadsetAudioState(intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1));
                }
            }
        };
        ContextCompat.registerReceiver(support.getContext(), audioStateReceiver,
                new IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED);
    }

    private boolean isBand(@Nullable final BluetoothDevice device) {
        final GBDevice gbDevice = support.getDevice();
        return device != null && gbDevice != null && gbDevice.getAddress().equalsIgnoreCase(device.getAddress());
    }

    private void onHeadsetAudioState(final int state) {
        LOG.debug("Band headset audio state {} during voice session {}", state, activeSession);
        if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
            headsetAudioOpen = true;
            handler.removeCallbacks(finishTimeout);
            handler.postDelayed(finishTimeout, MAX_DIALOG_MS);
        } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED && headsetAudioOpen) {
            // the assistant is done with the band's microphone and speaker
            headsetAudioOpen = false;
            finishDialog("assistant closed the headset audio");
        }
    }

    private void finishDialog(final String reason) {
        if (activeSession >= 0) {
            LOG.info("Finishing voice session {} on the band: {}", activeSession, reason);
            support.sendCommand("aivs dialog finish", buildDialogFinishCommand(activeSession));
        }
        activeSession = -1;
        cleanup();
    }

    private void requestHeadsetProxy() {
        if (headset != null) {
            return;
        }
        final BluetoothAdapter adapter = getAdapter();
        if (adapter == null) {
            return;
        }
        adapter.getProfileProxy(support.getContext(), new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(final int profile, final BluetoothProfile proxy) {
                headset = (BluetoothHeadset) proxy;
            }

            @Override
            public void onServiceDisconnected(final int profile) {
                headset = null;
            }
        }, BluetoothProfile.HEADSET);
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is required to connect to the band at all
    private void stopHeadsetVoiceRecognition() {
        final BluetoothAdapter adapter = getAdapter();
        final GBDevice gbDevice = support.getDevice();
        if (headset == null || adapter == null || gbDevice == null) {
            return;
        }
        try {
            final boolean stopped = headset.stopVoiceRecognition(adapter.getRemoteDevice(gbDevice.getAddress()));
            LOG.info("Stopping headset voice recognition on the band: {}", stopped);
        } catch (final SecurityException e) {
            LOG.error("No permission to stop headset voice recognition", e);
        }
    }

    @Nullable
    private BluetoothAdapter getAdapter() {
        final BluetoothManager manager = (BluetoothManager) support.getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : null;
    }

    private void cleanup() {
        handler.removeCallbacks(finishTimeout);
        if (audioStateReceiver != null) {
            try {
                support.getContext().unregisterReceiver(audioStateReceiver);
            } catch (final IllegalArgumentException ignored) {
                // already gone
            }
            audioStateReceiver = null;
        }
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
        activeSession = -1;
        cleanup();
        if (headset != null) {
            final BluetoothAdapter adapter = getAdapter();
            if (adapter != null) {
                adapter.closeProfileProxy(BluetoothProfile.HEADSET, headset);
            }
            headset = null;
        }
    }

    private static AivsInstruction.Builder instruction(final int resultType, final int session) {
        return AivsInstruction.newBuilder().setResultType(resultType).setSessionId(session);
    }

    static XiaomiProto.Command buildStopCaptureCommand(final int sessionId, @Nullable final String toast) {
        final AivsInstructionList.Builder list = AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_STOP_CAPTURE, Math.max(sessionId, 0)));
        if (toast != null) {
            list.addInstruction(instruction(RESULT_TEMPLATE_TOAST, Math.max(sessionId, 0))
                    .setTemplateToast(AivsText.newBuilder().setText(toast)));
        }
        return aivsCommand(list);
    }

    static XiaomiProto.Command buildDialogFinishCommand(final int sessionId) {
        return aivsCommand(AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_DIALOG_FINISH, Math.max(sessionId, 0))));
    }

    private static XiaomiProto.Command aivsCommand(final AivsInstructionList.Builder list) {
        return XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE_AIVS)
                .setSubtype(CMD_AIVS_INSTRUCTIONS)
                .setAivs(Aivs.newBuilder().setInstructionList(list))
                .build();
    }
}
