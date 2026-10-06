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
 * retried, and lockDebugging's report.
 */
public class KioskPolicyTest {

    private static final long OPENED = 1_000_000L;
    private static final long EXPIRED = OPENED + KioskPolicy.LOCAL_MAINTENANCE_TTL_MS;

    private final FakePrefs prefs = new FakePrefs();
    private final List<String> calls = new ArrayList<>();

    private Supplier<String> step(String name) {
        return () -> { calls.add(name); return name; };
    }

    /** Stands in for arm(): on success it records armed=true, as the real one does. */
    private Supplier<String> arm(boolean succeeds) {
        return () -> {
            calls.add("arm");
            if (!succeeds) return "ARM FAILED: java.lang.SecurityException";
            prefs.edit().putBoolean(KioskPolicy.KEY_ARMED, true).apply();
            return "armed=true";
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
    public void lockDebuggingNamesFailedRestriction() {
        String r = KioskPolicy.lockDebugging((key, on) -> {
            if (key.equals("no_usb_file_transfer")) throw new SecurityException("refused");
        }, true);
        assertFalse("must not claim success: " + r, r.contains("adb is gone"));
        assertTrue("must name the failed restriction: " + r,
                r.contains("failed=[no_usb_file_transfer(SecurityException)]"));
    }

    @Test
    public void lockDebuggingReportsIncompleteUnlock() {
        String r = KioskPolicy.lockDebugging((key, on) -> {
            if (key.equals("no_debugging_features")) throw new SecurityException("refused");
        }, false);
        assertTrue("a failed unlock must say so: " + r, r.contains("UNLOCK INCOMPLETE"));
        assertFalse("must not claim success: " + r, r.contains("re-enabled"));
        assertTrue("must name the failed restriction: " + r,
                r.contains("failed=[no_debugging_features(SecurityException)]"));
    }

    @Test
    public void lockDebuggingReportsSuccessWhenBothApply() {
        List<String> applied = new ArrayList<>();
        String r = KioskPolicy.lockDebugging((key, on) -> applied.add(key + "=" + on), true);
        assertEquals(Arrays.asList("no_debugging_features=true", "no_usb_file_transfer=true"),
                applied);
        assertTrue(r, r.contains("DISABLED (adb is gone)"));
        assertTrue(r, r.contains("failed=[]"));
    }
}
