package dev.androidcad.brep

import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
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

    @Test
    fun handleGuardUseWithOppositeOrdersWithParallelCloseDoesNotDeadlock() {
        val pool = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "UseWithRace").apply { isDaemon = true }
        }
        try {
            val rounds = 2000
            for (i in 0 until rounds) {
                val a = HandleGuard(1000L + i * 2L) {}
                val b = HandleGuard(1001L + i * 2L) {}
                val start = CyclicBarrier(4)
                val f1 = pool.submit {
                    start.await()
                    try {
                        a.useWith(b) { ha, hb ->
                            assertEquals(1000L + i * 2L, ha)
                            assertEquals(1001L + i * 2L, hb)
                        }
                    } catch (_: IllegalStateException) {
                    }
                }
                val f2 = pool.submit {
                    start.await()
                    try {
                        b.useWith(a) { hb, ha ->
                            assertEquals(1001L + i * 2L, hb)
                            assertEquals(1000L + i * 2L, ha)
                        }
                    } catch (_: IllegalStateException) {
                    }
                }
                val f3 = pool.submit {
                    start.await()
                    a.close()
                }
                val f4 = pool.submit {
                    start.await()
                    b.close()
                }
                f1.get(3, TimeUnit.SECONDS)
                f2.get(3, TimeUnit.SECONDS)
                f3.get(3, TimeUnit.SECONDS)
                f4.get(3, TimeUnit.SECONDS)
            }

            // Gleiches Handle / gleicher Guard darf ebenfalls nicht klemmen oder doppelt blockieren
            val selfLock = ReentrantReadWriteLock()
            val selfGuard = HandleGuard(77L, selfLock) {}
            selfGuard.useWith(selfGuard) { h1, h2 ->
                assertEquals(77L, h1)
                assertEquals(77L, h2)
                assertEquals(1, selfLock.readHoldCount, "Gleicher Guard darf nur einmal gesperrt werden")
            }
            selfGuard.close()
            assertFailsWith<IllegalStateException> { selfGuard.useWith(selfGuard) { _, _ -> } }

            // Verschiedene Guards mit gleichem Handle-Wert brauchen dennoch konsistente Reihenfolge ohne Deadlock
            val eq1 = HandleGuard(5L) {}
            val eq2 = HandleGuard(5L) {}
            eq1.useWith(eq2) { h1, h2 ->
                assertEquals(5L, h1)
                assertEquals(5L, h2)
            }
            eq2.useWith(eq1) { h1, h2 ->
                assertEquals(5L, h1)
                assertEquals(5L, h2)
            }
            eq1.close()
            eq2.close()
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun handleGuardUseWithDeterministicInterleavingDoesNotDeadlock() {
        val firstReadersHeld = CountDownLatch(2)
        val releaseReadersToSecondLock = CountDownLatch(1)

        class HookLock(val pauseOnThreadName: String) : ReentrantReadWriteLock() {
            private val hookedRead = object : ReadLock(this) {
                override fun lock() {
                    super.lock()
                    if (Thread.currentThread().name == pauseOnThreadName) {
                        firstReadersHeld.countDown()
                        releaseReadersToSecondLock.await(80, TimeUnit.MILLISECONDS)
                    }
                }
            }

            override fun readLock(): ReadLock = hookedRead
        }

        val a = HandleGuard(10L, HookLock("reader-ab")) {}
        val b = HandleGuard(20L, HookLock("reader-ba")) {}
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        val tA = Thread({
            try {
                a.useWith(b) { _, _ -> }
            } catch (_: IllegalStateException) {
            } catch (t: Throwable) {
                errors.add(t)
            }
        }, "reader-ab").apply { isDaemon = true }

        val tB = Thread({
            try {
                b.useWith(a) { _, _ -> }
            } catch (_: IllegalStateException) {
            } catch (t: Throwable) {
                errors.add(t)
            }
        }, "reader-ba").apply { isDaemon = true }

        val wA = Thread({
            try {
                a.close()
            } catch (t: Throwable) {
                errors.add(t)
            }
        }, "writer-a").apply { isDaemon = true }

        val wB = Thread({
            try {
                b.close()
            } catch (t: Throwable) {
                errors.add(t)
            }
        }, "writer-b").apply { isDaemon = true }

        tA.start()
        tB.start()
        // Nach dem Fix wartet reader-ba nicht auf b.read, bevor a.read frei ist; ohne Fix halten beide ihren 1. Read-Lock sofort
        if (firstReadersHeld.await(40, TimeUnit.MILLISECONDS)) {
            wA.start()
            wB.start()
            val waitDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)
            while ((wA.state != Thread.State.WAITING || wB.state != Thread.State.WAITING) &&
                System.nanoTime() < waitDeadline
            ) {
                Thread.yield()
            }
            releaseReadersToSecondLock.countDown()
        } else {
            wA.start()
            wB.start()
            releaseReadersToSecondLock.countDown()
        }

        val joinDeadline = System.currentTimeMillis() + 2000L
        for (t in listOf(tA, tB, wA, wB)) {
            val remaining = (joinDeadline - System.currentTimeMillis()).coerceAtLeast(1L)
            t.join(remaining)
            assertFalse(t.isAlive, "Thread ${t.name} hängt (möglicher Deadlock in useWith/close)")
        }
        assertTrue(errors.isEmpty(), "Unerwartete Fehler: $errors")
    }
}
