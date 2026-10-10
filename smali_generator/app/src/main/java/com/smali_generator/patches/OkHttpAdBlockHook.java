package com.smali_generator.patches;

import android.util.Log;

import com.arthooks.ArtHooks;
import com.smali_generator.Hook;
import com.smali_generator.utils.AdBlocklist;

import java.io.IOException;
import java.lang.reflect.Method;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * C13 VOD: makes the JS see an ad schedule with zero breaks.
 *
 * For VOD the C13 JS fetches the GAM VMAP itself (scheduleOwner 'app'). It holds
 * the source for up to 8 s while it does, and then waits up to 15 s at every
 * midroll cue for the ad it scheduled to begin. TheoImaHook alone drops those ads
 * natively, but the waits remain. The VMAP fetch is answered with a schedule whose
 * only break is out of reach (AdBlocklist.NEVER_REACHED_VMAP), so the JS resolves
 * at once and waits for nothing. An empty VMAP or an error would be worse: the JS
 * counts both as a failed resolution, retries, and holds the source for 8 s.
 *
 * The JS fetch reaches OkHttp through RN networking and expo-fetch, which each
 * build their own client. So the interceptor is added in OkHttpClient.Builder.build(),
 * which every client goes through. OkHttp is compileOnly: these classes resolve
 * against the app's own OkHttp 4.12 at runtime. MediaType.parse is reached
 * through Companion because R8 stripped the static bridge.
 */
public class OkHttpAdBlockHook implements Hook {

    private static final String TAG = "PATCH";

    static final class AdInterceptor implements Interceptor {
        static final AdInterceptor INSTANCE = new AdInterceptor();
        private static final MediaType XML = MediaType.Companion.parse("text/xml; charset=utf-8");

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            try {
                HttpUrl url = request.url();
                String host = url.host();
                if (AdBlocklist.isGoogleAdHost(host)) {
                    String path = url.encodedPath();
                    if (AdBlocklist.isAdTagRequest(host, path)) {
                        String body = AdBlocklist.appAdTagBody(url.queryParameter("output"), url.queryParameter("ad_rule"));
                        Log.i(TAG, "OkAd: " + (AdBlocklist.NEVER_REACHED_VMAP.equals(body) ? "never-reached VMAP" : "empty VAST")
                                + " <- " + host + path + " iu=" + url.queryParameter("iu"));
                        return reply(request, 200, "OK", ResponseBody.create(body, XML));
                    }
                    Log.i(TAG, "OkAd: blocked " + host + path);
                    return reply(request, 204, "No Content", ResponseBody.create("", null));
                }
            } catch (Throwable t) {
                Log.e(TAG, "OkAd: " + t);
            }
            return chain.proceed(request);
        }

        private static Response reply(Request request, int code, String message, ResponseBody body) {
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .header("Cache-Control", "no-store")
                    .body(body)
                    .build();
        }
    }

    // Backup: ArtHooks points it at the original body. It must be static native,
    // because dex2oat would inline a Java body into build().
    static native Object build_backup(Object thiz);

    // Replacement for the instance method OkHttpClient$Builder.build(): static, leading thiz.
    static Object build(Object thiz) {
        try {
            OkHttpClient.Builder builder = (OkHttpClient.Builder) thiz;
            // newBuilder() copies interceptors, so a client derived from a patched one already has it.
            if (!builder.interceptors().contains(AdInterceptor.INSTANCE)) {
                builder.addInterceptor(AdInterceptor.INSTANCE);
            }
        } catch (Throwable t) {
            Log.e(TAG, "OkAd: add interceptor: " + t);
        }
        return build_backup(thiz);
    }

    public void load() {
        try {
            Method original = OkHttpClient.Builder.class.getDeclaredMethod("build");
            boolean ok = ArtHooks.hook_function(original,
                    OkHttpAdBlockHook.class.getDeclaredMethod("build", Object.class),
                    OkHttpAdBlockHook.class.getDeclaredMethod("build_backup", Object.class));
            Log.i(TAG, "OkAd: hooked OkHttpClient$Builder.build = " + ok);
        } catch (Throwable t) {
            Log.e(TAG, "OkAd: " + t);
        }
    }

    public void unload() {
    }
}
