package io.github.ralfspoeth.json.data;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.io.JsonReader;
import io.github.ralfspoeth.json.query.Pointer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static org.junit.jupiter.api.Assertions.*;

class JsonValueTest {

    @Test
    void testDepth() {
        assertAll(
                () -> assertEquals(1, objectBuilder().build().depth()),
                () -> assertEquals(2, objectBuilder().put("x", JsonNull.INSTANCE).build().depth()),
                () -> assertEquals(3, objectBuilder().put("x", objectBuilder().put("y", JsonBoolean.TRUE).build()).build().depth())
        );
    }

    @Test
    void testMutability() {
        var je = new JsonArray(
                List.of(
                        JsonNull.INSTANCE, JsonBoolean.FALSE, JsonBoolean.TRUE,
                        Basic.of(5), new JsonString("five"),
                        new JsonObject(Map.of("a", JsonBoolean.TRUE, "b", new JsonObject(Map.of())))
                )
        );
        var obj = je.elements().stream()
                .filter(e -> e instanceof JsonObject)
                .map(JsonObject.class::cast)
                .findFirst()
                .orElseThrow();

        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> je.elements().add(JsonNull.INSTANCE)),
                () -> assertThrows(UnsupportedOperationException.class, () -> je.elements().removeFirst()),
                () -> assertThrows(UnsupportedOperationException.class, () -> obj.members().put("c", JsonBoolean.TRUE))

        );
    }

    @Test
    void testSwitch() {
        var str = Stream.of(
                JsonBoolean.FALSE,
                JsonBoolean.TRUE,
                JsonNumber.ZERO,
                JsonNull.INSTANCE,
                new JsonString("str"),
                arrayBuilder()
                        .add(JsonNumber.ZERO)
                        .build(),
                objectBuilder()
                        .put("a", JsonBoolean.TRUE)
                        .put("b", arrayBuilder())
                        .build()
        );
        var l = str.map(e -> switch (e) {
            case Basic<?> b -> b.json();
            case Aggregate a -> a.toString();
        }).toList();
        assertNotNull(l);
    }

    @Test
    void testDecimal() {
        assertAll(
                () -> assertEquals(0, JsonValue.of(10).decimal().orElseThrow().compareTo(BigDecimal.TEN)),
                () -> assertEquals(BigDecimal.TWO, JsonValue.of(null).decimal().orElse(BigDecimal.TWO)),
                () -> assertThrows(NoSuchElementException.class, () -> Basic.of(null).decimal().orElseThrow())
        );
    }

    @Test
    void testString() {
        assertAll(
                () -> assertEquals("hello", JsonValue.of("hello").string().orElseThrow()),
                () -> assertEquals("hello", JsonValue.of(null).string().orElse("hello")),
                () -> assertThrows(NoSuchElementException.class, () -> Basic.of(null).string().orElseThrow())
        );
    }

    @Test
    void testOptConv() throws IOException {
        // given
        var src = """
                {
                    "a": [0, 1, 2, {"b": true}]
                }
                """;
        // when
        var parsed = Greyson.readValue(Reader.of(src)).orElseThrow();
        // then
        assertAll(
                () -> assertTrue(parsed.get("a").isPresent()),
                () -> assertTrue(parsed.get("a")
                        .flatMap(a -> a.get(3))
                        .flatMap(a3 -> a3.get("b"))
                        .flatMap(JsonValue::bool)
                        .orElseThrow()),
                () -> assertTrue(
                        parsed.get("x")
                                .flatMap(x -> x.get(5))
                                .flatMap(JsonValue::decimal)
                                .map(BigDecimal::doubleValue)
                                .isEmpty()
                ),
                () -> assertTrue(
                        parsed.get("y").map(JsonValue::decimal).isEmpty()
                )
        );
    }

    @Test
    void testBuilder() throws IOException {
        // given
        var src = """
                {
                    "a": [0, 1, 2, {"b": true}]
                }
                """;

        try (var jr = new JsonReader(Reader.of(src))) {
            // when
            var parsed = jr.read();
            // then
            assertAll(
                    () -> assertDoesNotThrow(() -> parsed.orElseThrow())
            );
        }
    }

    @Test
    void testBool() {
        assertAll(
                () -> assertTrue(JsonBoolean.TRUE.bool().orElseThrow()),
                () -> assertFalse(JsonBoolean.FALSE.bool().orElseThrow()),
                () -> assertTrue(JsonNull.INSTANCE.bool().isEmpty()),
                () -> assertTrue(new JsonNumber(BigDecimal.ZERO).bool().isEmpty()),
                () -> assertTrue(new JsonString("str").bool().isEmpty()),
                () -> assertTrue(new JsonArray(List.of()).bool().isEmpty()),
                () -> assertTrue(new JsonObject(Map.of()).bool().isEmpty())
        );
    }

    @Test
    void testInt() {
        // given: {"a":1, "b": 2, "c": 3}
        var jo = objectBuilder()
                .putBasic("a", 1)
                .putBasic("b", 2)
                .putBasic("c", 3)
                .build();
        // when
        record Equiv(int a, int b, int c) {}
        record Partial(int a, int c) {}
        record Renamed(int x, int y, int z) {}
        record Different(int x, int y, double d, String name) {}
        // then
        assertAll(
                () -> assertEquals(new Equiv(1, 2, 3), new Equiv(
                        jo.get("a").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow(),
                        jo.get("b").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow(),
                        jo.get("c").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow()
                )),
                () -> assertEquals(new Partial(1, 3), new Partial(
                        jo.get("a").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow(),
                        jo.get("c").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow()
                )),
                () -> assertEquals(new Renamed(1, 2, 3), new Renamed(
                        jo.get("a").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow(),
                        jo.get("b").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow(),
                        jo.get("c").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow()
                )),
                () -> assertEquals(new Different(1, 2, 0d, null), new Different(
                        jo.get("a").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElse(0),
                        jo.get("b").flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElse(0),
                        jo.get("d").flatMap(JsonValue::decimal).map(BigDecimal::doubleValue).orElse(0d),
                        jo.get("name").flatMap(JsonValue::string).orElse(null)
                ))
        );
    }

    @Test
    void testArrayOfPoints() throws IOException {
        // Given
        record Point(int x, int y) {}
        var src = """
                [{"x": 1, "y": 2}, {"x": 3, "y": 4}, {"x": 5, "y": 6}, {"x": 7, "y": 8}]
                """;
        // when
        var points = Greyson.readValue(Reader.of(src))
                .stream()
                .flatMap(a -> a.elements().stream())
                .map(o -> new Point(
                        Pointer.parse("x").intValue(o).orElseThrow(),
                        Pointer.parse("y").intValue(o).orElseThrow()
                ))
                .toList();
        // then
        assertAll(
                () -> assertEquals(4, points.size()),
                () -> assertEquals(new Point(1, 2), points.getFirst()),
                () -> assertEquals(new Point(7, 8), points.getLast())
        );
    }

    @Test
    void testPathAsFunctionToOptional() throws IOException {
        // given
        record Point(int x, int y) {}
        record Rectangle(Point topLeft, Point bottomRight){}
        var src = """
                [
                    {"tl": {"x": 1, "y": 2}, "br": {"x": 3, "y": 4}},
                    {"tl": {"x": 5, "y": 6}, "br": {"x": 7, "y": 8}}
                ]
                """;
        // when
        var r = Pointer.self();
        var topLeft = r.member("tl");
        var bottomRight = r.member("br");
        var x = r.member("x");
        var y = r.member("y");

        var rects = Greyson.readValue(Reader.of(src))
                .stream()
                .flatMap(a -> a.elements().stream())
                .map(v -> new Rectangle(
                        new Point(
                                topLeft.resolve(x).intValue(v).orElse(0),
                                topLeft.resolve(y).intValue(v).orElse(0)
                        ),
                        new Point(
                                bottomRight.resolve(x).intValue(v).orElse(0),
                                bottomRight.resolve(y).intValue(v).orElse(0)
                        )
                ))
                .toList();
        // then
        assertAll(
                () -> assertEquals(2, rects.size()),
                () -> assertEquals(new Rectangle(new Point(1, 2), new Point(3, 4)), rects.getFirst()),
                () -> assertEquals(new Rectangle(new Point(5, 6), new Point(7, 8)), rects.getLast())
        );
    }

    @Test
    void testValueOf() {
        // given
        // 1. primitives
        int i = 1;
        long l = 2L;
        float f = 3.14f;
        double d = 4.23;
        boolean b = true;
        String s = "Hello";
        // 2. array'ish structures
        Object o = new int[]{1, 2, 3};
        List<Integer> li = List.of(1, 2, 3);
        Iterable<Integer> it = List.of(1, 2, 3);
        // 3. map
        Map<?, ?> m = Map.of(1, 1, 2, 2, 3, 3);
        // then
        assertAll(
                () -> assertInstanceOf(JsonNumber.class, JsonValue.of(i)),
                () -> assertInstanceOf(JsonNumber.class, JsonValue.of((short)i)),
                () -> assertInstanceOf(JsonNumber.class, JsonValue.of((byte)i)),
                () -> assertInstanceOf(JsonNumber.class, JsonValue.of(l)),
                () -> assertInstanceOf(JsonNumber.class, JsonValue.of(f)),
                () -> assertInstanceOf(JsonNumber.class, JsonValue.of(d)),
                () -> assertInstanceOf(JsonBoolean.class, JsonValue.of(b)),
                () -> assertInstanceOf(JsonNull.class, JsonValue.of(null)),
                () -> assertInstanceOf(JsonString.class, JsonValue.of(s)),
                () -> assertInstanceOf(JsonArray.class, JsonValue.of(o)),
                () -> assertInstanceOf(JsonArray.class, JsonValue.of(li)),
                () -> assertInstanceOf(JsonArray.class, JsonValue.of(it)),
                () -> assertInstanceOf(JsonObject.class, JsonValue.of(m))
        );
    }

    @Test
    void children() {
        var arr = arrayBuilder().addBasic(1).addBasic(2).addBasic(3).build();
        var obj = objectBuilder().putBasic("a", 1).putBasic("b", 2).build();
        assertAll(
                // an array streams its elements, in order
                () -> assertEquals(List.of(Basic.of(1), Basic.of(2), Basic.of(3)),
                        arr.children().toList()),
                // an object streams its member values (not the keys), order unspecified
                () -> assertEquals(2L, obj.children().count()),
                () -> assertTrue(obj.children().toList()
                        .containsAll(List.of(Basic.of(1), Basic.of(2)))),
                // scalars have no children
                () -> assertEquals(0L, Basic.of("x").children().count()),
                () -> assertEquals(0L, JsonNull.INSTANCE.children().count())
        );
    }

    @Test
    void depthAndNodesViaChildren() {
        // {"a": [1, 2], "b": {"c": 3}}  -> depth 3; nodes 6
        // (the root, the array, 1, 2, the inner object, and 3 — keys aren't nodes)
        var doc = objectBuilder()
                .put("a", arrayBuilder().addBasic(1).addBasic(2))
                .put("b", objectBuilder().putBasic("c", 3))
                .build();
        assertAll(
                () -> assertEquals(3, doc.depth()),
                () -> assertEquals(6, doc.nodes()),
                () -> assertEquals(1, Basic.of(1).depth()),
                () -> assertEquals(1, Basic.of(1).nodes())
        );
    }
}
