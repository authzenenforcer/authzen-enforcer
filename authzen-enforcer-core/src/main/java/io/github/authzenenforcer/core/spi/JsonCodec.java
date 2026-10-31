package io.github.authzenenforcer.core.spi;

import io.github.authzenenforcer.core.internal.json.InternalJsonCodec;

/**
 * Converts between JSON text and a tree of plain JDK values.
 *
 * <p>The SDK maps its model types to and from such trees itself, so a codec never sees SDK types.
 * Trees use these types:
 * <table>
 *   <caption>JSON to Java type mapping</caption>
 *   <tr><th>JSON</th><th>Java</th></tr>
 *   <tr><td>object</td><td>{@link java.util.Map Map&lt;String, Object&gt;}</td></tr>
 *   <tr><td>array</td><td>{@link java.util.List List&lt;Object&gt;}</td></tr>
 *   <tr><td>string</td><td>{@link String}</td></tr>
 *   <tr><td>number</td><td>any {@link Number}</td></tr>
 *   <tr><td>true / false</td><td>{@link Boolean}</td></tr>
 *   <tr><td>null</td><td>{@code null}</td></tr>
 * </table>
 *
 * <p>The built-in codec, returned by {@link #defaultCodec()}, needs no dependencies. Framework
 * integrations may supply a codec backed by their own JSON library. Such an implementation:
 * <ul>
 *   <li>must be thread-safe;</li>
 *   <li>should reject duplicate object keys, so that it can never disagree with another parser
 *       on a value such as {@code decision};</li>
 *   <li>should limit nesting depth and input size;</li>
 *   <li>must not include fragments of the input in exception messages.</li>
 * </ul>
 */
public interface JsonCodec {

    /**
     * Serializes a tree to JSON text.
     *
     * @param value the tree, may be {@code null}
     * @return the JSON text
     * @throws IllegalArgumentException if the tree contains a value that cannot be represented
     */
    String write(Object value);

    /**
     * Parses JSON text into a tree.
     *
     * @param json the JSON text
     * @return the tree, which may be {@code null} for the JSON literal {@code null}
     * @throws RuntimeException if the text is not valid JSON or exceeds a limit
     */
    Object read(String json);

    /**
     * Returns the built-in, dependency-free codec.
     *
     * <p>It rejects duplicate keys and limits nesting depth to 64 and input size to 8 MiB.
     *
     * @return the shared built-in codec
     */
    static JsonCodec defaultCodec() {
        return InternalJsonCodec.INSTANCE;
    }
}
