package com.theoplayer.android.api.network.http;

import com.theoplayer.android.api.util.JavaCallback;

// onBody is a JVM default that awaits this callback's byte[] result.
public interface JavaBodyInterceptor extends BodyInterceptor {
    void onBodyAsync(byte[] body, JavaCallback callback);
}
