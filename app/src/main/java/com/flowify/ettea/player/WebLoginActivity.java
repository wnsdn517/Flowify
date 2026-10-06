package com.flowify.ettea.player;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.flowify.ettea.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive Spotify sign-in on a plain WebView. Cookies live in the app-wide CookieManager,
 * so the headless player picks the session up with no handoff at all.
 *
 * <p>The Spotify web app itself is never shown here: the moment the sign-in flow heads back to
 * open.spotify.com that navigation is intercepted and a native "signed in" screen replaces it,
 * which then closes itself after a short countdown. Opening this screen while already signed in
 * goes straight to that screen without loading anything.
 */
public final class WebLoginActivity extends Activity {
    private static final String LOGIN_URL = "https://accounts.spotify.com/login?continue="
            + "https%3A%2F%2Fopen.spotify.com%2F";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    /** Opened from inside Spotify: finishing lands right back on the screen the user left,
     *  where launching Spotify again would reset it to its home screen. */
    static final String EXTRA_FROM_SPOTIFY = "from_spotify";
    private static final int CLOSE_AFTER_SECONDS = 5;
    private static final long COOKIE_POLL_MS = 1000L;
    /** How long to wait for the session cookie once the flow has left the login pages. */
    private static final long FINISH_TIMEOUT_MS = 12000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WebView> popups = new ArrayList<>();
    private FrameLayout root;
    private WebView view;
    private View statusPanel;
    private TextView statusTitle;
    private TextView statusBody;
    private TextView countdown;
    private LinearLayout doneActions;
    private boolean done;
    private boolean finishing;
    private long finishingSince;
    private boolean allowSpotifyLoad;
    private int secondsLeft;
    /** Strings in the Spicy EX interface language (see PlayerSession#resources). */
    private android.content.res.Resources res;

    private final Runnable cookiePoll = new Runnable() {
        @Override
        public void run() {
            if (done) return;
            if (WebPlayerService.hasSessionCookie()) {
                onSignedIn(false);
                return;
            }
            if (finishing && System.currentTimeMillis() - finishingSince > FINISH_TIMEOUT_MS) {
                if (!allowSpotifyLoad) {
                    // Some flows only mint the session on the landing page itself: let it load
                    // (still hidden) and give it another window.
                    allowSpotifyLoad = true;
                    finishingSince = System.currentTimeMillis();
                    if (view != null) view.loadUrl("https://open.spotify.com/");
                } else {
                    // No session after all - back to the login form rather than a dead end.
                    finishing = false;
                    allowSpotifyLoad = false;
                    showWeb();
                    if (view != null) view.loadUrl(LOGIN_URL);
                }
            }
            handler.postDelayed(this, COOKIE_POLL_MS);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (secondsLeft <= 0) {
                backToSpotify();
                return;
            }
            countdown.setText(res.getString(R.string.connect_login_countdown, secondsLeft));
            secondsLeft--;
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        PlayerSession.noteUiLanguage(this, getIntent());
        res = PlayerSession.resources(this);
        buildUi();
        // Also lets the already-running (or about to run) player pay the one-time WebView
        // provider init instead of this screen; a foreground activity may start it freely.
        startPlayer(WebPlayerService.ACTION_WARMUP);
        if (WebPlayerService.hasSessionCookie()) {
            onSignedIn(true);
            return;
        }
        showStatus(res.getString(R.string.connect_login_loading), null);
        openLoginPage();
    }

    private void startPlayer(String action) {
        try {
            Intent i = new Intent(action);
            i.setClassName(getPackageName(), WebPlayerService.class.getName());
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Throwable ignored) {
        }
    }

    // --- UI -------------------------------------------------------------------------------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF121212);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(32), dp(32), dp(32), dp(32));

        statusTitle = new TextView(this);
        statusTitle.setTextColor(0xFFFFFFFF);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        statusTitle.setGravity(Gravity.CENTER);
        statusTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(statusTitle, wrap());

        statusBody = new TextView(this);
        statusBody.setTextColor(0xFFB3B3B3);
        statusBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bodyLp = wrap();
        bodyLp.topMargin = dp(12);
        panel.addView(statusBody, bodyLp);

        countdown = new TextView(this);
        countdown.setTextColor(0xFF1ED760);
        countdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        countdown.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cdLp = wrap();
        cdLp.topMargin = dp(20);
        panel.addView(countdown, cdLp);

        doneActions = new LinearLayout(this);
        doneActions.setOrientation(LinearLayout.VERTICAL);
        doneActions.setGravity(Gravity.CENTER);
        Button back = new Button(this);
        back.setAllCaps(false);
        back.setText(res.getString(R.string.connect_login_back));
        back.setTextColor(0xFF000000);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xFF1ED760);
        pill.setCornerRadius(dp(24));
        back.setBackground(pill);
        back.setPadding(dp(28), 0, dp(28), 0);
        back.setOnClickListener(v -> backToSpotify());
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        backLp.topMargin = dp(24);
        doneActions.addView(back, backLp);
        TextView other = new TextView(this);
        other.setText(res.getString(R.string.connect_login_switch));
        other.setTextColor(0xFFB3B3B3);
        other.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        other.setPadding(dp(16), dp(16), dp(16), dp(16));
        other.setOnClickListener(v -> confirmSignInAgain());
        LinearLayout.LayoutParams otherLp = wrap();
        otherLp.topMargin = dp(8);
        doneActions.addView(other, otherLp);
        doneActions.setVisibility(View.GONE);
        panel.addView(doneActions, wrap());

