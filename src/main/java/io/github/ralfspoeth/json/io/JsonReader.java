package io.github.ralfspoeth.json.io;

import io.github.ralfspoeth.json.data.*;
import io.github.ralfspoeth.json.data.Builder.ArrayBuilder;
import io.github.ralfspoeth.json.data.Builder.ObjectBuilder;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

import static io.github.ralfspoeth.json.io.JsonReader.Elem.ArrBuilderElem.arrBuilderElem;
import static io.github.ralfspoeth.json.io.JsonReader.Elem.ObjBuilderElem.objBuilderElem;
import static io.github.ralfspoeth.json.io.Lexer.FixToken.*;
import static io.github.ralfspoeth.json.io.Lexer.Type.STRING;

/**
 * Instances parse character streams into JSON {@link JsonValue}s.
 * The class implements {@link AutoCloseable} and is meant to
 * be used in try-with-resources statements like this:
 * {@snippet :
 * // given
 * Reader r = new StringReader(); // @replace substring="new StringReader();" replacement="..."
 * try(var jr = new JsonReader(r)) {
 *     return jr.read();
 * }
 *}
 * The class supports strict adherence to the JSON specification
 * exclusively; there is no support for the sloppy variant.
 */
public class JsonReader implements Closeable {

    private final Lexer lexer;

    /**
     * Instantiate this class on top of a {@link Reader}.
     * The source will be closed together with {@code this}.
     *
     * @param src the character source
     */
    public JsonReader(Reader src) {
        this.lexer = new Lexer(src);
    }


    sealed interface Elem {
        record ObjBuilderElem(ObjectBuilder builder) implements Elem {
            static ObjBuilderElem objBuilderElem() {
                return new ObjBuilderElem(Builder.objectBuilder());
            }
        }

        record ArrBuilderElem(ArrayBuilder builder) implements Elem {
            static ArrBuilderElem arrBuilderElem() {
                return new ArrBuilderElem(Builder.arrayBuilder());
            }
        }

        record NameElem(String name) implements Elem {}

        enum Char implements Elem {colon, comma}

        record Root(Builder<?> elem) implements Elem {
        }
    }

    // parser state: stack of partially-built elements
    private final Deque<Elem> stack = new ArrayDeque<>();

    /**
     * Reads the source and returns a {@link Builder} for
     * a {@link JsonValue}, if the source contains
     * a complete and terminated JSON shape.
     *
     * @throws IOException whenever the lexer throws
     */
    public Optional<Builder<? extends JsonValue>> read() throws IOException {
        var result = readNextElement();
        if (lexer.hasNext()) {
            throw new JsonParseException("Input contains tokens after the first element", lexer.row(), lexer.column());
        } else {
            return Optional.ofNullable(result);
        }
    }


