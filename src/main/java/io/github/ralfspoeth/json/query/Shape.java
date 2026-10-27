package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.query.Pointer.self;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

/**
 * A structural description of a {@link JsonValue}, used to <em>check</em> a
 * document ("what is wrong with it, and where?") and to <em>filter</em> a stream
 * of values ("keep the ones shaped like this").
 *
 * <p>A {@code Shape} maps a value to the {@link Violation}s it exhibits, so
 * an empty stream means the value satisfies the description:
 * {@snippet :
 * var shape = Shape.member("isin", Shape.string())
 *         .and(Shape.member("ccy", Shape.string()));
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
 * {@link Selector#numbers()}, &hellip;), a {@code Shape} filters by
 * <em>shape</em> — and {@link Selector#where(Shape)} keeps that inside the
 * query algebra rather than dropping out to {@link Stream#filter}.
 *
 * <p>The three query types compose in both directions:
 * {@link Pointer#select(Selector)} and {@link Selector#point(Pointer)} bridge
 * pointer and selector, {@link Pointer#must(Shape)} asserts a shape at a
 * location, and {@link Selector#where(Shape)} narrows a selection by shape.
 *
 * <h2>Scope</h2>
 * This is deliberately <em>not</em> a JSON Schema implementation. When a
 * document has a schema that is authoritative for the operation at hand, use a
 * schema validator; {@code Shape} is for data in the wild, where no such
 * document exists or none is being honoured. Consequently there is no schema
 * I/O in either direction and no {@code $ref} resolution — both need a schema
 * <em>document</em> to mean anything. Violation messages are therefore local
 * and self-describing: they never point at a spec, because there is none.
 *
 * <p>That test asks whether a schema governs the operation, not which keywords
 * a schema language happens to have. {@link #and(Shape)} and {@link #or(Shape)}
 * overlap with {@code allOf} and {@code anyOf} and belong here regardless,
 * because conjunction and alternation describe messy data rather than specs.
 * There is no {@code not}: negation has nothing useful to report about what it
 * expected.
 *
 * <p>A {@code Shape} also does not become a mapper to records, tempting though
 * it looks: it lists members and their types, a record lists components and
 * their types, and the two appear isomorphic. They are the two <em>endpoints</em>
 * though. The shape knows only the JSON side and the record only the Java side,
 * while everything difficult lives on the edge between them — a field defaulting
 * to a sibling or to a value from an enclosing object, {@code "%"} becoming an
 * enum constant, a string becoming a {@code Currency}. None of that is written
 * down in either endpoint, and a mechanism covering the members that do
 * correspond one-to-one would leave the rest hand-written, which is worse than
 * leaving all of it hand-written: two styles in one mapper, divided arbitrarily.
 * Mapping stays explicit code over {@link Pointer}s; reaching a record from an
 * arbitrary document needs reflection or code generation, and this library has
 * neither.
 *
 * <p>Shapes are obtained from the factory methods below and are immutable value
 * objects: equal shapes compare equal, hash consistently, and are usable as map
 * keys. The one exception is
 * {@link #satisfying(String, java.util.function.Predicate)}, which closes over a
 * lambda and so compares by identity, as does any composite containing one. The
 * implementing types are intentionally not part of the public API, so a
 * description is read through {@link #explain()} rather than by matching on its
 * structure.
 */
