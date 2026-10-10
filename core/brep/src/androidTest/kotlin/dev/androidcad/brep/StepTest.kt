package dev.androidcad.brep

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

class StepTest {
    @Test
    fun boxWithHoleIsWrittenAsSolidInMillimetres() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "test.step")
        val profile = Profile.rectangle(0.0, 0.0, 40.0, 20.0).withHole(listOf(ProfileSegment.Circle(10.0, 10.0, 3.0)))
        BrepShape.extrude(profile, 10.0).use { it.writeStep(file) }
        val text = file.readText()
        assertTrue(text.startsWith("ISO-10303-21;"), text.take(40))
        assertTrue(text.contains("MANIFOLD_SOLID_BREP"), "ein Volumenkörper")
        assertTrue(text.contains("CYLINDRICAL_SURFACE"), "die Bohrung als exakter Zylinder")
        assertTrue(text.contains(".MILLI."), "Einheit Millimeter")
        file.delete()
    }
}
