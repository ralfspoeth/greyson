package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

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
                assertEquals(orig, result);
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
     * layout -- it had the same three defects -- so it gets its own expected
     * text rather than being compared against the value overload. Members come
     * out by name because {@code ObjectBuilder} holds a {@code TreeMap}; that
     * is the builder's order, not something the writer imposes.
     */
    @Test
    void testBuildersAreIndented() throws IOException {
        var source = """
                {"b": 6.0, "a": 5, "n": null, "arr": [1, {"x": 2}], "empty": []}""";
        var expected = """
                {
                    "a": 5,
                    "arr": [
                        1,
                        {
                            "x": 2
                        }
                    ],
                    "b": 6.0,
                    "empty": [],
                    "n": null
                }""";
        try (var rdr = new StringReader(source); var jr = new JsonReader(rdr)) {
            var builder = jr.read().orElseThrow();
            var w = new StringWriter();
            try (var jw = new JsonWriter(w)) {
                jw.write(builder);
            }
            assertEquals(expected, w.toString());
        }
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