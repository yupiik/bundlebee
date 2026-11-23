/*
 * Copyright (c) 2021 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.bundlebee.core.http;

import static java.util.Optional.empty;

import io.yupiik.fusion.framework.api.container.Types;
import io.yupiik.fusion.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;

public class JsonHttpResponse implements HttpResponse<Map<String, Object>> {
    private final JsonMapper jsonb;
    private final HttpResponse<String> delegate;

    @Override
    public String toString() {
        final URI uri = request().uri();
        return '(' + request().method() + " " + (uri == null ? "" : uri.toString()) + ") " + statusCode();
    }

    @Override
    public Optional<HttpResponse<Map<String, Object>>> previousResponse() {
        return empty();
    }

    @Override
    public Map<String, Object> body() {
        return jsonb.fromString(
                new Types.ParameterizedTypeImpl(Map.class, String.class, Object.class), delegate.body());
    }

    @Override
    public int statusCode() {
        return delegate.statusCode();
    }

    @Override
    public HttpRequest request() {
        return delegate.request();
    }

    @Override
    public HttpHeaders headers() {
        return delegate.headers();
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return delegate.sslSession();
    }

    @Override
    public URI uri() {
        return delegate.uri();
    }

    @Override
    public HttpClient.Version version() {
        return delegate.version();
    }

    public JsonHttpResponse(final JsonMapper jsonb, final HttpResponse<String> delegate) {
        this.jsonb = jsonb;
        this.delegate = delegate;
    }
}
