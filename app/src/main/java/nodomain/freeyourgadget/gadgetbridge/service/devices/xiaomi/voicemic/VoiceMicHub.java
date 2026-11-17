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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.voicemic;

import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Holds the band's voice for the assistant while a session is handed over.
 * <p>
 * Audio is kept from the start of the session, because the band streams before the assistant opens its
 * microphone. Each recording the assistant opens gets everything so far followed by live audio; the assistant
 * may open a short probe recording before the real one. Nothing outlives the session.
 */
public final class VoiceMicHub {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceMicHub.class);
    private static final VoiceMicHub INSTANCE = new VoiceMicHub();

    // 16 kHz mono PCM16 is 32000 bytes per second
    private static final int MAX_SESSION_BYTES = 32000 * 30;

    public interface Listener {
        /** The assistant opened a recording that is fed. */
        void onAssistantStartedListening();

        /** The assistant stopped a fed recording; it may open another one right after. */
        void onAssistantStoppedListening();
    }

    public interface AnswerListener {
        /** The assistant shows (or updates while streaming) an answer text. */
        void onAssistantAnswer(String text);
    }

    private final ByteArrayOutputStream sessionAudio = new ByteArrayOutputStream();
    private boolean active;
    @Nullable
    private Writer writer;
    @Nullable
    private Listener listener;
    @Nullable
    private AnswerListener answerListener;

    public static VoiceMicHub getInstance() {
        return INSTANCE;
    }

    public synchronized void startSession(@Nullable final Listener listener) {
        endSession();
        this.listener = listener;
        active = true;
        LOG.info("Voice mic session started");
    }

    public synchronized boolean isActive() {
        return active;
    }

    /** 16-bit mono PCM at 16 kHz from the band. */
    public synchronized void onPcm(final short[] pcm, final int samples) {
        if (!active) {
            return;
        }
        final byte[] bytes = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            bytes[2 * i] = (byte) pcm[i];
            bytes[2 * i + 1] = (byte) (pcm[i] >> 8);
        }
        if (sessionAudio.size() + bytes.length <= MAX_SESSION_BYTES) {
            sessionAudio.write(bytes, 0, bytes.length);
        }
        if (writer != null) {
            writer.offer(bytes);
        }
    }

    /** A pipe carrying the session audio so far and then live audio, or null when no session is active. */
    @Nullable
    public synchronized ParcelFileDescriptor openStream() throws IOException {
        if (!active) {
            return null;
        }
        final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        if (writer != null) {
            writer.close();
        }
        writer = new Writer(new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]));
        writer.offer(sessionAudio.toByteArray());
        LOG.info("Assistant opened the voice mic, {} bytes of session audio queued", sessionAudio.size());
        if (listener != null) {
            listener.onAssistantStartedListening();
        }
        return pipe[0];
    }

    /** Answers are reported whenever the assistant shows one; the listener decides whether one is expected. */
    public synchronized void setAnswerListener(@Nullable final AnswerListener answerListener) {
        this.answerListener = answerListener;
    }

    public synchronized void onAnswer(final String text) {
        if (answerListener != null) {
            answerListener.onAssistantAnswer(text);
        }
    }

    public synchronized void onRecordingStopped() {
        if (active && listener != null) {
            listener.onAssistantStoppedListening();
        }
    }

    public synchronized void endSession() {
        if (active) {
            LOG.info("Voice mic session ended after {} bytes", sessionAudio.size());
        }
        active = false;
        listener = null;
        sessionAudio.reset();
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    /** Writes to the pipe off the caller's thread, the pipe buffer is smaller than the session audio. */
    private static final class Writer implements Runnable {
        private static final byte[] EOF = new byte[0];
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final OutputStream out;

        Writer(final OutputStream out) {
            this.out = out;
            final Thread thread = new Thread(this, "voice-mic-writer");
            thread.setDaemon(true);
            thread.start();
        }

        void offer(final byte[] data) {
            if (data.length > 0) {
                queue.offer(data);
            }
        }

        void close() {
            queue.offer(EOF);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    final byte[] data = queue.take();
                    if (data == EOF) {
                        break;
                    }
                    out.write(data);
                }
            } catch (final IOException e) {
                LOG.debug("Voice mic consumer went away: {}", e.getMessage());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    out.close();
                } catch (final IOException ignored) {
                    // closing anyway
                }
            }
        }
    }
}
