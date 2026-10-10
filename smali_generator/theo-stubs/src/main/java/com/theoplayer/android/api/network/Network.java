package com.theoplayer.android.api.network;

import com.theoplayer.android.api.network.http.HTTPInterceptor;

public interface Network {
    void addHTTPInterceptor(HTTPInterceptor interceptor);

    void removeHTTPInterceptor(HTTPInterceptor interceptor);
}
