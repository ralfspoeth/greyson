package io.github.ralfspoeth.json;

import io.github.ralfspoeth.json.data.*;
import io.github.ralfspoeth.json.io.JsonParseException;
import io.github.ralfspoeth.json.query.Pointer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class GreysonTest {

    @Test
    void testReadValueFromString_validJson() throws IOException {
        String jsonString = "{\"name\":\"test\",\"value\":123}";
        JsonValue element = Greyson.readValue(Reader.of(jsonString)).orElseThrow();
        assertNotNull(element);
        assertInstanceOf(JsonObject.class, element);
        JsonObject jo = (JsonObject) element;
        assertEquals(new JsonString("test"), jo.members().get("name"));
        assertEquals(Basic.of(123), jo.members().get("value"));
    }

    @Test
    void testReadValueFromString_invalidJson() {
        String invalidJsonString = "{\"name\":\"test\",\"value\":123"; // Missing closing brace
        assertThrows(JsonParseException.class, () -> Greyson.readValue(Reader.of(invalidJsonString)));
    }

    @Test
    void testReadValueFromString_nullInput() {
        assertThrows(NullPointerException.class, () -> Greyson.readValue(null));
    }

    @Test
    void testReadValueFromString_emptyString() {
        // Behavior for empty string depends on JsonReader.readElement(String) implementation
        // It might throw an exception or return null/JsonNull if it's considered valid empty content.
        // Assuming it throws an exception for non-JSON content.
        assertThrows(NoSuchElementException.class, () -> Greyson.readValue(Reader.of("")).orElseThrow());
    }

    @Test
    void testReadValueFromReader_validJson() throws IOException {
        String jsonString = "[\"apple\", \"banana\"]";
        Reader reader = new StringReader(jsonString);
        JsonValue element = Greyson.readValue(reader).orElseThrow();
        assertNotNull(element);
        assertInstanceOf(JsonArray.class, element);
        JsonArray ja = (JsonArray) element;
        assertEquals(2, ja.elements().size());
        assertEquals(new JsonString("apple"), ja.elements().getFirst());
    }

    @Test
    void testReadValueFromReader_invalidJson() {
        String invalidJsonString = "[1, 2,"; // Missing closing bracket
        Reader reader = new StringReader(invalidJsonString);
        // Json.read(Reader) wraps JsonReader, which might throw during parsing.
        assertThrows(JsonParseException.class, () -> Greyson.readValue(reader));
    }

    @Test
    void testReadFromReader_ioExceptionDuringReadValue() throws IOException {
        try (Reader faultyReader = new Reader() {
            @Override
            public int read(char @NonNull [] buffer, int off, int len) throws IOException {
                throw new IOException("Simulated read error");
            }

            @Override
            public void close() {}
        }) {
            // Json.read(Reader) declares IOException
            assertThrows(IOException.class, () -> Greyson.readValue(faultyReader));
        }
    }

    @Test
    void testWriteValue_jsonArray() {
        // given
        JsonArray jsonArray = Builder.arrayBuilder()
                .add(JsonBoolean.TRUE)
                .add(JsonNull.INSTANCE)
                .build();
        // when
        var w = new StringWriter();
        Greyson.writeValue(w, jsonArray);
        var result = w.toString();
        String expectedOutput = "\\s*\\[\\s*true\\s*,\\s*null\\s*]\\s*";
        assertTrue(Pattern.matches(expectedOutput, result));
    }

    @Test
    void testWriteValue_jsonNull() {
        StringWriter writer = new StringWriter();
        Greyson.writeValue(writer, JsonNull.INSTANCE);
        assertEquals("null", writer.toString().trim());
    }

    @Test
    void testWriteBuilder_arrayBuilder() throws IOException {
        // given
        var w = new StringWriter();
        var ab = Builder.arrayBuilder().addBasic(1).addBasic(2).addBasic(true).addBasic(null);
        var ja = ab.build();
        // when
        Greyson.writeBuilder(w, ab);
        // then
        assertEquals(ja, Greyson.readValue(Reader.of(w.getBuffer())).orElseThrow());
    }

    @Test
    void testWriteBuilder_objectBuilder() throws IOException {
        // given
        var w = new StringWriter();
        var ob = Builder.objectBuilder()
                .putBasic("a", 1)
                .putBasic("b", 2)
                .putBasic("c", null)
                .putBasic("d", true);
        var jo = ob.build();
        // when
        Greyson.writeBuilder(w, ob);
        // then
        assertEquals(jo, Greyson.readValue(Reader.of(w.getBuffer())).orElseThrow());
    }

    @Test
    void testAddTimeStamp() throws IOException {
        // given
        var src = """
                {
                    "make": "BMW",
                    "year": 1971
                }
                """;
        // when
        var target = new StringWriter();
        Greyson.readBuilder(Reader.of(src))
                .filter(Builder.ObjectBuilder.class::isInstance)
                .map(Builder.ObjectBuilder.class::cast)
                .map(ob -> ob.putBasic("ts", LocalDateTime.now().toString()))
                .ifPresent(ob -> Greyson.writeBuilder(target, ob));
        // then
        var expected = Greyson.readValue(Reader.of(src)).orElseThrow();
        var result = Greyson.readValue(Reader.of(target.getBuffer())).orElseThrow();
        Pointer make = Pointer.self().member("make"), year = Pointer.self().member("year");
        assertAll(
                () -> assertEquals(make.stringOrThrow(expected), make.stringOrThrow(result)),
                () -> assertEquals(year.intValue(expected).orElseThrow(), year.intValue(result).orElseThrow()),
                () -> assertTrue(result.get("ts").isPresent())
        );
    }
}