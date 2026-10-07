package dev.androidcad.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import dev.androidcad.lang.Boolean3D
import dev.androidcad.lang.BoolOp
import dev.androidcad.lang.Circle
import dev.androidcad.lang.Colored
import dev.androidcad.lang.Cube
import dev.androidcad.lang.Cylinder
import dev.androidcad.lang.Group
import dev.androidcad.lang.Hull
import dev.androidcad.lang.LinearExtrude
import dev.androidcad.lang.Mat4
import dev.androidcad.lang.Minkowski
import dev.androidcad.lang.Node
import dev.androidcad.lang.Offset as OffsetNode
import dev.androidcad.lang.Polygon2D
import dev.androidcad.lang.Polyhedron
import dev.androidcad.lang.RotateExtrude
import dev.androidcad.lang.Sphere
import dev.androidcad.lang.Square
import dev.androidcad.lang.Transform
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Achsenparalleler Quader nach Anwendung der Transformationen (acht Eckpunkte im Weltraum). */
internal class WireBox(val corners: List<Triple<Double, Double, Double>>, val subtractive: Boolean)

/**
 * Sammelt Hüllquader aller Körper des CSG-Baums. Platzhalter für den späteren Mesh-Renderer
 * (Manifold + Filament): zeigt schon jetzt Lage und Größe der Teile, subtraktive Körper gedimmt.
 */
internal fun collectBoxes(node: Node, m: Mat4 = Mat4.IDENTITY, subtractive: Boolean = false, out: MutableList<WireBox> = ArrayList()): List<WireBox> {
    fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double) {
        val c = ArrayList<Triple<Double, Double, Double>>(8)
        for (x in doubleArrayOf(x0, x1)) for (y in doubleArrayOf(y0, y1)) for (z in doubleArrayOf(z0, z1)) c += m.apply(x, y, z)
        out += WireBox(c, subtractive)
    }
    when (node) {
        is Cube -> {
            val (sx, sy, sz) = node.size
            if (node.center) box(-sx / 2, sx / 2, -sy / 2, sy / 2, -sz / 2, sz / 2) else box(0.0, sx, 0.0, sy, 0.0, sz)
        }
        is Sphere -> box(-node.radius, node.radius, -node.radius, node.radius, -node.radius, node.radius)
        is Cylinder -> {
            val r = max(node.r1, node.r2)
            if (node.center) box(-r, r, -r, r, -node.height / 2, node.height / 2) else box(-r, r, -r, r, 0.0, node.height)
        }
        is Polyhedron -> if (node.points.isNotEmpty()) {
            box(node.points.minOf { it.first }, node.points.maxOf { it.first }, node.points.minOf { it.second }, node.points.maxOf { it.second }, node.points.minOf { it.third }, node.points.maxOf { it.third })
        }
        is Transform -> node.children.forEach { collectBoxes(it, m * node.matrix, subtractive, out) }
        is Group -> node.children.forEach { collectBoxes(it, m, subtractive, out) }
        is Hull -> node.children.forEach { collectBoxes(it, m, subtractive, out) }
        is Minkowski -> node.children.forEach { collectBoxes(it, m, subtractive, out) }
        is Colored -> node.children.forEach { collectBoxes(it, m, subtractive, out) }
        is Boolean3D -> node.children.forEachIndexed { i, c ->
            collectBoxes(c, m, subtractive || (node.op == BoolOp.DIFFERENCE && i > 0), out)
        }
        is LinearExtrude -> {
            val b = bounds2d(node.children) ?: return out
            if (node.center) box(b[0], b[1], b[2], b[3], -node.height / 2, node.height / 2) else box(b[0], b[1], b[2], b[3], 0.0, node.height)
        }
        is RotateExtrude -> {
            val b = bounds2d(node.children) ?: return out
            val r = max(abs(b[0]), abs(b[1]))
            box(-r, r, -r, r, b[2], b[3])
        }
        else -> Unit // 2D-Knoten ohne Extrusion haben kein Volumen
    }
    return out
}

