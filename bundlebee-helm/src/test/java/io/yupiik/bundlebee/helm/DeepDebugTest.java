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

class DeepDebugTest {

    @Test
    void debug() throws Exception {
        final var functionsMap = new LinkedHashMap<String, HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new DefaultFunc(), new TrimSuffixFunc(), new TrimPrefixFunc(),
                new TruncFunc(), new PrintfFunc(), new ReplaceFunc(),
                new ContainsFunc(), new QuoteFunc(), new NindentFunc(),
                new IndentFunc(), new NeFunc(), new GeFunc(),
                new ToYamlFunc(), new ToStringFunc(), new CoalesceFunc(),
                new SetFunc(), new DictFunc(), new ListFunc()
        )) {
            functionsMap.put(fn.name(), fn);
        }
        final var renderer = new HelmGoTemplateRenderer(functionsMap);
        final var lexer = new HelmGoTemplateLexer();

        final var helpersTokens = lexer.tokenize(
                "{{- define \"polaris.labels\" -}}\n" +
                "helm.sh/chart: {{ include \"polaris.chart\" . }}\n" +
                "{{ include \"polaris.selectorLabels\" . }}\n" +
                "{{- if .Chart.AppVersion }}\n" +
                "app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}\n" +
                "{{- end }}\n" +
                "app.kubernetes.io/managed-by: {{ .Release.Service }}\n" +
                "{{- end }}\n\n" +
                "{{- define \"polaris.selectorLabels\" -}}\n" +
                "app.kubernetes.io/name: {{ include \"polaris.name\" . }}\n" +
                "app.kubernetes.io/instance: {{ .Release.Name }}\n" +
                "{{- end }}\n\n" +
                "{{- define \"polaris.name\" -}}\n" +
                "{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix \"-\" }}\n" +
                "{{- end }}\n\n" +
                "{{- define \"polaris.chart\" -}}\n" +
                "{{ printf \"%s-%s\" .Chart.Name .Chart.Version | replace \"+\" \"_\" | trunc 63 | trimSuffix \"-\" }}\n" +
                "{{- end }}");

        // Print tokens
        System.out.println("=== Tokens ===");
        for (int i = 0; i < helpersTokens.size(); i++) {
            final var t = helpersTokens.get(i);
            System.out.println(i + ": " + t.getType() + " '" + t.getValue()
                    .replace("\n", "\\n") + "' trimL=" + t.isTrimLeft() + " trimR=" + t.isTrimRight());
        }

        final var helpersNodes = new HelmGoTemplateParser(helpersTokens).parse();
        renderer.registerDefines(helpersNodes);

        // Now test each include individually
        final var context = new LinkedHashMap<String, Object>();
        context.put("Values", Map.of());
        context.put("Release", Map.of("Name", "polaris-test", "Namespace", "default", "Service", "Helm"));
        context.put("Chart", Map.of("Name", "polaris", "Version", "1.6.0", "AppVersion", "1.6.0"));

        for (final var tmplName : List.of("polaris.name", "polaris.chart", "polaris.selectorLabels", "polaris.labels")) {
            final var tokens = lexer.tokenize("{{ include \"" + tmplName + "\" . }}");
            final var nodes = new HelmGoTemplateParser(tokens).parse();
            final var result = renderer.render(nodes, context);
            System.out.println("\n=== " + tmplName + " result ===");
            System.out.println("'" + result.replace("\n", "\\n") + "'");
        }
    }
}
