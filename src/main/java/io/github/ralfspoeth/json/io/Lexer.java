package io.github.ralfspoeth.json.io;

import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.Arrays;

import static java.util.Objects.requireNonNull;

class Lexer implements Closeable {

    sealed interface Token permits LiteralToken, NumberToken, FixToken {
        String value();
    }

    enum Type {
        STRING, NULL, TRUE, FALSE
    }

    record LiteralToken(Type type, String value) implements Token {}

    /**
     * Number tokens carry a parsed {@link BigDecimal} payload so that the
     * reader does not have to re-parse a {@link String} representation.
     * The lexer constructs the {@code BigDecimal} directly from the scan
     * buffer's {@code char[]}, taking a {@code long} fast path for small
     * integers.
     */
    record NumberToken(BigDecimal number) implements Token {
        @Override
        public String value() {
            return number.toPlainString();
        }
    }

    enum FixToken implements Token {
        COMMA(","),
        COLON(":"),
        OPENING_BRACE("{"),
        CLOSING_BRACE("}"),
        OPENING_BRACKET("["),
        CLOSING_BRACKET("]");

        private final String value;

        FixToken(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }

    static final class InternalPushbackReader implements Closeable {

        private final Reader source;

        InternalPushbackReader(Reader source) {this.source = source;}

        // hand-rolled pushback facility
        private int ch = -1;

        int read() throws IOException {
            // if a character has been unread before, return it
            if (ch != -1) {
                var ret = ch;
                ch = -1;
                return ret;
            }
            // otherwise, read from the source
            else {
                return source.read();
            }
        }

        void unread(int ch) {
            this.ch = ch;
        }

        @Override
        public void close() throws IOException {
            source.close();
        }
    }

    private final InternalPushbackReader source;

    Lexer(Reader rdr) {
        this.source = new InternalPushbackReader(rdr);
    }

    private enum State {
        INITIAL,
        STRLIT, STRLIT_ESC, STRLIT_UC,
        NUM_LIT, CONST_LIT,
        EOF
    }

    // the next token
    private @Nullable Token nextToken;
    // the current state
    private State state = State.INITIAL;

    // very similar to a string builder
    static final class Buffer implements CharSequence {
        // the buffered char array
        private char[] buffer = new char[4_096];
        // the position where to add the next char read
        private int bufferPos = 0;

        void append(char c) {
            // double buffer size
            if (bufferPos == buffer.length) {
                char[] tmp = new char[buffer.length * 2];
                System.arraycopy(buffer, 0, tmp, 0, buffer.length);
                buffer = tmp;
            }
            // add the char
            buffer[bufferPos++] = c;
        }

        void appendCodePoint(int codePoint) {
            for (char c : Character.toChars(codePoint)) {
                append(c);
            }
        }

        String contentsAndReset() {
            var ret = String.valueOf(buffer, 0, bufferPos);
            bufferPos = 0;
            return ret;
        }

        /**
         * Direct read access to the backing array. The valid range is
         * {@code [0, length())}; callers must not mutate the array. Used
         * by {@link Lexer#numberToken()} to construct a {@link BigDecimal}
         * without going through an intermediate {@link String}.
         */
        char[] array() {
            return buffer;
        }

        void reset() {
            bufferPos = 0;
        }

        @Override
        public int length() {
            return bufferPos;
        }

        @Override
        public char charAt(int index) {
            return buffer[index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return String.valueOf(buffer, start, end);
        }

        @Override
        public String toString() {
            return String.valueOf(buffer, 0, bufferPos);
        }
    }

    // buffer for string and const literals
    private final Buffer buffer = new Buffer();

    // intermediate unicode sequence: accumulates four hex digits in an int.
    // The state machine only feeds valid hex digits ([0-9a-fA-F]) into add(c),
    // so we can decode each nibble with simple bit tricks: digits map via
    // {@code c - '0'}, letters via {@code (c & 0x5F) - 'A' + 10} which folds
    // {@code 'a'..'f'} onto {@code 'A'..'F'} before subtracting.
    static final class UnicodeSequence {
        private int acc = 0;
        private int len = 0;

        void add(char c) {
            int nibble = (c <= '9') ? (c - '0') : ((c & 0x5F) - 'A' + 10);
            acc = (acc << 4) | nibble;
            len++;
        }

        boolean isFull() {
            return len == 4;
        }

        int toCodePoint() {
            int r = acc;
            acc = 0;
            len = 0;
            return r;
        }
    }

