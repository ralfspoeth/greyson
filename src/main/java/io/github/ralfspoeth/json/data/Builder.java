package io.github.ralfspoeth.json.data;

import org.jspecify.annotations.Nullable;

import java.util.*;

import static java.util.Objects.requireNonNull;

/**
 * Implements the builder pattern for both aggregate and basic types.
 * The builders implement a fluent API; any mutation operation
 * will by default return the builder itself.
 * Builders can be considered to be the mutable counterpart of their
 * immutable {@link JsonValue} cousins.
 *
 * @param <T> the type of the object built by the builder.
 */
public sealed interface Builder<T extends JsonValue> {

    /**
     * Instantiate a fresh builder from a given {@link JsonValue}.
     *
     * @param value the value, may not be {@code null}
     * @param <T>   the actual type of the value
     * @return a new builder instance
     */
    @SuppressWarnings("unchecked")
    static <T extends JsonValue> Builder<T> of(T value) {
        return (Builder<T>) switch (value) {
            case JsonObject jo -> objectBuilder(jo);
            case JsonArray ja -> arrayBuilder(ja);
            case Basic<?> b -> basicBuilder(b);
        };
    }

    /**
     * Instantiate the mutable value holder.
     *
     * @param value a value, may not be {@code null}
     */
    static BasicBuilder basicBuilder(Basic<?> value) {
        return new BasicBuilder(value);
    }

    /**
     * Instantiate an empty builder for {@code JsonObject}s.
     */
    static ObjectBuilder objectBuilder() {
        return new ObjectBuilder();
    }

    /**
     * Instantiate a builder for {@code JsonObject}s with the initial
     * name-value pairs copied from given {@code JsonObject jo}.
     * Same as {@code objectBuilder(jo.members())}
     */
    static ObjectBuilder objectBuilder(JsonObject jo) {
        return objectBuilder().insert(jo);
    }

    /**
     * Instantiate an empty builder for {@code JsonArray}s.
     */
    static ArrayBuilder arrayBuilder() {
        return new ArrayBuilder();
    }

    /**
     * Instantiate a builder with an initial set of elements copied
     * from the given {@code JsonArray}.
     * Same as {@code arrayBuilder(ja.elements())}
     *
     * @param ja a {@code JsonArray}, may not be {@code null}
     */
    static ArrayBuilder arrayBuilder(JsonArray ja) {
        return arrayBuilder().addAll(ja.elements());
    }

    /**
     * The number of direct descendants of the builder.
     */
    int size();

    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Build the immutable aggregate instance.
     */
    T build();

    /**
     * Builder implementation for {@code JsonArray}s.
     */
    final class ArrayBuilder implements Builder<JsonArray> {
        private ArrayBuilder() {}

        @Override
        public int size() {
            return data.size();
        }

        @Override
        public JsonArray build() {
            return data.isEmpty() ? new JsonArray(List.of()) :
                    new JsonArray(data
                            .stream()
                            .map(Builder::build)
                            .map(JsonValue.class::cast)
                            .toList()
                    );
        }

        private final List<Builder<? extends JsonValue>> data = new ArrayList<>();

        public List<Builder<? extends JsonValue>> data() {
            return data;
        }

        public ArrayBuilder add(JsonValue elem) {
            data.add(switch (elem) {
                case JsonObject jo -> objectBuilder(jo);
                case JsonArray ja -> arrayBuilder(ja);
                case Basic<?> b -> basicBuilder(b);
            });
            return this;
        }

        public ArrayBuilder add(Builder<? extends JsonValue> b) {
            data.add(b);
            return this;
        }

        /**
         * Add a basic value to the builder.
         *
         * @param o an object, may not be {@code null}, will be passed to {@link Basic#of(Object)}
         * @return {@code this}
         */
        public ArrayBuilder addBasic(Object o) {
            return add(Basic.of(o));
        }

        /**
         * Remove the element at the given index.
         *
         * @param index the index
         * @return {@code this}
         * @throws IndexOutOfBoundsException if the index is out of bounds
         */
        public ArrayBuilder remove(int index) {
            data.remove(index);
            return this;
        }

        /**
         * Empty out the builder.
         *
         * @return {@code this}
         */
        public ArrayBuilder clear() {
            data.clear();
            return this;
        }

        /**
         * Add all elements of the given collection to this builder.
         *
         * @param elements the elements to be appended at the end of the list, may not be {@code null}
         * @return {@code this}
         */
        public ArrayBuilder addAll(Collection<? extends JsonValue> elements) {
            elements.forEach(this::add);
            return this;
        }
    }

    /**
     * Builder implementation for {@code JsonObject}s.
     */
    final class ObjectBuilder implements Builder<JsonObject> {

        // prevent instantiation for user code
        private ObjectBuilder() {}

        // the data which will be turned into the
        // members map in the JsonObject instance later.
        private final Map<String, Builder<? extends JsonValue>> data = new TreeMap<>();

        public Map<String, Builder<? extends JsonValue>> data() {
            return data;
        }

