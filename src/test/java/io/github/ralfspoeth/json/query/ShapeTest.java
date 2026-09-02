package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;

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
                () -> assertEquals(List.of("<root>: expected array or object, got null"),
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
        var parts = assertInstanceOf(Shape.And.class, shape).parts();
        assertAll(
                () -> assertEquals(3, parts.size()),
                () -> assertInstanceOf(Shape.Member.class, parts.getFirst()),
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
