package dev.androidcad.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerCtrl
import androidx.compose.ui.input.pointer.isShiftPressed as pointerShift
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.Vec2
import dev.androidcad.sketchedit.PointerButton
import dev.androidcad.sketchedit.PointerKind
import dev.androidcad.sketchedit.Preview
import dev.androidcad.sketchedit.Sel
import dev.androidcad.sketchedit.SketchEditor
import dev.androidcad.sketchedit.SnapKind
import dev.androidcad.sketchedit.Tool
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot

private class SketchColors(
    val grid: Color, val gridMajor: Color, val axisX: Color, val axisY: Color,
    val under: Color, val fully: Color, val construction: Color, val selected: Color, val hover: Color,
    val preview: Color, val conflict: Color, val text: Color, val labelBg: Color, val labelBorder: Color, val badgeBg: Color,
)

/**
 * Zeichenfläche der Skizze: zeigt Raster, Geometrie (blau = noch beweglich, dunkel = vollständig bestimmt, rot = Widerspruch),
 * Constraint-Symbole und Maße und nimmt Eingaben per Maus, Stift und Touch entgegen:
 *  - Primärtaste / Stift / ein Finger: aktives Werkzeug; Radierer-Ende des Stifts löscht
 *  - Mausrad, Zwei-Finger-Geste: Zoom; mittlere Maustaste, Zwei-Finger-Geste: Verschieben
 *  - rechte Maustaste: laufende Aktion abbrechen
 *  - Tastatur: Esc, Entf, Strg+Z/Y, L R C A S (Werkzeuge)
 */
