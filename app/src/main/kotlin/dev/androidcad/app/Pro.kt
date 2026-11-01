package dev.androidcad.app

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Ergebnis der Käufe zu [Pro.PRODUCT_ID]: gekauft, Zahlung ausstehend oder kein Kauf. */
internal enum class ProStatus { PRO, PENDING, FREE }

/** Ein gekaufter Eintrag zählt vor einem ausstehenden; reine Logik, damit sie ohne Play Store testbar ist. */
internal fun proStatus(purchaseStates: List<Int>): ProStatus = when {
    Purchase.PurchaseState.PURCHASED in purchaseStates -> ProStatus.PRO
    Purchase.PurchaseState.PENDING in purchaseStates -> ProStatus.PENDING
    else -> ProStatus.FREE
}

/** Pro-Funktion nutzbar: gekauft oder Testphase. */
internal fun proUnlocked(isPro: Boolean, freeDuringTest: Boolean = Pro.FREE_DURING_TEST) = isPro || freeDuringTest

/**
 * Einmalkauf „ParamWorker Pro“ über Google Play Billing. Frei bleibt alles zum Modellieren; Pro schaltet STEP-/3MF-Export und das
 * Anlegen/Bearbeiten der Parameter-Tabelle frei. Vorhandene Parameter werden auch ohne Pro ausgewertet – niemand wird von seinen
 * Modellen ausgesperrt.
 *
 * Der Kaufstatus wird in den [UiPrefs] gemerkt, damit Pro offline gilt; erst eine erfolgreiche Abfrage ohne Kauf setzt ihn zurück.
 * Ohne Play Store (Emulator, Fehler) bleibt der gemerkte Stand, der Kaufdialog nennt den Grund.
 */
internal object Pro : PurchasesUpdatedListener {
    /** Testphase laut Store-Text: alle Funktionen frei, Kaufdialog bleibt erreichbar (Lizenztester). Vor 1.0 auf `false`. */
    const val FREE_DURING_TEST = true
    const val PRODUCT_ID = "paramworker_pro"
    private const val KEY = "pro.owned"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: BillingClient? = null
    private var prefs: UiPrefs? = null
    private var details: ProductDetails? = null

    var isPro by mutableStateOf(false)
        private set
    var price by mutableStateOf<String?>(null)
        private set
    /** Hinweis im Kaufdialog (ausstehend, Fehler, wiederhergestellt) oder `null`. */
    var message by mutableStateOf<String?>(null)
        private set
    /** Kaufdialog offen (Projektmenü oder gesperrte Funktion). */
    var dialogOpen by mutableStateOf(false)

