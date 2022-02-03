/*
 * Copyright (c) 2021, 2022 - Yupiik SAS - https://www.yupiik.com
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
package io.yupiik.bundlebee.core.json;

import io.yupiik.bundlebee.core.qualifier.BundleBee;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.json.JsonBuilderFactory;
import javax.json.bind.Jsonb;
import javax.json.bind.JsonbBuilder;
import javax.json.bind.JsonbConfig;
import javax.json.bind.config.PropertyOrderStrategy;
import javax.json.spi.JsonProvider;
import java.util.Map;

@ApplicationScoped
public class JsonProducer {
    @Produces
    @BundleBee
    @ApplicationScoped
    public Jsonb jsonb() {
        return JsonbBuilder.create(new JsonbConfig()
                .setProperty("johnzon.skip-cdi", true)
                .withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
    }

    public void release(@Disposes @BundleBee final Jsonb jsonb) {
        try {
            jsonb.close();
        } catch (final Exception e) {
            // no-op: not important since we don't use cdi there
        }
    }

    @Produces
    @BundleBee
    @ApplicationScoped
    public JsonProvider jsonProvider() {
        return JsonProvider.provider();
    }

    @Produces
    @BundleBee
    @ApplicationScoped
    public JsonBuilderFactory jsonBuilderFactory(@BundleBee final JsonProvider provider) {
        return provider.createBuilderFactory(Map.of());
    }
}
