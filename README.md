# Greyson

A small, opinionated JSON library for Java.

```xml
<dependency>
    <groupId>io.github.ralfspoeth</groupId>
    <artifactId>greyson</artifactId>
    <version>2.3.0</version>
</dependency>
```

📖 **[API documentation](https://ralfspoeth.github.io/greyson/)** — published
from `main` on every change to the public sources.

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

`writeValue` indents with four spaces per level, one member or element per
line, and keeps empty objects and arrays on one line as `{}` and `[]`. Member
order is whatever the underlying map yields — see
[Two things about object members](#two-things-about-object-members). The
compact, single-line form is `JsonValue.json()`.

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

Three abstractions, with a clear division of labor. Each is a function
from `JsonValue`; they differ in what comes out, and that difference *is*
the division:

| | is a function to | answers |
| --- | --- | --- |
| **`Pointer`** | `Optional<JsonValue>` | *where is this one value?* |
| **`Selector`** | `Stream<JsonValue>` | *which values are there?* |
| **`Shape`** | `Stream<Violation>` | *what is wrong with this value?* |

All three are immutable, and all three compose with each other in both
directions — see [composing the three](#composing-the-three).

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

### Composing the three

Every pair bridges in both directions, so a query never has to leave the
algebra:

| | |
| --- | --- |
| `pointer.select(selector)` | fan out from where the pointer resolves |
| `selector.point(pointer)` | narrow each selected value to a sub-value |
| `pointer.must(shape)` | assert a shape at a location |
| `selector.where(shape)` | keep only the selected values of that shape |

Pick the entry point by which side you're coming from:

```java
// "navigate to one place, then fan out from there"
//   Pointer → Selector
var users = Pointer.parse("data/users").select(Selector.all());
Stream.of(doc).flatMap(users).forEach(...);

// "fan out from many places, then narrow each one"
//   Selector → Pointer
var emails = Selector.all().point(Pointer.self().member("email"));
Stream.of(usersArray).flatMap(emails).forEach(...);

// "everything here must look like this"
//   Pointer → Shape
var record = Shape.member("id", Shape.string())
        .and(Shape.member("amount", Shape.number()));
var wellFormed = Pointer.parse("records").must(Shape.each(record));

// "keep only the ones that do" — note the same shape value is reused
//   Selector → Shape, and still a Selector, so it keeps composing
var usable = Selector.all().where(record);
```

Missing data is absent from the output rather than an exception:
`Pointer.select` yields an empty stream when the pointer doesn't resolve,
and `Selector.point` drops elements for which it doesn't. The two `Shape`
bridges are the deliberate exception — `must` reports an unresolved
pointer as a violation, because a shape's whole job is to complain.

There is no `Selector`-to-`Shape` *quantifier* (`every`), and that
absence is by design: a `Selector` discards location — `all()` hands you
array elements without their index — so such a combinator could only
report violations at the container, which is exactly the useless report
`Shape` exists to avoid. Filtering can cross that boundary because it
needs only the predicate; diagnosis cannot, because it needs the paths.
That is also why `each` is not merely `all()` with a shape attached.

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
yields a pointer to that key, `each` rebases onto `[i]`, and `must`
rebases onto the whole path. So a report tells you where to look:

```java
Pointer.parse("records").must(Shape.each(record)).violations(doc)
// records/[1]/amount: expected number, got string
// records/[2]/id: missing required member
```

The same description also yields a `Predicate` — and because the violation
stream is lazy, it stops at the first problem and never builds a message
it won't use:

```java
var wellFormed = docs.children().filter(shape.predicate()).toList();
```

Where a `Selector` filters by **type** (`objects()`, `numbers()`, …), a
`Shape` filters by **shape**.

**Naming a member is requiring it.** Either you are interested in a member —
and then in its value — or you are not, in which case you don't mention it.
`optional` is for the third case, where absence is acceptable but a value that
*is* there still has to fit:

| |                                          |
| --- |------------------------------------------|
| `member("ccy", string())` | `ccy` is there, and it is a string       |
| `member("ccy")` | `ccy` is there, whatever it holds        |
| `optional("ccy", string())` | *if* `ccy` is there, it is a string      |
| `exactlyOne(Map.of("result", r, "error", e))` | exactly one of these members, satisfying its shape |
| `exact(Basic.of("2.0"))` | the value equals `"2.0"` — for discriminators |
| `oneOf("USD", "EUR", "GBP")` | the value is one of these |
| `matching("[A-Z]{3}")` | the value is a string matching the regex in full |
| `satisfying("a whole number", p)` | the value passes an arbitrary predicate |
| `each(number())` | an array who's every element is a number |
| `size(2)` / `size(1, 5)` / `atLeast(1)` | cardinality of an array or object        |
| `parse("a/b").must(string())` | `a/b` resolves, and holds a string       |
| `a.or(b)` | either holds — for union-typed fields |
| `anything()` / `nothing()` | accept everything / nothing; identities of `and` / `or` |

A shape is an immutable value object — comparable, usable as a map key, and
printable via `explain()`. The implementing types are package-private, so
`explain()` is how you read a description rather than matching on its
structure:

```java
member("isin", string()).and(member("ccy", string())).explain();
// "isin": string and "ccy": string
```

**Alternation reports coarsely, on purpose.** Real feeds produce union-typed
fields — a member that is an array in most documents and a bare object in some —
so `or` exists for them:

```java
member("positions", array().or(object()));
```

When no branch holds you get **one** violation at the location of the choice,
naming the alternatives:

```
positions: expected (array or object), got string
```

The branch violations are dropped deliberately. A value that only had to satisfy
one alternative would otherwise accumulate a defect per branch, reading as
several problems when there is a single unsatisfied choice — and blaming the
"closest" branch would need a heuristic no message could explain. So unlike
`and`, which reports each defect with a full path, `or` tells you where the
choice failed rather than pointing into a sub-value. There is no `not`:
negation has nothing useful to say about what it wanted.

**This is deliberately not JSON Schema.** When a document has a schema that
is authoritative for the operation at hand, use a schema validator.
`Shape` is for data in the wild, where no such document exists or none
is being honoured — so there is no schema I/O in either direction and no
`$ref` resolution, both of which need a schema *document* to make sense.
Messages are local and self-describing, because there is no spec to point at.

That test is about whether a schema governs the operation, not about which
keywords a schema language happens to have: `and` and `or` overlap with
`allOf` and `anyOf` and belong here anyway, because conjunction and
alternation are properties of messy data rather than of specs.

**And a `Shape` does not become a record mapper**, tempting though it looks: it
lists members and their types, a record lists components and their types, and
the two look isomorphic. But they are the two *endpoints* — the shape knows only
the JSON side, the record only the Java side, and everything difficult lives on
the edge between them. A field defaulting to a sibling, or to a value from an
enclosing object; `"%"` becoming an enum constant; a string becoming a
`Currency`. None of that is written down in either endpoint, and covering only
the members that *do* correspond one-to-one would leave the rest hand-written —
two styles in one mapper, split arbitrarily. Mapping stays explicit code over
`Pointer`s; reaching an arbitrary record needs reflection or code generation,
and Greyson has neither.

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
- `io.github.ralfspoeth.json.query` — `Pointer`, `Selector`, `Shape`, `Queries`

### Why ship a descriptor at all

Being a kind citizen, mostly. Writing a `module-info.java` costs the author
something — the test path gets awkward, split packages are out, reflection needs
`opens` — and almost all of the benefit lands on whoever consumes the library.

Without one, a modular consumer has to take the jar as an automatic module, named
by guessing from the filename, and cannot `jlink` a runtime image that includes
it. The library becomes the reason somebody else can't be strict. With one, they
get a stable name, a dependency graph the compiler checks before anything runs,
and services declared rather than configured.

It is also the only way to say which packages are *not* API. Greyson's internals
stay unexported and `Shape`'s implementations are package-private, so both remain
free to change. That part is a kindness to the author too — but it's the
enforcement that makes the promise worth anything.

---

## Conformance

Greyson's parser is exercised against the
[nst JSON Test Suite](https://github.com/nst/JSONTestSuite). Every
`y_*` (must accept) case parses; every `n_*` (must reject) case
rejects; the `i_*` (implementation-defined) cases are documented in
the test resources.

### Two things about object members

**Duplicate names: the last one wins, silently.** The suite's
`y_object_duplicate_key*.json` cases must be *accepted*, and Greyson accepts
them — members are collected into a `Map` while parsing, so `{"a": 1, "a": 2}`
parses to `{"a": 2}` with no warning and no way to see that anything was
discarded.

[RFC 8259](https://datatracker.ietf.org/doc/html/rfc8259#section-4) says names
within an object SHOULD be unique, and that the behaviour of software receiving
non-unique names is "unpredictable". Last-one-wins is therefore a legal reading
rather than a conformance gap — and it is what the field does: Jackson and Gson
resolve duplicates exactly the same way, which
[greyson-competition](https://github.com/ralfspoeth/greyson-competition) asserts
rather than assumes.

But it *is* a choice, and a lossy one. Jackson's streaming layer can be
configured for strict duplicate detection, turning a repeated name into a parse
error; Greyson has no such switch and cannot report that a value was dropped. If
you need duplicates caught, Greyson is the wrong tool and does not pretend
otherwise.

**Member order is not preserved.** `JsonObject` holds its members in a
`Map.copyOf` map, whose iteration order is unspecified, so `json()` may emit
members in a different order than the input. Round-tripping a document preserves
its *meaning*, not its byte-for-byte layout.

`JsonWriter` does not reorder, so the same applies to its indented output, and
with one wrinkle: *unspecified* is not merely "different from the input", it is
free to differ between two runs over the same document, so pretty-printed
`JsonObject` text is not something to diff or to hash. A `Builder.ObjectBuilder`
holds a `TreeMap`, so writing the *builder* is by name and stable — the two
overloads are faithful to their inputs rather than to each other.

Greyson is not the fastest JSON library on the JVM. Jackson and Gson
use bespoke streaming parsers, intern strings, and skip allocation in
the hot path. Greyson allocates an algebraic data type for every node.
If your bottleneck is JSON parsing throughput, use Jackson. If it
isn't, the simplicity is worth the trade.

---

## What's new (unreleased)

**Fixed: `JsonWriter` was not pretty-printing.** The indented output was
malformed, in three independent ways: members were separated by `,` with no
newline, so an object's members all landed on one line; the nesting level was
incremented twice on the way into a member's value; and the indentation was
written *before* the opening brace even in a member position, where the cursor
already sits behind `"name": `. Arrays never broke lines at all, and `{}` came
out as a brace, a newline, and a stray run of spaces. A document like

```json
{"a": 5, "arr": [null, {"a": 5.0}]}
```

was written as

```
{
    "a": 5,    "arr":         [null,         {
            "a": 5.0
        }]
}
```

Both overloads — `JsonValue` and `Builder` — carried the same three faults,
because they are parallel implementations of one layout. Empty objects and
arrays now stay on one line as `{}` and `[]`.

The round-trip test that covered this code could not catch it: the reader is
indifferent to whitespace, so malformed indentation reads back as an equal
document. The tests now assert the expected text — for the `JsonValue`
overload, on a document whose objects hold one member each, since member order
there is unspecified and there is otherwise no text to assert.

---

## What's new in 2.3.0

**New: `Shape.satisfying(explanation, predicate)`.** The escape hatch for
constraints on a *value*, which nothing else in `Shape` could express: the other
factories check types, exact equality, membership and cardinality, but say
nothing about a number's magnitude, a string's form, or membership of a set.

MCP is what prompted it. It narrows JSON-RPC's `id` to a string or an
*integer* — not any number — and `number()` cannot say "whole":

```java
var whole = satisfying("a whole number",
        v -> v instanceof JsonNumber(var n) && n.stripTrailingZeros().scale() <= 0);

optional("id", string().or(whole));   // "id"?: (string or a whole number)
```

A failed check reports the **value**, not its type, since the type is rarely the
complaint: `id: expected a whole number, got 1.5`. A predicate can only answer
about the value it is handed, so scope it with a combinator to place the
violation — `member("amount", positive)` reports under `amount`.

**One caveat, and it is a documented guarantee being narrowed.** Shapes are
value objects, but a `satisfying` shape closes over a lambda and so compares by
*identity* — two of them never compare equal even with identical arguments, and
neither does any composite containing one. Prefer a specific factory where one
fits.

**New: `Shape.exactlyOne(alternatives)`.** For members a document must choose
between — a JSON-RPC response carries `result` or `error`, never both and never
neither:

```java
var response = exactlyOne(Map.of("result", result, "error", error))
        .and(member("jsonrpc", exact(Basic.of("2.0"))));
```

Presence is settled first, so the two ways of getting it wrong read as
themselves — `expected exactly one of "error", "result", got none`, or
`… got "error", "result"`. And when exactly one *is* present, its own violations
come through in full under its key: `error/code: expected number, got string`.

That last part is why this isn't an exclusive-or of `member(…)` shapes. A branch
that is present but slightly malformed is the *common* failure, and an
alternation collapses it into "nothing matched", discarding the one diagnostic
that helps. Saying what is wrong and where is the point of a `Shape`; a
combinator that routinely throws it away would undercut that.

**New: `Shape.oneOf(values…)`**, for the enumerated set a field may take:

```java
member("ccy", oneOf("USD", "EUR", "GBP"));
// ccy: expected one of "EUR", "GBP", "USD", got "usd"
```

Arguments go through `Basic.of`, as with `putBasic`, so the common cases need no
wrapping, and equality is `JsonValue`'s — `oneOf(1, 2)` is satisfied by `1.0`.
These stay comparable, order-independently.

It could have been sugar for `exact(a).or(exact(b))…`, but the message would
then come from `or` and read `got string` rather than `got "usd"`, losing the
value — which is the thing worth reporting.

**New: `Shape.matching(regex)`**, another specific factory. Regex-on-a-
string is common enough to deserve its own shape rather than a lambda:

```java
member("ccy", matching("[A-Z]{3}"));
// ccy: expected a string matching /[A-Z]{3}/, got "usd"
```

It matches in **full**, like `Pointer.regex` and `Selector.regex` — a constraint
almost always means the whole string, and `.*` at either end gives you
containment. A non-string fails with `string()`'s own complaint, so that wording
lives in one place. And unlike `satisfying`, these **stay comparable**: `Pattern`
doesn't define equality, so the shape compares by the pattern's source and
flags, which is the entire reason it's a record and not a lambda.

## What's new in 2.2.0

**New: `Shape.optional(key, shape)`.** If the object carries `key` its value must
satisfy `shape`; an absent key is not a defect. For fields that are genuinely
optional *and* constrained:

```java
var request = member("jsonrpc", exact(Basic.of("2.0")))
        .and(member("method", string()))
        .and(optional("id", string().or(number()).or(nul())))
        .and(optional("params", array().or(object())));
```

That is a JSON-RPC request, and it is the case that brought `optional` back. 2.1.0
could not express it: `member` requires the key, and there was no way to say
"absent is acceptable".

**New: `Shape.exact(Basic)`.** The value must equal a given one — the other half
of the example above, for the discriminators protocols are built from. A mismatch
reports the value rather than the type, since being a string is no defect when
the complaint is that the string is the wrong one:

```
jsonrpc: expected "2.0", got "1.0"
```

Equality is `JsonValue`'s, so numbers compare *numerically*: `exact(Basic.of(1))`
is satisfied by `1`, `1.0` and `1.00` alike. Containers work too — an object
matches regardless of member order, since object equality is map equality, which
matters given Greyson does not preserve the input's order; arrays, being lists,
are order-sensitive. A failed match renders both values as JSON, so requiring a
large container exactly produces a correspondingly large message.

When 1.8.0 folded `Required` into `Member`, the argument for dropping
optional-but-typed members was that an optional field is read with an
`Optional`-returning accessor like `stringValue()`, which yields empty for both
*absent* and *wrong type* — so the consumer is already total and a shape check
adds nothing. JSON-RPC is where that breaks: a request with no `id` is a
*notification*, so reading a malformed `id` as an absent one silently turns a
request into a notification. Absence carries meaning, and conflating it with
malformedness is a protocol bug rather than lenient parsing.

`member` still means required, so a required-and-typed member is named once as
before. `explain()` marks optionality where a reader expects it:

```
"jsonrpc": string and "method": string and "id"?: (string or number or null)
```

## What's new in 2.1.0

**New: `Shape.or`.** Union-typed fields are what sloppy feeds actually produce —
`"positions"` an array in three documents and a bare object in the fourth — and
until now `Shape` could not describe them at all:

```java
member("positions", array().or(object()));
```

When no branch holds, the result is a *single* violation at the choice point
(`positions: expected (array or object), got string`) rather than one defect per
branch, which would read as several problems where there is one unsatisfied
choice. `or` flattens like `and`, parenthesises itself in `explain()` so mixing
the two stays unambiguous, and comes with `nothing()` as its identity for
folding a list of alternatives. There is deliberately no `not`.

This was previously ruled out as `anyOf`-style alternation drifting toward JSON
Schema. That reasoning was weak — `allOf` is equally a Schema keyword and `and`
exists — and the real test is whether a schema is authoritative for the
operation. Union-typed members are definitionally data in the wild.

**New: `JsonValue.typeName()`.** The JSON type name of a value, as the spec
names them — `object`, `array`, `number`, `string`, `null`, and for booleans the
literal names `true` and `false` ([RFC 8259 §3](https://datatracker.ietf.org/doc/html/rfc8259#section-3)
lists those three as literal names rather than naming a boolean type). Violation
messages are built from it, so the vocabulary is the same one you get.

`Shape`'s type names follow suit, which is visible in messages and in
`explain()`: `bool()` describes itself as `true|false`, and the abstract
supertypes as the union of their members — `type(Basic.class)` is
`null|true|false|string|number`, `type(Aggregate.class)` is `array|object`, and
`type(JsonValue.class)` is all seven.

**New: `Shape.Violation.expected(at, wanted, found)`.** The `"expected …, got …"`
wording now lives in exactly one place instead of being concatenated at five
call sites. `found` is a `String` rather than a `JsonValue` because not every
mismatch is about a type: a `size` constraint reports the count it found, having
been given the right type.

Alongside it, `member`, `each` and `size` now delegate their container
precondition to the type shapes — `object()`, `array()` and
`array().or(object())` — rather than carrying their own copies of those nouns.
One visible consequence: a `size` constraint applied to a non-aggregate now
reports `expected (array or object), got null`, with parentheses, because `or`
parenthesises itself.

`explain()`'s contract is also written down properly: it renders a *whole shape*
for a human and is **not** specified to read correctly after the word
"expected". It happens to for simple shapes, which is why those pass it to
`Violation.expected`, but `expected "ccy": string` and `expected a and b` both
read badly — which is why `and` reports its parts individually rather than
describing itself.

**Breaking change.** `Shape`'s implementing types are no longer public. The six
records — `Shape.Type`, `Shape.Member`, `Shape.Each`, `Shape.Size`, `Shape.At`
and `Shape.And` — are now package-private top-level records (`TypeShape`,
`MemberShape`, and so on). `Shape.Violation` is unaffected: it is real API and
stays public and nested.

Nothing else moves. Every factory (`string()`, `member(…)`, `each(…)`,
`size(…)`, `atLeast(…)`, `anything()`, `type(…)`), `Pointer.must`,
`Selector.where`, `violations`, `predicate`, `explain` and `and` are unchanged,
so code that builds shapes through the API needs no edit at all. Only code
naming an implementing type directly is affected, and there is no supported way
it could have needed to.

They were only public by accident: members of an interface are implicitly
public, so sealing `Shape` as an interface in order to use records exposed all
six whether or not that was wanted. Two things follow from fixing it:

- Their signatures are no longer frozen by semver, so `size`, `member` and the
  rest stay free to change.
- `and()` flattens nested conjunctions, and the docs promise it. Previously
  `new Shape.And(List.of(nested, …))` could construct a non-flat conjunction
  that no factory would ever produce — an invariant anyone could step around.

They remain records rather than hand-written classes, so `equals`, `hashCode`
and `toString` still come for free and shapes stay comparable, hashable value
objects. Reading a description is what `explain()` is for.

## What's new in 2.0.3

No behaviour changes — the compiled classes are identical to 2.0.2. This release
documents two properties of `JsonObject` that were always true and never written
down, in the README's [Conformance](#conformance) section and in the type's own
javadoc:

- **Duplicate member names collapse, last one wins, silently.** Members are
  collected into a `Map` while parsing, so `{"a": 1, "a": 2}` yields `{"a": 2}`
  with no warning and no way to detect the loss afterwards. RFC 8259 leaves this
  to the implementation, and Jackson and Gson resolve it identically — asserted
  in `greyson-competition` rather than assumed. Jackson can additionally be
  configured to reject duplicates outright; Greyson cannot.
- **Member order is not preserved.** `JsonObject` stores members in a
  `Map.copyOf` map with unspecified iteration order, so `json()` may emit them in
  a different order than the input. Round-tripping preserves meaning, not layout.

## What's new in 2.0.2

No library changes — the jar is identical to 2.0.1, so there is nothing to do if
you are already on it. This release covers documentation and repository work:

- **API docs are published** to
  [ralfspoeth.github.io/greyson](https://ralfspoeth.github.io/greyson/), rebuilt
  by a GitHub Action on every change to the public sources.
- **The Query API section of this README is rebuilt around the three types.** It
  previously opened with "two abstractions" and introduced `Shape` only as an
  afterthought; it now leads with what each type is a function *to*, and gathers
  all four composition bridges — `select`, `point`, `must`, `where` — into one
  place, including why there is deliberately no `Selector`-to-`Shape` quantifier.

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
- Shapes are immutable value objects: comparable, usable as a map key, and
  printable via `explain()`.
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
