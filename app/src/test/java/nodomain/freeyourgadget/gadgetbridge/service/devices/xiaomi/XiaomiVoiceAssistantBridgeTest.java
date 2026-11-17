package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstruction;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class XiaomiVoiceAssistantBridgeTest {
    @Test
    public void stopCaptureKeepsTheSession() throws Exception {
        final XiaomiProto.Command cmd = XiaomiProto.Command.parseFrom(
                XiaomiVoiceAssistantBridge.buildStopCaptureCommand(7, "listening").toByteArray());
        Assert.assertEquals(14, cmd.getType());
        Assert.assertEquals(0, cmd.getSubtype());
        Assert.assertEquals(2, cmd.getAivs().getInstructionList().getInstructionCount());
        final AivsInstruction toast = cmd.getAivs().getInstructionList().getInstruction(1);
        Assert.assertEquals(XiaomiVoiceAssistantBridge.RESULT_TEMPLATE_TOAST, toast.getResultType());
        Assert.assertEquals("listening", toast.getTemplateToast().getText());
        final AivsInstruction stop = cmd.getAivs().getInstructionList().getInstruction(0);
        Assert.assertEquals(XiaomiVoiceAssistantBridge.RESULT_STOP_CAPTURE, stop.getResultType());
        Assert.assertEquals(7, stop.getSessionId());
    }

    @Test
    public void wireLayoutUsesOfficialFieldNumbers() {
        // Command.type 14, subtype 0, aivs (16) -> instruction_list (1) -> instruction (1):
        // result_type (1) = 3 STOP_CAPTURE, session_id (13) = 0 for a session without START
        Assert.assertEquals("080e10008201080a060a0408036800",
                GB.hexdump(XiaomiVoiceAssistantBridge.buildStopCaptureCommand(-1, null).toByteArray()).toLowerCase());
        // result_type = 4 DIALOG_FINISH, session 5
        Assert.assertEquals("080e10008201080a060a0408046805",
                GB.hexdump(XiaomiVoiceAssistantBridge.buildDialogFinishCommand(5).toByteArray()).toLowerCase());
    }

    @Test
    public void finishStopsCaptureAndDialog() throws Exception {
        final XiaomiProto.Command cmd = XiaomiProto.Command.parseFrom(
                XiaomiVoiceAssistantBridge.buildFinishCommand(3).toByteArray());
        Assert.assertEquals(14, cmd.getType());
        Assert.assertEquals(2, cmd.getAivs().getInstructionList().getInstructionCount());
        Assert.assertEquals(XiaomiVoiceAssistantBridge.RESULT_STOP_CAPTURE,
                cmd.getAivs().getInstructionList().getInstruction(0).getResultType());
        Assert.assertEquals(XiaomiVoiceAssistantBridge.RESULT_DIALOG_FINISH,
                cmd.getAivs().getInstructionList().getInstruction(1).getResultType());
        Assert.assertEquals(3, cmd.getAivs().getInstructionList().getInstruction(1).getSessionId());
    }

    @Test
    public void answerUsesLlmCardFields() {
        // instruction: result_type 292, session_id (13) = 2, llm_toast_stream (33) -> markdown_text (1) = "hi"
        Assert.assertEquals("080e10008201100a0e0a0c08a40268028a02040a026869",
                GB.hexdump(XiaomiVoiceAssistantBridge.buildAnswerCommand(2, "hi").toByteArray()).toLowerCase());
        // instruction: result_type 289, session_id = 2, llm_loading_card (30) -> timeout (1) = 30
        Assert.assertEquals("080e100082010e0a0c0a0a08a1026802f20102081e",
                GB.hexdump(XiaomiVoiceAssistantBridge.buildLoadingCardCommand(2, 30).toByteArray()).toLowerCase());
    }
}
