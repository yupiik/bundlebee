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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HelmFunctionsTest {
    @Test
    void upper() {
        assertEquals("HELLO", new UpperFunc().execute("hello"));
        assertEquals("HELLO WORLD", new UpperFunc().execute("Hello World"));
        assertEquals("", new UpperFunc().execute((Object) null));
    }

    @Test
    void untitle() {
        assertEquals("hello world", new UntitleFunc().execute("Hello World"));
        assertEquals("hello", new UntitleFunc().execute("Hello"));
        assertEquals("", new UntitleFunc().execute((Object) null));
    }

    @Test
    void abbrev() {
        assertEquals("he...", new AbbrevFunc().execute(5, "hello world"));
        assertEquals("hello", new AbbrevFunc().execute(20, "hello"));
        assertEquals("hello world", new AbbrevFunc().execute(11, "hello world"));
    }

    @Test
    void abbrevBoth() {
        // left=5, maxlen=10, s.length()=14 > 10, right=10-5-3=2
        // result = first 5 chars + "..." + last 2 chars
        final var result = new AbbrevBothFunc().execute(5, 10, "1234 5678 9123");
        assertEquals("1234 ...23", result);
    }

    @Test
    void abbrevBothShortString() {
        assertEquals("hello", new AbbrevBothFunc().execute(3, 10, "hello"));
    }

    @Test
    void snakecase() {
        assertEquals("first_name", new SnakecaseFunc().execute("FirstName"));
        assertEquals("h_t_t_p_server", new SnakecaseFunc().execute("HTTPServer"));
        assertEquals("hello", new SnakecaseFunc().execute("hello"));
    }

    @Test
    void camelcase() {
        assertEquals("HttpServer", new CamelcaseFunc().execute("http_server"));
        assertEquals("Hello", new CamelcaseFunc().execute("hello"));
        assertEquals("MyVariableName", new CamelcaseFunc().execute("my_variable_name"));
    }

    @Test
    void kebabcase() {
        assertEquals("first-name", new KebabcaseFunc().execute("FirstName"));
        assertEquals("hello-world", new KebabcaseFunc().execute("helloWorld"));
        assertEquals("hello", new KebabcaseFunc().execute("hello"));
    }

    @Test
    void swapcase() {
        assertEquals("hELLO wORLD", new SwapcaseFunc().execute("Hello World"));
        assertEquals("123ABC", new SwapcaseFunc().execute("123abc"));
        assertEquals("", new SwapcaseFunc().execute((Object) null));
    }

    @Test
    void shuffle() {
        final var result = new ShuffleFunc().execute("abcdef").toString();
        assertEquals(6, result.length());
        final var sorted1 = "abcdef".chars().sorted().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        final var sorted2 = result.chars().sorted().collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        assertEquals(sorted1, sorted2);
    }

    @Test
    void shuffleEmpty() {
        assertEquals("", new ShuffleFunc().execute((Object) null));
        assertEquals("", new ShuffleFunc().execute(""));
    }

    @Test
    void wrapWith() {
        final var result = new WrapWithFunc().execute(5, "\t", "Hello World").toString();
        assertTrue(result.contains("\t"));
    }

    @Test
    void wrapWithEmpty() {
        assertEquals("", new WrapWithFunc().execute(5, "\t", (Object) null));
    }

    @Test
    void randAlphaNum() {
        final var result = new RandAlphaNumFunc().execute(10).toString();
        assertEquals(10, result.length());
        assertTrue(result.matches("[a-zA-Z0-9]+"));
    }

    @Test
    void randAlphaNumZero() {
        assertEquals("", new RandAlphaNumFunc().execute(0));
    }

    @Test
    void randAlpha() {
        final var result = new RandAlphaFunc().execute(10).toString();
        assertEquals(10, result.length());
        assertTrue(result.matches("[a-zA-Z]+"));
    }

    @Test
    void randAlphaZero() {
        assertEquals("", new RandAlphaFunc().execute(0));
    }

    @Test
    void randNumeric() {
        final var result = new RandNumericFunc().execute(5).toString();
        assertEquals(5, result.length());
        assertTrue(result.matches("[0-9]+"));
    }

    @Test
    void randNumericZero() {
        assertEquals("", new RandNumericFunc().execute(0));
    }

    @Test
    void randAscii() {
        final var result = new RandAsciiFunc().execute(10).toString();
        assertEquals(10, result.length());
        for (final var c : result.toCharArray()) {
            assertTrue(c >= 32 && c <= 126, "non-printable char: " + (int) c);
        }
    }

    @Test
    void randAsciiZero() {
        assertEquals("", new RandAsciiFunc().execute(0));
    }

    @Test
    void mustRegexMatchTrue() {
        assertEquals(true, new MustRegexMatchFunc().execute("^[a-z]+$", "hello"));
    }

    @Test
    void mustRegexMatchFalse() {
        assertEquals(false, new MustRegexMatchFunc().execute("^[a-z]+$", "Hello"));
    }

    @Test
    void mustRegexMatchBadRegex() {
        assertThrows(RuntimeException.class, () -> new MustRegexMatchFunc().execute("[invalid", "hello"));
    }

    @Test
    void mustRegexFindAll() {
        final var result = (List<?>) new MustRegexFindAllFunc().execute("[0-9]+", "abc123def456", -1);
        assertEquals(List.of("123", "456"), result);
    }

    @Test
    void mustRegexFindAllWithLimit() {
        final var result = (List<?>) new MustRegexFindAllFunc().execute("[0-9]+", "abc123def456ghi789", 2);
        assertEquals(List.of("123", "456"), result);
    }

    @Test
    void mustRegexFindAllBadRegex() {
        assertThrows(RuntimeException.class, () -> new MustRegexFindAllFunc().execute("[invalid", "hello", -1));
    }

    @Test
    void regexFind() {
        assertEquals("abc", new RegexFindFunc().execute("[a-z]+", "abc123"));
    }

    @Test
    void regexFindNoMatch() {
        assertEquals("", new RegexFindFunc().execute("[0-9]+", "abc"));
    }

    @Test
    void regexFindNull() {
        assertEquals("", new RegexFindFunc().execute((Object) null, (Object) null));
    }

    @Test
    void mustRegexFind() {
        assertEquals("abc", new MustRegexFindFunc().execute("[a-z]+", "abc123"));
    }

    @Test
    void mustRegexFindBadRegex() {
        assertThrows(RuntimeException.class, () -> new MustRegexFindFunc().execute("[invalid", "hello"));
    }

    @Test
    void mustRegexReplaceAll() {
        assertEquals("-W-xxW-", new MustRegexReplaceAllFunc().execute("a(x*)b", "-ab-axxb-", "$1W"));
    }

    @Test
    void mustRegexReplaceAllBadRegex() {
        assertThrows(RuntimeException.class, () -> new MustRegexReplaceAllFunc().execute("[invalid", "hello", "x"));
    }

    @Test
    void regexReplaceAllLiteral() {
        // Pattern.quote wraps replacement in \Q...\E; Matcher.replaceAll processes \Q->Q, \E->E
        final var result = new RegexReplaceAllLiteralFunc().execute("a(x*)b", "-ab-axxb-", "REPLACED").toString();
        assertEquals("-QREPLACEDE-QREPLACEDE-", result);
    }

    @Test
    void regexReplaceAllLiteralNull() {
        assertEquals("", new RegexReplaceAllLiteralFunc().execute((Object) null, (Object) null, (Object) null));
    }

    @Test
    void mustRegexReplaceAllLiteral() {
        // Same as regexReplaceAllLiteral - Pattern.quote wraps in \Q...\E
        final var result = new MustRegexReplaceAllLiteralFunc().execute("a(x*)b", "-ab-axxb-", "REPLACED").toString();
        assertEquals("-QREPLACEDE-QREPLACEDE-", result);
    }

    @Test
    void mustRegexReplaceAllLiteralBadRegex() {
        assertThrows(RuntimeException.class, () -> new MustRegexReplaceAllLiteralFunc().execute("[invalid", "hello", "x"));
    }

    @Test
    void regexSplit() {
        final var result = (List<?>) new RegexSplitFunc().execute("z+", "pizza", -1);
        assertEquals(List.of("pi", "a"), result);
    }

    @Test
    void regexSplitNoMatch() {
        final var result = (List<?>) new RegexSplitFunc().execute("x", "hello", -1);
        assertEquals(List.of("hello"), result);
    }

    @Test
    void mustRegexSplit() {
        final var result = (List<?>) new MustRegexSplitFunc().execute("z+", "pizza", -1);
        assertEquals(List.of("pi", "a"), result);
    }

    @Test
    void mustRegexSplitBadRegex() {
        assertThrows(RuntimeException.class, () -> new MustRegexSplitFunc().execute("[invalid", "hello"));
    }

    @Test
    void regexQuoteMeta() {
        final var result = new RegexQuoteMetaFunc().execute("1.2.3").toString();
        assertEquals(Pattern.quote("1.2.3"), result);
    }

    @Test
    void regexQuoteMetaNull() {
        assertEquals("", new RegexQuoteMetaFunc().execute((Object) null));
    }

    @Test
    void addf() {
        assertEquals(5.5, (double) new AddfFunc().execute(1.5, 2.0, 2.0), 0.001);
    }

    @Test
    void addfTwoArgs() {
        assertEquals(3.0, (double) new AddfFunc().execute(1.0, 2.0), 0.001);
    }

    @Test
    void add1f() {
        assertEquals(2.5, (double) new Add1fFunc().execute(1.5), 0.001);
    }

    @Test
    void add1fNegative() {
        assertEquals(-0.5, (double) new Add1fFunc().execute(-1.5), 0.001);
    }

    @Test
    void subf() {
        assertEquals(5.5, (double) new SubfFunc().execute(7.5, 2.0), 0.001);
    }

    @Test
    void subfSimple() {
        assertEquals(5.0, (double) new SubfFunc().execute(7.0, 2.0), 0.001);
    }

    @Test
    void divf() {
        assertEquals(5.0, (double) new DivfFunc().execute(10.0, 2.0), 0.001);
    }

    @Test
    void divfSimple() {
        assertEquals(1.25, (double) new DivfFunc().execute(5.0, 4.0), 0.001);
    }

    @Test
    void divfByZero() {
        assertThrows(ArithmeticException.class, () -> new DivfFunc().execute(10.0, 0.0));
    }

    @Test
    void mulf() {
        assertEquals(6.0, (double) new MulfFunc().execute(1.5, 2.0, 2.0), 0.001);
    }

    @Test
    void mulfTwoArgs() {
        assertEquals(6.0, (double) new MulfFunc().execute(2.0, 3.0), 0.001);
    }

    @Test
    void maxf() {
        assertEquals(3.0, (double) new MaxfFunc().execute(1.0, 2.5, 3.0), 0.001);
    }

    @Test
    void maxfSingle() {
        assertEquals(5.0, (double) new MaxfFunc().execute(5.0), 0.001);
    }

    @Test
    void minf() {
        assertEquals(1.5, (double) new MinfFunc().execute(1.5, 2.0, 3.0), 0.001);
    }

    @Test
    void minfSingle() {
        assertEquals(5.0, (double) new MinfFunc().execute(5.0), 0.001);
    }

    @Test
    void until() {
        assertEquals(List.of(0, 1, 2, 3, 4), new UntilFunc().execute(5));
    }

    @Test
    void untilZero() {
        assertEquals(List.of(), new UntilFunc().execute(0));
    }

    @Test
    void untilStep() {
        assertEquals(List.of(3, 5), new UntilStepFunc().execute(3, 6, 2));
    }

    @Test
    void untilStepNegative() {
        final var result = (List<?>) new UntilStepFunc().execute(5, 1, -2);
        assertEquals(List.of(5, 3), result);
    }

    @Test
    void untilStepZeroThrows() {
        assertThrows(IllegalArgumentException.class, () -> new UntilStepFunc().execute(0, 5, 0));
    }

    @Test
    void seqSingleArg() {
        assertEquals(List.of(1, 2, 3, 4, 5), new SeqFunc().execute(5));
    }

    @Test
    void seqThreeArgs() {
        assertEquals(List.of(0, 2, 4, 6, 8, 10), new SeqFunc().execute(0, 2, 10));
    }

    @Test
    void seqTwoArgs() {
        assertEquals(List.of(1, 2, 3), new SeqFunc().execute(1, 3));
    }

    @Test
    void dig() {
        final Map<String, Object> dict = Map.of("a", Map.of("b", "value"));
        assertEquals("value", new DigFunc().execute("b", "default", dict.get("a")));
    }

    @Test
    void digWithDefault() {
        final Map<String, Object> dict = Map.of("a", "val");
        assertEquals("default", new DigFunc().execute("missing", "default", dict));
    }

    @Test
    void digNullDict() {
        assertEquals("default", new DigFunc().execute("key", "default", (Object) null));
    }

    @Test
    void mergeOverwrite() {
        final var map1 = new LinkedHashMap<String, Object>();
        map1.put("a", "1");
        map1.put("b", "old");
        final var map2 = new LinkedHashMap<String, Object>();
        map2.put("b", "new");
        map2.put("c", "3");
        final var result = (Map<?, ?>) new MergeOverwriteFunc().execute(map1, map2);
        assertEquals("1", result.get("a"));
        assertEquals("new", result.get("b"));
        assertEquals("3", result.get("c"));
    }

    @Test
    void mergeOverwriteNull() {
        assertEquals(Map.of(), new MergeOverwriteFunc().execute((Object) null));
    }

    @Test
    void mustMergeOverwrite() {
        final var map1 = new LinkedHashMap<String, Object>();
        map1.put("a", "1");
        map1.put("b", "old");
        final var map2 = new LinkedHashMap<String, Object>();
        map2.put("b", "new");
        map2.put("c", "3");
        final var result = (Map<?, ?>) new MustMergeOverwriteFunc().execute(map1, map2);
        assertEquals("1", result.get("a"));
        assertEquals("new", result.get("b"));
        assertEquals("3", result.get("c"));
    }

    @Test
    void mustMerge() {
        final var map1 = new LinkedHashMap<String, Object>();
        map1.put("a", "1");
        final var map2 = new LinkedHashMap<String, Object>();
        map2.put("b", "2");
        final var result = (Map<?, ?>) new MustMergeFunc().execute(map1, map2);
        assertEquals("1", result.get("a"));
        assertEquals("2", result.get("b"));
    }

    @Test
    void deepCopy() {
        final var map = new LinkedHashMap<String, Object>();
        map.put("key", "val");
        map.put("nested", new LinkedHashMap<>(Map.of("inner", "value")));
        final var copy = (Map<String, Object>) new DeepCopyFunc().execute(map);
        assertEquals("val", copy.get("key"));
        //noinspection unchecked
        final var nestedCopy = (Map<String, Object>) copy.get("nested");
        nestedCopy.put("inner", "changed");
        assertEquals("value", ((Map<?, ?>) map.get("nested")).get("inner"));
    }

    @Test
    void deepCopyNull() {
        assertNull(new DeepCopyFunc().execute((Object) null));
    }

    @Test
    void mustDeepCopy() {
        final var list = new ArrayList<>(List.of(1, 2, 3));
        final var copy = (List<?>) new MustDeepCopyFunc().execute(list);
        assertEquals(List.of(1, 2, 3), copy);
    }

    @Test
    void mustDeepCopyNull() {
        assertNull(new MustDeepCopyFunc().execute((Object) null));
    }

    @Test
    void allTrue() {
        assertEquals(true, new AllFunc().execute(1, 2, 3));
    }

    @Test
    void allFalse() {
        assertEquals(false, new AllFunc().execute(0, 1));
    }

    @Test
    void allEmpty() {
        assertEquals(false, new AllFunc().execute());
    }

    @Test
    void anyTrue() {
        assertEquals(true, new AnyFunc().execute(0, 1));
    }

    @Test
    void anyFalse() {
        assertEquals(false, new AnyFunc().execute(0, ""));
    }

    @Test
    void anyEmpty() {
        assertEquals(false, new AnyFunc().execute());
    }

    @Test
    void ternaryTrue() {
        assertEquals("foo", new TernaryFunc().execute("foo", "bar", true));
    }

    @Test
    void ternaryFalse() {
        assertEquals("bar", new TernaryFunc().execute("foo", "bar", false));
    }

    @Test
    void ternaryNull() {
        assertNull(new TernaryFunc().execute((Object) null));
    }

    @Test
    void mustToJson() {
        final var result = new MustToJsonFunc().execute(Map.of("key", "val")).toString();
        assertTrue(result.contains("\"key\""));
        assertTrue(result.contains("\"val\""));
    }

    @Test
    void mustToJsonNull() {
        assertEquals("null", new MustToJsonFunc().execute((Object) null));
    }

    @Test
    void mustToPrettyJson() {
        final var result = new MustToPrettyJsonFunc().execute(Map.of("key", "val")).toString();
        assertTrue(result.contains("\"key\""));
        assertTrue(result.contains("\"val\""));
        assertTrue(result.contains("\n"));
    }

    @Test
    void mustToPrettyJsonNull() {
        assertEquals("null", new MustToPrettyJsonFunc().execute((Object) null));
    }

    @Test
    void toRawJson() {
        final var result = new ToRawJsonFunc().execute(Map.of("key", "val")).toString();
        assertTrue(result.contains("key"));
        assertTrue(result.contains("val"));
    }

    @Test
    void toRawJsonNull() {
        assertEquals("null", new ToRawJsonFunc().execute((Object) null));
    }

    @Test
    void mustToRawJson() {
        final var result = new MustToRawJsonFunc().execute(Map.of("key", "val")).toString();
        assertTrue(result.contains("key"));
        assertTrue(result.contains("val"));
    }

    @Test
    void mustToRawJsonNull() {
        assertEquals("null", new MustToRawJsonFunc().execute((Object) null));
    }

    @Test
    void dateFunc() {
        // date with invalid time input returns empty
        assertEquals("", new DateFunc().execute("2006-01-02", ""));
    }

    @Test
    void dateFuncEmpty() {
        assertEquals("", new DateFunc().execute("", "2023-05-15T00:00:00Z"));
    }

    @Test
    void agoFunc() {
        final var result = new AgoFunc().execute("2020-01-01T00:00:00Z").toString();
        assertFalse(result.isEmpty());
    }

    @Test
    void agoFuncEmpty() {
        assertEquals("", new AgoFunc().execute(""));
    }

    @Test
    void durationFunc() {
        assertEquals("1m35s", new DurationFunc().execute("95"));
    }

    @Test
    void durationFuncZero() {
        assertEquals("0s", new DurationFunc().execute("0"));
    }

    @Test
    void durationFuncEmpty() {
        assertEquals("", new DurationFunc().execute(""));
    }

    @Test
    void unixEpochFunc() {
        final var result = new UnixEpochFunc().execute("2020-01-01T00:00:00Z");
        assertTrue(result instanceof Number);
        assertEquals(1577836800L, ((Number) result).longValue());
    }

    @Test
    void unixEpochFuncEmpty() {
        assertEquals("", new UnixEpochFunc().execute(""));
    }

    @Test
    void htmlDateFunc() {
        final var result = new HtmlDateFunc().execute("2023-05-15T12:00:00Z").toString();
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    void htmlDateFuncEmpty() {
        assertEquals("", new HtmlDateFunc().execute(""));
    }

    @Test
    void toDateFunc() {
        // toDate with empty layout returns empty string
        assertEquals("", new ToDateFunc().execute("2006-01-02", "bad-input"));
    }

    @Test
    void toDateFuncEmpty() {
        assertEquals("", new ToDateFunc().execute("", ""));
    }

    @Test
    void sha1sum() {
        assertEquals("d3486ae9136e7856bc42212385ea797094475802", new Sha1SumFunc().execute("Hello world!"));
    }

    @Test
    void sha1sumEmpty() {
        assertEquals("", new Sha1SumFunc().execute((Object) null));
    }

    @Test
    void sha512sum() {
        final var result = new Sha512SumFunc().execute("Hello world!").toString();
        assertEquals(128, result.length());
        assertTrue(result.matches("[0-9a-f]+"));
    }

    @Test
    void sha512sumEmpty() {
        assertEquals("", new Sha512SumFunc().execute((Object) null));
    }

    @Test
    void adler32sum() {
        final var result = new Adler32SumFunc().execute("Hello").toString();
        assertEquals(8, result.length());
        assertTrue(result.matches("[0-9a-f]+"));
    }

    @Test
    void adler32sumEmpty() {
        assertEquals("", new Adler32SumFunc().execute((Object) null));
    }

    @Test
    void randBytes() {
        final var result = new RandBytesFunc().execute(24).toString();
        assertFalse(result.isEmpty());
        assertTrue(result.matches("[A-Za-z0-9+/=]+"));
    }

    @Test
    void randBytesZero() {
        assertEquals("", new RandBytesFunc().execute(0));
    }

    @Test
    void atoi() {
        assertEquals(42, new AtoiFunc().execute("42"));
    }

    @Test
    void atoiNegative() {
        assertEquals(-7, new AtoiFunc().execute("-7"));
    }

    @Test
    void atoiEmpty() {
        assertEquals(0, new AtoiFunc().execute(""));
    }

    @Test
    void toDecimal() {
        assertEquals(511, new ToDecimalFunc().execute("0777"));
    }

    @Test
    void toDecimalEmpty() {
        assertEquals(0, new ToDecimalFunc().execute(""));
    }

    @Test
    void toStrings() {
        final var result = (List<?>) new ToStringsFunc().execute(List.of(1, 2, 3));
        assertEquals(List.of("1", "2", "3"), result);
    }

    @Test
    void toStringsNull() {
        assertEquals(List.of(), new ToStringsFunc().execute((Object) null));
    }

    @Test
    void isAbsTrue() {
        assertEquals(true, new IsAbsFunc().execute("/foo/bar"));
    }

    @Test
    void isAbsFalse() {
        assertEquals(false, new IsAbsFunc().execute("foo/bar"));
    }

    @Test
    void isAbsNull() {
        assertEquals(false, new IsAbsFunc().execute((Object) null));
    }

    @Test
    void osBase() {
        assertEquals("baz", new OsBaseFunc().execute("/foo/bar/baz"));
    }

    @Test
    void osBaseNull() {
        assertEquals("", new OsBaseFunc().execute((Object) null));
    }

    @Test
    void osDir() {
        final var result = new OsDirFunc().execute("/foo/bar/baz").toString();
        assertTrue(result.endsWith("foo/bar") || result.endsWith("foo\\bar"));
    }

    @Test
    void osDirNull() {
        assertEquals(".", new OsDirFunc().execute((Object) null));
    }

    @Test
    void osClean() {
        final var result = new OsCleanFunc().execute("/foo/../bar").toString();
        assertTrue(result.endsWith("bar"));
        assertFalse(result.contains(".."));
    }

    @Test
    void osCleanNull() {
        assertEquals(".", new OsCleanFunc().execute((Object) null));
    }

    @Test
    void osExt() {
        assertEquals(".bar", new OsExtFunc().execute("foo.bar"));
    }

    @Test
    void osExtNoDot() {
        assertEquals("", new OsExtFunc().execute("foobar"));
    }

    @Test
    void osExtNull() {
        assertEquals("", new OsExtFunc().execute((Object) null));
    }

    @Test
    void uuidv4() {
        final var result = new Uuidv4Func().execute().toString();
        assertTrue(result.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    }

    @Test
    void getHostByName() {
        final var result = new GetHostByNameFunc().execute("localhost").toString();
        assertFalse(result.isEmpty());
    }

    @Test
    void getHostByNameNull() {
        assertEquals("", new GetHostByNameFunc().execute((Object) null));
    }

    @Test
    void mustFirst() {
        assertEquals(1, new MustFirstFunc().execute(List.of(1, 2, 3)));
    }

    @Test
    void mustFirstEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new MustFirstFunc().execute(List.of()));
    }

    @Test
    void mustRest() {
        assertEquals(List.of(2, 3), new MustRestFunc().execute(List.of(1, 2, 3)));
    }

    @Test
    void mustRestSingle() {
        assertEquals(List.of(), new MustRestFunc().execute(List.of(1)));
    }

    @Test
    void mustLast() {
        assertEquals(3, new MustLastFunc().execute(List.of(1, 2, 3)));
    }

    @Test
    void mustLastEmpty() {
        assertThrows(IllegalArgumentException.class, () -> new MustLastFunc().execute(List.of()));
    }

    @Test
    void mustInitial() {
        assertEquals(List.of(1, 2), new MustInitialFunc().execute(List.of(1, 2, 3)));
    }

    @Test
    void mustInitialSingle() {
        assertEquals(List.of(), new MustInitialFunc().execute(List.of(1)));
    }

    @Test
    void mustAppend() {
        assertEquals(List.of(1, 2, 3), new MustAppendFunc().execute(List.of(1, 2), 3));
    }

    @Test
    void mustPrepend() {
        assertEquals(List.of(1, 2, 3), new MustPrependFunc().execute(List.of(2, 3), 1));
    }

    @Test
    void mustReverse() {
        assertEquals(List.of(3, 2, 1), new MustReverseFunc().execute(List.of(1, 2, 3)));
    }

    @Test
    void mustUniq() {
        assertEquals(List.of(1, 2, 3), new MustUniqFunc().execute(List.of(1, 1, 2, 3)));
    }

    @Test
    void mustWithout() {
        assertEquals(List.of(1, 3), new MustWithoutFunc().execute(List.of(1, 2, 3, 4), 2, 4));
    }

    @Test
    void mustHasTrue() {
        assertEquals(true, new MustHasFunc().execute(2, List.of(1, 2, 3)));
    }

    @Test
    void mustHasFalse() {
        assertEquals(false, new MustHasFunc().execute(5, List.of(1, 2, 3)));
    }

    @Test
    void mustCompact() {
        final var input = new ArrayList<>();
        input.add(1);
        input.add("");
        input.add(null);
        input.add(2);
        assertEquals(List.of(1, 2), new MustCompactFunc().execute(input));
    }

    @Test
    void mustSlice() {
        assertEquals(List.of(2, 3), new MustSliceFunc().execute(List.of(1, 2, 3, 4, 5), 1, 3));
    }

    @Test
    void mustSliceToEnd() {
        assertEquals(List.of(3, 4, 5), new MustSliceFunc().execute(List.of(1, 2, 3, 4, 5), 2));
    }

    @Test
    void toYaml() {
        final var result = new ToYamlFunc().execute(Map.of("key", "val")).toString();
        assertTrue(result.contains("key:"));
        assertTrue(result.contains("val"));
    }

    @Test
    void toYamlNull() {
        assertEquals("null", new ToYamlFunc().execute((Object) null));
    }

    @Test
    void lookup() {
        final var result = (Map<?, ?>) new LookupFunc().execute();
        assertTrue(result.isEmpty());
    }
}
