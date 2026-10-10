package com.smali_generator.live;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fallback for when no origin can be resolved: rewrites a G-Mana media playlist
 * so that every inserted ad segment plays a bundled black, silent slate of the
 * same length instead.
 *
 * Dropping the ad segments is not an option. At the live edge the playlist would
 * stop growing for the length of the pod, and Media3 fails a live playlist that
 * has not grown for 3.5 target durations (PlaylistStuckException). A 1:1 swap
 * keeps the segment count, so MEDIA-SEQUENCE is unchanged.
 *
 * Each slate is its own file with timestamps starting at zero, so each one needs
 * its own #EXT-X-DISCONTINUITY. G-Mana only marks the two ends of a pod, so the
 * discontinuities between consecutive ads are inserted here. Inserted
 * discontinuities that later slide out of the window must be added to
 * DISCONTINUITY-SEQUENCE, or the discontinuity numbers of the segments still in
 * the window shift between refreshes. That bookkeeping is the per-playlist state:
 * for each content segment (keyed by its origin sequence number), the running
 * count of discontinuities inserted up to and including it.
 */
public final class SlateSwap {

    private static final int MAX_PLAYLISTS = 8;
    private static final long SLATE_MIN_MS = 1000;
    private static final long SLATE_MAX_MS = 10000;

    private static final class State {
        // origin sequence number -> inserted discontinuities up to and including that segment, all time
        Map<Long, Long> insertedThrough = new HashMap<>();
        long slidOut;
        int maxTargetDuration;
    }

