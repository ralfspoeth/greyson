package io.github.ralfspoeth.json.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.ralfspoeth.json.data.JsonString.escaped;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JsonStringTest {

    /**
     * The point of the scan-first implementation: a string with nothing to
     * escape -- the common case by a wide margin -- allocates nothing and the
     * argument comes straight back. {@code assertSame} is what pins that; an
     * {@code assertEquals} would pass over an allocating implementation too.
     */
    @Test
    @DisplayName("A string needing no escaping is returned as-is")
    void cleanStringsAreNotCopied() {
        assertAll(
                () -> assertSame("", escaped("")),
                () -> assertSame("plain", escaped("plain")),
                () -> assertSame("spaces and punctuation: ok!", escaped("spaces and punctuation: ok!")),
                // non-ASCII is emitted literally, not as \\u escapes
                () -> assertSame("Grüße, 日本", escaped("Grüße, 日本")),
                // a surrogate pair is two chars, neither of them special
                () -> assertSame("emoji 😀", escaped("emoji 😀"))
        );
    }

    /**
     * The prefix before the first offending character is copied in one block,
     * which is the step a naive rewrite gets wrong -- so assert that the
     * prefix survives and is not duplicated or dropped.
     */
    @Test
    @DisplayName("Escaping starting mid-string keeps the prefix intact")
    void prefixIsPreserved() {
        assertAll(
                () -> assertEquals("abc\\n", escaped("abc\n")),
                () -> assertEquals("\\nabc", escaped("\nabc")),
                () -> assertEquals("a\\nb\\nc", escaped("a\nb\nc")),
                () -> assertEquals("abc\\ndef", escaped("abc\ndef"))
        );
    }

    @Test
    @DisplayName("Every character json.org calls out is escaped")
    void allSpecialCharacters() {
        assertAll(
                () -> assertEquals("\\\\", escaped("\\")),
                () -> assertEquals("\\\"", escaped("\"")),
                () -> assertEquals("\\/", escaped("/")),
                () -> assertEquals("\\b", escaped("\b")),
                () -> assertEquals("\\f", escaped("\f")),
                () -> assertEquals("\\n", escaped("\n")),
                () -> assertEquals("\\r", escaped("\r")),
                () -> assertEquals("\\t", escaped("\t"))
        );
    }

    /**
     * The control characters without a short form take the {@code \\uXXXX}
     * branch. 0x7f is deliberately not among them: DEL is not a control
     * character as far as RFC 8259 is concerned and needs no escape.
     */
    @Test
    @DisplayName("Control characters without a short form become \\uXXXX")
    void controlCharacters() {
        assertAll(
                () -> assertEquals("\\u0000", escaped("\0")),
                () -> assertEquals("\\u0001", escaped("\u0001")),
                () -> assertEquals("\\u001f", escaped("\u001f")),
                () -> assertSame("\u007f", escaped("\u007f"))
        );
    }

    /**
     * Guards the invariant that {@code needsEscape} and the switch in
     * {@code escapeFrom} agree: a character the scan stops on but the switch
     * passes through unchanged would make the whole sweep pointless work, and
     * one the scan skips but the switch would have escaped is a bug in the
     * output. Equivalently: escaping is idempotent only where it is a no-op.
     */
    @Test
    @DisplayName("The scan stops on exactly the characters the switch changes")
    void theScanAndTheSwitchAgree() {
        for (char c = 0; c < 0x100; c++) {
            var s = String.valueOf(c);
            var escaped = escaped(s);
            if (escaped.equals(s)) {
                assertSame(s, escaped, "no-op escape should not copy: 0x%02x".formatted((int) c));
            } else {
                assertEquals('\\', escaped.charAt(0), "0x%02x escaped to <%s>".formatted((int) c, escaped));
            }
        }
    }

    @Test
    @DisplayName("json() wraps the escaped value in quotes")
    void jsonQuotes() {
        assertAll(
                () -> assertEquals("\"a\"", new JsonString("a").json()),
                () -> assertEquals("\"\"", new JsonString("").json()),
                () -> assertEquals("\"a\\\"b\"", new JsonString("a\"b").json())
        );
    }
}
