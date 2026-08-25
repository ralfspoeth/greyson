package io.github.ralfspoeth.json.data;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static java.util.stream.Collectors.joining;

/**
 * The array aggregate type in the JSON hierarchy.
 *
 * @param elements the elements, may not be {@code null}
 */
public record JsonArray(List<JsonValue> elements) implements Aggregate, IntFunction<JsonValue> {
    public JsonArray {
        elements = List.copyOf(elements);
    }

    public static JsonArray of(Object o) {
        return switch(o) {
            case Iterable<?> it -> ofIterable(it);
            case Object ar when ar.getClass().isArray() -> ofArray(ar);
            default -> throw new IllegalArgumentException(
                    "Conversion of %s into %s not supported".formatted(o, JsonArray.class)
            );
        };
    }

    public static JsonArray ofIterable(Iterable<?> iterable) {
        var ab = arrayBuilder();
        for (var it : iterable) {
            ab.add(JsonValue.of(it));
        }
        return ab.build();
    }

    public static JsonArray ofArray(Object o) {
        var ab = arrayBuilder();
        for (int i = 0, len = Array.getLength(o); i < len; i++) {
            ab.add(JsonValue.of(Array.get(o, i)));
        }
        return ab.build();
    }

    @Override
    public Stream<JsonValue> values() {
        return elements().stream();
    }

    @Override
    public String json() {
        return elements.stream()
                .map(JsonValue::json)
                .collect(joining(", ", "[", "]"));
    }

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public int nodes() {
        return 1 + elements.stream().mapToInt(JsonValue::nodes).sum();
    }

    @Override
    public int depth() {
        return elements.stream()
                .mapToInt(JsonValue::depth)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public JsonValue apply(int index) {
        return elements.get(index);
    }

    @Override
    public Optional<JsonValue> get(int index) {
        if(0<=index && index<elements().size()) {
            return Optional.of(elements().get(index));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return switch(o) {
            case JsonArray(var elems) -> elements.equals(elems);
            case null, default -> false;
        };
    }

    @Override
    public int hashCode() {
        return Objects.hash(elements);
    }
}
