package dev.deviceowner.kiosk;

import android.app.admin.DevicePolicyManager;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Device Owner policy. Split into separately-applicable steps rather than one apply() so
 * each can be verified on hardware before the next lands, and so the step that removes
 * our own ADB access (debugging) is always last and always deliberate.
 */
public final class KioskPolicy {

    private static final String TAG = "DOKiosk";
    static final String PREFS = "kiosk";
    static final String KEY_ARMED = "lock_task_armed";

    private KioskPolicy() {}

    /**
     * Packages that must KEEP their launcher entry / must not be hidden.
     *
     * Play Store stays deliberately: it is how Android System WebView keeps receiving
     * security updates, and this tablet already pulled WebView 151 from a factory 140 with
     * no account signed in. Lock task makes Play unlaunchable anyway, so hiding it would
     * buy nothing and cost years of patches.
     *
     * Settings and the Samsung launcher stay because hiding them can destabilise One UI and
     * because they are our fallback if the HOME takeover ever misfires. Neither is reachable
     * under lock task.
     *
     * Flavor-specific additions come from Flavor.extraKeepVisible and are merged in hideApps().
     */
    private static final Set<String> KEEP_VISIBLE = new HashSet<>(Arrays.asList(
            "dev.deviceowner.kiosk",
            "com.android.settings",
            "com.sec.android.app.launcher",

            // Play + its framework. Play Services owns a launcher activity ("Google
            // Settings") and the first sweep hid it, which would have taken Play Store,
            // WebView updates and a pile of system plumbing down with it. Never hide these.
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.webview",

            // The keyboard. No launcher activity today, but if that ever changes, hiding it
            // would leave a tablet on which nothing can be typed, including the admin response.
            "com.samsung.android.honeyboard"
    ));

    private static final String KEY_HIDDEN = "hidden_packages";

    static ComponentName admin(Context c) {
        return AdminReceiver.componentName(c);
    }

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isOwner(Context c) {
        DevicePolicyManager d = dpm(c);
        return d != null && d.isDeviceOwnerApp(c.getPackageName());
    }

    // ---------------------------------------------------------------- restrictions

    /** Everything except DISALLOW_DEBUGGING_FEATURES, which is its own explicit step. */
    static String applyRestrictions(Context c) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";

        List<String> on = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        // The two that actually defeat app-level lockdowns: a second user, and safe mode.
        add(d, a, UserManager.DISALLOW_SAFE_BOOT, on, failed);
        add(d, a, UserManager.DISALLOW_ADD_USER, on, failed);
        add(d, a, UserManager.DISALLOW_USER_SWITCH, on, failed);
        add(d, a, UserManager.DISALLOW_REMOVE_USER, on, failed);

        // Self-defence. Install restrictions are not here: DISALLOW_UNINSTALL_APPS and
        // DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY are the handover-time lockInstall() step,
        // and DISALLOW_INSTALL_APPS is not applied at all (see lockInstall()).
        add(d, a, UserManager.DISALLOW_FACTORY_RESET, on, failed);

        // Accounts cannot be added or removed. Defence in depth: nothing reachable today can
        // get to account settings (Settings is blocked by lock task, and the account-add flows
        // reachable from allowlisted apps were tested and refused), but this forecloses it
        // permanently if some future surface turns up. It costs nothing operationally because
        // the device is never meant to carry a personal account.
        //
        // Note for a future re-provision: clearing Device Owner drops every restriction,
        // including this one, so it cannot lock anyone out of removing accounts later.
        add(d, a, UserManager.DISALLOW_MODIFY_ACCOUNTS, on, failed);