    // unicode sequence
    private final UnicodeSequence unicSeq = new UnicodeSequence();

    /**
     * Hands back one shared {@link String} for repeated string literals
     * instead of allocating a fresh one each time.
     *
     * <p>Documents repeat strings relentlessly, and member names worst of all:
     * an array of 10,000 records with three members each contains 30,000
     * occurrences of three distinct names. Enum-like values — {@code "event"},
     * {@code "USD"}, {@code "OPEN"} — repeat the same way. Allocating a
     * {@code String} per occurrence was measurably the largest single item in
     * the lexer's share of parsing.
     *
     * <p>Lookup is on the scan buffer's {@code char[]} <em>before</em> any
     * {@code String} exists, so a hit allocates nothing at all. That is the
     * whole point: canonicalising after the fact would save retention but not
     * allocation.
     *
     * <p>Three properties keep this from becoming the unbounded static cache
     * that {@code JsonString.escaped} used to be — a memory leak in server
     * shape, since values carry attacker-controlled cardinality:
     * <ul>
     * <li><b>Per lexer, never static.</b> It dies with the reader, so nothing
     *     outlives the document it came from.</li>
     * <li><b>Fixed size, direct-mapped.</b> One slot per hash; a collision
     *     overwrites. No growth, no eviction policy, no bookkeeping. A stream
     *     of distinct strings simply keeps replacing slots and costs a hash
     *     and a failed comparison.</li>
     * <li><b>Short strings only.</b> Names are short; a long value neither
     *     enters nor is scanned for.</li>
     * </ul>
     *
     * <p>Sharing instances is safe because {@code String} is immutable, and
     * invisible because nothing in the API compares them by identity.
     */
    static final class StringCache {
        // power of two: the mask below depends on it
        private static final int CAPACITY = 128;
        private static final int MAX_LENGTH = 48;

        private final @Nullable String[] slots = new String[CAPACITY];

        String lookup(char[] buf, int len) {
            if (len == 0) return "";
            if (len > MAX_LENGTH) return String.valueOf(buf, 0, len);
            int hash = 0;
            for (int i = 0; i < len; i++) {
                hash = hash * 31 + buf[i];
            }
            // spread the high bits, as HashMap does: 31-polynomial hashes of
            // short ASCII strings vary mostly above the low seven bits
            int index = (hash ^ (hash >>> 16)) & (CAPACITY - 1);
            var cached = slots[index];
            if (cached != null && matches(cached, buf, len)) {
                return cached;
            }
            var fresh = String.valueOf(buf, 0, len);
            slots[index] = fresh;
            return fresh;
        }

        private static boolean matches(String s, char[] buf, int len) {
            if (s.length() != len) return false;
            for (int i = 0; i < len; i++) {
                if (s.charAt(i) != buf[i]) return false;
            }
            return true;
        }
    }

    private final StringCache strings = new StringCache();


    boolean hasNext() throws IOException {
        readNextToken();
        return nextToken != null;
    }

    Token next() {
        var tmp = requireNonNull(nextToken);
        nextToken = null;
        return tmp;
    }

