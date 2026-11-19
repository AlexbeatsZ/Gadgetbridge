package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.media.AudioDeviceInfo;

import org.junit.Assert;
import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class XiaomiHeadsetActivatorTest extends TestBase {
    private static final String WATCH = "C4:57:81:3B:DE:6F";

    private static AudioDeviceInfo output(final int type, final String address) {
        final AudioDeviceInfo info = mock(AudioDeviceInfo.class);
        when(info.getType()).thenReturn(type);
        when(info.getAddress()).thenReturn(address);
        return info;
    }

    @Test
    public void watchScoOutputMeansActive() {
        Assert.assertEquals(XiaomiHeadsetActivator.State.ACTIVE, XiaomiHeadsetActivator.state(new AudioDeviceInfo[]{
                output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""),
                output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, WATCH.toLowerCase()),
        }, WATCH));
    }

    @Test
    public void noScoOutputMeansNoneActive() {
        // the watch connected over HFP but not active: audio routing offers no SCO output at all
        Assert.assertEquals(XiaomiHeadsetActivator.State.NONE_ACTIVE, XiaomiHeadsetActivator.state(new AudioDeviceInfo[]{
                output(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, ""),
                output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""),
                output(AudioDeviceInfo.TYPE_TELEPHONY, ""),
        }, WATCH));
    }

    @Test
    public void otherHeadsetIsLeftAlone() {
        Assert.assertEquals(XiaomiHeadsetActivator.State.OTHER_ACTIVE, XiaomiHeadsetActivator.state(new AudioDeviceInfo[]{
                output(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, WATCH),
                output(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "11:22:33:44:2A:12"),
        }, WATCH));
    }

    @Test
    public void rootScriptRunsTheToolFromTheApk() {
        Assert.assertEquals("CLASSPATH='/data/app/~~a==/nodomain.freeyourgadget.gadgetbridge-b==/base.apk' exec app_process /system/bin "
                        + "nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.BluetoothActiveDeviceTool " + WATCH + "\n",
                XiaomiHeadsetActivator.buildRootScript(
                        "/data/app/~~a==/nodomain.freeyourgadget.gadgetbridge-b==/base.apk", WATCH));
    }

    @Test
    public void rootScriptRejectsShellInput() {
        Assert.assertNull(XiaomiHeadsetActivator.buildRootScript("/data/app/x'; reboot; '/base.apk", WATCH));
        Assert.assertNull(XiaomiHeadsetActivator.buildRootScript("/data/app/base.apk", "C4:57:81:3B:DE:6F; reboot"));
        Assert.assertNull(XiaomiHeadsetActivator.buildRootScript(null, WATCH));
    }
}
