package dev.deviceowner.kiosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The kiosk WebView may only follow links on exactly the start URL's origin. */
public class OriginCheckTest {

    private static final String START = "https://example.com/";

    private static void refused(String url) {
        assertFalse("should be refused: " + url, OriginCheck.isAllowed(url, START));
    }

    private static void allowed(String url) {
        assertTrue("should be allowed: " + url, OriginCheck.isAllowed(url, START));
    }

    @Test
    public void refusesHostThatOnlyStartsWithTheAllowedName() {
        refused("https://example.com.evil.test/");
        refused("https://example.community/");
    }

    @Test
    public void refusesAllowedNameInUserinfo() {
        // The real host here is evil.test; example.com is only the username.
        refused("https://example.com@evil.test/");
    }

    @Test
    public void refusesOtherPortOnAllowedHost() {
        refused("https://example.com:8443/");
    }

    @Test
    public void refusesOtherScheme() {
        refused("http://example.com/");
        refused("intent://example.com/#Intent;end");
        refused("javascript:alert('https://example.com/')");
    }

    @Test
    public void refusesUnparseableInput() {
        refused("not a url");
        refused("https://exa mple.com/");
        refused("");
        refused(null);
    }

    @Test
    public void allowsSameOrigin() {
        allowed("https://example.com/");
        allowed("https://example.com/page?x=1");
        allowed("https://example.com");
        allowed("https://example.com:443/page");
        allowed("HTTPS://EXAMPLE.COM/page");
    }

    @Test
    public void allowsSameOriginWithCharactersJavaUriRejectsInPathOrQuery() {
        // java.net.URI throws on '|' in a query and '{' in a path, which a browser can pass
        // through unescaped. Only the origin is parsed, so these same-origin links still load.
        allowed("https://example.com/search?q=a|b");
        allowed("https://example.com/a{b}");
    }
}
