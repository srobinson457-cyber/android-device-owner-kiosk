package dev.deviceowner.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Everything a kiosk shell does regardless of what it displays: hold lock task, poll the
 * remote policy, and gate administrator access.
 *
 * Extracted from KioskActivity when the phone flavor was added. It is deliberately shared
 * rather than duplicated: the corner-tap gesture, the challenge/response gate and the
 * lock-task re-assert are the security boundary of this whole project, and two copies of a
 * security boundary drift. The flavors differ only in what fills the screen.
 *
 * Subclasses supply the content view in onCreate BEFORE calling through, or after - the shell
 * touches no views of its own; the corner gesture reads the decor view, so it works over any
 * layout.
 */
public abstract class KioskShellActivity extends Activity {

    private static final String PREFS = KioskPolicy.PREFS;
    private static final String KEY_ARMED = KioskPolicy.KEY_ARMED;

    private static final int CORNER_TAPS_REQUIRED = 7;
    private static final long CORNER_TAP_WINDOW_MS = 2500L;

    /**
     * Policy polling runs on a Handler tied to this always-foreground activity rather than a
     * foreground service, on purpose: Android 15 blocks dataSync FGS from BOOT_COMPLETED and
     * cancels PendingIntents when an app is force-stopped, which breaks the usual kiosk
     * watchdog patterns outright. The kiosk IS the foreground, so a Handler is both simpler
     * and immune to both changes.
     */
    private static final long POLL_INTERVAL_MS = 10 * 60_000L;

    private final Handler poller = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() {
        @Override
        public void run() {
            RemotePolicy.pollAsync(KioskShellActivity.this);
            poller.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private int cornerTaps;
    private long lastCornerTapMs;

    @Override
    protected void onResume() {
        super.onResume();
        maybeStartLockTask();
        poller.removeCallbacks(pollTick);
        poller.post(pollTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        poller.removeCallbacks(pollTick);
    }

    protected void maybeStartLockTask() {
        // Before anything else: if maintenance mode was left open and has gone stale, close
        // it. This has to run BEFORE the armed check, because the whole point is that
        // maintenance mode sets armed=false and that state survives reboots.
        KioskPolicy.expireMaintenanceIfStale(this);

        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ARMED, false)) {
            return; // not armed yet
        }
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
            return;
        }
        // Re-assert the armed feature set on every launch. This is what expires a temporary
        // power-off grant: setLockTaskFeatures is persistent policy, so without this, one tap
        // of "Shut down" would leave the power menu available to the device user forever.
        KioskPolicy.reassertLockTaskFeatures(this);
        try {
            startLockTask();
        } catch (IllegalArgumentException ignored) {
            // Not allowlisted for lock task - policy has not been applied yet.
        }
    }

    /**
     * Corner-tap admin gesture, detected in dispatchTouchEvent rather than with an overlay
     * view so that it never steals a touch from the content underneath.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            View decor = getWindow().getDecorView();
            boolean inCorner = ev.getX() < decor.getWidth() * 0.15f
                    && ev.getY() < decor.getHeight() * 0.08f;
            long now = SystemClock.elapsedRealtime();
            if (inCorner) {
                if (now - lastCornerTapMs > CORNER_TAP_WINDOW_MS) {
                    cornerTaps = 0;
                }
                lastCornerTapMs = now;
                if (++cornerTaps >= CORNER_TAPS_REQUIRED) {
                    cornerTaps = 0;
                    openAdmin();
                    return true;
                }
            } else {
                cornerTaps = 0;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * Admin entry. Three ways in, in order of preference:
     *  1. Remote maintenance flag from the policy Worker - no secret travels at all.
     *  2. Offline challenge/response - works with the network down, which is exactly when a
     *     stuck device is hardest to reach.
     *  3. Nothing. There is deliberately no "forgot it" path on the device.
     */
    protected void openAdmin() {
        if (RemotePolicy.isMaintenanceActive(this)) {
            startActivity(new Intent(this, ProbeActivity.class));
            return;
        }

        long locked = AdminGate.lockedForMs(this);
        if (locked > 0) {
            new AlertDialog.Builder(this)
                    .setTitle("Locked")
                    .setMessage("Too many attempts. Try again in " + (locked / 1000) + "s.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final String challenge = AdminGate.newChallenge();
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("6-digit response");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 32, 48, 8);
        TextView label = new TextView(this);
        label.setTextSize(18f);
        label.setText("Challenge\n\n" + challenge
                + "\n\nRead this number to the administrator and enter the code they give back.");
        box.addView(label);
        box.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Administrator access")
                .setView(box)
                .setPositiveButton("Unlock", null)   // set below so a bad code does not dismiss
                .setNegativeButton("Cancel", null)
                .create();

        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String err = AdminGate.verify(this, input.getText().toString());
            if (err == null) {
                dlg.dismiss();
                startActivity(new Intent(this, ProbeActivity.class));
            } else {
                Toast.makeText(this, err, Toast.LENGTH_SHORT).show();
                input.setText("");
            }
        }));
        dlg.show();
    }

    /** Back never leaves the shell. Subclasses may override to consume it usefully. */
    @Override
    public void onBackPressed() {
        // deliberately nothing
    }
}
