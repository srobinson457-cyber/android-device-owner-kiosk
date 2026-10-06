package dev.deviceowner.kiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

/**
 * AdminGate.verify(): the offline gate in front of the admin screen. Uses the overload that
 * takes the preferences and both clocks, because SystemClock returns 0 off-device.
 */
public class AdminGateVerifyTest {

    private static final String SECRET = "00112233445566778899aabbccddeeff";
    private static final long ISSUED = 10_000L;               // elapsed-realtime clock
    private static final long WALL = 1_800_000_000_000L;      // wall clock
    private static final long FIVE_MIN = 5 * 60_000L;

    private final FakePrefs prefs = new FakePrefs();
    private String challenge;

    @Before
    public void provisionAndIssueChallenge() {
        prefs.edit().putString(AdminGate.KEY_SECRET, SECRET).apply();
        challenge = AdminGate.newChallenge(ISSUED);
    }

    private String right() {
        return AdminGate.respond(SECRET, challenge);
    }

    private String wrong() {
        return right().equals("000000") ? "111111" : "000000";
    }

    @Test
    public void correctResponseUnlocksAndResetsFailCount() {
        prefs.edit().putInt(AdminGate.KEY_FAILS, 3).apply();
        assertNull(AdminGate.verify(prefs, right(), ISSUED + 1_000, WALL));
        assertFalse("fail count must reset on success", prefs.contains(AdminGate.KEY_FAILS));
    }

    @Test
    public void fifthWrongResponseLocksForFiveMinutesAndClearsChallenge() {
        for (int i = 1; i <= 4; i++) {
            assertEquals("Incorrect (" + i + "/5)",
                    AdminGate.verify(prefs, wrong(), ISSUED + i, WALL));
        }
        assertEquals("Too many attempts: locked 5 min",
                AdminGate.verify(prefs, wrong(), ISSUED + 5, WALL));
        assertEquals(FIVE_MIN, AdminGate.lockedForMs(prefs, WALL));

        // While locked, even the right answer is refused.
        assertEquals("Locked for 300s", AdminGate.verify(prefs, right(), ISSUED + 6, WALL));
        // Once the lockout ends, the challenge it cleared cannot be answered.
        assertEquals("Challenge expired: reopen",
                AdminGate.verify(prefs, right(), ISSUED + 7, WALL + FIVE_MIN));
    }

    @Test
    public void expiredChallengeIsRefused() {
        assertEquals("Challenge expired: reopen",
                AdminGate.verify(prefs, right(), ISSUED + FIVE_MIN + 1, WALL));
        // The same challenge is still good at exactly five minutes, so the refusal above was
        // the expiry and not a missing challenge.
        assertNull(AdminGate.verify(prefs, right(), ISSUED + FIVE_MIN, WALL));
    }

    @Test
    public void missingSecretIsRefused() {
        prefs.edit().remove(AdminGate.KEY_SECRET).apply();
        assertEquals("No admin secret provisioned on this device",
                AdminGate.verify(prefs, "123456", ISSUED + 1, WALL));
    }
}
