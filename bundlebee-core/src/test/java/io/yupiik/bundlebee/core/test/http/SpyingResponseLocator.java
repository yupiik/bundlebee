package io.yupiik.bundlebee.core.test.http;

import lombok.Getter;
import org.talend.sdk.component.junit.http.api.Request;
import org.talend.sdk.component.junit.http.api.Response;
import org.talend.sdk.component.junit.http.internal.impl.DefaultResponseLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public class SpyingResponseLocator extends DefaultResponseLocator {
    @Getter
    private final List<Response> found = new ArrayList<>();

    public SpyingResponseLocator(final String test) {
        super("mock/http/", test);
    }

    @Override
    public Optional<Response> findMatching(Request request, Predicate<String> headerFilter) {
        return super.findMatching(request, headerFilter)
                .map(it -> {
                    synchronized (found) {
                        found.add(it);
                    }
                    return it;
                });
    }
}
