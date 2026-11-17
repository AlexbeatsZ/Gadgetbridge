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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import androidx.annotation.Nullable;

import org.concentus.OpusDecoder;
import org.concentus.OpusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiChannelHandler;

/**
 * Voice assistant audio from the band, received on the SPP V2 voice channel (raw channel 3) after decryption.
 * <p>
 * Each delivered buffer starts with a version byte (0), followed by one or more commands:
 * type (1 byte), big endian length (2 bytes), data. Audio data is Opus, 16 kHz mono, in fixed 80-byte frames of
 * 20 ms. See docs/design/xiaomi-s4-official-audio.md.
 * <p>
 * Decoded PCM is only handed to the listener; nothing is stored here.
 */
public class XiaomiVoiceHandler implements XiaomiChannelHandler {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiVoiceHandler.class);

    public static final int CMD_START = 1;
    public static final int CMD_AUDIO_DATA = 2;
    public static final int CMD_AUDIO_END = 3;
    public static final int CMD_QUIT = 4;

    public static final int SAMPLE_RATE = 16000;
    public static final int OPUS_FRAME_BYTES = 80;
    public static final int SAMPLES_PER_FRAME = 320;

    public interface Listener {
        void onVoiceStart(int sessionId);

        /** 16-bit mono PCM at {@link #SAMPLE_RATE}, one Opus frame. The array is reused after the call. */
        void onVoicePcm(int sessionId, short[] pcm, int samples);

        void onVoiceEnd(int sessionId, boolean quit);
    }

    @Nullable
    private Listener listener;

    private OpusDecoder decoder;
    private int sessionId = -1;
    private final short[] pcm = new short[SAMPLES_PER_FRAME];

    // per session statistics, for logging only
    private int framesDecoded;
    private int framesFailed;
    private double peakDbfs = -120;

    public void setListener(@Nullable final Listener listener) {
        this.listener = listener;
    }

    @Override
    public void handle(final byte[] payload) {
        if (payload.length < 2) {
            LOG.warn("Voice buffer too short: {} bytes", payload.length);
            return;
        }
        final ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        final int version = buf.get() & 0xff;
        if (version != 0) {
            LOG.warn("Unknown voice buffer version {}", version);
            return;
        }
        while (buf.hasRemaining()) {
            final int type = buf.get() & 0xff;
            byte[] data = new byte[0];
            if (buf.remaining() >= 2) {
                final int length = buf.getShort() & 0xffff;
                if (length > buf.remaining()) {
                    LOG.warn("Voice command {} declares {} bytes, only {} left", type, length, buf.remaining());
                    return;
                }
                data = new byte[length];
                buf.get(data);
            } else if (buf.hasRemaining()) {
                LOG.warn("Voice command {} truncated", type);
                return;
            }
            handleCommand(type, data);
        }
    }

    private void handleCommand(final int type, final byte[] data) {
        switch (type) {
            case CMD_START:
                startSession(data.length > 0 ? data[0] & 0xff : -1);
                return;
            case CMD_AUDIO_DATA:
                onAudio(data);
                return;
            case CMD_AUDIO_END:
                endSession(false);
                return;
            case CMD_QUIT:
                endSession(true);
                return;
            default:
                LOG.warn("Unknown voice command {} with {} bytes", type, data.length);
        }
    }

    private void startSession(final int newSessionId) {
        if (decoder != null) {
            LOG.debug("Voice session {} replaced by {}", sessionId, newSessionId);
            logSummary();
        }
        sessionId = newSessionId;
        framesDecoded = 0;
        framesFailed = 0;
        peakDbfs = -120;
        try {
            decoder = new OpusDecoder(SAMPLE_RATE, 1);
        } catch (final OpusException e) {
            LOG.error("Failed to create opus decoder", e);
            decoder = null;
            return;
        }
        LOG.info("Voice session {} started", sessionId);
        if (listener != null) {
            listener.onVoiceStart(sessionId);
        }
    }

    private void onAudio(final byte[] data) {
        if (decoder == null) {
            // audio without a START we saw, e.g. after a reconnect
            startSession(-1);
            if (decoder == null) {
                return;
            }
        }
        if (data.length % OPUS_FRAME_BYTES != 0) {
            LOG.warn("Voice data of {} bytes is not a multiple of {}", data.length, OPUS_FRAME_BYTES);
        }
        int ok = 0;
        double packetPeak = -120;
        for (int offset = 0; offset + OPUS_FRAME_BYTES <= data.length; offset += OPUS_FRAME_BYTES) {
            try {
                final int samples = decoder.decode(data, offset, OPUS_FRAME_BYTES, pcm, 0, SAMPLES_PER_FRAME, false);
                if (samples != SAMPLES_PER_FRAME) {
                    LOG.warn("Opus frame decoded to {} samples, expected {}", samples, SAMPLES_PER_FRAME);
                }
                framesDecoded++;
                ok++;
                packetPeak = Math.max(packetPeak, rmsDbfs(pcm, samples));
                if (listener != null) {
                    listener.onVoicePcm(sessionId, pcm, samples);
                }
            } catch (final OpusException | RuntimeException e) {
                framesFailed++;
                LOG.warn("Opus frame at {} failed to decode: {}", offset, e.getMessage());
            }
        }
        peakDbfs = Math.max(peakDbfs, packetPeak);
        LOG.debug("Voice session {}: {}/{} frames decoded, level {} dBFS",
                sessionId, ok, data.length / OPUS_FRAME_BYTES, Math.round(packetPeak));
    }

    private void endSession(final boolean quit) {
        LOG.info("Voice session {} {}", sessionId, quit ? "quit" : "ended");
        logSummary();
        if (listener != null) {
            listener.onVoiceEnd(sessionId, quit);
        }
        decoder = null;
        sessionId = -1;
    }

    private void logSummary() {
        LOG.info("Voice session {}: {} frames ({} ms) decoded, {} failed, peak {} dBFS",
                sessionId, framesDecoded, framesDecoded * 20, framesFailed, Math.round(peakDbfs));
    }

    /** Disconnects end any session without notifying the band. */
    public void reset() {
        if (decoder != null) {
            logSummary();
        }
        decoder = null;
        sessionId = -1;
    }

    static double rmsDbfs(final short[] samples, final int count) {
        if (count <= 0) {
            return -120;
        }
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += (double) samples[i] * samples[i];
        }
        final double rms = Math.sqrt(sum / count);
        return rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
    }

    public int getFramesDecoded() {
        return framesDecoded;
    }

    public int getFramesFailed() {
        return framesFailed;
    }

    public int getSessionId() {
        return sessionId;
    }
}
