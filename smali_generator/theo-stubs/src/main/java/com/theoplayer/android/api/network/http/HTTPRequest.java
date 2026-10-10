package com.theoplayer.android.api.network.http;

import java.net.URL;
import java.util.Map;

public interface HTTPRequest {
    URL getUrl();

    String getMethod();

    Map<String, String> getHeaders();

    RequestType getType();
}
