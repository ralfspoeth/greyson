package io.github.ralfspoeth.json.data;

import io.github.ralfspoeth.json.Greyson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Nesting depth is bounded by memory, not by the JVM call stack.
 *
 * <p>The reader has always kept its stack on the heap, which is why the JSON
 * Test Suite's {@code i_structure_500_nested_arrays.json} parses — but 500
 * frames is nothing, so that file never tested the property, only that the
 * number was small. {@code Greyson.readValue} is a parse followed by
 * {@code build()}, and {@code build()} recursed, so the guarantee stopped at
 * the parse. The same held for {@code JsonValue.builder()} in the other
 * direction.
 *
 * <p>50,000 is chosen to be past any plausible default call stack, so this
 * fails outright on a recursive implementation rather than passing by luck on
 * a large one.
 *
 * <p>Note what this test cannot use: {@code equals}, {@code hashCode},
 * {@code depth()}, {@code json()} and the record-generated {@code toString}
 * all recurse, so touching any of them here would overflow on a tree that is
 * perfectly well formed. Depth is therefore measured by walking.
 */
class DeepNestingTest {

    private static final int DEPTH = 50_000;

    private static String nestedArrays() {
        return "[".repeat(DEPTH) + "]".repeat(DEPTH);
    }

    /** Iterative, because {@link JsonValue#depth()} is not. */
    private static int depthOf(JsonValue v) {
        int d = 0;
        while (v instanceof JsonArray(var elements) && !elements.isEmpty()) {
            v = elements.getFirst();
            d++;
        }
        return d;
    }

    @Test
    @DisplayName("A 50,000-deep document parses, builds and converts back")
    void deeplyNestedSurvivesEveryStep() throws IOException {
        var source = nestedArrays();

        // the parse itself: already iterative before this change
        var builder = Greyson.readBuilder(Reader.of(source)).orElseThrow();

        // build(): the step that used to recurse, and the one readValue takes
        var value = builder.build();

        // and the inverse, JsonValue::builder -> Builder.of
        var roundTripped = value.builder().build();

        assertAll(
                () -> assertEquals(DEPTH - 1, depthOf(value)),
                () -> assertEquals(DEPTH - 1, depthOf(roundTripped))
        );
    }

    @Test
    @DisplayName("Greyson.readValue handles it too, in one call")
    void readValueIsIterativeEndToEnd() throws IOException {
        var value = Greyson.readValue(Reader.of(nestedArrays())).orElseThrow();
        assertEquals(DEPTH - 1, depthOf(value));
    }

    /**
     * Objects as well as arrays: the two sides of the traversal are separate
     * code paths in both directions.
     */
    @Test
    @DisplayName("Deeply nested objects behave the same")
    void deeplyNestedObjects() throws IOException {
        int depth = 20_000;
        var source = "{\"a\":".repeat(depth) + "1" + "}".repeat(depth);
        var value = Greyson.readValue(Reader.of(source)).orElseThrow();

        int steps = 0;
        var cursor = value;
        while (cursor instanceof JsonObject(var members) && !members.isEmpty()) {
            cursor = members.get("a");
            steps++;
        }
        // the loop variables have to be captured: a lambda needs them final
        final int reached = steps;
        final JsonValue innermost = cursor;
        assertAll(
                () -> assertEquals(depth, reached),
                () -> assertEquals(new JsonNumber(BigDecimal.ONE), innermost)
        );
    }
}
