package com.smali_generator;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.arthooks.ArtHooks;
import com.smali_generator.patches.GmaHook;
import com.smali_generator.patches.OkHttpAdBlockHook;
import com.smali_generator.patches.TheoImaHook;
import com.smali_generator.patches.TheoLiveHook;
import com.smali_generator.patches.WebViewAdBlockHook;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Entry point of the patch.
 *
 * Stitch adds this class to the target app's AndroidManifest as
 *   <provider android:name="com.smali_generator.InitProviderReshet"
 *             android:authorities="<target package>.com.smali_generator.InitProviderReshet"
 *             android:exported="false"
 *             android:initOrder="2147483647"/>
 * so onCreate() runs before the host Application's onCreate.
 *
 * makoPatcher keeps its hook list in TheAmazingPatch and delegates to it; here
 * the list lives on the provider itself, so there is one place to register a
 * hook. Classes from dex files the app has not loaded yet may not resolve this
 * early: if Class.forName() throws inside a hook, hook lazily instead.
 */
@SuppressWarnings("unused")
public class InitProviderReshet extends ContentProvider {

    private static final String TAG = "PATCH";

    // Register Hook implementations from patches/ here.
    static Hook[] hooks = {
            new TheoImaHook(),         // C13 client-side IMA video ads
            new GmaHook(),             // GMA interstitial/app-open/rewarded/banner, both variants
            new WebViewAdBlockHook(),  // WebView ad requests, and the web player's live stream off G-Mana
            new OkHttpAdBlockHook(),   // ad-schedule answers for the C13 JS's own VMAP/VAST fetches
            new TheoLiveHook(),        // C13 live stream off G-Mana, onto the clean origin
    };

    static final AtomicBoolean is_loaded = new AtomicBoolean(false);

    @Override
    public boolean onCreate() {
        Log.i(TAG, "InitProviderReshet: onCreate called");
        on_load();
        return true;
    }

    public static void on_load() {
        if (is_loaded.getAndSet(true)) {
            return;
        }
        Log.i(TAG, "Patch loaded!");
        try {
            if (!ArtHooks.is_available()) {
                // libarthooks.so did not load (wrong --arch?): every hook would silently no-op.
                Log.e(TAG, "ArtHooks unavailable, no hooks installed");
                return;
            }
        } catch (Throwable t) {
            Log.e(TAG, "ArtHooks failed to load, no hooks installed", t);
            return;
        }
        for (Hook hook : hooks) {
            try {
                hook.load();
            } catch (Throwable t) {
                // Catch Throwable, not Exception: we run before the host
                // Application, where a NoClassDefFoundError out of ArtHooks would
                // take the app down. One bad hook must not stop the rest either.
                Log.e(TAG, "Hook " + hook.getClass().getSimpleName() + " failed", t);
            }
        }
    }

    @Override public Cursor query(@NonNull Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(@NonNull Uri u) { return null; }
    @Override public Uri insert(@NonNull Uri u, ContentValues v) { return null; }
    @Override public int delete(@NonNull Uri u, String s, String[] a) { return 0; }
    @Override public int update(@NonNull Uri u, ContentValues v, String s, String[] a) { return 0; }
}
