package com.smali_generator.patches;

import android.net.Uri;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.live.Hls;
import com.smali_generator.live.OriginResolver;
import com.smali_generator.live.Slate;
import com.smali_generator.live.SlateSwap;
import com.smali_generator.utils.AdBlocklist;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ad requests made from WebViews, in both app variants.
 *
 * The legacy variant is 13tv.co.il in a WebView. Its video ads come from the
 * Kaltura web player's IMA plugin, which fetches a GAM VAST. That request gets an
 * empty VAST, so IMA reports "no ads" and the player starts the content. GPT,
 * AdSense and Taboola scripts and creatives get empty replies. C13 opens the same
 * site in an RN WebView, and this hook covers that one too.
 *
 * The hook is on the framework WebViewClient.shouldInterceptRequest. Neither app
 * client (WebViewFragment$WebClient, RNCWebViewClient) overrides it, nor does
 * androidx WebViewClientCompat, so virtual dispatch lands here. It runs on a
 * Chromium IO thread: no UI calls, no locks, and every path falls back to the
 * original.
 *
 * It also carries the live-stream bypass for the web player (see gmana()).
 */
public class WebViewAdBlockHook implements Hook {

    private static final String TAG = "PATCH";
    private static final byte[] EMPTY = new byte[0];
    private static final Set<String> loggedHosts = ConcurrentHashMap.newKeySet();

    // Backup: ArtHooks points it at the original body. It must be static native,
    // because dex2oat would inline a Java body into intercept().
    static native WebResourceResponse intercept_backup(Object thiz, WebView view, WebResourceRequest request);

    static WebResourceResponse intercept(Object thiz, WebView view, WebResourceRequest request) {
        try {
            WebResourceResponse blocked = block(request);
            if (blocked != null) {
                return blocked;
            }
        } catch (Throwable t) {
            Log.e(TAG, "WebAd: " + t);
        }
        return intercept_backup(thiz, view, request);
    }

