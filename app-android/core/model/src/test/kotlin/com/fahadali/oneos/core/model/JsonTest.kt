package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun writesNestedValuesWithEscapes() {
        val json = Json.write(
            linkedMapOf(
                "s" to "a\"b\\c\nd\u0001",
                "n" to 2,
                "l" to listOf(true, null, 1.5),
                "e" to emptyMap<String, Any>(),
            ),
        )
        assertEquals(
            "{\n  \"s\": \"a\\\"b\\\\c\\nd\\u0001\",\n  \"n\": 2,\n  \"l\": [\n    true,\n    null,\n    1.5\n  ],\n  \"e\": {}\n}\n",
            json,
        )
    }
}
