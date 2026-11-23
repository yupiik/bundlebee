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
package io.yupiik.bundlebee.operator;

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.bundlebee.operator.configuration.OperatorConfiguration;
import io.yupiik.bundlebee.operator.handler.ActionHandler;
import io.yupiik.bundlebee.operator.handler.AlveoliOperator;
import io.yupiik.bundlebee.operator.handler.CommandRunner;
import io.yupiik.bundlebee.operator.model.AlveoliResource;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.main.ArgsConfigSource;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.kubernetes.operator.base.impl.FusionGeneratedModule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BundlebeeOperatorTest {
    @Test
    void bootstrapArguments() {
        final var source = new ArgsConfigSource(List.of("--first", "1", "--second=2"));
        assertEquals("1", source.get("first"));
        assertEquals("2", source.get("second"));
        assertEquals("1,2", new ArgsConfigSource(List.of("--first", "1", "--first", "2")).get("first"));
        assertNull(new ArgsConfigSource(List.of()).get("first"));
        assertThrows(IllegalArgumentException.class, () -> new ArgsConfigSource(List.of("--missing")));
    }

    @Test
    void callbacksApplyModificationsWithoutDeletingFirstAndPropagateFailures() {
        final var invocations = new ArrayList<List<String>>();
        final var runner = new CommandRunner(null, null, null) {
            @Override
            public void run(final String[] args) {
                invocations.add(List.of(args));
                if (List.of(args).contains("fail")) {
                    throw new IllegalStateException("observer failure");
                }
            }
        };
        final var operator = new AlveoliOperator(new ActionHandler(runner), new OperatorConfiguration(List.of("test")));
        final var resource = object("test");
        operator.onAdd(resource);
        operator.onModify(resource);
        operator.onDelete(resource);
        assertEquals(
                List.of(
                        List.of("apply", "--alveolus", "test"),
                        List.of("apply", "--alveolus", "test"),
                        List.of("delete", "--alveolus", "test")),
                invocations);
        assertThrows(IllegalStateException.class, () -> operator.onModify(object("fail")));
        assertEquals(AlveoliResource.class, operator.resourceType());
        assertEquals("bundlebee.yupiik.io/v1", operator.apiVersion());
        assertEquals("alveoli", operator.pluralName());
        assertEquals(List.of("test"), operator.namespaces());
    }

    @Test
    void generatedResourceCodecRetainsUidAndResourceVersion() {
        try (final var container = ConfiguringContainer.of()
                        .disableModule(FusionGeneratedModule.class)
                        .start();
                final var mapper = container.lookup(JsonMapper.class)) {
            final var resource = mapper.instance()
                    .fromString(
                            AlveoliResource.class,
                            "{\"metadata\":{\"uid\":\"uid-1\",\"name\":\"test\",\"namespace\":\"ns\",\"resourceVersion\":\"v1\"},\"spec\":{\"args\":[\"--alveolus\",\"test\"]}}");
            assertEquals("uid-1", resource.metadata().uid());
            assertEquals("v1", resource.metadata().resourceVersion());
            assertEquals(
                    resource,
                    mapper.instance()
                            .fromString(AlveoliResource.class, mapper.instance().toString(resource)));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> resource.spec().args().add("mutate"));
        }
    }

    private AlveoliResource object(final String alveolus) {
        return new AlveoliResource(
                new AlveoliResource.Metadata("uid", alveolus, "test", "rv"),
                new AlveoliResource.Spec(List.of("--alveolus", alveolus)));
    }
}
