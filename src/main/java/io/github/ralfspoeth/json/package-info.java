/**
 * Greyson: JSON as immutable algebraic data types, with a small query algebra
 * over them.
 *
 * <p>No annotations, no reflection, no code generation. A document is parsed
 * into a sealed hierarchy of records and handed back; what you do with it is
 * ordinary Java, which is the point. The library binds to nothing and so has
 * nothing to configure.
 *
 * <p>{@link io.github.ralfspoeth.json.Greyson} is the entry point, and it is
 * four static methods:
 * <pre>{@code
 * Optional<JsonValue>                      Greyson.readValue(Reader)
 * Optional<Builder<? extends JsonValue>>   Greyson.readBuilder(Reader)
 * void                                     Greyson.writeValue(Writer, JsonValue)
 * void                                     Greyson.writeBuilder(Writer, Builder<?>)
 * }</pre>
 *
 * <p>Reading, navigating and writing in full:
 * <pre>{@code
 * var doc = Greyson.readValue(reader).orElseThrow();
 *
 * String owner = Pointer.parse("portfolio/owner").stringOrThrow(doc);
 *
 * var b = doc.builder();
 * Pointer.parse("portfolio/owner").set(b, Basic.of("Ada Lovelace"));
 * Greyson.writeValue(writer, b.build());   // doc itself is unchanged
 * }</pre>
 *
 * <h2>The three sub-packages</h2>
 *
 * <table class="striped">
 * <caption>Where things live</caption>
 * <thead><tr><th>Package</th><th>Holds</th></tr></thead>
 * <tbody>
 * <tr><td>{@link io.github.ralfspoeth.json.data}</td>
 *     <td>the sealed {@code JsonValue} hierarchy and its mutable twin,
 *         {@code Builder}</td></tr>
 * <tr><td>{@link io.github.ralfspoeth.json.io}</td>
 *     <td>{@code JsonReader}, {@code JsonWriter}, {@code JsonParseException} —
 *         rarely named directly, since the four methods above wrap them</td></tr>
 * <tr><td>{@link io.github.ralfspoeth.json.query}</td>
 *     <td>{@code Pointer}, {@code Selector} and {@code Shape}</td></tr>
 * </tbody>
 * </table>
 *
 * <p>The query types are three functions from a {@code JsonValue}, and what
 * each returns <em>is</em> the division of labour between them:
 *
 * <table class="striped">
 * <caption>The query algebra</caption>
 * <thead><tr><th>Type</th><th>Is a function to</th><th>Answers</th></tr></thead>
 * <tbody>
 * <tr><td>{@code Pointer}</td><td>{@code Optional<JsonValue>}</td>
 *     <td>where is this one value?</td></tr>
 * <tr><td>{@code Selector}</td><td>{@code Stream<JsonValue>}</td>
 *     <td>which values are there?</td></tr>
 * <tr><td>{@code Shape}</td><td>{@code Stream<Violation>}</td>
 *     <td>what is wrong with this value, and where?</td></tr>
 * </tbody>
 * </table>
 *
 * <p>All three are immutable values — comparable, hashable, usable as map keys
 * — and every pair bridges in both directions, so a query never has to leave
 * the algebra for a hand-written loop.
 *
 * <h2>What this library is not</h2>
 *
 * <p><b>Not a record mapper.</b> Reaching an arbitrary record from an
 * arbitrary document needs reflection or code generation, and Greyson has
 * neither by choice. Mapping stays explicit code over {@code Pointer}s, where
 * the interesting cases — a field defaulting to a sibling, a coded string
 * becoming an enum constant — are written down rather than inferred.
 *
 * <p><b>Not a JSON Schema implementation.</b> When a schema is authoritative
 * for the operation at hand, use a schema validator. {@code Shape} is for data
 * in the wild, where no such document exists or none is being honoured, so its
 * messages are local and self-describing: they never point at a spec, because
 * there is none.
 *
 * <p><b>Not the fastest</b>, though closer than it was. Parsing a 568&nbsp;kB
 * document into a tree takes about 1.6&nbsp;ms against Jackson's 1.25 and
 * Gson's 1.41, allocating 3.8&nbsp;MB against 2.1 and 3.2. What is left is
 * the data model rather than the parser: Jackson binds straight into a record
 * of {@code int} and {@code String} fields, while Greyson allocates a
 * {@link io.github.ralfspoeth.json.data.Basic} per scalar and an immutable
 * map per object — the tree you then query and pattern-match. If JSON
 * throughput really is your bottleneck, use Jackson.
 *
 * <p>Both claims are checked rather than asserted:
 * <a href="https://github.com/ralfspoeth/json-benchmarks">json-benchmarks</a>
 * for the numbers, and
 * <a href="https://github.com/ralfspoeth/greyson-competition">greyson-competition</a>,
 * which runs the same schema-less tasks through all three, for the rest.
 *
 * <h2>Dependencies</h2>
 *
 * <p>None at runtime. {@code org.jspecify} is {@code requires static} — its
 * annotations are compile-time only — and the module is {@code @NullMarked},
 * so a tool that understands JSpecify can see that nothing in this API returns
 * {@code null}.
 *
 * @see <a href="https://ralfspoeth.github.io/greyson/walkthrough.html">An
 *      interactive walkthrough</a>
 * @see io.github.ralfspoeth.json.data
 * @see io.github.ralfspoeth.json.io
 * @see io.github.ralfspoeth.json.query
 */
package io.github.ralfspoeth.json;
