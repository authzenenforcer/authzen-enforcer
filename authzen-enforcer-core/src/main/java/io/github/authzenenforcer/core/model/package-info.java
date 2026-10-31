/**
 * Immutable AuthZEN information model: subject, action, resource, context, and the Access
 * Evaluation request and response.
 *
 * <p>Required fields must not be {@code null}. As in the specification, required strings
 * ({@code type}, {@code id}, {@code name}) may be empty: the model does not judge what a PDP accepts.
 * Rejecting suspicious values, such as an empty id taken from a request path, is the job of the
 * enforcement layer.
 *
 * <p>{@code properties} and context values must be JSON-compatible: {@code null},
 * {@link java.lang.CharSequence CharSequence}, {@link java.lang.Boolean Boolean}, finite
 * {@link java.lang.Number Number}s, {@link java.util.Map Map}s with string keys, and
 * {@link java.lang.Iterable Iterable}s or arrays of such values. They are deep-copied and normalized
 * to the types a JSON round trip produces:
 * <table>
 *   <caption>Normalized value types</caption>
 *   <tr><th>JSON</th><th>Java</th></tr>
 *   <tr><td>object</td><td>{@link java.util.Map Map&lt;String, Object&gt;} (insertion ordered)</td></tr>
 *   <tr><td>array</td><td>{@link java.util.List List&lt;Object&gt;}</td></tr>
 *   <tr><td>string</td><td>{@link java.lang.String String}</td></tr>
 *   <tr><td>integer number</td><td>{@link java.lang.Long Long}, or
 *       {@link java.math.BigInteger BigInteger} if it does not fit</td></tr>
 *   <tr><td>number with fraction or exponent</td><td>{@link java.math.BigDecimal BigDecimal}</td></tr>
 *   <tr><td>true / false</td><td>{@link java.lang.Boolean Boolean}</td></tr>
 *   <tr><td>null</td><td>{@code null}</td></tr>
 * </table>
 * For example, an {@code Integer} property becomes a {@code Long} and a {@code Double} becomes a
 * {@code BigDecimal}, so a value read back from the PDP compares equal to the one that was sent.
 */
package io.github.authzenenforcer.core.model;