        statusPanel = panel;
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void showStatus(String title, String body) {
        statusTitle.setText(title);
        statusBody.setText(body == null ? "" : body);
        statusBody.setVisibility(body == null ? View.GONE : View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);
        statusPanel.bringToFront();
        if (view != null) view.setVisibility(View.INVISIBLE);
    }

    private void showWeb() {
        if (view == null) return;
        view.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.GONE);
    }

    // --- Sign-in flow ---------------------------------------------------------------------

    private void openLoginPage() {
        if (view == null) view = createWebView();
        done = false;
        finishing = false;
        allowSpotifyLoad = false;
        view.loadUrl(LOGIN_URL);
        handler.removeCallbacks(cookiePoll);
        handler.postDelayed(cookiePoll, COOKIE_POLL_MS);
    }

    private WebView createWebView() {
        WebView v = new WebView(this);
        v.setVisibility(View.INVISIBLE);
        root.addView(v, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configure(v);
        v.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!BrowserDisguise.documentStartSupported()) {
                    try {
                        view.evaluateJavascript(BrowserDisguise.lateMobileJs(WebLoginActivity.this), null);
                    } catch (Throwable ignored) {
                    }
                }
                if (isSpotifyApp(url) && !allowSpotifyLoad) beginFinishing();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (done || finishing) return;
                if (!isSpotifyApp(url)) showWeb();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request == null || request.getUrl() == null ? null : request.getUrl().toString();
                if (request != null && request.isForMainFrame() && isSpotifyApp(url) && !allowSpotifyLoad) {
                    // Sign-in is over and heading into the web app: don't show it.
                    beginFinishing();
                    return true;
                }
                return BrowserDisguise.reissueWithoutRequestedWith(view, request);
            }
        });
        v.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                return openPopup(parent, resultMsg);
            }
        });
        return v;
    }

    private void configure(WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSaveFormData(false);
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(v, true);
        } catch (Throwable ignored) {
        }
        BrowserDisguise.applyMobile(this, v);
    }

    /**
     * "Continue with Google/Facebook/Apple" may open a popup. Its navigation is forwarded into
     * the main view (the providers all fall back to a full redirect flow), and the popup itself
     * wears the same disguise: a popup left on the stock WebView UA and client hints is exactly
     * what made Google intermittently answer "this browser isn't supported".
     */
    private boolean openPopup(WebView parent, Message resultMsg) {
        try {
            WebView.HitTestResult hit = parent.getHitTestResult();
            String extra = hit == null ? null : hit.getExtra();
            if (extra != null && (extra.startsWith("http://") || extra.startsWith("https://"))) {
                BrowserDisguise.load(parent, extra);
                return true;
            }
            WebView popup = new WebView(this);
            configure(popup);
            popups.add(popup);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v2, WebResourceRequest r) {
                    String u = r != null && r.getUrl() != null ? r.getUrl().toString() : null;
                    if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                        BrowserDisguise.load(parent, u);
                        handler.post(() -> destroyPopup(popup));
                        return true;
                    }
                    return false;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void destroyPopup(WebView popup) {
        popups.remove(popup);
        try {
            popup.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSpotifyApp(String url) {
        if (url == null) return false;
        try {
            String host = Uri.parse(url).getHost();
            return "open.spotify.com".equalsIgnoreCase(host);
        } catch (Throwable t) {
            return false;
        }
    }

    private void beginFinishing() {
        if (done || finishing) return;
        finishing = true;
        finishingSince = System.currentTimeMillis();
        showStatus(res.getString(R.string.connect_login_finishing), null);
        handler.removeCallbacks(cookiePoll);
        handler.post(cookiePoll);
    }

    private void onSignedIn(boolean already) {
        if (done) return;
        done = true;
        finishing = false;
        handler.removeCallbacks(cookiePoll);
        try {
            CookieManager.getInstance().flush();
        } catch (Throwable ignored) {
        }
        PlayerSession.loggedIn = true;
        if (!already) startPlayer(WebPlayerService.ACTION_LOGIN_DONE);
        // The sign-in WebView is dead weight from here on; free it now, not in 5 seconds.
        releaseWebViews();
        showStatus(res.getString(already ? R.string.connect_login_already_title
                : R.string.connect_login_done_title), res.getString(R.string.connect_login_done_body));
        doneActions.setVisibility(View.VISIBLE);
        countdown.setVisibility(View.VISIBLE);
        secondsLeft = CLOSE_AFTER_SECONDS;
        handler.removeCallbacks(tick);
        tick.run();
    }

    /** Signing in with another account first signs the current one out - say so, and only
     *  go ahead on an explicit yes. The auto-close countdown waits while the question is up. */
    private void confirmSignInAgain() {
        handler.removeCallbacks(tick);
        try {
            new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(res.getString(R.string.connect_login_switch_confirm_title))
                    .setMessage(res.getString(R.string.connect_login_switch_confirm_body))
                    .setPositiveButton(res.getString(R.string.connect_login_switch_confirm),
                            (d, w) -> signInAgain())
                    .setNegativeButton(res.getString(R.string.connect_login_cancel), null)
                    .setOnDismissListener(d -> {
                        if (done && secondsLeft >= 0 && doneActions.getVisibility() == View.VISIBLE) {
                            handler.removeCallbacks(tick);
                            handler.postDelayed(tick, 1000L);
                        }
                    })
                    .show();
        } catch (Throwable t) {
            signInAgain();
        }
    }

    /** Signs the web session out (every cookie of this app's WebView profile) and starts over. */
    private void signInAgain() {
        handler.removeCallbacks(tick);
        countdown.setVisibility(View.GONE);
        doneActions.setVisibility(View.GONE);
        PlayerSession.loggedIn = false;
        done = false;
        showStatus(res.getString(R.string.connect_login_loading), null);
        try {
            CookieManager.getInstance().removeAllCookies(ok -> {
                CookieManager.getInstance().flush();
                startPlayer(WebPlayerService.ACTION_SIGNED_OUT);
                openLoginPage();
            });
        } catch (Throwable t) {
            openLoginPage();
        }
    }

    private void backToSpotify() {
        handler.removeCallbacksAndMessages(null);
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_SPOTIFY, false)) {
            finish();
            return;
        }
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(SPOTIFY_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            }
        } catch (Throwable ignored) {
        }
        finish();
    }

    private void releaseWebViews() {
        for (WebView popup : new ArrayList<>(popups)) destroyPopup(popup);
        WebView v = view;
        view = null;
        if (v == null) return;
        try {
            root.removeView(v);
            v.stopLoading();
            v.destroy();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null) {
            try {
                view.onResume();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        if (view != null) {
            try {
                view.onPause();
            } catch (Throwable ignored) {
            }
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseWebViews();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        try {
            if (!done && view != null && view.getVisibility() == View.VISIBLE && view.canGoBack()) {
                view.goBack();
                return;
            }
        } catch (Throwable ignored) {
        }
        super.onBackPressed();
    }
}
