package dev.deviceowner.kiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.ComponentName;
import android.content.Context;

/**
 * Device admin component. Its only job is to exist, so that
 * `adb shell dpm set-device-owner dev.deviceowner.kiosk/.AdminReceiver` has
 * something to bind to. Policy is applied by KioskPolicy, not here.
 */
public class AdminReceiver extends DeviceAdminReceiver {

    public static ComponentName componentName(Context context) {
        return new ComponentName(context.getApplicationContext(), AdminReceiver.class);
    }
}
