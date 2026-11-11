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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Anbieterkennzeichnung nach § 5 DDG, in der App (Internet nur für den Pro-Kauf über Google Play, ein Link allein wäre ohne Netz nicht erreichbar).
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
        title = { Text(stringResource(R.string.legal_imprint_title)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val body = MaterialTheme.typography.bodyMedium
                Heading(stringResource(R.string.legal_imprint_heading))
                Text(stringResource(R.string.legal_imprint_info), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.legal_imprint_contact, Imprint.NAME, Imprint.ADDRESS, Imprint.PHONE, Imprint.EMAIL), style = body)
                Heading(stringResource(R.string.legal_liability_heading))
                Text(stringResource(R.string.legal_liability_text), style = body)
                Heading(stringResource(R.string.legal_privacy_heading))
                Text(stringResource(R.string.legal_privacy_text), style = body)
                Text(stringResource(R.string.legal_privacy_feedback), style = body)
                Text(stringResource(R.string.legal_privacy_full, Imprint.PRIVACY_URL), style = body)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
