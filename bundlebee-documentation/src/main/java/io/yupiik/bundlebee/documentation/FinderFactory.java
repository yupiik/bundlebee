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
