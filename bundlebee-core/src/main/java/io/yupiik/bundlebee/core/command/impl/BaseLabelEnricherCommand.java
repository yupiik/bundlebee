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

import io.yupiik.bundlebee.core.command.CompletingExecutable;
import io.yupiik.bundlebee.core.descriptor.Manifest;
import io.yupiik.bundlebee.core.service.AlveolusHandler;
import io.yupiik.bundlebee.core.service.LabelSanitizerService;
import io.yupiik.bundlebee.core.service.VersioningService;
import lombok.extern.java.Log;

import javax.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;

@Log
public abstract class BaseLabelEnricherCommand implements CompletingExecutable {
    @Inject
    private LabelSanitizerService labelSanitizerService;

    @Inject
    private VersioningService versioningService;

    @Inject
    protected AlveolusHandler visitor;

    @Override
    public Stream<String> complete(final Map<String, String> options, final String optionName) {
        switch (optionName) {
            case "injectBundleBeeMetadata":
            case "injectTimestamp":
                return Stream.of("false", "true");
            case "alveolus":
                return visitor.findCompletionAlveoli(options);
            default:
                return Stream.empty();
        }
    }

    protected Map<String, String> createLabels(final Manifest.Alveolus alveolus,
                                               final boolean injectTimestamp,
                                               final boolean injectBundleBeeMetadata) {
        return Stream.of(
                        injectTimestamp ?
                                Map.of("bundlebee.timestamp", Long.toString(Instant.now().toEpochMilli())) :
                                Map.<String, String>of(),
                        injectBundleBeeMetadata ?
                                Map.of(
                                        "bundlebee.root.alveolus.version", labelSanitizerService.sanitize(findVersion(alveolus)),
                                        "bundlebee.root.alveolus.name", labelSanitizerService.sanitize(alveolus.getName())) :
                                Map.<String, String>of())
                .flatMap(m -> m.entrySet().stream())
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }


    private String findVersion(final Manifest.Alveolus alveolus) {
        try {
            return versioningService.findVersion(alveolus);
        } catch (final RuntimeException re) {
            log.log(Level.FINEST, re.getMessage(), re);
            return "unknown";
        }
    }

}
