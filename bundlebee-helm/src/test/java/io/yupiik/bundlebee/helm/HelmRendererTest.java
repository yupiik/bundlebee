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

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.helm.fn.B64EncFunc;
import io.yupiik.bundlebee.helm.fn.CatFunc;
import io.yupiik.bundlebee.helm.fn.ContainsFunc;
import io.yupiik.bundlebee.helm.fn.DefaultFunc;
import io.yupiik.bundlebee.helm.fn.DictFunc;
import io.yupiik.bundlebee.helm.fn.HasPrefixFunc;
import io.yupiik.bundlebee.helm.fn.HasSuffixFunc;
import io.yupiik.bundlebee.helm.fn.IndentFunc;
import io.yupiik.bundlebee.helm.fn.ListFunc;
import io.yupiik.bundlebee.helm.fn.LowerFunc;
import io.yupiik.bundlebee.helm.fn.NindentFunc;
import io.yupiik.bundlebee.helm.fn.NospaceFunc;
import io.yupiik.bundlebee.helm.fn.PluralFunc;
import io.yupiik.bundlebee.helm.fn.QuoteFunc;
import io.yupiik.bundlebee.helm.fn.RepeatFunc;
import io.yupiik.bundlebee.helm.fn.ReplaceFunc;
import io.yupiik.bundlebee.helm.fn.TitleFunc;
import io.yupiik.bundlebee.helm.fn.TrimFunc;
import io.yupiik.bundlebee.helm.fn.TrimPrefixFunc;
import io.yupiik.bundlebee.helm.fn.TrimSuffixFunc;
import io.yupiik.bundlebee.helm.fn.TruncFunc;
import io.yupiik.bundlebee.helm.fn.UpperFunc;
import io.yupiik.bundlebee.helm.fn.WrapFunc;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HelmRendererTest {

    private HelmRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        final var functions = new ArrayList<HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new TrimFunc(),
                new TrimPrefixFunc(),
                new TrimSuffixFunc(),
                new UpperFunc(),
                new LowerFunc(),
                new TitleFunc(),
                new RepeatFunc(),
                new ReplaceFunc(),
                new ContainsFunc(),
                new HasPrefixFunc(),
                new HasSuffixFunc(),
                new QuoteFunc(),
                new NospaceFunc(),
                new IndentFunc(),
                new NindentFunc(),
                new PluralFunc(),
                new CatFunc(),
                new WrapFunc(),
                new DefaultFunc(),
                new DictFunc(),
                new ListFunc(),
                new B64EncFunc(),
                new TruncFunc())) {
            functions.add(fn);
        }
        renderer = new HelmRenderer(
                new HelmFunctionRegistry(functions),
                new HelmChartLoader(null, Configuration.of(Map.of())),
                () -> "default");
    }

    @Test
    void renderTestChart() throws Exception {
        final var chartPath = "src/test/resources/helm/test-chart";
        final var results = renderer.render(chartPath, Map.of(), "my-release", "default")
                .toCompletableFuture()
                .get();

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
                .toCompletableFuture()
                .get();

        final var allContent = String.join("\n---\n", results);
        assertTrue(allContent.contains("LoadBalancer"), "Should use overridden service type");
    }
}
