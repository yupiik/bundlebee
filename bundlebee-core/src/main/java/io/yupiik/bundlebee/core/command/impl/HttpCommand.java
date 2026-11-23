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

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "http",
        description = "Execute a HTTP request over Kubernetes client. "
                + "This is mainly for not yet existing commands and to reuse all the client auto-configuration.")
public class HttpCommand implements CompletingExecutable {
    private final Logger log = Logger.getLogger(HttpCommand.class.getName());

    public HttpCommand(final Options options, final KubernetesClient kube) {
        this.kube = kube;
        this.method = options.method();
        this.payload = options.payload();
        this.headers = options.headers();
        this.path = options.path();
        this.payloadOnly = options.payloadOnly();
        this.failOnError = options.failOnError();
    }

    private final KubernetesClient kube;
    private String method;
    private String payload;
    private String headers;
    private String path;
    private boolean payloadOnly;
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
        return "Execute a HTTP request over Kubernetes client. "
                + "This is mainly for not yet existing commands and to reuse all the client auto-configuration.";
    }

    @Override
    public CompletionStage<?> execute() {
        final var requestBuilder = HttpRequest.newBuilder()
                .method(
                        this.method,
                        List.of("PATCH", "POST", "PUT").contains(method)
                                ? HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)
                                : HttpRequest.BodyPublishers.noBody());
        final var properties = new Properties();
        try (var reader = new StringReader(headers)) {
            properties.load(reader);
        } catch (final IOException e) {
            throw new IllegalArgumentException(e);
        }
        properties.stringPropertyNames().forEach(name -> requestBuilder.setHeader(name, properties.getProperty(name)));
        return kube.sendAsync(
                        requestBuilder
                                .uri(URI.create(
                                        path.startsWith("http:") || path.startsWith("https:")
                                                ? path
                                                : kube.base().toString() + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (isSuccess(response.statusCode())) {
                        if (payloadOnly) {
                            log.info(ofNullable(response.body()).orElse(""));
                            return response;
                        }
                        log.info(toHttpResponseDump(response));
                        return response;
                    }
                    final var error = "Invalid HTTP response for request " + method + " " + path + ":\n" + "\n"
                            + toHttpResponseDump(response);
                    if (failOnError) {
                        throw new IllegalStateException(error);
                    }
                    log.severe(error);
                    return response;
                });
    }

    private String toHttpResponseDump(final HttpResponse<String> response) {
        return "HTTP/1.1\n"
                + response.headers().map().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(it -> it.getKey() + ": " + String.join(", ", it.getValue()))
                        .collect(joining("\n", "", "\n"))
                + '\n' + ofNullable(response.body()).orElse("");
    }

    private boolean isSuccess(final int statusCode) {
        return statusCode > 199 && statusCode < 300;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.http.method",
                    defaultValue = "\"GET\"",
                    documentation = "HTTP verb to use for the request.")
            String method,

            @Property(
                    value = "bundlebee.http.payload",
                    defaultValue = "\"\"",
                    documentation =
                            "HTTP payload to use for the request - skipped for method different from `PATCH`, `POST` and `PUT`.")
            String payload,

            @Property(
                    value = "bundlebee.http.headers",
                    defaultValue = "\"Accept: application/json\\nContent-Type: application/json\"",
                    documentation = "Request HTTP headers (in properties format).")
            String headers,

            @Property(
                    value = "bundlebee.http.path",
                    defaultValue = "\"/api/\"",
                    documentation = "HTTP request path (optionally with query parameters). "
                            + "You can use the absolute kubernetes URL but if the value does not start with a protocol (`http` or `https`) "
                            + "then it will be prefixed by the Kubernetes API base.")
            String path,

            @Property(
                    value = "bundlebee.http.payloadOnly",
                    defaultValue = "true",
                    documentation = "If `true`, only the payload of the response will be logged if successful.")
            boolean payloadOnly,

            @Property(
                    value = "bundlebee.http.failOnError",
                    defaultValue = "true",
                    documentation =
                            "If `true`, an exception if thrown if the status is not between 200 and 299 (making the execution a failure).")
            boolean failOnError) {}
}
