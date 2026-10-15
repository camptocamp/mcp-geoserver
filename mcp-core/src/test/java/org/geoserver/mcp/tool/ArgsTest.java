package org.geoserver.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArgsTest {
    @Test
    void nullMapIsEmpty() {
        Args a = new Args(null);
        assertFalse(a.has("x"));
        assertEquals("d", a.string("x", "d"));
    }

    @Test
    void requiredStringRejectsMissingAndBlank() {
        Args a1 = new Args(Map.of());
        assertThrows(ToolException.class, () -> a1.string("x"));
        Args a2 = new Args(Map.of("x", " "));
        assertThrows(ToolException.class, () -> a2.string("x"));
        assertEquals("v", new Args(Map.of("x", "v")).string("x"));
    }

    @Test
    void optionalStringFallsBackOnBlank() {
        assertEquals("d", new Args(Map.of("x", "")).string("x", "d"));
        assertEquals("v", new Args(Map.of("x", "v")).string("x", "d"));
    }

    @Test
    void integerAcceptsNumbersAndNumericStrings() {
        assertEquals(7, new Args(Map.of()).integer("x", 7));
        assertEquals(3, new Args(Map.of("x", 3.9)).integer("x", 0));
        assertEquals(12, new Args(Map.of("x", "12")).integer("x", 0));
        Args a3 = new Args(Map.of("x", "twelve"));
        assertThrows(ToolException.class, () -> a3.integer("x", 0));
    }

    @Test
    void boolAcceptsBooleansAndStrings() {
        assertTrue(new Args(Map.of()).bool("x", true));
        assertTrue(new Args(Map.of("x", true)).bool("x", false));
        assertTrue(new Args(Map.of("x", "true")).bool("x", false));
        assertFalse(new Args(Map.of("x", "nope")).bool("x", true));
    }

    @Test
    void stringsWrapsScalar() {
        assertEquals(List.of(), new Args(Map.of()).strings("x"));
        assertEquals(List.of("a"), new Args(Map.of("x", "a")).strings("x"));
        assertEquals(List.of("1", "b"), new Args(Map.of("x", List.of(1, "b"))).strings("x"));
    }

    @Test
    void numbersParsesMixedListAndRejectsScalar() {
        assertEquals(List.of(), new Args(Map.of()).numbers("x"));
        assertEquals(List.of(1.0, 2.5), new Args(Map.of("x", List.of(1, "2.5"))).numbers("x"));
        Args a4 = new Args(Map.of("x", 4));
        assertThrows(ToolException.class, () -> a4.numbers("x"));
    }

    @Test
    void mapRejectsNonObject() {
        assertEquals(Map.of(), new Args(new HashMap<>()).map("x"));
        assertEquals(Map.of("k", 1), new Args(Map.of("x", Map.of("k", 1))).map("x"));
        Args a5 = new Args(Map.of("x", "s"));
        assertThrows(ToolException.class, () -> a5.map("x"));
    }
}
