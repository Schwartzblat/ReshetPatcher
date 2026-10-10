package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.live.Hls;
import com.smali_generator.live.OriginResolver;
import com.smali_generator.live.Slate;
import com.smali_generator.live.SlateSwap;
import com.theoplayer.android.api.network.http.CompleteHTTPResponse;
import com.theoplayer.android.api.network.http.HTTPRequest;
import com.theoplayer.android.api.network.http.InterceptableHTTPRequest;
import com.theoplayer.android.api.network.http.InterceptableHTTPResponse;
import com.theoplayer.android.api.network.http.JavaBodyInterceptor;
import com.theoplayer.android.api.network.http.JavaHTTPInterceptor;
import com.theoplayer.android.api.network.http.RequestType;
import com.theoplayer.android.api.player.Player;
import com.theoplayer.android.api.util.JavaCallback;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * C13 live: plays the clean origin stream instead of the G-Mana SSAI stream.
 *
 * The C13 JS hard-codes G-Mana master URLs for the live channel, and G-Mana
 * splices its own ads into the broadcast's commercial breaks for each viewer.
 * The origin it wraps is public. This hook adds a THEOplayer HTTP interceptor to
 * every player. The interceptor redirects the G-Mana master request to the origin
 * master. Media3 then resolves the variant playlists and segments against the
 * origin, so every later request and refresh goes straight to CloudFront and never
 * touches G-Mana.
 *
 * The hook point is the React Native bridge's player factory,
 * ReactTHEOplayerContext.Companion.create(). It is not obfuscated (the bridge is
 * compiled without obfuscation) and it is the only place a C13 player is made.
 *
 * Fallback: if no origin can be resolved, the G-Mana stream plays, and SlateSwap
 * replaces its ad segments with black, silent slates of the same length.
 */
public class TheoLiveHook implements Hook {

    private static final String TAG = "PATCH";
    private static final long RESOLVE_BUDGET_MS = 6000;

    private static final Set<Object> attached = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    static native Object create_backup(Object thiz, Object reactContext, Object playerConfig);

