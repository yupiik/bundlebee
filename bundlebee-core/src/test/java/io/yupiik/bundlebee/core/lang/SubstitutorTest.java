package io.yupiik.bundlebee.core.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubstitutorTest {
    @Test
    void replace() {
        assertEquals("foo replaced dummy", new Substitutor(k -> "key".equals(k) ? "replaced" : null).replace("foo {{key}} dummy"));
    }

    @Test
    void fallback() {
        assertEquals("foo or dummy", new Substitutor(k -> null).replace("foo {{key:-or}} dummy"));
    }
}
