package com.smali_generator.patches;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * Google Mobile Ads, in both app variants: every full-screen load and every
 * banner load fails at once with "no fill".
 *
 * A silent no-op is not enough, because both variants wait for an outcome. The
 * legacy AdLogic keeps the page hidden (canDisplayContent=false) until the
 * interstitial fails or is dismissed, and the C13 JS holds the home screen for
 * up to 8 s waiting on the launch interstitial. So each hook posts
 * onAdFailedToLoad to the main thread, the way the SDK itself would.
 *
 * All targets are public GMA API, kept by the SDK's consumer rules, so they are
 * hooked by name. MobileAds.initialize is left alone: the RN module's
 * initialize() promise waits on its listener.
 */
public class GmaHook implements Hook {

    private static final String TAG = "PATCH";
    private static final String ADS = "com.google.android.gms.ads.";
    private static final int ERROR_CODE_NO_FILL = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static Constructor<?> loadAdErrorCtor;
    // Taken from the public base classes: invoking the override found on an
    // anonymous (non-public) callback class throws IllegalAccessException.
    private static Method loadCallbackFailed;
    private static Method listenerFailed;
    private static Method getAdListener;

    private static Object newNoFillError() throws Exception {
        return loadAdErrorCtor.newInstance(ERROR_CODE_NO_FILL, "No fill", "com.google.android.gms.ads", null, null);
    }

    private static void postLoadFailure(final Object callback, final String kind, final String unit) {
        Log.i(TAG, "Gma: blocked " + kind + " " + unit);
        if (callback == null) {
            return;
        }
        MAIN.post(() -> {
            try {
                loadCallbackFailed.invoke(callback, newNoFillError());
            } catch (Throwable t) {
                Log.e(TAG, "Gma: " + kind + " failure callback: " + t);
            }
        });
    }

