package dev.androidcad.model

import dev.androidcad.text.Msg
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Prüft, dass [msg] den Hauptschlüssel [ModelText.FEATURE_FAILED] trägt
 * und der innere Grund ([Msg]) den Schlüssel [expectedKey] hat.
 */
fun assertFeatureFailedWithReason(msg: Msg?, expectedKey: Enum<*>) {
    assertNotNull(msg, "Feature-Fehler erwartet, aber null")
    assertEquals(ModelText.FEATURE_FAILED, msg.key, "Hauptschlüssel muss FEATURE_FAILED sein")
    val reason = msg.args.getOrNull(1) as? Msg
    assertNotNull(reason, "Innerer Grund (Msg) fehlt in Feature-Fehler: $msg")
    assertEquals(expectedKey, reason.key, "Innerer Fehlerschlüssel passt nicht")
}
