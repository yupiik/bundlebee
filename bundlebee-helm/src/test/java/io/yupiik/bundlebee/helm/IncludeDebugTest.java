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

import static org.junit.jupiter.api.Assertions.*;

class IncludeDebugTest {

    @Test
    void debugSimpleInclude() throws Exception {
        final var functionsMap = new LinkedHashMap<String, HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new DefaultFunc(), new TrimSuffixFunc(), new TruncFunc()
        )) {
            functionsMap.put(fn.name(), fn);
        }

        final var renderer = new HelmGoTemplateRenderer(functionsMap);

        // Register a simple define: "test.name" -> outputs .Chart.Name
        final var lexer = new HelmGoTemplateLexer();
        final var helpersTokens = lexer.tokenize(
                "{{- define \"test.name\" -}}{{ .Chart.Name }}{{- end }}");
        final var helpersNodes = new HelmGoTemplateParser(helpersTokens).parse();
        renderer.registerDefines(helpersNodes);

        // Build context
        final var context = new LinkedHashMap<String, Object>();
        context.put("Values", Map.of());
        context.put("Release", Map.of("Name", "my-release", "Namespace", "default"));
        context.put("Chart", Map.of("Name", "mychart", "Version", "1.0"));

        // Test 1: Direct include
        final var tokens = lexer.tokenize("{{ include \"test.name\" . }}");
        final var nodes = new HelmGoTemplateParser(tokens).parse();
        final var result = renderer.render(nodes, context);
        System.out.println("Direct include: '" + result + "'");
        assertEquals("mychart", result.trim());

        // Test 2: Render the define body directly
        final var directTokens = lexer.tokenize("{{ .Chart.Name }}");
        final var directNodes = new HelmGoTemplateParser(directTokens).parse();
        final var directResult = renderer.render(directNodes, context);
        System.out.println("Direct .Chart.Name: '" + directResult + "'");
        assertEquals("mychart", directResult.trim());
    }

    @Test
    void debugNestedInclude() throws Exception {
        final var functionsMap = new LinkedHashMap<String, HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new DefaultFunc(), new TrimSuffixFunc(), new TruncFunc()
        )) {
            functionsMap.put(fn.name(), fn);
        }

        final var renderer = new HelmGoTemplateRenderer(functionsMap);
        final var lexer = new HelmGoTemplateLexer();

        // Register two defines
        final var helpersTokens = lexer.tokenize(
                "{{- define \"inner\" -}}{{ .Chart.Name }}{{- end }}" +
                "{{- define \"outer\" -}}{{ include \"inner\" . }}{{- end }}");
        final var helpersNodes = new HelmGoTemplateParser(helpersTokens).parse();
        renderer.registerDefines(helpersNodes);

        final var context = new LinkedHashMap<String, Object>();
        context.put("Values", Map.of());
        context.put("Release", Map.of("Name", "my-release", "Namespace", "default"));
        context.put("Chart", Map.of("Name", "mychart", "Version", "1.0"));

        // Test nested include
        final var tokens = lexer.tokenize("{{ include \"outer\" . }}");
        final var nodes = new HelmGoTemplateParser(tokens).parse();
        final var result = renderer.render(nodes, context);
        System.out.println("Nested include: '" + result + "'");
        assertEquals("mychart", result.trim());
    }

    @Test
    void debugPolarisFullname() throws Exception {
        final var functionsMap = new LinkedHashMap<String, HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new DefaultFunc(), new TrimSuffixFunc(), new TruncFunc(),
                new ContainsFunc()
        )) {
            functionsMap.put(fn.name(), fn);
        }

        final var renderer = new HelmGoTemplateRenderer(functionsMap);
        final var lexer = new HelmGoTemplateLexer();

        // Simplified polaris.fullname
        final var helpersTokens = lexer.tokenize(
                "{{- define \"polaris.fullname\" -}}\n" +
                "{{- if .Values.fullnameOverride }}\n" +
                "{{- .Values.fullnameOverride | trunc 63 | trimSuffix \"-\" }}\n" +
                "{{- else }}\n" +
                "{{- $name := default .Chart.Name .Values.nameOverride }}\n" +
                "{{- if contains $name .Release.Name }}\n" +
                "{{- .Release.Name | trunc 63 | trimSuffix \"-\" }}\n" +
                "{{- else }}\n" +
                "{{- .Release.Name }}-{{ $name }}\n" +
                "{{- end }}\n" +
                "{{- end }}\n" +
                "{{- end }}");
        final var helpersNodes = new HelmGoTemplateParser(helpersTokens).parse();
        renderer.registerDefines(helpersNodes);

        final var values = new LinkedHashMap<String, Object>();
        values.put("fullnameOverride", "");
        values.put("nameOverride", "");

        final var context = new LinkedHashMap<String, Object>();
        context.put("Values", values);
        context.put("Release", Map.of("Name", "polaris-test", "Namespace", "default"));
        context.put("Chart", Map.of("Name", "polaris", "Version", "1.6.0"));

        final var tokens = lexer.tokenize("{{ include \"polaris.fullname\" . }}");
        final var nodes = new HelmGoTemplateParser(tokens).parse();
        final var result = renderer.render(nodes, context);
        System.out.println("polaris.fullname: '" + result + "'");
        assertEquals("polaris-test", result.trim());
    }
}
