/*
 * Copyright (c) 2021 - Yupiik SAS - https://www.yupiik.com
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubstitutorTest {
    @Test
    void replace() {
        assertEquals("foo replaced dummy", new Substitutor(k -> "key".equals(k) ? "replaced" : null).replace("foo {{key}} dummy"));
    }

    @Test
    void fallback() {
        assertEquals("foo or dummy", new Substitutor(k -> null).replace("foo {{key:-or}} dummy"));
    }
}
