package io.github.ralfspoeth.json;

import io.github.ralfspoeth.json.data.Builder;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.io.JsonReader;
import io.github.ralfspoeth.json.io.JsonWriter;

import java.io.*;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Static read and write operations for JSON streams: the starting point for
 * incoming data and the end point for outgoing.
 *
 * <p>Four methods, paired along the {@link JsonValue} / {@link Builder}
 * duality:
 *
 * <ul>
 * <li>{@link #readValue(Reader)} and {@link #writeValue(Writer, JsonValue)}
 *     for immutable values — the usual case.</li>
 * <li>{@link #readBuilder(Reader)} and
 *     {@link #writeBuilder(Writer, Builder)} for their mutable twin, when the
 *     point of reading is to edit.</li>
 * </ul>
 *
 * <p>The parser produces values, so {@code readValue} is the direct path and
 * {@code readBuilder} converts. Going the other way, {@link Builder#build()}
 * turns a builder into a value, and {@link JsonValue#builder()} back again.
 *
 * <p>Each method opens a {@link io.github.ralfspoeth.json.io.JsonReader} or
 * {@link io.github.ralfspoeth.json.io.JsonWriter} and closes it, which closes
 * the {@code Reader} or {@code Writer} passed in. Use those classes directly
 * to keep one open across several documents, or to control when the stream
 * closes.
 *
 * <p>The two write methods throw {@link UncheckedIOException} rather than
 * {@link IOException}, on the assumption that they are used in fluent
 * invocation chains. The read methods do not: an {@code IOException} while
 * reading is ordinary, and a malformed document raises
 * {@link io.github.ralfspoeth.json.io.JsonParseException}, which is unchecked
 * because it describes the data rather than the stream.
 */
public class Greyson {
    private Greyson() {}

    /**
     * Parse a JSON source into a {@link Builder}, ready to be edited before
     * {@link Builder#build()} makes it a value.
     *
     * <p>The parser produces values, so this converts; prefer
     * {@link #readValue(Reader)} unless editing is the point.
     *
     * @param rdr the reader, closed when this returns
     * @return the document as a builder, or empty if the source held only
     * whitespace
     * @throws IOException when the input stream throws
     * @throws io.github.ralfspoeth.json.io.JsonParseException when the source
     * is not one complete JSON document
     */
    public static Optional<Builder<? extends JsonValue>> readBuilder(Reader rdr) throws IOException {
        try(var jr = new JsonReader(rdr)) {
            return jr.readBuilder();
        }
    }

    /**
     * Parse a JSON source into an immutable {@link JsonValue}.
     *
     * <p>An empty source is not an error: it yields an empty
     * {@link Optional}.
     *
     * @param rdr the reader, closed when this returns
     * @return the document, or empty if the source held only whitespace
     * @throws IOException when the input stream throws
     * @throws io.github.ralfspoeth.json.io.JsonParseException when the source
     * is not one complete JSON document
     */
    public static Optional<JsonValue> readValue(Reader rdr) throws IOException {
        // straight from the parser; this used to be
        // readBuilder(rdr).map(Builder::build), which walked the whole
        // document a second time on the commoner of the two paths
        try(var jr = new JsonReader(rdr)) {
            return jr.readValue();
        }
    }


    /**
     * Serialize the {@link JsonValue} to the {@link Writer}.
     * @param writer a writer, must not be {@code null}
     * @param elem the element to serialize, must not be {@code null}
     */
    public static void writeValue(Writer writer, JsonValue elem) throws UncheckedIOException {
        try(var wrt = new JsonWriter(writer)) {
            wrt.write(requireNonNull(elem));
        } catch(IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Serialize the {@link Builder} to the {@link Writer}.
     *
     * @param writer a writer, must not be {@code null}
     * @param builder the builder, must not be {@code null}
     * @throws UncheckedIOException when the writer throws an IOException
     */
    public static void writeBuilder(Writer writer, Builder<? extends JsonValue> builder) throws UncheckedIOException {
        try(var wrt = new JsonWriter(writer)) {
            wrt.write(requireNonNull(builder));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
