import io.github.ralfspoeth.json.Greyson;
import io.github.ralfspoeth.json.data.JsonValue;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static io.github.ralfspoeth.json.query.Pointer.self;
import static io.github.ralfspoeth.json.query.Selector.all;

public class GreysonWay {

    void main() throws IOException, InterruptedException {
        var url = "https://api.weather.gov/gridpoints/MTR/97,83/forecast";
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()) {
            var req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            var response = client.send(req, HttpResponse.BodyHandlers.ofString());

            // @start region="classic"
            Greyson.readValue(Reader.of(response.body()))          // Optional<JsonValue>
                    .flatMap(v -> v.get("properties"))   // Optional<JsonValue>
                    .flatMap(v -> v.get("periods"))      // Optional<JsonValue>
                    .stream()                                      // Stream<JsonValue>, 0 or 1
                    .flatMap(JsonValue::values)                    // Stream<JsonValue>, many
                    .map(JsonValue::decimal)                       // Optional<BigDecimal>
                    .filter(Optional::isPresent)                   // filter only if present
                    .map(Optional::get)                            // ...and get contents
                    .mapToInt(BigDecimal::intValue)                // IntStream
                    .average()
                    .ifPresent(IO::println);
            // @end region="classic"

            // @start region="query"
            var tempValues = self()
                    .member("properties")
                    .member("periods")
                    .select(all())
                    .point(self().member("temperature"))
                    .presentValues(JsonValue::decimal);

            Greyson.readValue(Reader.of(response.body()))
                    .stream()
                    .flatMap(tempValues)
                    .mapToInt(BigDecimal::intValue)
                    .average()
                    .ifPresent(IO::println);
            // @end region="query"

        }
    }
}