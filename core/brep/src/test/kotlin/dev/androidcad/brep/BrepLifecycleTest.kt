package dev.androidcad.brep

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrepLifecycleTest {
    @Test
    fun brepShapeDoesNotUseFinalize() {
        val hasFinalize = BrepShape::class.java.declaredMethods.any { it.name == "finalize" }
        assertFalse(hasFinalize, "BrepShape darf kein veraltetes finalize() mehr verwenden")
    }

    @Test
    fun handleGuardWaitsForInFlightUseAndReleasesOnce() {
        val releaseCount = AtomicInteger(0)
        val inUse = AtomicBoolean(false)
        val releasedDuringUse = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val allowExit = CountDownLatch(1)
        val guard = HandleGuard(42L) {
            if (inUse.get()) releasedDuringUse.set(true)
            releaseCount.incrementAndGet()
        }

        val pool = Executors.newFixedThreadPool(3)
        try {
            val useFuture = pool.submit {
                guard.use { h ->
                    assertEquals(42L, h)
                    inUse.set(true)
                    entered.countDown()
                    assertTrue(allowExit.await(5, TimeUnit.SECONDS))
                    inUse.set(false)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeA = pool.submit { guard.close() }
            val closeB = pool.submit { guard.close() }
            Thread.sleep(40)
            assertEquals(0, releaseCount.get(), "close() muss auf laufenden use()-Aufruf warten")

            allowExit.countDown()
            useFuture.get(5, TimeUnit.SECONDS)
            closeA.get(5, TimeUnit.SECONDS)
            closeB.get(5, TimeUnit.SECONDS)

            assertFalse(releasedDuringUse.get(), "Handle darf nicht während use() freigegeben werden")
            assertEquals(1, releaseCount.get(), "Handle darf höchstens einmal freigegeben werden")
            assertFailsWith<IllegalStateException> { guard.use { it } }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun handleGuardUseWithRejectsClosedPartnerAndSerializesClose() {
        val releasedOther = AtomicInteger(0)
        val inUse = AtomicBoolean(false)
        val releasedDuringUse = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val allowExit = CountDownLatch(1)

        val a = HandleGuard(1L) {}
        val b = HandleGuard(2L) {
            if (inUse.get()) releasedDuringUse.set(true)
            releasedOther.incrementAndGet()
        }

        val pool = Executors.newFixedThreadPool(2)
        try {
            val useFuture = pool.submit {
                a.useWith(b) { ha, hb ->
                    assertEquals(1L, ha)
                    assertEquals(2L, hb)
                    inUse.set(true)
                    entered.countDown()
                    assertTrue(allowExit.await(5, TimeUnit.SECONDS))
                    inUse.set(false)
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closeFuture = pool.submit { b.close() }
            Thread.sleep(40)
            assertEquals(0, releasedOther.get(), "close() am Partner-Handle muss auf useWith() warten")

            allowExit.countDown()
            useFuture.get(5, TimeUnit.SECONDS)
            closeFuture.get(5, TimeUnit.SECONDS)

            assertFalse(releasedDuringUse.get())
            assertEquals(1, releasedOther.get())
            assertFailsWith<IllegalStateException> { a.useWith(b) { _, _ -> } }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun handleGuardCleansUpUnclosedOwnerOnGc() {
        val released = CountDownLatch(1)
        fun allocate() {
            val owner = Any()
            HandleGuard(99L) { released.countDown() }.register(owner)
        }
        allocate()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (released.count > 0 && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20)
        }
        assertEquals(0L, released.count, "Unclosed HandleGuard muss per PhantomReference freigegeben werden")
    }
}
