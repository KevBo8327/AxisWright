package dev.androidcad.app

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Prüft, dass die FOSS-Variante ohne gesetzte Spenden-URLs keinen Unterstützen-Eintrag zeigt und gesetzte Links korrekt filtert. */
class DistributionTest {
    @Test
    fun donationMenuAndDialogStayHiddenWhileUrlsAreUnset() {
        assertEquals("", Distribution.LIBERAPAY_URL)
        assertEquals("", Distribution.GITHUB_SPONSORS_URL)
        assertFalse(Distribution.hasSupportLinks())
        assertTrue(Distribution.supportLinks().isEmpty())
    }

    @Test
    fun supportLinksOnlyIncludeConfiguredNonBlankUrls() {
        assertFalse(Distribution.hasSupportLinks("", "   "))
        assertTrue(Distribution.supportLinks("", "   ").isEmpty())

        val onlyLiberapay = Distribution.supportLinks("https://liberapay.com/axiswright", "")
        assertTrue(Distribution.hasSupportLinks("https://liberapay.com/axiswright", ""))
        assertEquals(
            listOf(Distribution.SupportLink(Distribution.SupportProvider.LIBERAPAY, "https://liberapay.com/axiswright")),
            onlyLiberapay,
        )

        val onlyGithub = Distribution.supportLinks("", "https://github.com/sponsors/KevBo8327")
        assertTrue(Distribution.hasSupportLinks("", "https://github.com/sponsors/KevBo8327"))
        assertEquals(
            listOf(Distribution.SupportLink(Distribution.SupportProvider.GITHUB_SPONSORS, "https://github.com/sponsors/KevBo8327")),
            onlyGithub,
        )

        val both = Distribution.supportLinks("https://liberapay.com/axiswright", "https://github.com/sponsors/KevBo8327")
        assertEquals(2, both.size)
    }
}
