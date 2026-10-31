package io.github.authzenenforcer.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonCodecTest {

    private final JsonCodec codec = JsonCodec.defaultCodec();

    @Test
    void returnsSharedInstance() {
        assertSame(codec, JsonCodec.defaultCodec());
    }

    @Test
    void roundTripsTrees() {
        Map<String, Object> tree = Map.of("decision", true, "context", Map.of("ids", List.of(1L, 2L)));

        assertEquals(tree, codec.read(codec.write(tree)));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(RuntimeException.class, () -> codec.read("{\"decision\": false, \"decision\": true}"));
    }

    @Test
    void rejectsUnsupportedValues() {
        assertThrows(IllegalArgumentException.class, () -> codec.write(new Object()));
    }
}
