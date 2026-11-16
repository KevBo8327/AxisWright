package dev.androidcad.app

import org.junit.Test
import kotlin.test.assertEquals

/** Stellt sicher, dass die Quellcode-URL in beiden Varianten (play und foss) auf das AxisWright-Repository zeigt. */
class DistributionUrlTest {
    @Test
    fun sourceCodeUrlPointsToAxisWrightGitHubRepository() {
        assertEquals("https://github.com/KevBo8327/AxisWright", Distribution.SOURCE_CODE_URL)
    }
}
