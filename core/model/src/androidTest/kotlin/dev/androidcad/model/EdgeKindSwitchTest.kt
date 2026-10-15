package dev.androidcad.model

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertTrue

/** Gerätetest-Befund: eine Fase ↔ Verrundung umschalten muss den Körper neu bauen (Modell aus dem Test auf dem Honor). */
class EdgeKindSwitchTest {
    private fun load(): Document = DocumentCodec.decode(
        InstrumentationRegistry.getInstrumentation().context.assets.open("fase-modell.json").use { it.readBytes().toString(Charsets.UTF_8) },
    )

    private fun volume(doc: Document): Double = doc.build().use { res ->
        assertTrue(res.isOk, res.errors.toString())
        res.body!!.volume()
    }

    @Test
    fun switchingBetweenChamferAndRoundRebuildsTheBody() {
        val doc = load()
        val fase = doc.features.filterIsInstance<FilletFeature>().single()
        val chamfered = volume(doc)
        doc.update(fase.copy(kind = EdgeKind.ROUND))
        val rounded = volume(doc)
        doc.update(fase.copy(kind = EdgeKind.CHAMFER))
        val back = volume(doc)
        assertTrue(rounded > chamfered, "Verrundung nimmt weniger weg als die Fase: $rounded / $chamfered")
        assertTrue(kotlin.math.abs(back - chamfered) < 1e-6)
    }
}
