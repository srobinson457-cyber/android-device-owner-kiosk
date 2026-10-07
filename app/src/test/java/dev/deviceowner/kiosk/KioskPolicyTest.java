package dev.deviceowner.kiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * The relock path: the order of its steps, the maintenance marker that lets a failed relock be
 * retried (only a relock in which every step took clears it, whatever the armed flag says),
 * and lockDebugging's report.
 */
public class KioskPolicyTest {

    private static final long OPENED = 1_000_000L;
    private static final long EXPIRED = OPENED + KioskPolicy.LOCAL_MAINTENANCE_TTL_MS;

    private final FakePrefs prefs = new FakePrefs();
    private final List<String> calls = new ArrayList<>();

    private Supplier<KioskPolicy.Step> step(String name) {
        return () -> { calls.add(name); return new KioskPolicy.Step(true, name); };
    }

    /** A step that ran but did not apply everything it should have. */
    private Supplier<KioskPolicy.Step> failedStep(String name) {
        return () -> {
            calls.add(name);
            return new KioskPolicy.Step(false, name + " failed=[SecurityException]");
        };
    }

    /** Stands in for arm(): on success it records armed=true, as the real one does. */
    private Supplier<KioskPolicy.Step> arm(boolean succeeds) {
        return () -> {
            calls.add("arm");
            if (!succeeds) {
                return new KioskPolicy.Step(false, "ARM FAILED: java.lang.SecurityException");
            }
            prefs.edit().putBoolean(KioskPolicy.KEY_ARMED, true).apply();
            return new KioskPolicy.Step(true, "armed=true");
        };
    }

    private Supplier<String> relock(boolean armSucceeds) {
        return () -> KioskPolicy.relock(prefs, step("lockInstall"), arm(armSucceeds),
                step("lockDebugging"));
    }

    private void openMaintenance() {
        prefs.edit().putLong(KioskPolicy.KEY_MAINT_OPENED, OPENED)
                .putBoolean(KioskPolicy.KEY_ARMED, false).apply();
    }

    /**
     * Maintenance opened, but arm(false) threw after the debugging and install locks were
     * lifted, so armed is still true. Also the state after an `arm` sent over ADB.
     */
    private void openMaintenanceButStillArmed() {
        prefs.edit().putLong(KioskPolicy.KEY_MAINT_OPENED, OPENED)
                .putBoolean(KioskPolicy.KEY_ARMED, true).apply();
    }

    @Test
    public void relockRunsStepsInHandoverOrder() {
        String log = KioskPolicy.relock(prefs, step("lockInstall"), arm(true),
                step("lockDebugging"));
        assertEquals(Arrays.asList("lockInstall", "arm", "lockDebugging"), calls);
        assertEquals("lockInstall\narmed=true\nlockDebugging", log);
    }

