package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * C13 video ads: drops every client-side IMA ad before THEOplayer schedules it.
 *
 * THEOplayer's GoogleImaIntegration implementation (an obfuscated class, found by
 * TheoImaIntegrationFinder) registers each ad break through
 * schedule(GoogleImaAdDescription). Both the source.ads path (setSource loops
 * over getAds()) and the RN AdsModule.schedule() path end there. When nothing is
 * scheduled, IMA never requests an ad, and the content plays at once with no
 * CONTENT_PAUSE/RESUME handshake for anything to wait on.
 */
public class TheoImaHook implements Hook {

    private static final String TAG = "PATCH";

    // Replacement for the instance method schedule(GoogleImaAdDescription): static, leading thiz.
    static void ima_schedule(Object thiz, Object adDescription) {
        String offset = "?";
        try {
            Object value = adDescription.getClass().getMethod("getTimeOffset").invoke(adDescription);
            offset = String.valueOf(value);
        } catch (Throwable ignored) {
        }
        Log.i(TAG, "TheoIma: swallowed schedule(timeOffset=" + offset + ")");
    }

    public void load() {
        try {
            Class<?> cls = Class.forName("{{THEO_IMA_INTEGRATION_CLASS_NAME}}");
            Executable original = ArtHooks.find_function(
                    cls, "{{THEO_IMA_SCHEDULE_METHOD_NAME}}", "{{THEO_IMA_SCHEDULE_METHOD_SIG}}");
            if (original == null) {
                Log.e(TAG, "TheoIma: schedule() not found");
                return;
            }
            Method replacement = TheoImaHook.class.getDeclaredMethod("ima_schedule", Object.class, Object.class);
            boolean ok = ArtHooks.hook_function(original, replacement);
            Log.i(TAG, "TheoIma: hooked " + cls.getName() + "." + "{{THEO_IMA_SCHEDULE_METHOD_NAME}}" + " = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "TheoIma: " + t);
        }
    }

    public void unload() {
    }
}
