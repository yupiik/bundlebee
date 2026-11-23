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
package io.yupiik.bundlebee.documentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class K8sJSONSchemasGeneratorTest {
    @TempDir
    private Path temp;

    @Test
    void generatesGenericTreesAndResolvesReferencesDeterministically() throws Exception {
        final var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/tags", exchange -> {
            final var bytes = "[{\"name\":\"v1.30.0\",\"commit\":{\"sha\":\"ignored\"}},{\"name\":\"v1.31.0-alpha.1\"}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.createContext("/spec", exchange -> {
            final var bytes = """
                    {"definitions":{
                      "example.Child":{"type":"object","properties":{"enabled":{"type":"boolean","default":true}}},
                      "example.Widget":{"type":"object","x-kubernetes-group-version-kind":[{"kind":"Widget","version":"v1"}],
                        "properties":{"child":{"$ref":"#/definitions/example.Child"},
                                      "count":{"type":"number","default":2.5},
                                      "nullable":{"default":null},
                                      "nested":{"examples":[[{"$ref":"#/definitions/example.Child"}]]}}}
                    }}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (final var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        try {
            final var base = "http://localhost:" + server.getAddress().getPort();
            final var config = Map.of(
                    "minisite.actions.k8s.jsonschema",
                    "true",
                    "tagsUrl",
                    base + "/tags",
                    "specUrlTemplate",
                    base + "/spec",
                    "maxThreads",
                    "1",
                    "minVersion",
                    "1.30.0",
                    "skipReactView",
                    "true",
                    "skipPatchVersion",
                    "true",
                    "force",
                    "true");
            new K8sJSONSchemasGenerator(temp, config).run();
            final var output = temp.resolve("assets/generated/kubernetes/jsonschema/1.30/v1/Widget.jsonschema.json");
            assertTrue(Files.exists(output), "A resource schema must be emitted");
            final var first = Files.readString(output);
            assertFalse(first.isBlank());
            assertFalse(first.contains("$ref"));
            assertFalse(first.contains("x-kubernetes"));
            assertTrue(first.contains("2.5"));
            assertTrue(first.contains("true"));
            try (final var container = ConfiguringContainer.of().start()) {
                final var mapper = container.lookup(JsonMapper.class).instance();
                final var tree = JsonSupport.object(mapper.fromString(Object.class, first));
                final var properties = JsonSupport.object(tree, "properties");
                assertTrue(JsonSupport.object(properties, "nullable").containsKey("default"));
            }
            new K8sJSONSchemasGenerator(temp, config).run();
            assertEquals(first, Files.readString(output));
        } finally {
            server.stop(0);
        }
    }
}
