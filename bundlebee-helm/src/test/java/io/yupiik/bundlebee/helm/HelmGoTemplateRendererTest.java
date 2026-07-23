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
import io.yupiik.bundlebee.helm.fn.AndFunc;
import io.yupiik.bundlebee.helm.fn.OrFunc;
import io.yupiik.bundlebee.helm.fn.NotFunc;
import io.yupiik.bundlebee.helm.fn.EqFunc;
import io.yupiik.bundlebee.helm.fn.HasFunc;
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

import java.util.HashMap;
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
                new ListFunc(), new B64EncFunc(),
                new AndFunc(), new OrFunc(), new NotFunc(),
                new EqFunc(), new HasFunc())) {
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

    @Test
    void groupingSimpleVariable() {
        final var template = "{{ ( .Values.x ) }}";
        assertEquals("hello", render(template, Map.of("Values", Map.of("x", "hello"))));
    }

    @Test
    void groupingInIfCondition() {
        final var template = "{{ if ( .Values.enabled ) }}yes{{ end }}";
        assertEquals("yes", render(template, Map.of("Values", Map.of("enabled", true))));
        assertEquals("", render(template, Map.of("Values", Map.of("enabled", false))));
    }

    @Test
    void nestedGrouping() {
        final var template = "{{ ( ( .Values.x ) ) }}";
        assertEquals("val", render(template, Map.of("Values", Map.of("x", "val"))));
    }

    @Test
    void groupingWithPipeInside() {
        final var template = "{{ ( .Values.x | upper ) }}";
        assertEquals("HELLO", render(template, Map.of("Values", Map.of("x", "hello"))));
    }

    @Test
    void groupingPreservesValue() {
        final var template = "prefix-{{ ( .Values.x ) }}-suffix";
        assertEquals("prefix-val-suffix", render(template, Map.of("Values", Map.of("x", "val"))));
    }

    @Test
    void andBothTruthy() {
        final var template = "{{ and .Values.a .Values.b }}";
        assertEquals("world", render(template, Map.of("Values", Map.of("a", "hello", "b", "world"))));
    }

    @Test
    void andFirstFalsy() {
        final var template = "{{ and .Values.a .Values.b }}";
        assertEquals("", render(template, Map.of("Values", Map.of("a", "", "b", "world"))));
    }

    @Test
    void andBothFalsy() {
        final var template = "{{ and .Values.a .Values.b }}";
        assertEquals("", render(template, Map.of("Values", Map.of("a", "", "b", ""))));
    }

    @Test
    void andWithNull() {
        final var template = "{{ and .Values.a .Values.b }}";
        final var ctx = new HashMap<String, Object>();
        ctx.put("Values", new HashMap<String, Object>() {{
            put("a", null);
            put("b", "world");
        }});
        assertEquals("<nil>", render(template, ctx));
    }

    @Test
    void andWithGrouping() {
        final var template = "{{ and ( .Values.a ) ( .Values.b ) }}";
        assertEquals("world", render(template, Map.of("Values", Map.of("a", "hello", "b", "world"))));
    }

    @Test
    void andThreeArgs() {
        final var template = "{{ and .Values.a .Values.b .Values.c }}";
        assertEquals("final", render(template, Map.of("Values", Map.of("a", "x", "b", "y", "c", "final"))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", "", "b", "y", "c", "final"))));
    }

    @Test
    void andWithBooleans() {
        final var template = "{{ and .Values.a .Values.b }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", true, "b", true))));
        assertEquals("false", render(template, Map.of("Values", Map.of("a", true, "b", false))));
    }

    @Test
    void andWithNumbers() {
        final var template = "{{ and .Values.a .Values.b }}";
        assertEquals("2", render(template, Map.of("Values", Map.of("a", 1, "b", 2))));
        assertEquals("0", render(template, Map.of("Values", Map.of("a", 0, "b", 2))));
    }

    @Test
    void orFirstTruthy() {
        final var template = "{{ or .Values.a .Values.b }}";
        assertEquals("hello", render(template, Map.of("Values", Map.of("a", "hello", "b", "world"))));
    }

    @Test
    void orFirstFalsy() {
        final var template = "{{ or .Values.a .Values.b }}";
        assertEquals("world", render(template, Map.of("Values", Map.of("a", "", "b", "world"))));
    }

    @Test
    void orBothFalsy() {
        final var template = "{{ or .Values.a .Values.b }}";
        assertEquals("", render(template, Map.of("Values", Map.of("a", "", "b", ""))));
    }

    @Test
    void orWithGrouping() {
        final var template = "{{ or ( .Values.a ) ( .Values.b ) }}";
        assertEquals("hello", render(template, Map.of("Values", Map.of("a", "hello", "b", "world"))));
    }

    @Test
    void orThreeArgs() {
        final var template = "{{ or .Values.a .Values.b .Values.c }}";
        assertEquals("first", render(template, Map.of("Values", Map.of("a", "first", "b", "", "c", ""))));
        assertEquals("second", render(template, Map.of("Values", Map.of("a", "", "b", "second", "c", ""))));
        assertEquals("third", render(template, Map.of("Values", Map.of("a", "", "b", "", "c", "third"))));
    }

    @Test
    void orWithBooleans() {
        final var template = "{{ or .Values.a .Values.b }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", true, "b", false))));
        assertEquals("true", render(template, Map.of("Values", Map.of("a", false, "b", true))));
        assertEquals("false", render(template, Map.of("Values", Map.of("a", false, "b", false))));
    }

    @Test
    void orWithNumbers() {
        final var template = "{{ or .Values.a .Values.b }}";
        assertEquals("1", render(template, Map.of("Values", Map.of("a", 1, "b", 2))));
        assertEquals("2", render(template, Map.of("Values", Map.of("a", 0, "b", 2))));
    }

    @Test
    void notTruthy() {
        final var template = "{{ not .Values.a }}";
        assertEquals("false", render(template, Map.of("Values", Map.of("a", true))));
    }

    @Test
    void notFalsy() {
        final var template = "{{ not .Values.a }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", false))));
    }

    @Test
    void notNull() {
        final var template = "{{ not .Values.a }}";
        assertEquals("true", render(template, Map.of("Values", Map.of())));
    }

    @Test
    void notEmptyString() {
        final var template = "{{ not .Values.a }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", ""))));
    }

    @Test
    void notNonEmptyString() {
        final var template = "{{ not .Values.a }}";
        assertEquals("false", render(template, Map.of("Values", Map.of("a", "hello"))));
    }

    @Test
    void notWithGrouping() {
        final var template = "{{ not ( .Values.a ) }}";
        assertEquals("false", render(template, Map.of("Values", Map.of("a", true))));
        assertEquals("true", render(template, Map.of("Values", Map.of("a", false))));
    }

    @Test
    void eqStringsEqual() {
        final var template = "{{ eq .Values.a .Values.b }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", "hello", "b", "hello"))));
    }

    @Test
    void eqStringsNotEqual() {
        final var template = "{{ eq .Values.a .Values.b }}";
        assertEquals("false", render(template, Map.of("Values", Map.of("a", "hello", "b", "world"))));
    }

    @Test
    void eqWithNull() {
        final var template = "{{ eq .Values.a .Values.b }}";
        final var ctx = new HashMap<String, Object>();
        ctx.put("Values", new HashMap<String, Object>() {{
            put("a", null);
            put("b", "hello");
        }});
        assertEquals("false", render(template, ctx));
    }

    @Test
    void eqBothNull() {
        final var template = "{{ eq .Values.a .Values.b }}";
        final var ctx = new HashMap<String, Object>();
        ctx.put("Values", new HashMap<String, Object>() {{
            put("a", null);
            put("b", null);
        }});
        assertEquals("true", render(template, ctx));
    }

    @Test
    void eqNumbers() {
        final var template = "{{ eq .Values.a .Values.b }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", 42, "b", 42))));
        assertEquals("false", render(template, Map.of("Values", Map.of("a", 42, "b", 43))));
    }

    @Test
    void eqWithLiterals() {
        final var template = "{{ eq .Values.type \"LoadBalancer\" }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("type", "LoadBalancer"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("type", "ClusterIP"))));
    }

    @Test
    void hasInString() {
        final var template = "{{ has .Values.needle .Values.haystack }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("needle", "ell", "haystack", "hello"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("needle", "xyz", "haystack", "hello"))));
    }

    @Test
    void hasInList() {
        final var template = "{{ has .Values.needle .Values.haystack }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("needle", "b", "haystack", List.of("a", "b", "c")))));
        assertEquals("false", render(template, Map.of("Values", Map.of("needle", "d", "haystack", List.of("a", "b", "c")))));
    }

    @Test
    void andWithOrAndEq() {
        final var template = "{{ and .Values.externalTrafficPolicy (or (eq .Values.serviceType \"LoadBalancer\") (eq .Values.serviceType \"NodePort\")) }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("externalTrafficPolicy", "Local", "serviceType", "LoadBalancer"))));
        assertEquals("true", render(template, Map.of("Values", Map.of("externalTrafficPolicy", "Local", "serviceType", "NodePort"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("externalTrafficPolicy", "Local", "serviceType", "ClusterIP"))));
        assertEquals("", render(template, Map.of("Values", Map.of("externalTrafficPolicy", "", "serviceType", "LoadBalancer"))));
    }

    @Test
    void notWithHasAndGrouping() {
        final var template = "{{ not (has (quote .Values.x) (list \"\" (quote \"\"))) }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("x", "hello"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("x", ""))));
    }

    @Test
    void andWithGroupingAndFunctionCalls() {
        final var template = "{{ and ( .Values.a ) (or (eq .Values.b \"x\") (eq .Values.b \"y\")) }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", "ok", "b", "x"))));
        assertEquals("true", render(template, Map.of("Values", Map.of("a", "ok", "b", "y"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("a", "ok", "b", "z"))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", "", "b", "x"))));
    }

    @Test
    void andWithParensAndCapabilities() {
        final var template = "{{ and ( .Values.hasMonitoring ) (eq .Values.type \"ServiceMonitor\") }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("hasMonitoring", true, "type", "ServiceMonitor"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("hasMonitoring", false, "type", "ServiceMonitor"))));
    }

    @Test
    void deeplyNestedIfAndOr() {
        final var template = "{{ if and .Values.a (or .Values.b .Values.c) }}yes{{ end }}";
        assertEquals("yes", render(template, Map.of("Values", Map.of("a", true, "b", true, "c", false))));
        assertEquals("yes", render(template, Map.of("Values", Map.of("a", true, "b", false, "c", true))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", false, "b", true, "c", true))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", true, "b", false, "c", false))));
    }

    @Test
    void ifElseWithAnd() {
        final var template = "{{ if and .Values.enabled .Values.feature }}on{{ else }}off{{ end }}";
        assertEquals("on", render(template, Map.of("Values", Map.of("enabled", true, "feature", true))));
        assertEquals("off", render(template, Map.of("Values", Map.of("enabled", true, "feature", false))));
        assertEquals("off", render(template, Map.of("Values", Map.of("enabled", false, "feature", true))));
    }

    @Test
    void ifElseIfWithOrAndGrouping() {
        final var template = "{{ if (or (eq .Values.type \"A\") (eq .Values.type \"B\")) }}AB{{ else }}other{{ end }}";
        assertEquals("AB", render(template, Map.of("Values", Map.of("type", "A"))));
        assertEquals("AB", render(template, Map.of("Values", Map.of("type", "B"))));
        assertEquals("other", render(template, Map.of("Values", Map.of("type", "C"))));
    }

    @Test
    void notWithAndInIf() {
        final var template = "{{ if not (and .Values.a .Values.b) }}no{{ end }}";
        assertEquals("no", render(template, Map.of("Values", Map.of("a", true, "b", false))));
        assertEquals("no", render(template, Map.of("Values", Map.of("a", false, "b", true))));
        assertEquals("no", render(template, Map.of("Values", Map.of("a", false, "b", false))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", true, "b", true))));
    }

    @Test
    void notWithOrInIf() {
        final var template = "{{ if not (or .Values.a .Values.b) }}neither{{ end }}";
        assertEquals("neither", render(template, Map.of("Values", Map.of("a", false, "b", false))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", true, "b", false))));
        assertEquals("", render(template, Map.of("Values", Map.of("a", false, "b", true))));
    }

    @Test
    void tripleNestedGrouping() {
        final var template = "{{ and (or (eq .Values.a \"x\") (eq .Values.a \"y\")) (eq .Values.b \"z\") }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("a", "x", "b", "z"))));
        assertEquals("true", render(template, Map.of("Values", Map.of("a", "y", "b", "z"))));
        assertEquals("false", render(template, Map.of("Values", Map.of("a", "x", "b", "w"))));
    }

    @Test
    void andWithGroupingAndPipe() {
        final var template = "{{ and ( .Values.x | upper ) ( .Values.y ) }}";
        assertEquals("world", render(template, Map.of("Values", Map.of("x", "hello", "y", "world"))));
    }

    @Test
    void orWithGroupingAndPipe() {
        final var template = "{{ or ( .Values.x | default \"fallback\" ) .Values.y }}";
        assertEquals("fallback", render(template, Map.of("Values", Map.of("x", "", "y", ""))));
        assertEquals("hello", render(template, Map.of("Values", Map.of("x", "hello", "y", ""))));
    }

    @Test
    void eqWithGroupingAndVariable() {
        final var template = "{{ if (eq .Values.env \"production\") }}prod{{ end }}";
        assertEquals("prod", render(template, Map.of("Values", Map.of("env", "production"))));
        assertEquals("", render(template, Map.of("Values", Map.of("env", "staging"))));
    }

    @Test
    void andOrNotCombined() {
        final var template = "{{ and (not .Values.disabled) (or .Values.a .Values.b) }}";
        assertEquals("true", render(template, Map.of("Values", Map.of("disabled", false, "a", true, "b", false))));
        assertEquals("true", render(template, Map.of("Values", Map.of("disabled", false, "a", false, "b", true))));
        assertEquals("false", render(template, Map.of("Values", Map.of("disabled", true, "a", true, "b", true))));
        assertEquals("false", render(template, Map.of("Values", Map.of("disabled", false, "a", false, "b", false))));
    }
}
