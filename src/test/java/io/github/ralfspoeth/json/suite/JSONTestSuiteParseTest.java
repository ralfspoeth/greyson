package io.github.ralfspoeth.json.suite;

import io.github.ralfspoeth.json.io.JsonParseException;
import io.github.ralfspoeth.json.io.JsonReader;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.MultipleFailuresError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JSONTestSuiteParseTest extends JSONTestSuiteBase {

    private final List<Result> acceptedNs = new ArrayList<>();
    private final List<Result> rejectedYs = new ArrayList<>();
    private final List<Result> acceptedIs = new ArrayList<>();
    private final List<Result> rejectedIs = new ArrayList<>();

    @Test
    void i_structure_UTF8_BOM_empty_object() throws Exception {
        var srcFile = RESOURCES.resolve("i_structure_UTF-8_BOM_empty_object.json");
        try (var rdr = new JsonReader(Files.newBufferedReader(srcFile, StandardCharsets.UTF_8))) {
            assertThrows(JsonParseException.class, () -> rdr.readValue().orElseThrow());
        }
    }

    @Test
    void n_string_backslash_00() throws Exception {
        var srcFile = RESOURCES.resolve("n_string_backslash_00.json");
        try (var rdr = new JsonReader(Files.newBufferedReader(srcFile, StandardCharsets.UTF_8))) {
            assertThrows(JsonParseException.class, () -> rdr.readValue().orElseThrow());
        }
    }


    @Test
    void testAllFiles() throws Exception {
        collectNs();
        collectYs();
        collectIs();
        try {
            assertAll(
                    () -> assertEquals(0, acceptedNs.size(), "Ns"),
                    () -> assertEquals(0, rejectedYs.size(), "Ys"),
                    () -> assertEquals(0, rejectedIs.size(), "iNs"),
                    () -> assertEquals(0, acceptedIs.size(), "iYs")
            );
        } catch (MultipleFailuresError mfe) {
            int critical = 0;
            System.out.println("Path\tShould accept\tDid accept\tElement\tException");
            for (var f : mfe.getFailures()) {
                switch (f) {
                    case AssertionFailedError afe -> {
                        if (afe.getMessage().startsWith("Ns")) {
                            critical++;
                            listResults(acceptedNs, "no", "yes");
                        } else if (afe.getMessage().startsWith("Ys")) {
                            critical++;
                            listResults(rejectedYs, "yes", "no");
                        } else if (afe.getMessage().startsWith("iNs")) {
                            listResults(rejectedIs, "may", "no");
                        } else if (afe.getMessage().startsWith("iYs")) {
                            listResults(acceptedIs, "may", "yes");
                        } else {
                            throw new AssertionError();
                        }
                    }
                    case Throwable t -> System.out.println(t.getMessage());
                }
            }
            if (critical > 0) throw mfe;
        }

    }

    private void collectYs() throws IOException {
        try (var files = Files.list(RESOURCES)) {
            files.filter(fileNameFilter(RESOURCES.getFileSystem(), "y*.json"))
                    .map(this::parse)
                    .filter(r -> r.exception() != null)
                    .forEach(rejectedYs::add);
        }
    }

    void collectNs() throws IOException {
        try (var files = Files.list(RESOURCES)) {
            files.filter(fileNameFilter(RESOURCES.getFileSystem(), "n*.json"))
                    .map(this::parse)
                    .filter(r -> r.exception() == null && r.element() != null)
                    .forEach(acceptedNs::add);
        }
    }

    void collectIs() throws IOException {
        try (var files = Files.list(RESOURCES)) {
            files.filter(fileNameFilter(RESOURCES.getFileSystem(), "i*.json"))
                    .map(this::parse)
                    .forEach(r -> {
                        if (r.exception() == null) {
                            acceptedIs.add(r);
                        } else {
                            rejectedIs.add(r);
                        }
                    });
        }
    }

    String maxLength40(String s) {
        return 40 > s.length() ? s : s.substring(0, 37) + "...";
    }

    String formatJson(String json) {
        return maxLength40(json.replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        );
    }

    void listResults(List<Result> results, String should, String did) {
        results.forEach(r -> System.out.printf("%s\t%s\t%s\t%s\t%s%n",
                r.p(),
                should,
                did,
                r.element() == null ? "" : formatJson(r.element().json()),
                r.exception() == null ? "" : r.exception())
        );
    }
}
