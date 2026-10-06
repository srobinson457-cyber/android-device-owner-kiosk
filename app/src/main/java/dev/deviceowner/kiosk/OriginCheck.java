package dev.deviceowner.kiosk;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The kiosk WebView's navigation allowlist: is a URL on exactly the same origin (scheme, host
 * and port) as the start URL?
 *
 * Compare parsed parts, never a string prefix. "https://example.com.evil.test/" and
 * "https://example.com@evil.test/" both start with "https://example.com", and neither is that
 * host.
 *
 * Plain Java with no Android classes, so it runs as a JVM unit test (android.net.Uri only
 * returns default values there).
 */
final class OriginCheck {

    private OriginCheck() {}

    /**
     * True only when url has the same scheme and host (ignoring case) and the same effective
     * port as allowedUrl. Anything that does not parse is refused.
     */
    static boolean isAllowed(String url, String allowedUrl) {
        URI want = origin(allowedUrl);
        URI got = origin(url);
        if (want == null || got == null) return false;
        return want.getScheme().equalsIgnoreCase(got.getScheme())
                && want.getHost().equalsIgnoreCase(got.getHost())
                && effectivePort(want) == effectivePort(got);
    }

    /**
     * Parses only the scheme and authority. The path and query cannot change the origin, and
     * java.net.URI throws on characters such as '|' that a browser can leave unescaped there,
     * which would refuse ordinary same-origin links. The authority ends at the first '/', '?'
     * or '#' (or '\', which http and https treat as '/').
     */
    private static URI origin(String url) {
        if (url == null) return null;
        int sep = url.indexOf("://");
        if (sep <= 0) return null;
        int end = sep + 3;
        while (end < url.length() && "/?#\\".indexOf(url.charAt(end)) < 0) end++;
        try {
            URI u = new URI(url.substring(0, end));
            // No host means an opaque or registry-based URI, which is never the kiosk's site.
            return u.getScheme() == null || u.getHost() == null ? null : u;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static int effectivePort(URI u) {
        if (u.getPort() != -1) return u.getPort();
        String scheme = u.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) return 443;
        if (scheme.equals("http")) return 80;
        return -1;
    }
}