    /** Beim App-Start: gemerkten Stand laden, mit Google Play verbinden und Käufe abfragen. */
    fun start(context: Context, prefs: UiPrefs) {
        if (client != null) return
        this.prefs = prefs
        isPro = prefs.text(KEY) == "1"
        client = BillingClient.newBuilder(context.applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build()
            .also {
                it.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (result.responseCode == BillingResponseCode.OK) refresh() else message = errorText(result.responseCode)
                    }
                    override fun onBillingServiceDisconnected() {} // enableAutoServiceReconnection verbindet beim nächsten Aufruf neu
                })
            }
    }

    /** Pro-Funktion nutzbar? Sonst öffnet sich der Kaufdialog – Antippen tut nie still nichts. */
    fun allows(): Boolean = proUnlocked(isPro).also { if (!it) dialogOpen = true }

    /** Preis und Käufe neu abfragen (Start, Rückkehr in die App, „Kauf wiederherstellen“). */
    fun refresh(onDone: () -> Unit = {}) {
        val c = client?.takeIf { it.isReady } ?: return onDone()
        scope.launch {
            val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(ProductType.INAPP).build()
            val pd = c.queryProductDetails(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build())
            details = pd.productDetailsList?.firstOrNull()
            price = details?.oneTimePurchaseOfferDetails?.formattedPrice
            val owned = c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build())
            if (owned.billingResult.responseCode == BillingResponseCode.OK) handle(owned.purchasesList, confirmed = true)
            onDone()
        }
    }

    fun restore() {
        if (client?.isReady != true) { message = errorText(BillingResponseCode.SERVICE_UNAVAILABLE); return }
        message = null
        refresh { if (message == null) message = if (isPro) "Kauf wiederhergestellt – Pro ist aktiv." else "Kein Kauf von ParamWorker Pro gefunden." }
    }

    fun buy(activity: Activity) {
        val c = client
        val d = details
        if (c == null || !c.isReady || d == null) { message = errorText(BillingResponseCode.SERVICE_UNAVAILABLE); return }
        message = null
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        val result = c.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build())
        if (result.responseCode != BillingResponseCode.OK) message = errorText(result.responseCode)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> handle(purchases.orEmpty(), confirmed = false)
            BillingResponseCode.USER_CANCELED -> {}
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> message = errorText(result.responseCode)
        }
    }

    /**
     * [confirmed]: vollständige Liste aus queryPurchasesAsync – nur dann setzt „kein Kauf“ auf frei zurück.
     * shortcut: Kauf nur lokal geprüft (keine Serverprüfung des Tokens), genügt für einen kleinen Einmalkauf; bei auffälligem
     * Missbrauch Prüfung über die Play Developer API auf einem eigenen Server ergänzen.
     */
    private fun handle(purchases: List<Purchase>, confirmed: Boolean) {
        val mine = purchases.filter { PRODUCT_ID in it.products }
        when (proStatus(mine.map { it.purchaseState })) {
            ProStatus.PRO -> {
                store(true)
                // unbestätigte Käufe erstattet Google nach drei Tagen automatisch
                mine.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { p ->
                    scope.launch { client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) }
                }
            }
            ProStatus.PENDING -> message = "Kauf ausstehend – Pro wird freigeschaltet, sobald die Zahlung bestätigt ist."
            ProStatus.FREE -> if (confirmed) store(false)
        }
    }

    private fun store(on: Boolean) {
        isPro = on
        if (on) prefs?.putText(KEY, "1") else prefs?.removeText(KEY)
    }

    private fun errorText(code: Int) = when (code) {
        BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED,
        BillingResponseCode.NETWORK_ERROR -> "Google Play ist nicht erreichbar. Kaufen und Wiederherstellen brauchen den Play Store und eine Internetverbindung."
        BillingResponseCode.ITEM_UNAVAILABLE -> "ParamWorker Pro ist im Play Store derzeit nicht verfügbar."
        else -> "Kauf nicht möglich (Google Play meldet Code $code)."
    }
}

/** Kennzeichen „(Pro)“ hinter einer Pro-Funktion; nach dem Kauf ausgeblendet. */
@Composable
internal fun ProMark() {
    if (!Pro.isPro) Text(" (Pro)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

/** „ParamWorker Pro“: was frei ist, was Pro bringt, Preis, Kaufen und Wiederherstellen. */
@Composable
internal fun ProDialog(activity: Activity?, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ParamWorker Pro") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (Pro.FREE_DURING_TEST && !Pro.isPro) Text(
                    "Testphase: Alle Pro-Funktionen sind zurzeit frei nutzbar.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.primary,
                )
                Heading("Pro – einmal kaufen, dauerhaft nutzen")
                Bullets("STEP-Export (Austausch mit anderen CAD-Programmen)", "3MF-Export (3D-Druck)", "Parameter-Tabelle anlegen und bearbeiten")
                Heading("Immer frei")
                Bullets(
                    "Modellieren: Skizzen, Körper und alle Werkzeuge", "Messen", "STL-Export", "Projekte teilen und importieren",
                    "Skript-Editor (OpenSCAD)", "Unbegrenzt viele Projekte",
                )
                Text(
                    "Vorhandene Parameter in Ihren Modellen werden auch ohne Pro weiter berechnet.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
                // ohne Play Store kein Preis: dann steht nur die Meldung darunter, nicht „wird geladen“
                val status = if (Pro.isPro) "Pro aktiv – danke!" else Pro.price?.let { "Preis: $it (einmalig)" }
                    ?: "Preis wird von Google Play geladen …".takeIf { Pro.message == null }
                if (status != null) Text(
                    status,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (Pro.isPro) colors.primary else colors.onSurface, modifier = Modifier.padding(top = 4.dp),
                )
                Pro.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
            }
        },
        confirmButton = {
            if (!Pro.isPro) TextButton(onClick = { activity?.let(Pro::buy) }) { Text("Kaufen") }
            else TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = Pro::restore) { Text("Kauf wiederherstellen") }
                if (!Pro.isPro) TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        },
    )
}

@Composable
private fun Heading(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

@Composable
private fun Bullets(vararg lines: String) = Text(lines.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodyMedium)
