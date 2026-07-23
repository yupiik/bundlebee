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

import io.yupiik.bundlebee.helm.fn.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class PrintfDebugTest {

    @Test
    void debugPrintfPipeline() throws Exception {
        final var functionsMap = new LinkedHashMap<String, HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new PrintfFunc(), new TrimSuffixFunc(), new TruncFunc(),
                new ReplaceFunc()
        )) {
            functionsMap.put(fn.name(), fn);
        }
        final var renderer = new HelmGoTemplateRenderer(functionsMap);
        final var lexer = new HelmGoTemplateLexer();

        // Test: printf "%s-%s" .Chart.Name .Chart.Version
        final var template = "{{ printf \"%s-%s\" .Chart.Name .Chart.Version }}";
        final var tokens = lexer.tokenize(template);
        final var nodes = new HelmGoTemplateParser(tokens).parse();

        final var context = new LinkedHashMap<String, Object>();
        context.put("Values", Map.of());
        context.put("Release", Map.of("Name", "test", "Namespace", "default"));
        context.put("Chart", Map.of("Name", "polaris", "Version", "1.6.0", "AppVersion", "1.6.0"));

        final var result = renderer.render(nodes, context);
        System.out.println("printf result: '" + result + "'");

        // Test: polaris.chart define
        final var helpersTokens = lexer.tokenize(
                "{{- define \"polaris.chart\" -}}{{ printf \"%s-%s\" .Chart.Name .Chart.Version | replace \"+\" \"_\" | trunc 63 | trimSuffix \"-\" }}{{- end }}");
        final var helpersNodes = new HelmGoTemplateParser(helpersTokens).parse();
        renderer.registerDefines(helpersNodes);

        final var includeTokens = lexer.tokenize("{{ include \"polaris.chart\" . }}");
        final var includeNodes = new HelmGoTemplateParser(includeTokens).parse();
        final var chartResult = renderer.render(includeNodes, context);
        System.out.println("polaris.chart result: '" + chartResult + "'");
    }
}
