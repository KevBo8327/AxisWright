package dev.androidcad.brep

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Synchronisiert Zugriffe auf ein natives Handle gegen [close] und gibt vergessene Handles
 * per [PhantomReference] + Daemon-Thread frei (`java.lang.ref.Cleaner` gibt es auf Android erst ab API 33).
 */
internal class HandleGuard(
    private val handle: Long,
    private val releaser: (Long) -> Unit,
) {
    private val lock = ReentrantReadWriteLock()
    @Volatile private var closed = false
    private var ref: CleanableRef? = null

    fun register(owner: Any) {
        val cleanable = CleanableRef(owner, this, queue)
        activeRefs.add(cleanable)
        ref = cleanable
    }

    inline fun <T> use(block: (Long) -> T): T {
        val rl = lock.readLock()
        rl.lock()
        try {
            check(!closed) { "Körper wurde bereits freigegeben" }
            return block(handle)
        } finally {
            rl.unlock()
        }
    }

    inline fun <T> useWith(other: HandleGuard, block: (Long, Long) -> T): T {
        val r1 = lock.readLock()
        val r2 = other.lock.readLock()
        r1.lock()
        try {
            r2.lock()
            try {
                check(!closed && !other.closed) { "Körper wurde bereits freigegeben" }
                return block(handle, other.handle)
            } finally {
                r2.unlock()
            }
        } finally {
            r1.unlock()
        }
    }

    fun close() {
        val wl = lock.writeLock()
        wl.lock()
        try {
            if (closed) return
            closed = true
            ref?.let {
                activeRefs.remove(it)
                it.clear()
                ref = null
            }
            releaser(handle)
        } finally {
            wl.unlock()
        }
    }

    private class CleanableRef(
        owner: Any,
        val guard: HandleGuard,
        queue: ReferenceQueue<Any>,
    ) : PhantomReference<Any>(owner, queue)

    private companion object {
        val queue = ReferenceQueue<Any>()
        val activeRefs = ConcurrentHashMap.newKeySet<CleanableRef>()

        init {
            Thread({
                while (true) {
                    try {
                        val ref = queue.remove() as CleanableRef
                        activeRefs.remove(ref)
                        ref.clear()
                        ref.guard.close()
                    } catch (_: Throwable) {
                        // Hintergrund-Bereinigung darf nie abbrechen
                    }
                }
            }, "BrepShapeCleaner").apply {
                isDaemon = true
                start()
            }
        }
    }
}
