/**
 * The data model: a sealed hierarchy of immutable records, and the mutable
 * {@link io.github.ralfspoeth.json.data.Builder} that is its twin.
 *
 * <p>A parsed document is a tree of
 * {@link io.github.ralfspoeth.json.data.JsonValue}s, and the hierarchy is
 * sealed, so the six concrete types below are all there will ever be:
 * <pre>
 * JsonValue                       sealed
 * ├── Basic                       sealed
 * │   ├── JsonBoolean             record(boolean)
 * │   ├── JsonNumber              record(BigDecimal)
 * │   ├── JsonString              record(String)
 * │   └── JsonNull                record()
 * └── Aggregate                   sealed
 *     ├── JsonArray               record(List&lt;JsonValue&gt;)
 *     └── JsonObject              record(Map&lt;String, JsonValue&gt;)
 * </pre>
 * Sealing is the point rather than a detail: a {@code switch} over a
 * {@code JsonValue} is provably exhaustive, so there is no {@code default}
 * branch standing in for cases nobody thought about, and adding a case to the
 * hierarchy would be a compile error at every site that must handle it.
 *
 * <h2>Choices worth knowing</h2>
 *
 * <p><b>Numbers are {@link java.math.BigDecimal}, never {@code double}.</b>
 * Binary floating point loses digits JSON kept, so a value is held exactly as
 * written — scale and trailing zeros included — and {@code 18250.00}
 * serializes back as {@code 18250.00} rather than in scientific notation.
 * Equality is nonetheless <em>numeric</em>: {@code 18250.00} equals
 * {@code 18250}, with {@code hashCode} kept consistent by stripping trailing
 * zeros.
 *
 * <p><b>Immutability is structural, not a convention.</b> The canonical
 * constructors of {@link io.github.ralfspoeth.json.data.JsonArray} and
 * {@link io.github.ralfspoeth.json.data.JsonObject} copy their inputs unless
 * those are already immutable, so a tree cannot be changed through a reference
 * the caller kept. There is no mutating API to misuse and no {@code deepCopy}
 * to remember.
 *
 * <p><b>Editing goes through {@link io.github.ralfspoeth.json.data.Builder},
 * the mutable twin.</b> Every value yields a builder and every builder builds
 * a value, and the round trip is the identity:
 * {@snippet :
 * JsonValue v = null; // @replace regex="null;" replacement="..."
 * assert v.equals(v.builder().build());
 * }
 * So an edit is: take a builder, change it, build. The original is untouched
 * because it was never reachable from the copy.
 *
 * <p><b>No {@code null} in the API.</b> Anything that might have no value
 * returns an {@link java.util.Optional}, an empty {@code List} or an empty
 * {@code Map}. JSON {@code null} is
 * {@link io.github.ralfspoeth.json.data.JsonNull#INSTANCE} — a value in the
 * data model, distinct from the absence of one.
 *
 * <p><b>Aggregates are functions.</b> {@code JsonObject} implements
 * {@code Function<String, JsonValue>} and {@code JsonArray} implements
 * {@code IntFunction<JsonValue>}, so they drop into stream pipelines without
 * an adapter.
 *
 * <h2>Two things about object members</h2>
 *
 * <p><b>Duplicate names collapse, last one wins, silently.</b>
 * {@code {"a": 1, "a": 2}} parses to a single member {@code a=2};
 * <a href="https://datatracker.ietf.org/doc/html/rfc8259#section-4">RFC
 * 8259</a> declares names SHOULD be unique and the behaviour otherwise
 * "unpredictable", so this is one legal reading — but it is lossy, and there
 * is no way to detect that it happened.
 *
 * <p><b>Member order is not preserved.</b> {@code JsonObject} holds its
 * members in a {@link java.util.Map#copyOf} map of unspecified iteration
 * order, so a document's text is not reproduced byte for byte. Round-tripping
 * preserves meaning, not layout.
 *
 * @see io.github.ralfspoeth.json.io
 * @see io.github.ralfspoeth.json.query
 */
package io.github.ralfspoeth.json.data;
