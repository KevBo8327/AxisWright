package dev.androidcad.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.androidcad.viewport.ClipPlane
import dev.androidcad.viewport.SceneFrame
import java.util.Locale
import kotlin.math.roundToInt

/** Ausrichtung der Schnittebene in der Schnittansicht. */
enum class SectionPlaneType {
    XY, XZ, YZ, FACE
}

/** Zustand der Schnittansicht (Darstellungsebene, Versatz, Umkehrung). */
class SectionViewState {
    var enabled by mutableStateOf(false)
    var planeType by mutableStateOf(SectionPlaneType.XY)
    var offset by mutableStateOf(0.0)
    var flipped by mutableStateOf(false)
    var customPlaneNormal by mutableStateOf<Triple<Double, Double, Double>?>(null)
    var customPlaneOrigin by mutableStateOf<Triple<Double, Double, Double>?>(null)
    var pickingFace by mutableStateOf(false)

    fun toClipPlane(): ClipPlane? {
        if (!enabled) return null
        val (nx, ny, nz) = when (planeType) {
            SectionPlaneType.XY -> Triple(0.0, 0.0, 1.0)
            SectionPlaneType.XZ -> Triple(0.0, 1.0, 0.0)
            SectionPlaneType.YZ -> Triple(1.0, 0.0, 0.0)
            SectionPlaneType.FACE -> customPlaneNormal ?: Triple(0.0, 0.0, 1.0)
        }
        val (ox, oy, oz) = when (planeType) {
            SectionPlaneType.FACE -> customPlaneOrigin ?: Triple(0.0, 0.0, 0.0)
            else -> Triple(0.0, 0.0, 0.0)
        }
        val px = ox + nx * offset
        val py = oy + ny * offset
        val pz = oz + nz * offset
        val sign = if (flipped) -1.0 else 1.0
        val fnx = if (nx == 0.0) 0.0 else nx * sign
        val fny = if (ny == 0.0) 0.0 else ny * sign
        val fnz = if (nz == 0.0) 0.0 else nz * sign
        return ClipPlane.fromPointAndNormal(
            px.toFloat(), py.toFloat(), pz.toFloat(),
            fnx.toFloat(), fny.toFloat(), fnz.toFloat(),
        )
    }
}

/** Schwebende Karte zur Steuerung der Schnittansicht (Schnittebene, Versatz, Umkehrung). */
@Composable
internal fun SectionViewCard(state: WorkspaceState, modifier: Modifier = Modifier) {
    var collapsed by remember { mutableStateOf(false) }
    val section = state.sectionView
    val pos = state.output?.mesh?.positions ?: FloatArray(0)
    val extent = (SceneFrame.of(pos, state.sketchOverlay).radius * 1.2f).coerceAtLeast(50f)
    var textInput by remember(section.offset) { mutableStateOf(String.format(Locale.US, "%.1f", section.offset)) }

    FloatingCard(
        modifier = modifier,
        title = stringResource(R.string.model_section_view),
        icon = CadIcon.PLANE,
        onClose = { state.toggleSectionView() },
        collapsed = collapsed,
        onToggle = { collapsed = !collapsed },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.model_section_plane), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    SectionPlaneType.XY to R.string.model_section_plane_xy,
                    SectionPlaneType.XZ to R.string.model_section_plane_xz,
                    SectionPlaneType.YZ to R.string.model_section_plane_yz,
                    SectionPlaneType.FACE to R.string.model_section_plane_face,
                ).forEach { (type, labelRes) ->
                    val selected = section.planeType == type
                    FilterChip(
                        selected = selected,
                        onClick = {
                            section.planeType = type
                            if (type == SectionPlaneType.FACE && section.customPlaneNormal == null) {
                                state.startSectionPickFace()
                            }
                        },
                        label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (section.planeType == SectionPlaneType.FACE) {
                OutlinedButton(
                    onClick = {
                        if (section.pickingFace) state.cancelSectionPickFace() else state.startSectionPickFace()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(if (section.pickingFace) R.string.model_section_picking_face else R.string.model_section_pick_face),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Text(stringResource(R.string.model_section_offset), style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Slider(
                    value = section.offset.toFloat().coerceIn(-extent, extent),
                    onValueChange = {
                        val rounded = (it * 10f).roundToInt() / 10.0
                        section.offset = rounded
                        textInput = String.format(Locale.US, "%.1f", rounded)
                    },
                    valueRange = -extent..extent,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { str ->
                        textInput = str
                        str.toDoubleOrNull()?.let { section.offset = it }
                    },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = { Text("mm", style = MaterialTheme.typography.labelSmall) },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { section.flipped = !section.flipped },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.model_section_flip) + if (section.flipped) "  (⇄)" else "",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