    private void readNextToken() throws IOException {
        while (state != State.EOF && nextToken == null) {
            int r = source.read();
            // special case -1 (EOF)
            if (r == -1) {
                state = switch (state) {
                    case NUM_LIT, CONST_LIT -> literal();
                    case STRLIT, STRLIT_ESC, STRLIT_UC ->
                            throw new JsonParseException("unexpected end of file in string literal " + Arrays.toString(buffer.buffer), row, column);
                    case INITIAL, EOF -> State.EOF;
                };
            } else {
                // char value for codepoint
                char c = (char) r;
                // row/column accounting
                if (c == '\n') {
                    row++;
                    column = 1;
                } else {
                    column++;
                }
                state = switch (state) {
                    case INITIAL -> switch (c) {
                        // white space
                        case ' ', '\t', '\r', '\n' -> State.INITIAL;
                        // start of numbers
                        case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> appendAndState(c, State.NUM_LIT);
                        // start of strings
                        case '\"' -> State.STRLIT;
                        // start of const literals
                        case 'n', 't', 'f' -> appendAndState(c, State.CONST_LIT);
                        // separator char
                        case '{', '}', '[', ']', ':', ',' -> fixToken(c);
                        // else error
                        default -> parseException("Unexpected character: " + c);
                    };

                    case NUM_LIT -> switch (c) {
                        // append num char
                        case '.', 'e', 'E', '+', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> append(c);
                        //  unexpected char
                        case 'n', 't', 'f', 'u', 'r', 'a', 'l', 's', ',', ':', '}', ']', '\"', '{', '[' -> unread(c);
                        // separator
                        case ' ', '\t', '\r', '\n' -> literal();
                        default -> parseException("Unexpected character: " + c + " in number literal");
                    };

                    case CONST_LIT -> switch (c) {
                        case 'a', 'l', 's', 'e', 't', 'r', 'u' -> append(c);
                        case ',', '}', ']', '\"', '{', '[', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' ->
                                unread(c);
                        case ' ', '\t', '\r', '\n' -> literal();
                        default -> parseException("Misspelled literal: " + c);
                    };

                    case STRLIT -> switch (c) {
                        case '\"' -> stringLiteral();
                        case '\\' -> State.STRLIT_ESC;
                        default -> (r <= 0x001F) ? parseException("Unexpected control character " + c) : append(c);
                    };

                    case STRLIT_ESC -> switch (c) {
                        case 'u' -> State.STRLIT_UC;
                        case 'n' -> appendAndState('\n', State.STRLIT);
                        case 'r' -> appendAndState('\r', State.STRLIT);
                        case 't' -> appendAndState('\t', State.STRLIT);
                        case '\\' -> appendAndState('\\', State.STRLIT);
                        case '"' -> appendAndState('\"', State.STRLIT);
                        case 'f' -> appendAndState('\f', State.STRLIT);
                        case '/' -> appendAndState('/', State.STRLIT);
                        case 'b' -> appendAndState('\b', State.STRLIT);
                        case '\t', '\r', '\n', ' ', '\f' -> parseException("control character after escape " + c);
                        default -> (Character.isLetterOrDigit(c) || r > 0x001F || r == 0)
                                ? parseException("escaped non-control character " + c)
                                : appendAndState(c, State.STRLIT);
                    };

                    case STRLIT_UC -> switch (c) {
                        case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'A', 'B',
                             'C', 'D', 'E', 'F' -> {
                            unicSeq.add(c);
                            if (unicSeq.isFull()) {
                                buffer.appendCodePoint(unicSeq.toCodePoint());
                                yield State.STRLIT;
                            } else {
                                yield State.STRLIT_UC;
                            }
                        }
                        default -> parseException("Unexpected character " + c + " in unicode sequence");
                    };

                    case EOF -> parseException("character " + c + " after end of file");
                };
            }
        }
    }

    private State unread(char c) {
        source.unread(c);
        return literal();
    }

    private State fixToken(char c) {
        nextToken = switch (c) {
            case '{' -> FixToken.OPENING_BRACE;
            case '}' -> FixToken.CLOSING_BRACE;
            case '[' -> FixToken.OPENING_BRACKET;
            case ']' -> FixToken.CLOSING_BRACKET;
            case ':' -> FixToken.COLON;
            case ',' -> FixToken.COMMA;
            default -> throw new AssertionError();
        };
        return State.INITIAL;
    }

    private State stringLiteral() {
        var str = strings.lookup(buffer.array(), buffer.length());
        nextToken = new LiteralToken(Type.STRING, str);
        buffer.reset();
        return State.INITIAL;
    }

    private State literal() {
        // The state we're transitioning out of disambiguating which kind of
        // literal sits in the buffer: NUM_LIT means a JSON number, CONST_LIT
        // means one of {null, true, false}.
        nextToken = (state == State.NUM_LIT) ? numberToken() : constLiteralToken();
        return State.INITIAL;
    }

    /*
     * The three keyword tokens are constants, so a document full of nulls and
     * booleans allocates nothing for them: no String, no LiteralToken. The
     * buffer is compared in place rather than turned into a String to switch
     * on, which is the same saving the StringCache makes for names, and
     * exact rather than probabilistic because there are only three answers.
     */
    private static final LiteralToken NULL_TOKEN = new LiteralToken(Type.NULL, "null");
    private static final LiteralToken TRUE_TOKEN = new LiteralToken(Type.TRUE, "true");
    private static final LiteralToken FALSE_TOKEN = new LiteralToken(Type.FALSE, "false");

    private Token constLiteralToken() {
        Token token = null;
        if (holds("null")) token = NULL_TOKEN;
        else if (holds("true")) token = TRUE_TOKEN;
        else if (holds("false")) token = FALSE_TOKEN;
        if (token == null) {
            // only now is a String worth building, and the parse is over
            throw new JsonParseException("misspelled literal: " + buffer.contentsAndReset(), row, column);
        }
        buffer.reset();
        return token;
    }

