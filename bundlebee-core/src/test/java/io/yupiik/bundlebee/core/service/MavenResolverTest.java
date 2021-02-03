package io.yupiik.bundlebee.core.service;

import org.apache.openwebbeans.junit5.Cdi;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.nio.file.Files;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Cdi
class MavenResolverTest {
    @Inject
    private MavenResolver resolver;

    @Test
    void resolveLocal() throws ExecutionException, InterruptedException {
        assertTrue(Files.exists(resolver.findOrDownload("org.apache.geronimo.specs:geronimo-jcdi_2.0_spec:1.3")
                .toCompletableFuture().get()));
    }
}
