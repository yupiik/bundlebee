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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.kube.HttpKubeClient;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import java.io.IOException;
import java.io.StringReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

@Log
@Dependent
public class HttpCommand implements CompletingExecutable {
    @Inject
    private HttpKubeClient kube;

    @Inject
    @Description("HTTP verb to use for the request.")
    @ConfigProperty(name = "bundlebee.http.method", defaultValue = "GET")
    private String method;

    @Inject
    @Description("HTTP payload to use for the request - skipped for method different from `PATCH`, `POST` and `PUT`.")
    @ConfigProperty(name = "bundlebee.http.payload", defaultValue = "")
    private String payload;

    @Inject
    @Description("Request HTTP headers (in properties format).")
    @ConfigProperty(name = "bundlebee.http.headers", defaultValue = "Accept: application/json\nContent-Type: application/json")
    private String headers;

    @Inject
    @Description("" +
            "HTTP request path (optionally with query parameters). " +
            "You can use the absolute kubernetes URL but if the value does not start with a protocol (`http` or `https`) " +
            "then it will be prefixed by the Kubernetes API base.")
    @ConfigProperty(name = "bundlebee.http.path", defaultValue = "/api/")
    private String path;

    @Inject
    @Description("If `true`, only the payload of the response will be logged if successful.")
    @ConfigProperty(name = "bundlebee.http.payloadOnly", defaultValue = "true")
    private boolean payloadOnly;

    @Inject
    @Description("If `true`, an exception if thrown if the status is not between 200 and 299 (making the execution a failure).")
    @ConfigProperty(name = "bundlebee.http.failOnError", defaultValue = "true")
    private boolean failOnError;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "failOnError":
            case "payloadOnly":
                return Stream.of("false", "true");
            case "method":
                return Stream.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
            default:
                return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "http";
    }

    @Override
    public String description() {
        return "Execute a HTTP request over Kubernetes client. " +
                "This is mainly for not yet existing commands and to reuse all the client auto-configuration.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var requestBuilder = HttpRequest.newBuilder()
                .method(this.method, List.of("PATCH", "POST", "PUT").contains(method) ?
                        HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8) :
                        HttpRequest.BodyPublishers.noBody());
        final var properties = new Properties();
        try (final var reader = new StringReader(headers)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
        properties.stringPropertyNames().forEach(name -> requestBuilder.setHeader(name, properties.getProperty(name)));
        return kube.execute(requestBuilder, path)
                .thenApply(response -> {
                    if (isSuccess(response.statusCode())) {
                        if (payloadOnly) {
                            log.info(ofNullable(response.body()).orElse(""));
                            return response;
                        }
                        log.info(toHttpResponseDump(response));
                        return response;
                    }
                    final var error = "Invalid HTTP response for request " + method + " " + path + ":\n" +
                            "\n" + toHttpResponseDump(response);
                    if (failOnError) {
                        throw new IllegalStateException(error);
                    }
                    log.severe(error);
                    return response;
                });
    }

    private String toHttpResponseDump(final HttpResponse<String> response) {
        return "HTTP/1.1\n" +
                response.headers().map().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(it -> it.getKey() + ": " + String.join(", ", it.getValue()))
                        .collect(joining("\n", "", "\n")) + '\n' +
                ofNullable(response.body()).orElse("");
    }

    private boolean isSuccess(final int statusCode) {
        return statusCode > 199 && statusCode < 300;
    }
}
