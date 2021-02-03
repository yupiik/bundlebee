package io.yupiik.bundlebee.core;

import io.yupiik.bundlebee.core.test.BundleBeeExtension;
import io.yupiik.bundlebee.core.test.CommandExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.stream.Stream;

import static java.util.logging.Level.INFO;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleBeeTest {
    @RegisterExtension
    BundleBeeExtension extension = new BundleBeeExtension();

    @Test
    void help(final CommandExecutor executor) {
        final var noArg = executor.wrap(INFO, () -> new BundleBee().launch());
        final var explicitHelp = executor.wrap(INFO, () -> new BundleBee().launch("help"));
        final var missingCommand = executor.wrap(INFO, () -> new BundleBee().launch("missing"));
        Stream.of(noArg, explicitHelp, missingCommand)
                .forEach(content -> {
                    assertTrue(content.contains("  - help: Print help.\n"), content);
                });

        // missing command has an additional error message
        assertTrue(missingCommand.contains("No command found for args: [missing]\n"), missingCommand);
    }
}
