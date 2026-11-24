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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TemplateValueEqualityContractTest {
    @Test
    void allNodeComponentsParticipate() throws Exception {
        for (final var type : HelmGoTemplateNode.class.getDeclaredClasses()) {
            final var constructor = type.getDeclaredConstructors()[0];
            final var types = constructor.getParameterTypes();
            final var empty = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                empty[i] = types[i] == boolean.class ? false : null;
            }
            assertContract(
                    constructor.newInstance(empty), constructor.newInstance(empty), constructor.newInstance(empty));
            for (int i = 0; i < types.length; i++) {
                final var populated = empty.clone();
                populated[i] = types[i] == boolean.class
                        ? true
                        : types[i] == String.class
                                ? "value"
                                : types[i] == List.class ? List.of() : new HelmGoTemplateNode.NilNode();
                assertContract(
                        constructor.newInstance(populated),
                        constructor.newInstance(populated),
                        constructor.newInstance(populated));
                assertNotEquals(
                        constructor.newInstance(empty),
                        constructor.newInstance(populated),
                        type.getName() + " argument " + i);
                assertNotEquals(
                        constructor.newInstance(populated),
                        constructor.newInstance(empty),
                        type.getName() + " argument " + i);
            }
        }
        assertNotEquals(new HelmGoTemplateNode.StringNode("1"), new HelmGoTemplateNode.NumberNode("1"));
        assertEquals(1, new HelmGoTemplateNode.NilNode().hashCode());
    }

    @Test
    void nestedTreesAndGetterOverrides() {
        final var left = new HelmGoTemplateNode.IfNode(
                new HelmGoTemplateNode.BoolNode(true),
                List.of(new HelmGoTemplateNode.TextNode("body", false, true)),
                null);
        final var right = new HelmGoTemplateNode.IfNode(
                new HelmGoTemplateNode.BoolNode(true),
                List.of(new HelmGoTemplateNode.TextNode("body", false, true)),
                null);
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());

        final var overridden = new HelmGoTemplateNode.TextNode("stored", false, false) {
            @Override
            public String getText() {
                return "visible";
            }

            @Override
            public boolean isTrimLeft() {
                return true;
            }
        };
        final var visible = new HelmGoTemplateNode.TextNode("visible", true, false);
        assertEquals(visible, overridden);
        assertEquals(overridden, visible);
        assertEquals(visible.hashCode(), overridden.hashCode());

        final var restricted = new HelmGoTemplateNode.NilNode() {
            @Override
            protected boolean canEqual(final Object other) {
                return other == this;
            }
        };
        assertEquals(restricted, restricted);
        assertNotEquals(new HelmGoTemplateNode.NilNode(), restricted);
    }

    @Test
    void tokensRetainFlagsAndNullableValues() {
        assertContract(
                new HelmGoTemplateLexer.Token(null, null, false, false),
                new HelmGoTemplateLexer.Token(null, null, false, false),
                new HelmGoTemplateLexer.Token(null, null, false, false));
        final var token = new HelmGoTemplateLexer.Token(HelmGoTemplateLexer.TokenType.TEXT, "text", true, false);
        assertEquals(
                new HelmGoTemplateLexer.Token(HelmGoTemplateLexer.TokenType.TEXT, "text", true, false).hashCode(),
                token.hashCode());
        assertNotEquals(token, new HelmGoTemplateLexer.Token(null, "text", true, false));
        assertNotEquals(token, new HelmGoTemplateLexer.Token(HelmGoTemplateLexer.TokenType.TEXT, null, true, false));
        assertNotEquals(token, new HelmGoTemplateLexer.Token(HelmGoTemplateLexer.TokenType.TEXT, "text", false, false));
        assertNotEquals(token, new HelmGoTemplateLexer.Token(HelmGoTemplateLexer.TokenType.TEXT, "text", true, true));
        final var overridden = new HelmGoTemplateLexer.Token(null, "stored", true, false) {
            @Override
            public HelmGoTemplateLexer.TokenType getType() {
                return HelmGoTemplateLexer.TokenType.TEXT;
            }

            @Override
            public String getValue() {
                return "text";
            }
        };
        assertEquals(token, overridden);
        assertEquals(overridden, token);
        assertEquals(token.hashCode(), overridden.hashCode());
    }

    @Test
    void chartsUseAllGetterValues() {
        assertContract(new HelmChart(), new HelmChart(), new HelmChart());
        final var left = new HelmChart();
        final var right = new HelmChart();
        left.setName("name");
        assertNotEquals(left, right);
        right.setName("name");
        left.setVersion("1");
        assertNotEquals(left, right);
        right.setVersion("1");
        left.setAppVersion("2");
        assertNotEquals(left, right);
        right.setAppVersion("2");
        left.setValues(Map.of("key", "value"));
        assertNotEquals(left, right);
        right.setValues(Map.of("key", "value"));
        left.setTemplates(Map.of("template", "content"));
        assertNotEquals(left, right);
        right.setTemplates(Map.of("template", "content"));
        left.setDefines(Map.of("define", "content"));
        assertNotEquals(left, right);
        right.setDefines(Map.of("define", "content"));
        assertEquals(left, right);
        assertEquals(left.hashCode(), right.hashCode());
        final var visible = new HelmChart();
        visible.setName("visible");
        final var overridden = new HelmChart() {
            @Override
            public String getName() {
                return "visible";
            }
        };
        assertEquals(visible, overridden);
        assertEquals(overridden, visible);
        assertEquals(visible.hashCode(), overridden.hashCode());
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
