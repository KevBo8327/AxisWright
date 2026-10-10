package dev.androidcad.lang

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvalNumberTest {
    @Test
    fun expressionsWithNames() {
        assertEquals(7.0, CadLang.evalNumber("1 + 2 * 3"))
        assertEquals(26.0, CadLang.evalNumber("breite / 2 + wand", mapOf("breite" to 40.0, "wand" to 6.0)))
        assertEquals(5.0, CadLang.evalNumber("sqrt(9) + max(1, 2)"))
    }

    @Test
    fun unknownNamesAndGarbageAreErrors() {
        assertFailsWith<CadLangException> { CadLang.evalNumber("hoehe * 2") }
        assertFailsWith<CadLangException> { CadLang.evalNumber("2 +") }
        assertFailsWith<CadLangException> { CadLang.evalNumber("1 / 0") }
        assertFailsWith<CadLangException> { CadLang.evalNumber("[1, 2]") }
    }
}