        // Clock: a timezone-only shift is enough to move day boundaries and OTA windows, so both.
        add(d, a, UserManager.DISALLOW_CONFIG_DATE_TIME, on, failed);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                // Clock stays auto-synced: that is the actual anti-tamper, and it is what
                // stops anyone shifting time to defeat time-based policy.
                d.setAutoTimeEnabled(a, true);
                // Auto TIMEZONE is deliberately left alone here: it resolved to the wrong
                // zone on this tablet, and while it is on it makes
                // DevicePolicyManager.setTimeZone() illegal. The zone is pinned explicitly by
                // the `timezone` command instead. DISALLOW_CONFIG_DATE_TIME still stops the
                // user touching either one.
                on.add("autoTime");
            }
        } catch (Exception e) {
            failed.add("autoTime(" + e.getClass().getSimpleName() + ")");
        }

        // Network. Deliberately NOT DISALLOW_ADD_WIFI_CONFIG: the device lives at a remote
        // site and locking out Wi-Fi setup is a brick risk if the router ever changes.
        // The private-DNS lock (separate step) is what makes the network choice not matter.
        add(d, a, UserManager.DISALLOW_CONFIG_VPN, on, failed);
        add(d, a, UserManager.DISALLOW_CONFIG_TETHERING, on, failed);
        if (Build.VERSION.SDK_INT >= 28) {
            add(d, a, UserManager.DISALLOW_AIRPLANE_MODE, on, failed);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            add(d, a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS, on, failed);
        }

        // Physical media: this model has a microSD slot.
        add(d, a, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, on, failed);
        // DISALLOW_USB_FILE_TRANSFER deliberately NOT here (see lockDebugging()). On this
        // Samsung it kills the ADB data connection once USB re-enumerates, which presents as
        // "device unauthorized" with no prompt. Combined with lock task (which blocks the
        // UsbDebuggingActivity prompt from ever launching) that is an unrecoverable-by-ADB
        // corner. It belongs with the other handover-time steps, not in the working set.

        // Surface: other apps' overlays, and the "app has stopped" dialog, are both ways
        // out of a kiosk.
        add(d, a, UserManager.DISALLOW_CREATE_WINDOWS, on, failed);
        add(d, a, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS, on, failed);
        add(d, a, UserManager.DISALLOW_SET_WALLPAPER, on, failed);

        // Assistants / capture.
        if (Build.VERSION.SDK_INT >= 30) {
            add(d, a, UserManager.DISALLOW_CONTENT_CAPTURE, on, failed);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            add(d, a, "no_assist_content", on, failed);   // DISALLOW_ASSIST_CONTENT
            // SIM-capable device with no cellular plan only. Never on a device that relies on
            // an active cellular line: this is the one restriction in the set that could
            // interfere with it.
            if (Flavor.restrictSimGlobally()) {
                add(d, a, "no_sim_globally", on, failed);
            }
        }

        // Only system IMEs / no third-party accessibility services.
        try {
            d.setPermittedInputMethods(a, null);          // null = system only
            d.setPermittedAccessibilityServices(a, Collections.emptyList());
            on.add("permittedIMEs=system, a11y=none");
        } catch (Exception e) {
            failed.add("permitted*(" + e.getClass().getSimpleName() + ")");
        }

        // Auto-grant the managed app's own runtime permissions. A runtime permission dialog
        // inside a kiosk is both confusing and dangerous: one stray "Don't allow" would break
        // that feature permanently, with no Settings to undo it from.
        // Flavor-specific: granting a permission the app has not declared fails, which is why
        // this list is per-flavor rather than shared.
        for (String perm : Flavor.permissionsToGrant()) {
            try {
                d.setPermissionGrantState(a, c.getPackageName(), perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                on.add("granted:" + perm.substring(perm.lastIndexOf('.') + 1));
            } catch (Exception e) {
                failed.add("grant " + perm + "(" + e.getClass().getSimpleName() + ")");
            }
        }

        // Grant states for OTHER packages. A Device Owner can set any package's, and the state
        // is POLICY_FIXED so the user cannot change it back in Settings.
        //
        // Use this sparingly and prefer removing the CAPABILITY over denying the PERMISSION: a
        // denied permission is visible to the app, and a helpful app responds by deep-linking
        // to Settings > App info so the user can grant it, which is a Settings surface that
        // lock task lets through. That is exactly how denying CAMERA to an allowlisted
        // messaging app turned one hole into a worse one. See Flavor.disableCameraDeviceWide.
        for (String[] g : Flavor.permissionGrantsForOtherApps()) {
            int state = "DENIED".equals(g[2]) ? DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    : "GRANTED".equals(g[2]) ? DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    : DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
            try {
                d.setPermissionGrantState(a, g[0], g[1], state);
                on.add(g[2].toLowerCase() + ":" + g[0].substring(g[0].lastIndexOf('.') + 1)
                        + ":" + g[1].substring(g[1].lastIndexOf('.') + 1));
            } catch (Exception e) {
                failed.add("grantState " + g[0] + "/" + g[1]
                        + "(" + e.getClass().getSimpleName() + ")");
            }
        }

        // The camera itself, for every app on the device. This is what stops a messaging
        // app's QR pairing; unlike a permission denial it is invisible to the app, so nothing offers a
        // route into Settings to "fix" it.
        try {
            d.setCameraDisabled(a, Flavor.disableCameraDeviceWide());
            on.add("cameraDisabled=" + Flavor.disableCameraDeviceWide());
        } catch (Exception e) {
            failed.add("setCameraDisabled(" + e.getClass().getSimpleName() + ")");
        }

        // Defence in depth for the App-info screen found above: even if some other app deep
        // links there, force-stop, clear-data and permission edits are refused.
        add(d, a, UserManager.DISALLOW_APPS_CONTROL, on, failed);

        // Cannot be uninstalled, cannot be force-stopped or cleared. The second half is
        // what stops Android 15 wiping our PendingIntents by putting us in the stopped state.
        try {
            d.setUninstallBlocked(a, c.getPackageName(), true);
            on.add("uninstallBlocked");
        } catch (Exception e) {
            failed.add("uninstallBlocked");
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                d.setUserControlDisabledPackages(a,
                        Collections.singletonList(c.getPackageName()));
                on.add("userControlDisabled");
            }
        } catch (Exception e) {
            failed.add("userControlDisabled(" + e.getClass().getSimpleName() + ")");
        }

        String result = "APPLIED(" + on.size() + "): " + on + "\nFAILED: " + failed;
        Log.i(TAG, result);
        return result;
    }

    private static void add(DevicePolicyManager d, ComponentName a, String key,
                            List<String> on, List<String> failed) {
        try {
            d.addUserRestriction(a, key);
            on.add(key);
        } catch (Exception e) {
            failed.add(key + "(" + e.getClass().getSimpleName() + ")");
        }
    }

    // ---------------------------------------------------------------- maintenance mode

    /**
     * When on-device maintenance mode was opened (wall-clock ms). Only relock() clears it, and
     * only when all three of its steps took: lockInstall and lockDebugging applied every
     * restriction, and arm recorded armed=true. The armed flag alone proves nothing: opening
     * maintenance mode lifts the other two locks before it disarms, so a failed disarm leaves
     * the flag true with both of them lifted.
     */
    static final String KEY_MAINT_OPENED = "maintenance_opened_at";

    /**
     * How long on-device maintenance mode may stay open before the next kiosk start closes
     * it. Not the same thing as RemotePolicy.REMOTE_UNLOCK_MAX_MS, which bounds a remote
     * unlock of the admin screen and never disarms anything.
     */
    static final long LOCAL_MAINTENANCE_TTL_MS = 60 * 60_000L;

    /** Remember when maintenance mode was opened, so it can expire on its own. */
    static void markMaintenanceOpened(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_MAINT_OPENED, System.currentTimeMillis()).apply();
    }

    /**
     * Close maintenance mode if nobody closed it.
     *
     * Maintenance mode is the one control on the admin screen that leaves the tablet OPEN,
     * and it persists: `armed=false` survives reboots. So a single support call where the
     * person on site forgets to tap "Re-lock the kiosk" would silently undo the entire
     * lockdown, for good, with nothing to indicate it had happened.
     *
     * On an unattended device the likeliest failures are well-meaning mistakes, not attacks,
     * and this is the most expensive of them.
     *
     * An hour is generous for someone being talked through a fix on the phone, and bounded
     * enough that "forgot" costs an hour rather than forever. Called on every kiosk start, so
     * a reboot mid-window does not reset the clock: the timestamp is absolute. If any relock
     * step fails, the marker stays and the next start tries again.
     *
     * @return true if the window had expired and a relock was attempted.
     */
    static boolean expireMaintenanceIfStale(Context c) {
        return expireMaintenanceIfStale(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                System.currentTimeMillis(), () -> relock(c));
    }

    /** expireMaintenanceIfStale(Context) with the clock and the relock passed in, for tests. */
    static boolean expireMaintenanceIfStale(SharedPreferences p, long now,
                                            Supplier<String> relock) {
        long opened = p.getLong(KEY_MAINT_OPENED, 0L);
        if (opened == 0L) return false;
        // No shortcut on armed=true: it does not mean somebody re-locked properly. Opening
        // maintenance mode lifts the debugging and install locks before it disarms, so a
        // failed disarm, or an `arm` sent over ADB, leaves armed=true with both still lifted.
        // Only relock() clears the marker, so once the hour is up it runs whatever the flag
        // says.
        if (now - opened < LOCAL_MAINTENANCE_TTL_MS) return false;

        Log.i(TAG, "maintenance window expired, re-locking automatically\n" + relock.get());
        return true;
    }

    /**
     * Close maintenance mode: re-apply the three steps that opening it lifted. Used by the
     * admin screen's "Re-lock the kiosk" and by expireMaintenanceIfStale, so the order lives
     * in one place.
     */
    static String relock(Context c) {
        return relock(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
                () -> lockInstall(c, true),
                () -> arm(c, true),
                () -> lockDebugging(c, true));
    }

    /** relock(Context) with the three steps passed in, for tests. */
    static String relock(SharedPreferences p, Supplier<Step> lockInstall,
                         Supplier<Step> arm, Supplier<Step> lockDebugging) {
        Step install = lockInstall.get();
        Step armed = arm.get();
        // Debugging goes last, exactly as at handover: it is the step that removes our own
        // way back in, so nothing may fail after it.
        Step debugging = lockDebugging.get();
        // The one place the marker is cleared, and only when every step took. Until then it
        // stays, so the next start retries instead of leaving the device open. The armed flag
        // is not consulted: it can already be true when opening maintenance failed to disarm.
        if (install.ok && armed.ok && debugging.ok) {
            p.edit().remove(KEY_MAINT_OPENED).apply();
        }
        return install + "\n" + armed + "\n" + debugging;
    }

    /**
     * What a lock step did: its report, for a person or the log, and whether it did its whole
     * job. relock() reads ok rather than parsing the report.
     */
    static final class Step {
        final boolean ok;
        private final String report;

        Step(boolean ok, String report) {
            this.ok = ok;
            this.report = report;
        }

        @Override public String toString() { return report; }
    }

    // ---------------------------------------------------------------- updates and installs

    /**
     * Let Android security updates install themselves, overnight, with nobody present.
     *
     * Without a policy the device follows the default: an OTA raises a NOTIFICATION and
     * waits for someone to go to Settings and tap install. On this tablet the status bar is
     * disabled so the notification is invisible, and Settings cannot be launched at all, so
     * the update would sit there forever and the tablet would simply stop receiving security
     * patches, silently, for as long as it exists.
     *
     * A windowed policy installs in a fixed local-time window instead. 03:00-05:00 so the
     * reboot never lands mid-use, and the tablet is back on the managed app long before morning.
     * Times are minutes from local midnight, and the clock is DO-locked to network time, so
     * the window cannot be dragged around by changing the date.
     */
    static String systemUpdates(Context c) {
        if (Build.VERSION.SDK_INT < 23) return "not supported";
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";
        try {
            d.setSystemUpdatePolicy(a, SystemUpdatePolicy.createWindowedInstallPolicy(180, 300));
            SystemUpdatePolicy p = d.getSystemUpdatePolicy();
            return "system updates: windowed 03:00-05:00, readback type="
                    + (p == null ? "null" : String.valueOf(p.getPolicyType()));
        } catch (Exception e) {
            return "FAILED: " + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    /**
     * Handover-time step: blocks uninstalling apps and, on API 29+, installing from unknown
     * sources. Reversible with `unlockinstall` or `clear`.
     *
     * It does not block installs in general: DISALLOW_INSTALL_APPS is deliberately left out
     * (see below), so Play can keep Android System WebView patched.
     */
    static Step lockInstall(Context c, boolean locked) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return new Step(false, "NOT DEVICE OWNER");
        List<String> on = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        // DISALLOW_INSTALL_APPS is deliberately NOT here any more.
        //
        // It blocks ALL installs, and that includes Play updating existing apps, which on
        // this device means Android System WebView, the engine actually rendering
        // the managed app. This tablet has to survive years at a site with no physical access, so
        // freezing the browser engine at whatever version shipped is a real, compounding
        // security cost.
        //
        // What it bought us was close to nothing. The device user cannot reach Play (lock task
        // allowlists one package and Play is hidden), cannot sideload
        // (DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, still applied below), and has no
        // browser, file manager or ADB to obtain an APK with in the first place. Removing
        // the app is still blocked by DISALLOW_UNINSTALL_APPS and setUninstallBlocked.
        //
        // So: keep the restriction that stops sideloading, drop the one that stops security
        // patches. Note this was also the restriction that blocked `adb install` and had to
        // be lifted by hand at every maintenance visit.
        String[] keys = Build.VERSION.SDK_INT >= 29
                ? new String[]{UserManager.DISALLOW_UNINSTALL_APPS,
                               UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY}
                : new String[]{UserManager.DISALLOW_UNINSTALL_APPS};
        for (String k : keys) {
            try {
                if (locked) d.addUserRestriction(a, k); else d.clearUserRestriction(a, k);
                on.add(k);
            } catch (Exception e) {
                failed.add(k);
            }
        }
        return new Step(failed.isEmpty(),
                (locked ? "INSTALL LOCKED " : "INSTALL UNLOCKED ") + on + " failed=" + failed);
    }

    /**
     * The very last step before handover. Removes Developer options entirely (USB and
     * wireless debugging both), which means it also removes our own way back in. Everything
     * else must be verified before this runs. locked=false lifts both again.
     */
    static Step lockDebugging(Context c, boolean locked) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return new Step(false, "NOT DEVICE OWNER");
        return lockDebugging((k, on) -> {
            if (on) d.addUserRestriction(a, k); else d.clearUserRestriction(a, k);
        }, locked);
    }

    /** Adds (on) or clears (off) one user restriction. Lets tests stand in for the DPM. */
    interface RestrictionSetter {
        void set(String key, boolean on);
    }

    /** lockDebugging(Context, boolean) against any RestrictionSetter, for tests. */
    static Step lockDebugging(RestrictionSetter r, boolean locked) {
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        // Both of these remove our own access, so they go together and they go LAST.
        for (String k : new String[]{UserManager.DISALLOW_DEBUGGING_FEATURES,
                                     UserManager.DISALLOW_USB_FILE_TRANSFER}) {
            try {
                r.set(k, locked);
                done.add(k);
            } catch (Exception e) {
                failed.add(k + "(" + e.getClass().getSimpleName() + ")");
            }
        }
        // Claim the outcome only when both took: "adb is gone" while it is not would be a
        // diagnostic that lies.
        String outcome = !failed.isEmpty() ? (locked ? "LOCK" : "UNLOCK") + " INCOMPLETE"
                : locked ? "DISABLED (adb is gone)" : "re-enabled";
        return new Step(failed.isEmpty(),
                "DEBUGGING+USB " + outcome + " " + done + " failed=" + failed);
    }

    // ---------------------------------------------------------------- HOME takeover

    /** The single highest-leverage call: makes us the permanent, unchangeable launcher. */
    static String applyHome(Context c) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";
        try {
            IntentFilter home = new IntentFilter(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addCategory(Intent.CATEGORY_DEFAULT);
            d.addPersistentPreferredActivity(admin(c), home,
                    new ComponentName(c, Flavor.homeActivity()));
            String result = "HOME set to " + Flavor.homeActivity().getSimpleName();

            // Claim tel: as well, where the flavor asks for it.
            //
            // Lock task EXEMPTS the default dialer for tel: intents (the platform protects
            // emergency dialing), so dropping Samsung's dialer from setLockTaskPackages was
            // NOT enough on its own. Firing a tel: link launched DialtactsActivity with
            // mLockTaskModeState=LOCKED, putting a full dialer back one tap away. Being the
            // persistent preferred handler is what actually closes it.
            Class<?> tel = Flavor.telHandlerActivity();
            if (tel != null) {
                ComponentName target = new ComponentName(c, tel);
                for (String action : new String[]{Intent.ACTION_DIAL, Intent.ACTION_VIEW}) {
                    IntentFilter f = new IntentFilter(action);
                    f.addCategory(Intent.CATEGORY_DEFAULT);
                    f.addDataScheme("tel");
                    d.addPersistentPreferredActivity(admin(c), f, target);
                }
                // ACTION_DIAL with no data at all ("open the dialer") resolves separately.
                IntentFilter bare = new IntentFilter(Intent.ACTION_DIAL);
                bare.addCategory(Intent.CATEGORY_DEFAULT);
                d.addPersistentPreferredActivity(admin(c), bare, target);
                result += ", tel: claimed by " + tel.getSimpleName();
            }
            return result;
        } catch (Exception e) {
            return "HOME FAILED: " + e;
        }
    }

    static String clearHome(Context c) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";
        d.clearPackagePersistentPreferredActivities(admin(c), c.getPackageName());
        return "HOME preference cleared";
    }

    // ---------------------------------------------------------------- app suppression

    /**
     * Hide every package that owns a launcher icon, except {@link #KEEP_VISIBLE}.
     *
     * Computed at runtime rather than hardcoded: the list was 55 entries on this tablet and a
     * hardcoded copy silently rots the first time an OTA adds one.
     *
     * Hidden, not suspended: a suspended app still shows a grayed icon and a system dialog,
     * which just advertises that it exists.
     */
    static String hideApps(Context c, boolean hide) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";

        PackageManager pm = c.getPackageManager();
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> done = new ArrayList<>();
        List<String> refused = new ArrayList<>();

        // Base keep-list plus whatever this flavor cannot afford to lose. Hiding a package
        // the managed app depends on fails silently, so each flavor names its own.
        Set<String> keepVisible = new HashSet<>(KEEP_VISIBLE);
        keepVisible.addAll(Flavor.extraKeepVisible(c));

        if (hide) {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> all = pm.queryIntentActivities(launcher,
                    PackageManager.MATCH_ALL | PackageManager.MATCH_DISABLED_COMPONENTS);
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : all) {
                String pkg = ri.activityInfo.packageName;
                if (!seen.add(pkg)) continue;
                if (keepVisible.contains(pkg)) continue;
                try {
                    if (d.setApplicationHidden(a, pkg, true)) done.add(pkg);
                    else refused.add(pkg);
                } catch (Exception e) {
                    refused.add(pkg + "(" + e.getClass().getSimpleName() + ")");
                }
            }
            // Remember what we hid, additively. Needed because a hidden package stops being
            // returned by the launcher query above, so the naive unhide could never find
            // its way back and the tablet would keep whatever the first sweep took.
            Set<String> record = new HashSet<>(
                    prefs.getStringSet(KEY_HIDDEN, Collections.emptySet()));
            record.addAll(done);
            prefs.edit().putStringSet(KEY_HIDDEN, record).apply();
        } else {
            // Unhide from the authoritative source rather than the launcher query: walk every
            // installed application and clear the flag wherever it is set.
            for (ApplicationInfo ai :
                    pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)) {
                try {
                    if (d.isApplicationHidden(a, ai.packageName)) {
                        if (d.setApplicationHidden(a, ai.packageName, false)) done.add(ai.packageName);
                        else refused.add(ai.packageName);
                    }
                } catch (Exception e) {
                    refused.add(ai.packageName + "(" + e.getClass().getSimpleName() + ")");
                }
            }
            prefs.edit().remove(KEY_HIDDEN).apply();
        }

        String result = (hide ? "HIDDEN " : "UNHIDDEN ") + done.size() + ": " + done
                + "\nREFUSED " + refused.size() + ": " + refused;
        Log.i(TAG, result);
        return result;
    }

    // ---------------------------------------------------------------- lock task

    /**
     * The armed lock-task feature set, and the single source of truth for it.
     *
     * GLOBAL_ACTIONS is the ONLY lock-task flag that is on by DEFAULT; omitting it is what
     * removes the power long-press menu. OVERVIEW and NOTIFICATIONS are omitted too.
     *
     * This is a constant rather than a literal inside arm() because three places need the
     * same value: arm(), allowPowerOff(), and the re-assert on every kiosk launch that makes a
     * temporary power-off grant expire by itself. Copies of it would drift, and the way it would
     * drift is "the power menu quietly stays available to the user".
     */
    static final int ARMED_LOCK_TASK_FEATURES = Flavor.armedLockTaskFeatures();

    /**
     * Re-apply the armed feature set. Called on every kiosk launch, which is what makes
     * allowPowerOff() self-expiring: setLockTaskFeatures is persistent DPM policy, so
     * without this a one-off grant would survive every future reboot.
     */
    static void reassertLockTaskFeatures(Context c) {
        if (Build.VERSION.SDK_INT < 28) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return;
        try {
            d.setLockTaskFeatures(a, ARMED_LOCK_TASK_FEATURES);
        } catch (Exception e) {
            Log.i(TAG, "reassert features failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Temporarily restore the power menu so the tablet can be shut down properly.
     *
     * There is no public shutdown API: a Device Owner gets DevicePolicyManager.reboot(),
     * which restarts, and PowerManager.shutdown() is system-only. So the honest mechanism is
     * to hand the power menu back and let a human use it.
     *
     * Needed because the tablet genuinely could not be switched off at all (only forced
     * through a power cycle), which is a problem for screen repair, for storage, for a
     * flight, and for anyone who simply wants the thing to stop. The grant lasts until
     * the kiosk next starts, which is exactly as long as it takes to use it.
     */
    static String allowPowerOff(Context c) {
        if (Build.VERSION.SDK_INT < 28) return "not supported below API 28";
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";
        try {
            d.setLockTaskFeatures(a, ARMED_LOCK_TASK_FEATURES
                    | DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS);
            return "power menu enabled until next start";
        } catch (Exception e) {
            return "FAILED: " + e.getClass().getSimpleName();
        }
    }

    /** Render a lock-task feature bitmask as names plus the raw value dumpsys reports. */
    static String describeLockTaskFeatures(int f) {
        List<String> on = new ArrayList<>();
        if ((f & DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO) != 0) on.add("SYSTEM_INFO");
        if ((f & DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS) != 0) on.add("NOTIFICATIONS");
        if ((f & DevicePolicyManager.LOCK_TASK_FEATURE_HOME) != 0) on.add("HOME");
        if ((f & DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW) != 0) on.add("OVERVIEW");
        if ((f & DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS) != 0) on.add("GLOBAL_ACTIONS");
        if ((f & DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD) != 0) on.add("KEYGUARD");
        if (on.isEmpty()) on.add("NONE");
        // The raw value is included because that is what `dumpsys device_policy` prints as
        // mFlags, and matching the two is how this gets verified on hardware.
        return String.join("|", on) + "(mFlags=" + f + ")";
    }

    /** Arms or disarms lock task. The step is ok only once the armed flag is recorded. */
    static Step arm(Context c, boolean armed) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d == null || !isOwner(c)) return new Step(false, "NOT DEVICE OWNER");

        StringBuilder sb = new StringBuilder();
        try {
            // Flavor-supplied. The reference build is exactly one package (camera capture is
            // in-app so it stays that way). A flavor that allowlists more must include every
            // separate package a flow depends on, or that flow breaks inside lock task.
            String[] lockTaskPkgs = Flavor.lockTaskPackages(c);
            d.setLockTaskPackages(a, lockTaskPkgs);
            sb.append("lockTaskPackages=").append(Arrays.toString(lockTaskPkgs)).append(' ');

            if (Build.VERSION.SDK_INT >= 28) {
                int features = armed ? ARMED_LOCK_TASK_FEATURES
                        : DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS;
                d.setLockTaskFeatures(a, features);
                // Decoded from the value actually applied, never a hardcoded label. The old
                // literal read "HOME|SYSTEM_INFO" on every flavor, so when one flavor added
                // NOTIFICATIONS the log claimed it had not been set while dumpsys showed
                // mFlags=7. A diagnostic that lies is worse than no diagnostic: it invites
                // someone to "fix" a policy that was already correct.
                sb.append("features=").append(describeLockTaskFeatures(features)).append(' ');
            }

            d.setStatusBarDisabled(a, armed);
            sb.append("statusBarDisabled=").append(armed).append(' ');

            // Flavor decision. A device that leaves the building should keep its keyguard, and
            // keeping it also keeps the lock-screen emergency dialer. Lock task was always the
            // real barrier. The keyguard never was.
            if (Flavor.disableKeyguardWhenArmed()) {
                try {
                    d.setKeyguardDisabled(a, armed);
                    sb.append("keyguardDisabled=").append(armed).append(' ');
                } catch (Exception e) {
                    sb.append("keyguard(").append(e.getClass().getSimpleName()).append(") ");
                }
            } else {
                sb.append("keyguardLeftOn ");
            }
        } catch (Exception e) {
            return new Step(false, "ARM FAILED: " + e);
        }

        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ARMED, armed).apply();
        sb.append("armed=").append(armed);
        Log.i(TAG, sb.toString());
        return new Step(true, sb.toString());
    }

    /**
     * Device Owner timezone set (API 28+). Needed because the tablet auto-detected the wrong
     * zone, and the managed app keys "today" off LOCAL time, so an hour's
     * offset silently moves day boundaries. Requires automatic time-zone detection to be off first:
     *   adb shell cmd time_zone_detector set_auto_detection_enabled false
     */
    static String setTimeZone(Context c, String tz) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";
        if (tz == null || tz.isEmpty()) return "REFUSED: no timezone given";
        if (Build.VERSION.SDK_INT < 28) return "needs API 28";
        try {
            // Manual set is refused while automatic detection is on, so turn it off first.
            if (Build.VERSION.SDK_INT >= 30) d.setAutoTimeZoneEnabled(admin(c), false);
            boolean ok = d.setTimeZone(admin(c), tz);
            return "setTimeZone(" + tz + ") = " + ok
                    + " | now " + java.util.TimeZone.getDefault().getID();
        } catch (Exception e) {
            return "setTimeZone FAILED: " + e;
        }
    }

    // ---------------------------------------------------------------- DNS

    /** Category-filtered DoT resolver. Follows the tablet onto any Wi-Fi, which is the point. */
    static String applyPrivateDns(Context c, String host) {
        DevicePolicyManager d = dpm(c);
        if (d == null || !isOwner(c)) return "NOT DEVICE OWNER";
        if (Build.VERSION.SDK_INT < 29) return "needs API 29";
        try {
            int rc = d.setGlobalPrivateDnsModeSpecifiedHost(admin(c), host);
            return "privateDns(" + host + ") rc=" + rc
                    + " (0=SUCCESS, 1=ERROR_HOST_NOT_SERVING, 2=ERROR_FAILURE_SETTING)";
        } catch (Exception e) {
            return "privateDns FAILED: " + e;
        }
    }

    // ---------------------------------------------------------------- teardown

    /** Full undo, in the order that keeps the device usable at every step. */
    static String clearAll(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append(arm(c, false)).append('\n');
        sb.append(hideApps(c, false)).append('\n');
        sb.append(clearHome(c)).append('\n');
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        if (d != null && isOwner(c)) {
            for (String r : new ArrayList<>(d.getUserRestrictions(a).keySet())) {
                try { d.clearUserRestriction(a, r); } catch (Exception ignored) {}
            }
            try { d.setUninstallBlocked(a, c.getPackageName(), false); } catch (Exception ignored) {}
            sb.append("restrictions cleared");
        }
        return sb.toString();
    }

    static String status(Context c) {
        DevicePolicyManager d = dpm(c);
        if (d == null) return "no dpm";
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return "flavor=" + Flavor.NAME
                + "\ndeviceOwner=" + isOwner(c)
                + "\narmed=" + prefs.getBoolean(KEY_ARMED, false)
                + "\nlockTaskPackages=" + Arrays.toString(Flavor.lockTaskPackages(c))
                + "\nrestrictions=" + (isOwner(c) ? d.getUserRestrictions(admin(c)).keySet() : "n/a");
    }
}