/** Rechteck-Hülle (xMin, xMax, yMin, yMax) aller 2D-Knoten unter [nodes]. */
private fun bounds2d(nodes: List<Node>, m: Mat4 = Mat4.IDENTITY): DoubleArray? {
    var res: DoubleArray? = null
    fun add(x: Double, y: Double) {
        val p = m.apply(x, y, 0.0)
        val r = res ?: doubleArrayOf(p.first, p.first, p.second, p.second).also { res = it }
        r[0] = min(r[0], p.first); r[1] = max(r[1], p.first); r[2] = min(r[2], p.second); r[3] = max(r[3], p.second)
    }
    for (n in nodes) when (n) {
        is Square -> {
            val (w, h) = n.size
            val ox = if (n.center) -w / 2 else 0.0; val oy = if (n.center) -h / 2 else 0.0
            add(ox, oy); add(ox + w, oy + h)
        }
        is Circle -> { add(-n.radius, -n.radius); add(n.radius, n.radius) }
        is Polygon2D -> n.points.forEach { add(it.first, it.second) }
        is Transform -> bounds2d(n.children, m * n.matrix)?.let { add(it[0], it[2]); add(it[1], it[3]) }
        is Group -> bounds2d(n.children, m)?.let { add(it[0], it[2]); add(it[1], it[3]) }
        is Hull -> bounds2d(n.children, m)?.let { add(it[0], it[2]); add(it[1], it[3]) }
        is Boolean3D -> bounds2d(n.children, m)?.let { add(it[0], it[2]); add(it[1], it[3]) }
        is OffsetNode -> bounds2d(n.children, m)?.let { add(it[0] - n.amount, it[2] - n.amount); add(it[1] + n.amount, it[3] + n.amount) }
        is Colored -> bounds2d(n.children, m)?.let { add(it[0], it[2]); add(it[1], it[3]) }
        else -> Unit
    }
    return res
}

private val EDGES = listOf(
    0 to 1, 2 to 3, 4 to 5, 6 to 7, // entlang z
    0 to 2, 1 to 3, 4 to 6, 5 to 7, // entlang y
    0 to 4, 1 to 5, 2 to 6, 3 to 7, // entlang x
)

/**
 * Drahtgitter-Ansicht mit Orbit (Ziehen), Zoom (Pinch / Mausrad). Z zeigt nach oben.
 */
@Composable
internal fun WireViewport(boxes: List<WireBox>, modifier: Modifier = Modifier, solid: Color, dim: Color, axis: Color) {
    var yaw by remember { mutableFloatStateOf(-0.6f) }
    var pitch by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    val pts = remember(boxes) { boxes.flatMap { it.corners } }
    val center = remember(pts) {
        if (pts.isEmpty()) Triple(0.0, 0.0, 0.0) else Triple(
            (pts.minOf { it.first } + pts.maxOf { it.first }) / 2,
            (pts.minOf { it.second } + pts.maxOf { it.second }) / 2,
            (pts.minOf { it.third } + pts.maxOf { it.third }) / 2,
        )
    }
    val radius = remember(pts, center) {
        pts.maxOfOrNull { Math.sqrt((it.first - center.first).let { d -> d * d } + (it.second - center.second).let { d -> d * d } + (it.third - center.third).let { d -> d * d }) }
            ?.coerceAtLeast(1e-3) ?: 1.0
    }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    yaw += pan.x * 0.01f
                    pitch = (pitch + pan.y * 0.01f).coerceIn(-1.5f, 1.5f)
                    zoom = (zoom * gestureZoom).coerceIn(0.1f, 20f)
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.first().scrollDelta.y
                            zoom = (zoom * exp(-dy * 0.1f)).coerceIn(0.1f, 20f)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            },
    ) {
        val scale = (min(size.width, size.height) / 2f) * 0.8f / radius.toFloat() * zoom
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val origin = Offset(size.width / 2, size.height / 2)

        fun project(p: Triple<Double, Double, Double>): Offset {
            val x = (p.first - center.first).toFloat(); val y = (p.second - center.second).toFloat(); val z = (p.third - center.third).toFloat()
            // Drehung um Z (yaw), dann Kippen um X (pitch); Bildschirm-y zeigt nach unten.
            val x1 = x * cy - y * sy
            val y1 = x * sy + y * cy
            val sx = x1
            val sY = z * cp - y1 * sp
            return Offset(origin.x + sx * scale, origin.y - sY * scale)
        }

        // Koordinatenachsen am Schwerpunkt
        val len = radius * 0.6
        val o = center
        drawLine(Color.Red.copy(alpha = 0.7f), project(o), project(Triple(o.first + len, o.second, o.third)), 2f, StrokeCap.Round)
        drawLine(Color(0xFF2E7D32).copy(alpha = 0.7f), project(o), project(Triple(o.first, o.second + len, o.third)), 2f, StrokeCap.Round)
        drawLine(Color.Blue.copy(alpha = 0.7f), project(o), project(Triple(o.first, o.second, o.third + len)), 2f, StrokeCap.Round)

        for (b in boxes) {
            val pr = b.corners.map(::project)
            val c = if (b.subtractive) dim else solid
            for ((i, j) in EDGES) drawLine(c, pr[i], pr[j], if (b.subtractive) 1.5f else 2.5f, StrokeCap.Round)
        }
        if (boxes.isEmpty()) drawCircle(axis, 4f, origin)
    }
}
