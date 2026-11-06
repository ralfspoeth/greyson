package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonWriterTest {

    @Test
    void test1() throws Exception {
        var orig = Builder.objectBuilder()
                .put("a", Basic.of(5))
                .putBasic("bb", 6d)
                .putBasic("fuck", null)
                .put("arr", Builder.arrayBuilder()
                        .add(Basic.of(null))
                        .add(Basic.of(7.5))
                        .add(Builder.objectBuilder().build())
                        .add(Builder.objectBuilder().putBasic("a", 5d).build())
                        .build())
                .build();

        try (var pw = new StringWriter(); var wrt = new JsonWriter(new PrintWriter(pw))) {
            wrt.write(orig);
            try(var sr = new StringReader(pw.toString()); var rdr = new JsonReader(sr)) {
                var result = rdr.read().map(Builder::build).orElseThrow();
                assertEquals(new TreeMap<>(orig.members()), new TreeMap<>(result.members()));
            }
        }
    }

    @Test
    void testStrings() {
        var src = """
                Hello "World",\tthis
                is / very cool
                """ + (char)0x01;
        var json = "\"Hello \\\"World\\\",\\tthis\\nis \\/ very cool\\n\\u0001\"";
        assertEquals(json, new JsonString(src).json());
    }

    @Test
    void testBasics() throws IOException {
        try(var w = new StringWriter(); var jw = new JsonWriter(w)) {
            int len = 0;
            jw.write(Basic.of(1.2));
            assertEquals("1.2", w.getBuffer().substring(len, len+=3));
            jw.write(JsonNull.INSTANCE);
            assertEquals("null", w.getBuffer().substring(len, len+=4));
            jw.write(JsonBoolean.TRUE);
            assertEquals("true", w.getBuffer().substring(len, len+=4));
            jw.write(JsonBoolean.FALSE);
            assertEquals("false", w.getBuffer().substring(len, len+=5));
            jw.write(new JsonString("a"));
            assertEquals("\"a\"", w.getBuffer().substring(len, len+3));
        }
    }

    /*
     * Round-tripping alone cannot catch a layout defect -- the reader is
     * indifferent to whitespace, so malformed indentation reads back as an
     * equal document -- which is why the expected text is spelled out here.
     *
     * The value overload is pinned with a document whose objects hold a single
     * member each: JsonObject's members come from Map#copyOf, whose iteration
     * order is unspecified, so with two or more members there is no text to
     * assert. Nesting and empty aggregates are exercised through arrays, which
     * are ordered. The builder overload keeps a TreeMap, so it can be pinned
     * on a wider document, which the next test does.
     */
    private static final String NESTED = """
            {"o": {"arr": [null, 7.5, {}, [], {"a": 5.0}]}}""";

    private static final String PRETTY = """
            {
                "o": {
                    "arr": [
                        null,
                        7.5,
                        {},
                        [],
                        {
                            "a": 5.0
                        }
                    ]
                }
            }""";

    @Test
    void testAggregatesAreIndented() throws IOException {
        try (var rdr = new StringReader(NESTED); var jr = new JsonReader(rdr)) {
            var value = jr.read().map(Builder::build).orElseThrow();
            var w = new StringWriter();
            try (var jw = new JsonWriter(w)) {
                jw.write(value);
            }
            assertEquals(PRETTY, w.toString());
        }
    }

    /**
     * The builder overload is a second, parallel implementation of the same
     * layout -- it had the same three defects -- so it gets its own assertion
     * rather than being compared against the value overload.
     *
     * <p>It used to pin a wider document in name order, which held only
     * because {@code ObjectBuilder} kept a {@code TreeMap}. It keeps a
     * {@code HashMap} now, so member order is unspecified on this side too,
     * and the document below holds one member per object for the same reason
     * {@link #NESTED} does: nesting and empty aggregates are exercised through
     * arrays, which are ordered.
     */
    @Test
    void testBuildersAreIndented() throws IOException {
        try (var rdr = new StringReader(NESTED); var jr = new JsonReader(rdr)) {
            var builder = jr.read().orElseThrow();
            var w = new StringWriter();
            try (var jw = new JsonWriter(w)) {
                jw.write(builder);
            }
            assertEquals(PRETTY, w.toString());
        }
    }

    /**
     * On a document with several members per object, the two overloads can
     * only be compared as <em>documents</em>, not as text. {@code JsonObject}
     * runs its members through {@link java.util.Map#copyOf}, whose iteration
     * order comes from a salt randomised at JVM startup, while the builder
     * iterates a {@code HashMap}: the two orders differ, and the value side
     * differs between runs of the same build.
     *
     * <p>What must hold regardless is that both write the same document and
     * that both indent it -- the part the single-member documents above pin
     * exactly, and that only one of the two parallel implementations could
     * lose.
     */
    @Test
    void testBothOverloadsWriteTheSameDocument() throws IOException {
        var source = """
                {"b": 6.0, "a": 5, "n": null, "arr": [1, {"x": 2}], "empty": []}""";
        String fromBuilder;
        String fromValue;
        try (var rdr = new StringReader(source); var jr = new JsonReader(rdr)) {
            var builder = jr.read().orElseThrow();
            var w = new StringWriter();
            try (var jw = new JsonWriter(w)) {
                jw.write(builder);
            }
            fromBuilder = w.toString();
            var w2 = new StringWriter();
            try (var jw = new JsonWriter(w2)) {
                jw.write(builder.build());
            }
            fromValue = w2.toString();
        }
        assertAll(
                () -> assertEquals(reread(fromBuilder), reread(fromValue)),
                () -> assertEquals(lines(fromBuilder), lines(fromValue),
                        "one overload indented and the other did not"),
                () -> assertEquals(12, lines(fromValue), "five members, a nested object and an array")
        );
    }

    private static JsonValue reread(String text) throws IOException {
        try (var rdr = new StringReader(text); var jr = new JsonReader(rdr)) {
            return jr.read().map(Builder::build).orElseThrow();
        }
    }

    private static long lines(String text) {
        return text.lines().count();
    }

    @Test
    void testEmptyAggregatesStayOnOneLine() throws IOException {
        var w = new StringWriter();
        try (var jw = new JsonWriter(w)) {
            jw.write(Builder.objectBuilder().build());
            jw.write(Builder.arrayBuilder().build());
            jw.write(Builder.objectBuilder());
            jw.write(Builder.arrayBuilder());
        }
        assertEquals("{}[]{}[]", w.toString());
    }
}