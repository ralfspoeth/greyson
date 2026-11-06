package io.github.ralfspoeth.json.io;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lexer's {@link Lexer.StringCache} hands back one shared {@code String}
 * for a repeated literal. Content is unaffected — {@code String} is immutable
 * and nothing compares instances — so the only way to test it is by identity,
 * and the only way to test the bounds is to exceed them.
 *
 * <p>The bounds are the point. An unbounded version of this cache is the
 * memory leak that was removed from {@code JsonString.escaped}: string
 * <em>values</em> carry unbounded, often remote-controlled cardinality, so a
 * cache that grows without limit and outlives its document is a server-shaped
 * defect. These tests pin that it does neither.
 */
class StringCacheTest {

    private static List<String> lex(String source) throws IOException {
        var out = new ArrayList<String>();
        try (var lexer = new Lexer(new StringReader(source))) {
            while (lexer.hasNext()) {
                if (lexer.next() instanceof Lexer.LiteralToken(var type, var value)
                        && type == Lexer.Type.STRING) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("A repeated literal yields the very same instance")
    void repeatsAreShared() throws IOException {
        var strings = lex("""
                [{"ccy": "USD"}, {"ccy": "EUR"}, {"ccy": "USD"}]""");
        assertAll(
                () -> assertEquals(List.of("ccy", "USD", "ccy", "EUR", "ccy", "USD"), strings),
                // the member name, three occurrences
                () -> assertSame(strings.get(0), strings.get(2)),
                () -> assertSame(strings.get(0), strings.get(4)),
                // and a repeated value, which is where enum-like data pays off
                () -> assertSame(strings.get(1), strings.get(5))
        );
    }

    @Test
    @DisplayName("Content is untouched, including escapes")
    void contentIsUnchanged() throws IOException {
        assertEquals(
                List.of("a\"b", "c\\d", "e\tf", "äöü", "", "tab\there"),
                lex("""
                        ["a\\"b", "c\\\\d", "e\\tf", "\\u00e4\\u00f6\\u00fc", "", "tab\\there"]"""));
    }

    /**
     * Long strings bypass the cache entirely — they are neither stored nor
     * scanned for — so a document of large distinct values cannot fill it.
     */
    @Test
    @DisplayName("Strings beyond the length bound are not cached")
    void longStringsBypass() throws IOException {
        var long1 = "x".repeat(200);
        var strings = lex("[\"" + long1 + "\", \"" + long1 + "\"]");
        assertAll(
                () -> assertEquals(long1, strings.get(0)),
                () -> assertEquals(long1, strings.get(1)),
                () -> assertNotSame(strings.get(0), strings.get(1), "a long string should not be retained")
        );
    }

    /**
     * Direct-mapped and fixed-size: a flood of distinct strings overwrites
     * slots rather than growing. Correctness must not depend on a hit, so the
     * values still have to come back right — and a name seen before the flood
     * may or may not survive it, which is fine and deliberately not asserted.
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
        json.append(']');
        assertEquals(expected, lex(json.toString()));
    }

    /**
     * The cache belongs to one lexer, so nothing survives the document. This
     * is the property whose absence made the old escape cache a leak.
     */
    @Test
    @DisplayName("Nothing is shared between two lexers")
    void cacheIsPerLexer() throws IOException {
        var first = lex("""
                ["ccy"]""");
        var second = lex("""
                ["ccy"]""");
        assertAll(
                () -> assertEquals(first, second),
                () -> assertNotSame(first.getFirst(), second.getFirst(),
                        "a static cache would make these the same instance")
        );
    }

    /**
     * {@code null}, {@code true} and {@code false} are constants, so they cost
     * neither a {@code String} nor a token.
     */
    @Test
    @DisplayName("Keyword tokens are shared constants")
    void keywordsAreConstants() throws IOException {
        var tokens = new ArrayList<Lexer.Token>();
        try (var lexer = new Lexer(new StringReader("[null, true, false, null, true, false]"))) {
            while (lexer.hasNext()) {
                var t = lexer.next();
                if (t instanceof Lexer.LiteralToken) tokens.add(t);
            }
        }
        assertAll(
                () -> assertEquals(6, tokens.size()),
                () -> assertSame(tokens.get(0), tokens.get(3)),
                () -> assertSame(tokens.get(1), tokens.get(4)),
                () -> assertSame(tokens.get(2), tokens.get(5)),
                () -> assertEquals("null", tokens.getFirst().value())
        );
    }

    @Test
    @DisplayName("A misspelled keyword still reports its text")
    void misspelledKeywordStillReports() {
        var ex = assertThrows(JsonParseException.class, () -> lex("[nul]"));
        assertTrue(ex.getMessage().contains("nul"), ex.getMessage());
    }
}
