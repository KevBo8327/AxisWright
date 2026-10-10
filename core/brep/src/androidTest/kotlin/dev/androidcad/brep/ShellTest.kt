package dev.androidcad.brep

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShellTest {
    /** Quader 20 × 20 × 10 mit Namen: Seiten 1–4, Boden 5, Deckel 6. */
    private fun box(): BrepShape {
        val p = Profile(
            listOf(
                ProfileSegment.Line(0.0, 0.0, 20.0, 0.0, 1), ProfileSegment.Line(20.0, 0.0, 20.0, 20.0, 2),
                ProfileSegment.Line(20.0, 20.0, 0.0, 20.0, 3), ProfileSegment.Line(0.0, 20.0, 0.0, 0.0, 4),
            ),
        )
        return BrepShape.extrude(p, 10.0, bottomTag = 5, topTag = 6)
    }

    @Test
    fun openBoxKeepsOuterNamesAndNamesTheInside() {
        box().use { b ->
            val top = b.facesWithTag(6).single()
            b.shell(listOf(top), 2.0) { it + 100 }.use { s ->
                // außen 20 × 20 × 10, innen 16 × 16 × 8 (Boden 2 mm, oben offen)
                assertTrue(abs(s.volume() - (4000.0 - 16 * 16 * 8)) < 1e-6, "Volumen ${s.volume()}")
                // vom Deckel bleibt der Rand um die Öffnung; er trägt den Namen des Deckels, seine Außenkante ist wählbar
                assertEquals(1, s.facesWithTag(6).size, "Rand an der Öffnung")
                assertEquals(1, s.edgesBetween(6, 1).size)
                for (t in 1L..5L) assertEquals(1, s.facesWithTag(t).size, "Außenfläche $t behält ihren Namen")
                for (t in 101L..105L) assertEquals(1, s.facesWithTag(t).size, "Innenfläche ${t - 100} ist benannt")
                // Innenkante zwischen Innenboden und einer Innenwand lässt sich über die Namen finden (zum Verrunden)
                assertEquals(1, s.edgesBetween(101, 105).size)
            }
        }
    }

    @Test
    fun tooThickWallFails() {
        box().use { b -> assertFailsWith<BrepException> { b.shell(listOf(b.facesWithTag(6).single()), 15.0).close() } }
    }
}
