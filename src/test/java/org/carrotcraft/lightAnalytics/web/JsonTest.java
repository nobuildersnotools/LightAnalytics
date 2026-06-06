package org.carrotcraft.lightAnalytics.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void writesFlatObjectWithMixedTypes() {
        String json = Json.object()
                .add("n", 42)
                .add("l", 9_000_000_000L)
                .add("d", 1.5)
                .add("b", true)
                .add("s", "hi")
                .build();
        assertEquals("{\"n\":42,\"l\":9000000000,\"d\":1.5,\"b\":true,\"s\":\"hi\"}", json);
    }

    @Test
    void escapesStrings() {
        assertEquals("{\"k\":\"x\\\"y\"}", Json.object().add("k", "x\"y").build());
        assertEquals("{\"k\":\"a\\nb\\tc\"}", Json.object().add("k", "a\nb\tc").build());
        assertEquals("{\"k\":\"a\\\\b\"}", Json.object().add("k", "a\\b").build());
    }

    @Test
    void nullStringAndNonFiniteNumbersBecomeNull() {
        assertEquals("{\"s\":null}", Json.object().add("s", (String) null).build());
        assertEquals("{\"d\":null}", Json.object().add("d", Double.NaN).build());
        assertEquals("{\"d\":null}", Json.object().add("d", Double.POSITIVE_INFINITY).build());
    }

    @Test
    void nestsRawObjectsAndArrays() {
        String inner = Json.object().add("x", 1).build();
        String array = Json.array().addRaw(inner).addRaw(inner).build();
        String json = Json.object().addRaw("items", array).build();
        assertEquals("{\"items\":[{\"x\":1},{\"x\":1}]}", json);
    }
}
