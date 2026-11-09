package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonString;
import io.github.ralfspoeth.json.data.JsonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JsonReader.StringCache} hands back one shared {@code String} for a
 * repeated literal. Content is unaffected — {@code String} is immutable and
 * nothing compares instances — so the only way to test it is by identity, and
 * the only way to test its bounds is to exceed them.
 *
 * <p>The bounds are the point. An unbounded version of this cache is the
 * memory leak removed from {@code JsonString.escaped} in 2.3.2: string
 * <em>values</em> carry unbounded, often remote-controlled cardinality, so a
 * cache that grows without limit and outlives its document is a
 * server-shaped defect. These tests pin that it does neither.
 */
class StringCacheTest {

    /** Every string literal the reader produced, in document order. */
    private static List<String> strings(String source) throws IOException {
        var out = new ArrayList<String>();
        try (var rdr = new JsonReader(Reader.of(source))) {
            collect(rdr.readValue().orElseThrow(), out);
        }
        return out;
    }

    private static void collect(JsonValue v, List<String> out) {
        switch (v) {
            case JsonObject(var members) -> members.forEach((k, m) -> {
                out.add(k);
                collect(m, out);
            });
            case JsonArray(var elements) -> elements.forEach(e -> collect(e, out));
            case JsonString(var s) -> out.add(s);
            default -> { }
        }
    }

    @Test
    @DisplayName("A repeated literal yields the very same instance")
    void repeatsAreShared() throws IOException {
        // one member per object, so map iteration order cannot shuffle this
        var s = strings("""
                [{"ccy": "USD"}, {"ccy": "EUR"}, {"ccy": "USD"}]""");
        assertAll(
                () -> assertEquals(List.of("ccy", "USD", "ccy", "EUR", "ccy", "USD"), s),
                // the member name, three occurrences
                () -> assertSame(s.get(0), s.get(2)),
                () -> assertSame(s.get(0), s.get(4)),
                // and a repeated value, which is where enum-like data pays off
                () -> assertSame(s.get(1), s.get(5))
        );
    }

    @Test
    @DisplayName("Content is untouched, including escapes")
    void contentIsUnchanged() throws IOException {
        assertEquals(
                List.of("a\"b", "c\\d", "e\tf", "äöü", "", "tab\there", "sl/ash"),
                strings("""
                        ["a\\"b", "c\\\\d", "e\\tf", "\\u00e4\\u00f6\\u00fc", "", \
                        "tab\\there", "sl\\/ash"]"""));
    }

    /**
     * Long strings bypass the cache entirely — neither stored nor scanned for
     * — so a document of large distinct values cannot fill it.
     */
    @Test
    @DisplayName("Strings beyond the length bound are not retained")
    void longStringsBypass() throws IOException {
        var long1 = "x".repeat(200);
        var s = strings("[\"" + long1 + "\", \"" + long1 + "\"]");
        assertAll(
                () -> assertEquals(long1, s.get(0)),
                () -> assertEquals(long1, s.get(1)),
                () -> assertNotSame(s.get(0), s.get(1))
        );
    }

    /**
     * Direct-mapped and fixed-size: a flood of distinct strings overwrites
     * slots rather than growing. Correctness must not depend on a hit, so the
     * values still have to come back right.
     */
    @Test
    @DisplayName("A flood of distinct strings stays correct")
    void floodStaysCorrect() throws IOException {
        var json = new StringBuilder("[");
        var expected = new ArrayList<String>();
        for (int i = 0; i < 5_000; i++) {
            var s = "value-" + i;
            expected.add(s);
            json.append(i == 0 ? "" : ", ").append('"').append(s).append('"');
        }
        assertEquals(expected, strings(json.append(']').toString()));
    }

    /**
     * The cache belongs to one reader, so nothing survives the document —
     * the property whose absence made the old escape cache a leak.
     */
    @Test
    @DisplayName("Nothing is shared between two readers")
    void cacheIsPerReader() throws IOException {
        var first = strings("""
                ["ccy"]""");
        var second = strings("""
                ["ccy"]""");
        assertAll(
                () -> assertEquals(first, second),
                () -> assertNotSame(first.getFirst(), second.getFirst(),
                        "a static cache would make these the same instance")
        );
    }

    @Test
    @DisplayName("A misspelled keyword still reports its text")
    void misspelledKeywordStillReports() {
        var ex = assertThrows(JsonParseException.class, () -> strings("[nul]"));
        assertTrue(ex.getMessage().contains("null"), ex.getMessage());
    }
}
