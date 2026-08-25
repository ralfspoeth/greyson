package io.github.ralfspoeth.json.data;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

/**
 * A JSON object comprised of an immutable map of name-value pairs.
 * The members map is immutable by utilizing {@link Map#copyOf(Map)}.
 *
 * @param members a non-{@code null} map of name-value pairs
 *                of non-{@code null} names and non-{@code null} values.
 */
public record JsonObject(Map<String, JsonValue> members) implements Aggregate, Function<String, @Nullable JsonValue> {

    public JsonObject {
        members = Map.copyOf(members);
    }

    /**
     * A new instance for a given map of arbitrary keys and values.
     * The keys of the map are converted into strings using
     * {@link String#valueOf(Object)}, whereas the values are converted
     * using {@link JsonValue#of(Object)}.
     * @param map the input map
     */
    public static JsonObject ofMap(Map<?, ?> map) {
        var members = map.entrySet()
                .stream()
                .collect(toMap(
                        o -> String.valueOf(o.getKey()),
                        o -> JsonValue.of(o.getValue())
                ));
        return new JsonObject(members);
    }

    /**
     * @return the number of entries of the members
     */
    @Override
    public int size() {
        return members.size();
    }

    /**
     * The depth of a JSON object is 1 plus
     * the maximum depth of the values in the member map.
     */
    @Override
    public int depth() {
        return members.values()
                .stream()
                .mapToInt(JsonValue::depth)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public int nodes() {
        return 1 + members.values()
                .stream()
                .mapToInt(JsonValue::nodes)
                .sum();
    }

    /**
     * A minimalistic one-line JSON string representation
     * of the map without any form of indentation.
     */
    @Override
    public String json() {
        return members.entrySet()
                .stream()
                .map(e -> "\"" + JsonString.escaped(e.getKey()) + "\": " + e.getValue().json())
                .collect(joining(", ", "{", "}"));
    }

    /**
     * The value for a given key; wrapped into an {@link Optional}.
     * @param key the search key
     * @return the value wrapped into an {@link Optional}
     */
    @Override
    public Optional<JsonValue> get(String key) {
        return Optional.ofNullable(members.get(key));
    }

    @Override
    public Stream<JsonValue> values() {
        return members().values().stream();
    }

    /**
     * Implements the {@link Function} from some key to
     * a nullable value.
     * @param key the function argument
     * @return the value associated with the key, or {@code null} if absent
     */
    @Override
    public @Nullable JsonValue apply(String key) {
        return members.get(key);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof JsonObject(var otherMembers) && members.equals(otherMembers);
    }

    @Override
    public int hashCode() {
        return members.hashCode();
    }
}
