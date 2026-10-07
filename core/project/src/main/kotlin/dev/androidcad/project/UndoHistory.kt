package dev.androidcad.project

/**
 * Linearer Undo/Redo-Verlauf aus Zustands-Schnappschüssen.
 *
 * Aufeinanderfolgende Änderungen mit demselben [record]-`coalesceKey` innerhalb von [coalesceWindowMs]
 * werden zu **einem** Schritt zusammengefasst (Tippen, Slider-Ziehen). Eine Änderung nach einem Undo
 * verwirft den Redo-Zweig.
 *
 * [current] ist immer der zuletzt aufgezeichnete bzw. wiederhergestellte Zustand.
 */
public class UndoHistory<T>(
    initial: T,
    private val maxSize: Int = 200,
    private val coalesceWindowMs: Long = 800,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxSize >= 2) { "maxSize muss mindestens 2 sein" }
    }

    private val entries = ArrayList<T>().apply { add(initial) }
    private var index = 0
    private var lastKey: Any? = null
    private var lastTime = 0L

    public val current: T get() = entries[index]
    public val canUndo: Boolean get() = index > 0
    public val canRedo: Boolean get() = index < entries.lastIndex

    /** Zeichnet [state] als neuen aktuellen Zustand auf. */
    public fun record(state: T, coalesceKey: Any? = null) {
        val now = clock()
        val merge = coalesceKey != null && coalesceKey == lastKey && now - lastTime <= coalesceWindowMs && index > 0
        if (merge) {
            entries[index] = state
            // Redo-Zweig kann hier nicht existieren (Undo/Redo setzen lastKey zurück); sicherheitshalber kappen.
            while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
        } else {
            while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
            entries.add(state)
            index++
            if (entries.size > maxSize) {
                entries.removeAt(0)
                index--
            }
        }
        lastKey = coalesceKey
        lastTime = now
    }

    /** Geht einen Schritt zurück; `null`, wenn nichts mehr rückgängig zu machen ist. */
    public fun undo(): T? {
        if (!canUndo) return null
        index--
        lastKey = null
        return entries[index]
    }

    /** Geht einen Schritt vor; `null`, wenn kein Redo-Zweig existiert. */
    public fun redo(): T? {
        if (!canRedo) return null
        index++
        lastKey = null
        return entries[index]
    }

    /** Beendet die aktuelle Zusammenfassung (z. B. wenn der Fokus wechselt). */
    public fun breakCoalescing() {
        lastKey = null
    }
}
