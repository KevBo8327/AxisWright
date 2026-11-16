package dev.androidcad.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

object Distribution {
    const val SOURCE_CODE_URL = "https://github.com/KevBo8327/AxisWright"

    // TODO(Nutzer): Eigene Spenden-URLs eintragen (leer lassen, bis die Konten eingerichtet sind)
    const val LIBERAPAY_URL = ""
    const val GITHUB_SPONSORS_URL = ""

    internal enum class SupportProvider { LIBERAPAY, GITHUB_SPONSORS }

    internal data class SupportLink(val provider: SupportProvider, val url: String)

    internal fun supportLinks(
        liberapayUrl: String = LIBERAPAY_URL,
        githubSponsorsUrl: String = GITHUB_SPONSORS_URL,
    ): List<SupportLink> = buildList {
        if (liberapayUrl.isNotBlank()) add(SupportLink(SupportProvider.LIBERAPAY, liberapayUrl))
        if (githubSponsorsUrl.isNotBlank()) add(SupportLink(SupportProvider.GITHUB_SPONSORS, githubSponsorsUrl))
    }

    internal fun hasSupportLinks(
        liberapayUrl: String = LIBERAPAY_URL,
        githubSponsorsUrl: String = GITHUB_SPONSORS_URL,
    ): Boolean = supportLinks(liberapayUrl, githubSponsorsUrl).isNotEmpty()

    @Composable
    fun MenuItems(onOpenSupport: () -> Unit) {
        if (!hasSupportLinks()) return
        MenuItem(
            text = stringResource(R.string.menu_support),
            icon = CadIcon.HEART,
            onClick = onOpenSupport,
        )
    }

    @Composable
    fun Dialogs(showSupport: Boolean, onDismissSupport: () -> Unit) {
        if (showSupport && hasSupportLinks()) {
            SupportDialog(onDismiss = onDismissSupport)
        }
    }
}

@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val links = Distribution.supportLinks()
    if (links.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.support_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (link in links) {
                        val labelRes = when (link.provider) {
                            Distribution.SupportProvider.LIBERAPAY -> R.string.support_liberapay
                            Distribution.SupportProvider.GITHUB_SPONSORS -> R.string.support_github
                        }
                        Button(
                            onClick = { openUrl(context, link.url) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(labelRes))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}
