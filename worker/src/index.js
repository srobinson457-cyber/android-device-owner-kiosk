/**
 * Policy endpoint for the managed-app kiosk devices.
 *
 * Returns { payload: base64(json), sig: hex HMAC-SHA256(secret, payload) }. The device
 * verifies the signature with its own device secret BEFORE parsing, because this document can
 * open a maintenance window: trusting TLS alone would make the unlock only as strong as
 * whatever resolver that site happens to be using.
 *
 * TWO DEVICES, TWO SECRETS, ONE WORKER:
 *
 *   /        -> DEVICE A, a tablet at a remote site, signed with KIOSK_SECRET
 *   /b       -> DEVICE B, a second device, signed with KIOSK_SECRET_B
 *
 * They are separated because a maintenance window is an UNLOCK. With one policy and one
 * secret, opening device B for a fix would also open the tablet (at a site nobody can
 * drive to), and a challenge/response code read aloud for one device would work on the other.
 * Different secrets mean a payload minted for one device fails signature verification on the
 * other, which is the property that actually matters.
 *
 * Anything that is not exactly /b falls through to the tablet, preserving the original
 * catch-all behavior: the deployed tablet build predates this routing and must keep working
 * byte-for-byte.
 *
 * The policies are source constants rather than KV on purpose: there are two devices, and
 * `wrangler deploy` is a 20-second round trip. One less moving part to be wrong at a site I cannot physically reach.
 *
 * To open a maintenance window: bump that device's `stamp` AND set `maintenance_minutes`, then
 * redeploy. The stamp MUST increase or the device ignores the change (idempotency).
 */

const TABLET_POLICY = {
  stamp: 1,
  // 0 = locked down normally. Set e.g. 30 to let the admin screen open without a
  // challenge/response for the next 30 minutes. It is a DURATION, not a timestamp: immune to
  // clock skew, and it cannot be replayed into a permanently-open window.
  maintenance_minutes: 0,
  target_url: 'https://example.com/',
  note: 'normal',
};

const DEVICE_B_POLICY = {
  stamp: 1,
  maintenance_minutes: 0,
  // No target_url: device B's flavor has no WebView. Verified that nothing reads this field:
  // RemotePolicy parses only `stamp` and `maintenance_minutes`, both with optLong defaults,
  // so the tablet's copy is vestigial too, and its absence here breaks nothing.
  note: 'normal',
};

export default {
  async fetch(request, env) {
    const isB = new URL(request.url).pathname === '/b';

    const policy = isB ? DEVICE_B_POLICY : TABLET_POLICY;
    const secret = isB ? env.KIOSK_SECRET_B : env.KIOSK_SECRET;
    const which = isB ? 'KIOSK_SECRET_B' : 'KIOSK_SECRET';

    // Fail loudly rather than signing with `undefined`, which would produce a stable-looking
    // signature that no device could ever verify.
    if (!secret) {
      return new Response(`${which} not configured`, { status: 500 });
    }

    const payload = btoa(JSON.stringify(policy));
    const sig = await hmacHex(secret, payload);
    return new Response(JSON.stringify({ payload, sig }), {
      headers: {
        'content-type': 'application/json',
        // Never let a CDN or an intermediary pin a stale maintenance window.
        'cache-control': 'no-store, max-age=0',
      },
    });
  },
};

async function hmacHex(secret, data) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw',
    enc.encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const sig = await crypto.subtle.sign('HMAC', key, enc.encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, '0')).join('');
}
