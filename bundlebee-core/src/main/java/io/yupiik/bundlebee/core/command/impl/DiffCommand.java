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

import static io.yupiik.bundlebee.core.command.impl.DiffCommand.ActualStatus.EXISTS;
import static io.yupiik.bundlebee.core.command.impl.DiffCommand.ActualStatus.MISSING;
import static io.yupiik.bundlebee.core.command.impl.DiffCommand.DiffType.AUTO;
import static io.yupiik.bundlebee.core.command.impl.DiffCommand.DiffType.JSON_PATCH;
import static java.util.Collections.emptyMap;
import static java.util.Map.entry;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import io.yupiik.bundlebee.core.configuration.Description;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.kube.KubeClient;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.lang.spi.NamespaceProvider;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.cli.Command;
import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonOthers;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.json.diff.GenericJsonDiff;
import io.yupiik.fusion.json.patch.JsonPatchOperation;
import io.yupiik.fusion.json.pointer.GenericJsonPointer;
import java.io.IOException;
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
import java.util.logging.Logger;
import java.util.stream.Stream;

@DefaultScoped
@Command(
        name = "diff",
        description = "Diff an alveolus against a running cluster.\n" + "// end of short description\n"
                + "The logic behind is to visit the configured alveolus and for each of its descriptor, query the cluster state and do a JSON-Diff between both.\n"
                + "To avoid false positives, you will likely want to tune the ignored pointers which enable to drop dynamic data (managed by Kubernetes server).\n\n"
                + "The diff output has two types of diff:\n\n"
                + "* `JSON-Patch`: a JSON-Patch which, once applied on the actual state will bring up the state to the expected one,\n"
                + "* `JSON`: means the Kubernetes server misses an alveolus descriptor and the expected one is fully printed\n"
                + "\n"
                + "The diff line syntax is: `diff --$alveolusName a/$expectedLocalDescriptor b/$remoteDescriptor`.\n")
public class DiffCommand extends VisitorCommand {
    private final Logger log = Logger.getLogger(DiffCommand.class.getName());

    public DiffCommand(
            final Options options,
            JsonMapper jsonb,
            KubeClient k8s,
            NamespaceProvider httpK8s,
            io.yupiik.bundlebee.core.service.AlveolusHandler visitor,
            io.yupiik.bundlebee.core.service.ArchiveReader archives) {
        super(visitor, archives);
        this.alveolus = options.alveolus();
        this.manifest = options.manifest();
        this.from = options.from();
        this.descriptor = options.descriptor();
        this.concurrency = options.concurrency();
        this.ignoredPointers = options.ignoredPointers();
        this.customIgnoredPointers = options.customIgnoredPointers();
        this.ignorableAttributes = options.ignorableAttributes();
        this.customIgnorableAttributes = options.customIgnorableAttributes();
        this.ignoreEmptyRemovals = options.ignoreEmptyRemovals();
        this.formatted = options.formatted();
        this.showLocalSource = options.showLocalSource();
        this.ignoreNoDiffEntries = options.ignoreNoDiffEntries();
        this.maxDifferences = options.maxDifferences();
        this.diffType = options.diffType();
        this.outputType = options.outputType();
        this.dumpLocation = options.dumpLocation();
        this.jsonb = jsonb;
        this.k8s = k8s;
        this.httpK8s = httpK8s;
        this.pointersToStrip = Stream.of(ignoredPointers, customIgnoredPointers)
                .filter(it -> !List.of(UNSET).equals(it))
                .flatMap(Collection::stream)
                .map(GenericJsonPointer::new)
                .collect(toList());
    }

