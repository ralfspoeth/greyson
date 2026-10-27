package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.Aggregate;
import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonBoolean;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static io.github.ralfspoeth.json.query.Pointer.parse;
import static io.github.ralfspoeth.json.query.Pointer.self;
import static io.github.ralfspoeth.json.query.Shape.*;
import static org.junit.jupiter.api.Assertions.*;

class ShapeTest {

    /** {"isin": ..., "ccy": ...}, omitting "ccy" when {@code ccy} is null. */
    private static JsonObject instrument(String isin, Object ccy) {
        var b = objectBuilder().putBasic("isin", isin);
        if (ccy != null) b.putBasic("ccy", ccy);
        return b.build();
    }

    private static List<String> messages(Shape s, JsonValue v) {
        return s.violations(v).map(Shape.Violation::toString).toList();
    }

    @Test
    void satisfiedShapeYieldsNoViolations() {
        var shape = member("isin", string()).and(member("ccy", string()));
        assertAll(
                () -> assertEquals(List.of(), messages(shape, instrument("US1", "USD"))),
                () -> assertTrue(shape.predicate().test(instrument("US1", "USD")))
        );
    }

    @Test
    void namingAMemberRequiresIt() {
        var shape = member("ccy", string());
        assertAll(
                // present and right -> fine
                () -> assertEquals(List.of(), messages(shape, instrument("US1", "USD"))),
                // present and wrong -> violation rebased onto the member
                () -> assertEquals(List.of("ccy: expected string, got number"),
                        messages(shape, instrument("US1", 42))),
                // absent -> a violation pointing at the key itself
                () -> assertEquals(List.of("ccy: missing required member"),
                        messages(shape, instrument("US1", null))),
                () -> assertFalse(shape.predicate().test(instrument("US1", null)))
        );
    }

    @Test
    void memberWithoutAShapeRequiresPresenceOnly() {
        var shape = member("ccy");
        assertAll(
                () -> assertEquals(List.of(), messages(shape, instrument("US1", "USD"))),
                // any value will do, as long as it is there
                () -> assertEquals(List.of(), messages(shape, instrument("US1", 42))),
                () -> assertEquals(List.of("ccy: missing required member"),
                        messages(shape, instrument("US1", null)))
        );
    }

    @Test
    void memberOnANonObjectIsASingleViolation() {
        var msgs = messages(member("a", string()), Basic.of(42));
        assertAll(
                () -> assertEquals(1, msgs.size()),
                () -> assertEquals("<root>: expected object, got number", msgs.getFirst())
        );
    }

    @Test
    void eachMissingMemberIsReportedAtItsOwnLocation() {
        var shape = member("isin").and(member("ccy")).and(member("name"));
        var violations = shape.violations(instrument("US1", null)).toList();
        assertAll(
                () -> assertEquals(2, violations.size()),
                () -> assertEquals("ccy", violations.getFirst().at().toString()),
                () -> assertEquals("name", violations.get(1).at().toString()),
                () -> assertEquals("missing required member", violations.getFirst().message())
        );
    }

    @Test
    void eachTagsViolationsWithTheElementIndex() {
        var arr = arrayBuilder().addBasic("a").addBasic(2).addBasic("c").addBasic(4).build();
        assertEquals(
                List.of("[1]: expected string, got number", "[3]: expected string, got number"),
                messages(each(string()), arr)
        );
    }

    @Test
    void eachOnANonArray() {
        assertEquals(List.of("<root>: expected array, got object"),
                messages(each(string()), instrument("US1", "USD")));
    }

    @Test
    void atReachesDeepAndRebasesTheViolation() {
        // {"data": {"users": [{"name": 1}]}}
        var doc = objectBuilder()
                .put("data", objectBuilder()
                        .put("users", arrayBuilder()
                                .add(objectBuilder().putBasic("name", 1))))
                .build();
        assertEquals(List.of("data/users/[0]/name: expected string, got number"),
                messages(parse("data/users/[0]/name").must(string()), doc));
    }