public sealed interface Shape extends Function<JsonValue, Stream<Shape.Violation>>
        permits TypeShape, ExactShape, OneOfShape, MatchingShape, PredicateShape,
                MemberShape, OptionalShape, ExactlyOneShape, EachShape, SizeShape,
                AtShape, AndShape, OrShape {

    /**
     * A single structural defect: what is wrong, and where.
     *
     * @param at      the location of the offending value, relative to the value
     *                the {@code Shape} was applied to
     * @param message a self-describing, one-line explanation
     */
    record Violation(Pointer at, String message) {
        public Violation {
            requireNonNull(at);
            requireNonNull(message);
        }

        /**
         * A mismatch between what a shape wanted and what it found, phrased
         * {@code "expected <wanted>, got <found>"} — the one place that wording
         * lives.
         *
         * <p>{@code found} is a {@link String} rather than a {@link JsonValue}
         * because not every mismatch is about a type: a {@code size} constraint
         * reports the count it found, having been given the right type.
         *
         * @param at     where the defect is
         * @param wanted a noun phrase reading correctly after "expected"
         * @param found  what was there instead
         */
        static Violation expected(Pointer at, String wanted, String found) {
            return new Violation(at, "expected " + wanted + ", got " + found);
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
     * shape. The stream is lazy, so callers that only need to know
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
     * A short, one-line rendering of <em>this whole shape</em>, for showing a
     * description to a human:
     * {@snippet :
     * Shape.member("isin", Shape.string()).and(Shape.member("ccy", Shape.string())).explain();
     * // "isin": string and "ccy": string
     *}
     *
     * <p>This is deliberately <em>not</em> specified as a fragment that reads
     * correctly after the word "expected". For simple shapes it happens to —
     * {@code string}, {@code (array or object)} — and those shapes do pass it to
     * {@link Violation#expected(Pointer, String, String)}. For composites it does
     * not: <em>expected "ccy": string</em> and <em>expected a and b</em> both read
     * badly, which is why {@code and} reports its parts' violations individually
     * rather than describing itself. A shape building a mismatch therefore states
     * its wanted phrase at the call site instead of assuming {@code explain()}
     * will fit.
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
     * Both this shape and {@code other}; violations of the two are
     * concatenated, lazily. Nested conjunctions are flattened.
     */
    default Shape and(Shape other) {
        requireNonNull(other);
        var parts = new ArrayList<Shape>();
        if (this instanceof AndShape(var mine)) parts.addAll(mine); else parts.add(this);
        if (other instanceof AndShape(var theirs)) parts.addAll(theirs); else parts.add(other);
        return new AndShape(parts);
    }

    /**
     * Either this shape or {@code other}; satisfied as soon as one of them is.
     * Nested alternations are flattened.
     *
     * <p>Use it for the union-typed fields real feeds produce — a member that is
     * an array in most documents and a bare object in some:
     * {@snippet :
     * var positions = Shape.member("positions", Shape.array().or(Shape.object()));
     *}
     *
     * <p>When <em>no</em> branch holds, the result is a <b>single</b> violation at
     * the location of the choice, naming the alternatives:
     * {@snippet :
     * // positions: expected array or object, got string
     *}
     * The branch violations are deliberately not reported. A value that only had
     * to satisfy one alternative would otherwise accumulate a defect per branch,
     * reading as several problems when there is one unsatisfied choice — and
     * picking a "closest" branch to blame would need a heuristic no message could
     * explain. The cost is that alternation reports more coarsely than
     * {@link #and(Shape)}: one message at the choice point rather than a pointer
     * into the offending sub-value.
     */
    default Shape or(Shape other) {
        requireNonNull(other);
        var parts = new ArrayList<Shape>();
        if (this instanceof OrShape(var mine)) parts.addAll(mine); else parts.add(this);
        if (other instanceof OrShape(var theirs)) parts.addAll(theirs); else parts.add(other);
        return new OrShape(parts);
    }

    // ---- factories --------------------------------------------------------

    /** Accepts any value; the identity of {@link #and(Shape)}. */
    static Shape anything() {
        return new AndShape(List.of());
    }

    /**
     * Accepts nothing; the identity of {@link #or(Shape)}, and so the seed for
     * folding a set of alternatives:
     * {@snippet :
     * var any = alternatives.stream().reduce(Shape.nothing(), Shape::or);
     *}
     */
    static Shape nothing() {
        return new OrShape(List.of());
    }

    static Shape type(Class<? extends JsonValue> expected) {
        return new TypeShape(expected);
    }

    static Shape string() {
        return new TypeShape(JsonString.class);
    }

    static Shape number() {
        return new TypeShape(JsonNumber.class);
    }

    static Shape bool() {
        return new TypeShape(JsonBoolean.class);
    }

    static Shape nul() {
        return new TypeShape(JsonNull.class);
    }

    static Shape object() {
        return new TypeShape(JsonObject.class);
    }

    static Shape array() {
        return new TypeShape(JsonArray.class);
    }

    /**
     * The value equals {@code expected} — for the discriminators protocols are
     * built from, such as a version tag:
     * {@snippet :
     * Shape.member("jsonrpc", Shape.exact(Basic.of("2.0")));
     * // jsonrpc: expected "2.0", got "1.0"
     *}
     *
     * <p>Equality is {@link JsonValue}'s, so numbers compare <em>numerically</em>:
     * {@code exact(Basic.of(1))} is satisfied by {@code 1}, {@code 1.0} and
     * {@code 1.00} alike, matching how {@link JsonNumber} defines equality.
     *
     * <p>Any {@link JsonValue} can be required, containers included: an object
     * matches regardless of member order, since {@link JsonObject} equality is
     * map equality, while an array is order-sensitive. Note that a failed match
     * renders both values as JSON, so requiring a large container exactly
     * produces a correspondingly large message.
     *
     * @param expected the value the input must equal, may not be {@code null}
     */
    static Shape exact(JsonValue expected) {
        return new ExactShape(expected);
    }

    /**
     * The value equals one of {@code values} — the enumerated set a field is
     * allowed to take:
     * {@snippet :
     * Shape.member("ccy", Shape.oneOf("USD", "EUR", "GBP"));
     * // ccy: expected one of "EUR", "GBP", "USD", got "usd"
     *}
     *
     * <p>Each argument goes through {@link Basic#of(Object)}, as with
     * {@link Builder.ObjectBuilder#putBasic(String, Object)}, so the common cases
     * need no wrapping. Containers are not accepted: an enumerated set of whole
     * documents is a different thing, and {@link #exact(JsonValue)} composed with
     * {@link #or(Shape)} says it if you ever need to.
     *
     * <p>Equality is {@link JsonValue}'s throughout, so numbers compare
     * numerically: {@code oneOf(1, 2)} is satisfied by {@code 1.0}. Shapes built
     * this way stay comparable, and the set makes them order-independent.
     *
     * @param values the permitted values, may not be empty
     * @throws IllegalArgumentException if {@code values} is empty, which nothing
     *                                  could satisfy
     */
    static Shape oneOf(Object... values) {
        return new OneOfShape(Stream.of(values).map(Basic::of).collect(toSet()));
    }

    /**
     * The value is a string matching {@code regex} in full.
     *
     * <p>Full match, as with {@link Pointer#regex(Pattern)} and
     * {@link Selector#regex(Pattern)}: the pattern has to cover the whole string
     * rather than occur somewhere in it, which is what a constraint almost always
     * means. Use {@code .*} at either end for a containment test.
     * {@snippet :
     * Shape.member("ccy", Shape.matching("[A-Z]{3}"));
     * // ccy: expected a string matching /[A-Z]{3}/, got "usd"
     *}
     *
     * <p>A non-string fails with {@link #string()}'s own complaint, so the
     * wording comes from one place. Unlike
     * {@link #satisfying(String, Predicate)}, shapes built this way remain
     * comparable: {@link Pattern} does not define equality, so these compare by
     * the pattern's source and flags.
     *
     * @param regex the pattern the whole string must match, may not be {@code null}
     */
    static Shape matching(Pattern regex) {
        return new MatchingShape(regex);
    }

    /** As {@link #matching(Pattern)}, compiling {@code regex} first. */
    static Shape matching(String regex) {
        return new MatchingShape(Pattern.compile(regex));
    }

    /**
     * The value satisfies {@code test}; {@code explanation} names what was
     * wanted, and appears in the violation and in {@link #explain()}.
     *
     * <p>The escape hatch for constraints on a <em>value</em>, which the other
     * factories cannot express: they check types, exact equality, membership and
     * cardinality, but say nothing about a number's magnitude, a string's form,
     * or membership of a set. MCP, for instance, narrows JSON-RPC's {@code id}
     * to a string or an <em>integer</em> rather than any number:
     * {@snippet :
     * var whole = Shape.satisfying("a whole number",
     *         v -> v instanceof JsonNumber(var n) && n.stripTrailingZeros().scale() <= 0);
     * var id = Shape.optional("id", Shape.string().or(whole));
     * // id: expected a whole number, got 1.5
     *}
     *
     * <p>Reach for it only when nothing more specific fits. Enumerated values
     * are {@link #oneOf(Object...)} and a string's form is
     * {@link #matching(Pattern)}; both keep the comparability this gives up, and
     * both say it in one call rather than three.
     *
     * <p>A predicate can only answer about the value it is handed, so the
     * violation sits at the root of that value. Scope it with the combinators to
     * place it: {@code member("ccy", satisfying(...))} reports under
     * {@code ccy}. What it reports as <em>found</em> is the value rather than its
     * type, since the type is rarely the complaint.
     *
     * <p><b>Shapes built this way are not comparable.</b> A lambda has identity
     * equality, so two {@code satisfying} shapes never compare equal even with
     * identical arguments — and neither does any composite containing one. This
     * is the one exception to shapes being value objects, and the reason to
     * prefer a more specific factory when one fits.
     *
     * @param explanation what the value should be, phrased to read after
     *                    "expected", may not be {@code null}
     * @param test        the check, may not be {@code null}
     */
    static Shape satisfying(String explanation, Predicate<JsonValue> test) {
        return new PredicateShape(explanation, test);
    }

    /**
     * The object carries {@code key}, and its value satisfies {@code shape}.
     * Naming a member <em>is</em> requiring it: if a member is of no interest,
     * do not mention it; if only its presence matters, use {@link #member(String)}.
     */
    static Shape member(String key, Shape shape) {
        return new MemberShape(key, shape);
    }

    /** The object carries {@code key}, whatever is stored there. */
    static Shape member(String key) {
        return new MemberShape(key, anything());
    }

    /**
     * Exactly one of {@code alternatives} is present, and whichever it is
     * satisfies the shape it was given. For members a document must choose
     * between — a JSON-RPC response carries {@code result} or {@code error},
     * never both and never neither:
     * {@snippet :
     * var response = Shape.exactlyOne(Map.of("result", result, "error", error))
     *         .and(Shape.member("jsonrpc", Shape.exact(Basic.of("2.0"))));
     *}
     *
     * <p>Presence is settled first, so the two ways of getting it wrong are
     * reported as themselves:
     * {@snippet :
     * // expected exactly one of "error", "result", got none
     * // expected exactly one of "error", "result", got "error", "result"
     *}
     * and when exactly one <em>is</em> present, its own violations are reported
     * in full under its key — {@code error/code: expected number, got string}.
     * That is the reason to prefer this over an exclusive-or of
     * {@link #member(String, Shape)} shapes: a branch that is present but
     * malformed is the common failure, and an alternation would collapse it into
     * "nothing matched", discarding the diagnostic that actually helps.
     *
     * @param alternatives member name to the shape its value must satisfy, may
     *                     not be empty
     * @throws IllegalArgumentException if {@code alternatives} is empty, which
     *                                  nothing could satisfy
     */
    static Shape exactlyOne(Map<String, Shape> alternatives) {
        return new ExactlyOneShape(alternatives);
    }

    /**
     * If the object carries {@code key}, its value satisfies {@code shape}; an
     * absent key is not a defect.
     *
     * <p>For fields that are genuinely optional <em>and</em> constrained — the
     * shape a protocol reaches for when absence carries its own meaning. A
     * JSON-RPC request has no {@code id} when it is a notification, and an
     * {@code id} of the wrong type must not be mistaken for one:
     * {@snippet :
     * var request = Shape.member("jsonrpc", Shape.string())
     *         .and(Shape.member("method", Shape.string()))
     *         .and(Shape.optional("id", Shape.string().or(Shape.number()).or(Shape.nul())))
     *         .and(Shape.optional("params", Shape.array().or(Shape.object())));
     *}
     *
     * <p>Contrast {@link #member(String, Shape)}, where naming a member requires
     * it. Reach for {@code optional} only when absence is acceptable <em>and</em>
     * a present value still has to fit: if the member is of no interest at all,
     * do not mention it, since {@code optional(key, anything())} constrains
     * nothing.
     *
     * @param key   the member name, may not be {@code null}
     * @param shape what the value must satisfy when the member is present
     */
    static Shape optional(String key, Shape shape) {
        return new OptionalShape(key, shape);
    }

    /** An array whose every element satisfies {@code shape}. */
    static Shape each(Shape shape) {
        return new EachShape(shape);
    }

    /** An aggregate of exactly {@code size} elements or members. */
    static Shape size(int size) {
        return new SizeShape(size, size);
    }

    /** An aggregate holding between {@code min} and {@code max}, both inclusive. */
    static Shape size(int min, int max) {
        return new SizeShape(min, max);
    }

    /** An aggregate holding at least {@code min} elements or members. */
    static Shape atLeast(int min) {
        return new SizeShape(min, Integer.MAX_VALUE);
    }
}

// ---- implementations ------------------------------------------------------
// Package-private on purpose: these carry no API promise, so their shapes stay
// free to change. Records rather than classes so equality, hashing and
// toString come for free — Shape is documented as a value object, and that
// guarantee is cheaper to keep than to hand-write six times.

/** The value must be an instance of {@code expected}. */
record TypeShape(Class<? extends JsonValue> expected) implements Shape {
    TypeShape {
        requireNonNull(expected);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        return expected.isInstance(value)
                ? Stream.empty()
                : Stream.of(Violation.expected(self(), explain(), value.typeName()));
    }

    @Override
    public String explain() {
        if(expected.equals(JsonNull.class)) return "null";
        else if(expected.equals(JsonBoolean.class)) return "true|false";
        else if(expected.equals(JsonString.class)) return "string";
        else if(expected.equals(JsonNumber.class)) return "number";
        else if(expected.equals(Basic.class)) return "null|true|false|string|number";
        else if(expected.equals(JsonObject.class)) return "object";
        else if(expected.equals(JsonArray.class)) return "array";
        else if(expected.equals(Aggregate.class)) return "array|object";
        // JsonValue itself is a legal argument to type(Class) — the union of
        // everything, and a shape that nothing can fail
        else if(expected.equals(JsonValue.class)) return "null|true|false|string|number|array|object";
        // the hierarchy is sealed, so the nine cases above are exhaustive for
        // well-typed callers; only an unchecked cast can land here
        else throw new AssertionError("not a JSON type: " + expected.getName());
    }
}

/**
 * The value must be an object carrying {@code key}, and what is stored there
 * must satisfy {@code shape}.
 */
record MemberShape(String key, Shape shape) implements Shape {
    MemberShape {
        requireNonNull(key);
        requireNonNull(shape);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // "be an object" is a precondition this shape shares with object(),
        // so let that shape phrase the complaint rather than repeating it
        if (!(value instanceof JsonObject(var members))) {
            return Shape.object().apply(value);
        }
        var member = members.get(key);
        // the violation points at the key itself, not at the object
        return member == null
                ? Stream.of(new Violation(self().member(key), "missing required member"))
                : shape.apply(member).map(v -> v.rebase(self().member(key)));
    }

    @Override
    public String explain() {
        return "\"" + key + "\": " + shape.explain();
    }
}

/** The value must equal {@code expected}. */
record ExactShape(JsonValue expected) implements Shape {
    ExactShape {
        requireNonNull(expected);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // what is "found" here is the value, not its type: being a string is no
        // defect when the complaint is that the string is the wrong one
        return expected.equals(value)
                ? Stream.empty()
                : Stream.of(Violation.expected(self(), explain(), value.json()));
    }

    @Override
    public String explain() {
        return expected.json();
    }
}

/** The value must equal one of {@code values}. Built via {@link Shape#oneOf}. */
record OneOfShape(Set<Basic<?>> values) implements Shape {
    OneOfShape {
        values = Set.copyOf(values);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("no permitted values: nothing could satisfy this");
        }
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // a set lookup rather than a scan: JsonNumber's equals and hashCode
        // agree, so 1.0 finds 1 - which is the right answer for JSON
        return values.contains(value)
                ? Stream.empty()
                : Stream.of(Violation.expected(self(), explain(), value.json()));
    }

    @Override
    public String explain() {
        // sorted so a report is deterministic, the set having no order
        return values.stream().map(JsonValue::json).sorted()
                .collect(joining(", ", "one of ", ""));
    }
}

/** The value must be a string matching {@code regex} in full. */
record MatchingShape(Pattern regex) implements Shape {
    MatchingShape {
        requireNonNull(regex);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // being a string is a precondition shared with string(), so let that
        // shape phrase the complaint rather than repeating it
        if (!(value instanceof JsonString(var s))) {
            return Shape.string().apply(value);
        }
        return regex.matcher(s).matches()
                ? Stream.empty()
                : Stream.of(Violation.expected(self(), explain(), value.json()));
    }

    @Override
    public String explain() {
        return "a string matching /" + regex.pattern() + "/";
    }

    // Pattern compares by identity, so two shapes built from the same regex
    // would otherwise be unequal - which is the whole reason this is a record of
    // its own rather than a satisfying(...) lambda.
    @Override
    public boolean equals(Object o) {
        return o instanceof MatchingShape(var other)
                && regex.pattern().equals(other.pattern())
                && regex.flags() == other.flags();
    }

    @Override
    public int hashCode() {
        return Objects.hash(MatchingShape.class, regex.pattern(), regex.flags());
    }
}

/**
 * The value must satisfy an arbitrary predicate. Not comparable: a lambda has
 * identity equality, so this is the one shape that is not a value object — see
 * {@link Shape#satisfying(String, java.util.function.Predicate)}.
 */
record PredicateShape(String explanation, Predicate<JsonValue> test) implements Shape {
    PredicateShape {
        requireNonNull(explanation);
        requireNonNull(test);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // the value is what is reported: a predicate rejecting it says nothing
        // about its type being wrong, and usually the type is fine
        return test.test(value)
                ? Stream.empty()
                : Stream.of(Violation.expected(self(), explanation, value.json()));
    }

    @Override
    public String explain() {
        return explanation;
    }
}

/**
 * If the object carries {@code key}, its value must satisfy {@code shape}. An
 * absent key is not a defect — the distinction {@link MemberShape} does not draw.
 */
record OptionalShape(String key, Shape shape) implements Shape {
    OptionalShape {
        requireNonNull(key);
        requireNonNull(shape);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // being an object is still required: "optional member" says nothing
        // about whether the container is one
        if (!(value instanceof JsonObject(var members))) {
            return Shape.object().apply(value);
        }
        var member = members.get(key);
        return member == null
                ? Stream.empty()
                : shape.apply(member).map(v -> v.rebase(self().member(key)));
    }

    @Override
    public String explain() {
        // the ? marks optionality where a reader expects it
        return "\"" + key + "\"?: " + shape.explain();
    }
}

/**
 * Exactly one of {@code alternatives} must be present, and must satisfy its own
 * shape. Built via {@link Shape#exactlyOne(Map)}.
 */
record ExactlyOneShape(Map<String, Shape> alternatives) implements Shape {
    ExactlyOneShape {
        alternatives = Map.copyOf(alternatives);
        if (alternatives.isEmpty()) {
            throw new IllegalArgumentException("no alternatives: nothing could satisfy this");
        }
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        if (!(value instanceof JsonObject(var members))) {
            return Shape.object().apply(value);
        }
        // settle presence first: it is cheap, and it decides which of the three
        // outcomes we are in before any alternative's shape is evaluated
        var present = alternatives.keySet().stream()
                .filter(members::containsKey)
                .sorted()
                .toList();
        if (present.size() == 1) {
            // exactly one: report its own violations in full, under its key,
            // rather than losing them to "nothing matched"
            var key = present.getFirst();
            return alternatives.get(key).apply(members.get(key))
                    .map(v -> v.rebase(self().member(key)));
        }
        return Stream.of(Violation.expected(self(), "exactly one of " + quoted(alternatives.keySet()),
                present.isEmpty() ? "none" : quoted(present)));
    }

    @Override
    public String explain() {
        // the full rendering names each alternative's shape; the violation above
        // deliberately uses the shorter names-only phrase, since explain() is
        // documented as a whole-shape description rather than a message fragment
        return alternatives.keySet().stream().sorted()
                .map(k -> "\"" + k + "\": " + alternatives.get(k).explain())
                .collect(joining(", ", "exactly one of ", ""));
    }

    private static String quoted(Collection<String> keys) {
        return keys.stream().sorted().map(k -> "\"" + k + "\"").collect(joining(", "));
    }
}

/** The value must be an array whose every element satisfies {@code shape}. */
record EachShape(Shape shape) implements Shape {
    EachShape {
        requireNonNull(shape);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        if (!(value instanceof JsonArray(var elements))) {
            return Shape.array().apply(value);
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
record SizeShape(int min, int max) implements Shape {
    SizeShape {
        if (min < 0) throw new IllegalArgumentException("min must not be negative: " + min);
        if (max < min) throw new IllegalArgumentException("max " + max + " is below min " + min);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        if (!(value instanceof Aggregate aggregate)) {
            return Shape.array().or(Shape.object()).apply(value);
        }
        int size = aggregate.size();
        // the type was right, so what was "found" is the count, not a type name
        return (size >= min && size <= max)
                ? Stream.empty()
                : Stream.of(Violation.expected(self(), explain(), String.valueOf(size)));
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
 * {@code shape}. Built via {@link Pointer#must(Shape)}.
 */
record AtShape(Pointer at, Shape shape) implements Shape {
    AtShape {
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

/** Every part must hold; an empty conjunction accepts anything. */
record AndShape(List<Shape> parts) implements Shape {
    AndShape {
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
                : parts.stream().map(Shape::explain).collect(joining(" and "));
    }
}

/**
 * At least one part must hold. An empty alternation holds for nothing: there is
 * no branch to satisfy, which is the dual of an empty conjunction accepting
 * everything.
 */
record OrShape(List<Shape> parts) implements Shape {
    OrShape {
        parts = List.copyOf(parts);
    }

    @Override
    public Stream<Violation> apply(JsonValue value) {
        // findAny() on each branch stops at its first defect, and anyMatch stops
        // at the first branch that holds, so a satisfied alternation never builds
        // a message at all
        boolean satisfied = parts.stream().anyMatch(part -> part.apply(value).findAny().isEmpty());
        if (satisfied) return Stream.empty();
        // one violation at the choice point — see Shape#or(Shape) for why the
        // branch violations are not propagated
        return Stream.of(Violation.expected(self(), explain(), value.typeName()));
    }

    @Override
    public String explain() {
        // parenthesised so that mixing with "and" stays unambiguous:
        // (array or object) and "id": string
        return parts.isEmpty()
                ? "nothing"
                : parts.size() == 1
                ? parts.getFirst().explain()
                : parts.stream().map(Shape::explain).collect(joining(" or ", "(", ")"));
    }
}
