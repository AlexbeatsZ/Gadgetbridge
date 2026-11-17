package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.AivsInstruction;
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class XiaomiVoiceAssistantBridgeTest {
    @Test
    public void stopCaptureEndsTheSession() throws Exception {
        final XiaomiProto.Command cmd = XiaomiProto.Command.parseFrom(
                XiaomiVoiceAssistantBridge.buildStopCaptureCommand(7).toByteArray());
        Assert.assertEquals(14, cmd.getType());
        Assert.assertEquals(0, cmd.getSubtype());
        Assert.assertEquals(2, cmd.getAivs().getInstructionList().getInstructionCount());
        final AivsInstruction stop = cmd.getAivs().getInstructionList().getInstruction(0);
        Assert.assertEquals(XiaomiVoiceAssistantBridge.RESULT_STOP_CAPTURE, stop.getResultType());
        Assert.assertEquals(7, stop.getSessionId());
        Assert.assertEquals(XiaomiVoiceAssistantBridge.RESULT_DIALOG_FINISH,
                cmd.getAivs().getInstructionList().getInstruction(1).getResultType());
    }

    @Test
    public void wireLayoutUsesOfficialFieldNumbers() {
        // Command.type 14, subtype 0, aivs (16) -> instruction_list (1) -> instruction (1) x2:
        // result_type (1) = 3 STOP_CAPTURE / 4 DIALOG_FINISH, session_id (13) = 0 for a session without START
        Assert.assertEquals("080e100082010e0a0c0a04080368000a0408046800",
                GB.hexdump(XiaomiVoiceAssistantBridge.buildStopCaptureCommand(-1).toByteArray()).toLowerCase());
    }
}
