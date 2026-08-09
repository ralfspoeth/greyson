package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.*;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Gatherer;

import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toMap;


/**
 * The {@code Queries} class provides two different types of functions:
 * the {@link #asObject(JsonValue)} function which peals the most natural representation
 * out of a JSON instance, and the {@code {int|long|double}Array(JsonValue)} functions
 * which turn a {@link JsonArray} or {@link JsonNumber}s into an array of primitives
 * {@code int}, {@code long}, or {@code double}.
 * <p>
 * In addition, the class integrates JSON with the stream API:
 * the {@link java.util.stream.Collector}s {@link #toJsonArray()} and
 * {@link #toJsonObject()} aggregate streams into JSON aggregates, and the
 * {@link Gatherer}s {@link #distinctBy(Function)} and {@link #merging()}
 * provide stateful intermediate operations over streams of JSON values.
 */
public class Queries {

    // prevent instantiation
    private Queries() {}

    /**
     * Provides the most natural mapping of a JSON element
     * to their Java counterparts.
     * A {@link JsonObject object} is basically converted into its map of
     * {@link JsonObject#members() members},
     * the values of which are passed to this method recursively.
     * An {@link JsonArray array} is represented by a {@link List}
     * with this function applied to all
     * its {@link JsonArray#elements() elements}.
     * All other {@link Basic} elements are converted using the basic's
     * {@link Basic#value()} function.
     * {@link JsonNull} instances in {@link Aggregate}s are suppressed
     * if parameter skipNulls is {@code true}, allowing for the immutable
     * containers provided by {@link List#of()} and {@link Map#of()}.
     *
     * @param elem      a JSON element, may not be {@code null}
     * @param skipNulls if true, {code JsonNull}s are excluded
     * @return either a {@link Map}, a {@link List}
     * or a {@code String}, {@code BigDecimal}, {@code Boolean}, or {@code null}
     */
    public static @Nullable Object asObject(JsonValue elem, boolean skipNulls) {
        return switch (requireNonNull(elem)) {
            case JsonObject(var members) -> asMap(members, skipNulls);
            case JsonArray(var elements) -> asList(elements, skipNulls);
            case Basic<?> basic -> basic.value();
        };
    }

    /**
     * Same as {@code asObject(elem, true)}, kept mainly
     * for backward compatibility reasons
     * @param elem a JSON value; may not be {@code null}
     * @return a {@link Map}, {@link List} or a basic wrapper
     */
    public static @Nullable Object asObject(JsonValue elem) {
        return asObject(elem, true);
    }

    private static Map<String, ?> asMap(Map<String, JsonValue> members, boolean skipNulls) {
        if (!skipNulls) {
            Map<String, @Nullable Object> result = new HashMap<>();
            members.forEach((key, value) -> result.put(key, asObject(value, skipNulls)));
            return result;
        } else {
            return members.entrySet().stream()
                    .filter(e -> !JsonNull.INSTANCE.equals(e.getValue()))
                    .collect(toMap(Map.Entry::getKey,
                            e -> requireNonNull(asObject(e.getValue(), false)))
                    );
        }
    }

    // turns a JsonArray into a list
    private static List<?> asList(List<JsonValue> elements, boolean skipNulls) {
        return skipNulls ? elements.stream()
                           .filter(not(JsonNull.INSTANCE::equals))
                           .map(v -> asObject(v, true))
                           .toList()
                : elements.stream()
                  .map(e -> asObject(e, false))
                  .toList();
    }

    /**
     * Convert a {@link JsonArray} of {@link JsonNumber}s into an array
     * of {@code int}s.
     * If the
     *
     * @param elem, may be {@code null}
     * @return an array of {@code int}s; never {@code null}, zero length for elements other than a {@link JsonArray}
     */
    public static int[] intArray(@Nullable JsonValue elem) {
        return switch (elem) {
            case JsonArray(var elements) -> {
                var tmp = new int[elements.size()];
                for (int i = 0; i < tmp.length; i++) {
                    tmp[i] = elem.get(i).flatMap(JsonValue::decimal).map(BigDecimal::intValue).orElseThrow();
                }
                yield tmp;
            }
            case null, default -> new int[0];
        };
    }

    /**
     * Same as {@link #intArray(JsonValue)} but converting the elements of the
     * {@link JsonArray} into {@code long}s.
     * @param elem an element, may be {@code null}
     * @return an array of {@code long}s, never {@code null}; may have length 0
     */
    public static long[] longArray(@Nullable JsonValue elem) {
        return switch (elem) {
            case JsonArray(var elements) -> {
                var tmp = new long[elements.size()];
                for (int i = 0; i < tmp.length; i++) {
                    tmp[i] = elem.get(i).flatMap(JsonValue::decimal).map(BigDecimal::longValue).orElseThrow();
                }
                yield tmp;
            }
            case null, default -> new long[0];
        };
    }

    /**
     * Same as {@link #intArray(JsonValue)} but converting the elements of the
     * {@link JsonArray} into {@code double}s.
     */
    public static double[] doubleArray(@Nullable JsonValue elem) {
        return switch (elem) {
            case JsonArray(var elements) -> {
                var tmp = new double[elements.size()];
                for (int i = 0; i < tmp.length; i++) {
                    tmp[i] = elem.get(i).flatMap(JsonValue::decimal).map(BigDecimal::doubleValue).orElseThrow();
                }
                yield tmp;
            }
            case null, default -> new double[0];
        };
    }

