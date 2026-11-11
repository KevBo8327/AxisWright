package dev.androidcad.app

import androidx.compose.runtime.Composable

// TODO(Nutzer): Eigene Quellcode-URL eintragen
object Distribution {
    const val SOURCE_CODE_URL = "https://PLACEHOLDER/source"

    @Composable
    fun MenuItems(onOpenSupport: () -> Unit) {
        // In Play-Builds existiert kein Spenden-Menüeintrag (Google Play Richtlinien)
    }

    @Composable
    fun Dialogs(showSupport: Boolean, onDismissSupport: () -> Unit) {
        // In Play-Builds existiert kein Spenden-Dialog
    }
}
