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
package io.yupiik.bundlebee.junit5;

import com.sun.net.httpserver.HttpExchange;
import io.yupiik.bundlebee.junit5.impl.Injectable;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Represent kubernetes backend.
 * It is used to represent the mock of kubernetes API.
 * You can control the mock injecting your own handler in through this injectable type.
 */
@Injectable
public interface KubernetesApi {
    /**
     * @return enables the capture mode which means {@link #captured} will return the executed requests.
     */
    KubernetesApi capture();

    /**
     * @return enables the capture mode which means {@link #captured} will return the executed requests.
     */
    List<Request> captured();

    /**
     * @return base API URI of the server.
     */
    String base();

    /**
     * IMPORTANT: the exchange is implicitly closed, no need to do it in the handler.
     *
     * @param handler the mock handler.
     * @return {@code true} if the mock was implemented, {@code false} otherwise.
     */
    KubernetesApi exchangeHandler(final IOPredicate<HttpExchange> handler);

    interface IOPredicate<A> {
        boolean test(A exchange) throws IOException;
    }

    interface Request {
        String method();

        String uri();

        Map<String, String> headers();

        String payload();
    }
}
