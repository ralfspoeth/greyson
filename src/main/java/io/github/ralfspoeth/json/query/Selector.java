package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.*;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * A function from {@link JsonValue} to {@code Stream<JsonValue>} for
 * extracting <em>multiple</em> values from a JSON tree. Where
 * {@link Pointer} navigates to a single value, {@code Selector} fans out:
 * an array becomes a stream of its elements, an object becomes a stream
 * of its members satisfying some criterion, a non-matching value becomes
 * an empty stream.
 *
 * <p>{@code Selector} implements
 * {@link Function Function&lt;JsonValue, Stream&lt;JsonValue&gt;&gt;}, so
 * instances drop straight into {@link Stream#flatMap(Function)}:</p>
 *
 * {@snippet :
 * import io.github.ralfspoeth.json.Greyson;
 * var _ = Greyson.readValue(src).stream()
 *     .flatMap(Selector.all())               // fan out an array, or accept a single value
 *     .flatMap(Selector.objects())           // keep only JSON objects
 *     .flatMap(Selector.regex("addr(ess)?")) // and only their address-ish members
 *     .toList();
 *}
 *
 * <p>The factories fall into three groups:</p>
 * <ul>
 *   <li><b>Structural</b> &mdash; {@link #all()} flattens arrays;
 *       {@link #range(int, int)} slices arrays;
 *       {@link #regex(Pattern)} matches object members by key.</li>
 *   <li><b>Type filters</b> &mdash; {@link #objects()}, {@link #arrays()},
 *       {@link #strings()}, {@link #numbers()}, {@link #booleans()},
 *       {@link #nulls()}, plus the abstract supertypes {@link #basics()}
 *       and {@link #aggregates()}.</li>
 *   <li><b>Composition</b> &mdash; {@link #point(Pointer)} narrows each value
 *       in the stream to a sub-value via a pointer (the dual of
 *       {@link Pointer#select(Selector)}); {@link #presentValues(Function)}
 *       extracts a typed value from each selected value and drops the absent
 *       ones, yielding a {@code Stream<T>}.</li>
 * </ul>
 *
 * <p>Selector instances are immutable and safe to share across threads;
 * the parameter-free factories return cached singletons.</p>
 */
public sealed abstract class Selector implements Function<JsonValue, Stream<JsonValue>> {

    private static final class AllSelector extends Selector {

        private static final AllSelector INSTANCE = new AllSelector();

        @Override
        public Stream<JsonValue> apply(JsonValue jsonValue) {
            return jsonValue instanceof JsonArray(var elements) ?
                    elements.stream() :
                    Stream.of(jsonValue);
        }
    }

    /**
     * Every {@code Selector} starts with this special selector
     * which creates a stream of the elements of a {@link JsonArray}
     * or else the singleton stream of the {@link JsonValue} which it
     * is applied to.
     *
     * @return the selector which returns the contents of the {@link JsonValue}
     */
    public static Selector all() {
        return AllSelector.INSTANCE;
    }


    private static final class RangeSelector extends Selector {

        private final int min, max;

        private RangeSelector(int min, int max) {
            this.min = min;
            this.max = (max > min ? max : Integer.MAX_VALUE);
        }

        @Override
        public Stream<JsonValue> apply(JsonValue v) {
            return v instanceof JsonArray(var array) ? IntStream
                                                       .range(min, max)
                                                       .takeWhile(i -> i < array.size())
                                                       .mapToObj(array::get) :
                    Stream.empty();
        }


    }

    private static final class RegexSelector extends Selector {

        private final Pattern regex;

        private RegexSelector(Pattern regex) {
            this.regex = requireNonNull(regex);
        }

        @Override
        public Stream<JsonValue> apply(JsonValue elem) {
            return elem instanceof JsonObject(Map<String, JsonValue> members) ? members
                                                  .entrySet()
                                                  .stream()
                                                  .filter(e -> regex.matcher(e.getKey()).matches())
                                                  .map(Map.Entry::getValue) :
                    Stream.of();
        }

    }

    /**
     * A selector that filters its input by Java type. Each instance yields a
     * singleton stream containing the input when it is an instance of the
     * configured type, and an empty stream otherwise — the standard "filter
     * step" shape that drops cleanly into {@link Stream#flatMap(Function)}.
     *
     * <p>Type selectors are stateless and shared as cached singletons via the
     * factory methods {@link #basics()}, {@link #aggregates()},
     * {@link #arrays()}, {@link #objects()}, {@link #strings()},
     * {@link #booleans()}, {@link #numbers()}, and {@link #nulls()}.</p>
     */
    private static final class TypeSelector extends Selector {
        private static final TypeSelector BASIC_SELECTOR = new TypeSelector(Basic.class);
        private static final TypeSelector NUMBER_SELECTOR = new TypeSelector(JsonNumber.class);
        private static final TypeSelector STRING_SELECTOR = new TypeSelector(JsonString.class);
        private static final TypeSelector BOOLEAN_SELECTOR = new TypeSelector(JsonBoolean.class);
        private static final TypeSelector NULL_SELECTOR = new TypeSelector(JsonNull.class);
        private static final TypeSelector AGGREGATE_SELECTOR = new TypeSelector(Aggregate.class);
        private static final TypeSelector ARRAY_SELECTOR = new TypeSelector(JsonArray.class);
        private static final TypeSelector OBJECT_SELECTOR = new TypeSelector(JsonObject.class);

        private final Class<? extends JsonValue> type;
        private TypeSelector(Class<? extends JsonValue> type) {
            this.type = type;
        }

        @Override
        public Stream<JsonValue> apply(JsonValue elem) {
            return type.isInstance(elem) ? Stream.of(elem) : Stream.empty();
        }
    }

    // ---- Type-based selectors ---------------------------------------------
    // Use after {@link #all()} or another fan-out step to narrow the stream
    // to a single JSON value type. All factories return cached singletons.

    /**
     * Yields its input if it is a {@link Basic} value
     * ({@link JsonString}, {@link JsonNumber}, {@link JsonBoolean}, or
     * {@link JsonNull}); empty stream otherwise.
     */
    public static Selector basics() {
        return TypeSelector.BASIC_SELECTOR;
    }

    /**
     * Yields its input if it is an {@link Aggregate} value
     * ({@link JsonArray} or {@link JsonObject}); empty stream otherwise.
     */
    public static Selector aggregates() {
        return TypeSelector.AGGREGATE_SELECTOR;
    }

    /**
     * Yields its input if it is a {@link JsonArray}; empty stream otherwise.
     * @return a selector
     */
    public static Selector arrays() {
        return TypeSelector.ARRAY_SELECTOR;
    }

    /**
     * Yields its input if it is a {@link JsonObject}; empty stream otherwise.
     * @return a selector
     */
    public static Selector objects() {
        return TypeSelector.OBJECT_SELECTOR;
    }

    /** Yields its input if it is a {@link JsonString}; empty stream otherwise. */
    public static Selector strings() {
        return TypeSelector.STRING_SELECTOR;
    }

    /** Yields its input if it is a {@link JsonBoolean}; empty stream otherwise. */
    public static Selector booleans() {
        return TypeSelector.BOOLEAN_SELECTOR;
    }

    /** Yields its input if it is a {@link JsonNumber}; empty stream otherwise. */
    public static Selector numbers() {
        return TypeSelector.NUMBER_SELECTOR;
    }

    /** Yields its input if it is a {@link JsonNull}; empty stream otherwise. */
    public static Selector nulls() {
        return TypeSelector.NULL_SELECTOR;
    }


    private static final class FunctionWrapper extends Selector {
        private final Function<JsonValue, Stream<JsonValue>> function;
        private FunctionWrapper(Function<JsonValue, Stream<JsonValue>> function) {
            this.function = requireNonNull(function);
        }
        @Override
        public Stream<JsonValue> apply(JsonValue elem) {
            return function.apply(elem);
        }
    }


    static Selector of(Function<JsonValue, Stream<JsonValue>> selector) {
        return new FunctionWrapper(selector);
    }


    /**
     * Create a path that, given a JSON array {@code a},
     * returns a stream of its elements from index {@code startInclusive}
     * to {@code endExclusive} exclusively.
     * If {@code endExclusive} is negative, it is replaced by {@link Integer#MAX_VALUE}.
     *
     * @param startInclusive the first index, inclusively
     * @param endExclusive   the last index, exclusively
     */
    public static Selector range(int startInclusive, int endExclusive) {
        return new RangeSelector(startInclusive, endExclusive);
    }

    /**
     * Create a path which, when applied to a {@link JsonObject},
     * produces a stream of {@link JsonValue}s if its {@link JsonObject#members()}
     * the keys of which match the given regular expression.
     * {@snippet :
     * // given
     * var o = new JsonObject(Map.of("a1", Basic.of(1), "a2", Basic.of(2), "b", Basic.of(3)));
     * // when
     * var aN = Selector.regex("a[0-9]"); // a0, a1, a2, ..., a9, not b
     * // then
     * var l = Stream.of(o).flatMap(aN).toList();
     * var c = List.of(Basic.of(1), Basic.of(2));
     * assert l.equals(c);
     *}
     *
     * <p>This streams <em>every</em> matching member. When you instead want a
     * single value, use {@link Pointer#regex(Pattern)}, which resolves to the
     * lexicographically smallest matching key.</p>
     *
     * @param regex a regular expression matched against the keys of
     * @see Pointer#regex(Pattern)
     */
    public static Selector regex(Pattern regex) {
        return new RegexSelector(regex);
    }

    /**
     * Same as {@code regex(Pattern.compile(regex));}
     *
     * @param regex the regular expression
     * @return a selector
     */
    public static Selector regex(String regex) {
        return regex(Pattern.compile(regex));
    }

    /**
     * Mapping function that uses an {@code extractor} for some derived value,
     * and a {@code mapper} which maps the extracted value to the target domain.
     * @param extractor a function that derives some property of the given input
     * @param mapper a function that maps the result of the {@code extractor} into the target domain
     * @return a function
     * @param <T> the type of the resulting stream contents
     * @param <M> an intermediary type
     */
    public <T, M> Function<JsonValue, Stream<T>> as(
            Function<? super JsonValue, Optional<? extends M>> extractor,
            Function<? super M, T> mapper) {
        return v -> apply(v).
                flatMap(x -> extractor.apply(x).map(mapper).stream());
    }

    /**
     * Compose this selector with a {@link Pointer}: apply this selector to
     * fan out into a stream, then narrow each element via the pointer. Values
     * for which the pointer does not resolve are dropped from the stream.
     *
     * <p>Dual of {@link Pointer#select(Selector)}: where {@code select} starts
     * from a single value and fans out, {@code point} starts from a stream and
     * narrows each element to a single sub-value.</p>
     *
     * {@snippet :
     * import java.util.stream.Stream;
     * JsonValue users = null; // @replace regex="null;" replacement="..."
     * // From an array of user objects, pull out every "email" that is present.
     * var emails = Selector.all().point(Pointer.self().member("email"));
     * Stream.of(users).flatMap(emails).forEach(System.out::println);
     *}
     *
     * @param p a pointer applied to each value this selector produces
     * @return a stream-shaped function suitable for {@link Stream#flatMap(Function)}
     */
    public Selector point(Pointer p) {
        return of(v -> apply(v).flatMap(x -> p.apply(x).stream()));
    }

    /**
     * Compose this selector with an {@code Optional}-returning extractor: apply
     * this selector to fan out into a stream, map each value with {@code f}, and
     * keep only the results that are {@linkplain Optional#isPresent() present}.
     *
     * <p>This is the multi-value analogue of {@link Pointer#as(Function, Function)}:
     * it folds the usual {@code flatMap(v -> f.apply(v).stream())} bridge into a
     * single step and exits the {@code JsonValue} world into a {@code Stream<T>}.</p>
     *
     * {@snippet :
     * import io.github.ralfspoeth.json.query.Pointer;
     * import java.util.stream.Stream;
     * JsonValue doc = null; // @replace regex="null;" replacement="..."
     * // every session's "id" string, absent ones skipped
     * var ids = Pointer.parse("sessions").select(Selector.all())
     *         .presentValues(s -> Pointer.parse("id").stringValue(s));
     * Stream.of(doc).flatMap(ids).forEach(System.out::println);
     *}
     *
     * @param f   an extractor mapping each selected value to an optional result
     * @param <T> the type of the extracted values
     * @return a stream-shaped function suitable for {@link Stream#flatMap(Function)}
     */
    public <T> Function<JsonValue, Stream<T>> presentValues(Function<JsonValue, Optional<T>> f) {
        return v -> apply(v).map(f).filter(Optional::isPresent).map(Optional::get);
    }

    /**
     * Narrow this selection to the values satisfying {@code shape}. Where the
     * type selectors ({@link #objects()}, {@link #numbers()}, &hellip;) filter by
     * <em>type</em>, this filters by <em>shape</em> — and it stays a
     * {@code Selector}, so the selection can be narrowed further.
     * <p>
     * {@snippet :
     * import io.github.ralfspoeth.json.query.Structure;
     * JsonValue doc = null; // @replace regex="null;" replacement="..."
     * // every element that is an object with a string "isin" and a string "ccy"
     * var wellFormed = Selector.all().where(
     *         Structure.member("isin", Structure.string())
     *                 .and(Structure.member("ccy", Structure.string())));
     * Stream.of(doc).flatMap(wellFormed).forEach(System.out::println);
     *}
     * <p>
     * Because a {@link Structure}'s violation stream is lazy, each test stops at
     * the first defect and never builds a message that is thrown away.
     *
     * @param shape the structure a value must satisfy to be kept
     * @return a selector yielding only the values that satisfy {@code shape}
     */
    public Selector where(Structure shape) {
        var satisfied = requireNonNull(shape).predicate();
        return of(v -> apply(v).filter(satisfied));
    }
}
