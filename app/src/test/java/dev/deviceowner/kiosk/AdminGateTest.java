package dev.deviceowner.kiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

/**
 * Pins AdminGate.respond(), the function any off-device responder has to mirror exactly.
 *
 * The expected values were computed independently of this code, with Node's crypto module:
 *
 *   const c = require('crypto');
 *   const key = Buffer.from('00112233445566778899aabbccddeeff', 'utf8'); // the hex STRING's bytes
 *   const h = c.createHmac('sha256', key).update(challenge, 'utf8').digest();
 *   const off = h[31] & 0x0f;
 *   const code = String((h.readUInt32BE(off) & 0x7fffffff) % 1000000).padStart(6, '0');
 *
 * Worked example for challenge "000000":
 *   HMAC = efdefba0ae3b5c20934389c22e9344eaeff3bf51565bd845d96ce049f445c5c2
 *   last byte 0xc2, so offset = 2; bytes 2..5 = fb a0 ae 3b
 *   masked with 0x7fffffff: 0x7ba0ae3b = 2074127931; mod 10^6 = 127931
 *
 * Note that the key is the UTF-8 bytes of the hex string as provisioned, not the decoded bytes.
 * A responder that hex-decodes the secret first gets different codes, and this test is what
 * says which one the device actually does.
 */
public class AdminGateTest {

    private static final String SECRET = "00112233445566778899aabbccddeeff";

    @Test
    public void respond_matchesIndependentlyComputedVectors() {
        assertEquals("127931", AdminGate.respond(SECRET, "000000"));
        assertEquals("394447", AdminGate.respond(SECRET, "123456"));
        assertEquals("817931", AdminGate.respond(SECRET, "987654"));
    }

    @Test
    public void respond_keepsLeadingZeros() {
        // HMAC = b68449c4622d1819d2f95e2d62907b399ff2769790b770ed572ac6c9d7607e5a,
        // offset 10, truncated value 1580032656, mod 10^6 = 32656, padded to six digits.
        assertEquals("032656", AdminGate.respond(SECRET, "000006"));
    }

    @Test
    public void respond_isAlwaysSixDigits() {
        for (int i = 0; i < 200; i++) {
            String code = AdminGate.respond(SECRET, String.format("%06d", i * 4999));
            assertTrue("not six digits: " + code, code.matches("\\d{6}"));
        }
    }

    @Test
    public void codesAreAsciiDigitsWhateverTheDefaultLocale() {
        // In ar-EG (and fa-IR, bn-BD, ar) a locale-sensitive "%06d" prints non-ASCII digits,
        // which the person at the device cannot type back and no off-device responder expects.
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            String challenge = AdminGate.newChallenge(0L);
            assertTrue("challenge not ASCII digits: " + challenge,
                    challenge.matches("[0-9]{6}"));
            // The same vector as above, so this checks respond() on its own.
            assertEquals("127931", AdminGate.respond(SECRET, "000000"));
        } finally {
            Locale.setDefault(saved);
        }
    }
}
