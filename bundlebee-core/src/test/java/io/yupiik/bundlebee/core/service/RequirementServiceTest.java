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
package io.yupiik.bundlebee.core.service;

import io.yupiik.bundlebee.core.descriptor.Manifest;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
class RequirementServiceTest {
    @ParameterizedTest
    @CsvSource({
            "1.0.0,null,null,null,true",
            "1.0.0,null,0.9.9,null,false",
            "1.0.0,0.9.9,null,null,true",
            "1.0.0,1.0.1,null,null,false",
            "1.0.0,null,null,1.0.0,false",
            "1.0.0,null,null,1.0.0,false",
            "1.0.0,null,null,1.0.*,false",
            "1.0.0,null,null,1.*.*,false",
            "1.0.11,null,null,1.*.*,false",
            "1.0.11,null,null,1.*.10,true",
            "1.0.0,null,null,*.*.*,false",
            "1.0.0,1.0.0,null,null,true",
            "1.0.0,null,0.9.9,null,false",
            "1.0.0,1.0.0,0.9.9,null,false",
            "1.0.0,1.0.0,1.0.0,null,true",
            "1.0.0,1.0.0,1.0.0,null,true",
            "1.0.0,1.0.*,1.0.0,null,true",
            "1.0.0,1.*.0,1.0.0,null,true",
            "1.0.0,1.*.*,1.0.0,null,true",
            "1.0.0,1.0.0,1.0.*,null,true",
            "1.0.0,1.0.0,1.*.0,null,true",
            "1.0.0,1.0.0,1.*.*,null,true",
            "1.0.0,1.0.*,1.0.*,null,true",
            "1.0.0,1.*.0,1.0.*,null,true",
            "1.0.0,1.0.*,1.*.0,null,true",
            "1.0.0,1.*.*,1.*.0,null,true",
            "1.0.0,1.*.*,1.*.*,null,true",
            "1.0.0,1.*.*,1.*.*,1.0.1,true",
            "1.0.0,1.*.*,1.*.*,1.1.0,true",
            "1.0.0,1.*.*,1.*.*,1.1.*,true",
    })
    void check(final String currentVersion, final String min, final String max, final String forbidden, final boolean expected) {
        final var service = new RequirementService() {
            @Override
            protected String getBundlebeeVersion() {
                return currentVersion;
            }
        };
        final var check = new Manifest.Requirement();
        if (!"null".equals(min)) {
            check.setMinBundlebeeVersion(min);
        }
        if (!"null".equals(max)) {
            check.setMaxBundlebeeVersion(max);
        }
        if (!"null".equals(forbidden)) {
            check.setForbiddenVersions(List.of(forbidden.split(",")));
        }
        final var manifest = new Manifest();
        manifest.setRequirements(List.of(check));

        if (expected) {
            service.checkRequirements(manifest);
        } else {
            assertThrows(IllegalArgumentException.class, () -> service.checkRequirements(manifest));
        }
    }

}
