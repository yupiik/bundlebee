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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.helm.HelmFunction;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HelmFunctionDocGeneratorTest {
    @TempDir
    private Path temp;

    @Test
    void generate() throws Exception {
        final var srcBase = temp.resolve("src/main/minisite");
        final var currentDir = new File("").getAbsoluteFile().toPath();
        final var helmModule = Files.isDirectory(currentDir.resolve("bundlebee-helm"))
                ? currentDir.resolve("bundlebee-helm")
                : currentDir.resolve("../bundlebee-helm").normalize();
        new HelmFunctionDocGenerator(srcBase, helmModule) {}.run();
        final var output = srcBase.resolve("content/_partials/generated/documentation/helm.functions.adoc");
        assertTrue(Files.exists(output), "Generated file should exist");
        final var content = Files.readString(output);
        assertTrue(content.contains("=== String"), "Should contain String category");
        assertTrue(content.contains("=== Crypto"), "Should contain Crypto category");
        assertTrue(content.length() > 500, "Generated content should be non-trivial");
        assertTrue(content.startsWith("Bundlebee provides 239 built-in template functions.\n"));
        final var rows = content.lines().filter(it -> it.startsWith("| `")).toList();
        assertEquals(239, rows.size());
        assertEquals(239, new HashSet<>(rows).size());
        try (var container = ConfiguringContainer.of().start();
                var functions = container.lookups(HelmFunction.class)) {
            assertEquals(239, functions.instance().size());
            for (final var function : functions.instance()) {
                final var description =
                        function.getClass().getAnnotation(Description.class).value();
                assertTrue(
                        content.contains("| `" + function.name() + "`\n| " + description.replace("|", "\\|") + "\n\n"),
                        function.name());
            }
        }
        for (final var category : content.split("=== ")) {
            final var names = category.lines()
                    .filter(it -> it.startsWith("| `"))
                    .map(it -> it.substring(3, it.length() - 1))
                    .toList();
            assertEquals(names.stream().sorted().toList(), names);
        }
        new HelmFunctionDocGenerator(srcBase, helmModule).run();
        assertEquals(content, Files.readString(output));
    }

    @ParameterizedTest
    @MethodSource("formattedMetadata")
    void parseFormattedMetadata(final String source) throws Exception {
        final var metadata = parseMetadata(source);
        assertEquals("String", metadata.getProperty("category"));
        assertEquals("value", metadata.getProperty("other"));
        assertEquals(2, metadata.size());
    }

    private static Stream<String> formattedMetadata() {
        return Stream.of("\n", "\r\n")
                .flatMap(newline -> Stream.of(
                        "//metadata:start" + newline + "// category = String" + newline + "// other: value" + newline
                                + "//metadata:end" + newline,
                        "  // metadata:start  " + newline + "    // category = String" + newline + "\t// other: value"
                                + newline + "  // metadata:end  ",
                        "\t//\tmetadata \t: start\t" + newline + "\t// category = String" + newline + "// other: value"
                                + newline + "\t//\tmetadata :\tend\t" + newline,
                        "String fake = \"//metadata:start\\n//metadata:end\\n\";" + newline
                                + "String other = \"// metadata:end\";" + newline
                                + "// metadata:start" + newline + "// category = String" + newline + "// other: value"
                                + newline
                                + "// metadata:end" + newline));
    }

    @ParameterizedTest
    @MethodSource("invalidMetadata")
    void rejectInvalidMarkers(final String source) {
        final var error = assertThrows(IllegalStateException.class, () -> parseMetadata(source));
        assertTrue(error.getMessage().contains("metadata:"), error.getMessage());
        assertTrue(error.getMessage().contains("marker"), error.getMessage());
    }

    private static Stream<String> invalidMetadata() {
        return Stream.of(
                "",
                "// metadata:start\n// category = String\n",
                "// metadata:end\n",
                "// metadata:end\n// metadata:start\n",
                "// metadata:start\n// metadata:start\n// metadata:end\n",
                "// metadata:start\n// metadata:end\n// metadata:end\n",
                "// metadata:start\n// metadata:end\n// metadata:start\n// metadata:end\n",
                "String fake = \"//metadata:start\\n//metadata:end\\n\";\n",
                "String start = \"// metadata:start\";\n// category = String\nString end = \"// metadata:end\";\n");
    }

    private Properties parseMetadata(final String source) throws Exception {
        final var method = HelmFunctionDocGenerator.class.getDeclaredMethod("parseMetadata", String.class);
        method.setAccessible(true);
        try {
            return (Properties) method.invoke(new HelmFunctionDocGenerator(temp, temp), source);
        } catch (final InvocationTargetException e) {
            throw (RuntimeException) e.getCause();
        }
    }
}
