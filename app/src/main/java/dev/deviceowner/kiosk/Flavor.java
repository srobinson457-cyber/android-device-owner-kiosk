package dev.deviceowner.kiosk;

import android.app.admin.DevicePolicyManager;
import android.content.Context;

import java.util.Collections;
import java.util.List;

/**
 * The one seam between the shared policy engine and a specific device type.
 *
 * Everything in this package is device-agnostic and calls through here rather than branching
 * on BuildConfig. In the deployment this was extracted from there were two source sets, a
 * tablet and a phone, each shipping a file of this name and API. Adding a third device type
 * meant adding a source set, never adding an `if` to the policy code.
 *
 * What ships here is the SINGLE-APP reference case: one allowlisted package, its own activity
 * as HOME, no telephony. It is the simplest configuration that still exercises every policy
 * path, and it is the one to copy when adapting this to your own device.
 *
 * Read the methods as a checklist. Each one is a decision that has to be made deliberately for
 * a device you will not be able to physically reach, and several of them are one-way.
 */
final class Flavor {

    private Flavor() {}

    static final String NAME = "reference";

    /** What to call this device in text a non-technical person reads during a crisis. */
    static final String DEVICE_NOUN = "device";

    /** What the kiosk actually shows, for the same reason. */
    static final String KIOSK_SUBJECT = "the managed app";

    /**
     * The activity that becomes HOME. Being the persistent preferred HOME handler is what
     * makes the launcher unreachable; hiding the OEM launcher is not sufficient on its own
     * and destabilises some OEM shells.
     */
    static Class<?> homeActivity() {
        return KioskActivity.class;
    }

    /**
     * Return the activity that should be the persistent preferred handler for tel: intents,
     * or null on a device with no telephony.
     *
     * Do not skip this on a device that DOES have telephony. Lock task exempts the default
     * dialer for tel: intents because the platform protects emergency dialing, so removing
     * the OEM dialer from setLockTaskPackages is not enough by itself: a tel: link will still
     * launch it inside lock task.
     */
    static Class<?> telHandlerActivity() {
        return null;
    }

    /**
     * Exactly one package in the reference configuration.
     *
     * Resist adding the OEM camera here if you need photo capture. Allowlisting it puts its QR
     * scanner, which opens arbitrary URLs, inside lock task. Capture in-app instead.
     */
    static String[] lockTaskPackages(Context c) {
        return new String[]{ c.getPackageName() };
    }

    /**
     * GLOBAL_ACTIONS is the only lock-task feature enabled by default, and omitting it is what
     * removes the power long-press menu. OVERVIEW and NOTIFICATIONS are omitted here too, so
     * the device shows nothing but the managed app.
     */
    static int armedLockTaskFeatures() {
        return DevicePolicyManager.LOCK_TASK_FEATURE_HOME
                | DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO;
    }

    /**
     * True where the device has no secure lock screen and the keyguard is pure friction.
     *
     * Note the side effect before copying this: disabling the keyguard also removes the lock
     * screen's emergency-dialer surface. That costs nothing on a device with no phone line and
     * is a decision you must not make casually on one that has one.
     */
    static boolean disableKeyguardWhenArmed() {
        return true;
    }

    /** {package, permission} pairs to grant to OTHER allowlisted apps. Empty in single-app mode. */
    static String[][] permissionGrantsForOtherApps() {
        return new String[0][];
    }

    /**
     * Device-wide camera kill. This is a DevicePolicyManager control, not a permission, and it
     * overrides any per-app grant, so leave it false if the managed app needs the camera at all.
     */
    static boolean disableCameraDeviceWide() {
        return false;
    }

    /**
     * Permissions to auto-grant to the managed app at provisioning time.
     *
     * A runtime permission prompt inside lock task is unanswerable by design: there is no way
     * for the user to reach Settings to fix a denial. Grant what the app needs up front or it
     * will fail silently in the field.
     */
    static List<String> permissionsToGrant() {
        return Collections.emptyList();
    }

    /** True on a SIM-capable device with no cellular plan in use. */
    static boolean restrictSimGlobally() {
        return false;
    }

    /**
     * Packages to exclude from the bulk hide pass.
     *
     * Hiding a package the managed app depends on fails silently and at a distance, which is
     * the worst possible failure mode on an unreachable device.
     */
    static List<String> extraKeepVisible(Context c) {
        return Collections.emptyList();
    }
}
