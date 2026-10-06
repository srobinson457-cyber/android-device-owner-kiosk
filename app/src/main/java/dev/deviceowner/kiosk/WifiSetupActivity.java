package dev.deviceowner.kiosk;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Join a different Wi-Fi network", reachable only through AdminGate.
 *
 * The audience for this screen is not the operator: it is a non-technical person on site,
 * on the phone, somewhere nobody with ADB will ever visit. That shapes three decisions:
 *
 *  1. TYPED SSID IS THE PRIMARY PATH, the scan list is a convenience. The join path
 *     (addNetworkPrivileged + enableNetwork) has zero dependency on location permission,
 *     the system Location toggle, scan throttling or Samsung's location stack. The scan
 *     list depends on all four. Making the fragile thing optional means a failure there
 *     costs a nicety, not the recovery.
 *  2. IT REPORTS REAL CONNECTION STATE, not "saved". A wrong passphrase produces a
 *     successful add and a successful enable; the authentication failure only arrives
 *     later, asynchronously. Without a supplicant-state watcher the screen would say
 *     "connecting" forever and they would put the tablet down believing it worked.
 *  3. IT WAITS FOR VALIDATED INTERNET, not merely for association. Joining the access
 *     point and being able to reach the managed app are different claims, and the second one
 *     is the one that matters.
 */
public class WifiSetupActivity extends Activity {

    /**
     * Association plus internet validation. 45s was too short and produced a FALSE FAILURE
     * on a phone hotspot: the tablet had genuinely joined, the screen said "still not
     * connected", and (far worse) the recovery below then tore the good connection down.
     * Validation on a hotspot backhauled by mobile data is not fast.
     */
    private static final long ATTEMPT_TIMEOUT_MS = 90_000L;

    private TextView state;
    private TextView scanLabel;
    private LinearLayout scanBox;
    private EditText ssidField;
    private EditText passField;
    private CheckBox hiddenBox;
    private Button joinButton;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback netCallback;
    private BroadcastReceiver wifiEvents;
    private BroadcastReceiver scanResults;
    private boolean attempting;
    private Runnable timeout;
    /** SSID of the in-flight attempt, so recovery can tell success from failure. */
    private String pendingSsid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#001b3d"));
        root.setPadding(64, 56, 64, 56);

