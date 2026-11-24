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
package io.yupiik.bundlebee.helm.fn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FixedRegexFunctionsTest {
    @Test
    void indentationPreservesTrailingAndEmptyLines() {
        assertEquals("  a\n\n  b\n\n", new IndentFunc().execute(2, "a\n\nb\n\n"));
        assertEquals("\n  a\n\n  b\n\n", new NindentFunc().execute(2, "a\n\nb\n\n"));
        assertEquals("", new IndentFunc().execute(2, ""));
        assertEquals("\n", new NindentFunc().execute(2, ""));
        assertEquals("  a\r\n", new IndentFunc().execute(2, "a\r\n"));
    }

    @Test
    void initialsKeepAsciiWhitespaceSemantics() {
        assertEquals("ABCD", new InitialsFunc().execute(" \tAlpha\r\nBeta\fCharlie\u000bDelta \t"));
        assertEquals("", new InitialsFunc().execute(" \t\r\n"));
        assertEquals("A", new InitialsFunc().execute("Alpha\u00a0Beta"));
    }

    @Test
    void semanticVersionsSplitOnLiteralDotsAndDropTrailingEmptyParts() {
        assertEquals(Map.of("major", 1, "minor", 2, "patch", 3), new SemverFunc().execute("v1.2.3.."));
        assertEquals(0, new SemverCompareFunc().execute("v1.2.3..", "1.2.3"));
        assertThrows(IllegalArgumentException.class, () -> new SemverFunc().execute("1x2x3"));
        assertThrows(IllegalArgumentException.class, () -> new SemverFunc().execute("1.2."));
        assertThrows(IllegalArgumentException.class, () -> new SemverCompareFunc().execute("1.2.", "1.2.3"));
    }

    @Test
    void dynamicSplitsRetainLimitsAndRegexMetacharacters() {
        final var regexSplit = new RegexSplitFunc();
        assertEquals(List.of("a", "b", ""), regexSplit.execute("\\.", "a.b.", -1));
        assertEquals(List.of("a", "b"), regexSplit.execute("\\.", "a.b.", 0));
        assertEquals(List.of("a", "b."), regexSplit.execute("\\.", "a.b.", 2));
        assertEquals(List.of("a", "b", ""), new MustRegexSplitFunc().execute("\\|", "a|b|", -1));
        assertEquals(List.of("a", "b"), new MustRegexSplitFunc().execute("\\|", "a|b|", 0));
        assertEquals(List.of("a", "b|"), new MustRegexSplitFunc().execute("\\|", "a|b|", 2));
        assertEquals(List.of("a", "b", ""), new SplitListFunc().execute("\\.", "a.b."));
        assertEquals(Map.of("0", "a", "1", "b", "2", ""), new SplitFunc().execute("\\|", "a|b|"));
        assertEquals(Map.of("0", "a", "1", "b."), new SplitnFunc().execute("\\.", 2, "a.b."));
        assertEquals(Map.of("0", "a", "1", "b"), new SplitnFunc().execute("\\.", 0, "a.b."));
        assertEquals(Map.of("0", "a", "1", "b", "2", ""), new SplitnFunc().execute("\\.", -1, "a.b."));
    }

    @Test
    void replacementDollarsAndBackslashesKeepTheirExistingMeaning() {
        assertEquals("$1\\$1\\", new ReplaceFunc().execute(".", "$1\\", ".."));
        assertEquals("[a]$\\", new RegexReplaceAllFunc().execute("(a)", "a", "[$1]\\$\\\\"));
        assertEquals("[a]$\\", new MustRegexReplaceAllFunc().execute("(a)", "a", "[$1]\\$\\\\"));
    }

    @Test
    void dynamicMatchesStillRequireTheWholeInput() {
        assertTrue((Boolean) new RegexMatchFunc().execute("a+", "aaa"));
        assertFalse((Boolean) new RegexMatchFunc().execute("a+", "baaa"));
        assertTrue((Boolean) new MustRegexMatchFunc().execute("b+", "bbb"));
        assertFalse((Boolean) new MustRegexMatchFunc().execute("b+", "bbbc"));
    }
}
