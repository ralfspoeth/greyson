package io.github.ralfspoeth.json.data;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Represents a JSON string.
 *
 * @param value the string contents, must not be {@code null}.
 */
public record JsonString(String value) implements Basic<String> {
    public JsonString {
        value = requireNonNull(value);
    }

    /**
     * The escaped JSON string representation.
     */
    @Override
    public String json() {
        return "\"" + escaped(value) + "\"";
    }

    /**
     * Obtain the escaped version of the given string {@code s}.
     *
     * <p>Most strings contain nothing that needs escaping, and for those the
     * argument itself is returned: the scan below finds the first character
     * that does need it, and only then is anything allocated. Callers must not
     * rely on getting a distinct instance back.
     *
     * @param s a string, may not be {@code null}
     * @see <a href="http://json.org">json.org</a>
     */
    public static String escaped(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (needsEscape(s.charAt(i))) {
                return escapeFrom(s, i);
            }
        }
        return s;
    }

    /*
     * \b \f \n \r \t are all below 0x20, so the control-character test covers
     * them; char is unsigned, so no lower bound is needed. Keep in step with
     * the switch in escapeFrom.
     */
    private static boolean needsEscape(char c) {
        return c == '\\' || c == '"' || c == '/' || c <= 0x1f;
    }

    // s is clean up to first, so that prefix is copied in one go
    private static String escapeFrom(String s, int first) {
        var sb = new StringBuilder(s.length() + 16).append(s, 0, first);
        for (int i = first; i < s.length(); i++) {
            var c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '/' -> sb.append("\\/");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c <= 0x1f) {
                        // the remaining control characters, rare enough to afford format
                        sb.append("\\u%04x".formatted((int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    ///  @return a non-empty optional with {@code this.value()} as payload
    @Override
    public Optional<String> string() {
        return Optional.of(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonString(var s) && value.equals(s);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
