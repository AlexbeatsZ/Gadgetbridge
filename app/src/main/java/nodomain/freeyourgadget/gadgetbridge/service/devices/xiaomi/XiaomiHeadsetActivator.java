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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the watch the phone's active call device, so that the phone assistant accepts the watch as headset.
 * <p>
 * Android never makes a connected wrist watch the active HFP device on its own (AOSP ActiveDeviceManager skips
 * devices of class WEARABLE_WRIST_WATCH), so after every HFP reconnection the watch stays connected but inactive.
 * The assistant's {@code ACTION_VOICE_COMMAND} entry then refuses to start because "no Bluetooth headset" is
 * connected. Only privileged callers may choose the active device, so this runs {@link BluetoothActiveDeviceTool}
 * as root. Nothing is changed while another headset is the active call device.
 */
final class XiaomiHeadsetActivator {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiHeadsetActivator.class);

    private static final long ROOT_TIMEOUT_MS = 10000;
    // Audio routing adds the SCO device shortly after the Bluetooth stack switched the active device.
    private static final long ROUTE_WAIT_MS = 1500;
    private static final long ROUTE_POLL_MS = 100;

    enum State {
        /** The watch is the active call device. */
        ACTIVE,
        /** Another headset is the active call device. */
        OTHER_ACTIVE,
        /** No headset is the active call device. */
        NONE_ACTIVE,
        /** Cannot tell on this Android version. */
        UNKNOWN,
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private XiaomiHeadsetActivator() {
    }

    /**
     * Checks in the background and makes the watch the active call device if no headset is. {@code then} runs on
     * the main thread afterwards, whatever the result.
     */
    static void ensureActive(final Context context, final String address, final String reason,
                             @Nullable final Runnable then) {
        final Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        EXECUTOR.execute(() -> {
            try {
                ensureActiveBlocking(appContext, address, reason);
            } catch (final RuntimeException e) {
                LOG.warn("{}: could not check the active call device", reason, e);
            } finally {
                if (then != null) {
                    MAIN.post(then);
                }
            }
        });
    }

    private static void ensureActiveBlocking(final Context context, final String address, final String reason) {
        final AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return;
        }
        final State state = state(audio, address);
        if (state != State.NONE_ACTIVE) {
            LOG.debug("{}: watch call device state {}", reason, state);
            return;
        }
        if (audio.getMode() != AudioManager.MODE_NORMAL) {
            // in a call the user may have moved the audio away from the watch on purpose
            LOG.debug("{}: audio mode {}, leaving the call device alone", reason, audio.getMode());
            return;
        }
        if (!isHeadsetConnected(context)) {
            LOG.debug("{}: watch is not connected as headset", reason);
            return;
        }
        LOG.info("{}: watch is connected as headset but no call device is active, activating it as root", reason);
        if (!runAsRoot(context, address)) {
            return;
        }
        final long until = SystemClock.elapsedRealtime() + ROUTE_WAIT_MS;
        while (state(audio, address) != State.ACTIVE && SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(ROUTE_POLL_MS);
        }
        LOG.info("{}: watch call device state after activation {}", reason, state(audio, address));
    }

    /** Only the active HFP device is offered to audio routing as SCO output, so no special permission is needed. */
    static State state(final AudioManager audio, final String address) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return State.UNKNOWN;
        }
        return state(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS), address);
    }

    @VisibleForTesting
    static State state(final AudioDeviceInfo[] outputs, final String address) {
        boolean other = false;
        for (final AudioDeviceInfo output : outputs) {
            if (output.getType() != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                continue;
            }
            if (address.equalsIgnoreCase(output.getAddress())) {
                return State.ACTIVE;
            }
            other = true;
        }
        return other ? State.OTHER_ACTIVE : State.NONE_ACTIVE;
    }

    @SuppressWarnings("MissingPermission")
    private static boolean isHeadsetConnected(final Context context) {
        final BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        final BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        try {
            return adapter != null
                    && adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED;
        } catch (final SecurityException e) {
            LOG.warn("No permission to read the headset connection state", e);
            return false;
        }
    }

    @VisibleForTesting
    @Nullable
    static String buildRootScript(final String apkPath, final String address) {
        // single quotes keep the path literal; the address is validated and has no shell characters
        if (apkPath == null || apkPath.isEmpty() || apkPath.contains("'") || apkPath.contains("\n")
                || !BluetoothAdapter.checkBluetoothAddress(address)) {
            return null;
        }
        return "CLASSPATH='" + apkPath + "' exec app_process /system/bin "
                + BluetoothActiveDeviceTool.class.getName() + " " + address + "\n";
    }

    private static boolean runAsRoot(final Context context, final String address) {
        final String script = buildRootScript(context.getApplicationInfo().sourceDir, address);
        if (script == null) {
            LOG.warn("Unexpected APK path or address, not activating the watch");
            return false;
        }
        final Process process;
        try {
            process = new ProcessBuilder("su").redirectErrorStream(true).start();
        } catch (final IOException e) {
            LOG.warn("Root is not available, cannot make the watch the active call device", e);
            return false;
        }
        try {
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(script.getBytes(StandardCharsets.UTF_8));
            }
            // the helper prints a line or a stack trace, far less than the pipe buffer, so read after it exited
            final int status;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!process.waitFor(ROOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    LOG.warn("Making the watch the active call device timed out");
                    return false;
                }
                status = process.exitValue();
            } else {
                status = process.waitFor();
            }
            final StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && output.length() < 2000) {
                    output.append(line).append('\n');
                }
            }
            if (status == BluetoothActiveDeviceTool.EXIT_OK) {
                LOG.info("Made the watch the active call device");
                return true;
            }
            LOG.warn("Could not make the watch the active call device, status {}: {}", status, output.toString().trim());
            return false;
        } catch (final IOException e) {
            LOG.warn("Could not run the root helper", e);
            return false;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            process.destroy();
        }
    }
}
