package io.yupiik.bundlebee.documentation;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.xbean.finder.IAnnotationFinder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;

@Log
@RequiredArgsConstructor
public class ConfigurationGenerator implements Runnable {
    protected final Path sourceBase;
    protected final Map<String, String> configuration;

    @Override
    public void run() {
        final var module = configuration.get("module");
        final var exclude = configuration.get("exclude");
        try {
            final String lines = generate(exclude, new FinderFactory(configuration).finder());
            final var output = sourceBase
                    .resolve("content/_partials/generated/documentation")
                    .resolve(module.replace(".*", "") + ".adoc");
            java.nio.file.Files.createDirectories(output.getParent());
            java.nio.file.Files.writeString(
                    output,
                    lines.isEmpty() ? "No configuration yet." : lines,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Created " + output);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String generate(final String exclude, final IAnnotationFinder finder) {
        final var formatter = new DocEntryFormatter();
        return finder.findAnnotatedFields(ConfigProperty.class).stream()
                .filter(it -> exclude == null || !it.getDeclaringClass().getName().startsWith(exclude))
                .map(it -> formatter.format(it, identity()))
                .sorted() // by key name to ensure it is deterministic
                .collect(joining("\n\n"));
    }
}
