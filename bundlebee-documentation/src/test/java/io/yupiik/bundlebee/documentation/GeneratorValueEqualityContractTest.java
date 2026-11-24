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
package io.yupiik.bundlebee.documentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

public class GeneratorValueEqualityContractTest {
    @Test
    void nullableGeneratorValues() throws Exception {
        for (final var name : List.of(
                AlveoliConfigurationGenerator.class.getName() + "$Placeholder",
                K8sJSONSchemasGenerator.class.getName() + "$Descriptor")) {
            // Exercise value behavior without exposing private generator models.
            final var constructor = Class.forName(name).getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            final var empty = new Object[constructor.getParameterCount()];
            assertContract(
                    constructor.newInstance(empty), constructor.newInstance(empty), constructor.newInstance(empty));
            for (int i = 0; i < empty.length; i++) {
                final var populated = empty.clone();
                populated[i] = "value";
                assertContract(
                        constructor.newInstance(populated),
                        constructor.newInstance(populated),
                        constructor.newInstance(populated));
                assertNotEquals(
                        constructor.newInstance(empty), constructor.newInstance(populated), name + " argument " + i);
                assertNotEquals(
                        constructor.newInstance(populated), constructor.newInstance(empty), name + " argument " + i);
            }
        }
    }

    private void assertContract(final Object left, final Object right, final Object third) {
        assertEquals(left, left);
        assertEquals(left, right);
        assertEquals(right, left);
        assertEquals(right, third);
        assertEquals(left, third);
        assertEquals(left.hashCode(), right.hashCode());
        assertEquals(right.hashCode(), third.hashCode());
        assertNotEquals(left, null);
        assertNotEquals(left, new Object());
    }
}
