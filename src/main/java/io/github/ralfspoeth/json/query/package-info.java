/**
 * Designed to make querying {@link io.github.ralfspoeth.json.data.JsonValue}s simpler.
 * The OpenJDK community has an upcoming JEP 540 incubating a <em>Simple JSON API</em>
 * with an example how to read weather forecast data
 * <a href="https://openjdk.org/jeps/540#appendix">here</a>.
 * Here is how you do the same with Greyson without the Query API
 * {@snippet class = "GreysonWay" region = "classic"}
 * and here with {@link Selector}s and {@link Pointer}s.
 * {@snippet class = "GreysonWay" region = "query"}
 *
 * <p>Both of those assume the document is shaped the way the API documents it.
 * {@link Shape} is for when that assumption is worth checking — and it
 * answers with <em>what is wrong and where</em> rather than with a boolean,
 * which is the part that makes it useful on data you did not produce. The
 * same value then doubles as a filter, so a single malformed period need not
 * cost you the whole forecast:
 * {@snippet class = "GreysonWay" region = "shape"}
 */
package io.github.ralfspoeth.json.query;