    private String alveolus;
    private String manifest;
    private String from;
    private String descriptor;
    private int concurrency;
    private List<String> ignoredPointers;
    private List<String> customIgnoredPointers;
    private List<String> ignorableAttributes;
    private List<String> customIgnorableAttributes;
    private boolean ignoreEmptyRemovals;
    private boolean formatted;
    private boolean showLocalSource;
    private boolean ignoreNoDiffEntries;
    private int maxDifferences;
    private DiffType diffType;
    private OutputType outputType;
    private String dumpLocation;
    private JsonMapper jsonb;
    private KubeClient k8s;
    private final NamespaceProvider httpK8s;
    private Collection<GenericJsonPointer> pointersToStrip;

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String description() {
        return "Diff an alveolus against a running cluster.\n" + "// end of short description\n"
                + "The logic behind is to visit the configured alveolus and for each of its descriptor, query the cluster state and do a JSON-Diff between both.\n"
                + "To avoid false positives, you will likely want to tune the ignored pointers which enable to drop dynamic data (managed by Kubernetes server).\n\n"
                + "The diff output has two types of diff:\n\n"
                + "* `JSON-Patch`: a JSON-Patch which, once applied on the actual state will bring up the state to the expected one,\n"
                + "* `JSON`: means the Kubernetes server misses an alveolus descriptor and the expected one is fully printed\n"
                + "\n"
                + "The diff line syntax is: `diff --$alveolusName a/$expectedLocalDescriptor b/$remoteDescriptor`.\n";
    }