        TextView title = new TextView(this);
        title.setText("Wi-Fi setup");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26f);
        root.addView(title);

        state = new TextView(this);
        state.setTextColor(Color.parseColor("#9FC4FF"));
        state.setTextSize(16f);
        state.setPadding(0, 16, 0, 24);
        root.addView(state);

        ssidField = new EditText(this);
        ssidField.setHint("Network name (exactly as shown)");
        ssidField.setSingleLine(true);
        ssidField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        styleField(ssidField);
        root.addView(ssidField);

        passField = new EditText(this);
        passField.setHint("Password");
        passField.setSingleLine(true);
        styleField(passField);
        // Visible by default and deliberately so: this is typed once, behind the admin gate,
        // and a hidden password field is the single most common way a remote
        // Wi-Fi setup burns a phone call.
        passField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        root.addView(passField);

        hiddenBox = new CheckBox(this);
        hiddenBox.setText("This network is hidden");
        hiddenBox.setTextColor(Color.WHITE);
        root.addView(hiddenBox);

        joinButton = new Button(this);
        joinButton.setText("Join network");
        joinButton.setOnClickListener(v -> attemptJoin());
        root.addView(joinButton);

        scanLabel = new TextView(this);
        scanLabel.setTextColor(Color.parseColor("#9FC4FF"));
        scanLabel.setTextSize(15f);
        scanLabel.setPadding(0, 28, 0, 8);
        scanLabel.setText("Nearby networks");
        root.addView(scanLabel);

        Button rescan = new Button(this);
        rescan.setText("Search again");
        rescan.setAllCaps(false);
        rescan.setOnClickListener(v -> refreshScanList());
        root.addView(rescan);

        scanBox = new LinearLayout(this);
        scanBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(scanBox);

        Button done = new Button(this);
        done.setText("Done");
        done.setOnClickListener(v -> finish());
        root.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroller = new ScrollView(this);
        // The ScrollView is what fills the window, so IT needs the background: the child
        // LinearLayout is only as tall as its content, which left the bottom two-thirds of
        // the screen system-default gray.
        scroller.setBackgroundColor(Color.parseColor("#001b3d"));
        scroller.addView(root);
        setContentView(scroller);

        // targetSdk 35 means the window is edge-to-edge and nothing is inset for us, so
        // without this the first line of text sits underneath the status bar: a fixed offset
        // against a system bar whose height is not fixed.
        if (Build.VERSION.SDK_INT >= 30) {
            scroller.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        showCurrent();
        refreshScanList();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopWatching();
        if (scanResults != null) {
            try { unregisterReceiver(scanResults); } catch (Exception ignored) {}
            scanResults = null;
        }
    }

    /**
     * White-on-navy. The default EditText inherits near-black text and a dark gray hint,
     * which on this background rendered the field labels almost invisible, and the person
     * using this screen is following instructions over the phone.
     */
    private void styleField(EditText f) {
        f.setTextColor(Color.WHITE);
        f.setHintTextColor(Color.parseColor("#9FC4FF"));
    }

    // ---------------------------------------------------------------- current state

    private void showCurrent() {
        if (attempting) return;
        WifiManager wm = getSystemService(WifiManager.class);
        if (wm == null) {
            state.setText("Wi-Fi unavailable on this device.");
            return;
        }
        if (!wm.isWifiEnabled()) {
            state.setText("Wi-Fi is currently OFF. Joining a network will switch it on.");
            return;
        }
        WifiInfo info = wm.getConnectionInfo();
        String ssid = info == null ? null : stripQuotes(info.getSSID());
        if (ssid == null || ssid.isEmpty() || "<unknown ssid>".equals(ssid)) {
            state.setText("Not connected to any network.");
        } else {
            state.setText("Currently connected to: " + ssid
                    + (hasValidatedInternet() ? "\nInternet is working." : "\nNo internet yet."));
        }
    }

    // ---------------------------------------------------------------- the join

    private void attemptJoin() {
        final String ssid = WifiSetup.ssidForJoin(ssidField.getText().toString());
        final String pass = passField.getText().toString();
        final boolean hidden = hiddenBox.isChecked();

        if (ssid == null) {
            state.setText("Enter the network name first.");
            return;
        }
        // WPA-PSK passphrases are 8-63 characters. Catching this here turns a silent,
        // asynchronous authentication failure into an immediate, obvious message.
        if (pass.length() < 8 || pass.length() > 63) {
            state.setText("That password cannot be right. Wi-Fi passwords are between 8 "
                    + "and 63 characters. Check it and try again.");
            return;
        }

        attempting = true;
        pendingSsid = ssid;
        joinButton.setEnabled(false);
        state.setText("Connecting to " + ssid + "...");
        startWatching(ssid);

        // enableNetwork blocks on a latch server-side, so this cannot run on the main thread.
        new Thread(() -> {
            final WifiSetup.Result r = WifiSetup.join(this, ssid, pass, hidden);
            ui.post(() -> {
                if (!r.ok()) {
                    finishAttempt("Could not connect.\n\n" + r.detail);
                }
                // On CONNECTING we say nothing yet: the watcher below owns the outcome,
                // because "the connect was requested" is not "the connect succeeded".
            });
        }).start();
    }

    /**
     * Watch for the real outcome. Three things can happen and each needs a different
     * sentence, because each has a different fix.
     */
    private void startWatching(final String ssid) {
        stopWatching();

        wifiEvents = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                if (!attempting) return;
                String action = intent.getAction();
                if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                    int err = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                    if (err == WifiManager.ERROR_AUTHENTICATING) {
                        // Android reports an authentication failure when the access point
                        // simply is not there any more, so "wrong password" is not safe to
                        // claim without checking. Observed live: a phone hotspot switched
                        // itself off between attempts and the screen blamed the password,
                        // which is exactly the wrong thing to tell someone whose router is
                        // off or out of range, because they will retype a correct password
                        // for as long as they can stand it.
                        finishAttempt(visibleNearby(ssid)
                                ? "Wrong password for " + ssid + ".\n\nThe network was found, "
                                        + "but it rejected the password. Retype it and try "
                                        + "again."
                                : "Could not find " + ssid + " nearby.\n\nCheck the name is "
                                        + "exactly right, and that the router is switched on "
                                        + "and close by. Tap \"Search again\" to look for it.");
                        return;
                    }
                    // The typed getParcelableExtra overload is API 33+; minSdk here is 26,
                    // so it would compile clean and then NoSuchMethodError on an older
                    // device. This tablet is API 36, but the guard costs nothing and the
                    // rest of this codebase guards its version-gated calls the same way.
                    SupplicantState s;
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        s = intent.getParcelableExtra(
                                WifiManager.EXTRA_NEW_STATE, SupplicantState.class);
                    } else {
                        s = (SupplicantState) intent.getParcelableExtra(
                                WifiManager.EXTRA_NEW_STATE);
                    }
                    // Same trap as the capability callback: COMPLETED means "associated with
                    // something", not "associated with the network you asked for".
                    if (s == SupplicantState.COMPLETED && ssid.equals(currentSsid())) {
                        state.setText("Connected to " + ssid + ". Checking internet...");
                    }
                }
            }
        };
        registerReceiver(wifiEvents,
                new IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION),
                Context.RECEIVER_NOT_EXPORTED);

        // Association is not the goal: reaching the managed app is. Wait for the network to be
        // VALIDATED, which is the platform's own verdict on whether traffic actually flows.
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) {
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
                    if (!attempting) return;
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return;
                    // CRITICAL: check WHICH network, not just that one exists.
                    //
                    // The first version omitted this and produced a confident false success:
                    // the previous network was still connected and validated, so this fired
                    // immediately and reported "connected, internet is working" using the
                    // SSID the user had merely TYPED. Verified against a deliberately wrong
                    // password: the screen claimed success while `cmd wifi status` showed
                    // the tablet had never left the old network.
                    //
                    // That is the exact walk-away failure this watcher exists to prevent, so
                    // the identity of the network is the whole assertion.
                    if (!ssid.equals(connectedSsid(caps))) return;
                    ui.post(() -> finishAttempt("Connected to " + ssid
                            + ", and the internet is working.\n\nThe managed app should load "
                            + "now. You can tap Done.", true));
                }
            };
            try {
                cm.registerDefaultNetworkCallback(netCallback);
            } catch (Exception ignored) {
                netCallback = null;
            }
        }

        timeout = () -> {
            // Associated with the RIGHT network but no validated internet yet is a very
            // different situation from never having got on at all, and it must not be
            // reported as a failure: the tablet is on the network the user asked for.
            if (ssid.equals(currentSsid())) {
                finishAttempt("Connected to " + ssid + ", but the internet is not working "
                        + "yet.\n\nThe password was right. If the managed app does not load, the "
                        + "problem is the router's own internet connection, not the " + Flavor.DEVICE_NOUN + ".",
                        true);
            } else {
                finishAttempt("Still not connected.\n\nCheck the network name is exactly "
                        + "right, that the router is switched on and nearby, and try again.");
            }
        };
        ui.postDelayed(timeout, ATTEMPT_TIMEOUT_MS);
    }

    private void finishAttempt(String message) {
        finishAttempt(message, false);
    }

    private void finishAttempt(String message, boolean success) {
        if (!attempting) return;
        attempting = false;
        stopWatching();
        state.setText(message);
        joinButton.setEnabled(true);
        if (!success) recoverPreviousNetwork();
    }

    /**
     * Put the tablet back on whatever it was using before a failed attempt.
     *
     * Found by running the real thing: asking to join a network that is not in range does
     * NOT quietly fail, it drops the connection you already had, and the device does not
     * re-associate on its own within any useful time. So a mistyped SSID would take the person
     * on site from "working internet" to "no internet", which is a strictly worse position than
     * before they started, the exact opposite of what a recovery screen is for.
     *
     * Cycling the radio makes the framework re-run auto-join across saved networks and pick
     * the best one actually present. disconnect()/reconnect() is the obvious alternative and
     * does not work here: reconnect() has no Device Owner deprecation exemption and always
     * returns false. setWifiEnabled does.
     */
    private void recoverPreviousNetwork() {
        // NEVER tear down a connection to the network that was actually requested. The first
        // version skipped this check and cycled the radio on a join that had genuinely
        // succeeded, kicking the tablet back off the new network and onto the old one, a
        // recovery routine actively undoing the thing it was recovering.
        if (pendingSsid != null && pendingSsid.equals(currentSsid())) return;
        final WifiManager wm = getSystemService(WifiManager.class);
        if (wm == null) return;
        new Thread(() -> {
            try {
                wm.setWifiEnabled(false);
                Thread.sleep(2_000);
                wm.setWifiEnabled(true);
            } catch (Exception ignored) {
                // Best effort. Never let recovery throw on top of an already-failed join.
            }
        }).start();
    }

    private void stopWatching() {
        if (timeout != null) {
            ui.removeCallbacks(timeout);
            timeout = null;
        }
        if (wifiEvents != null) {
            try { unregisterReceiver(wifiEvents); } catch (Exception ignored) {}
            wifiEvents = null;
        }
        if (netCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            }
            netCallback = null;
        }
    }

    private boolean hasValidatedInternet() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // ---------------------------------------------------------------- scan list

    /**
     * Grant ourselves what scanning needs, kick off a FRESH scan, and render whatever is
     * cached meanwhile.
     *
     * Best effort, and deliberately so. getScanResults() needs ACCESS_FINE_LOCATION *and*
     * the system Location toggle on, with no Device Owner bypass, so as DO we grant
     * ourselves the permission and force Location on. If any step fails the list stays
     * empty and the typed path is unaffected.
     *
     * The first version of this only called getScanResults(), and on the tablet it listed
     * exactly one network (the one already connected) while a hotspot two feet away was
     * missing. Cached scan results can be minutes old, and the whole point of this screen is
     * that something about the network situation has just CHANGED. Asking for a new scan is
     * the difference between a useful list and a misleading one.
     */
    private void refreshScanList() {
        try {
            DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
            ComponentName admin = AdminReceiver.componentName(this);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                // Order matters: Location must be on BEFORE getScanResults, or the platform
                // throws rather than returning an empty list.
                try { dpm.setLocationEnabled(admin, true); } catch (Exception ignored) {}
                for (String p : new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION}) {
                    try {
                        dpm.setPermissionGrantState(admin, getPackageName(), p,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                    } catch (Exception ignored) {}
                }
            }

            WifiManager wm = getSystemService(WifiManager.class);
            if (wm == null) { hideScanList(); return; }

            // Results arrive asynchronously; render the cache now and again when they land.
            if (scanResults == null) {
                scanResults = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) { renderScanList(); }
                };
                registerReceiver(scanResults,
                        new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                        Context.RECEIVER_NOT_EXPORTED);
            }
            try {
                wm.startScan();
            } catch (Exception ignored) {
                // Throttled (four scans per two minutes per foreground app) or refused.
                // The cached list is still worth showing.
            }
            renderScanList();
        } catch (Exception e) {
            hideScanList();
        }
    }

    private void renderScanList() {
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            if (wm == null) { hideScanList(); return; }

            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) { hideScanList(); return; }

            scanBox.removeAllViews();
            scanLabel.setText("Nearby networks: tap one to fill in its name");

            // Strongest first, de-duplicated: a dual-band router shows up once per band and
            // a list with the same name three times is worse than no list.
            results.sort((a, b) -> Integer.compare(b.level, a.level));
            Set<String> seen = new LinkedHashSet<>();
            List<String> names = new ArrayList<>();
            for (ScanResult r : results) {
                // Do NOT trim the SSID. A trailing space is legal and real networks have one,
                // and trimming it would put a name into the field that no access point
                // answers to, producing a "network not found" failure on a network sitting
                // right there in the list. Trim only to decide whether it is blank.
                String name = r.SSID == null ? "" : r.SSID;
                if (name.trim().isEmpty() || !seen.add(name)) continue;
                names.add(name);
                if (names.size() >= 12) break;
            }
            if (names.isEmpty()) { hideScanList(); return; }

            for (String name : names) {
                Button b = new Button(this);
                b.setText(name);
                b.setAllCaps(false);
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                b.setOnClickListener(v -> {
                    ssidField.setText(name);
                    passField.requestFocus();
                });
                scanBox.addView(b);
            }
        } catch (Exception e) {
            hideScanList();
        }
    }

    private void hideScanList() {
        scanLabel.setText("Nearby networks could not be listed. Type the network name above.");
        scanBox.removeAllViews();
    }

    /**
     * SSID of the network those capabilities describe. Read from the capabilities' own
     * TransportInfo rather than from WifiManager.getConnectionInfo(), so it answers "what is
     * THIS network" instead of "what is the device connected to right now": during a
     * handover those are different, which is how the false success got through.
     */
    private String connectedSsid(NetworkCapabilities caps) {
        if (Build.VERSION.SDK_INT >= 29) {
            Object info = caps.getTransportInfo();
            if (info instanceof WifiInfo) return stripQuotes(((WifiInfo) info).getSSID());
        }
        return currentSsid();
    }

    /**
     * Is this SSID in the latest scan? A hidden network never is, so treat hidden as
     * "visible" rather than telling the user their network cannot be found when they have
     * just told us it is invisible by design.
     */
    private boolean visibleNearby(String ssid) {
        if (hiddenBox.isChecked()) return true;
        try {
            WifiManager wm = getSystemService(WifiManager.class);
            if (wm == null) return true;
            List<ScanResult> results = wm.getScanResults();
            if (results == null || results.isEmpty()) return true;  // unknown, do not accuse
            for (ScanResult r : results) {
                if (r.SSID != null && r.SSID.equals(ssid)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;  // never turn a diagnostic into a false accusation
        }
    }

    private String currentSsid() {
        WifiManager wm = getSystemService(WifiManager.class);
        if (wm == null) return null;
        WifiInfo info = wm.getConnectionInfo();
        return info == null ? null : stripQuotes(info.getSSID());
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
