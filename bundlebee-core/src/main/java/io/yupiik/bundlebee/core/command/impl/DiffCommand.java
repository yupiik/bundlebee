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
package io.yupiik.bundlebee.core.command.impl;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.HttpKubeClient;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.qualifier.BundleBee;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import lombok.Data;
import lombok.extern.java.Log;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.json.JsonArray;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonPointer;
import javax.json.JsonString;
import javax.json.JsonStructure;
import javax.json.JsonValue;
import javax.json.JsonWriterFactory;
import javax.json.bind.Jsonb;
import javax.json.spi.JsonProvider;
import javax.json.stream.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.yupiik.bundlebee.core.command.impl.DiffCommand.ActualStatus.EXISTS;
import static io.yupiik.bundlebee.core.command.impl.DiffCommand.ActualStatus.MISSING;
import static io.yupiik.bundlebee.core.command.impl.DiffCommand.DiffType.AUTO;
import static io.yupiik.bundlebee.core.command.impl.DiffCommand.DiffType.JSON_PATCH;
import static java.util.Collections.emptyMap;
import static java.util.Map.entry;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static javax.json.JsonValue.EMPTY_JSON_OBJECT;

@Log
@Dependent
public class DiffCommand extends VisitorCommand {
    @Inject
    @Description("Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. " +
            "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, " +
            "this will default to it instead of using classpath deployment.")
    @ConfigProperty(name = "bundlebee.diff.alveolus", defaultValue = "auto")
    private String alveolus;

    @Inject
    @Description("Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. " +
            "Ignored if set to `skip`.")
    @ConfigProperty(name = "bundlebee.diff.manifest", defaultValue = "skip")
    private String manifest;

