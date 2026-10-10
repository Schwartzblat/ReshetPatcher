package com.smali_generator.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.smali_generator.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Android glue for the live patch: the bundled slates, and the persistent origin cache.
 *
 * The slates are black, silent MPEG-TS clips (1024x576 H.264 + AAC 48 kHz, like
 * the broadcast) in assets/reshet_patch/slate/black_<ms>.ts, for 1 s to 10 s in
 * 0.5 s steps. They are served for https://slate.patch.invalid/black_<ms>.ts,
 * which SlateSwap writes into G-Mana playlists in place of ad segments.
 */
public final class Slate {

    private static final String TAG = "PATCH";
    private static final Pattern NAME = Pattern.compile("black_\\d+\\.ts");
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private Slate() {
    }

    /** @param path the request path, e.g. /black_6000.ts; null if it is not a bundled slate */
    public static byte[] bytes(String path) {
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        if (!NAME.matcher(name).matches()) {
            return null;
        }
        byte[] hit = CACHE.get(name);
        if (hit != null) {
            return hit;
        }
        Context context = Utils.getApplicationContext();
        if (context == null) {
            return null;
        }
        try (InputStream in = context.getAssets().open("reshet_patch/slate/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            byte[] bytes = out.toByteArray();
            CACHE.put(name, bytes);
            return bytes;
        } catch (Throwable t) {
            Log.e(TAG, "Slate: " + name + ": " + t);
            return null;
        }
    }

    /** Keeps resolved origins across launches, so a cold start skips the 1-3 resolve requests. */
    public static final class PrefsStore implements OriginResolver.Store {
        private SharedPreferences prefs() {
            Context context = Utils.getApplicationContext();
            return context == null ? null : context.getSharedPreferences("reshet_patch_gmana", Context.MODE_PRIVATE);
        }

        @Override
        public String get(String key) {
            SharedPreferences p = prefs();
            return p == null ? null : p.getString(key, null);
        }

        @Override
        public void put(String key, String value) {
            SharedPreferences p = prefs();
            if (p != null) {
                p.edit().putString(key, value).apply();
            }
        }

        @Override
        public void remove(String key) {
            SharedPreferences p = prefs();
            if (p != null) {
                p.edit().remove(key).apply();
            }
        }
    }
}