    // the implementation
    private @Nullable Builder<? extends JsonValue> readNextElement() throws IOException {
        // repeat to take the next token while the lexer has more tokens available.
        // and either the stack is empty or,
        // in case we expect to read more than one JSON element from the potentially unbounded source,
        // the top element is not a root element.
        while (lexer.hasNext() && !(stack.peek() instanceof Elem.Root)) {
            var tkn = lexer.next();
            // we switch over the type of the token as the primary level compound state
            switch (tkn) {
                // a colon is acceptable if and only if the current element at the
                // top of the stack is a name-value-pair
                case COLON -> {
                    if (stack.peek() instanceof Elem.NameElem) {
                        stack.push(Elem.Char.colon);
                    } else {
                        parseEx("unexpected token: " + tkn);
                    }
                }
                // a comma separates elements in an aggregate
                // that is, the top of the stack must be a non-empty
                // aggregate element
                case COMMA -> {
                    switch (stack.peek()) {
                        case Elem.ArrBuilderElem abe when !abe.builder.isEmpty() -> stack.push(Elem.Char.comma);
                        case Elem.ObjBuilderElem obe when !obe.builder.isEmpty() -> stack.push(Elem.Char.comma);
                        case null, default -> parseEx("unexpected token: " + tkn);
                    }
                }
                // opening braces (as well as opening brackets, see below)
                // that is, at the start, in an empty array, and after a colon or a comma.
                case OPENING_BRACE -> {
                    switch (stack.peek()) {
                        case null -> stack.push(objBuilderElem());
                        case Elem.ArrBuilderElem(var builder)
                                when builder.isEmpty() -> stack.push(objBuilderElem());
                        case Elem.Char ignored -> stack.push(objBuilderElem());
                        default -> parseEx("unexpected token " + tkn.value());
                    }
                }
                // opens an array, otherwise like a brace
                case OPENING_BRACKET -> {
                    switch (stack.peek()) {
                        case null -> stack.push(arrBuilderElem());
                        case Elem.Char ignored -> stack.push(arrBuilderElem());
                        case Elem.ArrBuilderElem(var builder) when builder.isEmpty() -> stack.push(arrBuilderElem());
                        default -> parseEx("unexpected token " + tkn.value());
                    }
                }
                // closes a JSON object
                // there should be an object builder on top of the stack
                case CLOSING_BRACE -> {
                    var obj = switch (stack.peek()) {
                        case Elem.ObjBuilderElem(var builder) -> {
                            stack.pop();
                            yield builder;
                        }
                        case null, default -> {
                            parseEx("unexpected token: " + tkn);
                            yield null;
                        }
                    };
                    handle(tkn.value(), obj);
                }
                // closing a JSON array
                // the array builder should be on top of the stack
                case CLOSING_BRACKET -> {
                    if (stack.peek() instanceof Elem.ArrBuilderElem(var builder)) {
                        stack.pop();
                        handle(tkn.value(), builder);
                    } else {
                        parseEx("unexpected token: " + tkn);
                    }
                }
                // literal tokens for null, true, false, string;
                // string is a special case because it can be the name part of a name-value-pair
                case Lexer.LiteralToken(var type, var val) -> {
                    if (type == STRING &&
                            stack.peek() instanceof Elem.ObjBuilderElem(var builder) && builder.isEmpty()
                    ) {
                        stack.push(new Elem.NameElem(val));
                    } else if (type == STRING && Elem.Char.comma.equals(stack.peek())) {
                        stack.pop();
                        switch (stack.peek()) {
                            case Elem.ObjBuilderElem ignored -> stack.push(new Elem.NameElem(val));
                            case Elem.ArrBuilderElem abe -> abe.builder.add(new JsonString(val));
                            case null, default -> parseEx("Unexpected value: " + val);
                        }
                    } else {
                        var literalToken = token2Value(tkn);
                        handle(val, Builder.of(literalToken));
                    }
                }
                // number tokens already carry a parsed BigDecimal; no further parsing required
                case Lexer.NumberToken(var number) -> handle(number.toString(), Builder.of(new JsonNumber(number)));
            }
        }

        // an empty stack is okay, signaling
        // that the input stream contains nothing but whitespace
        if (stack.isEmpty()) {
            return null;
        } else if (stack.pop() instanceof Elem.Root(var elem)) {
            // standard case: stack contains a single element at its top
            return elem;
        } else {
            // otherwise, something went wrong
            parseEx("stack not empty or top-most element not a JsonElement");
            return null;
        }
    }

    // handle tokens UNLESS these are element names
    // in a JSON object
    private void handle(String token, Builder<?> v) {
        switch (stack.peek()) {
            // stack is empty
            case null -> stack.push(new Elem.Root(v));
            // colon or comma at the top
            case Elem.Char nc -> {
                switch (nc) {
                    // colon: name-value-pair is second on top
                    case colon -> {
                        // pop colon
                        stack.pop();
                        // the topmost element must be an NVP with a null element,
                        // and the next stack element must be an object builder
                        if (!stack.isEmpty()
                                && stack.pop() instanceof Elem.NameElem(String name)
                                && stack.peek() instanceof Elem.ObjBuilderElem(var builder)) {
                            // add name-value-pair to the object builder
                            builder.put(name, v);
                        } else {
                            parseEx("unexpected token: " + token);
                        } // in every other case, something went wrong
                    }
                    // comma on top
                    case comma -> {
                        stack.pop(); // pop comma
                        if (stack.peek() instanceof Elem.ArrBuilderElem(var builder)) {
                            builder.add(v);
                        } else {
                            parseEx("unexpected token: " + token);
                        }
                    }
                }
            }
            case Elem.ArrBuilderElem(var builder) when builder.isEmpty() -> builder.add(v);
            default -> parseEx("unexpected token: " + token);
        }
    }

    private static Basic<?> token2Value(Lexer.Token tkn) {
        return switch (tkn) {
            case Lexer.LiteralToken(var type, var val) -> switch (type) {
                case NULL -> JsonNull.INSTANCE;
                case TRUE -> JsonBoolean.TRUE;
                case FALSE -> JsonBoolean.FALSE;
                case STRING -> new JsonString(val);
            };
            case Lexer.NumberToken(var number) -> new JsonNumber(number);
            case Lexer.FixToken ignored -> throw new AssertionError();
        };
    }

    private void parseEx(String msg) {
        throw new JsonParseException(msg, lexer.row(), lexer.column());
    }

    @Override
    public void close() throws IOException {
        lexer.close();
    }
}
