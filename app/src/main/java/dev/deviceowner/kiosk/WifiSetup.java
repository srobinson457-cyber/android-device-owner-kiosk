package dev.deviceowner.kiosk;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/**
 * Joining a Wi-Fi network from inside the kiosk, with no Settings and no ADB.
 *
 * This exists because of a hole found the hard way: the tablet was provisioned on the
 * operator's own network and then handed over at a site on a different one, with Settings
 * unlaunchable (lock task allowlists exactly one package), the Quick Panel gone
 * (setStatusBarDisabled) and ADB removed (lockDebugging). Wi-Fi was never restricted by
 * policy (see the comment in KioskPolicy.applyRestrictions), but "permitted" and
 * "reachable" are different things, and only the first one was ever true.
 *
 * The API path is narrow and most of the obvious calls are wrong, so the reasoning is
 * recorded here rather than rediscovered:
 *
 *  - addNetworkPrivileged(WifiConfiguration) (API 31) is the one to use. Its javadoc says
 *    "The usage of this API is limited to Device Owner (DO), Profile Owner (PO), system
 *    app, and privileged apps", so we qualify as DO WITHOUT the NETWORK_SETTINGS system
 *    permission, and it returns a real status code instead of a bare -1.
 *  - addNetwork()/enableNetwork() are deprecated and documented to "always fail and return
 *    false" for apps targeting Q or above, EXCEPT that the same javadoc carries an
 *    explicit "Deprecation Exemptions: Device Owner (DO), Profile Owner (PO) and system
 *    apps". enableNetwork is therefore still the correct way to trigger the connect.
 *  - addNetworkSuggestions() is the modern-looking answer and is WRONG here. It requires
 *    user approval, and AOSP's approval-bypass list covers carrier provisioning and
 *    NETWORK_SETTINGS only: there is no Device Owner path. It would ask for consent via a
 *    notification, in a status bar we have deliberately disabled.
 *  - reassociate() has no DO exemption at all and always returns false. Do not add it.
 *
 * Everything here works from inside lock task: lock task constrains activity launching and
 * system UI, not binder calls into WifiService.
 */
final class WifiSetup {

    private static final String TAG = "DOKiosk";

    private WifiSetup() {}

    /** Outcome of a join attempt. The distinction matters at 9pm on a phone call. */
    enum Stage { ADD_FAILED, ENABLE_REFUSED, CONNECTING }

    static final class Result {
        final Stage stage;
        final String detail;
        final int networkId;

        Result(Stage stage, String detail, int networkId) {
            this.stage = stage;
            this.detail = detail;
            this.networkId = networkId;
        }

        boolean ok() { return stage == Stage.CONNECTING; }

        @Override public String toString() { return stage + ": " + detail; }
    }

    /**
     * Add (or update) a PSK network and ask the framework to connect to it.
     *
     * MUST be called off the main thread: enableNetwork() with disableOthers=true maps
     * server-side to a directed connect that blocks on a latch.
     *
     * Note what this method does NOT tell you: whether the passphrase was correct. A typo
     * produces a successful add AND a successful enable, and the authentication failure
     * arrives later and asynchronously. Callers that a human is watching must observe the
     * supplicant state (see WifiSetupActivity), or the user sees "saved" and walks away
     * from a tablet that never connected.
     */
    static Result join(Context c, String ssid, String passphrase, boolean hidden) {
        WifiManager wm = c.getSystemService(WifiManager.class);
        if (wm == null) return new Result(Stage.ADD_FAILED, "no WifiManager", -1);

        // Deprecated for ordinary apps, exempt for Device Owner. If Wi-Fi is off, nothing
        // below can work, and there is no Settings toggle to turn it back on by hand.
        if (!wm.isWifiEnabled()) {
            try {
                wm.setWifiEnabled(true);
            } catch (Exception e) {
                Log.i(TAG, "wifi: setWifiEnabled threw " + e.getClass().getSimpleName());
            }
        }

        WifiConfiguration cfg = new WifiConfiguration();
        // Both of these must be double-quoted for UTF-8 values. An unquoted SSID is
        // interpreted as hex digits and fails in a way that reads like a wrong password.
        cfg.SSID = quote(ssid);
        cfg.preSharedKey = quote(passphrase);
        cfg.hiddenSSID = hidden;
        // SECURITY_TYPE_PSK covers WPA2 *and* WPA3-Personal: the framework adds the SAE
        // params itself. Do not hand-set allowedKeyManagement bits: that is the old advice
        // and it produces configs the modern supplicant rejects.
        cfg.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);

        // Reuse the existing entry for this SSID if there is one, so repeated attempts at a
        // mistyped password do not leave a pile of duplicate saved networks behind.
        int existing = findExisting(wm, ssid);
        if (existing != -1) cfg.networkId = existing;

        WifiManager.AddNetworkResult add;
        try {
            add = wm.addNetworkPrivileged(cfg);
        } catch (SecurityException e) {
            // This is the Samsung-deviation tripwire. AOSP permits a Device Owner here; One
            // UI ships its own Wi-Fi framework and it could not be verified remotely.
            return new Result(Stage.ADD_FAILED,
                    "refused by the platform (SecurityException): this build cannot join "
                            + "Wi-Fi on this device", -1);
        } catch (Exception e) {
            return new Result(Stage.ADD_FAILED, e.getClass().getSimpleName(), -1);
        }

        if (add == null || add.statusCode != WifiManager.AddNetworkResult.STATUS_SUCCESS) {
            int code = add == null ? -1 : add.statusCode;
            return new Result(Stage.ADD_FAILED, "add rejected, status " + code, -1);
        }

        boolean enabled;
        try {
            enabled = wm.enableNetwork(add.networkId, true);
        } catch (Exception e) {
            return new Result(Stage.ENABLE_REFUSED, e.getClass().getSimpleName(), add.networkId);
        }

        if (!enabled) {
            // The documented reason for a false here on a well-formed config is
            // WifiGlobals.isDeprecatedSecurityTypeNetwork, i.e. the router is WEP or
            // WPA-Personal/TKIP only. Worth naming explicitly, because the fix is at the
            // router and no amount of retyping the password will help.
            return new Result(Stage.ENABLE_REFUSED,
                    "the router refused the connection: it is probably running WEP or "
                            + "WPA/TKIP, which Android no longer connects to", add.networkId);
        }

        Log.i(TAG, "wifi: connecting to netId=" + add.networkId);
        return new Result(Stage.CONNECTING, "connecting", add.networkId);
    }

    /**
     * The SSID to join from the network-name field, or null when the field is blank.
     *
     * Trim only to decide whether it is blank. Leading and trailing spaces are legal in an
     * SSID, and an access point named "Cafe " does not answer to "Cafe".
     */
    static String ssidForJoin(String fieldText) {
        if (fieldText == null || fieldText.trim().isEmpty()) return null;
        return fieldText;
    }

    /** Existing saved network id for this SSID, or -1. Best effort, never throws. */
    private static int findExisting(WifiManager wm, String ssid) {
        try {
            List<WifiConfiguration> saved = wm.getConfiguredNetworks();
            if (saved == null) return -1;
            String want = quote(ssid);
            for (WifiConfiguration w : saved) {
                if (want.equals(w.SSID)) return w.networkId;
            }
        } catch (Exception ignored) {
            // getConfiguredNetworks has its own permission story; a miss here only costs a
            // duplicate saved entry, so it is not worth failing the join over.
        }
        return -1;
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
