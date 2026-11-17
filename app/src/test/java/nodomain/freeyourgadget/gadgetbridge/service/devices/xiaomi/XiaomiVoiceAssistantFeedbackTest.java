package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
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
                .putBoolean(XiaomiVoiceAssistantBridge.PREF_VOICE_ASSISTANT_HANDOFF, true).commit();
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
