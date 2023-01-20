/*
 * Copyright (c) 2021-2023 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.junit5;

import io.yupiik.bundlebee.junit5.impl.ValidateExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Will execute an {@code apply} in dry run mode.
 * It validates the descriptors and placeholders are resolved.
 *
 * To run in dry mode, it will setup a Kubernetes API backend - instead of using {@code --dry-run} option
 * to let you inject in your test method parameters {@link KubernetesApi} and enable you to customize the backend responses at need.
 *
 * You can also inject the {@link CommandExecutor} to manually trigger the execution instead of doing it implicitly.
 * It enables you to control and validate errors if needed.
 *
 * Also note that the mock server (kubernetes api) is auto-configured so don't try to configure kube config in options until you exactly know what you do.
 */
@Test
@Target(METHOD)
@Retention(RUNTIME)
@ExtendWith(ValidateExtension.class)
public @interface ValidateAlveolus {
    /**
     * @return the alveolus name.
     */
    String value();

    /**
     * @return the command to execute.
     */
    String command() default "apply";

    /**
     * @return custom options to use on apply command line.
     */
    String[] options() default {};

    /**
     * @return placeholders to test.
     */
    KeyValue[] placeholders() default {};
}
