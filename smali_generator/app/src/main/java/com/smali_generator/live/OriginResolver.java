package com.smali_generator.live;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps a G-Mana master URL to the clean origin master it wraps, for example
 * reshet.g-mana.live/media/87f59c77-.../mainManifest.m3u8 to
 * dsk76kvc9kie6.cloudfront.net/out/v1/2f2bc414.../index.m3u8.
 *
 * The origin is the broadcast feed itself. It has the channel's own commercial
 * breaks, but no inserted ads: no G-Mana session, no ad segments, no beacons.
 *
 * Network I/O: never call this on the main thread.
 */
public final class OriginResolver {

    private static final String TAG = "PATCH";
    private static final String UA = "okhttp/4.12.0";
    private static final int BODY_CAP = 1 << 20;
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    // Origin CDN hosts seen in front of Reshet's endpoints; probed last.
    private static final String[] FALLBACK_HOSTS = {
            "dsk76kvc9kie6.cloudfront.net",
            "d198ztbnlup2iq.cloudfront.net",
            "d15ds134q59udk.cloudfront.net",
    };

    /** Optional persistent cache, installed from the Android side. */
    public interface Store {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    public static final class Resolved {
        public final String url;
        /** The origin master body; null when served from cache without fetching. */
        public final String body;

        Resolved(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    private static final Map<String, String> MEMORY = new ConcurrentHashMap<>();
    private static volatile Store store;

    private OriginResolver() {
    }

    public static void setStore(Store s) {
        store = s;
    }

    public static final class Http {
        public int code;
        public String location;
        public String body;
        public URL url;
    }

    public static Http get(URL url, boolean followRedirects, int timeoutMs, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        try {
            c.setInstanceFollowRedirects(followRedirects);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", UA);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    c.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            Http r = new Http();
            r.code = c.getResponseCode();
            r.location = c.getHeaderField("Location");
            r.url = c.getURL();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : read(in);
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0 && out.size() < BODY_CAP) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static int timeout(long deadline) {
        return (int) Math.max(500, Math.min(3000, deadline - now()));
    }

    private static String cached(String uuid) {
        String hit = MEMORY.get(uuid);
        if (hit != null) {
            return hit;
        }
        Store s = store;
        if (s == null) {
            return null;
        }
        try {
            String v = s.get(uuid);
            if (v == null) {
                return null;
            }
            int bar = v.lastIndexOf('|');
            long savedAt = Long.parseLong(v.substring(bar + 1));
            if (System.currentTimeMillis() - savedAt > TTL_MS) {
                s.remove(uuid);
                return null;
            }
            String url = v.substring(0, bar);
            MEMORY.put(uuid, url);
            return url;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void remember(String uuid, String origin) {
        MEMORY.put(uuid, origin);
        Store s = store;
        if (s != null) {
            try {
                s.put(uuid, origin + "|" + System.currentTimeMillis());
            } catch (Throwable ignored) {
            }
        }
    }

    /** Drops the mapping that produced this origin URL, so the next request re-resolves. */
    public static void invalidateOrigin(String origin) {
        for (Map.Entry<String, String> e : MEMORY.entrySet()) {
            if (e.getValue().equals(origin)) {
                MEMORY.remove(e.getKey());
                Store s = store;
                if (s != null) {
                    try {
                        s.remove(e.getKey());
                    } catch (Throwable ignored) {
                    }
                }
                Log.w(TAG, "Gmana: invalidated " + e.getKey() + " -> " + origin);
            }
        }
    }

    private static String stripQuery(URL u) {
        return u.getProtocol() + "://" + u.getAuthority() + u.getPath();
    }

    private static boolean isOriginMaster(Http r) {
        return r.code == 200 && Hls.isPlaylist(r.body) && r.body.contains("#EXT-X-STREAM-INF");
    }

    /**
     * @param needBody also return the origin master body (the WebView reply serves it directly)
     * @return the origin, or null if none could be found within the budget
     */
    public static Resolved resolve(URL gmanaMaster, long budgetMs, boolean needBody) throws IOException {
        String uuid = Hls.uuidOfMaster(gmanaMaster);
        if (uuid == null) {
            return null;
        }
        long start = now();
        long deadline = start + budgetMs;

        String hit = cached(uuid);
        if (hit != null) {
            if (!needBody) {
                Log.i(TAG, "Gmana: resolved " + uuid + " -> " + hit + " (cache)");
                return new Resolved(hit, null);
            }
            Http r = get(new URL(hit), true, timeout(deadline), null);
            if (isOriginMaster(r)) {
                Log.i(TAG, "Gmana: resolved " + uuid + " -> " + hit + " (cache)");
                return new Resolved(hit, r.body);
            }
            invalidateOrigin(hit);
        }

        // The query (_sid, player.pal, player.idxu, ...) is dropped, so the app's own G-Mana session is never touched.
        URL base = new URL(stripQuery(gmanaMaster));
        Http master = get(base, false, timeout(deadline), null);
        if (master.code >= 300 && master.code < 400 && master.location != null) {
            // G-Mana already redirects some channels (the subtitled feeds) straight to their origin.
            URL loc = new URL(base, master.location);
            if (Hls.isOriginMaster(loc)) {
                String origin = stripQuery(loc);
                remember(uuid, origin);
                Log.i(TAG, "Gmana: resolved " + uuid + " -> " + origin + " (redirect, " + (now() - start) + " ms)");
                return new Resolved(origin, needBody ? get(new URL(origin), true, timeout(deadline), null).body : null);
            }
            return null;
        }
        if (master.code != 200 || !Hls.isPlaylist(master.body)) {
            Log.w(TAG, "Gmana: master " + base + " answered " + master.code);
            return null;
        }
        String variant = Hls.firstUri(master.body);
        if (variant == null) {
            return null;
        }
        Http media = get(new URL(base, variant), true, timeout(deadline), null);
        if (media.code != 200 || !Hls.isPlaylist(media.body)) {
            Log.w(TAG, "Gmana: variant answered " + media.code);
            return null;
        }
        Hls.OriginHint hint = Hls.scanMediaPlaylist(media.body);
        if (hint.endpoint == null) {
            Log.w(TAG, "Gmana: no origin endpoint in " + uuid + "'s media playlist");
            return null;
        }

        Set<String> hosts = new LinkedHashSet<>();
        if (!Hls.isGmanaHost(base.getHost())) {
            hosts.add(base.getHost());          // CloudFront hosts serve /media and /out/v1 alike
        }
        if (hint.segmentHost != null) {
            hosts.add(hint.segmentHost);
        }
        hosts.addAll(hint.cdnHosts);
        for (String h : FALLBACK_HOSTS) {
            hosts.add(h);
        }
        int i = 0;
        for (String host : hosts) {
            i++;
            if (now() >= deadline) {
                break;
            }
            String origin = "https://" + host + "/out/v1/" + hint.endpoint + "/index.m3u8";
            try {
                Http r = get(new URL(origin), true, timeout(deadline), null);
                if (isOriginMaster(r)) {
                    remember(uuid, origin);
                    Log.i(TAG, "Gmana: resolved " + uuid + " -> " + origin + " (variant scan, host " + i + "/"
                            + hosts.size() + ", " + (now() - start) + " ms)");
                    return new Resolved(origin, r.body);
                }
            } catch (IOException e) {
                Log.w(TAG, "Gmana: probe " + host + ": " + e);
            }
        }
        Log.w(TAG, "Gmana: no origin answered for " + uuid + " (endpoint " + hint.endpoint + ")");
        return null;
    }
}
