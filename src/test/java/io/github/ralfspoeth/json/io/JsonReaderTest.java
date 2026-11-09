package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

class JsonReaderTest {

    record Result(JsonValue elem, Exception ex) {}

    Result parse(String text) {
        try (var jr = new JsonReader(new StringReader(text))) {
            return new Result(jr.readValue().orElseThrow(), null);
        } catch (Exception e) {
            return new Result(null, e);
        }
    }

    @Test
    void testTrailingBackslash() {
        var result = parse("[\"\\u0102\\u\"]");
        assertInstanceOf(JsonParseException.class, result.ex);
    }

    @Test
    void num01() {
        var result = parse("0e+1");
        assertEquals(Basic.of(0), result.elem);
    }

    @Test
    void numPlus1() {
        var result = parse("+1");
        assertAll(
                () -> assertNull(result.elem),
                () -> assertInstanceOf(JsonParseException.class, result.ex)
        );
    }

    @Test
    void testMinus1TwoDots() {
        var result = parse("[-1.0.]");
        assertAll(
                () -> assertNull(result.elem),
                () -> assertInstanceOf(JsonParseException.class, result.ex)
        );
    }

    @Test
    void testCommaAfterArrayClose() {
        var src = "[1],";
        var result = parse(src);
        assertAll(
                () -> assertNull(result.elem),
                () -> assertInstanceOf(JsonParseException.class, result.ex)
        );
    }

    @Test
    void test1TrueNoComma() {
        var src = "[1 true]";
        var result = parse(src);
        assertAll(
                () -> assertNull(result.elem),
                () -> assertInstanceOf(JsonParseException.class, result.ex)
        );
    }

    @Test
    void testTwoPairNoComma() {
        var src = """
                {"a":5
                 "b":6}"""; // comma missing
        JsonValue result = null;
        Exception ex = null;
        try (var rdr = new JsonReader(new StringReader(src))) {
            result = rdr.readValue().orElseThrow();
        } catch (Exception e) {
            ex = e;
        }
        final var tmpResult = result;
        final var tmpEx = ex;
        assertAll(
                () -> assertNull(tmpResult),
                () -> assertInstanceOf(JsonParseException.class, tmpEx)
        );
    }

    @Test
    void testEmptyObject() throws IOException {
        var source = "{}";
        try (var rdr = new StringReader(source);
             var parser = new JsonReader(rdr)) {
            var result = parser.readValue().orElseThrow();
            assertAll(
                    () -> assertNotNull(result),
                    () -> assertTrue(result instanceof JsonObject(var members) && members.isEmpty())
            );
        }
    }

    @Test
    void testEmptyArray() throws IOException {
        var source = "[]";
        try (var rdr = new StringReader(source);
             var parser = new JsonReader(rdr)) {
            var result = parser.readValue().orElseThrow();
            assertAll(
                    () -> assertNotNull(result),
                    () -> assertTrue(result instanceof JsonArray(var elements) && elements.isEmpty())
            );
        }
    }

    @Test
    void testSingleElems() {
        var sources = List.of("1", "true", "null", "false", "\"str\"");
        sources.forEach(source -> {
            try (var parser = new JsonReader(new StringReader(source))) {
                var v = parser.readValue().orElseThrow();
                assertAll(() -> assertInstanceOf(Basic.class, v));
            } catch (IOException e) {
                assertNull(e);
            }
        });
    }

    @Test
    void testSingleElemArray() {
        var sources = List.of("[1]", "[null]", "[false]", "[true]", "[\"str\"]");
        sources.forEach(source -> {
            try (var rdr = new StringReader(source);
                 var parser = new JsonReader(rdr)) {
                var result = parser.readValue().orElseThrow();
                assertAll(
                        () -> assertNotNull(result),
                        () -> assertTrue(result instanceof JsonArray(var elements) && elements.size() == 1)
                );
            } catch (IOException e) {
                assertNull(e);
            }
        });
    }

    @Test
    void testSingleMemberObject() throws IOException {
        var source = "{\"n\":5}";
        try (var r = new JsonReader(new StringReader(source))) {
            var o = r.readValue().orElseThrow();
            assertAll(() -> assertInstanceOf(JsonObject.class, o),
                    () -> assertEquals(1, o instanceof JsonObject(var members) ? members.size() : -1),
                    () -> assertEquals(new JsonObject(Map.of("n", Basic.of(5d))), o)
            );
        }
    }

