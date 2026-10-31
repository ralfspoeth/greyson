package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.Map;

import static io.github.ralfspoeth.json.data.JsonString.escaped;
import static java.util.Objects.requireNonNull;

/**
 * A Writer for JSON values and builders.
 *
 * <p>The output is indented: four spaces per level, one member or array element
 * per line, with empty objects and arrays kept on one line as {@code {}} and
 * {@code []}. For the compact single-line form use {@link JsonValue#json()}
 * instead.
 *
 * <p>Members are written in the order the underlying map yields them, which is
 * not reordered here and is not the same thing for the two overloads:
 * {@link Builder.ObjectBuilder} keeps a {@code TreeMap}, so builder output is
 * by name, while {@link JsonObject} holds a {@link java.util.Map#copyOf} map,
 * whose iteration order is unspecified. Do not expect two runs over the same
 * {@code JsonObject} to produce byte-identical text.
 */
public class JsonWriter implements Closeable {

    private final CharSequence indentation;
    private final Writer out;

    private JsonWriter(Writer out, int indentation) {
        var tmp = new char[indentation];
        Arrays.fill(tmp, ' ');
        this.indentation = String.valueOf(tmp);
        this.out = requireNonNull(out);
    }

    /**
     * Instantiate new JsonWriter.
     * @param out the writer
     */
    public JsonWriter(Writer out) {
        this(out, 4);
    }

    /**
     * Serialize the {@link JsonValue} to the {@link Writer}.
     * @param elem the element to serialize, must not be {@code null}
     * @throws IOException when the writer throws
     */
    public void write(JsonValue elem) throws IOException {
        write(requireNonNull(elem), 0);
    }

    /*
     * Writes el at the cursor's current position: never an indentation before
     * the opening brace or bracket, since in a member position the cursor
     * already sits behind {@code "name": }. level is the depth of the line the
     * cursor is on, so children indent at level + 1 and the closing brace
     * lines up at level again.
     */
    private void write(JsonValue el, int level) throws IOException {
        switch (el) {
            case JsonObject(var members) when members.isEmpty() -> out.append("{}");
            case JsonObject(var members) -> {
                out.append('{').append('\n');
                var memberIterator = members.entrySet().iterator();
                writeMember(memberIterator.next(), level + 1);
                while (memberIterator.hasNext()) {
                    out.append(',').append('\n');
                    writeMember(memberIterator.next(), level + 1);
                }
                out.append('\n');
                indent(level);
                out.append('}');
            }
            case JsonArray(var elements) when elements.isEmpty() -> out.append("[]");
            case JsonArray(var elements) -> {
                out.append('[').append('\n');
                var itemIterator = elements.iterator();
                writeItem(itemIterator.next(), level + 1);
                while (itemIterator.hasNext()) {
                    out.append(',').append('\n');
                    writeItem(itemIterator.next(), level + 1);
                }
                out.append('\n');
                indent(level);
                out.append(']');
            }
            case Basic<?> b -> out.append(b.json());
        }
    }

    private void writeMember(Map.Entry<String, JsonValue> member, int level) throws IOException {
        write(escaped(member.getKey()), member.getValue(), level);
    }

    private void writeItem(JsonValue item, int level) throws IOException {
        indent(level);
        write(item, level);
    }

    private void write(String name, JsonValue elem, int level) throws IOException {
        indent(level);
        out.append('"').append(name).append('"').append(": ");
        write(elem, level);
    }

    /**
     * Serialize the {@link Builder} to the {@link Writer}.
     * @param builder the builder, must not be {@code null}
     * @throws IOException when the writer throws
     */
    public void write(Builder<? extends JsonValue> builder) throws IOException {
        write(requireNonNull(builder), 0);
    }

    // write with a level of indentation
    private void write(Builder<? extends JsonValue> builder, int level) throws IOException {
        switch (builder) {
            case Builder.BasicBuilder bb -> write(bb.get(), level);
            case Builder.ObjectBuilder ob when ob.data().isEmpty() -> out.append("{}");
            case Builder.ObjectBuilder ob -> {
                out.append('{').append('\n');
                var dataIterator = ob.data().entrySet().iterator();
                writeEntry(dataIterator.next(), level + 1);
                while (dataIterator.hasNext()) {
                    out.append(',').append('\n');
                    writeEntry(dataIterator.next(), level + 1);
                }
                out.append('\n');
                indent(level);
                out.append('}');
            }
            case Builder.ArrayBuilder ab when ab.data().isEmpty() -> out.append("[]");
            case Builder.ArrayBuilder ab -> {
                out.append('[').append('\n');
                var dataIterator = ab.data().iterator();
                writeItem(dataIterator.next(), level + 1);
                while (dataIterator.hasNext()) {
                    out.append(',').append('\n');
                    writeItem(dataIterator.next(), level + 1);
                }
                out.append('\n');
                indent(level);
                out.append(']');
            }
        }
    }

    private void writeEntry(Map.Entry<String, Builder<? extends JsonValue>> member, int level) throws IOException {
        write(escaped(member.getKey()), member.getValue(), level);
    }

    private void writeItem(Builder<? extends JsonValue> item, int level) throws IOException {
        indent(level);
        write(item, level);
    }

    private void write(String name, Builder<? extends JsonValue> elem, int level) throws IOException {
        indent(level);
        out.append('"').append(name).append('"').append(": ");
        write(elem, level);
    }

    // used for both builder and value methods
    private void indent(int level) throws IOException {
        for (int i = 0; i < level; i++) {
            out.append(indentation);
        }
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
    }
}