package dev.deviceowner.kiosk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The SSID typed or tapped on the Wi-Fi screen must reach the join exactly as entered. */
public class WifiSetupTest {

    @Test
    public void keepsLeadingAndTrailingSpaces() {
        // Both are legal in an SSID, and an access point named "Cafe " does not answer to "Cafe".
        assertEquals("Cafe ", WifiSetup.ssidForJoin("Cafe "));
        assertEquals(" Cafe", WifiSetup.ssidForJoin(" Cafe"));
        assertEquals("  Cafe  ", WifiSetup.ssidForJoin("  Cafe  "));
    }

    @Test
    public void returnsOrdinaryNamesUnchanged() {
        assertEquals("Cafe", WifiSetup.ssidForJoin("Cafe"));
        assertEquals("Cafe Wi-Fi", WifiSetup.ssidForJoin("Cafe Wi-Fi"));
    }

    @Test
    public void blankMeansNoNetworkName() {
        assertNull(WifiSetup.ssidForJoin(""));
        assertNull(WifiSetup.ssidForJoin("   "));
        assertNull(WifiSetup.ssidForJoin(null));
    }
}
