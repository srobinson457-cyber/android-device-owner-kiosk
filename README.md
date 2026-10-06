# Android Device Owner Kiosk

[![CI](https://github.com/srobinson457-cyber/android-device-owner-kiosk/actions/workflows/ci.yml/badge.svg)](https://github.com/srobinson457-cyber/android-device-owner-kiosk/actions/workflows/ci.yml)

A working Android Device Owner implementation: full device lockdown, lock task, a
challenge/response admin gate, and a signed remote policy channel, for a device you cannot
physically reach.

This is extracted from a build that has been running unattended on real hardware at a remote
site. It is a reference implementation and a set of field notes, not a library. The
interesting part is not the API calls, which are documented; it is the nine places where the
documented behavior is not the actual behavior, and what that costs you when the device is
hours away and nobody near it is technical.

Extracted in September 2026 from private code I wrote and run in production; the history
stays private because it contains private data.

Plain framework Java. No third-party dependencies in the app; JUnit is used only by the local
unit tests, which CI runs with a debug build on every push.

---

## The problem

Locking an Android device down to a single app is easy to do badly. Screen pinning, a kiosk
launcher from the Play Store, or a "guided access" toggle all produce something that looks
locked and is trivially escapable. Device Owner is the real mechanism: a privileged mode,
settable only on a device with no configured accounts, that lets an app set system policy.

It has one property that shapes every decision in this repo: **it is close to irreversible.**
Clearing Device Owner on a provisioned device generally means a factory reset. A
differently-signed APK cannot be installed over it. If you lock yourself out, you do not
debug the problem, you drive to the device.

So the design goal is not "lock it down". It is **lock it down and still be able to fix it.**

---

## What is here

| File | Lines | What it does |
|---|---:|---|
| `KioskPolicy.java` | 720 | The policy engine. Restrictions, package hiding, lock task, HOME takeover, OTA windows, time zone. |
| `WifiSetupActivity.java` | 598 | On-device Wi-Fi provisioning, so the device can move to a new network without a computer. |
| `ProbeActivity.java` | 308 | The admin console, reachable only through the gate. |
| `KioskShellActivity.java` | 193 | Base activity: enters lock task, polls policy, hosts the hidden admin gesture. |
| `RemotePolicy.java` | 181 | HMAC-signed policy polling. The only way to change anything after handover. |
| `AdminGate.java` | 151 | HOTP-style challenge/response. The offline escape hatch. |
| `WifiSetup.java` | 165 | Wi-Fi join primitives. |
| `AdminCommandReceiver.java` | 78 | Setup-time control surface over ADB. |
| `AdminReceiver.java` | 17 | The `DeviceAdminReceiver` that `dpm set-device-owner` points at. |
| `Flavor.java` | 127 | The one seam between the policy engine and a specific device type. |
| `KioskActivity.java` | 103 | Reference HOME activity: a WebView pinned to one origin. |
| `worker/` | 87 | A Cloudflare Worker serving signed policy documents. |

`Flavor.java` and `KioskActivity.java` are written fresh for this repo as the minimal
single-app reference case. Everything else is the deployed code with identifiers changed.

---

## Field notes

These are the things that were not in the documentation, in rough order of how much they cost
to find out.

### An escape hatch whose only setting is "destroy everything" is a fuse, not an escape hatch

The admin screen began as a read-only probe that reported whether Device Owner had taken. The
real control surface was `AdminCommandReceiver`, driven over ADB. The last step of handover is
`lockDebugging()`, which removes ADB.

So every lever that could fix the device became unreachable at exactly the moment the device
went somewhere unreachable, and the only on-device button left was **Clear Device Owner**.

This was discovered when the device needed to join a different network and there was no way to
do it short of dismantling the entire lockdown. The admin console now carries the same commands
the ADB receiver exposes. The destructive one is still last, still separated, and now confirms.

### `QUERY_ALL_PACKAGES` is not optional, and its absence fails silently

Without it, package-visibility filtering returns a **partial** launcher list to
`queryIntentActivities`. The first hide sweep saw 23 packages and missed Chrome, Play
and most of the 55 that `cmd package query-activities` reports from shell.

It did not error. It reported success, having hidden less than half the launcher.

### Lock task exempts the default dialer, so removing it from the allowlist is not enough

The platform protects emergency dialing. Dropping the OEM dialer from `setLockTaskPackages`
does **not** stop a `tel:` link from launching it, inside lock task, with
`mLockTaskModeState=LOCKED`. That puts a full dialer, and everything it links to, one tap away.

Becoming the persistent preferred handler for `tel:` intents is what actually closes it.

### `DISALLOW_USB_FILE_TRANSFER` can make a device unrecoverable by ADB

On the hardware this was built for, setting it kills the ADB data connection once USB
re-enumerates. It presents as `device unauthorized` with no prompt. Combined with lock task,
which blocks `UsbDebuggingActivity` from ever launching to show that prompt, there is no way
back in.

It belongs with the handover-time steps, not the working set. Lifting the restriction later is
also not sufficient on its own: `ADB_ENABLED` has to be set back to 1 explicitly.

### `setLockTaskFeatures` is persistent policy, so a temporary grant is permanent

Granting the power menu once, to let someone shut the device down, leaves it granted forever,
across reboots, with nothing to indicate it happened. The feature set is re-asserted on every
launch for exactly this reason. The same applies to a maintenance window: it is stored as a
**duration** rather than a timestamp, so it cannot be replayed into a permanently open window,
and it is expired before the armed check rather than after.

### A hardcoded package list rots silently on the first OTA

The hidden-package list is computed at runtime. It was 55 entries on the reference device, and
a hardcoded copy breaks the first time an OTA adds one, in the direction of leaving something
visible.

Hide rather than suspend: a suspended app still shows a grayed icon and a system dialog, which
just advertises that it exists.

### Do not trim SSIDs

A trailing space in an SSID is legal and significant, and real networks have them. Trimming it
puts a permanent, invisible "wrong password" bug in your provisioning flow.

Related: a WPA2 passphrase of exactly 64 characters is interpreted as raw hex, and fails in a
way that reads like a wrong password.

### Automatic OTA policy means no OTA at all

An install-immediately policy reboots the device mid-use; a postpone policy means the update
sits there forever and the device silently stops receiving security patches for as long as it
exists. A windowed policy, 03:00 to 05:00 local, is the only version that actually applies
updates. It requires the clock to be Device Owner-locked to network time, and automatic time
zone detection to be off.

### A WebView kiosk that follows an off-site link is a browser with no address bar

`shouldOverrideUrlLoading` is load-bearing security here, not a nicety. Refuse off-origin
navigation silently rather than handing it to an external handler: an `ACTION_VIEW` launches a
browser that is not in `setLockTaskPackages` and strands the device on a "blocked app" screen a
non-technical user cannot escape.

Compare the parsed scheme, host and port, not a string prefix. `https://example.com.evil.test/`
and `https://example.com@evil.test/` both start with `https://example.com`, and neither is that
host. `OriginCheck` does the comparison and has unit tests for those cases.

Also disable file and content access. A kiosk WebView that can read `file://` URLs can read the
app's own data directory, and that directory holds the admin secret.

---

## The two escape hatches

Both exist because a device you cannot reach needs a way back in that does not depend on the
thing that is broken.

**Remote, primary.** `RemotePolicy` polls a signed document. It authenticates with a
**per-device** secret rather than a user session, so the device can never orphan or refresh
someone's auth token. It is idempotent via a monotonically advancing stamp. It **fails safe**:
any network or parse error leaves enforcement exactly as it is. For a kiosk, fail-safe means
*stays locked*, and the human escape is never a network timeout.

The payload is HMAC-signed rather than trusted on HTTPS alone, because the document can unlock
the device, and DNS at a site you do not control would otherwise make the unlock only as strong
as whatever resolver that site happens to use.

**Offline, fallback.** `AdminGate` shows a random six-digit **challenge** and demands the
matching **response**, `HMAC-SHA256(deviceSecret, challenge)` truncated HOTP-style.

Not a PIN: a static PIN typed in front of a motivated user is a leaked PIN, permanently, with
no way to notice or rotate it remotely. Watching someone type a challenge response teaches you
nothing, because the next challenge is different. The secret is provisioned once over ADB and
never displayed on the device.

On threat model, stated plainly: the secret lives in app-private `SharedPreferences`. On a
locked-bootloader, non-rooted kiosk device, that is the right level. The threat model is a
determined user poking at the screen, not a forensic lab.

---

## Setup

Device Owner can only be set on a device with **no configured accounts**. In practice: factory
reset, skip every sign-in, then before adding any account:

```bash
adb install -r app-release.apk
adb shell dpm set-device-owner dev.deviceowner.kiosk/.AdminReceiver
```

Then drive setup through the command receiver:

```bash
adb shell am broadcast -a dev.deviceowner.kiosk.CMD \
    -n dev.deviceowner.kiosk/.AdminCommandReceiver \
    --es token <TOKEN> --es cmd status
```

**Before you flash anything:** change `TOKEN` in `AdminCommandReceiver.java`. It ships as
`CHANGE-ME-BEFORE-FLASHING`. It is a speed bump rather than a security boundary, since the real
boundary is that no other app can be installed and ADB is removed at handover, but ship your
own value.

Provision the admin secret (32+ hex characters) with the `setsecret` command, and set the same
value as the Worker's `KIOSK_SECRET`. Copy `keystore.properties.example` to
`keystore.properties` and keep the keystore itself outside the repo: once this package is Device
Owner, you cannot install a differently-signed APK over it.

`lockDebugging()` is the last step of handover, because it removes ADB. Do not run it until
everything else is verified on the device.

---

## What is not here

This is the shared policy core. The deployment it came from also had device-specific flavors,
each with its own home screen. Those are omitted: they are specific to particular devices and
add nothing to the general case.

`Flavor.java` is the seam they plugged into, and its methods are a usable checklist of the
decisions a new device type has to make deliberately.

---

## License

MIT. See [LICENSE](LICENSE).
