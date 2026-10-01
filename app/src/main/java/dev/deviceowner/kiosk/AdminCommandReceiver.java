package dev.deviceowner.kiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Setup-time control surface, driven over ADB:
 *
 *   adb shell am broadcast -a dev.deviceowner.kiosk.CMD \
 *       -n dev.deviceowner.kiosk/.AdminCommandReceiver \
 *       --es token <TOKEN> --es cmd status
 *
 * Exported because `am broadcast` runs as shell and cannot reach a private receiver. The
 * token is a speed bump, not a security boundary - the real boundary is that no other app
 * can be installed, and that DISALLOW_DEBUGGING_FEATURES removes ADB entirely at handover.
 * Results go to both the ordered-broadcast result and logcat, because `am broadcast`
 * truncates long result strings and the hide-apps output is long.
 */
public class AdminCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "DOKiosk";
    private static final String TOKEN = "CHANGE-ME-BEFORE-FLASHING";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!TOKEN.equals(intent.getStringExtra("token"))) {
            setResultData("BAD TOKEN");
            return;
        }
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) cmd = "";

        String out;
        switch (cmd) {
            case "status":       out = KioskPolicy.status(context)
                                        + "\nadminSecretSet=" + AdminGate.hasSecret(context)
                                        + "\n" + RemotePolicy.status(context); break;
            case "setsecret":    out = AdminGate.setSecret(
                                          context, intent.getStringExtra("secret")); break;
            case "policyurl":    out = RemotePolicy.setUrl(
                                          context, intent.getStringExtra("url")); break;
            case "poll":         RemotePolicy.pollAsync(context); out = "poll fired"; break;
            case "restrictions": out = KioskPolicy.applyRestrictions(context); break;
            case "home":         out = KioskPolicy.applyHome(context); break;
            case "unhome":       out = KioskPolicy.clearHome(context); break;
            case "hide":         out = KioskPolicy.hideApps(context, true); break;
            case "unhide":       out = KioskPolicy.hideApps(context, false); break;
            case "arm":          out = KioskPolicy.arm(context, true); break;
            case "poweroff":     out = KioskPolicy.allowPowerOff(context); break;
            case "updates":      out = KioskPolicy.systemUpdates(context); break;
            case "disarm":       out = KioskPolicy.arm(context, false); break;
            case "dns":          out = KioskPolicy.applyPrivateDns(
                                          context, intent.getStringExtra("host")); break;
            case "lockinstall":  out = KioskPolicy.lockInstall(context, true); break;
            case "unlockinstall":out = KioskPolicy.lockInstall(context, false); break;
            case "lockdebug":    out = KioskPolicy.lockDebugging(context, true); break;
            case "unlockdebug":  out = KioskPolicy.lockDebugging(context, false); break;
            case "timezone":     out = KioskPolicy.setTimeZone(
                                          context, intent.getStringExtra("tz")); break;
            // Preload a network at setup time, so the tablet auto-joins on arrival and the
            // on-device Wi-Fi screen is a fallback rather than the only hope. Runs inline on
            // the broadcast thread, which is not the main thread, so the blocking connect in
            // enableNetwork is fine here.
            case "wifi":         out = WifiSetup.join(context,
                                          intent.getStringExtra("ssid"),
                                          intent.getStringExtra("pass"),
                                          "1".equals(intent.getStringExtra("hidden"))
                                       ).toString(); break;
            case "clear":        out = KioskPolicy.clearAll(context); break;
            default:             out = "unknown cmd: " + cmd;
        }

        Log.i(TAG, "=== CMD " + cmd + " ===\n" + out);
        setResultData(out);
    }
}
