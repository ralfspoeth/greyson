package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * {@link Pointer}s are inspired by the {@code XPath} querying facility invented
 * with and for XML. Instances are created through the factory method
 * {@link Pointer#parse(String)} where the argument is first split into components
 * using the slash {@code /} as separator and then each component matches a
 * pattern of the following form:
 * <ul>
 * <li>{@code [n]} where {@code n} is an integer denoting the index of the
 * element in JSON array. Negative values of {@code n} point to the n-th element
 * from the end of the array.</li>
 * <li>{@code name} where {@code name} is just the literal member name of a JSON
 * object</li>
 * </ul>
 * Example:
 * <p>
 * {@snippet :
 * import io.github.ralfspoeth.json.Greyson;
 * import io.github.ralfspoeth.json.data.JsonValue;
 * import io.github.ralfspoeth.json.data.JsonBoolean;
 *
 * import java.util.List;
 *
 * // given be a JSON array of five elements, two numbers and three JSON objects
 * var given = """
 *       [1, 2, {"aa": true}, {"xy": 5, "ab": 2}, {"ac": 3}]
 *       """;
 *
 * // when p matches any element beginning with the third
 * // and then each member starts with 'a'
 * Pointer p = Pointer.parse("2/aa");
 *
 * // then
 * List<JsonValue> result = Greyson.readValue(given).flatMap(p).stream().toList();
 * assert result.size() == 1; // three JSON objects...
 * assert result.getFirst() == JsonBoolean.TRUE; // the "aa" member of the third object
 *}
 * <p>
 * The second approach to constructing paths is through the fluent API, as in
 * {@snippet :
 * var p = Pointer.self().index(2).member("a");
 *}
 * <p>
 * The class implements {@link Function} such that it may be used in stream
 * pipelines easily as in
 * <p>
 * {@snippet :
 * import java.util.List;
 * import io.github.ralfspoeth.greyson.*;
 * import io.github.ralfspoeth.json.data.JsonArray;
 * import io.github.ralfspoeth.json.data.JsonValue;
 * Pointer p = Pointer.parse("..."); // @replace regex='"..."' replacement="..."
 * JsonArray a = new JsonArray(List.of()); // @replace regex='new JsonArray(List.of())' replacement='...'
 * List<JsonValue> result = a.elements().stream().flatMap(p).toList(); // @highlight substring="flatMap(p)"
 *}
 * <p>
 * Beyond navigation, a {@code Pointer} supports the following:
 * <ul>
 * <li><b>Constructing</b> &mdash; {@link #parse(String)} (Greyson's native
 * syntax with the {@code [n]} index marker), the fluent
 * {@link #self()}/{@link #member(String)}/{@link #index(int)}/{@link #regex(Pattern)}
 * builders, and {@link #fromJsonPointer(String)} for
 * <a href="https://datatracker.ietf.org/doc/html/rfc6901">RFC 6901</a>
 * expressions (JSON Patch, OpenAPI {@code $ref}, JSON Schema, &hellip;).</li>
 * <li><b>Reading</b> &mdash; {@link #apply(Object)} for the raw optional, the
 * typed convenience getters ({@link #stringValue(JsonValue)},
 * {@link #intValue(JsonValue)}, &hellip;), and {@link #require(JsonValue)} /
 * {@link #stringOrThrow(JsonValue)}, which throw a {@link java.util.NoSuchElementException}
 * naming this pointer when a required value is absent or of the wrong type.</li>
 * <li><b>Writing</b> &mdash; pointers address a location in a mutable
 * {@link Builder} tree: {@link #at(Builder)} returns the live builder there,
 * and {@link #set(Builder, JsonValue)} / {@link #remove(Builder)} modify it in
 * place. Read a document into a builder, edit it by pointer, then
 * {@link Builder#build() build} the new immutable value. Missing object members
 * are created; an {@code [n]} step requires an existing array; a {@code #regex}
 * segment is not writable.</li>
 * <li><b>Composing</b> &mdash; {@link #resolve(Pointer)} concatenates two
 * pointers, {@link #select(Selector)} fans out from where this pointer
 * resolves, and {@link #as(Function, Function)} maps the resolved value.</li>
 * </ul>
 * <p>
 * Pointers are immutable. {@link #toString()} renders a pointer in
 * {@link #parse(String)} syntax, and {@link #equals(Object)}/{@link #hashCode()}
 * compare the whole segment chain by both type and data &mdash; so a literal
 * {@code member("x")} differs from the RFC 6901 {@code fromJsonPointer("/x")},
 * which dispatch differently &mdash; making pointers usable as map keys.
 */
public sealed abstract class Pointer implements Function<JsonValue, Optional<JsonValue>> {

    private static final class Self extends Pointer {

        private static final Self INSTANCE = new Self();

        @Override
        public Optional<JsonValue> apply(JsonValue jsonValue) {
            return Optional.of(jsonValue);
        }

        @Override
        public String toString() {
            return "";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Self; // singleton, but stay robust
        }

        @Override
        public int hashCode() {
            return Self.class.hashCode();
        }
    }

    /**
     * Always start with a root path, which matches
     * the given argument and returns it in a fresh {@link Stream}.
     */
    public static Pointer self() {
        return Self.INSTANCE;
    }

    private static abstract sealed class AbstractPointer extends Pointer {
        protected final Pointer parent;

        protected AbstractPointer(Pointer parent) {
            this.parent = requireNonNull(parent);
        }

        abstract Optional<JsonValue> evalSegment(JsonValue elem);

        /**
         * To be used with {@link Stream#flatMap(Function)} in a stream
         * pipeline.
         *
         * @param value a JSON element
         * @return all children of this path applied to the given root
         */
        @Override
        public Optional<JsonValue> apply(JsonValue value) {
            return parent.apply(value).flatMap(this::evalSegment);
        }

        abstract AbstractPointer withParent(Pointer parent);

        AbstractPointer resolve(AbstractPointer p) {
            if (p.parent instanceof AbstractPointer ap) {
                return ap.resolve(p.withParent(this));
            } else {
                return p.withParent(this);
            }
        }

        // ---- write side ----------------------------------------------------
        // Writes address a mutable Builder tree. The same parent chain that
        // drives reads drives writes: evalIn reads this segment's slot from a
        // container builder, setIn/removeIn mutate it, and vivify walks down
        // once (O(depth)) creating missing object levels on the way.

        /**
         * Read this segment's slot from a <em>builder</em> container; empty when
         * the container is of the wrong shape or the slot is absent.
         */
        abstract Optional<Builder<? extends JsonValue>> evalIn(Builder<? extends JsonValue> container);

        /**
         * Set this segment's slot in {@code container} to {@code value}.
         *
         * @throws IllegalStateException if {@code container} cannot hold this segment
         */
        abstract void setIn(Builder<? extends JsonValue> container, Builder<? extends JsonValue> value);

        /**
         * Remove this segment's slot from {@code container}. Removing an absent
         * slot is a no-op.
         */
        abstract void removeIn(Builder<? extends JsonValue> container);

        /**
         * Resolve this segment's slot as a container to descend into, creating it
         * when missing. Object members are created on demand; arrays are not.
         */
        abstract Builder<? extends JsonValue> vivifyIn(Builder<? extends JsonValue> container);

        /**
         * Walk from {@code root} down to the container this segment addresses,
         * creating missing object levels. One pass, no re-navigation from the root.
         */
        Builder<? extends JsonValue> vivify(Builder<? extends JsonValue> root) {
            return vivifyIn(parent instanceof AbstractPointer ap ? ap.vivify(root) : root);
        }

        /** The container this segment's slot lives in, creating missing levels. */
        Builder<? extends JsonValue> containerFor(Builder<? extends JsonValue> root) {
            return parent instanceof AbstractPointer ap ? ap.vivify(root) : root;
        }

        /**
         * This segment rendered in {@link Pointer#parse(String)} syntax.
         */
        abstract String segment();

        @Override
        public final String toString() {
            var head = parent.toString();
            return head.isEmpty() ? segment() : head + "/" + segment();
        }
    }

    private static final class MemberPointer extends AbstractPointer {

        private final String memberName;

        private MemberPointer(String memberName, Pointer parent) {
            super(parent);
            this.memberName = requireNonNull(memberName);
        }

        @Override
        Optional<JsonValue> evalSegment(JsonValue elem) {
            return elem instanceof JsonObject(var members) ?
                    Optional.ofNullable(members.get(memberName)) :
                    Optional.empty();
        }

        @Override
        AbstractPointer withParent(Pointer parent) {
            return new MemberPointer(memberName, parent);
        }

        @Override
        Optional<Builder<? extends JsonValue>> evalIn(Builder<? extends JsonValue> container) {
            if (!(container instanceof Builder.ObjectBuilder ob)) return Optional.empty();
            Builder<? extends JsonValue> child = ob.data().get(memberName);
            return Optional.ofNullable(child);
        }

        @Override
        void setIn(Builder<? extends JsonValue> container, Builder<? extends JsonValue> value) {
            if (!(container instanceof Builder.ObjectBuilder ob)) {
                throw new IllegalStateException("member step " + this + " requires an object builder");
            }
            ob.put(memberName, value);
        }

        @Override
        void removeIn(Builder<? extends JsonValue> container) {
            if (container instanceof Builder.ObjectBuilder ob) {
                ob.remove(memberName);
            }
        }

        @Override
        Builder<? extends JsonValue> vivifyIn(Builder<? extends JsonValue> container) {
            if (!(container instanceof Builder.ObjectBuilder ob)) {
                throw new IllegalStateException("member step " + this + " requires an object builder");
            }
            // Descend into an existing aggregate; otherwise create (or replace a
            // scalar with) a fresh object so member chains materialise on write.
            Builder<? extends JsonValue> child = ob.data().get(memberName);
            if (child instanceof Builder.ObjectBuilder || child instanceof Builder.ArrayBuilder) {
                return child;
            }
            var fresh = Builder.objectBuilder();
            ob.put(memberName, fresh);
            return fresh;
        }

        @Override
        String segment() {
            return memberName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MemberPointer mp
                    && memberName.equals(mp.memberName)
                    && parent.equals(mp.parent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(MemberPointer.class, memberName, parent);
        }
    }

    private static final class IndexPointer extends AbstractPointer {
        private final int index;

        private IndexPointer(int index, Pointer parent) {
            super(parent);
            this.index = index;
        }

        @Override
        Optional<JsonValue> evalSegment(JsonValue elem) {
            if (elem instanceof JsonArray(var elements)) {
                if (index >= 0 && index < elements.size()) return Optional.of(elements.get(index));
                else if (index < 0 && 0 <= elements.size() + index)
                    return Optional.of(elements.get(elements.size() + index));
                else return Optional.empty();
            } else {
                return Optional.empty();
            }
        }

        @Override
        AbstractPointer withParent(Pointer parent) {
            return new IndexPointer(index, parent);
        }

        /**
         * The absolute index within {@code ab}, or {@code -1} when out of range.
         * Negative indices count from the end.
         */
        private int indexIn(Builder.ArrayBuilder ab) {
            int n = ab.data().size();
            int i = index < 0 ? n + index : index;
            return (i < 0 || i >= n) ? -1 : i;
        }

        // Arrays cannot be conjured from nothing (no defined length), so a
        // missing or non-array container is an error rather than a create.
        private Builder.ArrayBuilder requireArray(Builder<? extends JsonValue> container) {
            if (container instanceof Builder.ArrayBuilder ab) return ab;
            throw new IllegalStateException("index step " + this + " requires an existing array");
        }

        @Override
        Optional<Builder<? extends JsonValue>> evalIn(Builder<? extends JsonValue> container) {
            if (!(container instanceof Builder.ArrayBuilder ab)) return Optional.empty();
            int i = indexIn(ab);
            if (i < 0) return Optional.empty();
            Builder<? extends JsonValue> child = ab.data().get(i);
            return Optional.of(child);
        }

        @Override
        void setIn(Builder<? extends JsonValue> container, Builder<? extends JsonValue> value) {
            var ab = requireArray(container);
            int i = indexIn(ab);
            if (i < 0) throw new IndexOutOfBoundsException("index " + index + " out of bounds for " + this);
            ab.data().set(i, value);
        }

        @Override
        void removeIn(Builder<? extends JsonValue> container) {
            if (container instanceof Builder.ArrayBuilder ab) {
                int i = indexIn(ab);
                if (i >= 0) ab.remove(i);
            }
        }

        @Override
        Builder<? extends JsonValue> vivifyIn(Builder<? extends JsonValue> container) {
            var ab = requireArray(container);
            int i = indexIn(ab);
            if (i < 0) throw new IndexOutOfBoundsException("index " + index + " out of bounds for " + this);
            var child = ab.data().get(i);
            if (child instanceof Builder.ObjectBuilder || child instanceof Builder.ArrayBuilder) {
                return child;
            }
            var fresh = Builder.objectBuilder();
            ab.data().set(i, fresh);
            return fresh;
        }

        @Override
        String segment() {
            return "[" + index + "]";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof IndexPointer ip
                    && index == ip.index
                    && parent.equals(ip.parent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(IndexPointer.class, index, parent);
        }
    }

    /**
     * A pointer segment that matches object members by regular expression
     * and yields the value associated with the lexicographically smallest
     * matching key. The name reflects its semantics: of all keys that satisfy
     * the pattern, the {@code First} one in sorted order wins. Use
     * {@link Selector#regex(Pattern)} when you need every match instead.
     */
    private static final class First extends AbstractPointer {
        private final Pattern pattern;

        private First(Pattern pattern, Pointer parent) {
            super(parent);
            this.pattern = requireNonNull(pattern);
        }

        @Override
        Optional<JsonValue> evalSegment(JsonValue elem) {
            if (!(elem instanceof JsonObject(var members))) {
                return Optional.empty();
            }
            // Sorting by key makes the result deterministic regardless of map
            // iteration order. For {@code "bal(ance)?"} against
            // {@code {"bal": ..., "balance": ...}} the shorter key wins.
            return members.entrySet().stream()
                    .filter(e -> pattern.matcher(e.getKey()).matches())
                    .min(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue);
        }

        @Override
        AbstractPointer withParent(Pointer parent) {
            return new First(pattern, parent);
        }

        @Override
        Optional<Builder<? extends JsonValue>> evalIn(Builder<? extends JsonValue> container) {
            if (!(container instanceof Builder.ObjectBuilder ob)) return Optional.empty();
            return ob.data().entrySet().stream()
                    .filter(e -> pattern.matcher(e.getKey()).matches())
                    .min(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue);
        }

        // On a hit the target is deterministic, but on a miss there is no defined
        // key to create, and mutating "the lexicographically smallest match" is a
        // surprising thing to do silently — so this segment reads but never writes.
        private UnsupportedOperationException notWritable() {
            return new UnsupportedOperationException(
                    "regex segment " + this + " is not addressable for writes; "
                            + "resolve it to a concrete member first");
        }

        @Override
        void setIn(Builder<? extends JsonValue> container, Builder<? extends JsonValue> value) {
            throw notWritable();
        }

        @Override
        void removeIn(Builder<? extends JsonValue> container) {
            throw notWritable();
        }

        @Override
        Builder<? extends JsonValue> vivifyIn(Builder<? extends JsonValue> container) {
            throw notWritable();
        }

        @Override
        String segment() {
            return "#" + pattern.pattern();
        }

        @Override
        public boolean equals(Object o) {
            // Pattern uses identity equality, so compare its source and flags.
            return o instanceof First f
                    && pattern.pattern().equals(f.pattern.pattern())
                    && pattern.flags() == f.pattern.flags()
                    && parent.equals(f.parent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(First.class, pattern.pattern(), pattern.flags(), parent);
        }
    }

    /**
     * A pointer segment that follows the RFC 6901 dispatch rule: the same
     * token resolves as a member name when applied to a JSON object and as
     * a non-negative array index when applied to a JSON array. Built only
     * by {@link #fromJsonPointer(String)}.
     */
    private static final class JsonPointerSegment extends AbstractPointer {
        private final String token;

        private JsonPointerSegment(String token, Pointer parent) {
            super(parent);
            this.token = requireNonNull(token);
        }

        @Override
        Optional<JsonValue> evalSegment(JsonValue elem) {
            return switch (elem) {
                case JsonObject(var members) -> Optional.ofNullable(members.get(token));
                case JsonArray(var elements) -> {
                    int idx = parseRfc6901Index(token);
                    yield (idx >= 0 && idx < elements.size())
                            ? Optional.of(elements.get(idx))
                            : Optional.empty();
                }
                default -> Optional.empty();
            };
        }

        // RFC 6901 array-index ABNF: '0' or [1-9][0-9]* — no signs, no leading
        // zeros. Returns -1 for any token that does not match (including the
        // RFC's '-' "after-last" marker, which has no read-side meaning here).
        private static int parseRfc6901Index(String token) {
            int len = token.length();
            if (len == 0) return -1;
            if (len > 1 && token.charAt(0) == '0') return -1;
            int idx = 0;
            for (int i = 0; i < len; i++) {
                char c = token.charAt(i);
                if (c < '0' || c > '9') return -1;
                idx = idx * 10 + (c - '0');
                if (idx < 0) return -1; // overflow
            }
            return idx;
        }

        @Override
        AbstractPointer withParent(Pointer parent) {
            return new JsonPointerSegment(token, parent);
        }

        // Writes dispatch on the runtime container type, mirroring evalSegment, so
        // RFC 6901 strings round-trip through writes (JSON Patch interop).

        @Override
        Optional<Builder<? extends JsonValue>> evalIn(Builder<? extends JsonValue> container) {
            return switch (container) {
                case Builder.ObjectBuilder ob -> {
                    Builder<? extends JsonValue> child = ob.data().get(token);
                    yield Optional.ofNullable(child);
                }
                case Builder.ArrayBuilder ab -> {
                    int idx = parseRfc6901Index(token);
                    if (idx < 0 || idx >= ab.data().size()) yield Optional.empty();
                    Builder<? extends JsonValue> child = ab.data().get(idx);
                    yield Optional.of(child);
                }
                default -> Optional.empty();
            };
        }

        @Override
        void setIn(Builder<? extends JsonValue> container, Builder<? extends JsonValue> value) {
            switch (container) {
                case Builder.ObjectBuilder ob -> ob.put(token, value);
                case Builder.ArrayBuilder ab -> {
                    int idx = parseRfc6901Index(token);
                    int n = ab.data().size();
                    if (idx < 0 || idx > n) {
                        throw new IndexOutOfBoundsException("RFC 6901 index " + token + " at " + this);
                    }
                    if (idx == n) ab.add(value); // an index equal to the length appends
                    else ab.data().set(idx, value);
                }
                default -> throw new IllegalStateException("cannot write through " + this);
            }
        }

        @Override
        void removeIn(Builder<? extends JsonValue> container) {
            switch (container) {
                case Builder.ObjectBuilder ob -> ob.remove(token);
                case Builder.ArrayBuilder ab -> {
                    int idx = parseRfc6901Index(token);
                    if (idx >= 0 && idx < ab.data().size()) ab.remove(idx);
                }
                default -> { }
            }
        }

        @Override
        Builder<? extends JsonValue> vivifyIn(Builder<? extends JsonValue> container) {
            var existing = evalIn(container).orElse(null);
            if (existing instanceof Builder.ObjectBuilder || existing instanceof Builder.ArrayBuilder) {
                return existing;
            }
            var fresh = Builder.objectBuilder();
            setIn(container, fresh);
            return fresh;
        }

        @Override
        String segment() {
            return token;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof JsonPointerSegment jps
                    && token.equals(jps.token)
                    && parent.equals(jps.parent);
        }

        @Override
        public int hashCode() {
            return Objects.hash(JsonPointerSegment.class, token, parent);
        }
    }

    private static final Pattern INDEX_PATTERN = Pattern.compile("\\[(-?\\d+)]");

    /**
     * Instantiate a {@link Pointer} from a text.
     * The text is split into parts on {@code '/'}; each part is then dispatched
     * by syntax:
     * <ul>
     *   <li>{@code [n]} (with an optional leading {@code -} for indices counted
     *       from the end of the array) is passed to {@link #index(int)};</li>
     *   <li>parts beginning with {@code '#'} pass the remainder to
     *       {@link #regex(String)}, matching object members whose key satisfies
     *       the regular expression — when several keys match, the
     *       lexicographically smallest wins;</li>
     *   <li>everything else is treated as a literal member name and passed to
     *       {@link #member(String)}.</li>
     * </ul>
     * Example:
     * {@snippet :
     * var p = parse("a/[2]/#b.*");
     * var q = self().member("a").index(2).regex("b.*");
     * // p and q are equivalent
     *}
     * Bare digit segments are member names, not array indices. Use {@code [n]}
     * for indices; if you need an integer-named member, just write the integer
     * (e.g. {@code "1"}) or call {@link #member(String)} directly.
     *
     * @param text a pointer text
     * @return a pointer
     */
    public static Pointer parse(String text) {
        var parts = requireNonNull(text).split("/");
        Pointer prev = self();
        for (String part : parts) {
            var im = INDEX_PATTERN.matcher(part);
            if (im.matches()) {
                var index = Integer.parseInt(im.group(1));
                prev = new IndexPointer(index, prev);
            } else if (part.startsWith("#")) {
                prev = new First(Pattern.compile(part.substring(1)), prev);
            } else {
                prev = new MemberPointer(part, prev);
            }
        }
        return prev;
    }

    /**
     * Create a path element that, if applied to some {@link JsonObject},
     * returns the associated {@link JsonObject#members()} key.
     *
     * @param memberName the name of the member
     */
    public Pointer member(String memberName) {
        return new MemberPointer(memberName, this);
    }

    /**
     * Create a path that picks the element at the given index,
     * or, if negative, at the index of the reversed array,
     * given that it is applied to a JSON array.
     * {@snippet :
     * // given
     * JsonArray a = new JsonArray(Basic.of(1), Basic.of(2), Basic.of(3));
     * // when
     * var first = Pointer.index(0);
     * var last = Pointer.index(-1);
     * var end = Pointer.index(a.size()-1);
     * // then
     * assert first.apply(a).findFirst().orElseThrow().equals(Basic.of(1));
     * assert last.apply(a).findFirst().orElseThrow().equals(Basic.of(3));
     * assert end.apply(a).findFirst().orElseThrow().equals(Basic.of(3));
     *}
     *
     * @param index the index
     */
    public Pointer index(int index) {
        return new IndexPointer(index, this);
    }

    /**
     * Create a pointer segment that matches object members by regular expression.
     *
     * <p><strong>This resolves to a single value, not to every match.</strong> A
     * {@link Pointer} is single-valued by definition, so when several keys satisfy
     * the pattern it picks exactly one — the value of the <em>lexicographically
     * smallest</em> matching key (see {@code First} for the rationale) — and
     * silently discards the rest. For {@code "bal(ance)?"} against
     * {@code {"bal": 1, "balance": 2}} you get {@code 1}.</p>
     *
     * <p>If you expect more than one key to match, this is almost certainly the
     * wrong tool: use {@link Selector#regex(Pattern)}, which streams <em>every</em>
     * matching member. Reach for {@code Pointer.regex} only when at most one key
     * can match, or when "smallest key wins" is genuinely the behaviour you want.</p>
     *
     * @param pattern the regex pattern, may not be {@code null}
     * @return a pointer resolving to the lexicographically smallest matching member
     * @see Selector#regex(Pattern)
     */
    public Pointer regex(Pattern pattern) {
        return new First(pattern, this);
    }

    /**
     * Same as {@code regex(Pattern.compile(pattern))}. See {@link #regex(Pattern)}
     * for the single-match (lexicographically smallest) semantics and when to
     * prefer {@link Selector#regex(Pattern)} instead.
     *
     * @param pattern the regex pattern, may not be {@code null}
     * @return a pointer
     */
    public Pointer regex(String pattern) {
        return regex(Pattern.compile(pattern));
    }

    /**
     * Build a pointer from an
     * <a href="https://datatracker.ietf.org/doc/html/rfc6901">RFC 6901</a>
     * JSON Pointer expression.
     *
     * <p>The empty string returns {@link #self()}. Otherwise the expression
     * must start with {@code '/'}. The remaining tokens are split on
     * {@code '/'}; each token is then unescaped according to the spec
     * ({@code ~1} → {@code /}, {@code ~0} → {@code ~}, applied in that order)
     * and resolved dynamically: against a {@link JsonObject} the token is
     * looked up as a member name; against a {@link JsonArray} the token must
     * be a non-negative decimal integer (no leading zeros, no sign) and
     * indexes the array.</p>
     *
     * <p>Note that this dispatch differs from {@link #parse(String)}, which
     * decides member-vs-index statically from the syntax. The two parsers
     * exist side by side: use {@code parse} for Greyson's native syntax with
     * its {@code [n]} index marker; use {@code fromJsonPointer} to interoperate
     * with anything that emits RFC 6901 strings (JSON Patch, OpenAPI
     * {@code $ref}, JSON Schema, …).</p>
     * <p>
     * {@snippet :
     * import io.github.ralfspoeth.json.Greyson;
     * var json = """
     *       {"a/b": [10, 20, 30], "m~n": "tilde"}
     *       """;
     * var slash = Pointer.fromJsonPointer("/a~1b/2");
     * var tilde = Pointer.fromJsonPointer("/m~0n");
     * assert Greyson.readValue(java.io.Reader.of(json))
     *     .flatMap(slash).flatMap(JsonValue::decimal).orElseThrow().intValue() == 30;
     * assert Greyson.readValue(java.io.Reader.of(json))
     *     .flatMap(tilde).flatMap(JsonValue::string).orElseThrow().equals("tilde");
     *}
     *
     * @param pointer an RFC 6901 JSON Pointer string, may not be {@code null}
     * @return a pointer
     * @throws IllegalArgumentException if {@code pointer} is non-empty and does
     *                                  not start with {@code '/'}
     */
    public static Pointer fromJsonPointer(String pointer) {
        requireNonNull(pointer);
        if (pointer.isEmpty()) {
            return self();
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    "JSON Pointer must be empty or start with '/': " + pointer);
        }
        // {@code limit = -1} preserves trailing empty tokens, so a pointer like
        // "/foo/" produces {"foo", ""} rather than {"foo"} — the empty string
        // is a valid member name in RFC 6901.
        String[] tokens = pointer.substring(1).split("/", -1);
        Pointer prev = self();
        for (String token : tokens) {
            prev = new JsonPointerSegment(unescapeRfc6901(token), prev);
        }
        return prev;
    }

    // RFC 6901 §4: replace {@code ~1} first, then {@code ~0}. Order matters so
    // that {@code ~01} (the literal {@code ~1} encoded) does not get mangled
    // into a slash.
    private static String unescapeRfc6901(String token) {
        if (token.indexOf('~') < 0) return token;
        return token.replace("~1", "/").replace("~0", "~");
    }

    /**
     * Resolve the given pointer {@code p} relative to this pointer.
     * {@snippet :
     * // given
     * var json = """
     *       [{"a":1}, {"a":2, "b":3}, 4, null]
     *       """;
     * // when
     * var self = Pointer.self();
     * var index1 = self.index(1);
     * var mem_a = self.member("a");
     * // when
     * var p1a = index1.resolve(mem_a);
     * // then
     * assert Greyson.readValue(Reader.of(json))
     *     .flatMap(p1a1)
     *     .orElseThrow()
     *     .equals(Basic.of(2));
     *}
     *
     * @param p another pointer, may not be {@code null}
     * @return a new pointer combining {@code this} and {@code p}
     */
    public Pointer resolve(Pointer p) {
        if (this instanceof AbstractPointer tp && p instanceof AbstractPointer ap) {
            return tp.resolve(ap);
        } else if (p instanceof AbstractPointer ap) {
            assert this instanceof Self;
            return ap;
        } else {
            return this;
        }
    }

    // Conversion convention: primitive payloads return OptionalInt/OptionalLong/OptionalDouble; reference types return Optional<T>.

    /**
     * Search the first {@link JsonNumber} if found by this and return it as
     * {@code OptionalInt}; otherwise return {@link OptionalInt#empty()}.
     *
     * @param v the value, may not be {@code null}
     * @return the int value if found wrapped in {@link OptionalInt}, empty otherwise
     */
    public OptionalInt intValue(JsonValue v) {
        return apply(v).flatMap(JsonValue::decimal)
                .map(d -> OptionalInt.of(d.intValue()))
                .orElse(OptionalInt.empty());
    }

    /**
     * Search the first {@link JsonNumber} if found by this and return it as
     * {@code OptionalLong}; otherwise return {@link OptionalLong#empty()}.
     *
     * @param v the value, may not be {@code null}
     * @return the long value if found wrapped in {@link OptionalLong}, empty otherwise
     */
    public OptionalLong longValue(JsonValue v) {
        return apply(v).flatMap(JsonValue::decimal)
                .map(d -> OptionalLong.of(d.longValue()))
                .orElse(OptionalLong.empty());
    }

    /**
     * Search the first {@link JsonNumber} if found by this and return it as
     * {@code OptionalInt} using {@link BigDecimal#intValueExact()};
     * otherwise return {@link OptionalInt#empty()}.
     *
     * @param v the value, may not be {@code null}
     * @return the int value if found wrapped in {@link OptionalInt}, empty otherwise
     */
    public OptionalInt intExact(JsonValue v) {
        return apply(v).flatMap(JsonValue::decimal)
                .map(d -> OptionalInt.of(d.intValueExact()))
                .orElse(OptionalInt.empty());
    }

    /**
     * Search the first {@link JsonNumber} if found by this and return it as
     * {@code OptionalLong} using {@link BigDecimal#longValueExact()};
     * otherwise return {@link OptionalLong#empty()}.
     *
     * @param v the value, may not be {@code null}
     * @return the long value if found wrapped in {@link OptionalLong}, empty otherwise
     */
    public OptionalLong longExact(JsonValue v) {
        return apply(v).flatMap(JsonValue::decimal)
                .map(d -> OptionalLong.of(d.longValueExact()))
                .orElse(OptionalLong.empty());
    }

    /**
     * Search the first {@link JsonNumber} if found by this and return it as
     * {@code OptionalDouble}; otherwise return {@link OptionalDouble#empty()}.
     *
     * @param v the value, may not be {@code null}
     * @return the double value if found wrapped in {@link OptionalDouble}, empty otherwise
     */
    public OptionalDouble doubleValue(JsonValue v) {
        return apply(v).flatMap(JsonValue::decimal)
                .map(d -> OptionalDouble.of(d.doubleValue()))
                .orElse(OptionalDouble.empty());
    }

    /**
     * Search the first {@link JsonNumber} if found by this and return its value
     * as a {@link BigDecimal}; otherwise {@link Optional#empty()}. The
     * {@code Optional}-returning twin of {@link #decimalOrThrow(JsonValue)}.
     *
     * @param v the value, may not be {@code null}
     * @return the decimal value if found, empty otherwise
     */
    public Optional<BigDecimal> decimalValue(JsonValue v) {
        return apply(v).flatMap(JsonValue::decimal);
    }

    /**
     * Search the first {@link JsonBoolean} if found by this and return its value.
     *
     * @param v the value, may not be {@code null}
     * @return the boolean value if found, empty otherwise
     */
    public Optional<Boolean> booleanValue(JsonValue v) {
        return apply(v).flatMap(JsonValue::bool);
    }

    /**
     * Search the first {@link JsonString} if found by this and return its value.
     *
     * @param v the value, may not be {@code null}
     * @return the string value if found, empty otherwise
     */
    public Optional<String> stringValue(JsonValue v) {
        return apply(v).flatMap(JsonValue::string);
    }

    /**
     * Create a function to be used with {@link Optional#flatMap(Function)} which
     * first extracts an optional value from the {@link JsonValue}, as with {@link JsonValue#string()},
     * and maps the payload of the optional value using the {@code mapper} function.
     * Example:
     * {@snippet :
     * import java.time.LocalDate;
     * import io.github.ralfspoeth.json.Greyson;
     * String src = "[\"2025-05-05\"]";
     * Pointer p = Pointer.self().index(0);
     * var ld = Greyson.readValue(Reader.of(src))
     *     .flatMap(p.as(JsonValue::string, LocalDate::parse))
     *     .orElseThrow();
     * assert ld.equals(LocalDate.of(2025, 5, 5));
     *}
     *
     * @param extractor an extraction function, returning an optional value
     * @param mapper    a mapper function applied to the payload of the extractor
     * @param <T>       the return type of the mapper
     * @param <M>       some intermediary type
     * @return a function to be used with {@link Optional#flatMap(Function)}
     */
    public <T, M> Function<JsonValue, Optional<T>> as(
            Function<? super JsonValue, Optional<? extends M>> extractor,
            Function<? super M, T> mapper) {
        return v -> apply(v).flatMap(extractor).map(mapper);
    }

    /**
     * Compose this pointer with a {@link Selector}: navigate via this pointer
     * first, then apply the selector to whatever the pointer resolves to. The
     * returned function is empty when the pointer does not resolve.
     *
     * <p>Dual of {@link Selector#point(Pointer)}: where {@code point} starts
     * from a stream and narrows each element to a single sub-value,
     * {@code select} starts from a single value and fans out.</p>
     * <p>
     * {@snippet :
     * import java.util.stream.Stream;
     * JsonValue doc = null; // @replace regex="null;" replacement="..."
     * // Navigate to "data/users" and stream every element of the array there.
     * var users = Pointer.parse("data/users").select(Selector.all());
     * Stream.of(doc).flatMap(users).forEach(System.out::println);
     *}
     *
     * @param selector a selector applied to whatever this pointer resolves to
     * @return a stream-shaped function suitable for {@link Stream#flatMap(Function)}
     */
    public Selector select(Selector selector) {
        return Selector.of(v -> apply(v).stream().flatMap(selector));
    }

    /**
     * Compose this pointer with a {@link Shape}: the value this pointer
     * addresses must be present, and must satisfy {@code shape}. Violations are
     * reported underneath this pointer, so they name the offending location in
     * full.
     *
     * <p>Dual of {@link #select(Selector)}: where {@code select} fans out from
     * where this pointer resolves, {@code must} makes an assertion about it.</p>
     * <p>
     * {@snippet :
     * var shape = Pointer.parse("data/users/[0]/name").must(Shape.string());
     * shape.violations(doc).forEach(System.out::println);
     * // data/users/[0]/name: expected string, got number
     *}
     *
     * @param shape the shape the addressed value must satisfy
     * @return a shape asserting {@code shape} at this location
     */
    public Shape must(Shape shape) {
        return new Shape.At(this, shape);
    }

    // ---- writing: pointers address a location in a mutable Builder tree -----

    /**
     * The {@link Builder} this pointer addresses within {@code root}, or empty
     * when the path does not resolve. The returned builder is <em>live</em>:
     * mutating it mutates {@code root}.
     * {@snippet :
     * var b = someValue.builder();
     * Pointer.parse("data/users").at(b)
     *        .filter(Builder.ArrayBuilder.class::isInstance)
     *        .ifPresent(users -> ((Builder.ArrayBuilder) users).addBasic("new"));
     *}
     *
     * @param root the builder to navigate, may not be {@code null}
     * @return the builder at this pointer, or empty
     */
    public Optional<Builder<? extends JsonValue>> at(Builder<? extends JsonValue> root) {
        requireNonNull(root);
        return this instanceof AbstractPointer ap
                ? ap.parent.at(root).flatMap(ap::evalIn)
                : Optional.of(root); // self() addresses the root builder
    }

    /**
     * Set the value addressed by this pointer within the builder tree
     * {@code root}, which is <em>mutated in place</em>.
     *
     * <p>Missing intermediate <em>object</em> members are created on the way down
     * (a scalar in the way is replaced by a fresh object); an {@code [n]} index
     * step requires an existing array and an in-range index, and a {@code #regex}
     * segment is not writable. An RFC 6901 index equal to the array length
     * appends.</p>
     *
     * {@snippet :
     * var b = Greyson.readBuilder(reader).orElseThrow();
     * Pointer.parse("data/users/[0]/name").set(b, Basic.of("Ada"));
     * JsonValue updated = b.build();
     *}
     *
     * @param root  the builder tree to modify, may not be {@code null}
     * @param value the value to place at this pointer, may not be {@code null}
     * @throws IllegalStateException         if this is {@link #self()}, or a container
     *                                       on the path cannot hold the segment
     * @throws IndexOutOfBoundsException     if an index step is out of range
     * @throws UnsupportedOperationException if the path contains a {@code #regex} segment
     */
    public void set(Builder<? extends JsonValue> root, JsonValue value) {
        set(root, Builder.of(requireNonNull(value)));
    }

    /**
     * Set the builder addressed by this pointer within {@code root}, which is
     * <em>mutated in place</em>. See {@link #set(Builder, JsonValue)} for the
     * path-creation rules.
     *
     * @param root  the builder tree to modify, may not be {@code null}
     * @param value the builder to place at this pointer, may not be {@code null}
     */
    public void set(Builder<? extends JsonValue> root, Builder<? extends JsonValue> value) {
        requireNonNull(root);
        requireNonNull(value);
        if (this instanceof AbstractPointer ap) {
            ap.setIn(ap.containerFor(root), value);
        } else {
            throw new IllegalStateException("cannot set the root (self) pointer");
        }
    }

    /**
     * Remove the value addressed by this pointer from the builder tree
     * {@code root}, which is <em>mutated in place</em>. Removing an absent slot,
     * or one whose parent does not resolve, is a no-op — nothing is created.
     *
     * @param root the builder tree to modify, may not be {@code null}
     * @throws IllegalStateException         if this is {@link #self()}
     * @throws UnsupportedOperationException if the path contains a {@code #regex} segment
     */
    public void remove(Builder<? extends JsonValue> root) {
        requireNonNull(root);
        if (this instanceof AbstractPointer ap) {
            ap.parent.at(root).ifPresent(ap::removeIn);
        } else {
            throw new IllegalStateException("cannot remove the root (self) pointer");
        }
    }

    /**
     * The value addressed by this pointer, or throw a {@link NoSuchElementException}
     * naming the pointer. Use this instead of {@code apply(root).orElseThrow()}
     * when a miss should report <em>where</em> it missed.
     *
     * @param root the document, may not be {@code null}
     * @return the value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve
     */
    public JsonValue require(JsonValue root) {
        return apply(root).orElseThrow(() -> new NoSuchElementException("no value at " + this));
    }

    /**
     * The string addressed by this pointer, or throw a {@link NoSuchElementException}
     * that distinguishes the two failure modes: the pointer not resolving at all
     * ({@code "no value at <pointer>"}), versus resolving to a non-string value
     * ({@code "value at <pointer> is a JsonNumber, not a string"}). The message
     * thus tells you whether your <em>path</em> is wrong or your <em>type
     * assumption</em> is.
     *
     * @param root the document, may not be {@code null}
     * @return the string value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve, or resolves
     *                                to something other than a {@link JsonString}
     */
    public String stringOrThrow(JsonValue root) {
        var value = require(root);
        return value.string().orElseThrow(() -> wrongType(value, "a string"));
    }

    /**
     * The number addressed by this pointer as a {@link BigDecimal}, or throw a
     * {@link NoSuchElementException} naming the pointer (see {@link #stringOrThrow}
     * for the two-failure-mode contract).
     *
     * @param root the document, may not be {@code null}
     * @return the decimal value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve, or resolves
     *                                to something other than a {@link JsonNumber}
     */
    public BigDecimal decimalOrThrow(JsonValue root) {
        var value = require(root);
        return value.decimal().orElseThrow(() -> wrongType(value, "a number"));
    }

    /**
     * The number at this pointer as an {@code int} (truncating, like
     * {@link #intValue(JsonValue)}), or throw a {@link NoSuchElementException}
     * naming the pointer.
     *
     * @param root the document, may not be {@code null}
     * @return the int value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve to a {@link JsonNumber}
     */
    public int intOrThrow(JsonValue root) {
        return decimalOrThrow(root).intValue();
    }

    /**
     * The number at this pointer as an {@code int} using
     * {@link BigDecimal#intValueExact()} (like {@link #intExact(JsonValue)}),
     * or throw a {@link NoSuchElementException} naming the pointer.
     *
     * @param root the document, may not be {@code null}
     * @return the exact int value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve to a {@link JsonNumber}
     * @throws ArithmeticException    if the number has a nonzero fractional part
     *                                or does not fit in an {@code int}
     */
    public int intExactOrThrow(JsonValue root) {
        return decimalOrThrow(root).intValueExact();
    }

    /**
     * The number at this pointer as a {@code long} (truncating, like
     * {@link #longValue(JsonValue)}), or throw a {@link NoSuchElementException}
     * naming the pointer.
     *
     * @param root the document, may not be {@code null}
     * @return the long value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve to a {@link JsonNumber}
     */
    public long longOrThrow(JsonValue root) {
        return decimalOrThrow(root).longValue();
    }

    /**
     * The number at this pointer as a {@code long} using
     * {@link BigDecimal#longValueExact()} (like {@link #longExact(JsonValue)}),
     * or throw a {@link NoSuchElementException} naming the pointer.
     *
     * @param root the document, may not be {@code null}
     * @return the exact long value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve to a {@link JsonNumber}
     * @throws ArithmeticException    if the number has a nonzero fractional part
     *                                or does not fit in a {@code long}
     */
    public long longExactOrThrow(JsonValue root) {
        return decimalOrThrow(root).longValueExact();
    }

    /**
     * The number at this pointer as a {@code double} (like
     * {@link #doubleValue(JsonValue)}), or throw a {@link NoSuchElementException}
     * naming the pointer.
     *
     * @param root the document, may not be {@code null}
     * @return the double value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve to a {@link JsonNumber}
     */
    public double doubleOrThrow(JsonValue root) {
        return decimalOrThrow(root).doubleValue();
    }

    /**
     * The boolean addressed by this pointer, or throw a {@link NoSuchElementException}
     * naming the pointer.
     *
     * @param root the document, may not be {@code null}
     * @return the boolean value at this pointer
     * @throws NoSuchElementException if this pointer does not resolve, or resolves
     *                                to something other than a {@link JsonBoolean}
     */
    public boolean booleanOrThrow(JsonValue root) {
        var value = require(root);
        return value.bool().orElseThrow(() -> wrongType(value, "a boolean"));
    }

    // Shared diagnostic for the *OrThrow family: a value resolved but had the
    // wrong type. Names the pointer and the actual type so the message says
    // whether the path or the type assumption is at fault.
    private NoSuchElementException wrongType(JsonValue value, String expected) {
        return new NoSuchElementException(
                "value at " + this + " is a " + value.getClass().getSimpleName()
                        + ", not " + expected);
    }
}
