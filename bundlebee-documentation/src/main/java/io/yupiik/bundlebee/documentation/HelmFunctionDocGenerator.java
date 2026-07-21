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
package io.yupiik.bundlebee.documentation;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.helm.HelmFunction;
import lombok.extern.java.Log;
import org.apache.xbean.finder.AnnotationFinder;
import org.apache.xbean.finder.ClassLoaders;
import org.apache.xbean.finder.UrlSet;
import org.apache.xbean.finder.archive.ClasspathArchive;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.joining;

/**
 * Scans all {@link HelmFunction} implementations using xbean-finder and generates
 * an AsciiDoc fragment documenting each function with its description.
 * The generated file is included by {@code helm.adoc}.
 * <p>
 * Metadata is embedded in source files using comment blocks:
 * <pre>
 * //metadata:start
 * category = String
 * //metadata:end
 * </pre>
 * The actual description comes from the {@code @Description} annotation.
 */
@Log
public class HelmFunctionDocGenerator implements Runnable {
    private final Path sourceBase;
    private final Path helmModule;

    public HelmFunctionDocGenerator(final Path sourceBase) {
        this(sourceBase, sourceBase.getParent().getParent().getParent().getParent().resolve("bundlebee-helm"));
    }

    protected HelmFunctionDocGenerator(final Path sourceBase, final Path helmModule) {
        this.sourceBase = sourceBase;
        this.helmModule = helmModule;
    }

    @Override
    public void run() {
        final var finder = createFinder();
        final var functions = new LinkedHashMap<String, FunctionInfo>();
        for (final var desc : finder.findImplementations(HelmFunction.class)) {
            final Class<?> clazz;
            try {
                clazz = Class.forName(desc.getName(), false, Thread.currentThread().getContextClassLoader());
            } catch (final ClassNotFoundException e) {
                continue;
            }
            if (Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }

            final var name = resolveFunctionNameViaReflection(clazz);
            if (name == null) {
                continue;
            }

            final var info = resolveFunctionInfo(clazz, name);
            functions.put(name, info);
        }

        final var sorted = new ArrayList<>(functions.entrySet());
        sorted.sort(Map.Entry.comparingByKey());

        // Group by category
        final var grouped = new LinkedHashMap<String, List<Map.Entry<String, FunctionInfo>>>();
        for (final var entry : sorted) {
            final var cat = entry.getValue().category();
            grouped.computeIfAbsent(cat, k -> new ArrayList<>()).add(entry);
        }

        final var sb = new StringBuilder();
        sb.append("Bundlebee provides ").append(functions.size()).append(" built-in template functions.\n");
        sb.append("These are compatible with the Sprig function library used by Helm.\n\n");
        for (final var catEntry : grouped.entrySet()) {
            sb.append("=== ").append(catEntry.getKey()).append("\n\n");
            sb.append("[cols=\"1,3\", options=\"header\"]\n");
            sb.append("|===\n");
            sb.append("| Function | Description\n\n");
            for (final var fn : catEntry.getValue()) {
                sb.append("| `").append(escapeAdoc(fn.getKey())).append("`");
                sb.append("\n| ").append(escapeAdoc(fn.getValue().description())).append("\n\n");
            }
            sb.append("|===\n\n");
        }

        try {
            final var output = Files.createDirectories(
                    sourceBase.resolve("content/_partials/generated/documentation"))
                    .resolve("helm.functions.adoc");
            Files.writeString(output, sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + output);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String resolveFunctionNameViaReflection(final Class<?> clazz) {
        try {
            final var nameMethod = clazz.getMethod("name");
            final Object instance;
            try {
                instance = clazz.getDeclaredConstructor().newInstance();
            } catch (final Exception e) {
                final var simpleName = clazz.getSimpleName();
                if (simpleName.endsWith("Func")) {
                    return simpleName.substring(0, simpleName.length() - 4).toLowerCase();
                }
                throw new IllegalStateException(e);
            }
            return (String) nameMethod.invoke(instance);
        } catch (final Exception e) {
            throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
        }
    }

    private FunctionInfo resolveFunctionInfo(final Class<?> clazz, final String name) {
        final var sourceFile = findSourceFile(clazz);
        if (sourceFile == null || Files.notExists(sourceFile)) {
            throw new IllegalStateException("Missing source for '" + name + "' (" + sourceFile + ")");
        }
        try {
            final var content = Files.readString(sourceFile);
            final var metadata = parseMetadata(content);
            final var description = requireNonNull(clazz.getAnnotation(Description.class), clazz.getName()).value();
            final var category = ofNullable(metadata.getProperty("category")).orElseThrow(() -> fail(name));
            return new FunctionInfo(description, category);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path findSourceFile(final Class<?> clazz) {
        if (helmModule == null) {
            return null;
        }
        final var className = clazz.getName().replace('.', '/') + ".java";
        return helmModule.resolve("src/main/java/" + className);
    }

    private Properties parseMetadata(final String content) {
        final var props = new Properties();
        final var start = content.indexOf("//metadata:start\n");
        final var end = content.indexOf("//metadata:end\n", start+1);
        if (end < 0 || start < 0) {
            throw new IllegalStateException("No //metadata:start/end in\n" + content);
        }

        try (final var reader = new StringReader(
                Stream.of(content.substring(content.indexOf('\n', start + 1), end).trim().split("\n"))
                        .map(it -> it.startsWith("//") ? it.substring("//".length()).trim() + "\n" : "")
                        .collect(joining("")))) {
            props.load(reader);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }

        return props;
    }

    private RuntimeException fail(final String name) {
        return new IllegalStateException("'" + name + "' helm function misses category metadata");
    }

    private String escapeAdoc(final String text) {
        if (text == null) {
            return "";
        }
        return text.replace("|", "\\|");
    }

    private AnnotationFinder createFinder() {
        try {
            final var loader = Thread.currentThread().getContextClassLoader();
            final var urls = new UrlSet(ClassLoaders.findUrls(loader))
                    .excludeJvm()
                    .getUrls();
            final var targetJar = urls.stream()
                    .map(org.apache.xbean.finder.util.Files::toFile)
                    .filter(f -> "classes".equals(f.getName()) && new File(f, "io/yupiik/bundlebee/helm").isDirectory() ||
                            f.getName().contains("bundlebee-helm"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("bundlebee-helm not found on classpath"));
            return new AnnotationFinder(ClasspathArchive.archive(loader, targetJar.toURI().toURL()));
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FunctionInfo {
        private final String description;
        private final String category;

        FunctionInfo(final String description, final String category) {
            this.description = description;
            this.category = category;
        }

        String description() { return description; }
        String category() { return category; }
    }
}
