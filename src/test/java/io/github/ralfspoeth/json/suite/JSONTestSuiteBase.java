package io.github.ralfspoeth.json.suite;

import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.io.JsonReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

class JSONTestSuiteBase {
    protected static final Path RESOURCES = Path.of("src/test/resources");

    protected static Predicate<Path> fileNameFilter(FileSystem fs, String pattern) {
        return p -> fs.getPathMatcher("glob:" + pattern).matches(p.getFileName());
    }

    // parse a single JSON file
    protected Result parse(Path p) {
        try (var rdr = new JsonReader(Files.newBufferedReader(p, StandardCharsets.UTF_8))) {
            return new Result(p.getFileName(), rdr.readValue().orElseThrow(), null);
        } catch (Throwable t) {
            return new Result(p.getFileName(), null, t);
        }
    }

    record Result(Path p, JsonValue element, Throwable exception) {}
}
