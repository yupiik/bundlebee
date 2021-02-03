package io.yupiik.bundlebee.core.command;

import java.util.concurrent.CompletionStage;

public interface Executable {
    String name();

    String description();

    CompletionStage<?> execute();
}
