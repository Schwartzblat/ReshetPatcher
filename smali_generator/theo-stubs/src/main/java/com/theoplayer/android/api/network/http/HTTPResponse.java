package com.theoplayer.android.api.network.http;

import java.net.URL;
import java.util.Map;

public interface HTTPResponse {
    HTTPRequest getRequest();

    URL getUrl();

    int getStatus();

    Map<String, String> getHeaders();
}
