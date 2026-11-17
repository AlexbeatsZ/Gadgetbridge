package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic audio in the layout the official app decodes (docs/design/xiaomi-s4-official-audio.md):
 * version 0, then type / big endian length / data; audio is 16 kHz mono Opus in fixed 80-byte, 20 ms frames.
 */
public class XiaomiVoiceHandlerTest {
    private static final int FRAMES_PER_PACKET = 5;

    @Test
    public void decodesSessionOfFixedSizeOpusFrames() throws Exception {
        final RecordingListener listener = new RecordingListener();
        final XiaomiVoiceHandler handler = new XiaomiVoiceHandler();
        handler.setListener(listener);

        handler.handle(new byte[]{0, XiaomiVoiceHandler.CMD_START, 0, 1, 7});
        final List<byte[]> frames = encodeSine(10);
        for (int i = 0; i < frames.size(); i += FRAMES_PER_PACKET) {
            final byte[] packet = audioPacket(frames.subList(i, i + FRAMES_PER_PACKET));
            Assert.assertEquals(404, packet.length);
            handler.handle(packet);
        }
        Assert.assertEquals(10, handler.getFramesDecoded());
        Assert.assertEquals(0, handler.getFramesFailed());
        handler.handle(new byte[]{0, XiaomiVoiceHandler.CMD_AUDIO_END, 0, 0});

        Assert.assertEquals(List.of("start 7", "end 7 false"), listener.events);
        Assert.assertEquals(10 * XiaomiVoiceHandler.SAMPLES_PER_FRAME, listener.samples);
        // a 440 Hz tone at about -12 dBFS, after the codec settles
        Assert.assertTrue("level " + listener.lastDbfs, listener.lastDbfs > -20 && listener.lastDbfs < -6);
        Assert.assertEquals(-1, handler.getSessionId());
    }

    @Test
    public void audioWithoutStartIsStillDecoded() throws Exception {
        final XiaomiVoiceHandler handler = new XiaomiVoiceHandler();
        handler.handle(audioPacket(encodeSine(FRAMES_PER_PACKET)));
        Assert.assertEquals(FRAMES_PER_PACKET, handler.getFramesDecoded());
    }

    @Test
    public void quitWithoutLengthAtEndOfBuffer() {
        final RecordingListener listener = new RecordingListener();
        final XiaomiVoiceHandler handler = new XiaomiVoiceHandler();
        handler.setListener(listener);
        handler.handle(new byte[]{0, XiaomiVoiceHandler.CMD_START, 0, 1, 3});
        handler.handle(new byte[]{0, XiaomiVoiceHandler.CMD_QUIT});
        Assert.assertEquals(List.of("start 3", "end 3 true"), listener.events);
    }

    @Test
    public void truncatedCommandIsIgnored() {
        final XiaomiVoiceHandler handler = new XiaomiVoiceHandler();
        handler.handle(new byte[]{0, XiaomiVoiceHandler.CMD_AUDIO_DATA, 0x01, (byte) 0x90, 1, 2, 3});
        Assert.assertEquals(0, handler.getFramesDecoded());
        Assert.assertEquals(0, handler.getFramesFailed());
    }

    private static List<byte[]> encodeSine(final int frameCount) throws OpusException {
        final OpusEncoder encoder = new OpusEncoder(XiaomiVoiceHandler.SAMPLE_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        encoder.setBitrate(32000);
        encoder.setUseVBR(false);
        final List<byte[]> frames = new ArrayList<>();
        final short[] pcm = new short[XiaomiVoiceHandler.SAMPLES_PER_FRAME];
        final byte[] out = new byte[400];
        int t = 0;
        for (int f = 0; f < frameCount; f++) {
            for (int i = 0; i < pcm.length; i++, t++) {
                pcm[i] = (short) (8000 * Math.sin(2 * Math.PI * 440 * t / XiaomiVoiceHandler.SAMPLE_RATE));
            }
            final int len = encoder.encode(pcm, 0, pcm.length, out, 0, out.length);
            Assert.assertEquals(XiaomiVoiceHandler.OPUS_FRAME_BYTES, len);
            final byte[] frame = new byte[len];
            System.arraycopy(out, 0, frame, 0, len);
            frames.add(frame);
        }
        return frames;
    }

    private static byte[] audioPacket(final List<byte[]> frames) {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (final byte[] frame : frames) {
            data.write(frame, 0, frame.length);
        }
        final byte[] audio = data.toByteArray();
        final byte[] packet = new byte[4 + audio.length];
        packet[0] = 0;
        packet[1] = XiaomiVoiceHandler.CMD_AUDIO_DATA;
        packet[2] = (byte) (audio.length >> 8);
        packet[3] = (byte) audio.length;
        System.arraycopy(audio, 0, packet, 4, audio.length);
        return packet;
    }

    private static class RecordingListener implements XiaomiVoiceHandler.Listener {
        final List<String> events = new ArrayList<>();
        int samples;
        double lastDbfs;

        @Override
        public void onVoiceStart(final int sessionId) {
            events.add("start " + sessionId);
        }

        @Override
        public void onVoicePcm(final int sessionId, final short[] pcm, final int count) {
            samples += count;
            lastDbfs = XiaomiVoiceHandler.rmsDbfs(pcm, count);
        }

        @Override
        public void onVoiceEnd(final int sessionId, final boolean quit) {
            events.add("end " + sessionId + " " + quit);
        }
    }
}
