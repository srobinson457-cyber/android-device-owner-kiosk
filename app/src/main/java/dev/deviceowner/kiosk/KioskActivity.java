package dev.deviceowner.kiosk;

import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * The reference HOME activity: a full-screen WebView pinned to one origin.
 *
 * Deliberately the thinnest thing that still demonstrates the shape. The real launcher this was
 * extracted from did considerably more (file chooser plumbing for in-app capture, offline and
 * error states, cookie handling). What matters for anyone adapting this is where the
 * responsibilities sit, because three of them are easy to put in the wrong place.
 *
 * WHAT THE BASE CLASS ALREADY DOES, so do not duplicate it here:
 *   - entering lock task, from onResume, only when armed and only when actually Device Owner;
 *   - re-asserting the lock-task feature set on every launch, which is what expires a temporary
 *     power-menu grant instead of leaving it enabled forever;
 *   - expiring a stale maintenance window before the armed check;
 *   - the corner-tap admin gesture, detected in dispatchTouchEvent so it never steals a touch
 *     from the content underneath. Do not consume touch events here or you will break it.
 *
 * WHAT THIS CLASS IS RESPONSIBLE FOR:
 *   1. BEING HOME. The manifest gives it CATEGORY_HOME and KioskPolicy makes it the PERSISTENT
 *      preferred handler. Hiding the OEM launcher is not sufficient on its own and destabilises
 *      some OEM shells; being the preferred handler is what actually closes the exit.
 *   2. NEVER LEAVING THE ALLOWLISTED ORIGIN. A WebView that follows an off-site link inside a
 *      kiosk is an unrestricted browser with no address bar, which is worse than a browser.
 *      shouldOverrideUrlLoading below is load-bearing security, not a nicety. It allows a
 *      link only when OriginCheck finds the same scheme, host and port as START_URL.
 *   3. HAVING NO VISIBLE WAY OUT. Back at the root is a no-op rather than finish(), because
 *      finishing HOME briefly exposes whatever is behind it.
 */
public class KioskActivity extends KioskShellActivity {

    /**
     * Replace with your own site. Its origin (scheme, host and port) is the only one the
     * WebView will navigate to.
     */
    private static final String START_URL = "https://example.com/";

    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // No file or content access. A kiosk WebView that can read file:// URLs can read this
        // app's own data directory, and that directory holds the admin secret.
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (OriginCheck.isAllowed(url, START_URL)) {
                    return false; // same origin, let the WebView load it
                }
                // Anything else is refused silently rather than handed to an external handler.
                // An ACTION_VIEW here would try to launch a browser that is not in
                // setLockTaskPackages, stranding the device on a "blocked app" screen that a
                // non-technical user cannot get out of.
                return true;
            }
        });

        setContentView(web);
        web.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();   // lock task and policy re-assertion happen here
        hideSystemUi();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        }
        // Otherwise deliberately nothing. See responsibility 3 above.
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
