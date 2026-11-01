package dev.androidcad.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Test
import kotlin.test.assertEquals

class ContextBarPositionTest {
    // Zeichenfläche 1000×800, Leiste 300×60, Rand 8, Abstand 18, Plakettenstreifen bis 44, daneben mindestens 160 breit
    private fun place(box: Rect, barW: Float = 300f) = contextBarPlace(box, barW, 60f, 1000f, 800f, 8f, 18f, 44f, 160f)

    @Test
    fun belowWhenThereIsRoom() {
        val p = place(Rect(400f, 200f, 600f, 300f))
        assertEquals(Offset(350f, 318f), p.topLeft)
        assertEquals(984f, p.width, "volle Breite")
    }

    @Test
    fun aboveWhenBelowIsFull() = assertEquals(Offset(350f, 522f), place(Rect(400f, 600f, 600f, 780f)).topLeft)

    @Test
    fun besideOnTheRoomierSideWhenAboveAndBelowAreFull() {
        // großer Kreis rechts: links ist mehr Platz, Leiste vertikal mittig an der Auswahl
        assertEquals(Offset(482f, 400f), place(Rect(800f, 100f, 980f, 760f)).topLeft)
        // links: rechts daneben
        assertEquals(Offset(218f, 400f), place(Rect(20f, 100f, 200f, 760f)).topLeft)
    }

    @Test
    fun besideNarrowedWhenTheBarIsWiderThanTheSide() {
        // Leiste 900 breit, rechts nur 200 frei: dort auf 200 begrenzt (Chips scrollen)
        val p = place(Rect(20f, 100f, 774f, 760f), barW = 900f)
        assertEquals(Offset(792f, 400f), p.topLeft)
        assertEquals(200f, p.width)
    }

    @Test
    fun bottomWhenNothingFits() = assertEquals(Offset(350f, 732f), place(Rect(150f, 60f, 850f, 760f)).topLeft)
}
