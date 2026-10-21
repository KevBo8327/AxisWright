package dev.androidcad.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Eine mitgelieferte Fremdbibliothek: Name, Zweck, Lizenz, Herkunft und die Lizenztexte in `assets/licenses`. */
internal data class ThirdParty(
    val name: String,
    val use: String,
    val license: String,
    val source: String,
    val files: List<String>,
    val note: String? = null,
)

/** Alle Fremdbibliotheken der App (Reihenfolge = Anzeige). */
internal val THIRD_PARTY = listOf(
    ThirdParty(
        "Open CASCADE Technology 7.9.3", "Geometriekern (B-Rep: Körper, Flächen, Kanten, Verrundungen)",
        "GNU LGPL 2.1 mit Open-CASCADE-Ausnahme", "https://github.com/Open-Cascade-SAS/OCCT (Tag V7_9_3)",
        listOf("occt_lgpl_2.1.txt", "occt_lgpl_exception.txt", "occt_delabella_mit.txt", "occt_flex_bsd.txt", "occt_strtod_lucent.txt", "occt_unicode.txt"),
        // LGPL 2.1 §6: austauschbar (eigene .so) und schriftliches Angebot des Quelltexts – der App-Quelltext selbst ist nicht öffentlich
        note = "Unverändert als eigene Bibliotheken (libTK*.so) mitgeliefert und dadurch gegen eine selbst gebaute Version austauschbar. " +
            "Den vollständigen Quelltext dieser OCCT-Version samt Bauanleitung senden wir auf Anfrage an kb-it-services@outlook.de " +
            "kostenlos zu (mindestens drei Jahre nach Erhalt dieser App-Version). Diese Bibliotheken dürfen für den eigenen Gebrauch " +
            "verändert und zur Fehlersuche an solchen Änderungen zurückentwickelt werden (LGPL 2.1 § 6). " +
            "Enthält DELABELLA (MIT), flex (BSD), strtod von David M. Gay/Lucent und Unicode-Code (Unicode, Inc.).",
    ),
    ThirdParty(
        "Manifold 3", "Körperberechnung im Skript-Editor (OpenSCAD)", "Apache License 2.0",
        "https://github.com/elalish/manifold",
        listOf("apache_2.0.txt", "manifold_svd_mit.txt", "physx_bsd_3.txt", "manifold_math_sun_musl.txt"),
        note = "Enthält Code aus NVIDIA PhysX (BSD 3-Clause), musl/FreeBSD msun und eine SVD-Routine (MIT).",
    ),
    ThirdParty(
        "Clipper2", "2D-Polygonoperationen (über Manifold)", "Boost Software License 1.0",
        "https://github.com/AngusJohnson/Clipper2", listOf("clipper2_bsl_1.0.txt"),
    ),
    ThirdParty(
        "LLVM libc++", "C++-Standardbibliothek der nativen Teile (libc++_shared.so aus dem Android NDK)",
        "Apache License 2.0 mit LLVM-Ausnahmen", "© LLVM Project · https://libcxx.llvm.org", listOf("apache_2.0.txt", "llvm_libcxx_exception.txt"),
    ),
    ThirdParty(
        "Haze", "Glas-Effekt (Unschärfe hinter schwebenden Flächen)", "Apache License 2.0",
        "© Chris Banes · https://github.com/chrisbanes/haze", listOf("apache_2.0.txt"),
    ),
    ThirdParty(
        "Kotlin-Standardbibliothek und kotlinx.coroutines", "Programmiersprache und nebenläufige Abläufe",
        "Apache License 2.0", "© JetBrains s.r.o. und Mitwirkende · https://github.com/JetBrains/kotlin", listOf("apache_2.0.txt"),
    ),
    ThirdParty(
        "AndroidX: Jetpack Compose, Material 3, Activity", "Oberfläche", "Apache License 2.0",
        "© The Android Open Source Project · https://developer.android.com/jetpack/androidx", listOf("apache_2.0.txt"),
    ),
)

/** „Lizenzen“: Fremdbibliotheken mit Lizenz und Herkunft; ein Tipp zeigt den vollständigen Lizenztext. */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf<ThirdParty?>(null) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lizenzen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Diese App nutzt folgende freie Software. Danke an alle Beteiligten!", style = MaterialTheme.typography.bodySmall)
                THIRD_PARTY.forEach { t ->
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().clickable { shown = t }.padding(vertical = 4.dp)) {
                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                        Text(t.use, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("${t.license} · ${t.source}", style = MaterialTheme.typography.bodySmall)
                        t.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                        Text("Lizenztext anzeigen ›", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
    shown?.let { t -> LicenseTextDialog(t) { shown = null } }
}

@Composable
private fun LicenseTextDialog(t: ThirdParty, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(t) {
        t.files.joinToString("\n\n――――――――\n\n") { f ->
            runCatching { context.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrElse { "($f fehlt)" }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t.license) },
        text = {
            Text(
                text, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zurück") } },
    )
}
