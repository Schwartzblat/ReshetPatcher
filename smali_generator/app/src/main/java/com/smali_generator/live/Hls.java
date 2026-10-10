package com.smali_generator.live;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL shapes and playlist text utilities for G-Mana live streams. Pure Java, no
 * Android: it is unit-tested on the host against captured playlists.
 *
 * G-Mana is an SSAI proxy. Its master is /media/<uuid>/mainManifest.m3u8 on any
 * host (reshet.g-mana.live, or a CloudFront host in front of it), and it wraps an
 * AWS-MediaPackage-style origin, /out/v1/<32 hex>/index.m3u8. The origin's
 * endpoint id shows up in every G-Mana content-segment URI.
 */
public final class Hls {

    public static final String SLATE_HOST = "slate.patch.invalid";

    static final Pattern GM_MASTER = Pattern.compile(
            "^/media/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/mainManifest\\.m3u8$");
    static final Pattern GM_MEDIA = Pattern.compile(
            "^/media/[0-9a-f-]{36}/profile/\\d+/profileManifest\\.m3u8$");
    static final Pattern OR_MASTER = Pattern.compile("^/out/v1/[0-9a-f]{32}/index\\.m3u8$");
    // A content segment of the origin, possibly inside an /mrouter/ beacon wrapper.
    // Group 1: absolute host if the line has one; group 2: endpoint; group 3: sequence number.
    static final Pattern OR_SEG = Pattern.compile(
            "(?:https?://([^/\\s]+))?\\S*?/out/v1/([0-9a-f]{32})/index(?:_\\d+)?_(\\d+)\\.(?:ts|aac|m4s|mp4)");
    // The CDN host G-Mana names inside an ad beacon: .../hs/<host>/gmana-assets/...
    static final Pattern HS_HOST = Pattern.compile("/hs/([a-z0-9.-]+)/");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");

    private Hls() {
    }

    public static String uuidOfMaster(URL u) {
        if (u == null || !"https".equals(u.getProtocol())) {
            return null;
        }
        Matcher m = GM_MASTER.matcher(u.getPath());
        return m.matches() ? m.group(1) : null;
    }

    public static boolean isGmanaMaster(URL u) {
        return uuidOfMaster(u) != null;
    }

    public static boolean isGmanaMedia(URL u) {
        return u != null && GM_MEDIA.matcher(u.getPath()).matches();
    }

    public static boolean isOriginMaster(URL u) {
        return u != null && OR_MASTER.matcher(u.getPath()).matches();
    }

    public static boolean isGmanaHost(String host) {
        return host != null && host.toLowerCase(Locale.ROOT).endsWith("g-mana.live");
    }

    /** Same rules as the app's own JS (parseSegments / toManifestMarker). */
    static boolean isAdSegment(String uri, List<String> tags) {
        String l = uri.toLowerCase(Locale.ROOT);
        if (l.contains("/gmana-assets/") || l.contains("/preroll/") || l.contains("/midroll/")
                || l.contains("/postroll/")) {
            return true;
        }
        for (String t : tags) {
            if (t.startsWith("#EXT-X-ASSET")) {
                return true;
            }
        }
        return false;
    }

    /** A segment request that should never happen once the bypass works. */
    public static boolean isGmanaAdSegment(URL u) {
        return u != null && (isGmanaHost(u.getHost()) || u.getPath().contains("/gmana-assets/"));
    }

    static String[] lines(String body) {
        return body.split("\r?\n");
    }

    static String firstUri(String body) {
        for (String line : lines(body)) {
            String l = line.trim();
            if (!l.isEmpty() && !l.startsWith("#")) {
                return l;
            }
        }
        return null;
    }

    static boolean isPlaylist(String body) {
        return body != null && body.startsWith("#EXTM3U");
    }

    /**
     * Rewrites every URI in a playlist to an absolute one. A WebView reply has no
     * redirect to carry the base URL, so relative variant URIs would otherwise
     * resolve against the G-Mana URL the page asked for.
     */
    public static String absolutize(String body, URL base) {
        StringBuilder out = new StringBuilder(body.length() + 512);
        for (String line : lines(body)) {
            String l = line.trim();
            if (l.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (!l.startsWith("#")) {
                out.append(resolve(base, l)).append('\n');
                continue;
            }
            Matcher m = URI_ATTR.matcher(l);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement("URI=\"" + resolve(base, m.group(1)) + "\""));
            }
            m.appendTail(sb);
            out.append(sb).append('\n');
        }
        return out.toString();
    }

    static String resolve(URL base, String ref) {
        try {
            return new URL(base, ref).toString();
        } catch (MalformedURLException e) {
            return ref;
        }
    }

    /** What a G-Mana media playlist reveals about the origin it wraps. */
    static final class OriginHint {
        String endpoint;
        String segmentHost;
        final List<String> cdnHosts = new ArrayList<>();
    }

    static OriginHint scanMediaPlaylist(String body) {
        OriginHint hint = new OriginHint();
        for (String line : lines(body)) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#")) {
                continue;
            }
            Matcher seg = OR_SEG.matcher(l);
            if (hint.endpoint == null && seg.find()) {
                hint.endpoint = seg.group(2);
                String host = seg.group(1);
                if (host != null && !isGmanaHost(host)) {
                    hint.segmentHost = host;
                }
            }
            Matcher hs = HS_HOST.matcher(l);
            if (hs.find() && !hint.cdnHosts.contains(hs.group(1))) {
                hint.cdnHosts.add(hs.group(1));
            }
            if (l.startsWith("https://") && l.contains("/gmana-assets/")) {
                try {
                    String host = new URL(l).getHost();
                    if (!hint.cdnHosts.contains(host)) {
                        hint.cdnHosts.add(host);
                    }
                } catch (MalformedURLException ignored) {
                }
            }
        }
        return hint;
    }

    /** Origin sequence number of a content-segment line, or -1. Stable across refreshes. */
    static long originSequence(String uri) {
        Matcher m = OR_SEG.matcher(uri);
        return m.find() ? Long.parseLong(m.group(3)) : -1;
    }
}
