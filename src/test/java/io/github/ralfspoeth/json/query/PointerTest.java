package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.Builder;
import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static io.github.ralfspoeth.json.query.Pointer.parse;
import static io.github.ralfspoeth.json.query.Pointer.self;
import static org.junit.jupiter.api.Assertions.*;

class PointerTest {


    @Test
    void testNull() {
        // given
        String member = null;
        JsonValue result = null;
        // then
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> self().member(member)),
                () -> assertThrows(NullPointerException.class, () -> self().apply(result))
        );
    }

    @Test
    void ofSingle() {
        // given
        var five = Basic.of(5);
        var singleElem = objectBuilder().put("one", five).build();
        // then
        assertAll(
                () -> assertEquals(five, parse("one").require(singleElem))
        );
    }

    @Test
    void testMember() {
        // given
        var ja = arrayBuilder().build();
        var jo = objectBuilder().putBasic("a", 1).putBasic("b", 2).build();
        var b = Basic.of(3);
        // when
        Pointer a = self().member("a");
        // then
        assertAll(
                () -> assertFalse(a.apply(ja).isPresent()),
                () -> assertFalse(a.apply(b).isPresent()),
                () -> assertEquals(1, a.intOrThrow(jo))
        );
    }

    @Test
    void testIndex() {
        // given
        var ja = arrayBuilder().addBasic(1).addBasic(2).build();
        var jo = objectBuilder().putBasic("a", 1).putBasic("b", 2).build();
        var b = Basic.of(3);
        // when
        Pointer i1 = self().index(1);
        // then
        assertAll(
                () -> assertTrue(i1.apply(ja).isPresent()),
                () -> assertEquals(2, i1.intOrThrow(ja)),
                () -> assertFalse(i1.apply(jo).isPresent()),
                () -> assertFalse(i1.apply(b).isPresent())
        );
    }

    @Test
    void testRegexFirstMatch() {
        // given
        var jo = objectBuilder()
                .putBasic("a", 1d)
                .putBasic("balance", 2d)
                .build();
        // when
        var ra = self().regex("[aA]");
        var bal = self().regex("bal(ance)?");
        // then single matches behave like a member lookup
        assertAll(
                () -> assertEquals(1d, ra.doubleOrThrow(jo)),
                () -> assertEquals(2d, bal.doubleOrThrow(jo))
        );
    }

    @Test
    void testParseRegexSegment() {
        // given an object with both a "bal" key and a "balance" key inside a
        // wrapping member "wallet"
        var jo = objectBuilder()
                .put("wallet", objectBuilder()
                        .putBasic("bal", 1d)
                        .putBasic("balance", 2d))
                .build();
        // when parse() encounters '#regex', it produces the same pointer as
        // chaining .regex(...) by hand
        var fromParse = parse("wallet/#bal(ance)?");
        var fluent = self().member("wallet").regex("bal(ance)?");
        // then both resolve to the lex-smallest matching key
        assertAll(
                () -> assertEquals(1d, fromParse.doubleOrThrow(jo)),
                () -> assertEquals(1d, fluent.doubleOrThrow(jo))
        );
    }

    @Test
    void testRegexMultiMatchPicksLexicographicallySmallest() {
        // given an object with both abbreviated and spelled-out forms
        var jo = objectBuilder()
                .putBasic("bal", 1d)
                .putBasic("balance", 2d)
                .build();
        // when the regex matches both keys
        var bal = self().regex("bal(ance)?");
        // then the value of the lexicographically smallest matching key wins
        assertEquals(1d, bal.doubleOrThrow(jo));
    }

    @Test
    void testFromJsonPointerEmpty() throws IOException {
        var json = """
                {"a": 1}
                """;
        var doc = Greyson.readValue(Reader.of(json)).orElseThrow();
        // empty string addresses the document root
        assertSame(doc, Pointer.fromJsonPointer("").require(doc));
    }

    @Test
    void testFromJsonPointerMustStartWithSlash() {
        assertThrows(IllegalArgumentException.class, () -> Pointer.fromJsonPointer("a/b"));
    }

    @Test
    void testFromJsonPointerMemberAndIndexDispatch() {
        // RFC 6901: the same token "0" resolves as a member name in objects
        // and as an array index in arrays.
        var asObject = """
                {"0": "obj-zero"}
                """;
        var asArray = """
                ["arr-zero", "arr-one"]
                """;
        assertAll(
                () -> assertEquals("obj-zero",
                        Pointer.fromJsonPointer("/0")
                                .stringOrThrow(Greyson.readValue(Reader.of(asObject)).orElseThrow())),
                () -> assertEquals("arr-zero",
                        Pointer.fromJsonPointer("/0")
                                .stringOrThrow(Greyson.readValue(Reader.of(asArray)).orElseThrow()))
        );
    }

    @Test
    void testFromJsonPointerEscapes() throws IOException {
        // ~1 -> '/', ~0 -> '~'; the order matters so that "~01" decodes as "~1"
        var json = """
                {"a/b": "slash", "m~n": "tilde", "~1": "literal-tilde-one"}
                """;
        var doc = Greyson.readValue(Reader.of(json)).orElseThrow();
        assertAll(
                () -> assertEquals("slash",
                        Pointer.fromJsonPointer("/a~1b").stringOrThrow(doc)),
                () -> assertEquals("tilde",
                        Pointer.fromJsonPointer("/m~0n").stringOrThrow(doc)),
                // "~01" should decode as the literal "~1", not "/"
                () -> assertEquals("literal-tilde-one",
                        Pointer.fromJsonPointer("/~01").stringOrThrow(doc))
        );
    }

    @Test
    void testFromJsonPointerArrayIndexValidation() throws IOException {
        var arr = """
                [10, 20, 30]
                """;
        var doc = Greyson.readValue(Reader.of(arr)).orElseThrow();
        assertAll(
                () -> assertEquals(20, Pointer.fromJsonPointer("/1").intOrThrow(doc)),
                // RFC 6901 forbids leading zeros — "01" is not a valid index
                () -> assertTrue(Pointer.fromJsonPointer("/01").apply(doc).isEmpty()),
                // Out-of-bounds is also empty, not an exception
                () -> assertTrue(Pointer.fromJsonPointer("/99").apply(doc).isEmpty()),
                // Negative tokens are not valid RFC 6901 array indices
                () -> assertTrue(Pointer.fromJsonPointer("/-1").apply(doc).isEmpty())
        );
    }

    @Test
    void testIntValue() {
        var jo = objectBuilder()
                .putBasic("n", 42)
                .putBasic("f", 3.7d)
                .putBasic("s", "not a number")
                .build();
        assertAll(
                () -> assertEquals(42, self().member("n").intValue(jo).orElseThrow()),
                // BigDecimal.intValue() truncates the fractional part. Use
                // intExact when you want the strict version.
                () -> assertEquals(3, self().member("f").intValue(jo).orElseThrow()),
                // wrong type — strings have no decimal()
                () -> assertTrue(self().member("s").intValue(jo).isEmpty()),
                // missing key
                () -> assertTrue(self().member("missing").intValue(jo).isEmpty())
        );
    }

    @Test
    void testLongValue() {
        var jo = objectBuilder()
                .putBasic("n", 9_876_543_210L)
                .putBasic("s", "not a number")
                .build();
        assertAll(
                () -> assertEquals(9_876_543_210L,
                        self().member("n").longValue(jo).orElseThrow()),
                () -> assertTrue(self().member("s").longValue(jo).isEmpty()),
                () -> assertTrue(self().member("missing").longValue(jo).isEmpty())
        );
    }

    @Test
    void testDoubleValue() {
        var jo = objectBuilder()
                .putBasic("n", 3.14d)
                .putBasic("i", 7)
                .putBasic("s", "not a number")
                .build();
        assertAll(
                () -> assertEquals(3.14d, self().member("n").doubleValue(jo).orElseThrow()),
                // ints widen cleanly through BigDecimal::doubleValue
                () -> assertEquals(7d, self().member("i").doubleValue(jo).orElseThrow()),
                () -> assertTrue(self().member("s").doubleValue(jo).isEmpty()),
                () -> assertTrue(self().member("missing").doubleValue(jo).isEmpty())
        );
    }

    @Test
    void testDecimalValue() {
        var jo = objectBuilder()
                .putBasic("n", new java.math.BigDecimal("3.50"))
                .putBasic("s", "not a number")
                .build();
        assertAll(
                // value-equal regardless of the stripped scale
                () -> assertEquals(0, new java.math.BigDecimal("3.5")
                        .compareTo(self().member("n").decimalValue(jo).orElseThrow())),
                () -> assertTrue(self().member("s").decimalValue(jo).isEmpty()),
                () -> assertTrue(self().member("missing").decimalValue(jo).isEmpty())
        );
    }

    @Test
    void testIntValueExact() {
        var jo = objectBuilder()
                .putBasic("ok", 1000)
                .putBasic("frac", 1.5d)
                .putBasic("big", 10_000_000_000L) // outside int range
                .putBasic("s", "x")
                .build();
        assertAll(
                () -> assertEquals(1000,
                        self().member("ok").intExact(jo).orElseThrow()),
                // BigDecimal.intExact rejects fractional values
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("frac").intExact(jo)),
                // and rejects values outside the int range
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("big").intExact(jo)),
                // wrong-type and missing keys still return empty without throwing
                () -> assertTrue(self().member("s").intExact(jo).isEmpty()),
                () -> assertTrue(self().member("missing").intExact(jo).isEmpty())
        );
    }

    @Test
    void testLongValueExact() throws IOException {
        // Build the document via JSON to guarantee a real BigDecimal payload
        // for the out-of-range case (Builder treats numeric primitives via
        // BigDecimal.valueOf which can't accept values larger than Long).
        var doc = Greyson.readValue(Reader.of("""
                {"ok": 1000000000, "frac": 1.5, "huge": 99999999999999999999, "s": "x"}
                """)).orElseThrow();
        assertAll(
                () -> assertEquals(1_000_000_000L,
                        self().member("ok").longExact(doc).orElseThrow()),
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("frac").longExact(doc)),
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("huge").longExact(doc)),
                () -> assertTrue(self().member("s").longExact(doc).isEmpty()),
                () -> assertTrue(self().member("missing").longExact(doc).isEmpty())
        );
    }

    @Test
    void testBooleanValue() {
        var jo = objectBuilder()
                .putBasic("t", true)
                .putBasic("f", false)
                .putBasic("s", "true") // literal string, not a JSON boolean
                .putBasic("n", 1)
                .build();
        assertAll(
                () -> assertTrue(self().member("t").booleanValue(jo).orElseThrow()),
                () -> assertFalse(self().member("f").booleanValue(jo).orElseThrow()),
                // strings and numbers are not coerced; the conversion is type-strict
                () -> assertTrue(self().member("s").booleanValue(jo).isEmpty()),
                () -> assertTrue(self().member("n").booleanValue(jo).isEmpty()),
                () -> assertTrue(self().member("missing").booleanValue(jo).isEmpty())
        );
    }

    @Test
    void testStringValue() {
        var jo = objectBuilder()
                .putBasic("s", "hello")
                .putBasic("n", 42)
                .putBasic("b", true)
                .build();
        assertAll(
                () -> assertEquals("hello",
                        self().member("s").stringValue(jo).orElseThrow()),
                // numbers and booleans are not stringified by the conversion
                () -> assertTrue(self().member("n").stringValue(jo).isEmpty()),
                () -> assertTrue(self().member("b").stringValue(jo).isEmpty()),
                () -> assertTrue(self().member("missing").stringValue(jo).isEmpty())
        );
    }

    @Test
    void testSelectNavigatesThenExpands() {
        // {"data": {"users": [{"id": 1}, {"id": 2}, {"id": 3}]}}
        var doc = objectBuilder()
                .put("data", objectBuilder()
                        .put("users", arrayBuilder()
                                .add(objectBuilder().putBasic("id", 1))
                                .add(objectBuilder().putBasic("id", 2))
                                .add(objectBuilder().putBasic("id", 3))))
                .build();
        // Navigate to the users array, then stream every element of it.
        var users = parse("data/users").select(Selector.all());
        var ids = users.apply(doc)
                .flatMap(v -> v.get("id").stream())
                .flatMap(v -> v.decimal().stream())
                .map(BigDecimal::intValue)
                .toList();
        assertEquals(List.of(1, 2, 3), ids);
    }

    @Test
    void testSelectEmptyWhenPointerDoesNotResolve() {
        // The pointer fails to resolve "missing", so the selector never runs.
        var doc = objectBuilder().putBasic("a", 1).build();
        var fn = self().member("missing").select(Selector.all());
        assertEquals(0L, fn.apply(doc).count());
    }

    @Test
    void testSelectWithSelfPointerEqualsSelectorApply() {
        // self() navigates nowhere, so self().select(s) ≡ s.apply.
        var arr = arrayBuilder().addBasic(1).addBasic(2).addBasic(3).build();
        var direct = Selector.all().apply(arr).toList();
        var viaPointer = self().select(Selector.all()).apply(arr).toList();
        assertEquals(direct, viaPointer);
    }

    @Test
    void testResolve() throws IOException {
        // given
        var json = """
                [
                    {"a": [1, 2, 3]},
                    [{"a":4}],
                    null
                ]
                """;
        // when
        Pointer i0 = self().index(0);
        Pointer i1 = self().index(1);
        Pointer ma = self().member("a");
        Pointer i0ma = i0.resolve(ma);
        Pointer i0mai1 = i0ma.resolve(i1);
        // then
        var opt = Greyson.readValue(Reader.of(json));
        var val = opt.orElseThrow();
        assertAll(
                () -> assertInstanceOf(JsonObject.class, i0.require(val)),
                () -> assertInstanceOf(JsonArray.class, i1.require(val)),
                () -> assertInstanceOf(JsonArray.class, i0ma.require(val)),
                () -> assertEquals(2, i0mai1.intOrThrow(val))
        );
    }

    @Test
    void testLargeArray() {
        // given
        var b = arrayBuilder();
        var ptr = self();
        var ab = b;
        for (int i = 0; i < 499; i++) {
            var tmp = arrayBuilder();
            ab.add(tmp);
            ab = tmp;
            ptr = ptr.resolve(self().index(0));
        }
        // when
        var ja = b.build();

        // then
        System.out.println(ja);
        System.out.println(ja.depth());
        System.out.println(ja.nodes());
        System.out.println(ptr.require(ja));
    }

    @Test
    void testLargeObject() {
        // given
        var b = objectBuilder();
        var ptr = self();
        var ob = b;
        for (int i = 0; i < 499; i++) {
            var tmp = objectBuilder();
            ob.put("a", tmp);
            ob = tmp;
            ptr = ptr.resolve(self().member("a"));
        }
        // when
        var jo = b.build();
        // then
        System.out.println(jo);
        System.out.println(ptr.require(jo));
    }

    // ---- write side: at / set / remove on Builders ------------------------

    @Test
    void testSetReplacesAndKeepsSiblings() {
        // given {"data": {"users": [{"name": "old", "id": 1}, {"name": "two"}]}}
        var b = objectBuilder()
                .put("data", objectBuilder()
                        .put("users", arrayBuilder()
                                .add(objectBuilder().putBasic("name", "old").putBasic("id", 1))
                                .add(objectBuilder().putBasic("name", "two"))));
        var p = parse("data/users/[0]/name");
        // when
        p.set(b, Basic.of("Ada"));
        var updated = b.build();
        // then
        assertAll(
                () -> assertEquals("Ada", p.stringOrThrow(updated)),
                // a sibling member in the same object survives
                () -> assertEquals(1, parse("data/users/[0]/id").intOrThrow(updated)),
                // an untouched array element survives
                () -> assertEquals("two", parse("data/users/[1]/name").stringOrThrow(updated))
        );
    }

    @Test
    void testSetAutoVivifiesObjectChain() {
        var b = objectBuilder();
        var p = self().member("a").member("b").member("c");
        // writing deep into a path that does not exist yet creates the levels
        p.set(b, Basic.of(42));
        assertEquals(42, p.intOrThrow(b.build()));
    }

    @Test
    void testSetReplacesScalarOnThePathWithAnObject() {
        // "a" is a number, but the path needs it to be a container
        var b = objectBuilder().putBasic("a", 1);
        self().member("a").member("b").set(b, Basic.of(2));
        assertEquals(2, parse("a/b").intOrThrow(b.build()));
    }

    @Test
    void testSetNegativeIndexWritesFromEnd() {
        var b = objectBuilder().put("xs", arrayBuilder().addBasic(1).addBasic(2).addBasic(3));
        var last = self().member("xs").index(-1);
        last.set(b, Basic.of(99));
        var updated = b.build();
        assertAll(
                () -> assertEquals(99, last.intOrThrow(updated)),
                () -> assertEquals(1, self().member("xs").index(0).intOrThrow(updated))
        );
    }

    @Test
    void testSetOnSelfThrows() {
        var b = objectBuilder().putBasic("a", 1);
        assertThrows(IllegalStateException.class, () -> self().set(b, Basic.of("new")));
    }

    @Test
    void testSetIndexRequiresExistingArray() {
        var b = objectBuilder().putBasic("a", 1);
        assertAll(
                // "a" is a number, not an array
                () -> assertThrows(IllegalStateException.class,
                        () -> self().member("a").index(0).set(b, Basic.of(9))),
                // a missing container vivifies to an object, which an index step rejects
                () -> assertThrows(IllegalStateException.class,
                        () -> self().member("missing").index(0).set(b, Basic.of(9)))
        );
    }

    @Test
    void testSetIndexOutOfBoundsThrows() {
        var b = objectBuilder().put("xs", arrayBuilder().addBasic(1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> self().member("xs").index(5).set(b, Basic.of(9)));
    }

    @Test
    void testSetRegexSegmentIsUnsupported() {
        var b = objectBuilder().putBasic("bal", 1);
        assertThrows(UnsupportedOperationException.class,
                () -> self().regex("bal.*").set(b, Basic.of(2)));
    }

    @Test
    void testRemoveMemberKeepsSiblings() {
        var b = objectBuilder().putBasic("a", 1).putBasic("b", 2);
        var a = self().member("a");
        a.remove(b);
        var pruned = b.build();
        assertAll(
                () -> assertTrue(a.apply(pruned).isEmpty()),
                () -> assertEquals(2, self().member("b").intOrThrow(pruned))
        );
    }

    @Test
    void testRemoveArrayElement() {
        var b = objectBuilder().put("xs", arrayBuilder().addBasic(10).addBasic(20).addBasic(30));
        self().member("xs").index(1).remove(b);
        var pruned = b.build();
        assertAll(
                () -> assertEquals(2, self().member("xs").require(pruned).elements().size()),
                () -> assertEquals(10, self().member("xs").index(0).intOrThrow(pruned)),
                () -> assertEquals(30, self().member("xs").index(1).intOrThrow(pruned))
        );
    }

    @Test
    void testRemoveAbsentSlotIsNoOp() {
        var b = objectBuilder().putBasic("a", 1);
        var before = b.build();
        // neither the missing member nor its missing parent is created
        self().member("missing").remove(b);
        self().member("nope").member("deep").remove(b);
        assertEquals(before, b.build());
    }

    @Test
    void testRemoveOnSelfThrows() {
        var b = objectBuilder().putBasic("a", 1);
        assertThrows(IllegalStateException.class, () -> self().remove(b));
    }

    @Test
    void testAtReturnsLiveBuilder() {
        var b = objectBuilder().put("xs", arrayBuilder().addBasic(1));
        // the builder handed back is live: mutating it mutates the tree
        var xs = assertInstanceOf(Builder.ArrayBuilder.class,
                self().member("xs").at(b).orElseThrow());
        xs.addBasic(2);
        assertAll(
                () -> assertEquals(2, self().member("xs").require(b.build()).elements().size()),
                () -> assertEquals(2, self().member("xs").index(1).intOrThrow(b.build())),
                // self() addresses the root builder
                () -> assertSame(b, self().at(b).orElseThrow()),
                // a path that does not resolve is empty, and nothing is created
                () -> assertTrue(self().member("nope").at(b).isEmpty()),
                () -> assertTrue(self().member("nope").apply(b.build()).isEmpty())
        );
    }

    @Test
    void testBuilderOfValueLeavesSourceUntouched() {
        // the immutable bridge: value -> builder -> edit -> build
        var doc = objectBuilder().putBasic("a", 1).build();
        var b = doc.builder();
        self().member("a").set(b, Basic.of(2));
        assertAll(
                () -> assertEquals(2, self().member("a").intOrThrow(b.build())),
                () -> assertEquals(1, self().member("a").intOrThrow(doc)) // source intact
        );
    }

    @Test
    void testRfc6901SetReplaceAndAppend() throws IOException {
        var replaced = Greyson.readBuilder(Reader.of("[10, 20]")).orElseThrow();
        Pointer.fromJsonPointer("/0").set(replaced, Basic.of(99));
        var appended = Greyson.readBuilder(Reader.of("[10, 20]")).orElseThrow();
        // an index equal to the current length appends
        Pointer.fromJsonPointer("/2").set(appended, Basic.of(30));
        assertAll(
                () -> assertEquals(99, Pointer.fromJsonPointer("/0").intOrThrow(replaced.build())),
                () -> assertEquals(2, replaced.build().elements().size()),
                () -> assertEquals(30, Pointer.fromJsonPointer("/2").intOrThrow(appended.build())),
                () -> assertEquals(3, appended.build().elements().size())
        );
    }

    @Test
    void testRfc6901SetNestedObject() throws IOException {
        var b = Greyson.readBuilder(Reader.of("""
                {"a": {"b": 1}}
                """)).orElseThrow();
        Pointer.fromJsonPointer("/a/b").set(b, Basic.of(2));
        assertEquals(2, Pointer.fromJsonPointer("/a/b").intOrThrow(b.build()));
    }

    // ---- toString and require --------------------------------------------

    @Test
    void testToStringRendersParseSyntax() {
        assertAll(
                () -> assertEquals("data/users/[0]/name", parse("data/users/[0]/name").toString()),
                () -> assertEquals("", self().toString())
        );
    }

    @Test
    void testRequireNamesThePointerOnMiss() {
        var doc = objectBuilder().putBasic("a", 1).build();
        var p = parse("data/users/[0]/name");
        var ex = assertThrows(NoSuchElementException.class, () -> p.require(doc));
        assertAll(
                () -> assertTrue(ex.getMessage().contains("data/users/[0]/name"), ex.getMessage()),
                () -> assertEquals(Basic.of(1), self().member("a").require(doc))
        );
    }

    @Test
    void testRequireStringDistinguishesMissingFromWrongType() {
        var doc = objectBuilder()
                .putBasic("s", "hi")
                .putBasic("n", 42)
                .build();
        var missing = assertThrows(NoSuchElementException.class,
                () -> self().member("missing").stringOrThrow(doc));
        var wrongType = assertThrows(NoSuchElementException.class,
                () -> self().member("n").stringOrThrow(doc));
        assertAll(
                () -> assertEquals("hi", self().member("s").stringOrThrow(doc)),
                // an unresolved path reports "no value at <pointer>"
                () -> assertTrue(missing.getMessage().contains("no value at missing"),
                        missing.getMessage()),
                // a resolved-but-wrong-type value names the actual type instead
                () -> assertTrue(wrongType.getMessage().contains("not a string"),
                        wrongType.getMessage()),
                () -> assertTrue(wrongType.getMessage().contains("JsonNumber"),
                        wrongType.getMessage())
        );
    }

    @Test
    void testRequireNumericAndBoolean() {
        var doc = objectBuilder()
                .putBasic("n", 42)
                .putBasic("big", 9_876_543_210L)
                .putBasic("f", 3.7d)
                .putBasic("flag", true)
                .putBasic("s", "x")
                .build();
        var wrongType = assertThrows(NoSuchElementException.class,
                () -> self().member("s").decimalOrThrow(doc));
        var missing = assertThrows(NoSuchElementException.class,
                () -> self().member("missing").intOrThrow(doc));
        assertAll(
                () -> assertEquals(new BigDecimal("42"), self().member("n").decimalOrThrow(doc)),
                () -> assertEquals(42, self().member("n").intOrThrow(doc)),
                () -> assertEquals(9_876_543_210L, self().member("big").longOrThrow(doc)),
                () -> assertEquals(3.7d, self().member("f").doubleOrThrow(doc)),
                // intOrThrow truncates, like intValue
                () -> assertEquals(3, self().member("f").intOrThrow(doc)),
                () -> assertTrue(self().member("flag").booleanOrThrow(doc)),
                // a present-but-wrong-type value names the actual type
                () -> assertTrue(wrongType.getMessage().contains("not a number"), wrongType.getMessage()),
                () -> assertTrue(wrongType.getMessage().contains("JsonString"), wrongType.getMessage()),
                // an unresolved path reports "no value at <pointer>"
                () -> assertTrue(missing.getMessage().contains("no value at missing"), missing.getMessage()),
                // booleanOrThrow is type-strict
                () -> assertThrows(NoSuchElementException.class,
                        () -> self().member("n").booleanOrThrow(doc))
        );
    }

    @Test
    void testRequireExact() throws IOException {
        // huge is outside long range; build via JSON for a real BigDecimal payload
        var doc = Greyson.readValue(Reader.of("""
                {"ok": 1000, "frac": 1.5, "big": 10000000000, "huge": 99999999999999999999, "s": "x"}
                """)).orElseThrow();
        assertAll(
                () -> assertEquals(1000, self().member("ok").intExactOrThrow(doc)),
                () -> assertEquals(1000L, self().member("ok").longExactOrThrow(doc)),
                // a nonzero fractional part is rejected
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("frac").intExactOrThrow(doc)),
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("frac").longExactOrThrow(doc)),
                // out of int range (but valid long)
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("big").intExactOrThrow(doc)),
                () -> assertEquals(10_000_000_000L, self().member("big").longExactOrThrow(doc)),
                // out of long range
                () -> assertThrows(ArithmeticException.class,
                        () -> self().member("huge").longExactOrThrow(doc)),
                // wrong type and missing still surface as NoSuchElementException
                () -> assertThrows(NoSuchElementException.class,
                        () -> self().member("s").intExactOrThrow(doc)),
                () -> assertThrows(NoSuchElementException.class,
                        () -> self().member("missing").longExactOrThrow(doc))
        );
    }

    @Test
    void testEqualsHashCodeAndMapKey() {
        assertAll(
                // structurally identical pointers are equal regardless of how built
                () -> assertEquals(parse("a/b/[0]"), self().member("a").member("b").index(0)),
                () -> assertEquals(parse("a/b/[0]").hashCode(),
                        self().member("a").member("b").index(0).hashCode()),
                () -> assertEquals(self(), self()),
                // differing segment data breaks equality
                () -> assertNotEquals(parse("a/b"), parse("a/c")),
                () -> assertNotEquals(self().index(0), self().index(1)),
                // same token, different dispatch semantics -> not equal
                () -> assertNotEquals(self().member("x"), Pointer.fromJsonPointer("/x")),
                // a member literally named "[0]" is not the index [0]
                () -> assertNotEquals(self().member("[0]"), self().index(0)),
                // usable as a hash-map key
                () -> {
                    var m = new java.util.HashMap<Pointer, String>();
                    m.put(parse("a/b"), "v");
                    assertEquals("v", m.get(self().member("a").member("b")));
                }
        );
    }
}
