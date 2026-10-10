package com.theoplayer.android.api.network.http;

import java.net.URL;

public interface InterceptableHTTPRequest extends HTTPRequest {
    void setUrl(URL url);

    void respondWith(CompleteHTTPResponse response);
}
