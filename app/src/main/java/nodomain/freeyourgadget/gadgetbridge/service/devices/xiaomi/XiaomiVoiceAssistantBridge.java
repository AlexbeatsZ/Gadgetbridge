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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.Aivs;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstruction;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstructionList;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsLlmLoadingCard;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsLlmToastStream;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsRecognizeResult;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsRecognizeResultItems;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsText;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services.XiaomiVoiceHandler;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.VoiceMicHub;

/**
 * Hands the band's voice assistant over to the phone's default assistant.
 * <p>
 * The band cannot be used as a Bluetooth headset microphone (its HFP uplink is silent outside real calls, see
 * docs/design/xiaomi-s4-official-audio.md). Instead the band keeps streaming its own voice channel, which is
 * decoded here and fed to the assistant through {@link VoiceMicHub}; Gadgetbridge's Xposed hook in the Google app
 * replaces what the assistant's recording reads with that audio. The band's assistant screen stays open while the
 * phone assistant listens. Once it stops recording the band stops capturing and shows a loading card; the answer
 * text the assistant then shows on the phone is sent to the band as an LLM answer card, the way the official app
 * shows answers of its own large model.
 */
public class XiaomiVoiceAssistantBridge implements XiaomiVoiceHandler.Listener, VoiceMicHub.Listener,
        VoiceMicHub.AnswerListener {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiVoiceAssistantBridge.class);

    public static final String PREF_VOICE_ASSISTANT_HANDOFF = "xiaomi_voice_assistant_handoff";

    public static final int COMMAND_TYPE_AIVS = 14;
    public static final int CMD_AIVS_INSTRUCTIONS = 0;
    public static final int RESULT_RECOGNIZE_RESULT = 2;
    public static final int RESULT_STOP_CAPTURE = 3;
    public static final int RESULT_DIALOG_FINISH = 4;
    public static final int RESULT_TEMPLATE_TOAST = 256;
    public static final int RESULT_LLM_LOADING_CARD = 289;
    public static final int RESULT_LLM_TOAST_STREAM = 292;
    public static final int RESULT_LLM_FINISH_STREAM = 293;

    // the assistant may stop a probe recording and open the real one right after
    private static final long STOPPED_GRACE_MS = 1500;
    // finish the band's dialog in any case after this long
    private static final long MAX_DIALOG_MS = 60000;
    // how long the band waits for the assistant's answer
    private static final int ANSWER_TIMEOUT_S = 30;
    // the answer text is updated while it streams in; it is sent once it stopped changing
    private static final long ANSWER_SETTLE_MS = 1500;
    // the official app cuts texts for the band at this length
    private static final int MAX_ANSWER_CHARS = 1000;

    private final XiaomiSupport support;
    private final VoiceMicHub hub = VoiceMicHub.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishTimeout = () -> finishDialog("timeout");
    private final Runnable finishAfterStop = this::awaitAnswer;
    private final Runnable sendAnswer = this::sendAnswer;

    // session handed over and not finished yet
    private int activeSession = -1;
    // the band shows a loading card and waits for the assistant's answer
    private boolean awaitingAnswer;
    @Nullable
    private String answer;
    // what was said, as transcribed in the assistant's panel
    @Nullable
    private String query;
    // panel texts seen outside a session, such as the input hint, are not transcripts
    private final Set<String> idleTexts = new HashSet<>();

    public XiaomiVoiceAssistantBridge(final XiaomiSupport support) {
        this.support = support;
        hub.setAnswerListener(this);
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
        if (activeSession >= 0) {
            // the band restarted its session while the assistant still listens; keep feeding
            LOG.debug("Voice session {} while session {} is handed over", sessionId, activeSession);
            return;
        }
        activeSession = Math.max(sessionId, 0);
        awaitingAnswer = false;
        answer = null;
        query = null;
        LOG.info("Handing voice session {} over to the phone assistant", sessionId);
        hub.startSession(this);
        handler.removeCallbacks(finishTimeout);
        handler.postDelayed(finishTimeout, MAX_DIALOG_MS);
        launchAssistant(support.getContext());
    }

    @Override
    public void onVoicePcm(final int sessionId, final short[] pcm, final int samples) {
        hub.onPcm(pcm, samples);
    }

    @Override
    public void onVoiceEnd(final int sessionId, final boolean quit) {
        // the assistant may still be reading what was buffered; it finishes the dialog when it stops
        LOG.debug("Band voice session {} {}", sessionId, quit ? "quit" : "ended");
    }

    @Override
    public void onAssistantStartedListening() {
        handler.post(() -> handler.removeCallbacks(finishAfterStop));
    }

    @Override
    public void onAssistantStoppedListening() {
        handler.post(() -> {
            handler.removeCallbacks(finishAfterStop);
            handler.postDelayed(finishAfterStop, STOPPED_GRACE_MS);
        });
    }

    /** The assistant stopped listening: stop the band's capture and show a loading card until the answer comes. */
    private void awaitAnswer() {
        if (activeSession < 0) {
            return;
        }
        hub.endSession();
        awaitingAnswer = true;
        LOG.info("Voice session {}: assistant stopped listening, waiting for its answer", activeSession);
        // the band only shows results after a final recognition result; without one it asks to say it again
        support.sendCommand("aivs recognized", buildRecognizeResultCommand(activeSession, query != null ? query : "Gemini", true));
        support.sendCommand("aivs stop capture", buildStopCaptureCommand(activeSession, null));
        support.sendCommand("aivs loading", buildLoadingCardCommand(activeSession, ANSWER_TIMEOUT_S));
        if (answer != null) {
            // already shown while the recording was still open
            handler.removeCallbacks(sendAnswer);
            handler.postDelayed(sendAnswer, ANSWER_SETTLE_MS);
        }
        handler.removeCallbacks(finishTimeout);
        handler.postDelayed(finishTimeout, ANSWER_TIMEOUT_S * 1000L);
    }

    @Override
    public void onAssistantAnswer(final String text) {
        handler.post(() -> {
            if (activeSession < 0) {
                return;
            }
            answer = text;
            if (awaitingAnswer) {
                handler.removeCallbacks(sendAnswer);
                handler.postDelayed(sendAnswer, ANSWER_SETTLE_MS);
            }
        });
    }

    @Override
    public void onAssistantPanelText(final String viewId, final String text) {
        handler.post(() -> {
            LOG.debug("Assistant panel text {}: {}", viewId, text);
            if (activeSession < 0) {
                idleTexts.add(text);
                return;
            }
            if (awaitingAnswer || !viewId.contains("query") || idleTexts.contains(text) || text.equals(query)) {
                return;
            }
            query = text;
            support.sendCommand("aivs recognizing", buildRecognizeResultCommand(activeSession, text, false));
        });
    }

    private void sendAnswer() {
        if (activeSession < 0 || answer == null) {
            return;
        }
        final String text = answer.length() > MAX_ANSWER_CHARS ? answer.substring(0, MAX_ANSWER_CHARS) : answer;
        LOG.info("Sending the assistant's answer to the band, {} chars", text.length());
        support.sendCommand("aivs answer", buildAnswerCommand(activeSession, text));
        support.sendCommand("aivs answer end", aivsCommand(AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_LLM_FINISH_STREAM, activeSession))));
        finishDialog("answer sent");
    }

    private void finishDialog(final String reason) {
        handler.removeCallbacks(finishTimeout);
        handler.removeCallbacks(finishAfterStop);
        handler.removeCallbacks(sendAnswer);
        if (activeSession >= 0) {
            LOG.info("Finishing voice session {} on the band: {}", activeSession, reason);
            support.sendCommand("aivs finish", awaitingAnswer
                    ? buildDialogFinishCommand(activeSession)
                    : buildFinishCommand(activeSession));
        }
        activeSession = -1;
        awaitingAnswer = false;
        answer = null;
        query = null;
        hub.endSession();
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
        activeSession = -1;
        awaitingAnswer = false;
        hub.endSession();
        hub.setAnswerListener(null);
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

    /** Stops the band's capture and finishes its dialog, as the official app does at the end of a dialog. */
    static XiaomiProto.Command buildFinishCommand(final int sessionId) {
        return aivsCommand(AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_STOP_CAPTURE, Math.max(sessionId, 0)))
                .addInstruction(instruction(RESULT_DIALOG_FINISH, Math.max(sessionId, 0))));
    }

    static XiaomiProto.Command buildRecognizeResultCommand(final int sessionId, final String text, final boolean isFinal) {
        return aivsCommand(AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_RECOGNIZE_RESULT, Math.max(sessionId, 0))
                        .setRecognizeResult(AivsRecognizeResult.newBuilder()
                                .setIsFinal(isFinal)
                                .setResults(AivsRecognizeResultItems.newBuilder()
                                        .addItem(AivsText.newBuilder().setText(text))))));
    }

    static XiaomiProto.Command buildLoadingCardCommand(final int sessionId, final int timeoutSeconds) {
        return aivsCommand(AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_LLM_LOADING_CARD, Math.max(sessionId, 0))
                        .setLlmLoadingCard(AivsLlmLoadingCard.newBuilder().setTimeout(timeoutSeconds))));
    }

    static XiaomiProto.Command buildAnswerCommand(final int sessionId, final String markdown) {
        return aivsCommand(AivsInstructionList.newBuilder()
                .addInstruction(instruction(RESULT_LLM_TOAST_STREAM, Math.max(sessionId, 0))
                        .setLlmToastStream(AivsLlmToastStream.newBuilder().setMarkdownText(markdown))));
    }

    private static XiaomiProto.Command aivsCommand(final AivsInstructionList.Builder list) {
        return XiaomiProto.Command.newBuilder()
                .setType(COMMAND_TYPE_AIVS)
                .setSubtype(CMD_AIVS_INSTRUCTIONS)
                .setAivs(Aivs.newBuilder().setInstructionList(list))
                .build();
    }
}
