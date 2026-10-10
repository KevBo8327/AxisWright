package dev.androidcad.lang

import kotlin.test.Test
import kotlin.test.assertTrue

/** Tiefe Verschachtelung darf keinen StackOverflowError werfen (der beendet die App), sondern eine normale Fehlermeldung. */
class DeepNestingTest {
    /** Wertet [src] auf einem Thread mit 1 MB Stack aus – so viel hat ein Hintergrund-Thread unter Android. */
    private fun renderOnSmallStack(src: String): Throwable? {
        var thrown: Throwable? = null
        val t = Thread(null, { thrown = runCatching { CadLang.render(src) }.exceptionOrNull() }, "render", 1L shl 20)
        t.start()
        t.join()
        return thrown
    }

    @Test
    fun deepModuleRecursionBelowTheLimitIsAnOrdinaryError() {
        val e = renderOnSmallStack("module m(n) { if (n > 0) translate([1,0,0]) m(n - 1); else cube(1); }\nm(399);")
        assertTrue(e == null || e is CadLangException, "erwartet CadLangException oder Erfolg, erhalten $e")
    }

    @Test
    fun thousandsOfParenthesesAreAnOrdinaryError() {
        val e = renderOnSmallStack("x = " + "(".repeat(5000) + "1" + ")".repeat(5000) + ";\ncube(x);")
        assertTrue(e is CadLangException, "erwartet CadLangException, erhalten $e")
    }
}