    @Test
    void testDualMemberObject() throws Exception {
        var source = "{\"n\":5, \"m\": 7}";
        try (var r = new JsonReader(new StringReader(source))) {
            r.readValue().orElseThrow();
        }
    }

    @Test
    void testArrayOfValues() throws IOException {
        String source = "[5, 6, 7, false, null, true, \"str\"]";// "[{\"n\":5}, {\"m\":6}]";
        try (var p = new JsonReader(new StringReader(source))) {
            var a = p.readValue().orElseThrow();
            assertAll(
                    () -> assertInstanceOf(JsonArray.class, a),
                    () -> assertEquals(7, a instanceof JsonArray(var elements) ? elements.size() : -1),
                    () -> assertTrue(a instanceof JsonArray(var elements) && elements.contains(new JsonString("str")))
            );
        }
    }

    @Test
    void testArrayOfObject() throws IOException {
        String source = "[{\"n\":55}]";
        try (var p = new JsonReader(new StringReader(source))) {
            var a = p.readValue().orElseThrow();
            assertAll(
                    () -> assertInstanceOf(JsonArray.class, a),
                    () -> assertEquals(1, a.elements().size()),
                    () -> assertTrue(a.elements().contains(
                            new JsonObject(Map.of("n", Basic.of(55)))
                    ))
            );
        }
    }

    @Test
    void testArrayOfTwoObjects() throws IOException {
        String source = "[{\"n\":55}, {\"m\":7}]";
        try (var p = new JsonReader(new StringReader(source))) {
            var a = p.readValue().orElseThrow();
            assertAll(
                    () -> assertInstanceOf(JsonArray.class, a),
                    () -> assertEquals(2, a.elements().size()),
                    () -> assertTrue(a.elements().contains(
                            new JsonObject(Map.of("m", Basic.of(7)))
                    ))
            );
        }
    }

    @Test
    void testNestedObjectDepth1() throws IOException {
        String source = "{\"a\":{\"b\":[]}}";
        try (var p = new JsonReader(new StringReader(source))) {
            var e = p.readValue().orElseThrow();
            if (e instanceof JsonObject(var members)) {
                assertAll(
                        () -> assertEquals(1, members.size()),
                        () -> assertTrue(members.containsKey("a"))
                );
                if (members.get("a") instanceof JsonObject(var membersOfA)) {
                    assertAll(
                            () -> assertEquals(1, membersOfA.size()),
                            () -> assertTrue(membersOfA.containsKey("b")),
                            () -> assertEquals(new JsonArray(List.of()), membersOfA.get("b"))
                    );
                } else {
                    fail("not a JsonObject");
                }
            } else {
                fail("not a JsonObject");
            }
        }
    }

    @Test
    void testParseString() throws Exception {
        try (var src = new StringReader("null"); var rdr = new JsonReader(src)) {
            assertEquals(JsonNull.INSTANCE, rdr.readValue().orElseThrow());
        }
    }

    @Test
    void testParseLarge() throws Exception {
        try (var src = largeFile(); var rdr = new JsonReader(src)) {
            var result = rdr.readBuilder().orElseThrow();
            assertNotNull(result);
        }
    }

    private static int hcSum = 0; // black hole

    static int getHcSum() {
        return hcSum;
    }


    @AfterAll
    static void afterAll() {
        if(getHcSum()==Integer.MAX_VALUE) {
            System.err.println("hcSum overflow");
        }
    }

    @Test
    void testInputStreamReader() throws IOException {
        try (var is = getClass().getResourceAsStream("/large-file.json");
             var rdr = new InputStreamReader(requireNonNull(is));
             var jr = new JsonReader(rdr))
        {
            var result = jr.readBuilder().orElseThrow();
            hcSum += result.hashCode();
        }
    }

    @Test
    void testInputStreamReaderArray() throws IOException {
        try (var is = getClass().getResourceAsStream("/very-big-array.json.gz");
             var gis = new GZIPInputStream(requireNonNull(is));
             var rdr = new InputStreamReader(gis);
             var jr = new JsonReader(rdr)) {
            var result = jr.readBuilder().orElseThrow();
            hcSum += result.hashCode();
        }
    }

    private Reader largeFile() {
        return new BufferedReader(new InputStreamReader(
                requireNonNull(getClass().getResourceAsStream("/large-file.json")),
                StandardCharsets.UTF_8
        ));
    }
}