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
package io.yupiik.bundlebee.maven.interpolation;

import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Map;
import java.util.Objects;

public class MavenConfigSource implements ConfigSource {
    public static ExpressionEvaluator expressionEvaluator;

    @Override
    public Map<String, String> getProperties() {
        return Map.of();
    }

    @Override
    public String getValue(final String s) {
        if (expressionEvaluator == null) {
            return null;
        }
        try {
            final var evaluate = expressionEvaluator.evaluate(s);
            return evaluate == null || Objects.equals(s, evaluate) ? null : String.valueOf(evaluate);
        } catch (final ExpressionEvaluationException e) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "maven";
    }
}
