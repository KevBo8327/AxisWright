package dev.androidcad.app

import androidx.compose.runtime.Composable

object Distribution {
    const val SOURCE_CODE_URL = "https://github.com/KevBo8327/AxisWright"

    @Composable
    fun MenuItems(onOpenSupport: () -> Unit) {
        // In Play-Builds existiert kein Spenden-Menüeintrag (Google Play Richtlinien)
    }

    @Composable
    fun Dialogs(showSupport: Boolean, onDismissSupport: () -> Unit) {
        // In Play-Builds existiert kein Spenden-Dialog
    }
}
