# Greyson

A small, opinionated JSON library for Java.

```xml
<dependency>
    <groupId>io.github.ralfspoeth</groupId>
    <artifactId>greyson</artifactId>
    <version>2.0.1</version>
</dependency>
```

No annotations. No reflection. No code generation. Greyson parses JSON
into immutable algebraic data types and gets out of your way. The whole
library, with dependencies, fits in under 100 kB.

![Greyson](greyson.png)

If your project already pulls in Jackson, you don't need Greyson. But if
you've ever wanted a JSON library that you can read end-to-end in an
afternoon, that doesn't ask you to decorate your records, and that
treats `Optional` and `Stream` as first-class citizens rather than
afterthoughts — this is that library.

> **Greyson vs the field, side by side:** for runnable examples that map the
> same messy JSON with Greyson, Jackson, and Gson, see
> [ralfspoeth/greyson-competition](https://github.com/ralfspoeth/greyson-competition).

---

## At a glance

```java
record Car(String make, int year) {}

Car car = Greyson.readValue(reader)
    .map(v -> new Car(
        v.get("make").flatMap(JsonValue::string).orElseThrow(),
        v.get("year").flatMap(JsonValue::intValue).orElseThrow()
    ))
    .orElseThrow();
```

Three things to notice:

- `readValue` returns `Optional<JsonValue>`. There are no nulls anywhere
  in the API.
- `JsonValue` is a sealed interface (`Basic | Aggregate`), so the
  compiler can prove your switches are exhaustive.
- Field extraction is just `Optional` chaining. No annotations on
  `Car`, no `TypeReference`, no `ObjectMapper` configuration.

Writing is the mirror image:

```java
JsonObject jo = Aggregate.objectBuilder()
    .putBasic("make", car.make())
    .putBasic("year", car.year())
    .build();

Greyson.writeValue(writer, jo);
```

---

## The duality: `JsonValue` ↔ `Builder`

Every `JsonValue` is immutable. Every `Builder` is its mutable twin.
The two are wired together so that round-tripping is always identity:

```java
JsonValue v = ...;
assert v.equals(v.builder().build());
```

This makes one-shot edits painless. To stamp a timestamp onto an
incoming object:

```java
Greyson.readBuilder(src)
    .filter(Builder.ObjectBuilder.class::isInstance)
    .map(Builder.ObjectBuilder.class::cast)
    .map(ob -> ob.putBasic("ts", Instant.now().toString()))
    .ifPresent(ob -> Greyson.writeBuilder(target, ob));
```

To wrap any incoming payload in an envelope:

```java
Greyson.readBuilder(src)
    .map(b -> objectBuilder()
        .putBasic("ts", Instant.now().toString())
        .put("msg", b))
    .ifPresent(ob -> Greyson.writeBuilder(target, ob));
```

Use `JsonValue` when you're mapping into your domain. Use `Builder`
when you're rewriting JSON in place.

---

## Real-world resilience

JSON in the wild is messy. Greyson treats single objects and arrays of
objects identically, ignores unknown keys without complaint, and lets
you mark fields mandatory or optional one at a time:

```java
record UserProfile(String id, List<String> tags, double balance) {}

static List<UserProfile> fromJson(Reader rdr) throws IOException {
    return Greyson.readValue(rdr).stream()
        .flatMap(Selector.all())                            // single object or array — both work
        .map(v -> new UserProfile(
            Pointer.self().member("id").stringValue(v).orElseThrow(),
            Pointer.self().member("tags").elements(v).stream()
                .flatMap(t -> t.string().stream())
                .toList(),
            Pointer.self().member("balance").doubleValue(v).orElse(0.0)
        ))
        .toList();
}
```

The same code accepts all of these without a configuration change:

```json
{"id": "p1", "tags": ["one", "two"]}
```

```json
[
    {"id": "p1", "tags": ["one", "two"]},
    {"id": "p2"},
    {"id": "p3", "balance": 100}
]
```

```json
{"id": "p4", "misspelled": "ignored", "balance": -500}
```

---

## Query API

Two abstractions, with a clear division of labor.

**`Selector`** is a function from `JsonValue` to `Stream<JsonValue>`.
Use it inside `Stream::flatMap` to pick out *a set of* values. Four
flavors ship in the box:

- `Selector.all()` — flattens an array, or yields the value itself
- `Selector.range(min, max)` — slices an array
- `Selector.regex(pattern)` — picks object members by key
- type filters — `objects()`, `arrays()`, `strings()`, `numbers()`,
  `booleans()`, `nulls()` (plus the abstract supertypes `basics()` and
  `aggregates()`); each yields its input if it matches the type, empty
  stream otherwise

Selectors stack:

```java
Greyson.readValue(src).stream()
    .flatMap(Selector.all())
    .flatMap(Selector.regex("a[0-9]*"))
    .flatMap(Selector.range(0, 2))
    .map(...);
```

**`Pointer`** is a function from `JsonValue` to `Optional<JsonValue>`.
Use it inside `Optional::flatMap` to navigate to *one* value. Build by
hand, or parse a slash-separated path:

```java
var p = Pointer.self().member("a").index(2).regex("b.*");
var q = Pointer.parse("a/[2]/#b.*");     // equivalent
```

Path syntax in `parse`:

- `name` — matches an object member by literal key
- `[n]` — indexes into an array (with optional leading `-` for
  from-the-end)
- `#regex` — matches an object member by regular expression; when
  several keys satisfy the pattern, the lexicographically smallest wins

The two forms are interchangeable; pick whichever reads more clearly at
the call site. Either way you get a `Pointer` you can plug straight
into a value extractor:

```java
JsonValue v = Greyson.readValue(src).orElseThrow();
boolean b = Pointer.parse("a/b/c").booleanValue(v).orElseThrow();
```

### JSON Pointer (RFC 6901)

Greyson reads
[RFC 6901](https://datatracker.ietf.org/doc/html/rfc6901) JSON Pointer
expressions via `Pointer.fromJsonPointer(String)`, so strings emitted
by JSON Patch, OpenAPI `$ref`, JSON Schema, or anything else that
speaks the standard drop straight in:

```java
var p = Pointer.fromJsonPointer("/users/0/email");
String email = p.stringValue(doc).orElseThrow();
```

Two nuances worth knowing:

- **Dispatch is dynamic.** The same token resolves as a member name
  against a `JsonObject` and as a non-negative integer index against a
  `JsonArray`. This differs from `parse`, which decides member-vs-index
  statically from the syntax. It's the price of RFC 6901 compatibility.
- **Escapes follow the spec.** `~1` decodes to `/` and `~0` to `~`, in
  that order, so a key like `a/b` is reachable via `/a~1b` and a key
  like `m~n` via `/m~0n`. The empty string addresses the document root.

### Updating a document

`Pointer` writes as well as reads — and it writes where mutation belongs:
on a `Builder`, the mutable twin of a `JsonValue`. Read a document into a
builder, edit it by pointer, then build the new immutable value:

```java
var b = Greyson.readBuilder(src).orElseThrow();

Pointer.parse("data/users/[0]/name").set(b, Basic.of("Ada"));
Pointer.parse("data/users/[0]/tmp").remove(b);

JsonValue updated = b.build();
```

Already holding a `JsonValue`? `builder()` gives you its mutable copy, so
the original is untouched:

```java
var b = doc.builder();
Pointer.parse("a").set(b, Basic.of(2));
JsonValue updated = b.build();   // doc unchanged
```

`at` hands back the *live* builder at a location, so you can keep using the
full `Builder` API from there:

```java
Pointer.parse("data/users").at(b)
        .map(Builder.ArrayBuilder.class::cast)
        .ifPresent(users -> users.add(newUser));
```

Writes drive the same engine through both path dialects, so an RFC 6901
string works too — enough to implement JSON Patch `add`/`remove`/`replace`
on top:

```java
Pointer.fromJsonPointer("/data/users/0/active").set(b, JsonBoolean.TRUE);
```

A few rules keep the semantics honest:

- **Missing object members are created.** Writing `a/b/c` into `{}`
  materialises the intermediate objects; a scalar in the way is replaced
  by a fresh object.
- **Arrays are not conjured.** An `[n]` step over a missing or non-array
  value throws `IllegalStateException`; an out-of-range index throws
  `IndexOutOfBoundsException`. An RFC 6901 index equal to the array length
  appends.
- **`#regex` segments don't write.** The target is ambiguous on a miss,
  so `set`/`remove` throw `UnsupportedOperationException`; resolve to a
  concrete member first.
- **`remove` never creates.** Removing an absent slot — or one who's
  parent doesn't resolve — is a no-op.
- **Removing and nulling are distinct.** `remove` drops the slot;
  `set(b, JsonNull.INSTANCE)` sets it to JSON `null`.

### Required extraction

Navigation returns `Optional`, which is right when a miss is a normal
outcome. When a value *must* be there, `require` throws instead — and
names the pointer, so the failure tells you where it missed:

```java
String name = Pointer.parse("data/users/[0]/name").stringOrThrow(doc);
// NoSuchElementException: no value at data/users/[0]/name
//   (or)  value at data/users/[0]/name is a JsonNumber, not a string
```

`require(doc)` returns the raw `JsonValue`; the typed `…OrThrow` accessors
(`stringOrThrow`, `intOrThrow`, `longOrThrow`, `doubleOrThrow`,
`booleanOrThrow`, `decimalOrThrow`, plus `intExactOrThrow` /
`longExactOrThrow`) add the type check and separate "absent" from "wrong
type" in the message. They pair with the `Optional`-returning accessors —
`stringValue`/`stringOrThrow`, `intValue`/`intOrThrow`, `intExact`/
`intExactOrThrow`, and so on.

### Pointers as values

A `Pointer` renders to its `parse` syntax via `toString()`, and `equals`
/ `hashCode` compare the whole segment chain — by type *and* data. So
`self().member("x")` and `fromJsonPointer("/x")` are distinct (they
dispatch differently), and a member literally named `[0]` is not the
index `[0]`. Pointers are immutable and safe to use as map keys.

### Composing pointers and selectors

`Pointer` and `Selector` compose in either direction. Pick the entry
point by which side you're coming from.

```java
// "navigate to one place, then fan out from there"
//   Pointer → Selector
var users = Pointer.parse("data/users").select(Selector.all());
Stream.of(doc).flatMap(users).forEach(...);

// "fan out from many places, then narrow each one"
//   Selector → Pointer
var emails = Selector.all().point(Pointer.self().member("email"));
Stream.of(usersArray).flatMap(emails).forEach(...);
```

`Pointer.select(Selector)` returns an empty stream when the pointer
doesn't resolve. `Selector.point(Pointer)` silently drops elements for
which the pointer doesn't resolve. In both cases, missing data is
absent from the output rather than a thrown exception.

### Checking and filtering by shape

A `Shape` describes the shape of a value. It maps a value to the
`Violation`s it exhibits, so an empty stream means the value is fine:

```java
var shape = Shape.member("isin", Shape.string())
        .and(Shape.member("ccy", Shape.string()));

shape.violations(doc).forEach(System.out::println);
// data/users/[0]/ccy: expected string, got number
```

Violations point at the *defect*, not at the container: a missing key
yields a pointer to that key, `each` rebases onto `[i]`, and `at` rebases
onto the whole path. So a report tells you where to look.

The same description also yields a `Predicate` — and because the violation
stream is lazy, it stops at the first problem and never builds a message
it won't use:

```java
var wellFormed = docs.children().filter(shape.predicate()).toList();
```

Where a `Selector` filters by **type** (`objects()`, `numbers()`, …), a
`Shape` filters by **shape**.

**Naming a member is requiring it.** Either you are interested in a member —
and then in its value — or you are not, in which case you don't mention it:

| |                                          |
| --- |------------------------------------------|
| `member("ccy", string())` | `ccy` is there, and it is a string       |
| `member("ccy")` | `ccy` is there, whatever it holds        |
| `each(number())` | an array who's every element is a number |
| `size(2)` / `size(1, 5)` / `atLeast(1)` | cardinality of an array or object        |
| `parse("a/b").must(string())` | `a/b` resolves, and holds a string       |
| `anything()` | accepts everything; identity of `and`    |

The three query types compose in both directions, so a description never has to
leave the algebra:

| | |
| --- | --- |
| `pointer.select(selector)` | fan out from where the pointer resolves |
| `selector.point(pointer)` | narrow each selected value to a sub-value |
| `pointer.must(shape)` | assert a shape at a location |
| `selector.where(shape)` | keep only the selected values of that shape |

```java
// still a Selector, so it keeps composing
var wellFormed = Selector.all().where(
        member("isin", string()).and(member("ccy", string())));
```

Shapes are records, so a description is inspectable data — comparable,
usable as a map key, and printable via `explain()`:

```java
member("isin", string()).and(member("ccy", string())).explain();
// "isin": string and "ccy": string
```

**This is deliberately not JSON Schema.** When a document has a schema that
is authoritative for the operation at hand, use a schema validator.
`Shape` is for data in the wild, where no such document exists or none
is being honoured — so there is no schema I/O in either direction, no
`$ref`, and no `anyOf`-style alternation (which would make "why did this
fail?" ambiguous). Messages are local and self-describing, because there is
no spec to point at.

---

## Data model

```
JsonValue                       sealed
├── Basic                       sealed
│   ├── JsonBoolean             record(boolean)
│   ├── JsonNumber              record(BigDecimal)
│   ├── JsonString              record(String)
│   └── JsonNull                record()
└── Aggregate                   sealed
    ├── JsonArray               record(List<JsonValue>)
    └── JsonObject              record(Map<String, JsonValue>)
```

A few design choices worth calling out:

- **`BigDecimal`, not `double`.** Numbers are held as `BigDecimal`, so digits
  are never lost to binary floating point, and the value is kept exactly as
  written — scale and trailing zeros included — so `18250.00` serializes back as
  `18250.00` (via `BigDecimal::toPlainString`, never scientific notation).
  Equality is *numeric*: `18250.00` and `18250` are equal (compared with
  `BigDecimal::compareTo`), with `hashCode` kept consistent by stripping trailing
  zeros. `JsonValue::intValue`, `::longValue`, `::doubleValue` give you the
  conversions when you want them.
- **Defensive copies in canonical constructors.** `JsonArray` and
  `JsonObject` copy their inputs unless they're already immutable
  (`List.of`, `Map.of`). Once constructed, the whole tree is
  effectively immutable.
- **No nulls in the API.** Every method that could return "no value"
  returns `Optional`, an empty `List`, or an empty `Map`. `null` is
  tolerated as input wherever it makes sense.
- **Aggregates are functions.** `JsonObject implements Function<String,
  JsonValue>` and `JsonArray implements IntFunction<JsonValue>`, so you
  can pass them straight into stream pipelines.

---

## Reading and writing

The `Greyson` class is the entry point. Four methods, that's it.

```java
Optional<JsonValue>                      Greyson.readValue(Reader)
Optional<Builder<? extends JsonValue>>   Greyson.readBuilder(Reader)
void                                     Greyson.writeValue(Writer, JsonValue)
void                                     Greyson.writeBuilder(Writer, Builder<?>)
```

`readValue` throws `IOException` from the underlying reader. The write
methods rethrow as `UncheckedIOException` so you can use them inside
fluent chains.

To filter at parse time — say, reject anything that isn't a JSON object
with a required key set:

```java
return Greyson.readValue(src)
    .filter(JsonObject.class::isInstance)
    .filter(jv -> jv.members().keySet().containsAll(Set.of("year", "make")))
    .map(...)
    .orElseThrow();
```

---

## JPMS

If you're using the Java module system, Greyson's module name is
`io.github.ralfspoeth.greyson`:

```java
module your.module {
    requires io.github.ralfspoeth.greyson;
}
```

It exports four packages:

- `io.github.ralfspoeth.json` — the `Greyson` entry point
- `io.github.ralfspoeth.json.data` — `JsonValue` and friends
- `io.github.ralfspoeth.json.io` — `JsonReader`, `JsonWriter`, `JsonParseException`
- `io.github.ralfspoeth.json.query` — `Pointer`, `Selector`, `Queries`

---

## Conformance

Greyson's parser is exercised against the
[nst JSON Test Suite](https://github.com/nst/JSONTestSuite). Every
`y_*` (must accept) case parses; every `n_*` (must reject) case
rejects; the `i_*` (implementation-defined) cases are documented in
the test resources.

Greyson is not the fastest JSON library on the JVM. Jackson and Gson
use bespoke streaming parsers, intern strings, and skip allocation in
the hot path. Greyson allocates an algebraic data type for every node.
If your bottleneck is JSON parsing throughput, use Jackson. If it
isn't, the simplicity is worth the trade.

---

## What's new in 2.0.1

- **Fixes a `StackOverflowError` in `Pointer.resolve(Pointer)`** when the
  right-hand pointer had more than one segment. The recursion reparented the
  argument without shortening it, so two states alternated forever. Every call
  site in the library and its tests happened to pass a single-segment pointer,
  which took the terminating branch — until `Shape.Violation.rebase` started
  composing multi-segment paths, so `parse("records").must(each(shape))` blew the
  stack. `resolve` now recurses on the argument's parent and reparents on the way
  out, bounded by the argument's length.

## What's new in 2.0.0

Greyson 2.0 changes its Maven coordinates and removes API. Everything below
that was announced for 1.8.0 ships here instead — 1.8.0 was never released.

### Migration from 1.7.x

**The artifact is now `greyson`, not `json`.** The library, the module
(`io.github.ralfspoeth.greyson`) and the docs have always said Greyson; only the
artifactId said `json`, so searching Maven Central for "greyson" found nothing.
1.7.x remains available at the old coordinates.

```xml
<dependency>
    <groupId>io.github.ralfspoeth</groupId>
    <artifactId>greyson</artifactId>   <!-- was: json -->
    <version>2.0.0</version>
</dependency>
```

Package names, the module name, and every type outside the table below are
unchanged, so the code change is usually just the pom.

| 1.7.x | 2.0.0 | why |
| --- | --- | --- |
| `Selector.as(extractor, mapper)` | `presentValues(x -> extractor.apply(x).map(mapper))` | exactly redundant with `presentValues` |
| `Pointer.as` → `Function<? super JsonValue, Optional<? extends T>>` | → `Function<JsonValue, Optional<T>>` | wildcards at a return position forced callers into capture for no benefit |
| `Selector.as` → `Function<? super JsonValue, Stream<T>>` | *(removed)* | see above |

`Pointer.as` itself stays: unlike `Selector`, `Pointer` has no `presentValues`,
so it isn't redundant with anything.

### New in 2.0.0

- `Shape` (in `query`) describes the shape of a value and maps it to the
  `Violation`s it exhibits — an empty stream means the value is fine. Violations
  carry a `Pointer` to the *defect*, so a report says where to look rather than
  just "no".
- The same description yields a `Predicate` for stream work, so one shape both
  explains and filters. Because the violation stream is lazy, `predicate()`
  short-circuits at the first problem and never builds messages it won't use.
  Where a `Selector` filters by type, a `Shape` filters by shape.
- Naming a member requires it: `member(k, shape)` asserts both that `k` is there
  and that its value fits, and `member(k)` asserts presence alone. Composed with
  `each`, `size`/`atLeast`, and `and`, which flattens.
- The three query types now bridge in both directions: alongside
  `Pointer.select` and `Selector.point`, `Pointer.must(shape)` asserts a shape at
  a location and `Selector.where(shape)` narrows a selection by shape — keeping
  shape-filtering inside the algebra rather than dropping out to `Stream.filter`.
- Shapes are records: a description is inspectable data, comparable, usable
  as a map key, and printable via `explain()`.
- Explicitly **not** a JSON Schema implementation, and won't grow into one: no
  schema I/O in either direction, no `$ref`, no `anyOf`. When a schema is
  authoritative for the operation, use a schema validator; `Shape` is for
  data in the wild.

## What's new in 1.7.1

The javadoc for the `query` package contains a reference to the upcoming 
JEP 540 "Simple JSON API"; the weather forecast extraction in particular.

## What's new in 1.7.0

**Breaking change.** The `Pointer` write side now targets `Builder` — the
mutable twin of a `JsonValue` — instead of rebuilding immutable copies. The
value-side `with(JsonValue, JsonValue)` and `without(JsonValue)` are **removed**
in favour of three methods that operate on a builder tree:

| removed | replacement |
| --- | --- |
| `JsonValue with(JsonValue, JsonValue)` | `void set(Builder<?>, JsonValue)` / `set(Builder<?>, Builder<?>)` |
| `JsonValue without(JsonValue)` | `void remove(Builder<?>)` |
| — | `Optional<Builder<?>> at(Builder<?>)` — the live builder at a location |

```java
// before (1.6.x)
JsonValue updated = Pointer.parse("a/b").with(doc, Basic.of(1));

// now
var b = doc.builder();                       // or Greyson.readBuilder(src)
Pointer.parse("a/b").set(b, Basic.of(1));
JsonValue updated = b.build();
```

Mutation now happens where it belongs — on the type designed for it — so the
two halves of the library stop duplicating each other. Editing many locations no
longer rebuilds the tree once per edit, and resolving a path is a single walk
down (previously each level re-navigated from the root). The path-creation rules
are unchanged: object members vivify, arrays don't, `#regex` segments don't write.

- `JsonValue.children()` streams a container's immediate children — the elements
  of an array or the member values of an object (not the keys), empty for scalars
  — giving one uniform way to descend a single level without an `instanceof`
  ladder. `depth()` and `nodes()` are now defined once, generically, on top of it
  (the per-type overrides are gone).
- `Selector.presentValues(f)` fans out and applies an `Optional`-returning
  extractor to each value, keeping the present results as a `Stream<T>` — folding
  the `flatMap(v -> f.apply(v).stream())` bridge into a single step. It's the
  multi-value analogue of `Pointer.as`.
- `Pointer.select(Selector)` now returns a `Selector` (previously a bare
  `Function`), so pointer navigation chains fluently into further selector
  operations, e.g. `parse("users").select(all()).presentValues(...)`.
  Source-compatible, but recompile-required.
- `JsonNumber` now preserves a number's scale exactly as written rather than
  stripping trailing zeros, so `18250.00` round-trips as `18250.00` (not
  `18250`). Equality stays *numeric* — `18250.00` equals `18250` via
  `BigDecimal::compareTo` — with `hashCode` kept consistent by stripping trailing
  zeros before hashing. This reverses the normalization introduced in 1.4.4.

## What's new in 1.6.0

- `Pointer.decimalValue(JsonValue)` returns `Optional<BigDecimal>` — the
  `Optional`-returning twin of `decimalOrThrow`, completing the
  `xxxValue`/`xxxOrThrow` pairing for every JSON scalar type.

## What's new in 1.5.0

**Breaking change.** The `Pointer` throwing accessors are renamed from the
`requireXxx` family to a type-prefixed `xxxOrThrow` family, so they pair with
the `Optional`-returning `xxxValue` accessors and sort beside them. The raw
`require(JsonValue)` (returns `JsonValue`) keeps its name. Migration:

| 1.4.x | 1.5.0 |
| --- | --- |
| `requireString` | `stringOrThrow` |
| `requireDecimal` | `decimalOrThrow` |
| `requireInt` | `intOrThrow` |
| `requireLong` | `longOrThrow` |
| `requireDouble` | `doubleOrThrow` |
| `requireBoolean` | `booleanOrThrow` |
| `requireIntExact` | `intExactOrThrow` |
| `requireLongExact` | `longExactOrThrow` |
| `require` | `require` *(unchanged)* |

The two `Optional`-returning exact accessors are also renamed for symmetry,
dropping the redundant `Value`: `intValueExact` → `intExact` and
`longValueExact` → `longExact` (so each pairs cleanly with `intExactOrThrow` /
`longExactOrThrow`). The other `xxxValue` accessors are unchanged.

## What's new in 1.4.4

- `JsonNumber` now serializes through `BigDecimal::toPlainString`, so numbers
  never appear in scientific notation: a value read as `18250.00` is written
  back as `18250` rather than `1.825E+4`. Numbers remain normalized (trailing
  zeros stripped), so the value compares equal either way — magnitude and
  significant digits are preserved, trailing-zero scale is not.

## What's new in 1.4.0

Version 1.4.0 grows `Pointer` from a read-only navigator into a full
read/write query primitive, developed with the help of Claude. Everything
below is new since 1.3.2.

- `Pointer.with(root, value)` and `Pointer.without(root)` update a document
  by returning a modified immutable copy, sharing every off-path subtree.
  Missing object members are auto-created; an `[n]` index step requires an
  existing array; `#regex` segments are not writable. RFC 6901 pointers
  drive the same engine, enough to implement JSON Patch on top.
- A `require*` family extracts a required value, throwing a
  `NoSuchElementException` that names the pointer and distinguishes an
  unresolved path from a wrong-typed value: `require`, `requireString`,
  `requireDecimal`, `requireInt`, `requireIntExact`, `requireLong`,
  `requireLongExact`, `requireDouble`, and `requireBoolean`.
- `Pointer` now implements `toString()` (rendering its `parse` syntax) plus
  `equals`/`hashCode` over the segment chain, compared by type and data — so
  pointers print legibly and work as map keys.

## What's new in 1.3

Version 1.3 incorporates changes with the help of Claude.

- `README.md` has been rewritten
- `Lexer` and therefore the `JsonReader` have gained some speed-up
- `Pointer.fromJsonPointer(String)` reads RFC 6901 JSON Pointer
  expressions, for interop with JSON Patch, OpenAPI `$ref`, JSON Schema,
  and other tooling that emits the standard syntax
- `Pointer.regex(...)` resolves multi-match patterns deterministically:
  when several keys match, the lexicographically smallest one wins
- `Pointer.select(Selector)` and `Selector.point(Pointer)` compose the
  two query primitives in either direction
- `Selector.objects()`, `arrays()`, `strings()`, `numbers()`,
  `booleans()`, `nulls()`, `basics()`, and `aggregates()` filter a
  stream to a single JSON value type

---

## License

MIT. Copyright 2025, 2026 Ralf Spöth.