    @Inject
    @Description("Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
    @ConfigProperty(name = "bundlebee.diff.from", defaultValue = "auto")
    private String from;

    @Inject
    @Description("" +
            "If set only this descriptor is handled, not that you can use a regex if you make the value prefixed with `r/`. " +
            "Note it generally only makes sense with verbose option.")
    @ConfigProperty(name = "bundlebee.diff.descriptor", defaultValue = UNSET)
    private String descriptor;

    @Inject
    @Description("Max concurrent requests to Kubernetes cluster. Value can't be less than `1`.")
    @ConfigProperty(name = "bundlebee.diff.concurrency", defaultValue = "16")
    private int concurrency;

    @Inject
    @Description("" +
            "A list (comma separated values) of JSON-Pointer to ignore in the comparison of client/configured state and actual cluster state. " +
            "Note that these ones will also affect `ignoreEmptyJsonObjectRemovals` since they are dropped before testing the value.")
    @ConfigProperty(name = "bundlebee.diff.ignoredPointers", defaultValue = "" +
            // todo: refine this one, it can contain anything so hard to make it right
            "/metadata/annotations," +
            // read-only or server related properties (k8s)
            "/metadata/creationTimestamp," +
            "/metadata/deletionGracePeriodSeconds," +
            "/metadata/generation," +
            "/metadata/managedFields," +
            "/metadata/ownerReferences," +
            "/metadata/resourceVersion," +
            "/metadata/selfLink," +
            "/metadata/uid," +
            "/status," +
            // bundlebee, we know it does not impact the state
            "/metadata/labels/bundlebee.root.alveolus.name," +
            "/metadata/labels/bundlebee.root.alveolus.version," +
            "/metadata/labels/bundlebee.timestamp")
    private List<String> ignoredPointers;

    @Inject
    @Description("Same as `ignoredPointers` but enables to keep defaults.")
    @ConfigProperty(name = "bundlebee.diff.customIgnoredPointers", defaultValue = UNSET)
    private List<String> customIgnoredPointers;

    @Inject
    @Description("" +
            "A list (comma separated values) of attribute to ignore if in the Kubernetes model but not in local descriptor (it is mainly fields with defaults). " +
            "Note that they behave as relative path (`endsWith`) so you should start them with a `/` in general.")
    @ConfigProperty(name = "bundlebee.diff.ignorableAttributes", defaultValue = "" +
            "/dnsPolicy,/terminationMessagePath,/terminationMessagePolicy,/schedulerName,/terminationGracePeriodSeconds," +
            "/resourceVersion,/uid,/spec/hostPath/type,/spec/persistentVolumeReclaimPolicy," +
            "/spec/claimRef/kind,/spec/claimRef/apiVersion,/spec/volumeName,/spec/volumeMode,/fieldRef/apiVersion")
    private List<String> ignorableAttributes;

    @Inject
    @Description("Same as `ignorableAttributes` but enables to keep defaults.")
    @ConfigProperty(name = "bundlebee.diff.customIgnorableAttributes", defaultValue = UNSET)
    private List<String> customIgnorableAttributes;

    @Inject
    @Description("Should a diff where the `op` is `remove` or `replace` and the associated `value` an empty JSON-Object/JSON-Array be ignored.")
    @ConfigProperty(name = "bundlebee.diff.ignoreEmptyRemovals", defaultValue = "true")
    private boolean ignoreEmptyRemovals;

    @Inject
    @Description("Should JSON(-Diff) be formatted.")
    @ConfigProperty(name = "bundlebee.diff.formatted", defaultValue = "true")
    private boolean formatted;

    @Inject
    @Description("If `true`, the local location of the description will be added to the diff.")
    @ConfigProperty(name = "bundlebee.diff.showLocalSource", defaultValue = "false")
    private boolean showLocalSource;

    @Inject
    @Description("If `true`, the descriptors without any notable diff will be ignored from the report.")
    @ConfigProperty(name = "bundlebee.diff.ignoreNoDiffEntries", defaultValue = "true")
    private boolean ignoreNoDiffEntries;

    @Inject
    @Description("If there are at least this number of differences then the build will fail, disabled if negative.")
    @ConfigProperty(name = "bundlebee.diff.maxDifferences", defaultValue = "-1")
    private int maxDifferences;

    @Inject
    @Description("How to print the diff for each descriptor, " +
            "`AUTO` means use `JSON-Patch` when there is a diff, " +
            "`JSON` when a descriptor is missing and skip the content when it is the same. " +
            "`JSON_PATCH` means always use `JSON-Patch` format. " +
            "`JSON` means always show expected state.")
    @ConfigProperty(name = "bundlebee.diff.diffType", defaultValue = "AUTO")
    private DiffType diffType;

    @Inject
    @Description("How to dump the diff, by default (`LOG`) it will print it but `FILE` will store it in a local file (using `dumpLocation`).")
    @ConfigProperty(name = "bundlebee.diff.outputType", defaultValue = "LOG")
    private OutputType outputType;

    @Inject
    @Description("Diff location when `outputType` is `FILE`.")
    @ConfigProperty(name = "bundlebee.diff.dumpLocation", defaultValue = "target/bundlebee.diff")
    private String dumpLocation;

    @Inject
    @BundleBee
    private Jsonb jsonb;

    @Inject
    @BundleBee
    private JsonProvider json;

    @Inject
    private KubeClient k8s;

    @Inject
    private HttpKubeClient httpK8s;

    private Collection<JsonPointer> pointersToStrip;

    @PostConstruct
    private void init() {
        pointersToStrip = Stream.of(ignoredPointers, customIgnoredPointers)
                .filter(it -> !List.of(UNSET).equals(it))
                .flatMap(Collection::stream)
                .map(json::createPointer)
                .collect(toList());
    }

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String description() {
        return "Diff an alveolus against a running cluster." +
                "// end of short description\n\n" +
                "The logic behind is to visit the configured alveolus and for each of its descriptor, query the cluster state and do a JSON-Diff between both.\n" +
                "To avoid false positives, you will likely want to tune the ignored pointers which enable to drop dynamic data (managed by Kubernetes server).\n\n" +
                "The diff output has two types of diff:\n\n" +
                "* `JSON-Patch`: a JSON-Patch which, once applied on the actual state will bring up the state to the expected one,\n" +
                "* `JSON`: means the Kubernetes server misses an alveolus descriptor and the expected one is fully printed\n" +
                "\n" +
                "The diff line syntax is: `diff --$alveolusName a/$expectedLocalDescriptor b/$remoteDescriptor`.\n";
    }

    @Override
    public CompletionStage<?> execute() {
        return doExecute().thenAccept(data -> {
            if (data.isEmpty()) {
                log.warning(() -> "No data to diff, check your setup");
                return;
            }

            final var writerFactory = formatted ?
                    json.createWriterFactory(Map.of(JsonGenerator.PRETTY_PRINTING, true)) :
                    null;
            final var diffCache = new ConcurrentHashMap<Item, JsonArray>();
            switch (diffType) {
                case JSON:
                    doWrite(() -> {
                        final var diff = json.createObjectBuilder();
                        data.entrySet().stream()
                                .collect(groupingBy(i -> i.getKey().getAlveolus().getName()))
                                .forEach((alveolusName, items) -> diff.add(
                                        alveolusName,
                                        json.createArrayBuilder(items.stream()
                                                .map(it -> doJsonDiff(it, diffCache))
                                                .filter(Objects::nonNull)
                                                .collect(toList()))));
                        final var json = diff.build();
                        return writerFactory != null ? format(json, writerFactory) : json.toString();
                    });
                    break;
                default:
                    doWrite(() -> "Diff:\n" + data.entrySet().stream()
                            .sorted(Comparator.<Map.Entry<Item, ActualState>, String>comparing(i -> i.getKey().getAlveolus().getName())
                                    .thenComparing(i -> i.getKey().getKind())
                                    .thenComparing(i -> i.getKey().getName()))
                            .map(it -> doLogDiff(it.getKey(), it.getValue(), writerFactory, diffCache))
                            .filter(Predicate.not(String::isBlank))
                            .collect(joining("\n")));
            }

            if (maxDifferences > 0) {
                final long diff = data.entrySet().stream()
                        .mapToLong(i -> doDiff(i.getKey(), i.getValue(), diffCache).size())
                        .sum();
                if (diff > maxDifferences) {
                    throw new IllegalStateException("Too much differences: " + diff + " (maxDifferences=" + maxDifferences + ")");
                }
            }
        });
    }

    private void doWrite(final Supplier<String> contentProvider) {
        switch (outputType) {
            case FILE:
                final var out = Path.of(dumpLocation);
                try {
                    if (out.getParent() != null) {
                        Files.createDirectories(out.getParent());
                    }
                    Files.writeString(out, contentProvider.get());
                } catch (final IOException ioe) {
                    throw new IllegalStateException(ioe);
                }
                break;
            default:
                log.info(contentProvider);
        }
    }

    protected CompletionStage<Map<Item, ActualState>> doExecute() {
        return super
                .doExecute(from, manifest, alveolus, descriptor)
                .thenCompose(collected -> {
                    // 1. load all descriptor to get the resource type
                    final var resources = collected.getDescriptors().entrySet().stream()
                            .flatMap(it -> it.getValue().stream().map(d -> entry(it.getKey(), d)))
                            // here we allow ourselves to call get() cause we know it is synchronous like
                            .flatMap(desc -> {
                                try {
                                    return loadItemsForDescriptor(collected, desc);
                                } catch (final InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                } catch (final ExecutionException e) {
                                    throw new IllegalStateException(e.getCause());
                                }
                            })
                            .collect(toList());

                    // 2. call all resources to check it exists and their current model
                    return fetchAll(resources.iterator());
                });
    }

    private JsonValue doJsonDiff(final Map.Entry<Item, ActualState> it, final Map<Item, JsonArray> cache) {
        final var diff = doDiff(it.getKey(), it.getValue(), cache);
        if (diff.isEmpty()) {
            return null;
        }
        return json.createObjectBuilder()
                .add("resource", it.getKey().getSource().getResource())
                .add("uri", it.getKey().getSource().getUri())
                .add("remote", it.getValue().getUrl())
                .add("expected", it.getKey().getExpected())
                .add("actual", it.getValue().getActual())
                .add("diff", json.createObjectBuilder()
                        // not a diff between expected and actual but the sanitized version we did
                        .add("clean", diff))
                .build();
    }

    private String doLogDiff(final Item expected, final ActualState actual,
                             final JsonWriterFactory writerFactory, final Map<Item, JsonArray> cache) {
        final var diff = doDiff(expected, actual, cache);
        if (diff.isEmpty() && ignoreNoDiffEntries) {
            return "";
        }
        final var data = diffType == DiffType.AUTO ?
                (actual.getStatus() == MISSING ? expected.getExpected() : diff) :
                (diffType == DiffType.JSON_PATCH ? diff : expected.getExpected());
        return "diff --" + expected.getAlveolus().getName() + " a/" + expected.getSource().getResource() + " b/" + actual.getUrl() + '\n' +
                (showLocalSource ? "local: " + expected.getSource().getUri() + '\n' : "") +
                "type: " + (actual.getStatus() == MISSING || diffType == JSON_PATCH ? "Missing" : "JSON-Patch") + '\n' +
                (diffType == AUTO && diff.isEmpty() ? "" : ((writerFactory != null ? format(data, writerFactory) : data.toString()).trim()));
    }

    private JsonArray doDiff(final Item expected, final ActualState actual, final Map<Item, JsonArray> cache) {
        return cache.computeIfAbsent(expected, e -> {
            final var preparedExpected = prepare(expected.getExpected());
            final var preparedActual = prepare(actual.getActual());
            return json.createArrayBuilder(doDiff(preparedActual, preparedExpected).stream()
                            .map(JsonValue::asJsonObject)
                            .filter(it -> isImportantDiff(it, p -> json.createPointer(p).getValue(actual.getActual())))
                            .collect(toList()))
                    .build();
        });
    }

    protected boolean isImportantDiff(final JsonObject op, final Function<String, JsonValue> accessor) {
        final var operation = op.getString("op", "");
        if (!"remove".equals(operation) && !"replace".equals(operation)) {
            return true;
        }

        final var path = op.getString("path", "");
        if (ignoreEmptyRemovals) {
            final var value = accessor.apply(path);
            if ((value.getValueType() == JsonValue.ValueType.OBJECT && prepare(value.asJsonObject()).isEmpty()) ||
                    (value.getValueType() == JsonValue.ValueType.ARRAY && (value.asJsonArray().isEmpty() || isIgnorableArray(value.asJsonArray())))) {
                return false;
            }
        }
        return ignorableAttributes.stream().noneMatch(path::endsWith) &&
                ((customIgnorableAttributes.size() == 1 && UNSET.equals(customIgnorableAttributes.get(0))) ||
                        customIgnorableAttributes.stream().noneMatch(path::endsWith));
    }

    private boolean isIgnorableArray(final JsonArray array) { // set by server by default so ignore
        return array.size() == 1 &&
                array.get(0).getValueType() == JsonValue.ValueType.STRING &&
                isIgnorableFinalizer(array);
    }

    private boolean isIgnorableFinalizer(final JsonArray array) {
        final var value = JsonString.class.cast(array.get(0)).getString();
        return "kubernetes.io/pv-protection".equals(value) || "kubernetes.io/pvc-protection".equals(value);
    }

    private JsonArray doDiff(final JsonObject preparedExpected, final JsonObject preparedActual) {
        return json.createDiff(preparedExpected, preparedActual).toJsonArray();
    }

    private String format(final JsonStructure value, final JsonWriterFactory writerFactory) {
        final var pretty = new StringWriter();
        try (final var writer = writerFactory.createWriter(pretty)) {
            writer.write(value);
        }
        return pretty.toString();
    }

    private JsonObject prepare(final JsonObject value) {
        var current = value;
        for (final var ptr : pointersToStrip) {
            try {
                current = ptr.remove(current);
            } catch (final JsonException je) {
                // no-op, not there so just ignore
            }
        }
        return sortObjects(current).asJsonObject();
    }

    private JsonValue sortObjects(final JsonValue value) {
        switch (value.getValueType()) {
            case OBJECT:
                final Map<String, Object> sortedMap = value.asJsonObject().entrySet().stream()
                        // strip null to ensure we don't have to compare JsonValue.NULL and null
                        .filter(Predicate.not(it -> JsonValue.NULL.equals(it.getValue())))
                        .sorted(Map.Entry.comparingByKey())
                        .collect(toMap(Map.Entry::getKey, e -> sortObjects(e.getValue()), (a, b) -> a, LinkedHashMap::new));
                return json.createObjectBuilder(sortedMap).build();
            case ARRAY: // don't sort the array for now, order means something there
                final var array = value.asJsonArray().stream()
                        .map(this::sortObjects)
                        .collect(toList());
                return json.createArrayBuilder(array).build();
            default:
                return value;
        }
    }

    private Stream<Item> loadItemsForDescriptor(final Collected collected, final Map.Entry<String, AlveolusHandler.LoadedDescriptor> desc) throws InterruptedException, ExecutionException {
        return k8s.forDescriptorWithOriginal(
                        "Parsing", desc.getValue().getContent(), desc.getValue().getExtension(),
                        item -> completedFuture(item.getPrepared()))
                .toCompletableFuture().get().stream()
                .map(obj -> {
                    final var metadata = ofNullable(obj.getJsonObject("metadata"))
                            .orElse(EMPTY_JSON_OBJECT);
                    return new Item(
                            obj.getString("kind", "unknown"),
                            metadata.getString("namespace", httpK8s.getNamespace()),
                            metadata.getString("name"),
                            obj, collected.getAlveoli().get(desc.getKey()), desc.getValue());
                });
    }

    private CompletionStage<Map<Item, ActualState>> fetchAll(final Iterator<Item> iterator) {
        if (!iterator.hasNext()) {
            return completedFuture(emptyMap());
        }

        final var cache = new ConcurrentHashMap<JsonObject, ActualState>();
        final var collector = new ConcurrentHashMap<Item, ActualState>();
        final var lock = new ReentrantLock();

        // seed the concurrency
        final var seeds = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < Math.max(concurrency, 1); i++) {
            seeds.add(fetchNext(lock, iterator, collector, cache));
        }

        return allOf(seeds.toArray(new CompletableFuture<?>[0]))
                .thenApply(ok -> collector);
    }

