package io.github.authzenenforcer.core.internal.json;

import io.github.authzenenforcer.core.spi.JsonCodec;

/**
 * The built-in {@link JsonCodec}, backed by {@link JsonWriter} and {@link JsonParser} with their
 * default limits.
 */
public final class InternalJsonCodec implements JsonCodec {

    /** The shared instance. */
    public static final InternalJsonCodec INSTANCE = new InternalJsonCodec();

    private final JsonWriter writer = new JsonWriter();
    private final JsonParser parser = new JsonParser();

    private InternalJsonCodec() {
    }

    @Override
    public String write(Object value) {
        return writer.write(value);
    }

    @Override
    public Object read(String json) {
        return parser.parse(json);
    }
}
