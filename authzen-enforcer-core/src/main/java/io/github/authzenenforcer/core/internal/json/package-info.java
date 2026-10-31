/**
 * Minimal, dependency-free JSON support used by the core module.
 *
 * <p><strong>Internal API.</strong> Types in this package are not part of the public API and may
 * change without notice between releases.
 *
 * <p>JSON values are represented with plain JDK types:
 * <table>
 *   <caption>JSON to Java type mapping</caption>
 *   <tr><th>JSON</th><th>Java</th></tr>
 *   <tr><td>object</td><td>{@link java.util.Map Map&lt;String, Object&gt;} (insertion ordered)</td></tr>
 *   <tr><td>array</td><td>{@link java.util.List List&lt;Object&gt;}</td></tr>
 *   <tr><td>string</td><td>{@link String}</td></tr>
 *   <tr><td>integer number</td><td>{@link Long}, or {@link java.math.BigInteger} if it does not fit</td></tr>
 *   <tr><td>number with fraction or exponent</td><td>{@link java.math.BigDecimal}</td></tr>
 *   <tr><td>true / false</td><td>{@link Boolean}</td></tr>
 *   <tr><td>null</td><td>{@code null}</td></tr>
 * </table>
 */
package io.github.authzenenforcer.core.internal.json;
