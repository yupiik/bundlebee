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
package io.yupiik.bundlebee.maven.mojo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class BaseMojoTest {
    @Test
    void invocationConfigurationIsPassedToSubclass() {
        final var mojo = new ConfigurationMojo();
        mojo.execute();
        assertTrue(mojo.called);
    }

    @Test
    void restoresLoggingOnSuccessAndFailure() {
        final var logger = Logger.getLogger("io.yupiik.bundlebee");
        final var handlers = logger.getHandlers();
        final var level = logger.getLevel();
        final var parents = logger.getUseParentHandlers();
        new TestMojo(false).execute();
        assertArrayEquals(handlers, logger.getHandlers());
        assertEquals(level, logger.getLevel());
        assertEquals(parents, logger.getUseParentHandlers());
        assertThrows(IllegalStateException.class, () -> new TestMojo(true).execute());
        assertArrayEquals(handlers, logger.getHandlers());
        assertEquals(level, logger.getLevel());
        assertEquals(parents, logger.getUseParentHandlers());
    }

    @Test
    void skippedInvocationDoesNotChangeLogging() throws Exception {
        final var logger = Logger.getLogger("io.yupiik.bundlebee");
        final var handlers = logger.getHandlers();
        final var mojo = new TestMojo(false);
        final var skip = BaseMojo.class.getDeclaredField("skip");
        skip.setAccessible(true);
        skip.set(mojo, true);
        mojo.execute();
        assertFalse(mojo.called);
        assertArrayEquals(handlers, logger.getHandlers());
    }

    private static final class ConfigurationMojo extends BaseMojo {
        private boolean called;

        @Override
        protected void doExecute(final ConfigurationSource configuration) {
            called = true;
            assertNull(configuration.get("missing"));
        }
    }

    private static final class TestMojo extends BaseMojo {
        private final boolean fail;
        private boolean called;

        private TestMojo(final boolean fail) {
            this.fail = fail;
        }

        @Override
        protected void doExecute(final ConfigurationSource configuration) {
            called = true;
            if (fail) {
                throw new IllegalStateException("expected");
            }
        }
    }
}