    // Static targets: no thiz. One replacement per target, so the log names it.
    static void interstitial_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "interstitial", unit);
    }

    static void admanager_interstitial_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "admanager interstitial", unit);
    }

    static void app_open_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "app-open", unit);
    }

    static void rewarded_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded", unit);
    }

    static void rewarded_admanager_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded (admanager)", unit);
    }

    static void rewarded_interstitial_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded interstitial", unit);
    }

    static void rewarded_interstitial_admanager_load(Context context, String unit, Object request, Object callback) {
        postLoadFailure(callback, "rewarded interstitial (admanager)", unit);
    }

    private static void blockBanner(Object adView, String kind) {
        Log.i(TAG, "Gma: blocked " + kind);
        try {
            ((View) adView).setVisibility(View.GONE);
        } catch (Throwable t) {
            Log.e(TAG, "Gma: hide " + kind + ": " + t);
        }
        MAIN.post(() -> {
            try {
                Object listener = getAdListener.invoke(adView);
                if (listener != null) {
                    listenerFailed.invoke(listener, newNoFillError());
                }
            } catch (Throwable t) {
                Log.e(TAG, "Gma: " + kind + " failure callback: " + t);
            }
        });
    }

    // Instance targets: leading thiz.
    static void banner_load(Object thiz, Object request) {
        blockBanner(thiz, "banner");
    }

    static void admanager_banner_load(Object thiz, Object request) {
        blockBanner(thiz, "admanager banner");
    }

    private static void hookStaticLoad(String cls, String sig, String replacement) {
        try {
            Executable original = ArtHooks.find_function(Class.forName(ADS + cls), "load", sig);
            if (original == null) {
                Log.w(TAG, "Gma: " + cls + ".load" + sig + " not in this build");
                return;
            }
            Method repl = GmaHook.class.getDeclaredMethod(
                    replacement, Context.class, String.class, Object.class, Object.class);
            Log.i(TAG, "Gma: hooked " + cls + ".load = " + ArtHooks.hook_function(original, repl));
        } catch (Throwable t) {
            Log.e(TAG, "Gma: " + cls + ".load: " + t);
        }
    }

    private static void hookBannerLoad(String cls, String sig, String replacement) {
        try {
            Executable original = ArtHooks.find_function(Class.forName(ADS + cls), "loadAd", sig);
            if (original == null) {
                Log.w(TAG, "Gma: " + cls + ".loadAd" + sig + " not in this build");
                return;
            }
            Method repl = GmaHook.class.getDeclaredMethod(replacement, Object.class, Object.class);
            Log.i(TAG, "Gma: hooked " + cls + ".loadAd = " + ArtHooks.hook_function(original, repl));
        } catch (Throwable t) {
            Log.e(TAG, "Gma: " + cls + ".loadAd: " + t);
        }
    }

    public void load() {
        try {
            Class<?> loadAdError = Class.forName(ADS + "LoadAdError");
            loadAdErrorCtor = loadAdError.getConstructor(int.class, String.class, String.class,
                    Class.forName(ADS + "AdError"), Class.forName(ADS + "ResponseInfo"));
            loadCallbackFailed = Class.forName(ADS + "AdLoadCallback").getMethod("onAdFailedToLoad", loadAdError);
            listenerFailed = Class.forName(ADS + "AdListener").getMethod("onAdFailedToLoad", loadAdError);
            getAdListener = Class.forName(ADS + "BaseAdView").getMethod("getAdListener");
        } catch (Throwable t) {
            // Without the failure callback, the legacy app would keep its content hidden. Hook nothing.
            Log.e(TAG, "Gma: GMA API not resolvable, not hooking: " + t);
            return;
        }

        final String ctx = "Landroid/content/Context;Ljava/lang/String;";
        final String adRequest = "Lcom/google/android/gms/ads/AdRequest;";
        final String amRequest = "Lcom/google/android/gms/ads/admanager/AdManagerAdRequest;";

        hookStaticLoad("interstitial.InterstitialAd",
                "(" + ctx + adRequest + "Lcom/google/android/gms/ads/interstitial/InterstitialAdLoadCallback;)V",
                "interstitial_load");
        hookStaticLoad("admanager.AdManagerInterstitialAd",
                "(" + ctx + amRequest + "Lcom/google/android/gms/ads/admanager/AdManagerInterstitialAdLoadCallback;)V",
                "admanager_interstitial_load");
        hookStaticLoad("appopen.AppOpenAd",
                "(" + ctx + adRequest + "Lcom/google/android/gms/ads/appopen/AppOpenAd$AppOpenAdLoadCallback;)V",
                "app_open_load");
        hookStaticLoad("rewarded.RewardedAd",
                "(" + ctx + adRequest + "Lcom/google/android/gms/ads/rewarded/RewardedAdLoadCallback;)V",
                "rewarded_load");
        hookStaticLoad("rewarded.RewardedAd",
                "(" + ctx + amRequest + "Lcom/google/android/gms/ads/rewarded/RewardedAdLoadCallback;)V",
                "rewarded_admanager_load");
        hookStaticLoad("rewardedinterstitial.RewardedInterstitialAd",
                "(" + ctx + adRequest + "Lcom/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAdLoadCallback;)V",
                "rewarded_interstitial_load");
        hookStaticLoad("rewardedinterstitial.RewardedInterstitialAd",
                "(" + ctx + amRequest + "Lcom/google/android/gms/ads/rewardedinterstitial/RewardedInterstitialAdLoadCallback;)V",
                "rewarded_interstitial_admanager_load");

        // AdView does not override loadAd, so the BaseAdView hook covers it.
        // AdManagerAdView adds its own overload taking AdManagerAdRequest.
        hookBannerLoad("BaseAdView", "(" + adRequest + ")V", "banner_load");
        hookBannerLoad("admanager.AdManagerAdView", "(" + amRequest + ")V", "admanager_banner_load");
    }

    public void unload() {
    }
}
