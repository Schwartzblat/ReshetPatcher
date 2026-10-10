package com.theoplayer.android.api.util;

public interface JavaCallback {
    void onSuccess(Object result);

    void onError(Throwable error);
}
