package com.theoplayer.android.api.network.http;

import com.theoplayer.android.api.util.JavaCallback;

// Its onRequest/onResponse suspend methods are JVM defaults that await these callbacks.
public interface JavaHTTPInterceptor extends HTTPInterceptor {
    void onRequestAsync(InterceptableHTTPRequest request, JavaCallback callback);

    void onResponseAsync(InterceptableHTTPResponse response, JavaCallback callback);
}
