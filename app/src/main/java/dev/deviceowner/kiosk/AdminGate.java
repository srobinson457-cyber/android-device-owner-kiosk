package dev.deviceowner.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Challenge/response gate for the admin screen.
 *
 * Why not a PIN: the device lives at a site the operator rarely visits, and a static PIN typed in front of a
 * motivated user is a leaked PIN - permanently, with no way for us to notice or rotate it
 * remotely. Instead the tablet shows a random 6-digit CHALLENGE and demands the matching
 * RESPONSE, which is HMAC-SHA256(deviceSecret, challenge) truncated HOTP-style. Watching
 * someone type a response teaches you nothing, because the next challenge is different.
 *
 * The device secret is provisioned once over ADB at setup and never displayed on the tablet.
 * It lives in app-private SharedPreferences: on a locked-bootloader, non-rooted device whose
 * only job is one app, app-private storage is the right level of protection - the threat
 * model is a determined user, not a forensic lab.
 *
 * A remote "maintenance" flag from the policy Worker unlocks the same screen without any
 * code at all, and is the primary path; this is the offline fallback for when the network
 * is down, which is exactly when a locked-out tablet is least reachable.
 */
public final class AdminGate {

    private static final String TAG = "DOKiosk";
    private static final String PREFS = KioskPolicy.PREFS;
    private static final String KEY_SECRET = "admin_secret";
    private static final String KEY_FAILS = "admin_fails";
    private static final String KEY_LOCKED_UNTIL = "admin_locked_until";

    /** Challenges expire so a response overheard today is useless tomorrow. */
    private static final long CHALLENGE_TTL_MS = 5 * 60_000L;
    private static final int MAX_FAILS = 5;
    private static final long LOCKOUT_MS = 5 * 60_000L;

    private static String activeChallenge;
    private static long challengeIssuedAt;

    private AdminGate() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasSecret(Context c) {
        String s = prefs(c).getString(KEY_SECRET, null);
        return s != null && !s.isEmpty();
    }

    static String setSecret(Context c, String hex) {
        if (hex == null || hex.length() < 32) {
            return "REFUSED: secret must be >= 32 hex chars";
        }
        prefs(c).edit().putString(KEY_SECRET, hex).remove(KEY_FAILS)
                .remove(KEY_LOCKED_UNTIL).apply();
        return "admin secret set (" + hex.length() + " hex chars)";
    }

    /** Issue a fresh challenge. Random, not time-derived, so it cannot be precomputed. */
    static String newChallenge() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        int n = ((b[0] & 0x7f) << 24) | ((b[1] & 0xff) << 16)
                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
        activeChallenge = String.format("%06d", n % 1_000_000);
        challengeIssuedAt = SystemClock.elapsedRealtime();
        return activeChallenge;
    }

    static long lockedForMs(Context c) {
        long until = prefs(c).getLong(KEY_LOCKED_UNTIL, 0L);
        long now = System.currentTimeMillis();
        return until > now ? until - now : 0L;
    }

    /** @return null on success, otherwise a human-readable reason. */
    static String verify(Context c, String response) {
        long lock = lockedForMs(c);
        if (lock > 0) {
            return "Locked for " + (lock / 1000) + "s";
        }
        if (activeChallenge == null
                || SystemClock.elapsedRealtime() - challengeIssuedAt > CHALLENGE_TTL_MS) {
            return "Challenge expired - reopen";
        }
        String secret = prefs(c).getString(KEY_SECRET, null);
        if (secret == null) {
            // Never fail open. An unprovisioned secret must not mean "anyone may in".
            return "No admin secret provisioned on this device";
        }

        String expected = respond(secret, activeChallenge);
        if (expected == null) return "Internal error";

        if (constantTimeEquals(expected, response == null ? "" : response.trim())) {
            prefs(c).edit().remove(KEY_FAILS).remove(KEY_LOCKED_UNTIL).apply();
            activeChallenge = null;
            Log.i(TAG, "admin gate: unlocked");
            return null;
        }

        int fails = prefs(c).getInt(KEY_FAILS, 0) + 1;
        SharedPreferences.Editor e = prefs(c).edit().putInt(KEY_FAILS, fails);
        String msg = "Incorrect (" + fails + "/" + MAX_FAILS + ")";
        if (fails >= MAX_FAILS) {
            e.putLong(KEY_LOCKED_UNTIL, System.currentTimeMillis() + LOCKOUT_MS).putInt(KEY_FAILS, 0);
            msg = "Too many attempts - locked 5 min";
            activeChallenge = null;
        }
        e.apply();
        Log.i(TAG, "admin gate: " + msg);
        return msg;
    }

    /**
     * HOTP-style truncation of HMAC-SHA256(secret, challenge) to 6 digits.
     * Any off-device responder you build must mirror this exactly.
     */
    static String respond(String secretHex, String challenge) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretHex.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal(challenge.getBytes(StandardCharsets.UTF_8));
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24)
                    | ((h[off + 1] & 0xff) << 16)
                    | ((h[off + 2] & 0xff) << 8)
                    | (h[off + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            Log.e(TAG, "hmac failed", e);
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
