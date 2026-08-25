package io.github.ralfspoeth.json.query;

import io.github.ralfspoeth.json.data.Basic;
import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.github.ralfspoeth.json.data.Builder.arrayBuilder;
import static io.github.ralfspoeth.json.data.Builder.objectBuilder;
import static io.github.ralfspoeth.json.query.Pointer.self;
import static io.github.ralfspoeth.json.query.Selector.all;
import static io.github.ralfspoeth.json.query.Selector.regex;
import static java.util.Collections.disjoint;
import static org.junit.jupiter.api.Assertions.*;

class SelectorTest {

    @Test
    void ofNull() {
        // given
        Pattern p = null;
        String s = null;
        // then
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> regex(p)),
                () -> assertThrows(NullPointerException.class, () -> regex(s))
        );
    }

    @Test
    void testAll() {
        // given
        JsonArray ja = arrayBuilder().addBasic(1).addBasic(2).addBasic(3).build();
        JsonObject jo = new JsonObject(Map.of());
        Basic<?> b = JsonNull.INSTANCE;
        // when
        Selector allSelector = all();
        // then
        assertAll(
                () -> assertEquals(ja.elements(), Stream.of(ja).flatMap(allSelector).toList()),
                () -> assertEquals(List.of(jo), Stream.of(jo).flatMap(allSelector).toList()),
                () -> assertEquals(List.of(b), Stream.of(b).flatMap(allSelector).toList())
        );
    }

    @Test
    void testRange() {
        // given
        var ja = arrayBuilder().addBasic(1).addBasic(2).addBasic(3).build();
        // when
        var rg12 = Selector.range(1, 2);
        var rg03 = Selector.range(0, 3);
        var rg0neg = Selector.range(0, -1);
        // then
        assertAll(
                () -> assertEquals(List.of(Basic.of(2)), Stream.of(ja).flatMap(rg12).toList()),
                () -> assertEquals(List.of(Basic.of(1), Basic.of(2), Basic.of(3)), Stream.of(ja).flatMap(rg03).toList()),
                () -> assertEquals(List.of(Basic.of(1), Basic.of(2), Basic.of(3)), Stream.of(ja).flatMap(rg0neg).toList())
        );
    }

    @Test
    void testRegex() {
        // given
        var jo = objectBuilder().putBasic("a1", 1).putBasic("a2", 2).putBasic("a3", 3).build();
        // when
        var aDigit = Selector.regex("a[0-9]+");
        var a12 = Selector.regex("a[1-2]");
        var a3 = Selector.regex("a3");
        var l123 = List.of(Basic.of(1), Basic.of(2), Basic.of(3));
        var l12 = List.of(Basic.of(1), Basic.of(2));
        var l3 = List.of(Basic.of(3));
        // then
        assertAll(
                () -> assertFalse(disjoint(l123, Stream.of(jo).flatMap(aDigit).toList())),
                () -> assertFalse(disjoint(l12, Stream.of(jo).flatMap(a12).toList())),
                () -> assertFalse(disjoint(l3, Stream.of(jo).flatMap(a3).toList()))
        );
    }

    @Test
    void testTypeSelectorsHappyPath() {
        // each factory yields a singleton stream of its argument when the
        // type matches
        var num = Basic.of(42);
        var str = Basic.of("hi");
        var bool = Basic.of(true);
        var nul = JsonNull.INSTANCE;
        var arr = arrayBuilder().addBasic(1).build();
        var obj = objectBuilder().putBasic("a", 1).build();
        assertAll(
                () -> assertEquals(List.of(num), Stream.of(num).flatMap(Selector.numbers()).toList()),
                () -> assertEquals(List.of(str), Stream.of(str).flatMap(Selector.strings()).toList()),
                () -> assertEquals(List.of(bool), Stream.of(bool).flatMap(Selector.booleans()).toList()),
                () -> assertEquals(List.of(nul), Stream.of(nul).flatMap(Selector.nulls()).toList()),
                () -> assertEquals(List.of(arr), Stream.of(arr).flatMap(Selector.arrays()).toList()),
                () -> assertEquals(List.of(obj), Stream.of(obj).flatMap(Selector.objects()).toList()),
                // Basic and Aggregate are abstract supertypes — they accept any leaf or branch
                () -> assertEquals(List.of(num), Stream.of(num).flatMap(Selector.basics()).toList()),
                () -> assertEquals(List.of(str), Stream.of(str).flatMap(Selector.basics()).toList()),
                () -> assertEquals(List.of(arr), Stream.of(arr).flatMap(Selector.aggregates()).toList()),
                () -> assertEquals(List.of(obj), Stream.of(obj).flatMap(Selector.aggregates()).toList())
        );
    }

    @Test
    void testTypeSelectorsRejectMismatchedTypes() {
        // when the type doesn't match, the stream is empty
        var num = Basic.of(42);
        var str = Basic.of("hi");
        var arr = arrayBuilder().addBasic(1).build();
        var obj = objectBuilder().putBasic("a", 1).build();
        assertAll(
                // a number is not a string, boolean, null, array, or object
                () -> assertEquals(List.of(), Stream.of(num).flatMap(Selector.strings()).toList()),
                () -> assertEquals(List.of(), Stream.of(num).flatMap(Selector.booleans()).toList()),
                () -> assertEquals(List.of(), Stream.of(num).flatMap(Selector.nulls()).toList()),
                () -> assertEquals(List.of(), Stream.of(num).flatMap(Selector.arrays()).toList()),
                () -> assertEquals(List.of(), Stream.of(num).flatMap(Selector.objects()).toList()),
                // a string is a Basic but not an Aggregate
                () -> assertEquals(List.of(), Stream.of(str).flatMap(Selector.aggregates()).toList()),
                // an array is an Aggregate but not a Basic
                () -> assertEquals(List.of(), Stream.of(arr).flatMap(Selector.basics()).toList()),
                // an object is an Aggregate but not an array
                () -> assertEquals(List.of(), Stream.of(obj).flatMap(Selector.arrays()).toList())
        );
    }

    @Test
    void testTypeSelectorsCachedSingletons() {
        // factories return cached singletons — repeated calls return the same instance
        assertAll(
                () -> assertSame(Selector.basics(), Selector.basics()),
                () -> assertSame(Selector.aggregates(), Selector.aggregates()),
                () -> assertSame(Selector.numbers(), Selector.numbers()),
                () -> assertSame(Selector.strings(), Selector.strings()),
                () -> assertSame(Selector.booleans(), Selector.booleans()),
                () -> assertSame(Selector.nulls(), Selector.nulls()),
                () -> assertSame(Selector.arrays(), Selector.arrays()),
                () -> assertSame(Selector.objects(), Selector.objects())
        );
    }

    @Test
    void testTypeSelectorsInChain() {
        // typical use: fan out an array, then filter to a single JSON type
        var mixed = arrayBuilder()
                .addBasic(1)            // JsonNumber
                .addBasic("two")        // JsonString
                .addBasic(3)            // JsonNumber
                .addBasic(true)         // JsonBoolean
                .add(JsonNull.INSTANCE) // JsonNull
                .addBasic(4)            // JsonNumber
                .build();
        var numbersOnly = Stream.of(mixed)
                .flatMap(all())
                .flatMap(Selector.numbers())
                .toList();
        assertEquals(
                List.of(Basic.of(1), Basic.of(3), Basic.of(4)),
                numbersOnly
        );
    }

    @Test
    void testPointDropsUnresolved() {
        // given a heterogeneous array — some elements have a "name", some don't
        var arr = arrayBuilder()
                .add(objectBuilder().putBasic("id", 1).putBasic("name", "Ada"))
                .add(objectBuilder().putBasic("id", 2))                          // no name
                .add(objectBuilder().putBasic("id", 3).putBasic("name", "Bea"))
                .build();
        // when each element is narrowed to "name"
        var names = all().point(self().member("name"));
        // then unresolved elements drop silently — Optional::stream is empty for empty optionals
        var result = Stream.of(arr).flatMap(names)
                .flatMap(v -> v.string().stream())
                .toList();
        assertEquals(List.of("Ada", "Bea"), result);
    }

    @Test
    void testPointWhenSelectorYieldsNothing() {
        // an out-of-range slice produces no elements regardless of pointer
        var arr = arrayBuilder().addBasic(1).addBasic(2).build();
        var fn = Selector.range(5, 10).point(self());
        assertEquals(0L, Stream.of(arr).flatMap(fn).count());
    }

    @Test
    void testPointWithSelfPointerIsIdentity() {
        // .point(self()) should leave the selector's stream untouched; the
        // pointer is the one that resolves every value to itself
        var arr = arrayBuilder().addBasic(1).addBasic(2).addBasic(3).build();
        var direct = Stream.of(arr).flatMap(all()).toList();
        var viaPoint = Stream.of(arr).flatMap(all().point(self())).toList();
        assertEquals(direct, viaPoint);
    }

    @Test
    void testMeAndPointer() {
        // given [{"a": 1}, {"a":2, "b": 2}, {"d": 4}, {"a":4}]
        var ja = arrayBuilder()
                .add(objectBuilder().putBasic("a", 1))
                .add(objectBuilder().putBasic("a", 2).putBasic("b", 2))
                .add(objectBuilder().putBasic("d", 4))
                .add(objectBuilder().putBasic("a", 3).putBasic("c", 4))
                .build();
        // when
        var as = Stream.of(ja)
                .flatMap(Selector
                        .all() // a singleton or every array element
                        .point(self().member("a")) // member "a"
                )
                .flatMap(a -> a.decimal().map(BigDecimal::intValue).stream())
                .toList();
        // then
        assertEquals(List.of(1, 2, 3), as);
    }

    @Test
    void presentValues() {
        // [{"id":"a"}, {"x":1}, {"id":"c"}] — the middle object has no "id"
        var arr = arrayBuilder()
                .add(objectBuilder().putBasic("id", "a"))
                .add(objectBuilder().putBasic("x", 1))
                .add(objectBuilder().putBasic("id", "c"))
                .build();
        // fan out, extract the "id" string, drop the absent one
        var ids = all().presentValues(v -> self().member("id").stringValue(v)).apply(arr).toList();
        assertEquals(List.of("a", "c"), ids);
    }

    @Test
    void selectThenPresentValues() {
        // {"users": [{"email":"a@x"}, {"n":1}, {"email":"c@z"}]}
        var doc = objectBuilder()
                .put("users", arrayBuilder()
                        .add(objectBuilder().putBasic("email", "a@x"))
                        .add(objectBuilder().putBasic("n", 1))
                        .add(objectBuilder().putBasic("email", "c@z")))
                .build();
        // pointer navigates, select fans out, presentValues extracts + drops absent
        var emails = self().member("users").select(all())
                .presentValues(v -> self().member("email").stringValue(v))
                .apply(doc)
                .toList();
        assertEquals(List.of("a@x", "c@z"), emails);
    }
}