    private static WebResourceResponse block(WebResourceRequest request) {
        if (request == null || request.isForMainFrame()) {
            return null;
        }
        WebResourceResponse live = gmana(request);
        if (live != null) {
            return live;
        }
        Uri url = request.getUrl();
        String host = url.getHost();
        if (!AdBlocklist.isWebAdHost(host)) {
            return null;
        }
        String path = url.getPath() == null ? "" : url.getPath();

        if (AdBlocklist.isAdTagRequest(host, path)) {
            String body = AdBlocklist.emptyAdTagBody(url.getQueryParameter("output"), url.getQueryParameter("ad_rule"));
            Log.i(TAG, "WebAd: empty " + (AdBlocklist.EMPTY_VMAP.equals(body) ? "VMAP" : "VAST")
                    + " <- " + host + path + " iu=" + url.getQueryParameter("iu"));
            return respond("text/xml", body.getBytes(StandardCharsets.UTF_8), corsHeaders(request));
        }

        if (loggedHosts.add(host)) {
            Log.i(TAG, "WebAd: blocking " + host + " (first hit: " + path + ")");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (AdBlocklist.isGptScript(host, lower)) {
            return respond("application/javascript",
                    AdBlocklist.GPT_STUB_JS.getBytes(StandardCharsets.UTF_8), corsHeaders(request));
        }
        String mime;
        if (lower.endsWith(".js")) {
            mime = "application/javascript";
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            mime = "text/html";
        } else if (lower.endsWith(".gif") || lower.endsWith(".png") || lower.endsWith(".jpg")) {
            mime = "image/gif";
        } else {
            mime = "text/plain";
        }
        return respond(mime, EMPTY, corsHeaders(request));
    }

    /**
     * The web player's live stream (13tv.co.il), in the legacy app and in C13's
     * fallback WebView. Like C13, it plays a G-Mana SSAI master. This answers that
     * request with the clean origin master the way TheoLiveHook does, with every URI
     * made absolute: a WebView reply cannot redirect, so relative URIs would
     * otherwise resolve against G-Mana. The origin's CloudFront allows the site's
     * origin over CORS, so hls.js loads the rest from there directly.
     *
     * Fallback when no origin resolves: G-Mana media playlists are fetched here and
     * get their ad segments swapped for slates, which this hook then serves.
     * Blocking is fine on this thread (a WebView IO thread, not the UI thread).
     */
    private static WebResourceResponse gmana(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        String method = request.getMethod();
        try {
            if (Hls.SLATE_HOST.equals(host)) {
                byte[] slate = Slate.bytes(uri.getPath());
                return slate == null ? null : respond("video/mp2t", slate, corsHeaders(request));
            }
            URL url = new URL(uri.toString());
            if (Hls.isGmanaMaster(url)) {
                if ("POST".equals(method)) {
                    // C13's session POST, should the site adopt it: hand back a plain master, no tracking URL.
                    OriginResolver.Resolved origin = OriginResolver.resolve(url, 5000, false);
                    String target = origin != null ? origin.url : uri.buildUpon().clearQuery().build().toString();
                    Log.i(TAG, "WebGmana: session POST -> " + target);
                    return respond("application/json",
                            ("{\"manifestUrl\":\"" + target + "\"}").getBytes(StandardCharsets.UTF_8), corsHeaders(request));
                }
                if ("GET".equals(method)) {
                    OriginResolver.Resolved origin = OriginResolver.resolve(url, 5000, true);
                    if (origin != null && origin.body != null) {
                        Log.i(TAG, "WebGmana: master " + host + url.getPath() + " -> " + origin.url);
                        String body = Hls.absolutize(origin.body, new URL(origin.url));
                        return respond("application/vnd.apple.mpegurl",
                                body.getBytes(StandardCharsets.UTF_8), corsHeaders(request));
                    }
                    Log.w(TAG, "WebGmana: no origin for " + url + " (slate fallback)");
                }
                return null;
            }
            if ("GET".equals(method) && Hls.isGmanaMedia(url)) {
                Map<String, String> headers = new HashMap<>();
                Map<String, String> requestHeaders = request.getRequestHeaders();
                if (requestHeaders != null) {
                    for (Map.Entry<String, String> h : requestHeaders.entrySet()) {
                        String k = h.getKey();
                        // HttpURLConnection only gunzips when it set Accept-Encoding itself.
                        if (k != null && !k.equalsIgnoreCase("Accept-Encoding") && !k.equalsIgnoreCase("Range")) {
                            headers.put(k, h.getValue());
                        }
                    }
                }
                String cookie = CookieManager.getInstance().getCookie(uri.toString());
                if (cookie != null) {
                    headers.put("Cookie", cookie);
                }
                OriginResolver.Http upstream = OriginResolver.get(url, true, 5000, headers);
                if (upstream.code != 200) {
                    return null;
                }
                SlateSwap.Result swapped = SlateSwap.apply(upstream.body, uri.toString());
                if (swapped.adSegments > 0) {
                    Log.i(TAG, "WebGmana: slate-swapped " + swapped.adSegments + " ad segments in " + url.getPath());
                }
                // The reply's base URL is still the G-Mana URL the page asked for, so relative URIs keep working.
                return respond("application/vnd.apple.mpegurl",
                        swapped.body.getBytes(StandardCharsets.UTF_8), corsHeaders(request));
            }
        } catch (Throwable t) {
            Log.e(TAG, "WebGmana: " + t);
        }
        return null;
    }

    /**
     * IMA fetches the VAST by XHR with credentials. The reply then needs the exact
     * page origin echoed back, because "*" is rejected for credentialed requests.
     * Use the Origin header when Chromium exposes it, otherwise derive the origin
     * from the Referer.
     */
    private static Map<String, String> corsHeaders(WebResourceRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        String origin = null;
        Map<String, String> requestHeaders = request.getRequestHeaders();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                String key = entry.getKey();
                if (key == null || entry.getValue() == null) {
                    continue;
                }
                if (key.equalsIgnoreCase("Origin")) {
                    origin = entry.getValue();
                    break;
                }
                if (key.equalsIgnoreCase("Referer")) {
                    Uri referer = Uri.parse(entry.getValue());
                    if (referer.getScheme() != null && referer.getAuthority() != null) {
                        origin = referer.getScheme() + "://" + referer.getAuthority();
                    }
                }
            }
        }
        if (origin != null && !origin.equals("null")) {
            headers.put("Access-Control-Allow-Origin", origin);
            headers.put("Access-Control-Allow-Credentials", "true");
            headers.put("Vary", "Origin");
        } else {
            headers.put("Access-Control-Allow-Origin", "*");
        }
        return headers;
    }

    private static WebResourceResponse respond(String mime, byte[] body, Map<String, String> headers) {
        return new WebResourceResponse(mime, "utf-8", 200, "OK", headers, new ByteArrayInputStream(body));
    }

    public void load() {
        try {
            Class<?>[] params = {Object.class, WebView.class, WebResourceRequest.class};
            boolean ok = ArtHooks.hook_function(
                    WebViewClient.class.getDeclaredMethod("shouldInterceptRequest", WebView.class, WebResourceRequest.class),
                    WebViewAdBlockHook.class.getDeclaredMethod("intercept", params),
                    WebViewAdBlockHook.class.getDeclaredMethod("intercept_backup", params));
            Log.i(TAG, "WebAd: hooked WebViewClient.shouldInterceptRequest = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "WebAd: " + t);
        }
    }

    public void unload() {
    }
}
