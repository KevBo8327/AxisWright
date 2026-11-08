package dev.androidcad.sketch

/**
 * Schnittstelle für Schriftumrisse.
 *
 * Der Kern bleibt frei von Android-Abhängigkeiten; die App erfüllt die Schnittstelle (z. B. mit
 * `Paint.getTextPath` und `Path.approximate`), im Test können einfache Ersatz-Umrisse genutzt werden.
 */
public fun interface TextOutlines {
    /**
     * Liefert geschlossene Konturen für [text] in der Höhe [height] (in mm).
     *
     * Der Ursprung (0, 0) liegt am Anfang der Grundlinie (Baseline). x wächst nach rechts, y nach oben.
     * Jede Kontur ist ein geschlossener [Loop] aus [LoopSegment]s (typischerweise [LoopSegment.Line]).
     */
    public fun outlines(text: String, height: Double): List<Loop>

    public companion object {
        /** Standard-Implementierung zur Laufzeit (von der App gesetzt). */
        public var default: TextOutlines? = null
    }
}
