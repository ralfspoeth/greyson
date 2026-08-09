import org.jspecify.annotations.NullMarked;

/**
 * The Greyson module which contains the JSON parser, serializer and querying library.
 */
@NullMarked
module io.github.ralfspoeth.greyson {
    requires static org.jspecify;
    exports io.github.ralfspoeth.json;
    exports io.github.ralfspoeth.json.io;
    exports io.github.ralfspoeth.json.query;
    exports io.github.ralfspoeth.json.data;
}