package com.theoplayer.android.api.network.http;

import java.net.URL;
import java.util.Map;

public final class CompleteHTTPResponse implements HTTPResponse {
    public CompleteHTTPResponse(HTTPRequest request, URL url, Map<String, String> headers,
                                int status, String statusText, byte[] body) {
        throw new UnsupportedOperationException("stub");
    }

    public HTTPRequest getRequest() { throw new UnsupportedOperationException("stub"); }

    public URL getUrl() { throw new UnsupportedOperationException("stub"); }

    public int getStatus() { throw new UnsupportedOperationException("stub"); }

    public Map<String, String> getHeaders() { throw new UnsupportedOperationException("stub"); }
}
