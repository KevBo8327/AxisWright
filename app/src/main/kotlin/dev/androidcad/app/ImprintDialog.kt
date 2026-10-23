package dev.androidcad.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Anbieterkennzeichnung nach § 5 DDG, offline in der App (die App hat kein Internet, ein Link allein wäre nicht erreichbar).
 * Platzhalter in eckigen Klammern vor der Veröffentlichung ersetzen – dieselben wie in docs/privacy/index.html; scripts/play-check.py
 * meldet sie, solange sie stehen.
 */
internal object Imprint {
    const val NAME = "Kevin Bodenstein"
    const val ADDRESS = "14612 Falkensee, Rembrandtstraße 34"
    const val PHONE = "+49 3322 8312325" // zweiter unmittelbarer Kontaktweg neben der E-Mail (§ 5 Abs. 1 Nr. 2 DDG)
    const val EMAIL = "kb-it-services@outlook.de"
    const val PRIVACY_URL = "https://sites.google.com/view/paramworker/datenschutz"
}

/** „Impressum & Datenschutz“: Anbieter, Kontakt und die Datenschutz-Kurzfassung (vollständig unter [Imprint.PRIVACY_URL]). */
@Composable
internal fun ImprintDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impressum & Datenschutz") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Heading("Impressum")
                Text("Angaben gemäß § 5 DDG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Imprint.NAME}\n${Imprint.ADDRESS}\nTelefon: ${Imprint.PHONE}\nE-Mail: ${Imprint.EMAIL}", style = MaterialTheme.typography.bodyMedium)
                Heading("Haftung")
                Text(
                    "ParamWorker ist ein Werkzeug zum Konstruieren. Prüfen Sie Maße, Toleranzen und Festigkeit Ihrer Teile vor der Fertigung " +
                        "selbst; für sicherheitsrelevante Bauteile ist die App nicht vorgesehen. Die gesetzliche Haftung bei Vorsatz, grober " +
                        "Fahrlässigkeit und für Schäden an Leben, Körper oder Gesundheit bleibt unberührt.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Heading("Datenschutz")
                Text(
                    "ParamWorker selbst erhebt keine personenbezogenen Daten. Die App hat keinen Internetzugang, keine Werbung, keine Analyse- " +
                        "oder Tracking-Werkzeuge und kein Benutzerkonto. Projekte werden lokal gespeichert; Android kann sie abhängig von " +
                        "Ihren Systemeinstellungen in einer Gerätesicherung sichern. Beim Feedback öffnet sich Ihr E-Mail-Programm.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "„Feedback senden“ öffnet nur Ihr E-Mail-Programm mit einer vorbereiteten Nachricht (App-Version, Gerät, Android-Version); " +
                        "gesendet wird sie erst, wenn Sie sie selbst abschicken.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Vollständige Datenschutzerklärung: ${Imprint.PRIVACY_URL}", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
