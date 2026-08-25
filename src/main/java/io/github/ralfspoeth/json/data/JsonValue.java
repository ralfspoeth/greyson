package io.github.ralfspoeth.json.data;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The root interface of the JSON hierarchy.
 * {@code JsonValue} is a sealed interface which allows for two subtypes
 * {@link Basic} and {@link Aggregate}, which are sealed interfaces themselves.
 * All instances are immutable, have a {@link #depth()}, can report
 * the number of {@link #nodes}, and provide a minimalistic {@link #json()}
 * representation.
 * <p>
 * The payload of a JSON object is either a {@link Boolean}, a {@link BigDecimal} or a {@link String},
 * or for aggregate types a {@link List} of elements or a {@link Map} of members.
 * Every {@link JsonValue} can report its payload returning an {@link Optional} wrapper through its
 * {@link #bool()}, {@link #string()}, and {@link #decimal()} methods,
 * {@link #elements()} and {@link #members()} methods return potentially empty {@link List} or {@link Map}
 * instances, where
 * {@link JsonBoolean} returns its payload in its {@link JsonBoolean#bool()} method,
 * {@link JsonNumber} its payload in its {@link JsonNumber#decimal()} method,
 * {@link JsonString} its payload in its {@link JsonString#string()}} method,
 * {@link JsonArray} its payload in its {@link JsonArray#elements()} method, and finally
 * {@link JsonObject} its payload in its {@link JsonObject#members()} method.
 * </p>
 * <p>
 * All other methods report empty {@link List}s, empty {@link Map}s and empty {@link Optional}s.
 * Additionally, {@link JsonArray#get(int)} and {@link JsonObject#get(String)} return an
 * {@link Optional} with a value {@link Optional#isPresent() present}. These two methods
 * </p>
 * <p>
 * The intent is to allow for a fluent query API, as in
 * {@snippet :
 * JsonValue v = JsonNull.INSTANCE; // @replace replacement="..." regex="JsonNull.INSTANCE;"
 * BigDecimal a5 = v
 *     .get("a")                      // member "a" if JSON object and member present
 *     .flatMap(a -> a.get(5))        // sixth element if JSON array and index&lt;length
 *     .flatMap(JsonValue::decimal)   // decimal if it is a JSON number
 *     .orElse(BigDecimal.ZERO);      // default to 0 if any of the above is empty
 * }
 * </p>
 */
public sealed interface JsonValue extends Predicate<@Nullable JsonValue> permits Aggregate, Basic {

    /**
     * A minimalistic JSON representation of the value.
     * @return a JSON string, never {@code null}, which adheres to the JSON specification.
     */
    String json();

    /**
     * The depth of tree of nested values.
     * The depth of each leaf node is 1.
     * The depth of container nodes is 1 + the maximum depth of its children.
     * @return the depth
     */
    default int depth() {
        return 1;
    }

    /**
     * Recursively collect the number of nodes including {@code this}
     * and the children.
     * The result is 1 for all {@link Basic} nodes.
     * For a {@link JsonArray}, it is 1 + the sum of the number of nodes
     * of each if its {@link JsonArray#elements()}.
     * For a {@link JsonObject}, it is 1 + the sum of the nodes of the
     * {@link Map#values() values} of its {@link JsonObject#members()}.
     * @return the number of nodes contained in this subtree
     */
    default int nodes() {
        return 1;
    }

    /**
     * Converts an object into a JSON value.
     * If you happen to pass a {@link Builder}, its {@link Builder#build()} method will be invoked;
     * if a JSON value, that itself will be returned - both just as a safeguard.
     * A map is converted into a {@link JsonObject} using {@link JsonObject#ofMap(Map)},
     * arrays and other iterables into {@link JsonArray}s.
     * Everything else is passed to {@link Basic#of(Object)}.
     *
     * @param o an object, may be {@code null}
     * @return a JSON value; never {@code null}
     */
    static JsonValue of(@Nullable Object o) {
        return switch(o) {
            case Builder<?> b -> b.build();
            case JsonValue v -> v;
            case Map<?, ?> m -> JsonObject.ofMap(m);
            case Iterable<?> it -> JsonArray.ofIterable(it);
            case Object array when array.getClass().isArray() -> JsonArray.ofArray(array);
            case null, default -> Basic.of(o);
        };
    }

    /**
     * See {@link #equals(Object)}
     */
    @Override
    int hashCode();

    /**
     * Both methods {@code equals(Object)} and {@link #hashCode} need to be overwritten
     * such that for two {@code JsonValue} instances {@code a} and {@code b}:
     * <ul>
     *     <li>{@code a.getClass()==b.getClass()}</li>
     *     <li>for numerical values in {@code JsonNumber}s: numerical equality</li>
     *     <li>for all other basic values that {@code a.value().equals(b.value())}</li>
     *     <li>for arrays that the contained list of elements equal in order and by value,
     *         or {@code a.elements().size()==b.elements().size()} and for each {@code 0<= i < a.elements().size()}
     *         {@code a.elements().get(i).equals(b.elements().get(i))}
     *     </li>
     *     <li>for objects that they contain the same set of keys
     *     {@snippet :
     *         JsonValue a=null, b=null; // @replace replacement="..." regex="=null"
     *         a.members().keySet().equals(b.members().keySet())
     *      }
     *        and the equality of the associated values, such that
     *      {@snippet :
     *         a.members().get(k).equals(b.members().get(k))
     *      }
     *        for each {@code k}
     *     </li>
     * </ul>
     * See {@link Object#equals(Object)} and {@link Object#hashCode()}
     */
    @Override
    boolean equals(Object o);

    /**
     * The boolean value of this element if reasonable.
     * Only {@link JsonBoolean} overrides this method.
     *
     * @return an optional boolean, empty except for {@link JsonBoolean}
     */
    default Optional<Boolean> bool() {
        return Optional.empty();
    }
    /**
     * The decimal value of this element if it is a {@link JsonNumber}.
     * Only {@link JsonNumber} overrides this method.
     * @return the decimal value, or an empty optional
     */
    default Optional<BigDecimal> decimal() {
        return Optional.empty();
    }

    /**
     * The string value of this element if it is a {@link JsonString}.
     * Only {@link JsonString} overrides this method.
     * @return the string value, or an empty optional
     */
    default Optional<String> string() {
        return Optional.empty();
    }

    /**
     * Get the value at a given index in a JSON array,
     * or an empty optional if the index is out of bounds or
     * this is not an array.
     * @param index the index
     * @return the value at the given index, or an empty optional
     */
    default Optional<JsonValue> get(int index) {
        return Optional.empty();
    }

    /**
     * Get the member named "name" in this JSON object;
     * or an empty optional if this is not a JSON object or
     * there is no such member.
     * @param name the name
     * @return the value of the member, or an empty optional
     */
    default Optional<JsonValue> get(String name) {
        return Optional.empty();
    }

    /**
     * Get all the elements of this it is a JSON array,
     * or an empty list if this is not a JSON array.
     */
    default List<? extends JsonValue> elements() {
        return List.of();
    }

    /**
     * Get all the members of this it is a JSON object,
     * or an empty map if this is not JSON object.
     */
    default Map<String, JsonValue> members() {
        return Map.of();
    }

    default Stream<JsonValue> values() {
        return Stream.empty();
    }

    /**
     * If this is a {@link Basic} instance, wrap it into an {@link Optional},
     * other return an empty {@link Optional}.
     * @return this wrapped if it is a {@link Basic}, empty optional otherwise
     */
    default Optional<Basic<?>> basic() {
        return Optional.empty();
    }

    /**
     * Converts this value into a {@link Builder}
     * the {@link Builder#build()} of which has the same type as this.
     *
     * @return a builder
     */
    default Builder<?> builder() {
        return Builder.of(this);
    }

    @Override
    default boolean test(@Nullable JsonValue jsonValue) {
        return jsonValue != null && jsonValue.equals(this);
    }
}