    private static final Map<String, State> STATES = new LinkedHashMap<String, State>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, State> eldest) {
            return size() > MAX_PLAYLISTS;
        }
    };

    private SlateSwap() {
    }

    /** The slate file nearest the ad's EXTINF: slates ship in 0.5 s steps from 1 s to 10 s. */
    static String slateUrl(double seconds) {
        long ms = Math.round(seconds * 2) * 500;
        ms = Math.max(SLATE_MIN_MS, Math.min(SLATE_MAX_MS, ms));
        return "https://" + Hls.SLATE_HOST + "/black_" + ms + ".ts";
    }

    private static double extinf(List<String> tags) {
        for (String t : tags) {
            if (t.startsWith("#EXTINF:")) {
                String v = t.substring(8);
                int comma = v.indexOf(',');
                try {
                    return Double.parseDouble(comma >= 0 ? v.substring(0, comma) : v);
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static boolean isPlaylistTag(String l) {
        return l.startsWith("#EXTM3U") || l.startsWith("#EXT-X-VERSION") || l.startsWith("#EXT-X-MEDIA-SEQUENCE")
                || l.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || l.startsWith("#EXT-X-PLAYLIST-TYPE")
                || l.startsWith("#EXT-X-START") || l.startsWith("#EXT-X-ENDLIST")
                || l.startsWith("#EXT-X-ALLOW-CACHE");
    }

    /** Result counts, for logging. */
    public static final class Result {
        public final String body;
        public final int adSegments;
        public final double adSeconds;

        Result(String body, int adSegments, double adSeconds) {
            this.body = body;
            this.adSegments = adSegments;
            this.adSeconds = adSeconds;
        }
    }

    /**
     * @param playlistKey identifies one media playlist across refreshes (its full URL:
     *                    G-Mana's _uid query is per session).
     */
    public static Result apply(String body, String playlistKey) {
        if (!Hls.isPlaylist(body)) {
            return new Result(body, 0, 0);
        }
        String[] lines = Hls.lines(body);
        List<String> out = new ArrayList<>(lines.length + 16);
        List<String> tags = new ArrayList<>();
        int tdIndex = -1;
        int dsIndex = -1;
        int tdIn = 0;
        long dsIn = 0;
        int ads = 0;
        double adSeconds = 0;
        double maxExtinf = 0;
        boolean prevWasAd = false;
        long inserted = 0;                       // inserted so far in this window
        long firstContentSeq = -1;
        long insertedThroughFirst = 0;
        Map<Long, Long> insertedThrough = new HashMap<>();

        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) {
                continue;
            }
            if (l.startsWith("#EXT-X-TARGETDURATION:")) {
                tdIn = parseInt(l.substring(22));
                tdIndex = out.size();
                out.add(l);
            } else if (l.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                dsIn = parseLong(l.substring(30));
                dsIndex = out.size();
                out.add(l);
            } else if (isPlaylistTag(l)) {
                out.add(l);
            } else if (l.startsWith("#")) {
                tags.add(l);
            } else {
                double d = extinf(tags);
                maxExtinf = Math.max(maxExtinf, d);
                if (Hls.isAdSegment(l, tags)) {
                    tags.removeIf(t -> t.startsWith("#EXT-X-ASSET"));
                    if (!tags.contains("#EXT-X-DISCONTINUITY")) {
                        tags.add(0, "#EXT-X-DISCONTINUITY");
                        inserted++;
                    }
                    out.addAll(tags);
                    out.add(slateUrl(d));
                    ads++;
                    adSeconds += d;
                    prevWasAd = true;
                } else {
                    if (prevWasAd && !tags.contains("#EXT-X-DISCONTINUITY")) {
                        tags.add(0, "#EXT-X-DISCONTINUITY");
                        inserted++;
                    }
                    out.addAll(tags);
                    out.add(l);
                    long seq = Hls.originSequence(l);
                    if (seq >= 0) {
                        insertedThrough.put(seq, inserted);
                        if (firstContentSeq < 0) {
                            firstContentSeq = seq;
                            insertedThroughFirst = inserted;
                        }
                    }
                    prevWasAd = false;
                }
                tags.clear();
            }
        }
        out.addAll(tags);   // trailing tags with no segment yet

        long slidOut;
        int td;
        synchronized (STATES) {
            State st = STATES.get(playlistKey);
            if (st == null) {
                st = new State();
                STATES.put(playlistKey, st);
            }
            // The first content segment's all-time count is known if it was in the
            // previous window. Everything inserted before it that is no longer in this
            // window has slid out.
            Long allTime = firstContentSeq >= 0 ? st.insertedThrough.get(firstContentSeq) : null;
            if (allTime != null) {
                st.slidOut = allTime - insertedThroughFirst;
            }
            slidOut = st.slidOut;
            Map<Long, Long> next = new HashMap<>(insertedThrough.size() * 2);
            for (Map.Entry<Long, Long> e : insertedThrough.entrySet()) {
                next.put(e.getKey(), e.getValue() + slidOut);
            }
            st.insertedThrough = next;
            td = Math.max(Math.max(tdIn, st.maxTargetDuration), (int) Math.ceil(maxExtinf));
            st.maxTargetDuration = td;
        }

        if (ads == 0 && slidOut == 0 && td == tdIn) {
            return new Result(body, 0, 0);
        }
        if (tdIndex >= 0) {
            out.set(tdIndex, "#EXT-X-TARGETDURATION:" + td);
        }
        if (dsIndex >= 0) {
            out.set(dsIndex, "#EXT-X-DISCONTINUITY-SEQUENCE:" + (dsIn + slidOut));
        } else if (slidOut > 0) {
            int at = tdIndex >= 0 ? tdIndex + 1 : Math.min(1, out.size());
            out.add(at, "#EXT-X-DISCONTINUITY-SEQUENCE:" + slidOut);
        }
        StringBuilder sb = new StringBuilder(body.length() + 1024);
        for (String l : out) {
            sb.append(l).append('\n');
        }
        return new Result(sb.toString(), ads, adSeconds);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String describe(Result r) {
        return String.format(Locale.ROOT, "%d ad segments (%.2f s)", r.adSegments, r.adSeconds);
    }
}
