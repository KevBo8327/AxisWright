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
import androidx.compose.ui.graphics.Path
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.androidcad.sketch.SketchOps
import dev.androidcad.sketch.TextData
import dev.androidcad.sketch.TextOutlines
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
    val dimension: Color,
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
    val cad = CadTheme.colors
    // Zustandsfarben des Farbkonzepts: weiß/dunkel = vollständig bestimmt, türkis/blau = beweglich, rot = Widerspruch, orange = Auswahl
    val colors = remember(scheme, cad) {
        SketchColors(
            grid = cad.grid, gridMajor = cad.gridMajor,
            axisX = cad.axisX.copy(alpha = 0.75f), axisY = cad.axisY.copy(alpha = 0.75f),
            under = cad.underDefined, fully = cad.fullyDefined, construction = cad.construction,
            selected = cad.selection, hover = cad.selection.copy(alpha = 0.6f), preview = cad.underDefined,
            conflict = cad.conflict, text = scheme.onSurface, labelBg = cad.sketchBackground.copy(alpha = 0.88f), labelBorder = cad.dimension.copy(alpha = 0.55f),
            badgeBg = scheme.surfaceContainerHigh, dimension = cad.dimension,
        )
    }
    val measurer = rememberTextMeasurer()
    val res = androidx.compose.ui.platform.LocalResources.current
    val focus = remember { FocusRequester() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    LaunchedEffect(size, editor) {
        // Größenwechsel kommen oft in mehreren Schritten (Drehen, Faltgerät): erst die endgültige Größe auswerten
        if (handle.fitted) kotlinx.coroutines.delay(200)
        val oldW = handle.width; val oldH = handle.height
        handle.width = size.width.toDouble(); handle.height = size.height.toDouble()
        if (size.width <= 0) return@LaunchedEffect
        when {
            !handle.fitted -> { handle.fitted = true; handle.fit(referencePoints(reference)) }
            // andere Breite (gedreht, geteilter Bildschirm, Fenster, Faltgerät): neu einpassen, sonst ragt die Skizze aus dem Bild
            oldW > 0 && oldW != size.width.toDouble() -> handle.fit(referencePoints(reference))
            // nur die Höhe (z. B. Tastatur): die Bildmitte bleibt, wo sie war
            oldW > 0 -> editor.pan((size.width - oldW) / 2, (size.height - oldH) / 2)
        }
    }

    Box(
        modifier
            .clipToBounds()
            .background(cad.sketchBackground)
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
            drawSketch(editor, measurer, colors, reference, res)
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

private fun DrawScope.drawSketch(editor: SketchEditor, measurer: TextMeasurer, c: SketchColors, reference: FloatArray?, res: android.content.res.Resources) {
    val view = editor.view
    drawGrid(editor, c)
    if (reference != null) drawReference(editor, reference, c.construction)
    drawGeometry(editor, c)

    // Auswahl und Hervorhebung
    for (s in editor.selection) drawSel(editor, s, c.selected, 3.6.dp.toPx())
    editor.hoverHit?.let { if (it !in editor.selection) drawSel(editor, it, c.hover, 3.dp.toPx()) }

    // Vorschau
    editor.preview?.let { drawPreview(editor, it, c) }
    // Maß der Vorschau schräg über dem Finger (er verdeckt sonst genau diese Stelle)
    val label = editor.previewLabel()
    val at = editor.hover?.position
    if (label != null && at != null) {
        val layout = measurer.measure(label, TextStyle(color = c.preview, fontSize = 13.sp))
        val o = off(editor, at)
        val topLeft = Offset(o.x + 20.dp.toPx(), o.y - 44.dp.toPx())
        val pad = 4.dp.toPx()
        drawRoundRect(
            c.labelBg, Offset(topLeft.x - pad, topLeft.y - pad),
            Size(layout.size.width + 2 * pad, layout.size.height + 2 * pad), androidx.compose.ui.geometry.CornerRadius(pad),
        )
        drawText(layout, topLeft = topLeft)
    }

    // Constraint-Symbole und Maße
    for (b in editor.badges()) {
        val center = Offset(b.center.x.toFloat(), b.center.y.toFloat())
        drawCircle(c.badgeBg, 9f, center)
        drawCentered(measurer, b.symbol, center, TextStyle(color = c.text, fontSize = 11.sp))
    }
    for (l in editor.dimensionLabels()) {
        val center = Offset(l.center.x.toFloat(), l.center.y.toFloat())
        val selected = Sel.K(l.id) in editor.selection
        val ink = if (selected) c.selected else c.dimension
        // Maßhilfslinien, Maßlinie und Pfeile; die Maßzahl unterbricht die Maßlinie (Hintergrund deckt sie ab)
        for ((a, b) in l.lines) drawLine(ink, Offset(a.x.toFloat(), a.y.toFloat()), Offset(b.x.toFloat(), b.y.toFloat()), 1.dp.toPx())
        for ((tip, d) in l.arrows) {
            val len = 10.0 * editor.uiScale; val w = 3.0 * editor.uiScale
            val base = Offset((tip.x - d.x * len).toFloat(), (tip.y - d.y * len).toFloat())
            val side = Offset((-d.y * w).toFloat(), (d.x * w).toFloat())
            drawPath(Path().apply {
                moveTo(tip.x.toFloat(), tip.y.toFloat()); lineTo(base.x + side.x, base.y + side.y); lineTo(base.x - side.x, base.y - side.y); close()
            }, ink)
        }
        val half = Offset(l.halfWidth.toFloat(), l.halfHeight.toFloat())
        drawRoundRect(c.labelBg, center - half, Size(half.x * 2, half.y * 2), CornerRadius(6f))
        if (selected) drawRoundRect(c.selected, center - half, Size(half.x * 2, half.y * 2), CornerRadius(6f), style = Stroke(1.5f))
        drawCentered(measurer, l.text, center, TextStyle(color = c.dimension, fontSize = 13.sp))
    }

    // Fangmarke und Koordinaten
    editor.hover?.let { snap ->
        val p = view.toScreen(snap.position)
        val o = Offset(p.x.toFloat(), p.y.toFloat())
        val (label, snapColor) = when (snap.kind) {
            SnapKind.POINT -> res.getString(R.string.ws_snap_point) to c.selected
            SnapKind.MIDPOINT -> res.getString(R.string.ws_snap_midpoint) to c.selected
            SnapKind.ON_LINE -> res.getString(R.string.ws_snap_on_line) to c.selected
            SnapKind.ON_ROUND -> res.getString(R.string.ws_snap_on_curve) to c.selected
            SnapKind.AXIS_X, SnapKind.AXIS_Y -> res.getString(R.string.ws_snap_axis) to c.selected
            SnapKind.GRID -> "" to c.preview
            SnapKind.FREE -> "" to c.preview
        }
        drawCircle(snapColor, 8f, o, style = Stroke(2f))
        drawCircle(snapColor, 2.5f, o)
        if (label.isNotEmpty()) drawText(measurer, label, Offset(o.x + 12, o.y + 8), TextStyle(color = snapColor, fontSize = 11.sp))
        // oben rechts: unten links liegen Hinweise und Werkzeugfelder darüber
        val coords = measurer.measure("x %.2f   y %.2f".format(java.util.Locale.ROOT, snap.position.x, snap.position.y), TextStyle(color = c.text.copy(alpha = 0.6f), fontSize = 11.sp))
        drawText(coords, topLeft = Offset(size.width - coords.size.width - 12f, 12f))
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
    // Breiten in dp (nicht px): gleich kräftig auf jedem Bildschirm; Hilfslinien schmaler als Geometrie
    val w = 2.4.dp.toPx(); val wc = 1.6.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    // Achslinien (wählbare X-/Y-Achse) sind schon als Achsen gezeichnet
    val axisLines = sk.lineIds.filter { SketchOps.axisOf(sk, it) != null }.toSet()
    val axisPoints = axisLines.map { sk.line(it).p2 }.toSet()
    for (id in sk.lineIds) {
        if (id in axisLines) continue
        val l = sk.line(id)
        val a = off(editor, sk.point(l.p1)); val b = off(editor, sk.point(l.p2))
        drawLine(color(st?.constrainedLines?.contains(id) == true, l.construction), a, b, if (l.construction) wc else w, pathEffect = if (l.construction) dash else null)
    }
    for (id in sk.circleIds) {
        val ci = sk.circle(id)
        drawCircle(
            color(st?.constrainedCircles?.contains(id) == true, ci.construction), (ci.radius * editor.view.scale).toFloat(),
            off(editor, sk.point(ci.center)), style = Stroke(if (ci.construction) wc else w, pathEffect = if (ci.construction) dash else null),
        )
    }
    for (id in sk.arcIds) {
        val a = sk.arc(id)
        drawArcOf(editor, sk.point(a.center), sk.point(a.start), sk.point(a.end), color(st?.constrainedArcs?.contains(id) == true, a.construction), if (a.construction) wc else w, if (a.construction) dash else null)
    }
    for (id in sk.textIds) {
        val t = sk.text(id)
        drawTextContours(editor, t, color(st?.constrainedPoints?.contains(t.location) == true, t.construction), if (t.construction) wc else w, if (t.construction) dash else null)
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
            val pc = if (conflict) c.conflict else if (fully) c.fully else c.under
            drawCircle(pc, 3.dp.toPx(), p)
            if (sk.isStandalone(id)) {
                // Einzelpunkt (Werkzeug „Punkt“): zusätzlich ein Kreuz, damit er als Bezug auffällt
                val k = 7.dp.toPx(); val sw = 1.5.dp.toPx()
                drawLine(pc, Offset(p.x - k, p.y), Offset(p.x + k, p.y), sw); drawLine(pc, Offset(p.x, p.y - k), Offset(p.x, p.y + k), sw)
            }
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
        is Sel.T -> if (sk.exists(s.id)) drawTextContours(editor, sk.text(s.id), color.copy(alpha = 0.85f), width, null)
        is Sel.K -> Unit
    }
}

private fun DrawScope.drawTextContours(editor: SketchEditor, text: TextData, color: Color, width: Float, effect: PathEffect?) {
    val sk = editor.sketch
    val loops = TextOutlines.default?.outlines(text.text, text.height) ?: return
    val loc = sk.point(text.location)
    val rad = Math.toRadians(text.angle)
    val cosA = kotlin.math.cos(rad)
    val sinA = kotlin.math.sin(rad)
    for (loop in loops) {
        val path = Path()
        var started = false
        for (seg in loop.segments) {
            if (seg is dev.androidcad.sketch.LoopSegment.Line) {
                val rx1 = seg.x1 * cosA - seg.y1 * sinA + loc.x
                val ry1 = seg.x1 * sinA + seg.y1 * cosA + loc.y
                val rx2 = seg.x2 * cosA - seg.y2 * sinA + loc.x
                val ry2 = seg.x2 * sinA + seg.y2 * cosA + loc.y
                val p1 = off(editor, Vec2(rx1, ry1))
                val p2 = off(editor, Vec2(rx2, ry2))
                if (!started) {
                    path.moveTo(p1.x, p1.y)
                    started = true
                }
                path.lineTo(p2.x, p2.y)
            }
        }
        if (started) {
            path.close()
            drawPath(path, color, style = Stroke(width, pathEffect = effect))
        }
    }
}

private fun DrawScope.drawPreview(editor: SketchEditor, p: Preview, c: SketchColors) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    val w = 2.dp.toPx()
    val v = editor.view
    fun o(p: Vec2) = off(editor, p)
    when (p) {
        is Preview.Line -> drawLine(c.preview, o(p.a), o(p.b), w, pathEffect = dash)
        is Preview.Rect -> {
            val a = v.toScreen(p.a); val b = v.toScreen(p.b)
            drawRect(c.preview, Offset(minOf(a.x, b.x).toFloat(), minOf(a.y, b.y).toFloat()), Size(kotlin.math.abs(a.x - b.x).toFloat(), kotlin.math.abs(a.y - b.y).toFloat()), style = Stroke(w, pathEffect = dash))
        }
        is Preview.Circle -> drawCircle(c.preview, (p.radius * v.scale).toFloat(), o(p.center), style = Stroke(w, pathEffect = dash))
        is Preview.Arc -> {
            drawArcOf(editor, p.center, p.start, p.end, c.preview, w, dash)
            drawLine(c.preview.copy(alpha = 0.4f), o(p.center), o(p.start), 1f)
            drawLine(c.preview.copy(alpha = 0.4f), o(p.center), o(p.end), 1f)
        }
        is Preview.Polygon -> {
            val path = androidx.compose.ui.graphics.Path()
            p.points.forEachIndexed { i, q -> val s = o(q); if (i == 0) path.moveTo(s.x, s.y) else path.lineTo(s.x, s.y) }
            path.close()
            drawPath(path, c.preview, style = Stroke(w, pathEffect = dash))
        }
        is Preview.Group -> p.parts.forEach { drawPreview(editor, it, c) }
        is Preview.Box -> Unit
    }
}
