package dev.androidcad.app

import com.android.billingclient.api.Purchase.PurchaseState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProTest {
    @Test
    fun lockedOnlyWithoutPurchaseAndOutsideTestPhase() {
        assertFalse(proUnlocked(isPro = false, freeDuringTest = false))
        assertTrue(proUnlocked(isPro = true, freeDuringTest = false))
        assertTrue(proUnlocked(isPro = false, freeDuringTest = true))
    }

    @Test
    fun purchasedWinsOverPendingNothingMeansFree() {
        assertEquals(ProStatus.FREE, proStatus(emptyList()))
        assertEquals(ProStatus.FREE, proStatus(listOf(PurchaseState.UNSPECIFIED_STATE)))
        assertEquals(ProStatus.PENDING, proStatus(listOf(PurchaseState.PENDING)))
        assertEquals(ProStatus.PRO, proStatus(listOf(PurchaseState.PENDING, PurchaseState.PURCHASED)))
    }
}
