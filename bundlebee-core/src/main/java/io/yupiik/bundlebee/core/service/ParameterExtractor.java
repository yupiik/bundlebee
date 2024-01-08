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

import io.yupiik.bundlebee.core.configuration.Description;
import lombok.Data;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.spi.BeanManager;
import javax.inject.Inject;
import java.util.function.Function;
import java.util.stream.Stream;

@ApplicationScoped
public class ParameterExtractor {
    @Inject
    private BeanManager beanManager;

    public Stream<Parameter> toParameters(final Class<?> type, final Function<String, String> prefix) {
        return beanManager.createInjectionTarget(beanManager.createAnnotatedType(type)).getInjectionPoints().stream()
                .filter(it -> it.getAnnotated().isAnnotationPresent(ConfigProperty.class) && it.getAnnotated().isAnnotationPresent(Description.class))
                .map(it -> {
                    final var annotated = it.getAnnotated();
                    final var annotation = annotated.getAnnotation(ConfigProperty.class);
                    var name = annotation.name();
                    if (name.isEmpty()) {
                        name = it.getMember().getDeclaringClass().getName() + '.' + it.getMember().getName();
                    }
                    final var desc = annotated.getAnnotation(Description.class).value();
                    return new Parameter(
                            "--" + prefix.apply(name),
                            ConfigProperty.UNCONFIGURED_VALUE.equals(annotation.defaultValue()) ? null : annotation.defaultValue(),
                            Character.toLowerCase(desc.charAt(0)) + desc.substring(1));
                });
    }

    @Data
    public static class Parameter {
        private final String name;
        private final String defaultValue;
        private final String description;
    }
}
