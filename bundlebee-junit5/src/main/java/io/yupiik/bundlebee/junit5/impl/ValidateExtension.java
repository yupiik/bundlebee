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
package io.yupiik.bundlebee.junit5.impl;

import io.yupiik.bundlebee.junit5.CommandExecutor;
import io.yupiik.bundlebee.junit5.KubernetesApi;
import io.yupiik.bundlebee.junit5.LogCapturer;
import io.yupiik.bundlebee.junit5.ValidateAlveolus;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.reflect.Method;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.fail;

public class ValidateExtension implements BeforeEachCallback, ParameterResolver, AfterEachCallback, InvocationInterceptor {
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(ValidateExtension.class);

    @Override
    public void beforeEach(final ExtensionContext extensionContext) {
        final var store = extensionContext.getStore(NAMESPACE);
        store.getOrComputeIfAbsent(KubernetesApi.class, k -> {
            final var mock = new KubernetesMock();
            mock.start();
            return mock;
        });
        store.getOrComputeIfAbsent(LogCapturer.class, k -> new DefaultLogCapturer());
    }

    @Override
    public void interceptTestMethod(final Invocation<Void> invocation, final ReflectiveInvocationContext<Method> invocationContext,
                                    final ExtensionContext extensionContext) throws Throwable {
        invocation.proceed();
        if (extensionContext.getStore(NAMESPACE).get(CommandExecutor.class) == null) {
            newExecutor(extensionContext).run();
        }
    }

    @Override
    public void afterEach(final ExtensionContext extensionContext) {
        final var store = extensionContext.getStore(NAMESPACE);
        ofNullable(store.get(KubernetesApi.class))
                .filter(AutoCloseable.class::isInstance)
                .map(AutoCloseable.class::cast)
                .ifPresent(it -> {
                    try {
                        it.close();
                    } catch (final Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
        final var executor = store.get(CommandExecutor.class, CommandExecutor.class);
        if (DefaultCommandExecutor.class.isInstance(executor) && !DefaultCommandExecutor.class.cast(executor).wasExecuted()) {
            fail("no execution detected, ensure you called CommandExecutor.run()");
        }
    }

    @Override
    public boolean supportsParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().isAnnotationPresent(Injectable.class);
    }

    @Override
    public Object resolveParameter(final ParameterContext parameterContext, final ExtensionContext extensionContext) throws ParameterResolutionException {
        final var type = parameterContext.getParameter().getType();
        final var store = extensionContext.getStore(NAMESPACE);
        final var instance = store.get(type, type);
        if (instance == null) {
            if (type == CommandExecutor.class) { // enable to be lazy, means "don't run yourself, user managed"
                return store.getOrComputeIfAbsent(CommandExecutor.class, k -> newExecutor(extensionContext));
            }
        }
        return requireNonNull(instance, () -> type.getName() + " missing");
    }

    private DefaultCommandExecutor newExecutor(final ExtensionContext extensionContext) {
        final var config = AnnotationUtils.findAnnotation(extensionContext.getRequiredTestMethod(), ValidateAlveolus.class)
                .orElseThrow(() -> new IllegalArgumentException("No @ValidateAlveolus on " + extensionContext.getRequiredTestMethod()));
        return new DefaultCommandExecutor(
                config,
                extensionContext.getStore(NAMESPACE).get(KubernetesApi.class, KubernetesApi.class).base(),
                extensionContext.getStore(NAMESPACE).get(LogCapturer.class, LogCapturer.class));
    }
}
