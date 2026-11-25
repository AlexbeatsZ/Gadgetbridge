package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.services;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.robolectric.Shadows.shadowOf;

/** Wear app messages go only to the phone app sharing the band app's package, when it listens for them. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class XiaomiRpkTargetTest {
    private static final String APP_ID = "org.dayweave.mobile";

    private final PackageManager packageManager = RuntimeEnvironment.getApplication().getPackageManager();

    @Test
    public void listeningPhoneAppGetsTheOnlyCopy() {
        final ComponentName receiver = new ComponentName(APP_ID, APP_ID + ".WatchBridge");
        shadowOf(packageManager).addReceiverIfNotPresent(receiver);
        shadowOf(packageManager).addIntentFilterForReceiver(receiver,
                new IntentFilter(XiaomiRpkService.ACTION_WATCH_APP_MESSAGE_RECEIVED));

        final Intent sent = XiaomiRpkService.targetPhoneApp(message(), APP_ID, packageManager);

        Assert.assertEquals(APP_ID, sent.getPackage());
        Assert.assertEquals("{}", sent.getStringExtra("payload"));
    }

    @Test
    public void otherwiseBroadcastStaysOpen() {
        final Intent sent = XiaomiRpkService.targetPhoneApp(message(), "com.meta.s4probe", packageManager);

        Assert.assertNull(sent.getPackage());
    }

    private static Intent message() {
        return new Intent(XiaomiRpkService.ACTION_WATCH_APP_MESSAGE_RECEIVED).putExtra("payload", "{}");
    }
}
