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

// TODO(Nutzer): Eigene Spenden- und Quellcode-URLs eintragen
object Distribution {
    const val SOURCE_CODE_URL = "https://PLACEHOLDER/source"
    const val LIBERAPAY_URL = "https://liberapay.com/PLACEHOLDER"
    const val GITHUB_SPONSORS_URL = "https://github.com/sponsors/PLACEHOLDER"

    @Composable
    fun MenuItems(onOpenSupport: () -> Unit) {
        MenuItem(
            text = stringResource(R.string.menu_support),
            icon = CadIcon.HEART,
            onClick = onOpenSupport,
        )
    }

    @Composable
    fun Dialogs(showSupport: Boolean, onDismissSupport: () -> Unit) {
        if (showSupport) {
            SupportDialog(onDismiss = onDismissSupport)
        }
    }
}

@Composable
private fun SupportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
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
                    Button(
                        onClick = {
                            openUrl(context, Distribution.LIBERAPAY_URL)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.support_liberapay))
                    }
                    Button(
                        onClick = {
                            openUrl(context, Distribution.GITHUB_SPONSORS_URL)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.support_github))
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
