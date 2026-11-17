package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
        final long session = hub.getResponseSession();
        assertTrue(session > 0);
        hub.onResponse(session, "闹钟已设置\n07:30\n明天");
        advance(1500);
        assertEquals("闹钟已设置\n07:30\n明天", answerTexts().get(0));
        assertEquals(0, hub.getResponseSession());
    }

    @Test
    public void streamingCardSendsLatestSnapshotOnce() {
        startAndStopListening();
        hub.onResponse(hub.getResponseSession(), "闹钟");
        advance(1000);
        hub.onResponse(hub.getResponseSession(), "闹钟已设置\n08:00");
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
        hub.onResponse(hub.getResponseSession(), "闹钟");
        advance(500);
        hub.onResponse(hub.getResponseSession(), "");
        advance(30000);
        assertEquals(List.of(getContext().getString(R.string.xiaomi_voice_assistant_no_visible_response)), answerTexts());
    }

    @Test
    public void previousSessionCannotSupplyTheNextAnswer() {
        startAndStopListening();
        final long previousSession = hub.getResponseSession();
        hub.onResponse(previousSession, "第一次回答");
        advance(1500);
        startAndStopListening();
        hub.onResponse(previousSession, "过期的闹钟卡片");
        hub.onResponse(hub.getResponseSession(), "当前闹钟卡片");
        advance(1500);
        assertEquals(List.of("第一次回答", "当前闹钟卡片"), answerTexts());
    }

    @Test
    public void resultShownBeforeRecordingStopsIsStillSent() {
        bridge.onVoiceStart(0);
        hub.onResponse(hub.getResponseSession(), "07:30\n明天");
        advance(0);
        bridge.onAssistantStoppedListening();
        advance(3000);
        assertEquals(List.of("07:30\n明天"), answerTexts());
    }

    @Test
    public void sameResultCanBeSentForConsecutiveRequests() {
        startAndStopListening();
        hub.onResponse(hub.getResponseSession(), "07:30\n明天");
        advance(1500);
        startAndStopListening();
        hub.onResponse(hub.getResponseSession(), "07:30\n明天");
        advance(1500);
        assertEquals(List.of("07:30\n明天", "07:30\n明天"), answerTexts());
    }

    @Test
    public void longResponseDoesNotSplitSupplementaryCharacters() {
        startAndStopListening();
        hub.onResponse(hub.getResponseSession(), "a".repeat(999) + "😀" + "more");
        advance(1500);
        assertEquals("a".repeat(999) + "😀", answerTexts().get(0));
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
