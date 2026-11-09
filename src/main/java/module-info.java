import org.jspecify.annotations.NullMarked;

/**
 * Greyson: the JSON parser, serializer and query library.
 *
 * <p>Four packages are exported and everything else is internal. That
 * boundary is the practical reason this descriptor exists — the reader was
 * replaced wholesale in 2.4.0 without touching the exported surface.
 *
 * @see io.github.ralfspoeth.json the entry point, {@code Greyson}
 * @see <a href="https://github.com/ralfspoeth">On shipping a module
 * descriptor</a>
 */
@NullMarked
module io.github.ralfspoeth.greyson {
    requires static org.jspecify;
    exports io.github.ralfspoeth.json;
    exports io.github.ralfspoeth.json.io;
    exports io.github.ralfspoeth.json.query;
    exports io.github.ralfspoeth.json.data;
}