    @Test
    void mustReportsAMissingLocation() {
        assertEquals(List.of("a/b: no value at this location"),
                messages(parse("a/b").must(string()), objectBuilder().build()));
    }

    @Test
    void sizeAndAtLeast() {
        var three = arrayBuilder().addBasic(1).addBasic(2).addBasic(3).build();
        assertAll(
                () -> assertEquals(List.of(), messages(size(3), three)),
                () -> assertEquals(List.of("<root>: expected 2 element(s), got 3"), messages(size(2), three)),
                () -> assertEquals(List.of(), messages(size(1, 5), three)),
                () -> assertEquals(List.of(), messages(atLeast(3), three)),
                () -> assertEquals(List.of("<root>: expected at least 4 element(s), got 3"),
                        messages(atLeast(4), three)),
                // objects count members
                () -> assertEquals(List.of(), messages(size(2), instrument("US1", "USD"))),
                // the aggregate precondition is delegated to array().or(object()),
                // which parenthesises itself
                () -> assertEquals(List.of("<root>: expected (array or object), got null"),
                        messages(size(1), JsonNull.INSTANCE))
        );
    }

    @Test
    void sizeRejectsNonsenseBounds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> size(-1, 3)),
                () -> assertThrows(IllegalArgumentException.class, () -> size(5, 2))
        );
    }

    @Test
    void andConcatenatesViolationsAndFlattens() {
        var shape = member("isin").and(member("ccy", string())).and(size(2));
        // the AST is inspectable data — three parts, not a nested tree
        var parts = assertInstanceOf(AndShape.class, shape).parts();
        assertAll(
                () -> assertEquals(3, parts.size()),
                () -> assertInstanceOf(MemberShape.class, parts.getFirst()),
                // one value, two independent problems, both reported
                () -> assertEquals(
                        List.of("isin: missing required member", "ccy: expected string, got number"),
                        messages(shape, objectBuilder().putBasic("ccy", 1).putBasic("x", 2).build()))
        );
    }

    @Test
    void anythingAcceptsEverythingAndIsTheIdentityOfAnd() {
        assertAll(
                () -> assertEquals(List.of(), messages(anything(), JsonNull.INSTANCE)),
                () -> assertTrue(anything().predicate().test(Basic.of(1))),
                () -> assertEquals(List.of(), messages(anything().and(anything()), Basic.of("x")))
        );
    }

    @Test
    void typeFactoriesCoverTheHierarchy() {
        assertAll(
                () -> assertTrue(string().predicate().test(Basic.of("s"))),
                () -> assertTrue(number().predicate().test(Basic.of(1))),
                () -> assertTrue(bool().predicate().test(Basic.of(true))),
                () -> assertTrue(nul().predicate().test(JsonNull.INSTANCE)),
                () -> assertTrue(object().predicate().test(objectBuilder().build())),
                () -> assertTrue(array().predicate().test(arrayBuilder().build())),
                () -> assertFalse(string().predicate().test(Basic.of(1)))
        );
    }

    /**
     * The motivating use case: one description, used to filter rather than to
     * explain. Where Selector filters by type, a Shape filters by shape.
     */
    @Test
    void predicateFiltersAStreamByShape() {
        var docs = arrayBuilder()
                .add(instrument("US1", "USD"))
                .add(instrument("DE1", null))   // no ccy
                .add(instrument("GB1", 42))     // ccy is not a string
                .add(instrument("JP1", "JPY"))
                .build();
        var shape = member("isin", string()).and(member("ccy", string()));

        var wellFormed = docs.children()
                .filter(shape.predicate())
                .flatMap(v -> self().member("isin").stringValue(v).stream())
                .toList();

        assertEquals(List.of("US1", "JP1"), wellFormed);
    }

    /**
     * The Selector bridge: filtering by shape stays inside the query algebra
     * and keeps composing, instead of dropping out to {@link java.util.stream.Stream#filter}.
     */
    @Test
    void selectorWhereNarrowsByShape() {
        var docs = arrayBuilder()
                .add(instrument("US1", "USD"))
                .add(instrument("DE1", null))   // no ccy
                .add(instrument("GB1", 42))     // ccy is not a string
                .add(instrument("JP1", "JPY"))
                .build();
        var shape = member("isin", string()).and(member("ccy", string()));

        var isins = Selector.all().where(shape)
                .presentValues(v -> self().member("isin").stringValue(v))
                .apply(docs)
                .toList();

        assertAll(
                () -> assertEquals(List.of("US1", "JP1"), isins),
                // still a Selector, so it narrows further
                () -> assertEquals(2L, Selector.all().where(shape).apply(docs).count()),
                () -> assertEquals(0L, Selector.all().where(shape).where(member("nope")).apply(docs).count())
        );
    }

    @Test
    void pointerMustAssertsAShapeAtALocation() {
        var doc = objectBuilder().put("a", objectBuilder().putBasic("b", "ok")).build();
        assertAll(
                () -> assertEquals(List.of(), messages(parse("a/b").must(string()), doc)),
                () -> assertTrue(parse("a/b").must(string()).predicate().test(doc)),
                // must() composes with and() like any other shape
                () -> assertEquals(List.of("a/c: no value at this location"),
                        messages(parse("a/b").must(string()).and(parse("a/c").must(string())), doc))
        );
    }

    /**
     * {@code must} over {@code each} rebases a violation twice, so the pointer
     * handed to {@link Pointer#resolve} has more than one segment. That is the
     * case that used to overflow the stack.
     */
    @Test
    void nestedRebasingProducesFullPaths() {
        var doc = objectBuilder()
                .put("records", arrayBuilder()
                        .add(objectBuilder().putBasic("id", "r1").putBasic("amount", 1))
                        .add(objectBuilder().putBasic("id", "r2").putBasic("amount", "oops"))
                        .add(objectBuilder().putBasic("amount", 3)))
                .build();
        var record = member("id", string()).and(member("amount", number()));
        assertEquals(
                List.of("records/[1]/amount: expected number, got string",
                        "records/[2]/id: missing required member"),
                messages(parse("records").must(each(record)), doc));
    }

    @Test
    void orIsSatisfiedByEitherBranch() {
        // the union-typed field real feeds produce: an array here, a bare object there
        var positions = member("positions", array().or(object()));
        var asArray = objectBuilder().put("positions", arrayBuilder().addBasic(1)).build();
        var asObject = objectBuilder().put("positions", objectBuilder().putBasic("isin", "US1")).build();
        var asString = objectBuilder().putBasic("positions", "oops").build();
        assertAll(
                () -> assertEquals(List.of(), messages(positions, asArray)),
                () -> assertEquals(List.of(), messages(positions, asObject)),
                // no branch held: one violation at the choice point, naming the alternatives
                () -> assertEquals(List.of("positions: expected (array or object), got string"),
                        messages(positions, asString))
        );
    }

    @Test
    void orReportsOneViolationNotOnePerBranch() {
        // three branches, all failing, still a single message — the branch
        // violations are deliberately dropped
        var shape = string().or(number()).or(bool());
        var violations = shape.violations(JsonNull.INSTANCE).toList();
        assertAll(
                () -> assertEquals(1, violations.size()),
                // type names are the spec's literal names, so JsonBoolean reads
                // "true|false" rather than "boolean"
                () -> assertEquals("<root>: expected (string or number or true|false), got null",
                        violations.getFirst().toString()),
                // flattened, like and()
                () -> assertEquals(3, assertInstanceOf(OrShape.class, shape).parts().size())
        );
    }

    @Test
    void orMixesWithAndUnambiguously() {
        // parentheses keep precedence readable in explain()
        var shape = member("id", string()).and(member("positions", array().or(object())));
        assertAll(
                () -> assertEquals("\"id\": string and \"positions\": (array or object)",
                        shape.explain()),
                () -> assertEquals(List.of(), messages(shape, objectBuilder()
                        .putBasic("id", "p1")
                        .put("positions", objectBuilder().putBasic("isin", "US1"))
                        .build())),
                // the and-branch still reports with a full path
                () -> assertEquals(List.of("id: expected string, got number"),
                        messages(shape, objectBuilder()
                                .putBasic("id", 1)
                                .put("positions", arrayBuilder())
                                .build()))
        );
    }

    @Test
    void nothingIsTheIdentityOfOr() {
        assertAll(
                // accepts nothing at all
                () -> assertFalse(nothing().predicate().test(Basic.of(1))),
                () -> assertFalse(nothing().predicate().test(JsonNull.INSTANCE)),
                () -> assertEquals("nothing", nothing().explain()),
                // and so is the seed for folding alternatives
                () -> assertTrue(Stream.of(string(), number())
                        .reduce(nothing(), Shape::or)
                        .predicate().test(Basic.of(1))),
                // a single alternative does not get spurious parentheses
                () -> assertEquals("string", nothing().or(string()).explain())
        );
    }

    /**
     * {@code explain()} renders a whole shape for a human; it is deliberately
     * not specified to read correctly after the word "expected", which is why
     * composites report their parts rather than describing themselves.
     */
    @Test
    void explainRendersCompositesThatWouldNotFitAMessage() {
        var shape = member("ccy", string()).and(each(number()));
        assertAll(
                // the standalone rendering is the documented, useful one …
                () -> assertEquals("\"ccy\": string and each element number", shape.explain()),
                // … and it never reaches a message: and() reports its parts,
                // each with its own path, and neither says "expected a and b"
                () -> assertEquals(
                        List.of("ccy: expected string, got number", "<root>: expected array, got object"),
                        messages(shape, objectBuilder().putBasic("ccy", 1).build()))
        );
    }

    @Test
    void preconditionMessagesComeFromTheTypeShapes() {
        // member/each/size share "be an object|array|aggregate" with the type
        // shapes, so the wording is produced in one place, not three
        assertAll(
                () -> assertEquals(messages(object(), Basic.of(1)),
                        messages(member("k", string()), Basic.of(1))),
                () -> assertEquals(messages(array(), Basic.of(1)),
                        messages(each(string()), Basic.of(1))),
                () -> assertEquals(messages(array().or(object()), Basic.of(1)),
                        messages(size(2), Basic.of(1)))
        );
    }

    /**
     * {@code type(Class)} is public and the hierarchy is sealed, so every class
     * assignable to {@link JsonValue} must have a name — including the two
     * abstract supertypes and {@code JsonValue} itself, which is the union of
     * everything and a shape nothing can fail.
     */
    @Test
    void everyJsonTypeHasAName() {
        assertAll(
                () -> assertEquals("null|true|false|string|number|array|object",
                        type(JsonValue.class).explain()),
                () -> assertEquals("null|true|false|string|number", type(Basic.class).explain()),
                () -> assertEquals("array|object", type(Aggregate.class).explain()),
                () -> assertEquals("true|false", type(JsonBoolean.class).explain()),
                // nothing is not a JsonValue, so this shape never reports a defect
                () -> assertTrue(type(JsonValue.class).predicate().test(JsonNull.INSTANCE)),
                () -> assertTrue(type(JsonValue.class).predicate().test(Basic.of("x"))),
                () -> assertTrue(type(JsonValue.class).predicate().test(objectBuilder().build()))
        );
    }

    @Test
    void optionalAcceptsAbsenceButNotAWrongValue() {
        var shape = optional("ccy", string());
        assertAll(
                () -> assertEquals(List.of(), messages(shape, instrument("US1", "USD"))),
                // absent is fine — the distinction member() cannot draw
                () -> assertEquals(List.of(), messages(shape, instrument("US1", null))),
                () -> assertTrue(shape.predicate().test(instrument("US1", null))),
                // present but wrong is still a defect, pointed at the key
                () -> assertEquals(List.of("ccy: expected string, got number"),
                        messages(shape, instrument("US1", 42))),
                // "optional member" says nothing about the container being an object
                () -> assertEquals(messages(object(), Basic.of(1)), messages(shape, Basic.of(1)))
        );
    }

    /**
     * The case that brought {@code optional} back: in JSON-RPC an absent
     * {@code id} means notification, so a malformed {@code id} must not be
     * mistaken for an absent one.
     */
    @Test
    void optionalExpressesAJsonRpcRequest() {
        var request = member("jsonrpc", exact(Basic.of("2.0")))
                .and(member("method", string()))
                .and(optional("id", string().or(number()).or(nul())))
                .and(optional("params", array().or(object())));

        var notification = objectBuilder()
                .putBasic("jsonrpc", "2.0").putBasic("method", "ping").build();
        var withId = objectBuilder()
                .putBasic("jsonrpc", "2.0").putBasic("method", "ping").putBasic("id", 7).build();
        var badId = objectBuilder()
                .putBasic("jsonrpc", "2.0").putBasic("method", "ping").putBasic("id", true).build();

        assertAll(
                // a notification omits id entirely
                () -> assertEquals(List.of(), messages(request, notification)),
                () -> assertEquals(List.of(), messages(request, withId)),
                // a boolean id is rejected rather than silently read as absent
                () -> assertEquals(
                        List.of("id: expected (string or number or null), got true"),
                        messages(request, badId)),
                () -> assertEquals(
                        "\"jsonrpc\": \"2.0\" and \"method\": string "
                                + "and \"id\"?: (string or number or null) "
                                + "and \"params\"?: (array or object)",
                        request.explain())
        );
    }

    @Test
    void exactMatchesOneValueAndNamesItInTheMessage() {
        var version = member("jsonrpc", exact(Basic.of("2.0")));
        assertAll(
                () -> assertEquals(List.of(),
                        messages(version, objectBuilder().putBasic("jsonrpc", "2.0").build())),
                // the complaint is about the value, so the value is what is reported
                () -> assertEquals(List.of("jsonrpc: expected \"2.0\", got \"1.0\""),
                        messages(version, objectBuilder().putBasic("jsonrpc", "1.0").build())),
                // a wrong type is still just an unequal value
                () -> assertEquals(List.of("jsonrpc: expected \"2.0\", got 2"),
                        messages(version, objectBuilder().putBasic("jsonrpc", 2).build())),
                () -> assertEquals("\"2.0\"", exact(Basic.of("2.0")).explain())
        );
    }

    @Test
    void exactComparesNumbersNumerically() {
        // JsonNumber equality is compareTo-based, so scale does not matter
        var one = exact(Basic.of(1));
        assertAll(
                () -> assertTrue(one.predicate().test(Basic.of(1))),
                () -> assertTrue(one.predicate().test(Basic.of(new BigDecimal("1.00")))),
                () -> assertFalse(one.predicate().test(Basic.of(2))),
                // booleans and null are exact-matchable too
                () -> assertTrue(exact(Basic.of(true)).predicate().test(Basic.of(true))),
                () -> assertFalse(exact(Basic.of(true)).predicate().test(Basic.of(false))),
                () -> assertTrue(exact(JsonNull.INSTANCE).predicate().test(JsonNull.INSTANCE))
        );
    }

    @Test
    void exactMatchesAggregatesToo() {
        var expected = objectBuilder().putBasic("a", 1).putBasic("b", 2).build();
        var reordered = objectBuilder().putBasic("b", 2).putBasic("a", 1).build();
        var different = objectBuilder().putBasic("a", 1).putBasic("b", 3).build();
        assertAll(
                () -> assertTrue(exact(expected).predicate()
                        .test(objectBuilder().putBasic("a", 1).putBasic("b", 2).build())),
                // objects match regardless of member order — map equality, which
                // matters because Greyson does not preserve the input's order
                () -> assertTrue(exact(expected).predicate().test(reordered)),
                () -> assertFalse(exact(expected).predicate().test(different)),
                // arrays are lists, so they are order-sensitive
                () -> assertTrue(exact(arrayBuilder().addBasic(1).addBasic(2).build())
                        .predicate().test(arrayBuilder().addBasic(1).addBasic(2).build())),
                () -> assertFalse(exact(arrayBuilder().addBasic(1).addBasic(2).build())
                        .predicate().test(arrayBuilder().addBasic(2).addBasic(1).build())),
                // a container is not equal to a scalar
                () -> assertFalse(exact(expected).predicate().test(Basic.of("a")))
        );
    }

    /**
     * A JSON-RPC response carries {@code result} or {@code error}, never both
     * and never neither — the case {@code exactlyOne} exists for.
     */
    @Test
    void exactlyOneSettlesPresenceThenDelegates() {
        var error = member("code", number()).and(member("message", string()));
        var response = exactlyOne(Map.of("result", anything(), "error", error));

        var withResult = objectBuilder().putBasic("result", 7).build();
        var withError = objectBuilder()
                .put("error", objectBuilder().putBasic("code", -32600).putBasic("message", "bad"))
                .build();
        var both = objectBuilder()
                .putBasic("result", 7)
                .put("error", objectBuilder().putBasic("code", -1).putBasic("message", "x"))
                .build();
        var neither = objectBuilder().putBasic("jsonrpc", "2.0").build();

        assertAll(
                () -> assertEquals(List.of(), messages(response, withResult)),
                () -> assertEquals(List.of(), messages(response, withError)),
                // the two ways of getting presence wrong are reported as themselves
                () -> assertEquals(
                        List.of("<root>: expected exactly one of \"error\", \"result\", got \"error\", \"result\""),
                        messages(response, both)),
                () -> assertEquals(
                        List.of("<root>: expected exactly one of \"error\", \"result\", got none"),
                        messages(response, neither)),
                () -> assertEquals(messages(object(), Basic.of(1)), messages(response, Basic.of(1)))
        );
    }

    @Test
    void exactlyOneKeepsThePresentBranchesDiagnostics() {
        // the whole reason this is not an exclusive-or of member() shapes: a
        // branch that is present but malformed is the common failure, and an
        // alternation would report it as "nothing matched"
        var error = member("code", number()).and(member("message", string()));
        var response = exactlyOne(Map.of("result", anything(), "error", error));

        var malformed = objectBuilder()
                .put("error", objectBuilder().putBasic("code", "-32600").putBasic("message", "bad"))
                .build();

        assertEquals(List.of("error/code: expected number, got string"),
                messages(response, malformed));
    }

    @Test
    void exactlyOneRejectsAnEmptyChoiceAndStaysComparable() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> exactlyOne(Map.of())),
                // a single alternative is degenerate but legal: it just requires that member
                () -> assertEquals(List.of(), messages(exactlyOne(Map.of("a", string())),
                        objectBuilder().putBasic("a", "x").build())),
                // comparable, and order-independent since the map is
                () -> assertEquals(exactlyOne(Map.of("a", string(), "b", number())),
                        exactlyOne(Map.of("b", number(), "a", string()))),
                () -> assertNotEquals(exactlyOne(Map.of("a", string())),
                        exactlyOne(Map.of("a", number()))),
                // explain() names each alternative's shape, unlike the message
                () -> assertEquals("exactly one of \"a\": string, \"b\": number",
                        exactlyOne(Map.of("a", string(), "b", number())).explain())
        );
    }

    @Test
    void matchingRequiresAFullMatch() {
        var ccy = matching("[A-Z]{3}");
        assertAll(
                () -> assertEquals(List.of(), messages(ccy, Basic.of("USD"))),
                // full match, not a search: neither longer nor embedded passes
                () -> assertFalse(ccy.predicate().test(Basic.of("USDX"))),
                () -> assertFalse(ccy.predicate().test(Basic.of("xUSDx"))),
                () -> assertEquals(List.of("<root>: expected a string matching /[A-Z]{3}/, got \"usd\""),
                        messages(ccy, Basic.of("usd"))),
                // a non-string gets string()'s own complaint, worded once
                () -> assertEquals(messages(string(), Basic.of(1)), messages(ccy, Basic.of(1))),
                () -> assertEquals("a string matching /[A-Z]{3}/", ccy.explain()),
                () -> assertEquals(List.of("ccy: expected a string matching /[A-Z]{3}/, got \"usd\""),
                        messages(member("ccy", ccy), objectBuilder().putBasic("ccy", "usd").build()))
        );
    }

    @Test
    void matchingShapesStayComparable() {
        // the point of a record rather than a satisfying(...) lambda: Pattern
        // compares by identity, so equality is by source and flags
        assertAll(
                () -> assertEquals(matching("[A-Z]{3}"), matching("[A-Z]{3}")),
                () -> assertEquals(matching("[A-Z]{3}").hashCode(), matching("[A-Z]{3}").hashCode()),
                () -> assertEquals(member("ccy", matching("[A-Z]{3}")),
                        member("ccy", matching("[A-Z]{3}"))),
                () -> assertNotEquals(matching("[A-Z]{3}"), matching("[a-z]{3}")),
                // flags participate: a case-insensitive pattern is a different shape
                () -> assertNotEquals(matching(Pattern.compile("abc")),
                        matching(Pattern.compile("abc", Pattern.CASE_INSENSITIVE)))
        );
    }

    @Test
    void satisfyingConstrainsTheValueAndReportsIt() {
        var positive = satisfying("a positive number",
                v -> v instanceof JsonNumber(var n) && n.signum() > 0);
        assertAll(
                () -> assertEquals(List.of(), messages(positive, Basic.of(1))),
                // the value is reported, not its type: "got number" would be useless
                () -> assertEquals(List.of("<root>: expected a positive number, got -5"),
                        messages(positive, Basic.of(-5))),
                () -> assertEquals(List.of("<root>: expected a positive number, got \"x\""),
                        messages(positive, Basic.of("x"))),
                () -> assertEquals("a positive number", positive.explain()),
                // a predicate answers about the value it is handed, so scope it
                // with a combinator to place the violation
                () -> assertEquals(List.of("amount: expected a positive number, got -5"),
                        messages(member("amount", positive),
                                objectBuilder().putBasic("amount", -5).build()))
        );
    }

    /**
     * The case that motivated {@code satisfying}: MCP narrows JSON-RPC's
     * {@code id} to a string or an <em>integer</em>, and {@code number()} cannot
     * say "whole".
     */
    @Test
    void satisfyingExpressesAnIntegerValuedId() {
        var whole = satisfying("a whole number",
                v -> v instanceof JsonNumber(var n) && n.stripTrailingZeros().scale() <= 0);
        var id = optional("id", string().or(whole));

        assertAll(
                () -> assertTrue(id.predicate().test(objectBuilder().putBasic("id", "abc").build())),
                () -> assertTrue(id.predicate().test(objectBuilder().putBasic("id", 7).build())),
                // 1.0 is an integer value, scale and all
                () -> assertTrue(id.predicate().test(objectBuilder()
                        .putBasic("id", new BigDecimal("1.0")).build())),
                // absent is fine, it is optional
                () -> assertTrue(id.predicate().test(objectBuilder().build())),
                // a fractional id is not
                () -> assertFalse(id.predicate().test(objectBuilder()
                        .putBasic("id", new BigDecimal("1.5")).build())),
                () -> assertFalse(id.predicate().test(objectBuilder().putBasic("id", true).build())),
                () -> assertEquals("\"id\"?: (string or a whole number)", id.explain())
        );
    }

    @Test
    void oneOfEnumeratesPermittedValues() {
        var ccy = member("ccy", oneOf("USD", "EUR", "GBP"));
        assertAll(
                () -> assertTrue(ccy.predicate().test(objectBuilder().putBasic("ccy", "EUR").build())),
                () -> assertFalse(ccy.predicate().test(objectBuilder().putBasic("ccy", "usd").build())),
                () -> assertFalse(ccy.predicate().test(objectBuilder().putBasic("ccy", 1).build())),
                // the value is reported, and the set is sorted so it is deterministic
                () -> assertEquals(
                        List.of("ccy: expected one of \"EUR\", \"GBP\", \"USD\", got \"usd\""),
                        messages(ccy, objectBuilder().putBasic("ccy", "usd").build())),
                () -> assertEquals("one of \"EUR\", \"GBP\", \"USD\"", oneOf("USD", "EUR", "GBP").explain())
        );
    }

    @Test
    void oneOfComparesNumbersNumericallyAndStaysComparable() {
        assertAll(
                // a set lookup finds it because JsonNumber's equals and hashCode agree
                () -> assertTrue(oneOf(1, 2).predicate().test(Basic.of(new BigDecimal("1.0")))),
                () -> assertFalse(oneOf(1, 2).predicate().test(Basic.of(3))),
                // mixed kinds are fine, and null is a value like any other
                () -> assertTrue(oneOf("a", 1, true, null).predicate().test(JsonNull.INSTANCE)),
                // unlike satisfying, these compare equal - order-independently
                () -> assertEquals(oneOf("USD", "EUR"), oneOf("EUR", "USD")),
                () -> assertEquals(oneOf("USD").hashCode(), oneOf("USD").hashCode()),
                () -> assertNotEquals(oneOf("USD"), oneOf("EUR")),
                () -> assertThrows(IllegalArgumentException.class, Shape::oneOf)
        );
    }

    @Test
    void satisfyingShapesAreNotComparable() {
        Predicate<JsonValue> same = v -> true;
        assertAll(
                // identical arguments, still unequal: a lambda has identity equality
                () -> assertNotEquals(satisfying("x", v -> true), satisfying("x", v -> true)),
                // and a composite containing one inherits that
                () -> assertNotEquals(member("k", satisfying("x", v -> true)),
                        member("k", satisfying("x", v -> true))),
                // sharing the predicate instance is what makes them equal again
                () -> assertEquals(satisfying("x", same), satisfying("x", same))
        );
    }

    @Test
    void explainDescribesTheShape() {
        assertAll(
                () -> assertEquals("string", string().explain()),
                () -> assertEquals("\"ccy\": string", member("ccy", string()).explain()),
                () -> assertEquals("\"ccy\": anything", member("ccy").explain()),
                () -> assertEquals("each element number", each(number()).explain()),
                () -> assertEquals("anything", anything().explain()),
                () -> assertEquals("a/b string", parse("a/b").must(string()).explain()),
                () -> assertEquals("\"isin\": string and \"ccy\": string",
                        member("isin", string()).and(member("ccy", string())).explain())
        );
    }

    @Test
    void structuresAreValueObjects() {
        // records: equal by content, usable as map keys, printable
        assertAll(
                () -> assertEquals(member("k", string()), member("k", string())),
                () -> assertEquals(member("k").hashCode(), member("k").hashCode()),
                () -> assertEquals(each(string()), each(string())),
                () -> assertNotEquals(string(), number()),
                () -> assertNotEquals(member("a", string()), member("b", string()))
        );
    }
}
