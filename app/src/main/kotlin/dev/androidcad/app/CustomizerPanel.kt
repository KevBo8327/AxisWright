package dev.androidcad.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.androidcad.lang.ParamKind
import dev.androidcad.lang.ParamSpec
import kotlin.math.roundToInt

/** Aus den Customizer-Annotationen automatisch erzeugte Bedienoberfläche (Slider, Auswahl, Schalter …). */
@Composable
fun CustomizerPanel(state: CadState, modifier: Modifier = Modifier) {
    val params = state.params
    if (params.isEmpty()) {
        Text(
            stringResource(R.string.script_params_empty),
            modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val grouped = params.groupBy { it.group }
    LazyColumn(modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.common_parameters), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = state::resetParams, enabled = state.overrides.isNotEmpty()) { Text(stringResource(R.string.common_reset)) }
            }
        }
        for ((group, list) in grouped) {
            if (group.isNotEmpty()) item(key = "g_$group") { Text(group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(list, key = { it.name }) { spec -> ParamRow(spec, state.overrides[spec.name] ?: spec.defaultLiteral) { state.setParam(spec.name, it) } }
        }
    }
}

@Composable
private fun ParamRow(spec: ParamSpec, currentLiteral: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        val title = spec.description.ifBlank { spec.name }
        when (val k = spec.kind) {
            is ParamKind.Slider -> {
                val value = currentLiteral.toDoubleOrNull() ?: k.min
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(fmt(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                val steps = ((k.max - k.min) / k.step).roundToInt() - 1
                Slider(
                    value = value.toFloat().coerceIn(k.min.toFloat(), k.max.toFloat()),
                    onValueChange = { raw ->
                        val snapped = (k.min + ((raw - k.min) / k.step).roundToInt() * k.step).coerceIn(k.min, k.max)
                        onChange(fmt(snapped))
                    },
                    valueRange = k.min.toFloat()..k.max.toFloat(),
                    steps = if (steps in 1..500) steps else 0,
                )
            }
            is ParamKind.Toggle -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = currentLiteral.trim() == "true", onCheckedChange = { onChange(it.toString()) })
            }
            is ParamKind.Choices -> {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                var open by remember { mutableStateOf(false) }
                val current = k.options.firstOrNull { it.literal == currentLiteral.trim() }
                Column {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current?.label ?: currentLiteral) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (o in k.options) DropdownMenuItem(text = { Text(o.label) }, onClick = { onChange(o.literal); open = false })
                    }
                }
            }
            is ParamKind.NumberField, is ParamKind.TextField, is ParamKind.VectorField -> {
                OutlinedTextField(
                    value = currentLiteral,
                    onValueChange = onChange,
                    label = { Text(title) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun fmt(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e12) v.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
