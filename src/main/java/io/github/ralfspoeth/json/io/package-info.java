/**
 * Reading and writing: {@link io.github.ralfspoeth.json.io.JsonReader},
 * {@link io.github.ralfspoeth.json.io.JsonWriter} and the
 * {@link io.github.ralfspoeth.json.io.JsonParseException} a malformed document
 * raises.
 *
 * <p>Most callers never name these types. {@link io.github.ralfspoeth.json.Greyson}
 * is the entry point and wraps them in four static methods that handle the
 * {@code try}-with-resources and rethrow a failure as an
 * {@link java.io.UncheckedIOException}:
 * {@code
 * import io.github.ralfspoeth.json.data.JsonValue;
 * Optional<JsonValue>                      Greyson.readValue(Reader);
 * Optional<Builder<JsonValue>>             Greyson.readBuilder(Reader);
 * void                                     Greyson.writeValue(Writer, JsonValue);
 * void                                     Greyson.writeBuilder(Writer, Builder<?>);
 *}
 * Use the classes here directly when you want to keep a reader or writer open
 * across several documents, or to control when the underlying stream closes.
 *
 * <h2>Reading</h2>
 *
 * <p>{@link io.github.ralfspoeth.json.io.JsonReader#read()} returns an
 * {@link java.util.Optional} that is empty at end of input, so an empty source
 * is not an error — it is the absence of a document. The result is a
 * {@link io.github.ralfspoeth.json.data.Builder}; call {@code build()} for the
 * immutable value, or keep the builder when the next thing you do is edit.
 * That is why {@code Greyson} offers both {@code readValue} and
 * {@code readBuilder}: they differ only in whether that call has happened yet.
 *
 * <p>A document that is not JSON raises a
 * {@link io.github.ralfspoeth.json.io.JsonParseException} carrying the row and
 * column, because "invalid JSON" without a position is not a diagnosis. It is
 * unchecked: a malformed document is a fact about the data, not an
 * {@link java.io.IOException} about the stream, and conflating the two makes
 * both harder to handle.
 *
 * <h2>Writing</h2>
 *
 * <p>{@code JsonWriter} indents — four spaces per level, one member or array
 * element per line, with empty aggregates kept on one line as <code>{}</code>
 * and {@code []}. The compact single-line form is
 * {@link io.github.ralfspoeth.json.data.JsonValue#json()} instead, which is a
 * method on the value rather than anything in this package.
 *
 * <p>Members are written in the order the underlying map yields them and are
 * never reordered here, which is not the same thing for the two overloads.
 * {@link io.github.ralfspoeth.json.data.Builder.ObjectBuilder} keeps a
 * {@code TreeMap}, so writing a <em>builder</em> is by name and stable.
 * {@link io.github.ralfspoeth.json.data.JsonObject} holds an immutable map
 * whose iteration order derives from a salt randomised at JVM startup, so
 * <em>unspecified</em> there is not merely "different from the input": the
 * same document can come out in a different order on the next run of the
 * same build.
 *
 * <p>Indented output is therefore for reading, not for diffing, hashing or
 * golden-file comparison. To compare two documents, parse them back and use
 * {@code equals}, which is order-independent for objects.
 *
 * <h2>Nesting depth</h2>
 *
 * <p><b>The reader imposes no depth limit.</b> {@code JsonReader} holds its
 * partially-built values on an explicit heap stack rather than recursing, so
 * nesting is bounded by memory and not by the JVM's call stack — which is why
 * the JSON Test Suite's {@code i_structure_500_nested_arrays.json} parses
 * rather than overflowing. That file is an {@code i_} case, which the suite
 * leaves to the implementation, so accepting it is a deliberate position and
 * not a conformance requirement.
 *
 * <p>Anyone tempted to rewrite that stack as recursive descent — which would
 * read more clearly, and is how most parsers are written — should know that
 * this is the property being traded away.
 *
 * <p>The guarantee is the <em>reader's</em>, though, not the library's.
 * {@code JsonWriter} recurses once per level, and so do
 * {@link io.github.ralfspoeth.json.data.JsonValue#json()},
 * {@link io.github.ralfspoeth.json.data.JsonValue#depth()},
 * {@link io.github.ralfspoeth.json.data.JsonValue#nodes()} and the
 * record-generated {@code toString}, {@code equals} and {@code hashCode}. So
 * a sufficiently deep document can be parsed and then fail to be written or
 * printed. At the depths real documents reach, nothing notices.
 *
 * <h2>Resources</h2>
 *
 * <p>Both classes implement {@link java.io.Closeable} and take ownership of
 * the stream they are given: closing either closes the underlying
 * {@link java.io.Reader} or {@link java.io.Writer}, and closing a writer
 * flushes it first. Wrap them in {@code try}-with-resources, or let
 * {@code Greyson} do it.
 *
 * @see io.github.ralfspoeth.json.Greyson
 * @see io.github.ralfspoeth.json.data
 */
package io.github.ralfspoeth.json.io;
