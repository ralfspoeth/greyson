/**
 * Designed to make querying {@link io.github.ralfspoeth.json.data.JsonValue}s simpler.
 * The OpenJDK community has an upcoming JEP 540 incubating a <em>Simple JSON API</em>
 * with an example how read weather forecast data
 * {@linkplain "<a href="https://openjdk.org/jeps/540#appendix">here</a>"}
 * Here is how you do the same with Greyson without the Query API
 * {@snippet class = "GreysonWay" region = "classic"}
 * and here with {@link Selector}s and {@link Pointer}s.
 * {@snippet class = "GreysonWay" region = "query"}
 */
package io.github.ralfspoeth.json.query;