@Composable
fun SketchCanvas(handle: EditorHandle, modifier: Modifier = Modifier, multiSelect: Boolean = false, reference: FloatArray? = null) {
    val editor = handle.editor
    val scheme = MaterialTheme.colorScheme
    val colors = remember(scheme) {
        SketchColors(
            grid = scheme.onSurface.copy(alpha = 0.07f), gridMajor = scheme.onSurface.copy(alpha = 0.16f),
            axisX = Color(0xFFD32F2F).copy(alpha = 0.7f), axisY = Color(0xFF388E3C).copy(alpha = 0.7f),
            under = Color(0xFF1E6FE0), fully = scheme.onSurface, construction = scheme.outline,
            selected = Color(0xFFFF8F00), hover = Color(0xFFFFB74D), preview = scheme.primary,
            conflict = Color(0xFFD32F2F), text = scheme.onSurface, labelBg = scheme.surface, labelBorder = scheme.outline,
            badgeBg = scheme.secondaryContainer,
        )
    }
    val measurer = rememberTextMeasurer()
    val focus = remember { FocusRequester() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(size, editor) {
        val oldW = handle.width; val oldH = handle.height
        handle.width = size.width.toDouble(); handle.height = size.height.toDouble()
        if (size.width <= 0) return@LaunchedEffect
        when {
            !handle.fitted -> { handle.fitted = true; handle.fit(referencePoints(reference)) }
            // gedreht (hoch ↔ quer): neu einpassen, sonst ragt die Skizze aus dem Bild
            oldW > 0 && (oldW > oldH) != (size.width > size.height) -> handle.fit(referencePoints(reference))
            // sonst (z. B. Tastatur): die Bildmitte bleibt, wo sie war
            oldW > 0 -> editor.pan((size.width - oldW) / 2, (size.height - oldH) / 2)
        }
    }

    Box(
        modifier
            .clipToBounds()
            .background(scheme.surface)
            .onSizeChanged { size = it }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { handleKey(editor, it) }
            .pointerInput(editor, multiSelect) {
                awaitPointerEventScope {
                    var primary: PointerId? = null
                    var panning = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val changes = ev.changes

                        // Mausrad: Zoom um den Zeiger
                        if (ev.type == PointerEventType.Scroll) {
                            val c = changes.first()
                            editor.zoomAt(Vec2(c.position.x.toDouble(), c.position.y.toDouble()), exp(-c.scrollDelta.y * 0.12))
                            changes.forEach { it.consume() }
                            continue
                        }
                        // Zwei Finger: Zoom und Verschieben, laufende Einzelgeste abbrechen
                        if (changes.count { it.pressed && it.type == PointerType.Touch } >= 2) {
                            if (primary != null) { editor.pointerCancel(); primary = null; panning = false }
                            val zoom = ev.calculateZoom(); val pan = ev.calculatePan(); val centroid = ev.calculateCentroid()
                            if (zoom != 1f && centroid.isSpecified()) editor.zoomAt(Vec2(centroid.x.toDouble(), centroid.y.toDouble()), zoom.toDouble())
                            if (pan != Offset.Zero) editor.pan(pan.x.toDouble(), pan.y.toDouble())
                            changes.forEach { it.consume() }
                            continue
                        }
                        for (c in changes) {
                            val pos = Vec2(c.position.x.toDouble(), c.position.y.toDouble())
                            when {
                                c.changedToDown() && primary == null -> {
                                    focus.requestFocus()
                                    val button = when {
                                        c.type == PointerType.Eraser -> PointerButton.ERASER
                                        ev.buttons.isTertiaryPressed -> PointerButton.MIDDLE
                                        ev.buttons.isSecondaryPressed && c.type == PointerType.Mouse -> PointerButton.SECONDARY
                                        else -> PointerButton.PRIMARY
                                    }
                                    when (button) {
                                        PointerButton.MIDDLE -> { primary = c.id; panning = true }
                                        PointerButton.SECONDARY -> editor.pointerDown(pos, PointerKind.MOUSE, button)
                                        else -> {
                                            primary = c.id
                                            editor.pointerDown(pos, kindOf(c.type), button, additive = multiSelect || ev.keyboardModifiers.pointerShift || ev.keyboardModifiers.pointerCtrl)
                                        }
                                    }
                                    c.consume()
                                }
                                c.id == primary && c.changedToUp() -> {
                                    if (panning) panning = false else editor.pointerUp(pos)
                                    primary = null
                                    c.consume()
                                }
                                c.id == primary && c.pressed -> {
                                    if (panning) c.positionChange().let { editor.pan(it.x.toDouble(), it.y.toDouble()) } else editor.pointerMove(pos)
                                    c.consume()
                                }
                                !c.pressed && primary == null && (c.type == PointerType.Mouse || c.type == PointerType.Stylus) -> editor.hover(pos)
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            handle.version // Beobachtung: bei jeder Editor-Änderung neu zeichnen
            drawSketch(editor, measurer, colors, reference)
        }
    }
}

/** Endpunkte der Referenzlinien (x1 y1 x2 y2 …) als Punkte – damit „Einpassen“ den Umriss der Fläche mit einschließt. */
internal fun referencePoints(reference: FloatArray?): List<Vec2> {
    if (reference == null) return emptyList()
    return (0 until reference.size / 2).map { Vec2(reference[2 * it].toDouble(), reference[2 * it + 1].toDouble()) }
}

private fun Offset.isSpecified() = !x.isNaN() && !y.isNaN()

private fun kindOf(t: PointerType): PointerKind = when (t) {
    PointerType.Touch -> PointerKind.TOUCH
    PointerType.Stylus, PointerType.Eraser -> PointerKind.STYLUS
    else -> PointerKind.MOUSE
}

private fun handleKey(editor: SketchEditor, e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    return when {
        e.isCtrlPressed && e.key == Key.Z && e.isShiftPressed -> { editor.redo(); true }
        e.isCtrlPressed && e.key == Key.Z -> { editor.undo(); true }
        e.isCtrlPressed && e.key == Key.Y -> { editor.redo(); true }
        e.isCtrlPressed && e.key == Key.A -> { editor.selectAll(); true }
        e.isCtrlPressed -> false
        e.key == Key.Escape -> { editor.cancelOperation(); editor.clearSelection(); true }
        e.key == Key.Enter -> { editor.cancelOperation(); true }
        e.key == Key.Delete || e.key == Key.Backspace -> { editor.deleteSelection(); true }
        e.key == Key.S -> { editor.setTool(Tool.SELECT); true }
        e.key == Key.L -> { editor.setTool(Tool.LINE); true }
        e.key == Key.R -> { editor.setTool(Tool.RECTANGLE); true }
        e.key == Key.C -> { editor.setTool(Tool.CIRCLE); true }
        e.key == Key.A -> { editor.setTool(Tool.ARC); true }
        else -> false
    }
}

// ---- Zeichnen ---------------------------------------------------------------------------------------

private fun DrawScope.drawSketch(editor: SketchEditor, measurer: TextMeasurer, c: SketchColors, reference: FloatArray?) {
    val view = editor.view
    drawGrid(editor, c)
    if (reference != null) drawReference(editor, reference, c.construction)
    drawGeometry(editor, c)

    // Auswahl und Hervorhebung
    for (s in editor.selection) drawSel(editor, s, c.selected, 4.5f)
    editor.hoverHit?.let { if (it !in editor.selection) drawSel(editor, it, c.hover, 3.5f) }

    // Vorschau
    editor.preview?.let { drawPreview(editor, it, c) }

    // Constraint-Symbole und Maße
    for (b in editor.badges()) {
        val center = Offset(b.center.x.toFloat(), b.center.y.toFloat())
        drawCircle(c.badgeBg, 9f, center)
        drawCentered(measurer, b.symbol, center, TextStyle(color = c.text, fontSize = 11.sp))
    }
    for (l in editor.dimensionLabels()) {
        val anchor = Offset(l.anchor.x.toFloat(), l.anchor.y.toFloat()); val center = Offset(l.center.x.toFloat(), l.center.y.toFloat())
        drawLine(c.labelBorder.copy(alpha = 0.6f), anchor, center, 1f)
        val selected = Sel.K(l.id) in editor.selection
        val half = Offset(l.halfWidth.toFloat(), l.halfHeight.toFloat())
        drawRoundRect(c.labelBg, center - half, Size(half.x * 2, half.y * 2), CornerRadius(6f))
        drawRoundRect(if (selected) c.selected else c.under, center - half, Size(half.x * 2, half.y * 2), CornerRadius(6f), style = Stroke(1.5f))
        drawCentered(measurer, l.text, center, TextStyle(color = c.under, fontSize = 13.sp))
    }

    // Fangmarke und Koordinaten
    editor.hover?.let { snap ->
        val p = view.toScreen(snap.position)
        val o = Offset(p.x.toFloat(), p.y.toFloat())
        val (label, snapColor) = when (snap.kind) {
            SnapKind.POINT -> "Punkt" to c.selected
            SnapKind.MIDPOINT -> "Mitte" to c.selected
            SnapKind.ON_LINE -> "Auf Linie" to c.selected
            SnapKind.ON_ROUND -> "Auf Kurve" to c.selected
            SnapKind.AXIS_X, SnapKind.AXIS_Y -> "Achse" to c.selected
            SnapKind.GRID -> "" to c.preview
            SnapKind.FREE -> "" to c.preview
        }
        drawCircle(snapColor, 8f, o, style = Stroke(2f))
        drawCircle(snapColor, 2.5f, o)
        if (label.isNotEmpty()) drawText(measurer, label, Offset(o.x + 12, o.y + 8), TextStyle(color = snapColor, fontSize = 11.sp))
        drawText(
            measurer, "x %.2f   y %.2f".format(java.util.Locale.ROOT, snap.position.x, snap.position.y), Offset(10f, size.height - 22f),
            TextStyle(color = c.text.copy(alpha = 0.6f), fontSize = 11.sp),
        )
    }
    // Fensterauswahl
    (editor.preview as? Preview.Box)?.let {
        val a = view.toScreen(it.a); val b = view.toScreen(it.b)
        val tl = Offset(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat())
        val sz = Size(kotlin.math.abs(a.x - b.x).toFloat(), kotlin.math.abs(a.y - b.y).toFloat())
        drawRect(c.selected.copy(alpha = 0.12f), tl, sz)
        drawRect(c.selected, tl, sz, style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
    }
}

private fun DrawScope.drawCentered(measurer: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val layout: TextLayoutResult = measurer.measure(text, style)
    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - layout.size.height / 2f))
}

private fun DrawScope.drawGrid(editor: SketchEditor, c: SketchColors) {
    val view = editor.view
    val step = view.gridStep()
    val tl = view.toWorld(Vec2(0.0, 0.0)); val br = view.toWorld(Vec2(size.width.toDouble(), size.height.toDouble()))
    var i = floor(tl.x / step).toLong()
    while (i * step <= br.x) {
        val sx = view.toScreen(Vec2(i * step, 0.0)).x.toFloat()
        drawLine(if (i % 5L == 0L) c.gridMajor else c.grid, Offset(sx, 0f), Offset(sx, size.height), 1f)
        i++
    }
    var j = floor(br.y / step).toLong()
    while (j * step <= tl.y) {
        val sy = view.toScreen(Vec2(0.0, j * step)).y.toFloat()
        drawLine(if (j % 5L == 0L) c.gridMajor else c.grid, Offset(0f, sy), Offset(size.width, sy), 1f)
        j++
    }
    val o = view.toScreen(Vec2(0.0, 0.0))
    drawLine(c.axisX, Offset(0f, o.y.toFloat()), Offset(size.width, o.y.toFloat()), 1.5f)
    drawLine(c.axisY, Offset(o.x.toFloat(), 0f), Offset(o.x.toFloat(), size.height), 1.5f)
}

/** Umriss des Körpers in der Ebene der Skizze (nur zur Orientierung, nicht bearbeitbar). */
private fun DrawScope.drawReference(editor: SketchEditor, reference: FloatArray, color: Color) {
    var i = 0
    while (i + 3 < reference.size) {
        drawLine(
            color.copy(alpha = 0.9f),
            off(editor, Vec2(reference[i].toDouble(), reference[i + 1].toDouble())),
            off(editor, Vec2(reference[i + 2].toDouble(), reference[i + 3].toDouble())),
            3f,
        )
        i += 4
    }
}

private fun DrawScope.drawGeometry(editor: SketchEditor, c: SketchColors) {
    val sk = editor.sketch
    val st = editor.status
    val conflict = st != null && !st.isSolved
    fun color(constrained: Boolean, construction: Boolean) = when {
        conflict -> c.conflict
        construction -> c.construction
        constrained -> c.fully
        else -> c.under
    }
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    // Achslinien (wählbare X-/Y-Achse) sind schon als Achsen gezeichnet
    val axisLines = sk.lineIds.filter { SketchOps.axisOf(sk, it) != null }.toSet()
    val axisPoints = axisLines.map { sk.line(it).p2 }.toSet()
    for (id in sk.lineIds) {
        if (id in axisLines) continue
        val l = sk.line(id)
        val a = off(editor, sk.point(l.p1)); val b = off(editor, sk.point(l.p2))
        drawLine(color(st?.constrainedLines?.contains(id) == true, l.construction), a, b, 2.5f, pathEffect = if (l.construction) dash else null)
    }
    for (id in sk.circleIds) {
        val ci = sk.circle(id)
        drawCircle(
            color(st?.constrainedCircles?.contains(id) == true, ci.construction), (ci.radius * editor.view.scale).toFloat(),
            off(editor, sk.point(ci.center)), style = Stroke(2.5f, pathEffect = if (ci.construction) dash else null),
        )
    }
    for (id in sk.arcIds) {
        val a = sk.arc(id)
        drawArcOf(editor, sk.point(a.center), sk.point(a.start), sk.point(a.end), color(st?.constrainedArcs?.contains(id) == true, a.construction), 2.5f, if (a.construction) dash else null)
    }
    for (id in sk.pointIds) {
        val p = off(editor, sk.point(id))
        if (id in axisPoints) continue
        if (id == sk.origin) {
            // Ursprung als deutlicher Bezugspunkt: Ring mit Kreuz
            drawCircle(c.text, 7f, p, style = Stroke(2f))
            drawCircle(c.text, 2.5f, p)
            drawLine(c.text, Offset(p.x - 11, p.y), Offset(p.x + 11, p.y), 1.5f); drawLine(c.text, Offset(p.x, p.y - 11), Offset(p.x, p.y + 11), 1.5f)
        } else {
            val fully = st?.constrainedPoints?.contains(id) == true
            drawCircle(if (conflict) c.conflict else if (fully) c.fully else c.under, 3.5f, p)
        }
    }
}

private fun off(editor: SketchEditor, p: Vec2): Offset = editor.view.toScreen(p).let { Offset(it.x.toFloat(), it.y.toFloat()) }

private fun DrawScope.drawArcOf(editor: SketchEditor, center: Vec2, start: Vec2, end: Vec2, color: Color, width: Float, effect: PathEffect?) {
    val c = off(editor, center)
    val r = (hypot(start.x - center.x, start.y - center.y) * editor.view.scale).toFloat()
    val a0 = atan2(start.y - center.y, start.x - center.x)
    var sweep = atan2(end.y - center.y, end.x - center.x) - a0
    while (sweep <= 0) sweep += 2 * PI
    // Skizzenwinkel laufen gegen den Uhrzeigersinn, Bildschirmwinkel (y nach unten) im Uhrzeigersinn → Vorzeichen drehen
    drawArc(color, (-Math.toDegrees(a0)).toFloat(), (-Math.toDegrees(sweep)).toFloat(), false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(width, pathEffect = effect))
}

private fun DrawScope.drawSel(editor: SketchEditor, s: Sel, color: Color, width: Float) {
    val sk = editor.sketch
    when (s) {
        is Sel.P -> if (sk.exists(s.id)) drawCircle(color, 8f, off(editor, sk.point(s.id)), style = Stroke(width))
        is Sel.L -> if (sk.exists(s.id)) {
            val axis = SketchOps.axisOf(sk, s.id)
            if (axis != null) {
                // gewählte Achse über die ganze Breite bzw. Höhe hervorheben
                val o = off(editor, Vec2(0.0, 0.0))
                if (axis) drawLine(color.copy(alpha = 0.7f), Offset(0f, o.y), Offset(size.width, o.y), width)
                else drawLine(color.copy(alpha = 0.7f), Offset(o.x, 0f), Offset(o.x, size.height), width)
            } else sk.line(s.id).let { drawLine(color.copy(alpha = 0.85f), off(editor, sk.point(it.p1)), off(editor, sk.point(it.p2)), width) }
        }
        is Sel.C -> if (sk.exists(s.id)) sk.circle(s.id).let { drawCircle(color.copy(alpha = 0.85f), (it.radius * editor.view.scale).toFloat(), off(editor, sk.point(it.center)), style = Stroke(width)) }
        is Sel.A -> if (sk.exists(s.id)) sk.arc(s.id).let { drawArcOf(editor, sk.point(it.center), sk.point(it.start), sk.point(it.end), color.copy(alpha = 0.85f), width, null) }
        is Sel.K -> Unit
    }
}

private fun DrawScope.drawPreview(editor: SketchEditor, p: Preview, c: SketchColors) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
    val v = editor.view
    fun o(p: Vec2) = off(editor, p)
    when (p) {
        is Preview.Line -> drawLine(c.preview, o(p.a), o(p.b), 2.5f, pathEffect = dash)
        is Preview.Rect -> {
            val a = v.toScreen(p.a); val b = v.toScreen(p.b)
            drawRect(c.preview, Offset(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat()), Size(kotlin.math.abs(a.x - b.x).toFloat(), kotlin.math.abs(a.y - b.y).toFloat()), style = Stroke(2.5f, pathEffect = dash))
        }
        is Preview.Circle -> drawCircle(c.preview, (p.radius * v.scale).toFloat(), o(p.center), style = Stroke(2.5f, pathEffect = dash))
        is Preview.Arc -> {
            drawArcOf(editor, p.center, p.start, p.end, c.preview, 2.5f, dash)
            drawLine(c.preview.copy(alpha = 0.4f), o(p.center), o(p.start), 1f)
            drawLine(c.preview.copy(alpha = 0.4f), o(p.center), o(p.end), 1f)
        }
        is Preview.Box -> Unit
    }
}
