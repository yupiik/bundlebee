/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.test.http;

import lombok.Getter;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.DefaultResponseLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class SpyingResponseLocator extends DefaultResponseLocator {
    @Getter
    private final List<Response> found = new ArrayList<>();

    public SpyingResponseLocator(final String test) {
        super("mock/http/", test);
    }

    @Override
    public Optional<Response> findMatching(Request request, Predicate<String> headerFilter) {
        return super.findMatching(request, headerFilter)
                .map(it -> {
                    synchronized (found) {
                        found.add(it);
                    }
                    return it;
                });
    }
}