        /**
         * Add a name-value pair to the builder.
         *
         * @param name  the name
         * @param value the value
         * @return {@code this}
         */
        public ObjectBuilder put(String name, JsonValue value) {
            data.put(requireNonNull(name), switch (requireNonNull(value)) {
                case JsonObject jo -> objectBuilder(jo);
                case JsonArray ja -> arrayBuilder(ja);
                case Basic<?> b -> basicBuilder(b);
            });
            return this;
        }

        /**
         * Add a name-builder pair.
         *
         * @param name the name
         * @param b    the builder
         * @return {@code this}
         */
        public ObjectBuilder put(String name, Builder<? extends JsonValue> b) {
            data.put(name, b);
            return this;
        }

        /**
         * Add a named basic value to this builder.
         *
         * @param name the name
         * @param o    an object, may not be {@code null}, will be passed to {@link Basic#of(Object)}
         * @return {@code this}
         */
        public ObjectBuilder putBasic(String name, @Nullable Object o) {
            return put(name, Basic.of(o));
        }

        /**
         * Updates the current mapping of names to values to the values given.
         *
         * @param map a map of names to values, may not be {@code null}.
         * @return {@code this}
         */
        public ObjectBuilder update(Map<String, ? extends JsonValue> map) {
            map.entrySet().stream()
                    .filter(e -> data.containsKey(e.getKey()))
                    .forEach(e -> put(e.getKey(), e.getValue()));
            return this;
        }

        /**
         * Same as {@code update(o.members())}.
         */
        public ObjectBuilder update(JsonObject o) {
            return update(o.members());
        }

        /**
         * Merge, that is insert or update, the given map into the current map
         * of name-value pairs.
         *
         * @param map a map of names to values, may not be {@code null}.
         * @return {@code this}
         */
        public ObjectBuilder merge(Map<String, ? extends JsonValue> map) {
            map.forEach((key, val) -> data.put(key, switch (val) {
                case JsonObject jo -> objectBuilder(jo);
                case JsonArray ja -> arrayBuilder(ja);
                case Basic<?> b -> basicBuilder(b);
            }));
            return this;
        }

        /**
         * Same as {@code merge(o.members())}.
         */
        public ObjectBuilder merge(JsonObject o) {
            return merge(o.members());
        }

        /**
         * Insert entries into the current map of name-value pairs.
         *
         * @param map a map of name-value pairs
         * @return {@code this}
         */
        public ObjectBuilder insert(Map<String, ? extends JsonValue> map) {
            map.entrySet()
                    .stream()
                    .filter(e -> !data.containsKey(e.getKey()))
                    .forEach(e -> put(e.getKey(), e.getValue()));
            return this;
        }

        public ObjectBuilder insert(JsonObject o) {
            return insert(o.members());
        }

        /**
         * Remove a single name-value pair identified by the given key.
         *
         * @param key the key.
         * @return {@code this}
         */
        public ObjectBuilder remove(String key) {
            data.remove(key);
            return this;
        }

        /**
         * Remove all name-value pairs with a name in the given set of keys.
         *
         * @param keys the keys to be removed, may not be {@code null}
         * @return {@code this}
         */
        public ObjectBuilder removeAll(Collection<String> keys) {
            keys.forEach(data::remove);
            return this;
        }

        /**
         * {@inheritDoc}
         *
         * <p>Built through {@link Map#ofEntries} rather than collected into a
         * {@link java.util.HashMap}, because {@link Map#copyOf} — which
         * {@link JsonObject}'s canonical constructor applies, and must keep
         * applying, since that constructor is public — returns its argument
         * unchanged when it is already immutable. Handing it a {@code HashMap}
         * instead means building a whole map that exists only to be copied and
         * discarded: a table plus a node per member, per object.
         *
         * <p>{@code ofEntries} rejects duplicate keys where {@code copyOf} of a
         * {@code HashMap} silently keeps the last. That is safe only because
         * {@code data} is a {@code Map} and cannot hold duplicates in the first
         * place — worth remembering if this ever stops being one, since
         * RFC 8259 duplicate names must still collapse rather than throw.
         */
        @Override
        public JsonObject build() {
            if (data.isEmpty()) {
                return new JsonObject(Map.of());
            }
            @SuppressWarnings("unchecked")
            Map.Entry<String, JsonValue>[] entries = new Map.Entry[data.size()];
            int i = 0;
            // a plain loop rather than a stream: this runs once per object in
            // a document, and a pipeline per object is not free either
            for (var e : data.entrySet()) {
                entries[i++] = Map.entry(e.getKey(), e.getValue().build());
            }
            return new JsonObject(Map.ofEntries(entries));
        }

        @Override
        public int size() {
            return data.size();
        }
    }

    /**
     * Builder implementation for {@code JsonValue}s.
     */
    final class BasicBuilder implements Builder<Basic<?>> {
        private Basic<?> value;

        private BasicBuilder(Basic<?> value) {
            setValue(value);
        }

        public BasicBuilder set(Basic<?> value) {
            setValue(value);
            return this;
        }

        public Basic<?> get() {
            return value;
        }

        private void setValue(Basic<?> value) {
            this.value = requireNonNull(value);
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public Basic<?> build() {
            return value;
        }
    }
}
