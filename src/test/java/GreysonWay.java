/*
 * The source of the {@snippet} regions in io.github.ralfspoeth.json.query's
 * package documentation. The build only compiles it; nothing runs it, and it
 * fetches a live forecast, so it could not run in CI anyway.
 *
 * The assertions at the bottom are therefore only enforced when you run it
 * yourself, and only with -ea:
 *
 *     mvn -q compile
 *     java -ea -cp target/classes src/test/java/GreysonWay.java
 *
 * Without -ea they are dead code and the file can print nothing while looking
 * correct -- which is exactly what the "classic" region did until the
 * assertions were added.
 */

import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonValue;
import io.github.ralfspoeth.json.query.Pointer;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static io.github.ralfspoeth.json.query.Pointer.self;
import static io.github.ralfspoeth.json.query.Selector.all;
import static io.github.ralfspoeth.json.query.Shape.*;

void main() throws IOException, InterruptedException {
    var url = "https://api.weather.gov/gridpoints/MTR/97,83/forecast";
    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
        var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        var response = client.send(req, HttpResponse.BodyHandlers.ofString());

        // @start region="classic"
        var classicAverage = Greyson.readValue(Reader.of(response.body())) // Optional<JsonValue>
                .flatMap(v -> v.get("properties"))             // Optional<JsonValue>
                .flatMap(v -> v.get("periods"))                // Optional<JsonValue>
                .stream()                                      // Stream<JsonValue>, 0 or 1
                .flatMap(JsonValue::children)                  // Stream<JsonValue>, the periods
                .map(v -> v.get("temperature"))                // Optional<JsonValue>
                .flatMap(Optional::stream)                     // Stream<JsonValue>, present ones
                .map(JsonValue::decimal)                       // Optional<BigDecimal>
                .filter(Optional::isPresent)                   // filter only if present
                .map(Optional::get)                            // ...and get contents
                .mapToInt(BigDecimal::intValue)                // IntStream
                .average();
        classicAverage.ifPresent(IO::println);
        // @end region="classic"

        // @start region="query"
        Pointer periodsPtr = self().member("properties").member("periods");
        var tempValues = periodsPtr
                .select(all())
                .point(self().member("temperature"))
                .presentValues(JsonValue::decimal);

        var queryAverage = Greyson.readValue(Reader.of(response.body()))
                .stream()
                .flatMap(tempValues)
                .mapToInt(BigDecimal::intValue)
                .average();
        queryAverage.ifPresent(IO::println);
        // @end region="query"

        // @start region="shape"
        // What a forecast period has to look like. Naming a member requires
        // it; optional() is for the ones that may be absent but still have to
        // fit when they are there.
        var period = member("number", number())
                .and(member("name", string()))
                .and(member("temperature", number()))
                .and(member("temperatureUnit", oneOf("F", "C")))
                .and(member("isDaytime", bool()))
                .and(optional("windSpeed", string()));

        var periodsPointer = self()
                .member("properties")
                .member("periods");

        var forecast = periodsPointer.must(each(period));

        // explain() prints the description back, so the rule can be logged
        // or asserted rather than only applied
        IO.println(forecast.explain());

        var doc = Greyson.readValue(Reader.of(response.body()));

        // The diagnostic use: what is wrong, and where. An empty stream means
        // the document is fine, so this prints nothing on a good day.
        var defects = doc.stream().flatMap(forecast::violations).toList();
        defects.forEach(IO::println);
        // properties/periods/[3]/temperature: expected number, got string
        // properties/periods/[7]/name: missing required member

        // The same value as a filter: keep the periods that are usable and
        // average those, instead of failing on the whole document because one
        // period is malformed.
        var usableAverage = doc.stream()
                .flatMap(periodsPointer.select(all().where(period))
                        .point(self().member("temperature"))
                        .presentValues(JsonValue::decimal))
                .mapToInt(BigDecimal::intValue)
                .average();
        usableAverage.ifPresent(IO::println);
        // @end region="shape"

        /*
         * Outside the snippets: what the three pipelines above are supposed to
         * agree on. This file is never run by the build -- it exists to be
         * compiled and quoted -- so these only fire when someone runs it by
         * hand with -ea. That is still worth having: nothing else here would
         * notice a pipeline that silently computes nothing, which is exactly
         * what the classic one did before it learned to step into
         * "temperature".
         */
        assert classicAverage.isPresent() : "no temperatures found at all";
        assert classicAverage.equals(queryAverage)
                : "classic " + classicAverage + " != query " + queryAverage;

        // the live document should match the shape; if it stops doing so,
        // api.weather.gov changed and the snippet needs revisiting
        assert defects.isEmpty() : "forecast no longer matches the shape: " + defects;

        // and with nothing filtered out, the shape-guarded average is the
        // same number arrived at a third way
        assert usableAverage.equals(queryAverage)
                : "usable " + usableAverage + " != query " + queryAverage;
    }
}
