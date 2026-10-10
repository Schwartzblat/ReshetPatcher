package com.theoplayer.android.api.network.http;

import java.util.Map;

public interface InterceptableHTTPResponse extends HTTPResponse {
    void setHeaders(Map<String, String> headers);

    void onBody(BodyInterceptor interceptor);
}
