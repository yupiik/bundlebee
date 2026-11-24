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
package io.yupiik.bundlebee.maven.interpolation;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import java.util.Objects;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;

/** A Maven expression evaluator owned exclusively by one mojo invocation. */
public final class MavenConfigSource implements ConfigurationSource {
    private final ExpressionEvaluator expressionEvaluator;
    private final boolean allowForcedFiltering;

    public MavenConfigSource(final ExpressionEvaluator expressionEvaluator, final boolean allowForcedFiltering) {
        this.expressionEvaluator = Objects.requireNonNull(expressionEvaluator);
        this.allowForcedFiltering = allowForcedFiltering;
    }

    @Override
    public String get(final String key) {
        try {
            var evaluate = expressionEvaluator.evaluate(key);
            if (wasFiltered(key, evaluate)) {
                return String.valueOf(evaluate);
            }
            if (allowForcedFiltering) {
                evaluate = expressionEvaluator.evaluate("${" + key + "}");
                if (wasFiltered("${" + key + "}", evaluate)) {
                    return String.valueOf(evaluate);
                }
            }
            return null;
        } catch (final ExpressionEvaluationException e) {
            return null;
        }
    }

    private boolean wasFiltered(final String key, final Object evaluate) {
        return evaluate != null && !Objects.equals(key, evaluate);
    }
}
