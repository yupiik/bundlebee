package io.yupiik.bundlebee.core.http;

import io.yupiik.bundlebee.core.qualifier.BundleBee;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import java.net.http.HttpClient;
import java.util.concurrent.ForkJoinPool;

@ApplicationScoped
public class HttpClientProducer {
    @Produces
    @BundleBee
    @ApplicationScoped
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                /*todo: config*/
                .executor(ForkJoinPool.commonPool())
                .build();
    }
}
