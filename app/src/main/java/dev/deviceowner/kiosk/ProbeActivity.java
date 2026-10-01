package dev.deviceowner.kiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The administrator console, reached only through AdminGate.
 *
 * This screen began life as a phase-1 probe that reported whether Device Owner had taken,
 * and for a while that is all it was. That turned out to be a design fault with teeth: at
 * handover the real control surface was AdminCommandReceiver, which is driven over ADB,
 * and the last step of handover is lockDebugging() - which removes ADB. So every lever
 * that could fix the tablet became unreachable at exactly the moment the tablet went
 * somewhere unreachable, and the only on-device button left was "Clear Device Owner".
 *
 * That was discovered when the tablet needed to join a different site's Wi-Fi and there
 * was no way to do it short of dismantling the entire lockdown. The lesson generalises:
 * an escape hatch whose only setting is "destroy everything" is not an escape hatch, it is
 * a fuse. This screen now carries the same commands AdminCommandReceiver exposes, so a
 * fix needs a phone call rather than a car journey.
 *
 * Everything here sits behind the HMAC challenge/response, which a casual user cannot pass and
 * which does not leak by being watched - so exposing real controls here does not weaken
 * the lockdown. The destructive one is still last, still separated, and now confirms.
 */
public class ProbeActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#001b3d"));
        root.setPadding(64, 56, 64, 56);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(17f);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The reason this screen exists for anyone other than the operator. First, and
        // labelled in the words the person holding the tablet would use.
        addButton(root, "Join a different Wi-Fi network",
                () -> startActivity(new Intent(this, WifiSetupActivity.class)));
        addButton(root, "Shut down the " + Flavor.DEVICE_NOUN, this::allowPowerOff);

        addHeading(root, "Maintenance");

        addButton(root, "Open maintenance mode (restores ADB)", this::openMaintenance);
        addButton(root, "Open Android Settings", this::openSettings);
        addButton(root, "Re-lock the kiosk", this::relock);
        addButton(root, "Check for a policy update",
                () -> { RemotePolicy.pollAsync(this); toast("Policy check requested"); });
        addButton(root, "Refresh this screen", this::render);

        addHeading(root, "Last resort");

        addButton(root, "Clear Device Owner", this::confirmClearDeviceOwner);

        TextView note = new TextView(this);
        note.setTextColor(Color.parseColor("#FF9E9E"));
        note.setTextSize(14f);
        note.setPadding(0, 12, 0, 0);
        note.setText("Clearing Device Owner removes the whole lockdown and cannot be undone "
                + "from this " + Flavor.DEVICE_NOUN + " - putting it back needs a computer and a USB cable. "
                + "Use maintenance mode instead unless someone has told you otherwise.");
        root.addView(note);

        ScrollView scroller = new ScrollView(this);
        // Background belongs on the scroller, not the content: the LinearLayout is only as
        // tall as its children, which leaves the rest of the screen system grey.
        scroller.setBackgroundColor(Color.parseColor("#001b3d"));
        scroller.addView(root);
        setContentView(scroller);

        // Edge-to-edge is enforced at targetSdk 35, so nothing is inset for us and the first
        // line of text ends up under the status bar.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            scroller.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void addHeading(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.parseColor("#9FC4FF"));
        t.setTextSize(15f);
        t.setPadding(0, 28, 0, 4);
        parent.addView(t);
    }

    private void addButton(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * The tablet could not be switched off at all - holding the buttons only forces a power
     * cycle, and it comes straight back. Fine day to day, not fine when it has to go in for a
     * screen repair, or go in a drawer for a month.
     *
     * There is no public shutdown API to call, so this hands the power menu back rather than
     * pretending to do it in one tap. The grant lasts until the kiosk next starts.
     */
    private void allowPowerOff() {
        String r = KioskPolicy.allowPowerOff(this);
        new AlertDialog.Builder(this)
                .setTitle("Shut down the " + Flavor.DEVICE_NOUN)
                .setMessage("Now hold the Power button for a few seconds and choose "
                        + "\"Power off\".\n\nWhen you switch the " + Flavor.DEVICE_NOUN + " back on it returns to "
                        + Flavor.KIOSK_SUBJECT + " as normal, and the power menu turns itself back off - "
                        + "so you will need to come back here to shut it down again.")
                .setPositiveButton("OK", null)
                .show();
        toast(r);
    }

    // ---------------------------------------------------------------- maintenance

    /**
     * Everything needed to get a laptop talking to this tablet again, without giving up
     * Device Owner. Three things have to be true and each is separately non-obvious:
     *
     *  - DISALLOW_DEBUGGING_FEATURES and DISALLOW_USB_FILE_TRANSFER both have to go. The
     *    second is the one people miss: it kills the ADB data connection once USB
     *    re-enumerates, and it presents as "device unauthorized" with no prompt.
     *  - Lock task has to end, because the "Allow USB debugging?" prompt is an ACTIVITY,
     *    and lock task refuses to start activities outside the allowlist. That is what
     *    made the August lockout unrecoverable by ADB.
     *  - ADB_ENABLED itself has to be set back to 1. Lifting the restriction does not do
     *    it: the platform writes the setting to 0 when the restriction is applied and has
     *    no branch that writes it back. We try the Device Owner path and fall back to
     *    telling the human where the toggle is.
     */
    private void openMaintenance() {
        // Start the clock. If nobody taps "Re-lock the kiosk", the kiosk closes itself on a
        // later start rather than staying open forever.
        KioskPolicy.markMaintenanceOpened(this);
        StringBuilder log = new StringBuilder();
        log.append(KioskPolicy.lockDebugging(this, false)).append('\n');
        log.append(KioskPolicy.lockInstall(this, false)).append('\n');
        log.append(KioskPolicy.arm(this, false)).append('\n');

        boolean adbSet = false;
        try {
            DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
            ComponentName admin = AdminReceiver.componentName(this);
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1");
                adbSet = Settings.Global.getInt(
                        getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
            }
        } catch (Exception ignored) {
            // The allowlist for setGlobalSetting has shrunk over the years and ADB_ENABLED
            // may no longer be on it. Not fatal - it is a convenience, not the mechanism.
        }
        log.append("adbEnabled=").append(adbSet);

        new AlertDialog.Builder(this)
                .setTitle("Maintenance mode")
                .setMessage((adbSet
                        ? "The kiosk is unlocked and USB debugging is on.\n\nPlug the " + Flavor.DEVICE_NOUN + " "
                          + "into the computer and accept the prompt if one appears."
                        : "The kiosk is unlocked.\n\nTurn on USB debugging by hand: Settings "
                          + "> About " + Flavor.DEVICE_NOUN + " > Software information > tap Build number seven "
                          + "times, then Developer options > USB debugging.")
                        + "\n\nWhile it is unlocked you can also use \"Open Android Settings\" "
                        + "to change the brightness, pair headphones, or change the text "
                        + "size.\n\nTap \"Re-lock the kiosk\" when you are finished. If you "
                        + "forget, it locks itself again within an hour.")
                .setPositiveButton("OK", null)
                .show();

        toast(log.toString());
        render();
    }

    /**
     * There is no launcher on this device - this app owns HOME - so Settings has to be
     * launched from here or not at all. Only works once lock task has ended, which is why
     * it says so rather than failing silently.
     */
    private void openSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("Settings refused to open - open maintenance mode first.");
        }
    }

    private void relock() {
        StringBuilder log = new StringBuilder();
        log.append(KioskPolicy.lockInstall(this, true)).append('\n');
        log.append(KioskPolicy.arm(this, true)).append('\n');
        // Debugging goes last, exactly as at handover: it is the step that removes our own
        // way back in, so nothing may fail after it.
        log.append(KioskPolicy.lockDebugging(this, true));
        toast(log.toString());
        render();
    }

    // ---------------------------------------------------------------- status

    private void render() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName admin = AdminReceiver.componentName(this);

        boolean adminActive = dpm != null && dpm.isAdminActive(admin);
        boolean deviceOwner = dpm != null && dpm.isDeviceOwnerApp(getPackageName());

        int provisioned = Settings.Global.getInt(
                getContentResolver(), "device_provisioned", -1);
        int userSetup = Settings.Secure.getInt(
                getContentResolver(), "user_setup_complete", -1);
        int adbEnabled = Settings.Global.getInt(
                getContentResolver(), Settings.Global.ADB_ENABLED, -1);

        status.setText(
                (deviceOwner ? "DEVICE OWNER: YES" : "DEVICE OWNER: no")
                        + "\n\npackage: " + getPackageName()
                        + "\nadmin active: " + adminActive
                        + "\ndevice_provisioned: " + provisioned
                        + "\nuser_setup_complete: " + userSetup
                        + "\nadb_enabled: " + adbEnabled
                        + "\n\nSDK " + android.os.Build.VERSION.SDK_INT
                        + "  " + android.os.Build.MODEL);
        status.setTextColor(deviceOwner ? Color.parseColor("#7CFFB2") : Color.WHITE);
    }

    // ---------------------------------------------------------------- last resort

    /**
     * Confirmation added when this screen gained neighbours. As the only button here it
     * was merely blunt; sitting one row below "Check for a policy update" it is a trap,
     * and the person most likely to reach for it is a well-meaning helper who has been
     * told the tablet is "stuck".
     */
    private void confirmClearDeviceOwner() {
        new AlertDialog.Builder(this)
                .setTitle("Remove the whole lockdown?")
                .setMessage("This turns the " + Flavor.DEVICE_NOUN + " back into an ordinary " + Flavor.DEVICE_NOUN + ": every app "
                        + "returns, the restrictions come off, and " + Flavor.KIOSK_SUBJECT + " stops being "
                        + "the only thing it can run.\n\nIt cannot be undone from this "
                        + Flavor.DEVICE_NOUN + ". Putting the lockdown back needs a computer, a USB cable, "
                        + "and removing every account from the device first.\n\nIf you are "
                        + "trying to fix Wi-Fi or install an update, cancel and use "
                        + "maintenance mode instead.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove it", (d, w) -> clearDeviceOwner())
                .show();
    }

    private void clearDeviceOwner() {
        DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) {
            Toast.makeText(this, "Not device owner - nothing to clear", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            dpm.clearDeviceOwnerApp(getPackageName());
            Toast.makeText(this, "Device Owner cleared", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Clear failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        render();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
