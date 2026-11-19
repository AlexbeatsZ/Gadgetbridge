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
import android.bluetooth.BluetoothDevice;
import android.os.Looper;

import java.lang.reflect.Method;

/**
 * Runs as root through {@code app_process} with this APK as class path, see {@link XiaomiHeadsetActivator}.
 * Makes a connected Bluetooth headset the active phone call device, which only privileged callers may do.
 * <p>
 * Keep this class free of Gadgetbridge state, loggers and other static initialization: it runs outside the
 * app, without an application context.
 */
public final class BluetoothActiveDeviceTool {
    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;

    // BluetoothAdapter.ACTIVE_DEVICE_PHONE_CALL, a system API
    private static final int ACTIVE_DEVICE_PHONE_CALL = 1;

    private BluetoothActiveDeviceTool() {
    }

    @SuppressWarnings({"deprecation", "MissingPermission"})
    public static void main(final String[] args) {
        int status = EXIT_FAILED;
        try {
            if (args.length != 1 || !BluetoothAdapter.checkBluetoothAddress(args[0])) {
                status = EXIT_USAGE;
            } else {
                initBluetoothFramework();
                final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) {
                    System.out.println("no Bluetooth adapter");
                } else {
                    final Object result = BluetoothAdapter.class
                            .getMethod("setActiveDevice", BluetoothDevice.class, int.class)
                            .invoke(adapter, adapter.getRemoteDevice(args[0]), ACTIVE_DEVICE_PHONE_CALL);
                    System.out.println("setActiveDevice=" + result);
                    if (Boolean.TRUE.equals(result)) {
                        status = EXIT_OK;
                    }
                }
            }
        } catch (final Throwable t) {
            t.printStackTrace(System.out);
        }
        // the Bluetooth binder keeps the process alive otherwise
        System.exit(status);
    }

    /**
     * Without an ActivityThread nothing sets up the Bluetooth framework: since Android 13 getDefaultAdapter()
     * returns null until the service manager is set, and the adapter needs a main looper for its handler.
     */
    private static void initBluetoothFramework() {
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        try {
            for (final Method method : Class.forName("android.bluetooth.BluetoothFrameworkInitializer").getDeclaredMethods()) {
                if (method.getName().equals("setBluetoothServiceManager") && method.getParameterTypes().length == 1) {
                    method.setAccessible(true);
                    method.invoke(null, method.getParameterTypes()[0].getConstructor().newInstance());
                    return;
                }
            }
        } catch (final ClassNotFoundException e) {
            // before Android 13 the adapter finds its service by itself
        } catch (final ReflectiveOperationException | RuntimeException e) {
            System.out.println("could not set the Bluetooth service manager: " + e);
        }
    }
}
