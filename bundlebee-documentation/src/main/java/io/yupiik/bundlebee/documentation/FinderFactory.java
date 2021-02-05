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
package io.yupiik.bundlebee.documentation;

import lombok.RequiredArgsConstructor;
import org.apache.xbean.finder.AnnotationFinder;
import org.apache.xbean.finder.ClassLoaders;
import org.apache.xbean.finder.UrlSet;
import org.apache.xbean.finder.archive.ClasspathArchive;
import org.apache.xbean.finder.util.Files;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

@RequiredArgsConstructor
public class FinderFactory {
    protected final Map<String, String> configuration;

    public AnnotationFinder finder() {
        final var module = configuration.get("module");
        final var jarToScan = Pattern.compile(module);
        try {
            final var loader = Thread.currentThread().getContextClassLoader();
            final var dependency = new UrlSet(ClassLoaders.findUrls(loader))
                    .excludeJvm()
                    .getUrls().stream()
                    .map(Files::toFile)
                    .filter(it -> jarToScan.matcher(it.getName()).matches())
                    .findFirst()
                    .orElseThrow();
            return new AnnotationFinder(ClasspathArchive.archive(loader, dependency.toURI().toURL()));
        } catch (final IOException ioe) {
            throw new IllegalArgumentException(ioe);
        }
    }
}