    // Replacement for the instance method Companion.create(ThemedReactContext, PlayerConfigAdapter).
    static Object create(Object thiz, Object reactContext, Object playerConfig) {
        Object context = create_backup(thiz, reactContext, playerConfig);
        try {
            if (context != null) {
                Player player = (Player) context.getClass().getMethod("getPlayer").invoke(context);
                if (player != null && attached.add(player)) {
                    player.getNetwork().addHTTPInterceptor(GmanaInterceptor.INSTANCE);
                    Log.i(TAG, "TheoNet: interceptor added to player@" + System.identityHashCode(player));
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "TheoNet: attach: " + t);
        }
        return context;
    }

    static final class GmanaInterceptor implements JavaHTTPInterceptor {
        static final GmanaInterceptor INSTANCE = new GmanaInterceptor();

        private static final AtomicInteger threadIds = new AtomicInteger();
        private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "patch-gmana-" + threadIds.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        // Origin masters this interceptor redirected to, so a failing one can be re-resolved.
        private static final Set<String> rewritten = ConcurrentHashMap.newKeySet();
        private static final AtomicInteger leaks = new AtomicInteger();

        @Override
        public void onRequestAsync(final InterceptableHTTPRequest request, final JavaCallback callback) {
            boolean handedOff = false;
            try {
                final URL url = request.getUrl();
                if (Hls.SLATE_HOST.equals(url.getHost())) {
                    byte[] slate = Slate.bytes(url.getPath());
                    if (slate != null) {
                        Map<String, String> headers = new HashMap<>();
                        headers.put("content-type", "video/mp2t");
                        request.respondWith(new CompleteHTTPResponse(request, url, headers, 200, "OK", slate));
                    }
                } else if (request.getType() == RequestType.MANIFEST && Hls.isGmanaMaster(url)) {
                    // Resolving does network I/O; THEO awaits the callback, so finish it off-thread.
                    EXEC.execute(() -> {
                        try {
                            OriginResolver.Resolved origin = OriginResolver.resolve(url, RESOLVE_BUDGET_MS, false);
                            if (origin != null) {
                                request.setUrl(new URL(origin.url));
                                rewritten.add(origin.url);
                                Log.i(TAG, "TheoNet: master " + url.getHost() + url.getPath() + " -> " + origin.url);
                            } else {
                                Log.w(TAG, "TheoNet: no origin for " + url + " (slate fallback)");
                            }
                        } catch (Throwable t) {
                            Log.e(TAG, "TheoNet: resolve: " + t);
                        } finally {
                            callback.onSuccess(null);
                        }
                    });
                    handedOff = true;
                } else if (request.getType() == RequestType.SEGMENT && Hls.isGmanaAdSegment(url)) {
                    // Must never appear once the bypass works.
                    if (leaks.incrementAndGet() <= 20) {
                        Log.w(TAG, "TheoNet: LEAK " + url);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "TheoNet: request: " + t);
            } finally {
                if (!handedOff) {
                    callback.onSuccess(null);
                }
            }
        }

        @Override
        public void onResponseAsync(InterceptableHTTPResponse response, JavaCallback callback) {
            try {
                HTTPRequest request = response.getRequest();
                URL url = response.getUrl();
                if (request != null && request.getType() == RequestType.MANIFEST && url != null) {
                    String u = url.toString();
                    if (response.getStatus() >= 400 && rewritten.contains(u)) {
                        OriginResolver.invalidateOrigin(u);
                    } else if (response.getStatus() == 200 && Hls.isGmanaMedia(url)) {
                        // Only reached when the master could not be redirected.
                        swapAds(response, u);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "TheoNet: response: " + t);
            }
            callback.onSuccess(null);
        }

        private static void swapAds(InterceptableHTTPResponse response, final String playlistKey) {
            Map<String, String> headers = new HashMap<>();
            Map<String, String> original = response.getHeaders();
            if (original != null) {
                for (Map.Entry<String, String> h : original.entrySet()) {
                    String k = h.getKey();
                    if (k != null && !k.equalsIgnoreCase("content-length") && !k.equalsIgnoreCase("content-encoding")) {
                        headers.put(k, h.getValue());
                    }
                }
            }
            response.setHeaders(headers);
            response.onBody(new JavaBodyInterceptor() {
                @Override
                public void onBodyAsync(byte[] body, JavaCallback bodyCallback) {
                    byte[] out = body;
                    try {
                        SlateSwap.Result r = SlateSwap.apply(new String(body, StandardCharsets.UTF_8), playlistKey);
                        if (r.adSegments > 0) {
                            Log.i(TAG, "TheoNet: slate-swapped " + r.adSegments + " ad segments ("
                                    + String.format(java.util.Locale.ROOT, "%.2f", r.adSeconds) + " s)");
                        }
                        out = r.body.getBytes(StandardCharsets.UTF_8);
                    } catch (Throwable t) {
                        Log.e(TAG, "TheoNet: slate swap: " + t);
                    }
                    bodyCallback.onSuccess(out);
                }
            });
        }

        @Override
        public boolean equals(Object o) {
            return o == this;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    public void load() {
        try {
            OriginResolver.setStore(new Slate.PrefsStore());
            Class<?> companion = Class.forName("com.theoplayer.ReactTHEOplayerContext$Companion");
            Executable original = ArtHooks.find_function(companion, "create",
                    "(Lcom/facebook/react/uimanager/ThemedReactContext;Lcom/theoplayer/PlayerConfigAdapter;)"
                            + "Lcom/theoplayer/ReactTHEOplayerContext;");
            if (original == null) {
                Log.e(TAG, "TheoNet: ReactTHEOplayerContext.Companion.create not found");
                return;
            }
            Class<?>[] params = {Object.class, Object.class, Object.class};
            Method replacement = TheoLiveHook.class.getDeclaredMethod("create", params);
            Method backup = TheoLiveHook.class.getDeclaredMethod("create_backup", params);
            Log.i(TAG, "TheoNet: hooked ReactTHEOplayerContext.Companion.create = "
                    + ArtHooks.hook_function(original, replacement, backup));
        } catch (Throwable t) {
            Log.e(TAG, "TheoNet: " + t);
        }
    }

    public void unload() {
    }
}
