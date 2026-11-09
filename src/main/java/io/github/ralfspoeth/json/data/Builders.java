package io.github.ralfspoeth.json.data;

import org.jspecify.annotations.Nullable;

import java.util.*;

import static java.util.Objects.requireNonNull;

/**
 * The two conversions between a {@link JsonValue} tree and a {@link Builder}
 * tree, done iteratively.
 *
 * <p>Both are naturally recursive and were written that way, which made
 * nesting depth a function of the JVM call stack. {@code JsonReader} has
 * always kept its own stack on the heap so that a deeply nested document
 * parses rather than overflowing — but {@code Greyson.readValue} is a parse
 * followed by {@code build()}, so the guarantee stopped at the parse and the
 * document was then walked recursively anyway. These two methods are what
 * make the property hold on every path rather than on one of them.
 *
 * <p>The shape is the usual explicit-stack post-order traversal: a frame per
 * <em>open</em> aggregate, holding an iterator over its children and an array
 * for the results. Live frames are therefore proportional to depth, not to
 * the size of the document, and the result arrays are ones the aggregates
 * need built anyway.
 */
final class Builders {

    private Builders() {}

    /** The {@link JsonValue} a builder tree describes. */
    static JsonValue build(Builder<?> root) {
        if (root instanceof Builder.BasicBuilder bb) {
            return bb.get();
        }
        var frames = new ArrayDeque<BuildFrame>();
        frames.push(new BuildFrame(root));
        for (;;) {
            var frame = frames.peek();
            if (frame.hasNext()) {
                var child = frame.next();
                if (child instanceof Builder.BasicBuilder bb) {
                    frame.add(bb.get());
                } else {
                    frames.push(new BuildFrame(child));
                }
            } else {
                frames.pop();
                var built = frame.finish();
                var parent = frames.peek();
                if (parent == null) {
                    return built;
                }
                parent.add(built);
            }
        }
    }

    /** The {@link Builder} tree describing a value; the inverse of {@link #build}. */
    static Builder<? extends JsonValue> toBuilder(JsonValue root) {
        if (root instanceof Basic<?> b) {
            return Builder.basicBuilder(b);
        }
        var frames = new ArrayDeque<ToBuilderFrame>();
        frames.push(new ToBuilderFrame(root));
        for (;;) {
            var frame = frames.peek();
            if (frame.hasNext()) {
                var child = frame.next();
                if (child instanceof Basic<?> b) {
                    frame.add(Builder.basicBuilder(b));
                } else {
                    frames.push(new ToBuilderFrame(child));
                }
            } else {
                frames.pop();
                var parent = frames.peek();
                if (parent == null) {
                    return frame.target;
                }
                parent.add(frame.target);
            }
        }
    }

    /**
     * One open aggregate on the way from builders to values.
     *
     * <p>{@code acc} is a {@code Map.Entry[]} for an object and a
     * {@code JsonValue[]} for an array, sized exactly, so {@code finish} can
     * hand it to {@link Map#ofEntries} or {@link List#of} without a further
     * copy on this side.
     */
    private static final class BuildFrame {
        private final boolean object;
        private final Iterator<?> it;
        private final Object[] acc;
        private int n;
        private @Nullable String pendingKey;

        BuildFrame(Builder<?> b) {
            if (b instanceof Builder.ObjectBuilder ob) {
                object = true;
                it = ob.data().entrySet().iterator();
                acc = new Map.Entry[ob.size()];
            } else {
                object = false;
                it = ((Builder.ArrayBuilder) b).data().iterator();
                acc = new JsonValue[b.size()];
            }
        }

        boolean hasNext() {
            return it.hasNext();
        }

        Builder<?> next() {
            if (object) {
                @SuppressWarnings("unchecked")
                var e = (Map.Entry<String, Builder<? extends JsonValue>>) it.next();
                pendingKey = e.getKey();
                return e.getValue();
            }
            return (Builder<?>) it.next();
        }

        void add(JsonValue value) {
            acc[n++] = object ? Map.entry(requireNonNull(pendingKey), value) : value;
        }

        @SuppressWarnings("unchecked")
        JsonValue finish() {
            if (object) {
                // names came from a Map, so they are unique and ofEntries --
                // which throws on duplicates where copyOf would keep the last
                // -- is safe here
                return new JsonObject(Map.ofEntries((Map.Entry<String, JsonValue>[]) acc));
            }
            return new JsonArray(List.of((JsonValue[]) acc));
        }
    }

    /** One open aggregate on the way from values to builders. */
    private static final class ToBuilderFrame {
        private final boolean object;
        private final Iterator<?> it;
        final Builder<? extends JsonValue> target;
        private @Nullable String pendingKey;

        ToBuilderFrame(JsonValue v) {
            if (v instanceof JsonObject(var members)) {
                object = true;
                it = members.entrySet().iterator();
                target = Builder.objectBuilder();
            } else {
                object = false;
                it = ((JsonArray) v).elements().iterator();
                target = Builder.arrayBuilder();
            }
        }

        boolean hasNext() {
            return it.hasNext();
        }

        JsonValue next() {
            if (object) {
                @SuppressWarnings("unchecked")
                var e = (Map.Entry<String, JsonValue>) it.next();
                pendingKey = e.getKey();
                return e.getValue();
            }
            return (JsonValue) it.next();
        }

        void add(Builder<? extends JsonValue> child) {
            if (object) {
                ((Builder.ObjectBuilder) target).put(requireNonNull(pendingKey), child);
            } else {
                ((Builder.ArrayBuilder) target).add(child);
            }
        }
    }
}