    @Override
    public CompletionStage<?> execute() {
        return doExecute().thenAccept(data -> {
            if (data.isEmpty()) {
                log.warning(() -> "No data to diff, check your setup");
                return;
            }
            final var writerFactory = formatted ? new io.yupiik.fusion.json.pretty.PrettyJsonMapper(jsonb) : null;
            final var diffCache = new ConcurrentHashMap<Item, List<JsonPatchOperation>>();
            switch (diffType) {
                case JSON:
                    doWrite(() -> {
                        final var diff = new LinkedHashMap<String, List<ResourceDiff>>();
                        data.entrySet().stream()
                                .collect(
                                        groupingBy(i -> i.getKey().getAlveolus().name()))
                                .forEach((alveolusName, items) -> diff.put(
                                        alveolusName,
                                        items.stream()
                                                .map(it -> doJsonDiff(it, diffCache))
                                                .filter(Objects::nonNull)
                                                .collect(toList())));
                        final var json = diff;
                        return writerFactory != null ? format(json, writerFactory) : jsonb.toString(json);
                    });
                    break;
                default:
                    doWrite(() -> "Diff:\n"
                            + data.entrySet().stream()
                                    .sorted(Comparator.<Map.Entry<Item, ActualState>, String>comparing(i ->
                                                    i.getKey().getAlveolus().name())
                                            .thenComparing(i -> i.getKey().getKind())
                                            .thenComparing(i -> i.getKey().getName()))
                                    .map(it -> doLogDiff(it.getKey(), it.getValue(), writerFactory, diffCache))
                                    .filter(Predicate.not(String::isBlank))
                                    .collect(joining("\n")));
            }
            if (maxDifferences > 0) {
                final long diff = data.entrySet().stream()
                        .mapToLong(
                                i -> doDiff(i.getKey(), i.getValue(), diffCache).size())
                        .sum();
                if (diff > maxDifferences) {
                    throw new IllegalStateException(
                            "Too much differences: " + diff + " (maxDifferences=" + maxDifferences + ")");
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
        return super.doExecute(from, manifest, alveolus, descriptor, null).thenCompose(collected -> {
            // 1. load all descriptor to get the resource type
            final var resources = // here we allow ourselves to call get() cause we know it is synchronous like
                    collected.getDescriptors().entrySet().stream()
                            .flatMap(it -> it.getValue().stream().map(d -> entry(it.getKey(), d)))
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

    private ResourceDiff doJsonDiff(
            final Map.Entry<Item, ActualState> it, final Map<Item, List<JsonPatchOperation>> cache) {
        final var diff = doDiff(it.getKey(), it.getValue(), cache);
        if (diff.isEmpty()) {
            return null;
        }
        return new ResourceDiff(
                it.getKey().getSource().getResource(),
                it.getKey().getSource().getUri(),
                it.getValue().getUrl(),
                it.getKey().getExpected(),
                it.getValue().getActual(),
                new CleanDiff(Map.of("clean", diff)));
    }

    private String doLogDiff(
            final Item expected,
            final ActualState actual,
            final JsonMapper writerFactory,
            final Map<Item, List<JsonPatchOperation>> cache) {
        final var diff = doDiff(expected, actual, cache);
        if (diff.isEmpty() && ignoreNoDiffEntries) {
            return "";
        }
        final var data = diffType == DiffType.AUTO
                ? (actual.getStatus() == MISSING ? expected.getExpected() : diff)
                : (diffType == DiffType.JSON_PATCH ? diff : expected.getExpected());
        return "diff --" + expected.getAlveolus().name() + " a/"
                + expected.getSource().getResource() + " b/" + actual.getUrl() + '\n'
                + (showLocalSource ? "local: " + expected.getSource().getUri() + '\n' : "") + "type: "
                + (actual.getStatus() == MISSING || diffType == JSON_PATCH ? "Missing" : "JSON-Patch") + '\n'
                + (diffType == AUTO && diff.isEmpty()
                        ? ""
                        : ((writerFactory != null ? format(data, writerFactory) : jsonb.toString(data)).trim()));
    }

    private List<JsonPatchOperation> doDiff(
            final Item expected, final ActualState actual, final Map<Item, List<JsonPatchOperation>> cache) {
        return cache.computeIfAbsent(expected, e -> {
            final var preparedExpected = prepare(expected.getExpected());
            final var preparedActual = prepare(actual.getActual());
            return doDiff(preparedActual, preparedExpected).stream()
                    .filter(it -> isImportantDiff(it, p -> new GenericJsonPointer(p).apply(actual.getActual())))
                    .collect(toList());
        });
    }

    protected boolean isImportantDiff(final JsonPatchOperation op, final Function<String, Object> accessor) {
        final var operation = op.op();
        if (operation != JsonPatchOperation.Operation.remove && operation != JsonPatchOperation.Operation.replace) {
            return true;
        }
        final var path = op.path();
        if (ignoreEmptyRemovals) {
            final var value = accessor.apply(path);
            if ((value instanceof Map<?, ?> map
                            && prepare((Map<String, Object>) map).isEmpty())
                    || (value instanceof List<?> array && (array.isEmpty() || isIgnorableArray(array)))) {
                return false;
            }
        }
        return ignorableAttributes.stream().noneMatch(path::endsWith)
                && ((customIgnorableAttributes.size() == 1 && UNSET.equals(customIgnorableAttributes.get(0)))
                        || customIgnorableAttributes.stream().noneMatch(path::endsWith));
    }

    private boolean isIgnorableArray(final List<?> array) {
        // set by server by default so ignore
        return array.size() == 1 && array.get(0) instanceof String && isIgnorableFinalizer(array);
    }

    private boolean isIgnorableFinalizer(final List<?> array) {
        final var value = String.class.cast(array.get(0));
        return "kubernetes.io/pv-protection".equals(value) || "kubernetes.io/pvc-protection".equals(value);
    }

    private List<JsonPatchOperation> doDiff(
            final Map<String, Object> preparedExpected, final Map<String, Object> preparedActual) {
        return new GenericJsonDiff(preparedExpected, preparedActual).toPatch();
    }

    private String format(final Object value, final JsonMapper writerFactory) {
        return writerFactory.toString(value);
    }

    private Map<String, Object> prepare(final Map<String, Object> value) {
        var current = value;
        for (final var ptr : pointersToStrip) {
            try {
                current = (Map<String, Object>) ptr.remove(current);
            } catch (final IllegalArgumentException je) {
            }
            // no-op, not there so just ignore
        }
        return (Map<String, Object>) sortObjects(current);
    }

    private Object sortObjects(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map)
                    .entrySet().stream()
                            .filter(it -> it.getValue() != null)
                            .sorted(Map.Entry.comparingByKey())
                            .collect(toMap(
                                    Map.Entry::getKey,
                                    e -> sortObjects(e.getValue()),
                                    (a, b) -> a,
                                    LinkedHashMap::new));
        }
        if (value instanceof List<?> array) {
            // Array ordering is significant.
            return array.stream().map(this::sortObjects).collect(toList());
        }
        return value;
    }

    private Stream<Item> loadItemsForDescriptor(
            final Collected collected, final Map.Entry<String, AlveolusHandler.LoadedDescriptor> desc)
            throws InterruptedException, ExecutionException {
        return k8s
                .forDescriptorWithOriginal(
                        "Parsing",
                        desc.getValue().getContent(),
                        desc.getValue().getExtension(),
                        item -> completedFuture(item.getPrepared()))
                .toCompletableFuture()
                .get()
                .stream()
                .map(obj -> {
                    final var metadata = obj.get("metadata") instanceof Map<?, ?> map ? map : Map.of();
                    return new Item(
                            obj.get("kind") instanceof String kind ? kind : "unknown",
                            metadata.get("namespace") instanceof String namespace ? namespace : httpK8s.namespace(),
                            (String) metadata.get("name"),
                            obj,
                            collected.getAlveoli().get(desc.getKey()),
                            desc.getValue());
                });
    }

    private CompletionStage<Map<Item, ActualState>> fetchAll(final Iterator<Item> iterator) {
        if (!iterator.hasNext()) {
            return completedFuture(emptyMap());
        }
        final var cache = new ConcurrentHashMap<Map<String, Object>, ActualState>();
        final var collector = new ConcurrentHashMap<Item, ActualState>();
        final var lock = new ReentrantLock();
        // seed the concurrency
        final var seeds = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < Math.max(concurrency, 1); i++) {
            seeds.add(fetchNext(lock, iterator, collector, cache));
        }
        return allOf(seeds.toArray(new CompletableFuture<?>[0])).thenApply(ok -> collector);
    }

    private CompletableFuture<?> fetchNext(
            final ReentrantLock lock,
            final Iterator<Item> iterator,
            final Map<Item, ActualState> collector,
            final Map<Map<String, Object>, ActualState> cache) {
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
        return cached != null
                ? completedFuture(cached).thenCompose(ok -> fetchNext(lock, iterator, collector, cache))
                : k8s.getResource(item.getExpected())
                        .thenCompose(res -> {
                            final var status = res.statusCode() >= 200 && res.statusCode() <= 299 ? EXISTS : MISSING;
                            collector.put(
                                    item,
                                    new ActualState(
                                            status,
                                            status == EXISTS ? jsonb.fromString(Map.class, res.body()) : Map.of(),
                                            res.request().uri().toASCIIString(),
                                            res.statusCode(),
                                            res.body()));
                            return fetchNext(lock, iterator, collector, cache);
                        })
                        .toCompletableFuture();
    }

    @JsonModel
    public record ResourceDiff(
            String resource,
            String uri,
            String remote,
            Map<String, Object> expected,
            Map<String, Object> actual,
            CleanDiff diff) {}

    // The Fusion processor cannot reference its built-in JsonPatchOperation codec in generated fields.
    // Keep the operations themselves typed and let the mapper dispatch their built-in codec at runtime.
    @JsonModel
    public record CleanDiff(@JsonOthers Map<String, Object> patches) {}

    public enum ActualStatus {
        @Description("The resource is missing.")
        MISSING,
        @Description("Just resource exists.")
        EXISTS;
    }

    public static class ActualState {
        private final ActualStatus status;
        private final Map<String, Object> actual;
        private final String url;
        private final int httpStatus;
        private final String httpBody;

        public ActualState(
                final ActualStatus status,
                final Map<String, Object> actual,
                final String url,
                final int httpStatus,
                final String httpBody) {
            this.status = status;
            this.actual = actual;
            this.url = url;
            this.httpStatus = httpStatus;
            this.httpBody = httpBody;
        }

        public ActualStatus getStatus() {
            return this.status;
        }

        public Map<String, Object> getActual() {
            return this.actual;
        }

        public String getUrl() {
            return this.url;
        }

        public int getHttpStatus() {
            return this.httpStatus;
        }

        public String getHttpBody() {
            return this.httpBody;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof ActualState other)) {
                return false;
            }
            return other.canEqual(this)
                    && this.getHttpStatus() == other.getHttpStatus()
                    && Objects.equals(this.getStatus(), other.getStatus())
                    && Objects.equals(this.getActual(), other.getActual())
                    && Objects.equals(this.getUrl(), other.getUrl())
                    && Objects.equals(this.getHttpBody(), other.getHttpBody());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof DiffCommand.ActualState;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    this.getHttpStatus(), this.getStatus(), this.getActual(), this.getUrl(), this.getHttpBody());
        }

        @Override
        public String toString() {
            return "DiffCommand.ActualState(status=" + this.getStatus() + ", actual=" + this.getActual() + ", url="
                    + this.getUrl() + ", httpStatus=" + this.getHttpStatus() + ", httpBody=" + this.getHttpBody() + ")";
        }
    }

    public static class Item {
        private final String kind;
        private final String namespace;
        private final String name;
        private final Map<String, Object> expected;
        private final Manifest.Alveolus alveolus;
        private final AlveolusHandler.LoadedDescriptor source;

        public Item(
                final String kind,
                final String namespace,
                final String name,
                final Map<String, Object> expected,
                final Manifest.Alveolus alveolus,
                final AlveolusHandler.LoadedDescriptor source) {
            this.kind = kind;
            this.namespace = namespace;
            this.name = name;
            this.expected = expected;
            this.alveolus = alveolus;
            this.source = source;
        }

        public String getKind() {
            return this.kind;
        }

        public String getNamespace() {
            return this.namespace;
        }

        public String getName() {
            return this.name;
        }

        public Map<String, Object> getExpected() {
            return this.expected;
        }

        public Manifest.Alveolus getAlveolus() {
            return this.alveolus;
        }

        public AlveolusHandler.LoadedDescriptor getSource() {
            return this.source;
        }

        @Override
        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Item other)) {
                return false;
            }
            return other.canEqual(this)
                    && Objects.equals(this.getKind(), other.getKind())
                    && Objects.equals(this.getNamespace(), other.getNamespace())
                    && Objects.equals(this.getName(), other.getName())
                    && Objects.equals(this.getExpected(), other.getExpected())
                    && Objects.equals(this.getAlveolus(), other.getAlveolus())
                    && Objects.equals(this.getSource(), other.getSource());
        }

        protected boolean canEqual(final Object other) {
            return other instanceof DiffCommand.Item;
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    this.getKind(),
                    this.getNamespace(),
                    this.getName(),
                    this.getExpected(),
                    this.getAlveolus(),
                    this.getSource());
        }

        @Override
        public String toString() {
            return "DiffCommand.Item(kind=" + this.getKind() + ", namespace=" + this.getNamespace() + ", name="
                    + this.getName() + ", expected=" + this.getExpected() + ", alveolus=" + this.getAlveolus()
                    + ", source=" + this.getSource() + ")";
        }
    }

    public enum DiffType {
        @Description("Use the most adapted diff type.")
        AUTO,
        @Description("Use JSON-Patch form.")
        JSON_PATCH,
        @Description("Use the JSON output directly, no diff.")
        JSON;
    }

    public enum OutputType {
        @Description("Log the output.")
        LOG,
        @Description("Write the output to a file.")
        FILE;
    }

    @RootConfiguration("-")
    public record Options(
            @Property(
                    value = "bundlebee.diff.alveolus",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Alveolus name to inspect. When set to `auto`, it will look for all manifests found in the classpath. "
                                    + "If you set manifest option, alveolus is set to `auto` and there is a single alveolus in it, "
                                    + "this will default to it instead of using classpath deployment.")
            String alveolus,

            @Property(
                    value = "bundlebee.diff.manifest",
                    defaultValue = "\"skip\"",
                    documentation =
                            "Manifest to load to start to find the alveolus. This optional setting mainly enables to use dependencies easily. "
                                    + "Ignored if set to `skip`.")
            String manifest,

            @Property(
                    value = "bundlebee.diff.from",
                    defaultValue = "\"auto\"",
                    documentation =
                            "Root dependency to download to get the manifest. If set to `auto` it is assumed to be present in current classpath.")
            String from,

            @Property(
                    value = "bundlebee.diff.descriptor",
                    defaultValue = "\"" + UNSET + "\"",
                    documentation =
                            "If set only this descriptor is handled, not that you can use a regex if you make the value prefixed with `r/`. "
                                    + "Note it generally only makes sense with verbose option.")
            String descriptor,

            @Property(
                    value = "bundlebee.diff.concurrency",
                    defaultValue = "16",
                    documentation = "Max concurrent requests to Kubernetes cluster. Value can't be less than `1`.")
            int concurrency,

            @Property(
                    value = "bundlebee.diff.ignoredPointers",
                    defaultValue =
                            "java.util.List.of(\"/metadata/annotations\", \"/metadata/creationTimestamp\", \"/metadata/deletionGracePeriodSeconds\", \"/metadata/generation\", \"/metadata/managedFields\", \"/metadata/ownerReferences\", \"/metadata/resourceVersion\", \"/metadata/selfLink\", \"/metadata/uid\", \"/status\", \"/metadata/labels/bundlebee.root.alveolus.name\", \"/metadata/labels/bundlebee.root.alveolus.version\", \"/metadata/labels/bundlebee.timestamp\")",
                    documentation =
                            "A list (comma separated values) of JSON-Pointer to ignore in the comparison of client/configured state and actual cluster state. "
                                    + "Note that these ones will also affect `ignoreEmptyJsonObjectRemovals` since they are dropped before testing the value.")
            List<String> ignoredPointers,

            @Property(
                    value = "bundlebee.diff.customIgnoredPointers",
                    defaultValue = "java.util.List.of(\"" + UNSET + "\")",
                    documentation = "Same as `ignoredPointers` but enables to keep defaults.")
            List<String> customIgnoredPointers,

            @Property(
                    value = "bundlebee.diff.ignorableAttributes",
                    defaultValue =
                            "java.util.List.of(\"/dnsPolicy\", \"/terminationMessagePath\", \"/terminationMessagePolicy\", \"/schedulerName\", \"/terminationGracePeriodSeconds\", \"/resourceVersion\", \"/uid\", \"/spec/hostPath/type\", \"/spec/persistentVolumeReclaimPolicy\", \"/spec/claimRef/kind\", \"/spec/claimRef/apiVersion\", \"/spec/volumeName\", \"/spec/volumeMode\", \"/fieldRef/apiVersion\")",
                    documentation =
                            "A list (comma separated values) of attribute to ignore if in the Kubernetes model but not in local descriptor (it is mainly fields with defaults). "
                                    + "Note that they behave as relative path (`endsWith`) so you should start them with a `/` in general.")
            List<String> ignorableAttributes,

            @Property(
                    value = "bundlebee.diff.customIgnorableAttributes",
                    defaultValue = "java.util.List.of(\"" + UNSET + "\")",
                    documentation = "Same as `ignorableAttributes` but enables to keep defaults.")
            List<String> customIgnorableAttributes,

            @Property(
                    value = "bundlebee.diff.ignoreEmptyRemovals",
                    defaultValue = "true",
                    documentation =
                            "Should a diff where the `op` is `remove` or `replace` and the associated `value` an empty JSON-Object/JSON-Array be ignored.")
            boolean ignoreEmptyRemovals,

            @Property(
                    value = "bundlebee.diff.formatted",
                    defaultValue = "true",
                    documentation = "Should JSON(-Diff) be formatted.")
            boolean formatted,

            @Property(
                    value = "bundlebee.diff.showLocalSource",
                    defaultValue = "false",
                    documentation = "If `true`, the local location of the description will be added to the diff.")
            boolean showLocalSource,

            @Property(
                    value = "bundlebee.diff.ignoreNoDiffEntries",
                    defaultValue = "true",
                    documentation =
                            "If `true`, the descriptors without any notable diff will be ignored from the report.")
            boolean ignoreNoDiffEntries,

            @Property(
                    value = "bundlebee.diff.maxDifferences",
                    defaultValue = "-1",
                    documentation =
                            "If there are at least this number of differences then the build will fail, disabled if negative.")
            int maxDifferences,

            @Property(
                    value = "bundlebee.diff.diffType",
                    defaultValue = "io.yupiik.bundlebee.core.command.impl.DiffCommand.DiffType.AUTO",
                    documentation = "How to print the diff for each descriptor, "
                            + "`AUTO` means use `JSON-Patch` when there is a diff, "
                            + "`JSON` when a descriptor is missing and skip the content when it is the same. "
                            + "`JSON_PATCH` means always use `JSON-Patch` format. "
                            + "`JSON` means always show expected state.")
            DiffType diffType,

            @Property(
                    value = "bundlebee.diff.outputType",
                    defaultValue = "io.yupiik.bundlebee.core.command.impl.DiffCommand.OutputType.LOG",
                    documentation =
                            "How to dump the diff, by default (`LOG`) it will print it but `FILE` will store it in a local file (using `dumpLocation`).")
            OutputType outputType,

            @Property(
                    value = "bundlebee.diff.dumpLocation",
                    defaultValue = "\"target/bundlebee.diff\"",
                    documentation = "Diff location when `outputType` is `FILE`.")
            String dumpLocation) {}
}