    /**
     * Collect a stream of {@link JsonValue}s into a {@link JsonArray}.
     * {@snippet :
     * import java.util.*;
     * // given
     * JsonObject jo = JsonObject.ofMap(Map.of("a", 1, "b", 2));
     * // when
     * var result = jo.members()
     *     .entrySet()
     *     .stream()
     *     .sorted(Map.Entry.comparingByKey())
     *     .map(Map.Entry::getValue)
     *     .collect(toJsonArray());
     * // then
     * assert new JsonArray(List.of(Basic.of(1), Basic.of(2))).equals(result);
     *}
     */
    public static Collector<? super JsonValue, ?, JsonArray> toJsonArray() {
        return Collector.of(
                ArrayList::new,
                ArrayList::add,
                (l1, l2) -> {
                    l1.addAll(l2);
                    return l1;
                },
                JsonArray::new
        );
    }

    /**
     * Collect a stream of name-value pairs into a {@link JsonObject};
     * the mirror image of {@link #toJsonArray()}. When the same name
     * occurs more than once, the value encountered last wins.
     * {@snippet :
     * import java.util.*;
     * // given
     * var entries = List.of(
     *     Map.entry("a", Basic.of(1)),
     *     Map.entry("b", Basic.of(2))
     * );
     * // when
     * var result = entries.stream().collect(toJsonObject());
     * // then
     * assert JsonObject.ofMap(Map.of("a", 1, "b", 2)).equals(result);
     *}
     */
    public static Collector<Map.Entry<String, ? extends JsonValue>, ?, JsonObject> toJsonObject() {
        return toJsonObject(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * Collect a stream of arbitrary elements into a {@link JsonObject}
     * by extracting a member name and a member value from each element.
     * When the same name occurs more than once, the value encountered
     * last wins.
     *
     * @param keyFn   extracts the member name from a stream element
     * @param valueFn extracts the member value from a stream element
     * @param <T>     the type of the stream elements
     */
    public static <T> Collector<T, ?, JsonObject> toJsonObject(
            Function<? super T, String> keyFn,
            Function<? super T, ? extends JsonValue> valueFn
    ) {
        requireNonNull(keyFn);
        requireNonNull(valueFn);
        return Collector.of(
                Builder::objectBuilder,
                (b, t) -> b.put(keyFn.apply(t), valueFn.apply(t)),
                (b1, b2) -> {
                    b2.data().forEach(b1::put);
                    return b1;
                },
                Builder.ObjectBuilder::build
        );
    }

    /**
     * A {@link Gatherer} which drops every {@link JsonValue} whose key
     * &mdash; as extracted by the given function &mdash; has been seen
     * before, retaining the <em>first</em> occurrence. Since {@link Pointer}
     * implements {@code Function<JsonValue, Optional<JsonValue>>}, pointers
     * can serve as key extractors directly:
     * {@snippet :
     * import java.util.stream.Stream;
     * // given
     * Stream<JsonValue> records = Stream.of(); // @replace substring="Stream.of();" replacement="..."
     * // when: one record per unique "id" member
     * var unique = records
     *     .gather(distinctBy(Pointer.parse("id")))
     *     .toList();
     *}
     * Note that all values lacking the key collapse onto the first such
     * value: a pointer extracts {@link java.util.Optional#empty()} from
     * each of them, and equal keys count as duplicates.
     *
     * <p>Unlike {@link java.util.stream.Stream#distinct()}, the operation is
     * guaranteed to preserve the first occurrence in encounter order.</p>
     *
     * @param key the key extractor, may not be {@code null}
     */
    public static Gatherer<JsonValue, ?, JsonValue> distinctBy(Function<? super JsonValue, ?> key) {
        requireNonNull(key);
        return Gatherer.ofSequential(
                HashSet::new,
                Gatherer.Integrator.ofGreedy(
                        (seen, value, downstream) -> !seen.add(key.apply(value)) || downstream.push(value)
                )
        );
    }

    /**
     * A {@link Gatherer} which merges a stream of {@link JsonObject}s
     * into their running combination, emitting the merged state after
     * each element &mdash; a scan in the terminology of functional
     * programming, based on {@link Builder.ObjectBuilder#merge(JsonObject)}:
     * members of later objects overwrite same-named members of earlier ones.
     *
     * <p>Typical uses are event-sourcing or patch streams; the final state
     * is simply the last element:</p>
     * {@snippet :
     * import java.util.stream.Stream;
     * // given
     * Stream<JsonObject> patches = Stream.of(); // @replace substring="Stream.of();" replacement="..."
     * // when
     * state = patches
     *     .gather(merging())
     *     .reduce((first, second) -> second); // Optional of the last merged state
     *}
     * <p>...while keeping all intermediate states &mdash;
     * {@code patches.gather(merging()).toList()} &mdash; yields the history
     * of the accumulation.</p>
     */
    public static Gatherer<JsonObject, ?, JsonObject> merging() {
        return Gatherer.ofSequential(
                Builder::objectBuilder,
                Gatherer.Integrator.ofGreedy(
                        (builder, object, downstream) -> downstream.push(builder.merge(object).build())
                )
        );
    }
}
