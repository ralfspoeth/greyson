package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.query.Pointer.self;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * A structural description of a {@link JsonValue}, used to <em>check</em> a
 * document ("what is wrong with it, and where?") and to <em>filter</em> a stream
 * of values ("keep the ones shaped like this").
 *
 * <p>A {@code Structure} maps a value to the {@link Violation}s it exhibits, so
 * an empty stream means the value satisfies the description:
 * {@snippet :
 * var shape = Structure.required("isin", "ccy")
 *         .and(Structure.member("ccy", Structure.string()));
 *
 * shape.violations(doc).forEach(System.out::println);
 * // instrument/ccy: expected string, got number
 *}
 *
 * <p>The same description also yields a {@link #predicate()} for stream work.
 * Because the violation stream is lazy, the predicate stops at the first problem
 * and never builds a message it will not use:
 * {@snippet :
 * var bonds = doc.children().filter(shape.predicate()).toList();
 *}
 * Where {@link Selector} filters by <em>type</em> ({@link Selector#objects()},
 * {@link Selector#numbers()}, &hellip;), a {@code Structure} filters by
 * <em>shape</em>.
 *
 * <h2>Scope</h2>
 * This is deliberately <em>not</em> a JSON Schema implementation. When a
 * document has a schema that is authoritative for the operation at hand, use a
 * schema validator; {@code Structure} is for data in the wild, where no such
 * document exists or none is being honoured. Consequently there is no schema
 * I/O in either direction, no {@code $ref} resolution, and no
 * {@code anyOf}-style alternation — which would make "why did this fail?"
 * ambiguous. Violation messages are therefore local and self-describing: they
 * never point at a spec, because there is none.
 *
 * <p>Structures are immutable records, so a description is inspectable data:
 * it can be pattern-matched, compared, and printed via {@link #explain()}.
 */
public sealed interface Structure extends Function<JsonValue, Stream<Structure.Violation>> {

    /**
     * A single structural defect: what is wrong, and where.
     *
     * @param at      the location of the offending value, relative to the value
     *                the {@code Structure} was applied to
     * @param message a self-describing, one-line explanation
     */
    record Violation(Pointer at, String message) {
        public Violation {
            requireNonNull(at);
            requireNonNull(message);
        }

        /** Re-root this violation underneath {@code base}. */
        Violation rebase(Pointer base) {
            return new Violation(base.resolve(at), message);
        }

        @Override
        public String toString() {
            var path = at.toString();
            return (path.isEmpty() ? "<root>" : path) + ": " + message;
        }
    }

    /**
     * The violations {@code value} exhibits, empty when it satisfies this
     * structure. The stream is lazy, so callers that only need to know
     * <em>whether</em> it is empty do not pay for the messages.
     */
    @Override
    Stream<Violation> apply(JsonValue value);

    /**
     * Alias for {@link #apply(JsonValue)}, reading better at call sites.
     */
    default Stream<Violation> violations(JsonValue value) {
        return apply(value);
    }

    /**
     * A short, one-line description of the shape, used to build violation
     * messages and to document a structure.
     */
    String explain();

    /**
     * {@code true} for values with no violations. Short-circuits at the first
     * problem.
     */
    default Predicate<JsonValue> predicate() {
        return value -> apply(value).findAny().isEmpty();
    }

    /**
     * Both this structure and {@code other}; violations of the two are
     * concatenated, lazily. Nested {@code and}s are flattened.
     */
    default Structure and(Structure other) {
        requireNonNull(other);
        var parts = new ArrayList<Structure>();
        if (this instanceof And(var mine)) parts.addAll(mine); else parts.add(this);
        if (other instanceof And(var theirs)) parts.addAll(theirs); else parts.add(other);
        return new And(parts);
    }

    // ---- implementations --------------------------------------------------

    /** The value must be an instance of {@code expected}. */
    record Type(Class<? extends JsonValue> expected) implements Structure {
        public Type {
            requireNonNull(expected);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            return expected.isInstance(value)
                    ? Stream.empty()
                    : Stream.of(new Violation(self(),
                    "expected " + explain() + ", got " + typeName(value.getClass())));
        }

        @Override
        public String explain() {
            return typeName(expected);
        }
    }

    /** The value must be an object carrying every one of {@code keys}. */
    record Required(Set<String> keys) implements Structure {
        public Required {
            keys = Set.copyOf(keys);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            if (!(value instanceof JsonObject(var members))) {
                return Stream.of(new Violation(self(),
                        "expected object with " + explain() + ", got " + typeName(value.getClass())));
            }
            // one violation per missing key, each pointing at the key itself;
            // sorted so the report is deterministic
            return keys.stream()
                    .filter(k -> !members.containsKey(k))
                    .sorted()
                    .map(k -> new Violation(self().member(k), "missing required member"));
        }

        @Override
        public String explain() {
            return keys.stream().sorted().collect(joining(", ", "required members {", "}"));
        }
    }

    /**
     * If the object carries {@code key}, its value must satisfy {@code shape}.
     * An absent key is <em>not</em> a violation — pair with
     * {@link #required(String...)} when it must be there.
     */
    record Member(String key, Structure shape) implements Structure {
        public Member {
            requireNonNull(key);
            requireNonNull(shape);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            if (!(value instanceof JsonObject(var members))) {
                return Stream.of(new Violation(self(),
                        "expected object, got " + typeName(value.getClass())));
            }
            var member = members.get(key);
            return member == null
                    ? Stream.empty() // optional by design
                    : shape.apply(member).map(v -> v.rebase(self().member(key)));
        }

        @Override
        public String explain() {
            return "\"" + key + "\": " + shape.explain();
        }
    }

    /** The value must be an array whose every element satisfies {@code shape}. */
    record Each(Structure shape) implements Structure {
        public Each {
            requireNonNull(shape);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            if (!(value instanceof JsonArray(var elements))) {
                return Stream.of(new Violation(self(),
                        "expected array, got " + typeName(value.getClass())));
            }
            return IntStream.range(0, elements.size())
                    .boxed()
                    .flatMap(i -> shape.apply(elements.get(i)).map(v -> v.rebase(self().index(i))));
        }

        @Override
        public String explain() {
            return "each element " + shape.explain();
        }
    }

    /**
     * The value must be an {@link Aggregate} holding between {@code min} and
     * {@code max} elements or members, both inclusive.
     */
    record Size(int min, int max) implements Structure {
        public Size {
            if (min < 0) throw new IllegalArgumentException("min must not be negative: " + min);
            if (max < min) throw new IllegalArgumentException("max " + max + " is below min " + min);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            if (!(value instanceof Aggregate aggregate)) {
                return Stream.of(new Violation(self(),
                        "expected array or object, got " + typeName(value.getClass())));
            }
            int size = aggregate.size();
            return (size >= min && size <= max)
                    ? Stream.empty()
                    : Stream.of(new Violation(self(), "expected " + explain() + ", got " + size));
        }

        @Override
        public String explain() {
            if (min == max) return min + " element(s)";
            return max == Integer.MAX_VALUE
                    ? "at least " + min + " element(s)"
                    : "between " + min + " and " + max + " element(s)";
        }
    }

    /**
     * The value must resolve at {@code at}, and what is found there must satisfy
     * {@code shape}. Lets a description reach deep without nesting.
     */
    record At(Pointer at, Structure shape) implements Structure {
        public At {
            requireNonNull(at);
            requireNonNull(shape);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            return at.apply(value)
                    .map(found -> shape.apply(found).map(v -> v.rebase(at)))
                    .orElseGet(() -> Stream.of(new Violation(at, "no value at this location")));
        }

        @Override
        public String explain() {
            var path = at.toString();
            return (path.isEmpty() ? "<root>" : path) + " " + shape.explain();
        }
    }

    /** Every part must hold; an empty {@code And} accepts anything. */
    record And(List<Structure> parts) implements Structure {
        public And {
            parts = List.copyOf(parts);
        }

        @Override
        public Stream<Violation> apply(JsonValue value) {
            // flatMap keeps this lazy, so predicate() still short-circuits
            return parts.stream().flatMap(part -> part.apply(value));
        }

        @Override
        public String explain() {
            return parts.isEmpty()
                    ? "anything"
                    : parts.stream().map(Structure::explain).collect(joining(" and "));
        }
    }

    // ---- factories --------------------------------------------------------

    /** Accepts any value; the identity of {@link #and(Structure)}. */
    static Structure anything() {
        return new And(List.of());
    }

    static Structure type(Class<? extends JsonValue> expected) {
        return new Type(expected);
    }

    static Structure string() {
        return new Type(JsonString.class);
    }

    static Structure number() {
        return new Type(JsonNumber.class);
    }

    static Structure bool() {
        return new Type(JsonBoolean.class);
    }

    static Structure nul() {
        return new Type(JsonNull.class);
    }

    static Structure object() {
        return new Type(JsonObject.class);
    }

    static Structure array() {
        return new Type(JsonArray.class);
    }

    /** The value is an object carrying every one of {@code keys}. */
    static Structure required(String... keys) {
        return new Required(Set.of(keys));
    }

    /** If {@code key} is present, its value satisfies {@code shape}. */
    static Structure member(String key, Structure shape) {
        return new Member(key, shape);
    }

    /** An array whose every element satisfies {@code shape}. */
    static Structure each(Structure shape) {
        return new Each(shape);
    }

    /** An aggregate of exactly {@code size} elements or members. */
    static Structure size(int size) {
        return new Size(size, size);
    }

    /** An aggregate holding between {@code min} and {@code max}, both inclusive. */
    static Structure size(int min, int max) {
        return new Size(min, max);
    }

    /** An aggregate holding at least {@code min} elements or members. */
    static Structure atLeast(int min) {
        return new Size(min, Integer.MAX_VALUE);
    }

    /** {@code at} resolves, and the value found there satisfies {@code shape}. */
    static Structure at(Pointer at, Structure shape) {
        return new At(at, shape);
    }

    /** {@code JsonString} &rarr; {@code "string"}, and so on. */
    private static String typeName(Class<?> type) {
        var name = type.getSimpleName();
        return (name.startsWith("Json") ? name.substring(4) : name).toLowerCase(Locale.ROOT);
    }
}
