package dev.androidcad.app

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Eine mitgelieferte Fremdbibliothek: Name, Zweck, Lizenz, Herkunft (Ressourcen) und die Lizenztexte in `assets/licenses`. */
internal data class ThirdParty(
    @StringRes val name: Int,
    @StringRes val use: Int,
    @StringRes val license: Int,
    @StringRes val source: Int,
    val files: List<String>,
    @StringRes val note: Int? = null,
)

/** Alle Fremdbibliotheken der App (Reihenfolge = Anzeige). */
internal val THIRD_PARTY = listOf(
    ThirdParty(
        R.string.legal_lib_occt_name, R.string.legal_lib_occt_use, R.string.legal_lib_occt_license, R.string.legal_lib_occt_source,
        listOf("occt_lgpl_2.1.txt", "occt_lgpl_exception.txt", "occt_delabella_mit.txt", "occt_flex_bsd.txt", "occt_strtod_lucent.txt", "occt_unicode.txt"),
        // LGPL 2.1 §6: austauschbar (eigene .so) und schriftliches Angebot des Quelltexts – der App-Quelltext selbst ist nicht öffentlich
        note = R.string.legal_lib_occt_note,
    ),
    ThirdParty(
        R.string.legal_lib_manifold_name, R.string.legal_lib_manifold_use, R.string.legal_license_apache, R.string.legal_lib_manifold_source,
        listOf("apache_2.0.txt", "manifold_svd_mit.txt", "physx_bsd_3.txt", "manifold_math_sun_musl.txt"),
        note = R.string.legal_lib_manifold_note,
    ),
    ThirdParty(
        R.string.legal_lib_clipper_name, R.string.legal_lib_clipper_use, R.string.legal_lib_clipper_license, R.string.legal_lib_clipper_source,
        listOf("clipper2_bsl_1.0.txt"),
    ),
    ThirdParty(
        R.string.legal_lib_libcxx_name, R.string.legal_lib_libcxx_use, R.string.legal_lib_libcxx_license, R.string.legal_lib_libcxx_source,
        listOf("apache_2.0.txt", "llvm_libcxx_exception.txt"),
    ),
    ThirdParty(
        R.string.legal_lib_haze_name, R.string.legal_lib_haze_use, R.string.legal_license_apache, R.string.legal_lib_haze_source,
        listOf("apache_2.0.txt"),
    ),
    ThirdParty(
        R.string.legal_lib_kotlin_name, R.string.legal_lib_kotlin_use, R.string.legal_license_apache, R.string.legal_lib_kotlin_source,
        listOf("apache_2.0.txt"),
    ),
    ThirdParty(
        R.string.legal_lib_androidx_name, R.string.legal_lib_androidx_use, R.string.legal_license_apache, R.string.legal_lib_androidx_source,
        listOf("apache_2.0.txt"),
    ),
    ThirdParty(
        R.string.legal_lib_billing_name, R.string.legal_lib_billing_use, R.string.legal_lib_billing_license, R.string.legal_lib_billing_source,
        listOf("google_play_notices.txt"),
        note = R.string.legal_lib_billing_note,
    ),
)

/** „Lizenzen“: Fremdbibliotheken mit Lizenz und Herkunft; ein Tipp zeigt den vollständigen Lizenztext. */
@Composable
internal fun LicensesDialog(onDismiss: () -> Unit) {
    var shown by remember { mutableStateOf<ThirdParty?>(null) }
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.legal_licenses_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.legal_licenses_intro), style = MaterialTheme.typography.bodySmall)
                THIRD_PARTY.forEach { t ->
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().clickable { shown = t }.padding(vertical = 4.dp)) {
                        Text(stringResource(t.name), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(t.use), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        Text("${stringResource(t.license)} · ${stringResource(t.source)}", style = MaterialTheme.typography.bodySmall)
                        t.note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                        Text(stringResource(R.string.legal_licenses_show_text), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
    shown?.let { t -> LicenseTextDialog(t) { shown = null } }
}

@Composable
private fun LicenseTextDialog(t: ThirdParty, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = LocalResources.current
    // Zeilenweise (null = lädt noch): Lesen abseits des UI-Threads, Anzeige lazy – die Google-Hinweise sind ~560 KB groß
    val lines by produceState<List<String>?>(null, t) {
        value = withContext(Dispatchers.IO) {
            t.files.joinToString("\n\n――――――――\n\n") { f ->
                runCatching { context.assets.open("licenses/$f").bufferedReader().use { it.readText() } }.getOrElse { res.getString(R.string.legal_licenses_file_missing, f) }
            }.lines()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(t.license)) },
        text = {
            val l = lines
            if (l == null) Text(stringResource(R.string.legal_licenses_loading), style = MaterialTheme.typography.bodySmall)
            else LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(l) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_back)) } },
    )
}
