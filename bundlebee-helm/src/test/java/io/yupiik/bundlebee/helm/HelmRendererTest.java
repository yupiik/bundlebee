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
package io.yupiik.bundlebee.helm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmRendererTest {

    private HelmRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        renderer = new HelmRenderer();

        // Inject function producer
        final var functionProducerField = HelmRenderer.class.getDeclaredField("functionProducer");
        functionProducerField.setAccessible(true);
        final var functionProducer = new HelmFunctionProducer();
        final var functionsField = HelmFunctionProducer.class.getDeclaredField("functions");
        functionsField.setAccessible(true);
        final var functions = new java.util.LinkedHashMap<String, HelmFunction>();
        for (final var fn : java.util.List.<HelmFunction>of(
                new io.yupiik.bundlebee.helm.fn.TrimFunc(),
                new io.yupiik.bundlebee.helm.fn.TrimPrefixFunc(),
                new io.yupiik.bundlebee.helm.fn.TrimSuffixFunc(),
                new io.yupiik.bundlebee.helm.fn.UpperFunc(),
                new io.yupiik.bundlebee.helm.fn.LowerFunc(),
                new io.yupiik.bundlebee.helm.fn.TitleFunc(),
                new io.yupiik.bundlebee.helm.fn.RepeatFunc(),
                new io.yupiik.bundlebee.helm.fn.ReplaceFunc(),
                new io.yupiik.bundlebee.helm.fn.ContainsFunc(),
                new io.yupiik.bundlebee.helm.fn.HasPrefixFunc(),
                new io.yupiik.bundlebee.helm.fn.HasSuffixFunc(),
                new io.yupiik.bundlebee.helm.fn.QuoteFunc(),
                new io.yupiik.bundlebee.helm.fn.NospaceFunc(),
                new io.yupiik.bundlebee.helm.fn.IndentFunc(),
                new io.yupiik.bundlebee.helm.fn.NindentFunc(),
                new io.yupiik.bundlebee.helm.fn.PluralFunc(),
                new io.yupiik.bundlebee.helm.fn.CatFunc(),
                new io.yupiik.bundlebee.helm.fn.WrapFunc(),
                new io.yupiik.bundlebee.helm.fn.DefaultFunc(),
                new io.yupiik.bundlebee.helm.fn.DictFunc(),
                new io.yupiik.bundlebee.helm.fn.ListFunc(),
                new io.yupiik.bundlebee.helm.fn.B64EncFunc(),
                new io.yupiik.bundlebee.helm.fn.TruncFunc())) {
            functions.put(fn.name(), fn);
        }
        functionsField.set(functionProducer, functions);
        functionProducerField.set(renderer, functionProducer);

        // Inject chart loader
        final var chartLoaderField = HelmRenderer.class.getDeclaredField("chartLoader");
        chartLoaderField.setAccessible(true);
        chartLoaderField.set(renderer, new HelmChartLoader());
    }

    @Test
    void renderTestChart() throws Exception {
        final var chartPath = "src/test/resources/helm/test-chart";
        final var results = renderer.render(chartPath, Map.of(), "my-release", "default").toCompletableFuture().get();

        final var allContent = String.join("\n---\n", results);
        assertTrue(results.size() >= 2, "Should render at least deployment and service");
        assertTrue(allContent.contains("my-release-nginx"), "Should contain release name in resource names");
        assertTrue(allContent.contains("nginx:1.19"), "Should contain image tag");
        assertTrue(allContent.contains("ClusterIP"), "Should contain service type");
        assertTrue(allContent.contains("helm.sh/chart: test-chart-0.1.0"), "Should contain chart label");
        assertTrue(allContent.contains("app.kubernetes.io/name: test-chart"), "Should contain app name label");
        assertTrue(allContent.contains("app.kubernetes.io/instance: my-release"), "Should contain instance label");
        assertTrue(allContent.contains("replicas: 1"), "Should contain replica count");
    }

    @Test
    void renderWithPlaceholderOverrides() throws Exception {
        final var chartPath = "src/test/resources/helm/test-chart";
        final var results = renderer.render(chartPath, Map.of("service.type", "LoadBalancer"), "test", "kube-system")
                .toCompletableFuture().get();

        final var allContent = String.join("\n---\n", results);
        assertTrue(allContent.contains("LoadBalancer"), "Should use overridden service type");
    }
}
