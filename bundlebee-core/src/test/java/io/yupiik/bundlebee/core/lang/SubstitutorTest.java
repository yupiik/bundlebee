/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SubstitutorTest {
    @Test
    void digest() {
        assertEquals(
                Base64.getEncoder().encodeToString(("" +
                        "{" +
                        "   \"auths\": {" +
                        "     \"localhost:5000\": {" +
                        "       \"username\": \"test\"," +
                        "       \"password\": \"secret\"," +
                        "       \"email\": \"test@test.com\"," +
                        "       \"auth\": \"dGVzdDpzZWNyZXQ=\"" +
                        "     }" +
                        "   }" +
                        " } " +
                        "").getBytes(StandardCharsets.UTF_8)),
                new SubstitutorProducer().substitutor(new Config() {
                    @Override
                    public <T> T getValue(final String s, final Class<T> aClass) {
                        switch (s) {
                            case "domain":
                                return aClass.cast("localhost:5000");
                            case "username":
                                return aClass.cast("test");
                            case "password":
                                return aClass.cast("secret");
                            default:
                                return null;
                        }
                    }

                    @Override
                    public <T> Optional<T> getOptionalValue(final String s, final Class<T> aClass) {
                        return ofNullable(getValue(s, aClass));
                    }

                    @Override
                    public Iterable<String> getPropertyNames() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public Iterable<ConfigSource> getConfigSources() {
                        throw new UnsupportedOperationException();
                    }
                }).replace("{{bundlebee-base64:{{bundlebee-inlined-file:src/test/resources/dockerconfig.json}}}}"));
    }

    @Test
    void replace() {
        assertEquals("foo replaced dummy", new Substitutor(k -> "key".equals(k) ? "replaced" : null).replace("foo {{key}} dummy"));
    }

    @Test
    void fallback() {
        assertEquals("foo or dummy", new Substitutor(k -> null).replace("foo {{key:-or}} dummy"));
    }

    @Test
    void nested() {
        assertEquals("foo replaced dummy", new Substitutor(k -> "key".equals(k) ? "replaced" : null).replace("foo {{k{{missing:-e}}y}} dummy"));
    }

    @Test
    void complex() {
        assertEquals("1", new Substitutor(k -> "name".equals(k) ? "foo" : null)
                .replace("{{{{name}}.resources.limits.cpu:-{{resources.limits.cpu:-1}}}}"));
        assertEquals("2", new Substitutor(k -> {
            switch (k) {
                case "name":
                    return "foo";
                case "foo.resources.limits.cpu":
                    return "2";
                default:
                    return null;
            }
        }).replace("{{{{name}}.resources.limits.cpu:-{{resources.limits.cpu:-1}}}}"));
    }
}
