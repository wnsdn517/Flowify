package com.flowify.ettea.player;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes a plain WebView read as a regular Chrome browser, consistently across every signal a
 * site can check. Google's "This browser or app may not be secure" / "browser not supported"
 * wall is triggered by any one of them saying "embedded WebView":
 * <ul>
 *   <li>the X-Requested-With: &lt;package&gt; request header WebView adds to every request
 *       (dropped where the WebView supports it, else overridden on the OAuth providers'
 *       navigations - see {@link #reissueWithoutRequestedWith});</li>
 *   <li>the Sec-CH-UA client-hint headers, whose brand list says "Android WebView" no matter
 *       what the UA string claims (also what navigator.userAgentData reports);</li>
 *   <li>the "; wv" / "Version/4.0" tokens of the default UA string;</li>
 *   <li>a version mismatch between the UA string and the real engine.</li>
 * </ul>
 * Versions come from the installed WebView itself, so the claimed Chrome is the engine that
 * actually runs and never drifts out of date. The JS layer is injected at document start (before
 * any page script can look) wherever the WebView supports that.
 */
final class BrowserDisguise {
    private static final String TAG = "[SpicyWeb]";
    private static final String FALLBACK_FULL_VERSION = "140.0.0.0";

    /** Hosts whose sign-in pages sniff for embedded browsers (Spotify's own plus the OAuth
     *  providers its login page links to). */
    private static final Set<String> SIGN_IN_ORIGINS = new HashSet<>(Arrays.asList(
            "https://*.spotify.com", "https://*.google.com", "https://*.youtube.com",
            "https://*.facebook.com", "https://*.apple.com"));

    private static volatile String cachedFullVersion;

    private BrowserDisguise() {
    }

    static String fullVersion(Context context) {
        String v = cachedFullVersion;
        if (v != null) return v;
        v = FALLBACK_FULL_VERSION;
        try {
            PackageInfo info = WebViewCompat.getCurrentWebViewPackage(context);
            if (info != null && info.versionName != null
                    && info.versionName.matches("\\d+\\.\\d+\\.\\d+\\.\\d+.*")) {
                v = info.versionName.split(" ")[0];
            }
        } catch (Throwable ignored) {
        }
        cachedFullVersion = v;
        return v;
    }

    static String majorVersion(Context context) {
        String full = fullVersion(context);
        int dot = full.indexOf('.');
        return dot > 0 ? full.substring(0, dot) : full;
    }

    static String desktopUserAgent(Context context) {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + majorVersion(context) + ".0.0.0 Safari/537.36";
    }

    static String mobileUserAgent(Context context) {
        // Chrome's reduced mobile UA: fixed "Android 10; K", no device model, no build id.
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + majorVersion(context) + ".0.0.0 Mobile Safari/537.36";
    }

    /** The hidden player: desktop Chrome on Windows, which is what gets Spotify to serve the
     *  full web player (its mobile page is an "open the app" wall). */
    static void applyDesktop(Context context, WebView view) {
        WebSettings s = view.getSettings();
        s.setUserAgentString(desktopUserAgent(context));
        applyHeaders(context, s, false);
        addDocumentStart(view, desktopJs(context), SIGN_IN_ORIGINS);
    }

    /** The visible sign-in screen: genuine mobile Chrome. Everything a page can measure (touch,
     *  screen, GPU) already says "phone", so claiming exactly that is the disguise with nothing
     *  left to contradict it - and accounts.spotify.com / Google both fully support it. */
    static void applyMobile(Context context, WebView view) {
        WebSettings s = view.getSettings();
        s.setUserAgentString(mobileUserAgent(context));
        applyHeaders(context, s, true);
        addDocumentStart(view, mobileJs(context), SIGN_IN_ORIGINS);
    }

    private static void applyHeaders(Context context, WebSettings s, boolean mobile) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, Collections.emptySet());
            }
        } catch (Throwable t) {
            Log.w(TAG, "X-Requested-With suppression failed type=" + t.getClass().getName());
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                String full = fullVersion(context);
                String major = majorVersion(context);
                UserAgentMetadata metadata = new UserAgentMetadata.Builder()
                        .setBrandVersionList(Arrays.asList(
                                brand("Not)A;Brand", "8", "8.0.0.0"),
                                brand("Chromium", major, full),
                                brand("Google Chrome", major, full)))
                        .setFullVersion(full)
                        .setPlatform(mobile ? "Android" : "Windows")
                        .setPlatformVersion(mobile ? "10.0.0" : "10.0.0")
                        .setArchitecture(mobile ? "" : "x86")
                        .setModel(mobile ? "K" : "")
                        .setMobile(mobile)
                        .setBitness(64)
                        .setWow64(false)
                        .build();
                WebSettingsCompat.setUserAgentMetadata(s, metadata);
            }
        } catch (Throwable t) {
            Log.w(TAG, "client hints override failed type=" + t.getClass().getName());
        }
    }

    private static UserAgentMetadata.BrandVersion brand(String name, String major, String full) {
        return new UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(major).setFullVersion(full).build();
    }

    private static final java.util.Map<String, String> NO_REQUESTED_WITH =
            Collections.singletonMap("X-Requested-With", "");

    /**
     * Where the WebView can't be told to drop X-Requested-With (the allow-list API is gone from
     * current WebView builds), a navigation it would send with the header is re-issued by us with
     * the header overridden - which WebView honours for loads the app starts. Only main-frame GET
     * navigations to the OAuth providers: that is the request their embedded-browser check sees
     * first, a GET can be replayed safely, and nothing else is disturbed.
     *
     * @return true when the navigation was taken over (return it from shouldOverrideUrlLoading)
     */
    static boolean reissueWithoutRequestedWith(WebView view, android.webkit.WebResourceRequest request) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) return false;
            if (request == null || !request.isForMainFrame() || request.getUrl() == null) return false;
            if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
            String host = request.getUrl().getHost();
            if (host == null || !isOAuthHost(host.toLowerCase(java.util.Locale.ROOT))) return false;
            view.loadUrl(request.getUrl().toString(), NO_REQUESTED_WITH);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void load(WebView view, String url) {
        try {
            String host = android.net.Uri.parse(url).getHost();
            if (host != null && isOAuthHost(host.toLowerCase(java.util.Locale.ROOT))) {
                view.loadUrl(url, NO_REQUESTED_WITH);
                return;
            }
        } catch (Throwable ignored) {
        }
        view.loadUrl(url);
    }

    private static boolean isOAuthHost(String host) {
        return host.equals("google.com") || host.endsWith(".google.com")
                || host.endsWith(".youtube.com") || host.endsWith(".facebook.com")
                || host.endsWith(".apple.com");
    }

    static boolean documentStartSupported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        } catch (Throwable t) {
            return false;
        }
    }

    static void addDocumentStart(WebView view, String js, Set<String> origins) {
        try {
            if (documentStartSupported()) WebViewCompat.addDocumentStartJavaScript(view, js, origins);
        } catch (Throwable t) {
            Log.e(TAG, "addDocumentStartJavaScript failed type=" + t.getClass().getName());
        }
    }

    /** Only needed where client hints can't be overridden natively (old WebView): mirrors the
     *  header claim into navigator.userAgentData so the two never disagree. */
    private static String uaDataJs(Context context, boolean mobile) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return "";
        String full = fullVersion(context);
        String major = majorVersion(context);
        return "try{var uaData={brands:[{brand:'Not)A;Brand',version:'8'},{brand:'Chromium',version:'" + major
                + "'},{brand:'Google Chrome',version:'" + major + "'}],mobile:" + mobile
                + ",platform:'" + (mobile ? "Android" : "Windows") + "',architecture:'" + (mobile ? "" : "x86")
                + "',bitness:'64',wow64:false,model:'" + (mobile ? "K" : "") + "',platformVersion:'10.0.0',"
                + "uaFullVersion:'" + full + "',fullVersionList:[{brand:'Not)A;Brand',version:'8.0.0.0'},"
                + "{brand:'Chromium',version:'" + full + "'},{brand:'Google Chrome',version:'" + full + "'}]};"
                + "uaData.getHighEntropyValues=async function(h){var o={brands:uaData.brands,mobile:uaData.mobile,platform:uaData.platform};"
                + "(h||[]).forEach(function(k){if(k in uaData)o[k]=uaData[k];});return o;};"
                + "uaData.toJSON=function(){return{brands:uaData.brands,mobile:uaData.mobile,platform:uaData.platform};};"
                + "d(navigator,'userAgentData',function(){return uaData;});}catch(e){}";
    }

    private static final String DEFINE = "function d(o,n,g){try{Object.defineProperty(o,n,{get:g,configurable:true});}catch(e){}}";

    /** window.chrome exists in every real Chrome; its absence is the classic WebView tell. */
    private static final String CHROME_OBJECT = "try{if(!window.chrome){Object.defineProperty(window,'chrome',"
            + "{value:{runtime:{},app:{isInstalled:false},loadTimes:function(){return{};},csi:function(){return{};}},"
            + "configurable:true,writable:true});}}catch(e){}";

    private static String mobileJs(Context context) {
        return "(function(){" + DEFINE
                + "d(navigator,'webdriver',function(){return false;});"
                + CHROME_OBJECT
                + uaDataJs(context, true)
                + "})();";
    }

    /** The desktop player page also checks screen/touch/GPU for "is this a phone", which is
     *  what its "browser not supported" gate rejects on - so those get desktop values too. */
    private static String desktopJs(Context context) {
        return "(function(){" + DEFINE
                + "try{d(screen,'width',function(){return 1920;});d(screen,'height',function(){return 1080;});"
                + "d(screen,'availWidth',function(){return 1920;});d(screen,'availHeight',function(){return 1040;});"
                + "d(window,'innerWidth',function(){return 1920;});d(window,'innerHeight',function(){return 978;});}catch(e){}"
                + "d(navigator,'webdriver',function(){return false;});"
                + "d(navigator,'vendor',function(){return 'Google Inc.';});"
                + "d(navigator,'platform',function(){return 'Win32';});"
                + "d(navigator,'maxTouchPoints',function(){return 0;});"
                + "d(navigator,'hardwareConcurrency',function(){return 8;});"
                + "d(navigator,'deviceMemory',function(){return 8;});"
                + CHROME_OBJECT
                + uaDataJs(context, false)
                + "try{var R='ANGLE (NVIDIA, NVIDIA GeForce RTX 3050 (0x00002584) Direct3D11 vs_5_0 ps_5_0, D3D11)';"
                + "[window.WebGLRenderingContext,window.WebGL2RenderingContext].forEach(function(C){if(!C)return;"
                + "var g=C.prototype.getParameter;C.prototype.getParameter=function(p){"
                + "if(p===37445)return 'Google Inc. (NVIDIA)';if(p===37446)return R;return g.call(this,p);};});}catch(e){}"
                + "})();";
    }

    /** For WebViews without document-start support: late, but better than nothing. */
    static String lateDesktopJs(Context context) {
        return desktopJs(context);
    }

    static String lateMobileJs(Context context) {
        return mobileJs(context);
    }
}
