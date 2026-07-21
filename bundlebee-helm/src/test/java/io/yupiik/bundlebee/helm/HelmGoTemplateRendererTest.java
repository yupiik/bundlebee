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

import io.yupiik.bundlebee.helm.fn.B64EncFunc;
import io.yupiik.bundlebee.helm.fn.DefaultFunc;
import io.yupiik.bundlebee.helm.fn.DictFunc;
import io.yupiik.bundlebee.helm.fn.ContainsFunc;
import io.yupiik.bundlebee.helm.fn.HasPrefixFunc;
import io.yupiik.bundlebee.helm.fn.HasSuffixFunc;
import io.yupiik.bundlebee.helm.fn.LowerFunc;
import io.yupiik.bundlebee.helm.fn.ListFunc;
import io.yupiik.bundlebee.helm.fn.NindentFunc;
import io.yupiik.bundlebee.helm.fn.IndentFunc;
import io.yupiik.bundlebee.helm.fn.TrimFunc;
import io.yupiik.bundlebee.helm.fn.TrimPrefixFunc;
import io.yupiik.bundlebee.helm.fn.TrimSuffixFunc;
import io.yupiik.bundlebee.helm.fn.UpperFunc;
import io.yupiik.bundlebee.helm.fn.QuoteFunc;
import io.yupiik.bundlebee.helm.fn.ReplaceFunc;
import io.yupiik.bundlebee.helm.fn.RepeatFunc;
import io.yupiik.bundlebee.helm.fn.NospaceFunc;
import io.yupiik.bundlebee.helm.fn.PluralFunc;
import io.yupiik.bundlebee.helm.fn.CatFunc;
import io.yupiik.bundlebee.helm.fn.TitleFunc;
import io.yupiik.bundlebee.helm.fn.WrapFunc;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HelmGoTemplateRendererTest {

    private Map<String, HelmFunction> defaultFunctions() {
        final var map = new LinkedHashMap<String, HelmFunction>();
        for (final var fn : List.<HelmFunction>of(
                new TrimFunc(), new TrimPrefixFunc(), new TrimSuffixFunc(),
                new UpperFunc(), new LowerFunc(), new TitleFunc(),
                new RepeatFunc(), new ReplaceFunc(), new ContainsFunc(),
                new HasPrefixFunc(), new HasSuffixFunc(),
                new QuoteFunc(), new NospaceFunc(), new IndentFunc(),
                new NindentFunc(), new PluralFunc(), new CatFunc(),
                new WrapFunc(), new DefaultFunc(), new DictFunc(),
                new ListFunc(), new B64EncFunc())) {
            map.put(fn.name(), fn);
        }
        return map;
    }

    private String render(final String template, final Map<String, Object> context) {
        final var tokens = new HelmGoTemplateLexer().tokenize(template);
        final var nodes = new HelmGoTemplateParser(tokens).parse();
        final var renderer = new HelmGoTemplateRenderer(defaultFunctions());
        return renderer.render(nodes, context);
    }

    @Test
    void simpleVariable() {
        final var result = render("Hello {{ .Values.name }}!", Map.of(
                "Values", Map.of("name", "world")));
        assertEquals("Hello world!", result);
    }

    @Test
    void nestedVariable() {
        final var result = render("{{ .Values.image.repository }}:{{ .Values.image.tag }}", Map.of(
                "Values", Map.of("image", Map.of("repository", "nginx", "tag", "1.19"))));
        assertEquals("nginx:1.19", result);
    }

    @Test
    void ifElse() {
        final var template = "{{ if .Values.enabled }}yes{{ else }}no{{ end }}";
        assertEquals("yes", render(template, Map.of("Values", Map.of("enabled", true))));
        assertEquals("no", render(template, Map.of("Values", Map.of("enabled", false))));
    }

    @Test
    void range() {
        final var template = "{{ range .Values.items }}- {{ . }}\n{{ end }}";
        final var result = render(template, Map.of("Values", Map.of("items", List.of("a", "b", "c"))));
        assertEquals("- a\n- b\n- c\n", result);
    }

    @Test
    void with() {
        final var template = "{{ with .Values.config }}name={{ .name }}{{ end }}";
        final var result = render(template, Map.of("Values", Map.of("config", Map.of("name", "test"))));
        assertEquals("name=test", result);
    }

    @Test
    void defineAndTemplate() {
        final var functions = defaultFunctions();
        final var renderer = new HelmGoTemplateRenderer(functions);
        final var defineTokens = new HelmGoTemplateLexer().tokenize("{{- define \"myhelper\" -}}helper-{{ . }}{{- end }}");
        final var defineNodes = new HelmGoTemplateParser(defineTokens).parse();
        renderer.registerDefines(defineNodes);
        final var templateTokens = new HelmGoTemplateLexer().tokenize("prefix-{{ template \"myhelper\" \"data\" }}-suffix");
        final var templateNodes = new HelmGoTemplateParser(templateTokens).parse();
        final var result = renderer.render(templateNodes, Map.of());
        assertEquals("prefix-helper-data-suffix", result);
    }

    @Test
    void trimWhitespace() {
        final var template = "start\n  {{- .Values.x -}}\nend";
        final var result = render(template, Map.of("Values", Map.of("x", "hello")));
        assertEquals("starthelloend", result);
    }

    @Test
    void pipeline() {
        final var template = "{{ .Values.name | upper }}";
        final var result = render(template, Map.of("Values", Map.of("name", "hello")));
        assertEquals("HELLO", result);
    }

    @Test
    void defaultFunction() {
        final var template = "{{ .Values.name | default \"fallback\" }}";
        assertEquals("fallback", render(template, Map.of("Values", Map.of())));
        assertEquals("actual", render(template, Map.of("Values", Map.of("name", "actual"))));
    }

    @Test
    void quoteFunction() {
        final var template = "{{ .Values.name | quote }}";
        final var result = render(template, Map.of("Values", Map.of("name", "hello")));
        assertEquals("\"hello\"", result);
    }

    @Test
    void b64encFunction() {
        final var template = "{{ .Values.data | b64enc }}";
        final var result = render(template, Map.of("Values", Map.of("data", "hello")));
        assertEquals("aGVsbG8=", result);
    }

    @Test
    void indentFunction() {
        final var template = "{{ .Values.text | indent 4 }}";
        final var result = render(template, Map.of("Values", Map.of("text", "line1\nline2")));
        assertEquals("    line1\n    line2", result);
    }

    @Test
    void containsFunction() {
        final var template = "{{ contains .Values.needle .Values.haystack }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("needle", "ell", "haystack", "hello"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("needle", "xyz", "haystack", "hello"))));
    }

    @Test
    void hasPrefixFunction() {
        final var template = "{{ hasPrefix .Values.prefix .Values.str }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("prefix", "hel", "str", "hello"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("prefix", "xyz", "str", "hello"))));
    }

    @Test
    void listFunction() {
        final var template = "{{ list .Values.a .Values.b }}";
        final var result = render(template, Map.of("Values", Map.of("a", "x", "b", "y")));
        assertEquals("[x, y]", result);
    }

    @Test
    void dictFunction() {
        final var template = "{{ dict \"key1\" .Values.a \"key2\" .Values.b }}";
        final var result = render(template, Map.of("Values", Map.of("a", "val1", "b", "val2")));
        assertEquals("{key1=val1, key2=val2}", result);
    }

    @Test
    void releaseContext() {
        final var template = "{{ .Release.Name }}-{{ .Release.Namespace }}";
        final var result = render(template, Map.of(
                "Release", Map.of("Name", "my-release", "Namespace", "default")));
        assertEquals("my-release-default", result);
    }

    @Test
    void chartContext() {
        final var template = "{{ .Chart.Name }}-{{ .Chart.Version }}";
        final var result = render(template, Map.of(
                "Chart", Map.of("Name", "my-chart", "Version", "1.0.0")));
        assertEquals("my-chart-1.0.0", result);
    }

    @Test
    void commentsAreIgnored() {
        final var template = "hello {{/* this is a comment */}} world";
        final var result = render(template, Map.of());
        assertEquals("hello  world", result);
    }

    @Test
    void multiLineCommentsAreIgnored() {
        final var template = "before\n{{/* line1\n   line2 */}}\nafter";
        final var result = render(template, Map.of());
        assertEquals("before\n\nafter", result);
    }
}
