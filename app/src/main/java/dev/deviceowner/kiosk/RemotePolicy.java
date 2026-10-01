package dev.deviceowner.kiosk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Remote management channel. The tablet lives at a remote site, so this is the only way to
 * change anything after handover without a car journey.
 *
 * Deliberately modelled on an earlier command-poller of mine, whose design decisions were
 * paid for once already:
 *  - authenticate with a per-DEVICE secret, never a user session, so the tablet can never
 *    orphan or refresh someone's auth token;
 *  - idempotent via a monotonically advancing stamp, so a repeated poll cannot re-apply;
 *  - FAIL-SAFE on any network or parse error - leave enforcement exactly as it is and retry
 *    on the next tick. For this kiosk fail-safe means STAYS LOCKED; the human escape is
 *    AdminGate's offline challenge/response, never a network timeout.
 *
 * The payload is HMAC-signed with the same device secret rather than trusted on HTTPS alone,
 * because this document can unlock the device: DNS at an unknown site is not something we
 * control, and a plain TLS fetch would make the unlock only as strong as the resolver.
 */
public final class RemotePolicy {

    private static final String TAG = "DOKiosk";
    private static final String PREFS = KioskPolicy.PREFS;

    private static final String KEY_URL = "policy_url";
    private static final String KEY_STAMP = "policy_stamp";
    private static final String KEY_MAINT_UNTIL = "maintenance_until";
    private static final String KEY_LAST_POLL = "policy_last_poll";

    /** Clock skew guard: a maintenance window more than a day out is treated as bogus. */
    private static final long MAX_MAINTENANCE_MS = 24 * 60 * 60_000L;

    private static final AtomicBoolean inFlight = new AtomicBoolean(false);

    private RemotePolicy() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String setUrl(Context c, String url) {
        if (url == null || !url.startsWith("https://")) return "REFUSED: https required";
        prefs(c).edit().putString(KEY_URL, url).apply();
        return "policy url set: " + url;
    }

    static boolean isMaintenanceActive(Context c) {
        return prefs(c).getLong(KEY_MAINT_UNTIL, 0L) > System.currentTimeMillis();
    }

    static String status(Context c) {
        SharedPreferences p = prefs(c);
        long until = p.getLong(KEY_MAINT_UNTIL, 0L);
        return "url=" + p.getString(KEY_URL, "(unset)")
                + "\nstamp=" + p.getLong(KEY_STAMP, -1)
                + "\nlastPoll=" + p.getLong(KEY_LAST_POLL, 0)
                + "\nmaintenanceActive=" + (until > System.currentTimeMillis())
                + " until=" + until;
    }

    /** Fire a poll on a background thread; no-ops if one is already running. */
    static void pollAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            if (!inFlight.compareAndSet(false, true)) return;
            try {
                poll(app);
            } catch (Exception e) {
                // Fail-safe: enforcement untouched. Next tick retries.
                Log.i(TAG, "policy poll failed: " + e.getClass().getSimpleName());
            } finally {
                inFlight.set(false);
            }
        }).start();
    }

    private static void poll(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String url = p.getString(KEY_URL, null);
        String secret = p.getString("admin_secret", null);
        if (url == null || secret == null) {
            // Say WHICH credential is missing, never its value.
            Log.i(TAG, "policy poll skip: url=" + (url != null) + " secret=" + (secret != null));
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        String body;
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.i(TAG, "policy poll http=" + code);
                return;
            }
            body = new BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                    .lines().reduce("", (a, b) -> a + b);
        } finally {
            conn.disconnect();
        }

        JSONObject envelope = new JSONObject(body);
        String payloadB64 = envelope.optString("payload", "");
        String sig = envelope.optString("sig", "");
        if (payloadB64.isEmpty() || sig.isEmpty()) {
            Log.i(TAG, "policy poll: malformed envelope");
            return;
        }
        if (!constantTimeEquals(hmacHex(secret, payloadB64), sig)) {
            // A bad signature is the interesting case - log it loudly, change nothing.
            Log.w(TAG, "policy poll: SIGNATURE MISMATCH - ignoring payload");
            return;
        }

        JSONObject payload = new JSONObject(
                new String(Base64.decode(payloadB64, Base64.DEFAULT), StandardCharsets.UTF_8));

        long stamp = payload.optLong("stamp", -1);
        long known = p.getLong(KEY_STAMP, -1);
        if (stamp <= known) {
            Log.i(TAG, "policy poll: no new stamp (" + stamp + " <= " + known + ")");
            p.edit().putLong(KEY_LAST_POLL, System.currentTimeMillis()).apply();
            return;
        }

        SharedPreferences.Editor e = p.edit()
                .putLong(KEY_STAMP, stamp)
                .putLong(KEY_LAST_POLL, System.currentTimeMillis());

        // maintenance_minutes is a DURATION, not an absolute timestamp: the tablet's clock is
        // DO-locked to network time, but a duration is immune to skew either way, and it
        // cannot be replayed into a permanently-open window.
        long minutes = payload.optLong("maintenance_minutes", 0);
        if (minutes > 0) {
            long until = Math.min(minutes * 60_000L, MAX_MAINTENANCE_MS)
                    + System.currentTimeMillis();
            e.putLong(KEY_MAINT_UNTIL, until);
            Log.i(TAG, "policy: maintenance window opened for " + minutes + " min");
        } else {
            e.remove(KEY_MAINT_UNTIL);
        }

        e.apply();
        Log.i(TAG, "policy applied stamp=" + stamp);
    }

    private static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
