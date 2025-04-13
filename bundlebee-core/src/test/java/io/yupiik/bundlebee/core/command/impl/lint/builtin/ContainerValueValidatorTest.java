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
package io.yupiik.bundlebee.core.command.impl.lint.builtin;

import io.yupiik.bundlebee.core.command.impl.lint.LintError;
import io.yupiik.bundlebee.core.command.impl.lint.LintingCheck;
import org.junit.jupiter.api.Test;

import javax.json.Json;
import javax.json.JsonObject;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerValueValidatorTest {
    private final LintError ERROR_MARKER = new LintError(LintError.LintLevel.INFO, "seen");
    private final JsonObject CRON_JOB = json("{\n" +
            "  \"apiVersion\": \"batch/v1\",\n" +
            "  \"kind\": \"CronJob\",\n" +
            "  \"metadata\": {\n" +
            "    \"name\": \"hello\"\n" +
            "  },\n" +
            "  \"spec\": {\n" +
            "    \"schedule\": \"* * * * *\",\n" +
            "    \"jobTemplate\": {\n" +
            "      \"spec\": {\n" +
            "        \"template\": {\n" +
            "          \"spec\": {\n" +
            "            \"containers\": [\n" +
            "              {\n" +
            "                \"name\": \"hello\",\n" +
            "                \"image\": \"busybox:1.28\",\n" +
            "                \"imagePullPolicy\": \"IfNotPresent\",\n" +
            "                \"command\": [\n" +
            "                  \"/bin/sh\",\n" +
            "                  \"-c\",\n" +
            "                  \"date; echo Hello from the Kubernetes cluster\"\n" +
            "                ]\n" +
            "              }\n" +
            "            ],\n" +
            "            \"restartPolicy\": \"OnFailure\"\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}");

    @Test
    void cronjobPodSpecLookup() {
        final var validator = new ContainerValueValidator(){
            @Override
            public String name() {
                return getClass().getName();
            }

            @Override
            public String description() {
                return name();
            }

            @Override
            public String remediation() {
                return "";
            }

            @Override
            protected Stream<LintError> validate(final JsonObject container, final LintableDescriptor descriptor) {
                return Stream.of(ERROR_MARKER);
            }
        };
        final var errors = validator.validateSync(new LintingCheck.LintableDescriptor("test", "test", CRON_JOB)).collect(toList());
        assertEquals(errors, List.of(ERROR_MARKER));
    }

    private JsonObject json(final String json) {
        try (final var parser = Json.createParserFactory(Map.of()).createParser(new StringReader(json))) {
            return parser.getObject();
        }
    }
}
