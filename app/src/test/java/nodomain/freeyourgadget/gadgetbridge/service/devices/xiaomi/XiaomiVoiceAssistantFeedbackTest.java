package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.AssistantPanelSession;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.VoiceMicContract;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic.VoiceMicHub;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class XiaomiVoiceAssistantFeedbackTest extends TestBase {
    private XiaomiSupport support;
    private XiaomiVoiceAssistantBridge bridge;
    private final VoiceMicHub hub = VoiceMicHub.getInstance();

    @Before
    public void setUpBridge() {
        final GBDevice device = new GBDevice("C4:57:81:00:00:02", "Xiaomi Watch S4 0002",
                null, null, DeviceType.XIAOMI_WATCH_S4);
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putBoolean(XiaomiVoiceAssistantBridge.PREF_VOICE_ASSISTANT_HANDOFF, true)
                .remove(XiaomiVoiceAssistantBridge.PREF_VOICE_ASSISTANT_PLAYBACK).commit();
        support = mock(XiaomiSupport.class);
        when(support.getDevice()).thenReturn(device);
        when(support.getContext()).thenReturn(getContext());
        bridge = new XiaomiVoiceAssistantBridge(support);
    }

    @After
    public void disposeBridge() {
        bridge.dispose();
    }

    @Test
    public void cardOnlyResultIsSentAfterAudioStops() {
        startAndStopListening();
        hub.onAnswer("闹钟已设置\n07:30\n明天");
        advance(1500);
        assertEquals("闹钟已设置\n07:30\n明天", answerTexts().get(0));
    }

    @Test
    public void streamingCardSendsLatestSnapshotOnce() {
        startAndStopListening();
        hub.onAnswer("闹钟");
        advance(1000);
        hub.onAnswer("闹钟已设置\n08:00");
        advance(1500);
        assertEquals(List.of("闹钟已设置\n08:00"), answerTexts());
    }

    @Test
    public void emptyResponseGetsExplicitFeedbackAtTimeout() {
        startAndStopListening();
        advance(30000);
        assertEquals(List.of(getContext().getString(R.string.xiaomi_voice_assistant_no_visible_response)), answerTexts());
    }

    @Test
    public void clearedCardDoesNotSendItsStalePartialContent() {
        startAndStopListening();
        hub.onAnswer("闹钟");
        advance(500);
        hub.onAnswer("");
        advance(30000);
        assertEquals(List.of(getContext().getString(R.string.xiaomi_voice_assistant_no_visible_response)), answerTexts());
    }

    @Test
    public void resultShownBeforeRecordingStopsIsStillSent() {
        bridge.onVoiceStart(0);
        hub.onAnswer("07:30\n明天");
        advance(0);
        bridge.onAssistantStoppedListening();
        advance(3000);
        assertEquals(List.of("07:30\n明天"), answerTexts());
    }

    @Test
    public void sameResultCanBeSentForConsecutiveRequests() {
        startAndStopListening();
        hub.onAnswer("07:30\n明天");
        advance(1500);
        startAndStopListening();
        hub.onAnswer("07:30\n明天");
        advance(1500);
        assertEquals(List.of("07:30\n明天", "07:30\n明天"), answerTexts());
    }

    @Test
    public void longResponseDoesNotSplitSupplementaryCharacters() {
        startAndStopListening();
        hub.onAnswer("a".repeat(999) + "😀" + "more");
        advance(1500);
        assertEquals("a".repeat(999) + "😀", answerTexts().get(0));
    }

    @Test
    public void quitStopsWatchAudioAndAllowsAnotherRequestImmediately() {
        bridge.onVoiceStart(0);
        bridge.onVoiceEnd(0, true);
        advance(0);
        assertFalse(hub.isActive());
        assertEquals(1, cancellationBroadcastCount());
        verify(support, never()).sendCommand(anyString(), any());
        bridge.onVoiceStart(0);
        assertTrue(hub.isActive());
    }

    @Test
    public void quitAfterAudioEndDiscardsPendingAnswer() {
        startAndStopListening();
        bridge.onVoiceEnd(0, false);
        hub.onAnswer("闹钟已设置");
        advance(0);
        assertEquals(0, cancellationBroadcastCount());
        // The decoder has already released the session after AUDIO_END.
        bridge.onVoiceEnd(-1, true);
        advance(30000);
        assertEquals(1, cancellationBroadcastCount());
        assertTrue(answerTexts().isEmpty());
    }

    @Test
    public void normalAudioEndDoesNotCancelTheAnswer() {
        bridge.onVoiceStart(0);
        bridge.onVoiceEnd(0, false);
        advance(0);
        assertEquals(0, cancellationBroadcastCount());
        assertTrue(hub.isActive());
        bridge.onAssistantStoppedListening();
        hub.onAnswer("正常回答");
        advance(3000);
        assertEquals(List.of("正常回答"), answerTexts());
    }

    @Test
    @Config(sdk = 35)
    public void scoOpensOnlyAfterCaptureAndClosesAfterSpeech() {
        final ReplyHeadset headset = new ReplyHeadset();
        bridge.onVoiceStart(0);
        advance(3000);
        verify(headset.adapter, never()).getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET));
        bridge.onAssistantStoppedListening();
        advance(1500);
        assertFalse(hub.isActive());
        verify(headset.adapter, never()).getProfileProxy(any(), any(), eq(BluetoothProfile.HEADSET));
        advance(450);
        headset.deliverProxy();
        verify(headset.proxy).startVoiceRecognition(headset.device);

        hub.onPlaybackChanged("reply", true);
        advance(12000);
        verify(headset.proxy, never()).stopVoiceRecognition(headset.device);
        hub.onPlaybackChanged("reply", false);
        advance(800);
        verify(headset.proxy).stopVoiceRecognition(headset.device);
        verify(headset.adapter).closeProfileProxy(BluetoothProfile.HEADSET, headset.proxy);
    }

    @Test
    @Config(sdk = 35)
    public void heldSpeechOpensTheRouteWithoutTheListeningGrace() {
        final ReplyHeadset headset = new ReplyHeadset();
        bridge.onVoiceStart(0);
        bridge.onAssistantStoppedListening();
        hub.onSpeechHeld();
        advance(0);
        assertFalse(hub.isActive());
        advance(450);
        headset.deliverProxy();
        verify(headset.proxy).startVoiceRecognition(headset.device);
    }

    @Test
    @Config(sdk = 35)
    public void watchRequestHoldsSpeechUntilScoIsConnected() {
        final ReplyHeadset headset = new ReplyHeadset();
        when(headset.proxy.isAudioConnected(headset.device)).thenReturn(true);
        bridge.onVoiceStart(0);
        assertEquals(List.of(XiaomiAssistantPlayback.HOLD_WINDOW_MS), holdBroadcasts(headset.context));
        hub.onSpeechHeld();
        advance(450);
        headset.deliverProxy();
        assertEquals(List.of(XiaomiAssistantPlayback.HOLD_WINDOW_MS), holdBroadcasts(headset.context));
        advance(200);
        assertEquals(List.of(XiaomiAssistantPlayback.HOLD_WINDOW_MS, 0L), holdBroadcasts(headset.context));
    }

    @Test
    public void phoneSpeechIsNotHeldWithoutWatchPlayback() {
        bridge.onVoiceStart(0);
        hub.onSpeechHeld();
        advance(3000);
        final long holds = shadowOf((android.app.Application) getContext().getApplicationContext()).getBroadcastIntents()
                .stream().filter(intent -> VoiceMicContract.ACTION_PLAYBACK_HOLD.equals(intent.getAction())).count();
        assertEquals(0, holds);
    }

    @Test
    @Config(sdk = 35)
    public void speechThatStartedBeforeTheRouteKeepsItOpen() {
        final ReplyHeadset headset = new ReplyHeadset();
        bridge.onVoiceStart(0);
        // speaking ends listening right away and the route is armed while the track already plays
        hub.onPlaybackChanged("early", true);
        advance(450);
        headset.deliverProxy();
        advance(12000);
        verify(headset.proxy, never()).stopVoiceRecognition(headset.device);
        hub.onPlaybackChanged("early", false);
        advance(800);
        verify(headset.proxy).stopVoiceRecognition(headset.device);
    }

    @Test
    @Config(sdk = 35)
    public void cancellationDiscardsAHeadsetProxyThatArrivesLate() {
        final ReplyHeadset headset = new ReplyHeadset();
        startAndStopListening();
        advance(450);
        bridge.onVoiceEnd(0, true);
        advance(0);
        headset.deliverProxy();
        verify(headset.proxy, never()).startVoiceRecognition(headset.device);
        verify(headset.adapter).closeProfileProxy(BluetoothProfile.HEADSET, headset.proxy);
    }

    @Test
    public void quitAfterAnswerStillClosesTheOpenPhonePanel() {
        final AssistantPanelSession panel = AssistantPanelSession.getInstance();
        bridge.onVoiceStart(0);
        final long token = panel.attach();
        bridge.onAssistantStoppedListening();
        hub.onAnswer("回答");
        advance(3000);
        assertEquals(List.of("回答"), answerTexts());
        advance(30000);
        bridge.onVoiceEnd(0, true);
        advance(0);
        assertEquals(1, cancellationBroadcastCount());
        assertTrue(panel.isCancelled(token));
    }

    @Test
    public void quitWhilePhoneIsStartingCanBeReadWhenThePanelOpens() {
        final AssistantPanelSession panel = AssistantPanelSession.getInstance();
        bridge.onVoiceStart(0);
        bridge.onVoiceEnd(0, true);
        advance(2000);
        final long token = panel.attach();
        assertTrue(panel.isCancelled(token));
        panel.detach(token);
        assertEquals(0, panel.attach());

        bridge.onVoiceStart(0);
        assertFalse(panel.isCancelled(token));
        final long nextToken = panel.attach();
        assertTrue(nextToken != token && nextToken != 0);
    }

    private final class ReplyHeadset {
        final Context context = mock(Context.class);
        final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
        final BluetoothDevice device = mock(BluetoothDevice.class);
        final BluetoothHeadset proxy = mock(BluetoothHeadset.class);

        ReplyHeadset() {
            final BluetoothManager manager = mock(BluetoothManager.class);
            final AudioManager audio = mock(AudioManager.class);
            when(support.getContext()).thenReturn(context);
            when(context.getSystemService(BluetoothManager.class)).thenReturn(manager);
            when(context.getSystemService(AudioManager.class)).thenReturn(audio);
            when(manager.getAdapter()).thenReturn(adapter);
            when(adapter.getRemoteDevice(support.getDevice().getAddress())).thenReturn(device);
            when(adapter.getProfileProxy(eq(context), any(), eq(BluetoothProfile.HEADSET))).thenReturn(true);
            when(proxy.getConnectionState(device)).thenReturn(BluetoothProfile.STATE_CONNECTED);
            when(proxy.startVoiceRecognition(device)).thenReturn(true);
            GBApplication.getDeviceSpecificSharedPrefs(support.getDevice().getAddress()).edit()
                    .putBoolean(XiaomiVoiceAssistantBridge.PREF_VOICE_ASSISTANT_PLAYBACK, true).apply();
        }

        void deliverProxy() {
            final ArgumentCaptor<BluetoothProfile.ServiceListener> listener =
                    ArgumentCaptor.forClass(BluetoothProfile.ServiceListener.class);
            verify(adapter).getProfileProxy(eq(context), listener.capture(), eq(BluetoothProfile.HEADSET));
            listener.getValue().onServiceConnected(BluetoothProfile.HEADSET, proxy);
        }
    }

    private static List<Long> holdBroadcasts(final Context context) {
        final ArgumentCaptor<android.content.Intent> intents = ArgumentCaptor.forClass(android.content.Intent.class);
        verify(context, org.mockito.Mockito.atLeast(0)).sendBroadcast(intents.capture(), anyString());
        return intents.getAllValues().stream()
                .filter(intent -> VoiceMicContract.ACTION_PLAYBACK_HOLD.equals(intent.getAction()))
                .map(intent -> intent.getLongExtra(VoiceMicContract.KEY_HOLD_MS, -1))
                .collect(Collectors.toList());
    }

    private long cancellationBroadcastCount() {
        return shadowOf((android.app.Application) getContext().getApplicationContext()).getBroadcastIntents().stream()
                .filter(intent -> VoiceMicContract.ACTION_CANCEL.equals(intent.getAction())
                        && VoiceMicContract.ASSISTANT_PACKAGE.equals(intent.getPackage()))
                .count();
    }

    private void startAndStopListening() {
        bridge.onVoiceStart(0);
        bridge.onAssistantStoppedListening();
        advance(1500);
    }

    private void advance(final long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private List<String> answerTexts() {
        final ArgumentCaptor<XiaomiProto.Command> commands = ArgumentCaptor.forClass(XiaomiProto.Command.class);
        verify(support, org.mockito.Mockito.atLeastOnce()).sendCommand(anyString(), commands.capture());
        return commands.getAllValues().stream()
                .flatMap(command -> command.getAivs().getInstructionList().getInstructionList().stream())
                .filter(instruction -> instruction.getResultType() == XiaomiVoiceAssistantBridge.RESULT_LLM_TOAST_STREAM)
                .map(instruction -> instruction.getLlmToastStream().getMarkdownText())
                .collect(Collectors.toList());
    }
}