    private boolean holds(String keyword) {
        if (buffer.length() != keyword.length()) return false;
        var buf = buffer.array();
        for (int i = 0; i < keyword.length(); i++) {
            if (buf[i] != keyword.charAt(i)) return false;
        }
        return true;
    }

    private State appendAndState(char c, Lexer.State s) {
        buffer.append(c);
        return s;
    }

    private State append(char c) {
        buffer.append(c);
        return state;
    }

    private State parseException(String msg) {
        throw new JsonParseException(msg, row, column);
    }

    private Token numberToken() {
        if (!jsonNumber(buffer)) {
            // Capture the offending text before resetting the buffer.
            var text = buffer.contentsAndReset();
            throw new JsonParseException("cannot parse %s as a JSON number".formatted(text), row, column);
        }
        var bd = parseBigDecimal(buffer.array(), buffer.length());
        buffer.reset();
        return new NumberToken(bd);
    }

    /**
     * Convert digits in {@code arr[0..len)} into a {@link BigDecimal}.
     * The caller must have validated the slice via {@link #jsonNumber(CharSequence)};
     * we rely on that and avoid re-validation here.
     *
     * <p>Two performance shortcuts:</p>
     * <ul>
     *   <li>Pure integers up to 18 digits (with optional leading {@code '-'})
     *       are returned via {@link BigDecimal#valueOf(long)}, which caches
     *       small values and skips numeric parsing entirely.</li>
     *   <li>Everything else uses {@link BigDecimal#BigDecimal(char[], int, int)},
     *       avoiding the intermediate {@link String} that
     *       {@link BigDecimal#BigDecimal(String)} would allocate.</li>
     * </ul>
     *
     * <p>{@code Long.MAX_VALUE} has 19 digits; we cap the long path at 18 to
     * sidestep overflow checks. {@code -1L * Long.MIN_VALUE} would also
     * overflow, but that requires 19 digits, so the cap covers it.</p>
     */
    private static BigDecimal parseBigDecimal(char[] arr, int len) {
        int start = 0;
        boolean negative = false;
        if (arr[0] == '-') {
            negative = true;
            start = 1;
        }
        int digits = len - start;
        if (digits >= 1 && digits <= 18) {
            long val = 0;
            for (int i = start; i < len; i++) {
                char c = arr[i];
                if (c < '0' || c > '9') {
                    // Has a fractional or exponent part — fall through.
                    return new BigDecimal(arr, 0, len);
                }
                val = val * 10 + (c - '0');
            }
            return BigDecimal.valueOf(negative ? -val : val);
        }
        return new BigDecimal(arr, 0, len);
    }

    // parse numbers; hand-rolled, no longer using a regular expression
    private static boolean jsonNumber(CharSequence cs) {
        if (cs.isEmpty()) return false;

        int i = 0;
        int len = cs.length();

        // 1. Optional Minus
        if (cs.charAt(i) == '-') {
            i++;
            if (i == len) return false; // Just a minus sign is not a number
        }

        // 2. Integer Part
        char c = cs.charAt(i);
        if (c == '0') {
            i++;
            // If it starts with 0, the next char cannot be another digit
        } else if (c >= '1' && c <= '9') {
            do {
                i++;
            } while (i < len && (c = cs.charAt(i)) >= '0' && c <= '9');
        } else {
            return false; // No valid integer part
        }

        // 3. Fractional Part
        if (i < len && cs.charAt(i) == '.') {
            i++;
            if (i == len) return false; // Decimal must be followed by digits

            int startI = i;
            while (i < len && (c = cs.charAt(i)) >= '0' && c <= '9') {
                i++;
            }
            if (i == startI) return false; // At least one digit required
        }

        // 4. Exponent Part
        if (i < len && ((c = cs.charAt(i)) == 'e' || c == 'E')) {
            i++;
            if (i == len) return false; // e/E must be followed by something

            c = cs.charAt(i);
            if (c == '+' || c == '-') {
                i++;
                if (i == len) return false;
            }

            int startI = i;
            while (i < len && (c = cs.charAt(i)) >= '0' && c <= '9') {
                i++;
            }
            if (i == startI) return false; // At least one digit required
        }

        // Must have consumed the entire CharSequence to be a valid match
        return i == len;
    }

    private int row = 1, column = 1;

    int row() {
        return row;
    }

    int column() {
        return column;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}