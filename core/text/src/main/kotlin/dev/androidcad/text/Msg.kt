package dev.androidcad.text

/**
 * Nutzersichtbare Meldung eines Kernmoduls – ohne fertigen Text: ein Schlüssel ([key], ein Enum-Eintrag wie
 * `SketchText.LINE_NO_LENGTH`) und die Werte, die eingesetzt werden. Den Wortlaut kennt nur die App
 * (`CoreText.kt` → `res/values/strings_core.xml` und `values-de/`), damit die Oberfläche die Gerätesprache sprechen kann.
 *
 * [args] sind Zahlen, Namen (Text), weitere [Msg] oder Enum-Einträge, die selbst Schlüssel sind. Bei Pluralen ist das erste
 * Argument die Anzahl (Int).
 */
public class Msg(public val key: Enum<*>, vararg args: Any) {
    public val args: List<Any> = args.toList()

    override fun equals(other: Any?): Boolean = other is Msg && other.key == key && other.args == args
    override fun hashCode(): Int = 31 * key.hashCode() + args.hashCode()

    /** Nur für Logs, Ausnahmen und Tests, z. B. `OPEN_END(1.5, 2.0)` – nie für die Oberfläche. */
    override fun toString(): String = if (args.isEmpty()) key.name else "${key.name}(${args.joinToString()})"
}