    @Test
    public void openWindowIsLeftAlone() {
        openMaintenance();
        assertFalse(KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED - 1, relock(true)));
        assertTrue(calls.isEmpty());
        assertEquals(OPENED, prefs.getLong(KioskPolicy.KEY_MAINT_OPENED, 0L));
    }

    @Test
    public void expiredWindowRelocksAndClearsMarker() {
        openMaintenance();
        assertTrue(KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED, relock(true)));
        assertEquals(Arrays.asList("lockInstall", "arm", "lockDebugging"), calls);
        assertFalse(prefs.contains(KioskPolicy.KEY_MAINT_OPENED));
    }

    @Test
    public void markerIsKeptWhenArmFails() {
        openMaintenance();
        KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED, relock(false));
        assertEquals("a failed re-arm must leave the marker so the next start retries",
                OPENED, prefs.getLong(KioskPolicy.KEY_MAINT_OPENED, 0L));
    }

    @Test
    public void failedRelockIsRetriedOnNextStart() {
        openMaintenance();
        KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED, relock(false));
        calls.clear();
        assertTrue(KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED + 1, relock(true)));
        assertEquals(Arrays.asList("lockInstall", "arm", "lockDebugging"), calls);
        assertFalse(prefs.contains(KioskPolicy.KEY_MAINT_OPENED));
    }

    @Test
    public void expiredWindowRelocksEvenWhenStillArmed() {
        openMaintenanceButStillArmed();
        assertTrue("armed=true must not stop the expiry relock",
                KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED, relock(true)));
        assertEquals(Arrays.asList("lockInstall", "arm", "lockDebugging"), calls);
        assertFalse(prefs.contains(KioskPolicy.KEY_MAINT_OPENED));
    }

    @Test
    public void openWindowKeepsMarkerEvenWhenStillArmed() {
        openMaintenanceButStillArmed();
        assertFalse(KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED - 1, relock(true)));
        assertTrue("no relock inside the hour: " + calls, calls.isEmpty());
        assertEquals("armed=true must not drop the marker",
                OPENED, prefs.getLong(KioskPolicy.KEY_MAINT_OPENED, 0L));
    }

    @Test
    public void markerIsKeptWhenArmFailsWhileStillArmed() {
        openMaintenanceButStillArmed();
        KioskPolicy.expireMaintenanceIfStale(prefs, EXPIRED, relock(false));
        assertEquals("a failed re-arm must keep the marker even if armed was already true",
                OPENED, prefs.getLong(KioskPolicy.KEY_MAINT_OPENED, 0L));
    }

    @Test
    public void markerIsKeptWhenLockInstallFails() {
        openMaintenance();
        KioskPolicy.relock(prefs, failedStep("lockInstall"), arm(true), step("lockDebugging"));
        assertEquals("a relock that left installs unlocked must keep the marker",
                OPENED, prefs.getLong(KioskPolicy.KEY_MAINT_OPENED, 0L));
    }

    @Test
    public void markerIsKeptWhenLockDebuggingFails() {
        openMaintenance();
        KioskPolicy.RestrictionSetter refusesUsb = (key, on) -> {
            if (key.equals("no_usb_file_transfer")) throw new SecurityException("refused");
        };
        KioskPolicy.relock(prefs, step("lockInstall"), arm(true),
                () -> KioskPolicy.lockDebugging(refusesUsb, true));
        assertEquals("a relock that left debugging unlocked must keep the marker",
                OPENED, prefs.getLong(KioskPolicy.KEY_MAINT_OPENED, 0L));
    }

    @Test
    public void lockDebuggingNamesFailedRestriction() {
        KioskPolicy.Step s = KioskPolicy.lockDebugging((key, on) -> {
            if (key.equals("no_usb_file_transfer")) throw new SecurityException("refused");
        }, true);
        String r = s.toString();
        assertFalse("a partial lock must not count as done: " + r, s.ok);
        assertFalse("must not claim success: " + r, r.contains("adb is gone"));
        assertTrue("must name the failed restriction: " + r,
                r.contains("failed=[no_usb_file_transfer(SecurityException)]"));
    }

    @Test
    public void lockDebuggingReportsIncompleteUnlock() {
        KioskPolicy.Step s = KioskPolicy.lockDebugging((key, on) -> {
            if (key.equals("no_debugging_features")) throw new SecurityException("refused");
        }, false);
        String r = s.toString();
        assertFalse("a partial unlock must not count as done: " + r, s.ok);
        assertTrue("a failed unlock must say so: " + r, r.contains("UNLOCK INCOMPLETE"));
        assertFalse("must not claim success: " + r, r.contains("re-enabled"));
        assertTrue("must name the failed restriction: " + r,
                r.contains("failed=[no_debugging_features(SecurityException)]"));
    }

    @Test
    public void lockDebuggingReportsSuccessWhenBothApply() {
        List<String> applied = new ArrayList<>();
        KioskPolicy.Step s =
                KioskPolicy.lockDebugging((key, on) -> applied.add(key + "=" + on), true);
        String r = s.toString();
        assertEquals(Arrays.asList("no_debugging_features=true", "no_usb_file_transfer=true"),
                applied);
        assertTrue("both applied, so the step is done: " + r, s.ok);
        assertTrue(r, r.contains("DISABLED (adb is gone)"));
        assertTrue(r, r.contains("failed=[]"));
    }
}
