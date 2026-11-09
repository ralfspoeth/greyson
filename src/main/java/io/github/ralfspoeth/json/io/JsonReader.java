package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.Builder;
import io.github.ralfspoeth.json.data.JsonArray;
import io.github.ralfspoeth.json.data.JsonBoolean;
import io.github.ralfspoeth.json.data.JsonNull;
import io.github.ralfspoeth.json.data.JsonNumber;
import io.github.ralfspoeth.json.data.JsonObject;
import io.github.ralfspoeth.json.data.JsonString;
import io.github.ralfspoeth.json.data.JsonValue;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Reads JSON from a {@link Reader} into a {@link JsonValue}, or into a
 * {@link Builder} when the point of reading is to edit.
 *
 * <p>The class implements {@link Closeable} and takes ownership of the reader
 * it is given: closing this closes that one.
 *
 * <h2>Shape of the implementation</h2>
 *
 * <p>There is no separate tokenizer. A token is control flow here rather than
 * an object, so scanning a document allocates only what ends up in the result:
 * the builders, the strings and the numbers. The previous design produced a
 * {@code Token} record per token and a stack element per structural step, and
 * measurement put that pipeline at over half the cost of a parse.
 *
 * <p>Three things it does deliberately:
 *
 * <ul>
 * <li><b>Reads in blocks.</b> Characters come out of a {@code char[]} filled
 *     by {@link Reader#read(char[], int, int)}, not one
 *     {@link Reader#read()} call each. This does not second-guess the reader
 *     handed in — a caller who already wrapped theirs loses nothing — it only
 *     avoids making half a million virtual calls to read half a million
 *     characters.</li>
 * <li><b>Keeps its stack on the heap.</b> Depth is bounded by memory, not by
 *     the JVM call stack, which is why
 *     {@code i_structure_500_nested_arrays.json} parses rather than
 *     overflowing. Recursive descent would read better and would cost that
 *     property; see the package documentation.</li>
 * <li><b>Shares repeated strings.</b> Member names and enum-like values repeat
 *     relentlessly, and the lookup happens on the buffer before any
 *     {@link String} exists, so a repeat allocates nothing at all.</li>
 * </ul>
 */
public class JsonReader implements Closeable {

    /*
     * The buffer starts small and grows only once the source proves it needs
     * more. A reader is created per document, so a fixed 8k buffer is ~16kB
     * charged to every parse however small -- which for a JSON-RPC message or
     * a config file is most of what the parse allocates. Documents that fit in
     * the initial size never pay for the rest; larger ones take one extra
     * read() call, against the hundreds they make anyway.
     */
    private static final int INITIAL_BUFFER_SIZE = 1024;
    private static final int MAX_BUFFER_SIZE = 8192;

    private final Reader source;

    // buf[pos, end) is the unconsumed window; eof once the source is drained
    private char[] buf = new char[INITIAL_BUFFER_SIZE];
    private int pos;
    private int end;
    private boolean eof;

    // scratch for the two cases a value cannot be taken straight from buf:
    // a string containing escapes, and a token straddling a refill
    private char[] scratch = new char[128];
    private int scratchLen;

    /*
     * One flat stack of finished items, and a frame stack of marks into it.
     *
     * Values accumulate on `items` as they are completed: for an array its
     * elements, for an object its member names and values alternating. An
     * aggregate records `top` when it opens, so when it closes, everything
     * above that mark is exactly its contents -- which are then replaced by
     * the single value they make. No node is allocated to hold a
     * partially-built aggregate, which is the allocation the previous
     * builder-and-element-stack design existed to pay for.
     */
    private Object[] items = new Object[64];
    private int top;

    private int[] marks = new int[32];
    private boolean[] isObject = new boolean[32];
    private int sp = -1;

    // position reporting: absolute offset of buf[0], and of the current line
    private long consumed;
    private long lineStart;
    private int row = 1;

    /**
     * Instantiate this class on top of a {@link Reader}.
     * The source will be closed together with {@code this}.
     *
     * @param src the character source
     */
    public JsonReader(Reader src) {
        this.source = requireNonNull(src);
    }

    /**
     * Reads the source, if it contains a complete and terminated JSON shape.
     * An empty source is not an error: it yields an empty {@link Optional}.
     *
     * <p>Values are what the parser produces, so this is the direct path and
     * the one to prefer.
     *
     * @return the parsed value, or empty when the source holds only whitespace
     * @throws IOException         when the reader throws
     * @throws JsonParseException  when the source is not one JSON document
     */
    public Optional<JsonValue> readValue() throws IOException {
        if (!skipWhitespace()) {
            return Optional.empty();
        }
        var root = parse();
        if (skipWhitespace()) {
            throw parseEx("Input contains tokens after the first element");
        }
        return Optional.of(root);
    }

    /**
     * The same document as a {@link Builder}, ready to be edited.
     *
     * <p>The parser produces values, so this converts — which is the right way
     * round: reading in order to edit is the rarer of the two.
     *
     * @return the parsed builder, or empty when the source holds only whitespace
     * @throws IOException        when the reader throws
     * @throws JsonParseException when the source is not one JSON document
     */
    public Optional<Builder<? extends JsonValue>> readBuilder() throws IOException {
        return readValue().map(JsonValue::builder);
    }

    /**
     * @return the parsed builder, or empty when the source holds only whitespace
     * @throws IOException        when the reader throws
     * @throws JsonParseException when the source is not one JSON document
     * @deprecated the name says nothing about what comes back. Use
     * {@link #readBuilder()}, or {@link #readValue()} if a value is what you
     * wanted, which it usually is.
     */
    @Deprecated(since = "2.4", forRemoval = true)
    public Optional<Builder<? extends JsonValue>> read() throws IOException {
        return readBuilder();
    }

    // ---- the parser ------------------------------------------------

    /*
     * States. VALUE_FIRST and NAME_FIRST differ from VALUE and NAME only in
     * admitting the matching closing bracket, which is what makes "[]" and
     * "{}" legal while "[1,]" and "{\"a\":1,}" are not -- a trailing comma
     * leaves the parser in VALUE or NAME, where a bracket is a syntax error.
     */
    private static final int VALUE = 0;
    private static final int VALUE_FIRST = 1;
    private static final int NAME = 2;
    private static final int NAME_FIRST = 3;
    private static final int AFTER = 4;

    private JsonValue parse() throws IOException {
        top = 0;
        sp = -1;
        int state = VALUE;
        for (;;) {
            // the only exit: a value finished with nothing left open
            if (state == AFTER && sp < 0) {
                return (JsonValue) items[--top];
            }
            char c = requireChar();
            switch (state) {
                case VALUE, VALUE_FIRST -> {
                    if (c == ']' && state == VALUE_FIRST) {
                        pos++;
                        closeArray();
                        state = AFTER;
                    } else if (c == '{') {
                        pos++;
                        open(true);
                        state = NAME_FIRST;
                    } else if (c == '[') {
                        pos++;
                        open(false);
                        state = VALUE_FIRST;
                    } else {
                        pushItem(scalar());
                        state = AFTER;
                    }
                }
                case NAME, NAME_FIRST -> {
                    if (c == '}' && state == NAME_FIRST) {
                        pos++;
                        closeObject();
                        state = AFTER;
                    } else if (c == '"') {
                        pos++;
                        pushItem(readString());   // the member name, paired with the value to come
                        if (requireChar() != ':') {
                            throw parseEx("expected ':' after a member name");
                        }
                        pos++;
                        state = VALUE;
                    } else {
                        throw parseEx("expected a member name, got " + c);
                    }
                }
                case AFTER -> {
                    switch (c) {
                        case ',' -> {
                            pos++;
                            state = isObject[sp] ? NAME : VALUE;
                        }
                        case '}' -> {
                            if (!isObject[sp]) {
                                throw parseEx("unexpected token: }");
                            }
                            pos++;
                            closeObject();
                        }
                        case ']' -> {
                            if (isObject[sp]) {
                                throw parseEx("unexpected token: ]");
                            }
                            pos++;
                            closeArray();
                        }
                        default -> throw parseEx("unexpected character: " + c);
                    }
                }
                default -> throw new AssertionError(state);
            }
        }
    }

    private void open(boolean object) {
        if (++sp == marks.length) {
            // depth is bounded by memory rather than by the call stack
            marks = Arrays.copyOf(marks, sp * 2);
            isObject = Arrays.copyOf(isObject, sp * 2);
        }
        marks[sp] = top;
        isObject[sp] = object;
    }

    private void pushItem(Object item) {
        if (top == items.length) {
            items = Arrays.copyOf(items, top * 2);
        }
        items[top++] = item;
    }

    private void closeArray() {
        int mark = marks[sp--];
        int n = top - mark;
        List<JsonValue> elements;
        if (n == 0) {
            elements = List.of();
        } else {
            var a = new JsonValue[n];
            System.arraycopy(items, mark, a, 0, n);
            elements = List.of(a);
        }
        top = mark;
        pushItem(new JsonArray(elements));
    }

    /*
     * Above the mark the stack holds name, value, name, value. The usual case
     * -- no repeated name -- goes straight to an immutable map, which
     * JsonObject's Map.copyOf then passes through untouched.
     *
     * Repeated names are legal and must collapse to the last one (RFC 8259
     * leaves it open; the JSON Test Suite requires such documents to be
     * accepted), but Map.ofEntries throws on them. A map built by putting is
     * the fallback, reached only for a document that actually repeats a name.
     * The scan that decides is quadratic, so wide objects skip it and take
     * the fallback directly -- they are rare, and n^2 on a thousand members
     * is a denial-of-service lever where an extra map is not.
     */
    private static final int DUPLICATE_SCAN_LIMIT = 16;

    private void closeObject() {
        int mark = marks[sp--];
        int n = (top - mark) / 2;
        Map<String, JsonValue> members;
        if (n == 0) {
            members = Map.of();
        } else if (n <= DUPLICATE_SCAN_LIMIT && !repeatsAName(mark, n)) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, JsonValue>[] entries = new Map.Entry[n];
            for (int i = 0; i < n; i++) {
                entries[i] = Map.entry((String) items[mark + 2 * i], (JsonValue) items[mark + 2 * i + 1]);
            }
            members = Map.ofEntries(entries);
        } else {
            var m = new LinkedHashMap<String, JsonValue>(n * 2);
            for (int i = 0; i < n; i++) {
                m.put((String) items[mark + 2 * i], (JsonValue) items[mark + 2 * i + 1]);
            }
            members = Map.copyOf(m);
        }
        top = mark;
        pushItem(new JsonObject(members));
    }

    private boolean repeatsAName(int mark, int n) {
        for (int i = 0; i < n; i++) {
            var name = items[mark + 2 * i];
            for (int j = i + 1; j < n; j++) {
                // the string cache usually makes this an identity hit
                if (name.equals(items[mark + 2 * j])) return true;
            }
        }
        return false;
    }

    private JsonValue scalar() throws IOException {
        char c = buf[pos];
        return switch (c) {
            case '"' -> {
                pos++;
                yield new JsonString(readString());
            }
            case 't' -> {
                keyword("true");
                yield JsonBoolean.TRUE;
            }
            case 'f' -> {
                keyword("false");
                yield JsonBoolean.FALSE;
            }
            case 'n' -> {
                keyword("null");
                yield JsonNull.INSTANCE;
            }
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw parseEx("Unexpected character: " + c);
            }
        };
    }

    private void keyword(String kw) throws IOException {
        for (int i = 0; i < kw.length(); i++) {
            if (pos == end && !refill()) {
                throw parseEx("unexpected end of file in literal " + kw);
            }
            if (buf[pos] != kw.charAt(i)) {
                throw parseEx("misspelled literal, expected " + kw);
            }
            pos++;
        }
    }

    // ---- strings ---------------------------------------------------

    /*
     * Called with pos just past the opening quote. The common case -- no
     * escapes, no refill in the middle -- never touches the scratch buffer and
     * never builds an intermediate: the cache is probed on buf itself, so a
     * repeated name or value allocates nothing.
     */
    private String readString() throws IOException {
        int start = pos;
        for (int i = pos; i < end; i++) {
            char c = buf[i];
            if (c == '"') {
                var s = strings.lookup(buf, start, i - start);
                pos = i + 1;
                return s;
            }
            if (c == '\\' || c < 0x20) {
                return readStringSlow(start, i);
            }
        }
        return readStringSlow(start, end);
    }

    /** {@code buf[start, from)} is already known to be clean. */
    private String readStringSlow(int start, int from) throws IOException {
        scratchLen = 0;
        appendScratch(buf, start, from - start);
        pos = from;
        for (;;) {
            if (pos == end && !refill()) {
                throw parseEx("unexpected end of file in string literal");
            }
            char c = buf[pos++];
            if (c == '"') {
                return strings.lookup(scratch, 0, scratchLen);
            } else if (c == '\\') {
                readEscape();
            } else if (c < 0x20) {
                throw parseEx("Unexpected control character " + (int) c);
            } else {
                appendScratch(c);
            }
        }
    }

    private void readEscape() throws IOException {
        if (pos == end && !refill()) {
            throw parseEx("unexpected end of file after an escape");
        }
        char c = buf[pos++];
        switch (c) {
            case '"' -> appendScratch('"');
            case '\\' -> appendScratch('\\');
            case '/' -> appendScratch('/');
            case 'b' -> appendScratch('\b');
            case 'f' -> appendScratch('\f');
            case 'n' -> appendScratch('\n');
            case 'r' -> appendScratch('\r');
            case 't' -> appendScratch('\t');
            case 'u' -> {
                int acc = 0;
                for (int i = 0; i < 4; i++) {
                    if (pos == end && !refill()) {
                        throw parseEx("unexpected end of file in a unicode escape");
                    }
                    acc = (acc << 4) | hex(buf[pos++]);
                }
                appendScratch((char) acc);
            }
            default -> throw parseEx("escaped non-control character " + c);
        }
    }

    /*
     * Digits map via c - '0'; letters via (c & 0x5F) - 'A' + 10, which folds
     * 'a'..'f' onto 'A'..'F' before subtracting.
     */
    private int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) return (c & 0x5F) - 'A' + 10;
        throw parseEx("Unexpected character " + c + " in unicode sequence");
    }

    // ---- numbers ---------------------------------------------------

    private static boolean numberChar(char c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E';
    }

    private JsonNumber readNumber() throws IOException {
        int start = pos;
        for (int i = pos; i < end; i++) {
            if (!numberChar(buf[i])) {
                var n = number(buf, start, i - start);
                pos = i;
                return n;
            }
        }
        // straddles a refill
        scratchLen = 0;
        appendScratch(buf, start, end - start);
        pos = end;
        for (;;) {
            if (pos == end && !refill()) break;
            char c = buf[pos];
            if (!numberChar(c)) break;
            appendScratch(c);
            pos++;
        }
        return number(scratch, 0, scratchLen);
    }

    private JsonNumber number(char[] a, int off, int len) {
        if (!isJsonNumber(a, off, len)) {
            throw parseEx("cannot parse %s as a JSON number".formatted(String.valueOf(a, off, len)));
        }
        return new JsonNumber(parseBigDecimal(a, off, len));
    }

    /**
     * Grammar check from <a href="https://www.json.org">json.org</a>: an
     * optional minus, an integer part with no leading zero, an optional
     * fraction, an optional exponent, and nothing left over.
     */
    private static boolean isJsonNumber(char[] a, int off, int len) {
        if (len == 0) return false;
        int i = off;
        int limit = off + len;
        if (a[i] == '-' && ++i == limit) return false;
        char c = a[i];
        if (c == '0') {
            i++;
        } else if (c >= '1' && c <= '9') {
            do {
                i++;
            } while (i < limit && (c = a[i]) >= '0' && c <= '9');
        } else {
            return false;
        }
        if (i < limit && a[i] == '.') {
            if (++i == limit) return false;
            int from = i;
            while (i < limit && (c = a[i]) >= '0' && c <= '9') i++;
            if (i == from) return false;
        }
        if (i < limit && ((c = a[i]) == 'e' || c == 'E')) {
            if (++i == limit) return false;
            c = a[i];
            if ((c == '+' || c == '-') && ++i == limit) return false;
            int from = i;
            while (i < limit && (c = a[i]) >= '0' && c <= '9') i++;
            if (i == from) return false;
        }
        return i == limit;
    }

    /**
     * Pure integers of up to 18 digits take {@link BigDecimal#valueOf(long)},
     * which caches small values and skips numeric parsing; everything else
     * goes through {@link BigDecimal#BigDecimal(char[], int, int)}, avoiding
     * the intermediate {@link String}. {@code Long.MAX_VALUE} has 19 digits,
     * so 18 sidesteps overflow checks.
     */
    private static BigDecimal parseBigDecimal(char[] a, int off, int len) {
        int start = off;
        boolean negative = a[off] == '-';
        if (negative) start++;
        int digits = off + len - start;
        if (digits >= 1 && digits <= 18) {
            long val = 0;
            for (int i = start; i < off + len; i++) {
                char c = a[i];
                if (c < '0' || c > '9') {
                    return new BigDecimal(a, off, len);
                }
                val = val * 10 + (c - '0');
            }
            return BigDecimal.valueOf(negative ? -val : val);
        }
        return new BigDecimal(a, off, len);
    }

    // ---- buffer plumbing -------------------------------------------

    private boolean refill() throws IOException {
        if (eof) return false;
        consumed += end;
        /*
         * The last read filled the buffer exactly, so there is more document
         * than buffer: grow before asking again. Safe to replace the array
         * here because refill is only reached with pos == end, and anything
         * still needed has already been copied into scratch.
         */
        if (end == buf.length && buf.length < MAX_BUFFER_SIZE) {
            buf = new char[MAX_BUFFER_SIZE];
        }
        int n;
        do {
            n = source.read(buf, 0, buf.length);
        } while (n == 0);
        if (n < 0) {
            eof = true;
            pos = 0;
            end = 0;
            return false;
        }
        pos = 0;
        end = n;
        return true;
    }

    /** Leaves {@code pos} on the next non-whitespace character. */
    private boolean skipWhitespace() throws IOException {
        for (;;) {
            while (pos < end) {
                char c = buf[pos];
                if (c == '\n') {
                    pos++;
                    row++;
                    lineStart = consumed + pos;
                } else if (c == ' ' || c == '\t' || c == '\r') {
                    pos++;
                } else {
                    return true;
                }
            }
            if (!refill()) return false;
        }
    }

    private char requireChar() throws IOException {
        if (!skipWhitespace()) {
            throw parseEx("unexpected end of file");
        }
        return buf[pos];
    }

    private void appendScratch(char c) {
        if (scratchLen == scratch.length) {
            scratch = Arrays.copyOf(scratch, scratchLen * 2);
        }
        scratch[scratchLen++] = c;
    }

    private void appendScratch(char[] a, int off, int len) {
        while (scratchLen + len > scratch.length) {
            scratch = Arrays.copyOf(scratch, scratch.length * 2);
        }
        System.arraycopy(a, off, scratch, scratchLen, len);
        scratchLen += len;
    }

    private JsonParseException parseEx(String msg) {
        return new JsonParseException(msg, row, (int) (consumed + pos - lineStart) + 1);
    }

    // ---- shared strings --------------------------------------------

    /**
     * Hands back one shared {@link String} for a repeated literal instead of
     * allocating a fresh one. Lookup is on the buffer, before any
     * {@code String} exists, so a hit allocates nothing; canonicalising after
     * the fact would save retention but not allocation.
     *
     * <p>Three properties keep this from being the unbounded static cache that
     * was removed from {@code JsonString.escaped} — a memory leak in server
     * shape, values carrying remote-controlled cardinality. It belongs to one
     * reader and dies with it; it is fixed-size and direct-mapped, so a
     * collision overwrites and a flood of distinct strings costs a hash and a
     * failed comparison rather than growth; and it ignores long strings
     * entirely.
     *
     * <p>Sharing is safe because {@code String} is immutable, and invisible
     * because nothing in the API compares instances.
     */
    static final class StringCache {
        private static final int CAPACITY = 128;   // power of two: see the mask
        private static final int MAX_LENGTH = 48;

        private final String[] slots = new String[CAPACITY];

        String lookup(char[] a, int off, int len) {
            if (len == 0) return "";
            if (len > MAX_LENGTH) return String.valueOf(a, off, len);
            int hash = 0;
            for (int i = off; i < off + len; i++) {
                hash = hash * 31 + a[i];
            }
            // spread the high bits, as HashMap does: 31-polynomial hashes of
            // short ASCII strings vary mostly above the low seven bits
            int index = (hash ^ (hash >>> 16)) & (CAPACITY - 1);
            var cached = slots[index];
            if (cached != null && matches(cached, a, off, len)) {
                return cached;
            }
            var fresh = String.valueOf(a, off, len);
            slots[index] = fresh;
            return fresh;
        }

        private static boolean matches(String s, char[] a, int off, int len) {
            if (s.length() != len) return false;
            for (int i = 0; i < len; i++) {
                if (s.charAt(i) != a[off + i]) return false;
            }
            return true;
        }
    }

    private final StringCache strings = new StringCache();

    int row() {
        return row;
    }

    int column() {
        return (int) (consumed + pos - lineStart) + 1;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
