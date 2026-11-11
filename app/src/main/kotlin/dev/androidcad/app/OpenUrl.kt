package dev.androidcad.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Öffnet eine Web-Adresse im Browser oder fängt den Fehler ab, falls keine passende
 * App installiert ist, und zeigt stattdessen einen Toast mit der URL.
 */
fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            context.getString(R.string.common_no_browser, url),
            Toast.LENGTH_LONG,
        ).show()
    }
}
