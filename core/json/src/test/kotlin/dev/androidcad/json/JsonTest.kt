package dev.androidcad.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun parsesAllValueTypes() {
        val v = Json.parse("""{"a": null, "b": true, "c": false, "d": [1, 2.5, -3e2, 0], "e": {"x": "y"}, "f": ""}""").asObject()
        assertNull(v["a"]); assertEquals(true, v["b"]); assertEquals(false, v["c"])
        assertEquals(listOf(1.0, 2.5, -300.0, 0.0), v["d"])
        assertEquals("y", v.obj("e").string("x"))
        assertEquals("", v.string("f"))
    }

    @Test
    fun objectKeepsInsertionOrder() {
        val m = Json.parse("""{"z":1,"a":2,"m":3}""").asObject()
        assertEquals(listOf("z", "a", "m"), m.keys.toList())
    }

    @Test
    fun writeAndParseRoundTripIncludingEdgeCases() {
        val data = mapOf(
            "text" to "Zeile 1\nZeile 2\t\"zitiert\" \\ Backslash / Slash \u0001 ü ß 日本語 😀",
            "numbers" to listOf(0, 1, -7, 3.14159, 1.0E-9, 1.0E20, 123456789012L, -0.5),
            "flags" to listOf(true, false, null),
            "nested" to mapOf("list" to listOf(mapOf("k" to 1), listOf<Any?>()), "empty" to mapOf<String, Any?>()),
        )
        for (pretty in listOf(false, true)) {
            val back = Json.parse(Json.write(data, pretty)).asObject()
            assertEquals(data["text"], back["text"])
            assertEquals(listOf(0.0, 1.0, -7.0, 3.14159, 1.0E-9, 1.0E20, 123456789012.0, -0.5), back["numbers"])
            assertEquals(listOf(true, false, null), back["flags"])
            assertEquals(data["nested"].toString().replace(".0", ""), back["nested"].toString().replace(".0", ""))
        }
    }

    @Test
    fun doublesRoundTripExactly() {
        val values = listOf(0.1, 1.0 / 3.0, Math.PI, 2.5e-300, 1.7976931348623157E308, -123.456789012345678, 4.9E-324, 100.0, 1e14, 999999999999999.0)
        val back = Json.parse(Json.write(values)).asList()
        assertEquals(values, back)
    }

    @Test
    fun integersAreWrittenWithoutDecimalPoint() {
        assertEquals("[1,2,-3,0]", Json.write(listOf(1.0, 2.0, -3.0, 0.0)))
        assertEquals("1.5", Json.write(1.5))
    }

    @Test
    fun parsesEscapesAndSurrogatePairs() {
        assertEquals("a\"b\\c/d\b\u000C\n\r\t", Json.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""))
        assertEquals("é€", Json.parse("\"\\u00e9\\u20ac\""))
        assertEquals("😀", Json.parse("\"\\ud83d\\ude00\""))
    }

    @Test
    fun prettyOutputIsReadableAndCompactForNumberLists() {
        val text = Json.write(mapOf("name" to "x", "p" to listOf(1, 2, 3), "o" to mapOf("k" to listOf(mapOf("a" to 1)))), pretty = true)
        assertTrue(text.contains("\n  \"name\": \"x\""))
        assertTrue(text.contains("\"p\": [1, 2, 3]"))
    }

    @Test
    fun rejectsMalformedInput() {
        val bad = listOf(
            "", "   ", "{", "[1,", "[1 2]", "{\"a\" 1}", "{\"a\":1,}", "[1,]", "{a:1}", "'x'", "01", "-", "1.", ".5", "1e", "+1",
            "\"abc", "\"\\x\"", "\"\\u12\"", "tru", "nul", "[1] 2", "{\"a\":1} x", "\"tab\there\"", "NaN", "[true false]",
        )
        for (b in bad) assertFailsWith<JsonException>("sollte scheitern: <$b>") { Json.parse(b) }
    }

    @Test
    fun errorMessagesCarryPosition() {
        val e = assertFailsWith<JsonException> { Json.parse("{\n  \"a\": 1,\n  \"b\": x\n}") }
        assertTrue(e.message!!.contains("Zeile 3"), e.message)
    }

    @Test
    fun deepNestingIsRejectedInsteadOfOverflowingTheStack() {
        assertFailsWith<JsonException> { Json.parse("[".repeat(10_000) + "]".repeat(10_000)) }
        val ok = "[".repeat(100) + "]".repeat(100)
        Json.parse(ok)
    }

    @Test
    fun writerRejectsUnrepresentableValues() {
        assertFailsWith<JsonException> { Json.write(Double.NaN) }
        assertFailsWith<JsonException> { Json.write(listOf(Double.POSITIVE_INFINITY)) }
        assertFailsWith<JsonException> { Json.write(mapOf(1 to "x")) }
        assertFailsWith<JsonException> { Json.write(object {}) }
    }

    @Test
    fun typedAccessorsReportTheFieldName() {
        val o = Json.parse("""{"n": 1.5, "s": "x", "i": 3, "b": true}""").asObject()
        assertEquals(3, o.int("i"))
        assertEquals(1.5, o.double("n"))
        assertTrue(o.bool("b"))
        assertEquals(false, o.bool("missing", default = false))
        assertEquals(null, o.stringOrNull("nope"))
        assertEquals(null, o.doubleOrNull("nope"))
        assertTrue(assertFailsWith<JsonException> { o.int("n") }.message!!.contains("'n'"))
        assertTrue(assertFailsWith<JsonException> { o.string("i") }.message!!.contains("'i'"))
        assertTrue(assertFailsWith<JsonException> { o.double("missing") }.message!!.contains("'missing'"))
        assertTrue(assertFailsWith<JsonException> { o.list("s") }.message!!.contains("'s'"))
        assertFailsWith<JsonException> { Json.parse("[1]").asObject() }
    }

    @Test
    fun duplicateKeysTakeTheLastValue() {
        assertEquals(2.0, Json.parse("""{"a":1,"a":2}""").asObject()["a"])
    }
}
