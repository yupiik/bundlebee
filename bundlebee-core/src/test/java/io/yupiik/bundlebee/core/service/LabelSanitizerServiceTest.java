/*
 * Copyright (c) 2021-2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.service;

import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Cdi
class LabelSanitizerServiceTest {
    @Inject
    private LabelSanitizerService service;


    @Test
    void valid() {
        assertEquals("groupId_artifactId_version", service.sanitize("groupId:artifactId:version"));
        assertEquals("groupId_artifactId_version", service.sanitize("groupId__artifactId__version"));
        assertEquals("groupId_artifactId_version", service.sanitize("groupId/artifactId/version"));
        assertEquals("com.company_my-component_1.0.0", service.sanitize("com.company:my-component:1.0.0"));
    }
}
