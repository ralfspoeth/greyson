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
        // What this computation needs, and nothing more
        var requiredShape = member("temperature", number())
                .and(member("temperatureUnit", oneOf("F", "C")));

        // What a forecast period has to look like. Naming a member requires
        // it; optional() is for the ones that may be absent but still have to
        // fit when they are there.
        var fullPeriodShape = requiredShape.and(member("number", number()))
                .and(member("name", string()))
                .and(member("isDaytime", bool()))
                .and(optional("windSpeed", string()));

        // pointer to periods array...
        var periodsPointer = self()
                .member("properties")
                .member("periods");

        // ...composed into a shape
        var forecastShape = periodsPointer.must(each(fullPeriodShape));

        // explain() prints the description back, so the rule can be logged
        // or asserted rather than only applied
        IO.println(forecastShape.explain());

        var doc = Greyson.readValue(Reader.of(response.body()));

        // The diagnostic use: what is wrong, and where. An empty stream means
        // the document is fine, so this prints nothing on a good day.
        var defects = doc.stream().flatMap(forecastShape::violations).toList();
        defects.forEach(IO::println);
        // properties/periods/[3]/temperature: expected number, got string
        // properties/periods/[7]/name: missing required member

        enum Unit {F, C}
        record Temperature(BigDecimal temperature, Unit unit) {
            Temperature withUnit(Unit newUnit) {
                if (unit == newUnit) {
                    return this;
                } else return switch (newUnit) {
                    case F -> new Temperature(temperature
                            .divide(BigDecimal.TEN, MathContext.UNLIMITED)
                            .multiply(BigDecimal.valueOf(18))
                            .add(BigDecimal.valueOf(32)),
                            Unit.F
                    );
                    case C -> new Temperature(temperature
                            .add(BigDecimal.valueOf(-32))
                            .multiply(BigDecimal.valueOf(5))
                            .divide(BigDecimal.valueOf(9), 4, RoundingMode.HALF_EVEN),
                            Unit.C
                    );
                };
            }

            Temperature add(Temperature other) {
                return new Temperature(temperature.add(other.withUnit(this.unit).temperature()), unit);
            }

            Temperature divide(long div) {
                return new Temperature(temperature
                        .divide(BigDecimal.valueOf(div), 4, RoundingMode.HALF_EVEN),
                        unit
                );
            }
        }

        var temperaturePtr = self().member("temperature");
        var unitPtr = self().member("temperatureUnit");

        // A shape as a filter, and a narrower one than the shape above: the
        // full contract is what the forecast promises, requiredShape is only
        // what this calculation reads. One malformed period costs that
        // period, not the document.
        //
        // And because every survivor satisfied requiredShape, the members are
        // known to be present and of the right type -- which is what makes
        // the OrThrow accessors honest here rather than optimistic.
        var temperatures = doc.stream()
                .flatMap(periodsPointer.select(all().where(requiredShape)))
                .map(p -> new Temperature(
                        temperaturePtr.decimalOrThrow(p),
                        Unit.valueOf(unitPtr.stringOrThrow(p))))
                .toList();

        temperatures.forEach(IO::println);
        // Temperature[temperature=54, unit=F]

        // Summed as Temperatures rather than as numbers: add() settles the
        // unit, and nothing is truncated on the way. Division is the one
        // operation that has to be told a precision -- which is the price of
        // BigDecimal, and the reason the digits survived this far.
        var meanTemperature = temperatures.stream()
                .reduce(Temperature::add)
                .map(sum -> sum.divide(temperatures.size()));
        meanTemperature.ifPresent(IO::println);
        // Temperature[temperature=56.4286, unit=F]
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

        // And with nothing filtered out, the shape-guarded mean is the same
        // number arrived at a third way -- within a tolerance, because the
        // other two truncate each reading to an int while this one keeps the
        // decimals and rounds once, at the end.
        assert meanTemperature.isPresent() : "no usable periods";
        assert Math.abs(meanTemperature.orElseThrow().temperature().doubleValue()
                - queryAverage.orElseThrow()) < 0.01
                : "mean " + meanTemperature + " != query " + queryAverage;
    }
}
