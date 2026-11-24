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
package io.yupiik.bundlebee.core.event;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class OnKubeRequest {
    private final HttpRequest request;
    // overrides the default request if set
    private HttpRequest userRequest;
    // pre-computed response if provided, if so userRequest is ignored
    private CompletionStage<HttpResponse<String>> userResponse;

    public OnKubeRequest(final HttpRequest request) {
        this.request = request;
    }

    public HttpRequest getRequest() {
        return this.request;
    }

    public HttpRequest getUserRequest() {
        return this.userRequest;
    }

    public CompletionStage<HttpResponse<String>> getUserResponse() {
        return this.userResponse;
    }

    public void setUserRequest(final HttpRequest userRequest) {
        this.userRequest = userRequest;
    }

    public void setUserResponse(final CompletionStage<HttpResponse<String>> userResponse) {
        this.userResponse = userResponse;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof OnKubeRequest other)) {
            return false;
        }
        return Objects.equals(this.getRequest(), other.getRequest())
                && Objects.equals(this.getUserRequest(), other.getUserRequest())
                && Objects.equals(this.getUserResponse(), other.getUserResponse());
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getRequest(), this.getUserRequest(), this.getUserResponse());
    }

    @Override
    public String toString() {
        return "OnKubeRequest(request=" + this.getRequest() + ", userRequest=" + this.getUserRequest()
                + ", userResponse=" + this.getUserResponse() + ")";
    }
}
