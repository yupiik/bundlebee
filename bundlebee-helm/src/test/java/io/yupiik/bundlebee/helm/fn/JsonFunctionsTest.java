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
package io.yupiik.bundlebee.helm.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.helm.HelmFunction;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class JsonFunctionsTest {
    private final RuntimeContainer container = ConfiguringContainer.of().start();
    private final JsonMapper mapper = container.lookup(JsonMapper.class).instance();

    @AfterEach
    void closeMapper() {
        container.close();
    }

    @Test
    void preservesMissingAndNullArgumentDefaults() {
        final var defaults = Map.<HelmFunction, Object>of(
                new ToJsonFunc(mapper), "",
                new MustToJsonFunc(mapper), "null",
                new ToPrettyJsonFunc(mapper), "{}",
                new MustToPrettyJsonFunc(mapper), "null",
                new ToRawJsonFunc(mapper), "null",
                new MustToRawJsonFunc(mapper), "null",
                new FromJsonFunc(mapper), Map.of(),
                new MustFromJsonFunc(mapper), Map.of());
        defaults.forEach((function, expected) -> {
            assertEquals(expected, function.execute(), function.name());
            assertEquals(expected, function.execute((Object[]) null), function.name());
            assertEquals(expected, function.execute((Object) null), function.name());
        });
    }

    @Test
    void decodesNestedValuesToTemplateTypes() {
        final var expected = new LinkedHashMap<String, Object>();
        expected.put("integer", 42L);
        expected.put("fraction", 1.25d);
        expected.put("scaled", 1.0d);
        expected.put("exponent", 100.0d);
        expected.put("items", Arrays.asList("text", true, false, null, Map.of("nested", 5L)));
        final var json = "{\"integer\":42,\"fraction\":1.25,\"scaled\":1.0,\"exponent\":1e2,"
                + "\"items\":[\"text\",true,false,null,{\"nested\":5}]}";
        for (final var decoder : List.<HelmFunction>of(new FromJsonFunc(mapper), new MustFromJsonFunc(mapper))) {
            assertEquals(expected, decoder.execute(json), decoder.name());
            assertEquals(Arrays.asList(1L, null, "x"), decoder.execute("[1,null,\"x\"]"));
        }
    }

    @Test
    void tolerantAndStrictDecodersKeepTheirErrorBehavior() {
        for (final var invalid : List.of("{invalid}", "[", "{\"large\":9223372036854775808}")) {
            assertEquals(Map.of(), new FromJsonFunc(mapper).execute(invalid));
            assertThrows(RuntimeException.class, () -> new MustFromJsonFunc(mapper).execute(invalid));
        }
    }

    @Test
    void encodesNestedValuesNullsAndArbitraryMapKeys() {
        final var values = new LinkedHashMap<Object, Object>();
        values.put(10, Arrays.asList(true, null, "quoted\"\n\\text"));
        values.put("null", null);
        values.put("nested", Map.of("number", 123L));
        final var expected = new LinkedHashMap<String, Object>();
        expected.put("10", values.get(10));
        expected.put("null", null);
        expected.put("nested", Map.of("number", new BigDecimal("123")));
        for (final var encoder : encoders()) {
            final var json = encoder.execute(values).toString();
            assertEquals(expected, mapper.fromString(Object.class, json), encoder.name());
            assertEquals("\"text\"", encoder.execute("text"));
            assertEquals("true", encoder.execute(true));
        }
    }

    @Test
    void preservesNumbersAndUnknownObjectStringFallback() {
        final var value = new Object() {
            @Override
            public String toString() {
                return "template-value";
            }
        };
        for (final var encoder : encoders()) {
            assertEquals("\"template-value\"", encoder.execute(value));
            assertEquals("42", encoder.execute(42L));
            assertEquals("1.5", encoder.execute(1.5d));
        }
        for (final var encoder : List.<HelmFunction>of(
                new ToJsonFunc(mapper),
                new MustToJsonFunc(mapper),
                new MustToPrettyJsonFunc(mapper),
                new ToRawJsonFunc(mapper),
                new MustToRawJsonFunc(mapper))) {
            assertEquals("123456789012345678901", encoder.execute(new BigInteger("123456789012345678901")));
            assertEquals("123.456", encoder.execute(new BigDecimal("123.456")));
        }
        // This function historically accepts any Iterable and uses longValue for non-floating numbers.
        assertEquals("123", new ToPrettyJsonFunc(mapper).execute(new BigDecimal("123.456")));
        assertEquals(
                List.of(new BigDecimal("42")),
                mapper.fromString(
                        Object.class,
                        new ToPrettyJsonFunc(mapper).execute(Set.of(42)).toString()));
    }

    @Test
    void preservesPrettyAndRawOutput() {
        final var value = Map.of("html", "<a href=\"/path\">&</a>");
        assertFalse(new ToJsonFunc(mapper).execute(value).toString().contains("\n"));
        for (final var encoder : List.<HelmFunction>of(
                new ToPrettyJsonFunc(mapper),
                new MustToPrettyJsonFunc(mapper),
                new ToRawJsonFunc(mapper),
                new MustToRawJsonFunc(mapper))) {
            final var json = encoder.execute(value).toString();
            assertTrue(json.contains("\n"), encoder.name());
            assertEquals(value, mapper.fromString(Object.class, json));
        }
        for (final var encoder : List.<HelmFunction>of(new ToRawJsonFunc(mapper), new MustToRawJsonFunc(mapper))) {
            final var json = encoder.execute(value).toString();
            assertTrue(json.contains("<a"));
            assertFalse(json.contains("\\/"));
        }
        // Function use must not close the injected mapper.
        assertNull(mapper.fromString(Object.class, "null"));
    }

    @Test
    void rejectsNonFiniteNumbers() {
        for (final var encoder : encoders()) {
            for (final var invalid : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException.class, () -> encoder.execute(invalid), encoder.name());
                assertThrows(
                        IllegalArgumentException.class,
                        () -> encoder.execute(Map.of("invalid", invalid)),
                        encoder.name());
            }
        }
    }

    @Test
    void preservesExactFormattingWithoutChangingPayloadText() {
        final var value = new LinkedHashMap<String, Object>();
        value.put("value", Arrays.asList(1.25d, null, "{\n  \"value\": <>&/\\\"}"));
        final var expected =
                "{\n  \"value\": [\n    1.25,\n    null,\n    \"{\\n  \\\"value\\\": <>&/\\\\\\\"}\"\n  ]\n}";
        for (final var encoder : List.<HelmFunction>of(
                new ToPrettyJsonFunc(mapper),
                new MustToPrettyJsonFunc(mapper),
                new ToRawJsonFunc(mapper),
                new MustToRawJsonFunc(mapper))) {
            assertEquals(expected, encoder.execute(value), encoder.name());
            assertEquals("{}", encoder.execute(Map.of()));
            assertEquals("[]", encoder.execute(List.of()));
            assertEquals("\"  trailing  \"", encoder.execute("  trailing  "));
        }
        for (final var encoder : encoders()) {
            final var json = encoder.execute(Arrays.asList(null, 3L, "text", false, Map.of("n", 2L)))
                    .toString();
            assertEquals(
                    Arrays.asList(null, new BigDecimal("3"), "text", false, Map.of("n", new BigDecimal("2"))),
                    mapper.fromString(Object.class, json),
                    encoder.name());
        }
    }

    private List<HelmFunction> encoders() {
        return List.of(
                new ToJsonFunc(mapper),
                new MustToJsonFunc(mapper),
                new ToPrettyJsonFunc(mapper),
                new MustToPrettyJsonFunc(mapper),
                new ToRawJsonFunc(mapper),
                new MustToRawJsonFunc(mapper));
    }
}
