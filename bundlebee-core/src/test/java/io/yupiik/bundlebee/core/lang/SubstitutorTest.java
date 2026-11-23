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
package io.yupiik.bundlebee.core.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.yupiik.bundlebee.core.configuration.ThreadLocalConfigSource;
import io.yupiik.fusion.framework.api.configuration.Configuration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubstitutorTest {

    @Test
    void explicitExecutionIdAndNullSource() {
        final var substitute = new Substitutor(key -> {
            throw new IllegalStateException("Built-in executionId must not use lookup");
        });
        assertEquals("run", substitute.replace("{{executionId}}", "run"));
        assertEquals("", substitute.replace("{{executionId:-ignored}}", null));
        assertNull(substitute.replace(null, "run"));
    }

    @Test
    void lookupFailureUsesOnlyExplicitFallback() {
        final var failure = new IllegalStateException("lookup");
        final var substitute = new Substitutor(key -> {
            throw failure;
        });
        assertEquals("fallback", substitute.replace("{{key:-fallback}}", "run"));
        assertEquals("", substitute.replace("{{key:-}}", null));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> substitute.replace("{{key}}", "run")));
    }

    @Test
    void digest() {
        assertEquals(
                Base64.getEncoder()
                        .encodeToString(("{" + "   \"auths\": {" + "     \"localhost:5000\": {"
                                        + "       \"username\": \"test\"," + "       \"password\": \"secret\","
                                        + "       \"email\": \"test@test.com\","
                                        + "       \"auth\": \"dGVzdDpzZWNyZXQ=\"" + "     }" + "   }" + " } " + "")
                                .getBytes(StandardCharsets.UTF_8)),
                new SubstitutorProducer(null, null, null, null, null, null, new ThreadLocalConfigSource())
                        .substitutor(Configuration.of(
                                Map.of("domain", "localhost:5000", "username", "test", "password", "secret")))
                        .replace(
                                "{{bundlebee-base64:{{bundlebee-inlined-file:src/test/resources/dockerconfig.json}}}}",
                                null));
    }

    @Test
    void replace() {
        assertEquals(
                "foo replaced dummy",
                new Substitutor(k -> "key".equals(k) ? "replaced" : null).replace("foo {{key}} dummy", null));
    }

    @Test
    void fallback() {
        assertEquals("foo or dummy", new Substitutor(k -> null).replace("foo {{key:-or}} dummy", null));
    }

    @Test
    void escaped() {
        // we want to enable this escaping to propagate when not escaping {{}},
        // case where a bash script is injected in a deployment for ex (init container)
        assertEquals("foo {{key:-or}} dummy", new Substitutor(k -> null).replace("foo \\{{key:-or}} dummy", null));
        assertEquals(
                "foo before {{key:-or}} / {{key:-or2}} after dummy",
                new Substitutor(k -> {
                            switch (k) {
                                case "suffix":
                                    return "after";
                                case "prefix":
                                    return "before";
                                default:
                                    return null;
                            }
                        })
                        .replace("foo {{prefix}} \\{{key:-or}} / {{test:-\\{{key:-or2}}}} {{suffix}} dummy", null));
    }

    @Test
    void nested() {
        assertEquals(
                "foo replaced dummy",
                new Substitutor(k -> "key".equals(k) ? "replaced" : null)
                        .replace("foo {{k{{missing:-e}}y}} dummy", null));
    }

    @Test
    void complex() {
        assertEquals(
                "1",
                new Substitutor(k -> "name".equals(k) ? "foo" : null)
                        .replace("{{{{name}}.resources.limits.cpu:-{{resources.limits.cpu:-1}}}}", null));
        assertEquals(
                "2",
                new Substitutor(k -> {
                            switch (k) {
                                case "name":
                                    return "foo";
                                case "foo.resources.limits.cpu":
                                    return "2";
                                default:
                                    return null;
                            }
                        })
                        .replace("{{{{name}}.resources.limits.cpu:-{{resources.limits.cpu:-1}}}}", null));
    }
}