    private CompletableFuture<?> fetchNext(final ReentrantLock lock, final Iterator<Item> iterator,
                                           final Map<Item, ActualState> collector, final Map<JsonObject, ActualState> cache) {
        final Item item;
        lock.lock();
        try {
            if (!iterator.hasNext()) {
                return completedFuture(null);
            }
            item = iterator.next();
        } finally {
            lock.unlock();
        }

        final var cached = cache.get(item.getExpected());
        return cached != null ?
                completedFuture(cached)
                        .thenCompose(ok -> fetchNext(lock, iterator, collector, cache)) :
                k8s.getResource(item.getExpected())
                        .thenCompose(res -> {
                            final var status = res.statusCode() >= 200 && res.statusCode() <= 299 ? EXISTS : MISSING;
                            collector.put(item, new ActualState(
                                    status,
                                    status == EXISTS ? jsonb.fromJson(res.body(), JsonObject.class) : EMPTY_JSON_OBJECT,
                                    res.request().uri().toASCIIString(), res.statusCode(), res.body()));
                            return fetchNext(lock, iterator, collector, cache);
                        })
                        .toCompletableFuture();
    }

    public enum ActualStatus {
        @Description("The resource is missing.")
        MISSING,

        @Description("Just resource exists.")
        EXISTS
    }

    @Data
    public static class ActualState {
        private final ActualStatus status;
        private final JsonObject actual;
        private final String url;
        private final int httpStatus;
        private final String httpBody;
    }

    @Data
    public static class Item {
        private final String kind;
        private final String namespace;
        private final String name;
        private final JsonObject expected;
        private final Manifest.Alveolus alveolus;
        private final AlveolusHandler.LoadedDescriptor source;
    }

    public enum DiffType {
        @Description("Use the most adapted diff type.")
        AUTO,
        @Description("Use JSON-Patch form.")
        JSON_PATCH,
        @Description("Use the JSON output directly, no diff.")
        JSON
    }

    public enum OutputType {
        @Description("Log the output.")
        LOG,
        @Description("Write the output to a file.")
        FILE
    }
}
