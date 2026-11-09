package dev.androidcad.app

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import dev.androidcad.geometry.Mesh
import dev.androidcad.viewport.ClipPlane
import dev.androidcad.viewport.EdgePicker
import dev.androidcad.viewport.FaceHit
import dev.androidcad.viewport.FacePicker
import dev.androidcad.viewport.OrthoCamera
import dev.androidcad.viewport.SceneFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp

/**
 * OpenGL-ES-3-Renderer für ein Manifold-/B-Rep-[Mesh]: orthografische Kamera, Tiefenpuffer,
 * Flat-Shading (Normale per Bildschirmableitung im Fragment-Shader – keine Normalen-Puffer nötig).
 * Zusätzlich zeichnet er Linien: Kanten, hervorgehobene Kanten und die Skizzen (immer sichtbar, ohne Tiefentest).
 * Das Netz ist optional: Ohne Körper zeigt der Viewer nur die Skizzenlinien.
 *
 * Interim-Lösung ohne Material/PBR; ein vollwertiger Renderer (Filament) kann sie später ersetzen.
 */
internal class MeshRenderer : GLSurfaceView.Renderer {
    // Von der UI-Thread-Seite gesetzt, vom GL-Thread gelesen.
    @Volatile var yaw = ViewCamera.ISO_YAW
    @Volatile var pitch = ViewCamera.ISO_PITCH
    @Volatile var zoom = 1f
    @Volatile var baseColor = floatArrayOf(0.4f, 0.3f, 0.7f)
    @Volatile var background = floatArrayOf(0.95f, 0.95f, 0.97f)
    /** Untere Farbe des Hintergrundverlaufs (oben: [background]). */
    @Volatile var backgroundBottom = floatArrayOf(0.95f, 0.95f, 0.97f)
    @Volatile var lineColor = floatArrayOf(0.1f, 0.1f, 0.15f)
    @Volatile var highlightColor = floatArrayOf(1f, 0.55f, 0f)
    @Volatile var sketchColor = floatArrayOf(0.1f, 0.4f, 0.95f)
    @Volatile var gridColor = floatArrayOf(0.8f, 0.83f, 0.88f)
    @Volatile var gridMajorColor = floatArrayOf(0.7f, 0.74f, 0.8f)
    /** Kappenfarbe für freigelegte Innenflächen beim Schnitt. */
    @Volatile var capColor = floatArrayOf(0.83f, 0.42f, 0.33f)
    /** Aktive Schnittebene im 3D-Raum; `null` = Schnittansicht inaktiv. */
    @Volatile var clipPlane: ClipPlane? = null

    private class Lines {
        @Volatile var pending: FloatArray? = null
        @Volatile var dirty = false
        var vbo = 0
        var vertices = 0
    }

    @Volatile private var pendingMesh: Mesh? = null
    @Volatile private var hasPendingMesh = false
    @Volatile private var frameDirty = true
    private val edges = Lines()
    private val highlight = Lines()
    private val sketch = Lines()
    // Bodengitter: zählt nicht zum Bildausschnitt (sonst zöge es die Einpassung auf)
    private val grid = Lines()
    private val gridMajor = Lines()

    private var program = 0
    private var gradientProgram = 0
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0
    private var meshPositions: FloatArray? = null
    private var frame = SceneFrame(FloatArray(3), 1f)
    private var width = 1
    private var height = 1

    fun setMesh(mesh: Mesh?) {
        if (mesh === pendingMesh) return // gleiches Netz: nicht erneut hochladen
        pendingMesh = mesh
        hasPendingMesh = true
    }

    /** Linienpaare (x1 y1 z1 x2 y2 z2 …) für Kanten; `null` = keine. */
    fun setEdges(segments: FloatArray?) = set(edges, segments)

    /** Linienpaare der hervorgehobenen (gewählten) Kanten; `null` = keine. */
    fun setHighlight(segments: FloatArray?) = set(highlight, segments)

    /** Linienpaare der Skizzen im Raum; `null` = keine. */
    fun setSketchLines(segments: FloatArray?) {
        if (segments !== sketch.pending) frameDirty = true
        set(sketch, segments)
    }

    /** Feine und kräftige Linien des Gitters; `null` = kein Gitter. */
    fun setGrid(g: GridLines?) { set(grid, g?.minor); set(gridMajor, g?.major) }

    private fun set(l: Lines, segments: FloatArray?) {
        if (segments === l.pending) return
        l.pending = segments
        l.dirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        program = link(VERTEX, FRAGMENT)
        gradientProgram = link(GRADIENT_VERTEX, GRADIENT_FRAGMENT)
        val ids = IntArray(7)
        GLES30.glGenBuffers(7, ids, 0)
        vbo = ids[0]; ibo = ids[1]; edges.vbo = ids[2]; highlight.vbo = ids[3]; sketch.vbo = ids[4]; grid.vbo = ids[5]; gridMajor.vbo = ids[6]
        // Kontext neu erzeugt (z. B. nach Pause): alles erneut hochladen
        hasPendingMesh = pendingMesh != null
        for (l in listOf(edges, highlight, sketch, grid, gridMajor)) { l.dirty = l.pending != null; l.vertices = 0 }
        indexCount = 0
        frameDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1); height = h.coerceAtLeast(1)
        GLES30.glViewport(0, 0, w, h)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (hasPendingMesh) {
            hasPendingMesh = false
            upload(pendingMesh)
            frameDirty = true
        }
        for (l in listOf(edges, highlight, sketch, grid, gridMajor)) if (l.dirty) { l.dirty = false; l.vertices = uploadLines(l.vbo, l.pending) }
        if (frameDirty) {
            frameDirty = false
            frame = SceneFrame.of(meshPositions ?: FloatArray(0), sketch.pending)
        }
        val bg = background
        GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawBackground()
        if (indexCount == 0 && sketch.vertices == 0) return

        // Dieselbe Kamera wie beim Antippen (EdgePicker): Treffer im Bild stimmen exakt mit dem Gezeichneten überein.
        val camera = OrthoCamera(yaw, pitch, zoom, frame, width, height)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMvp"), 1, false, camera.mvp, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uView"), 1, false, camera.view, 0)
        val c = baseColor
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uColor"), c[0], c[1], c[2])
        val cap = capColor
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uCapColor"), cap[0], cap[1], cap[2])

        val clip = clipPlane
        val clipPlaneLoc = GLES30.glGetUniformLocation(program, "uClipPlane")
        val clipEnabledLoc = GLES30.glGetUniformLocation(program, "uClipEnabled")
        if (clip != null) {
            GLES30.glUniform4f(clipPlaneLoc, clip.nx, clip.ny, clip.nz, clip.d)
        }

        val flat = GLES30.glGetUniformLocation(program, "uFlat")
        val line = GLES30.glGetUniformLocation(program, "uLine")
        val loc = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glEnableVertexAttribArray(loc)

        fun lines(l: Lines, color: FloatArray, lineWidth: Float, clipLines: Boolean = false) {
            if (l.vertices == 0) return
            GLES30.glUniform1i(clipEnabledLoc, if (clipLines && clip != null) 1 else 0)
            GLES30.glUniform1f(flat, 1f)
            GLES30.glUniform3f(line, color[0], color[1], color[2])
            GLES30.glLineWidth(lineWidth)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, l.vbo)
            GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, l.vertices)
        }

        if (indexCount > 0) {
            // Flächen; leicht nach hinten versetzt, damit die Kanten darüber nicht flackern (z-fighting)
            GLES30.glUniform1i(clipEnabledLoc, if (clip != null) 1 else 0)
            GLES30.glUniform1f(flat, 0f)
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(1f, 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glVertexAttribPointer(loc, 3, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
            lines(edges, lineColor, 2f, clipLines = true)
        }
        // Gitter nach dem Körper, mit Tiefentest: der Körper verdeckt es
        lines(grid, gridColor, 1f, clipLines = false)
        lines(gridMajor, gridMajorColor, 1.5f, clipLines = false)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        lines(highlight, highlightColor, 6f, clipLines = true)
        GLES30.glDepthFunc(GLES30.GL_LESS)
        // Skizzen: immer sichtbar (auch wenn sie in oder hinter dem Körper liegen)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        lines(sketch, sketchColor, 3f, clipLines = false)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisableVertexAttribArray(loc)
    }

    /** Senkrechter Farbverlauf über das ganze Bild: ein bildschirmfüllendes Dreieck ohne Puffer (Ecken aus gl_VertexID). */
    private fun drawBackground() {
        val top = background; val bottom = backgroundBottom
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(gradientProgram)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(gradientProgram, "uTop"), top[0], top[1], top[2])
        GLES30.glUniform3f(GLES30.glGetUniformLocation(gradientProgram, "uBottom"), bottom[0], bottom[1], bottom[2])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Lädt Linienpaare in [vbo]; gibt die Anzahl der Eckpunkte zurück. */
    private fun uploadLines(vbo: Int, segments: FloatArray?): Int {
        if (segments == null || segments.isEmpty()) return 0
        val buf = ByteBuffer.allocateDirect(segments.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(segments)
        buf.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, segments.size * 4, buf, GLES30.GL_STATIC_DRAW)
        return segments.size / 3
    }

    private fun upload(mesh: Mesh?) {
        if (mesh == null || mesh.isEmpty) { indexCount = 0; meshPositions = null; return }
        val pos = ByteBuffer.allocateDirect(mesh.positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.positions)
        pos.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.positions.size * 4, pos, GLES30.GL_STATIC_DRAW)
        val idx = ByteBuffer.allocateDirect(mesh.triangles.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.triangles)
        idx.position(0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.triangles.size * 4, idx, GLES30.GL_STATIC_DRAW)
        indexCount = mesh.triangles.size
        meshPositions = mesh.positions
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Programm: " + GLES30.glGetProgramInfoLog(p) }
        return p
    }

    private companion object {
        const val GRADIENT_VERTEX = """#version 300 es
out float vY;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2)) * 2.0 - 1.0;  // (-1,-1), (3,-1), (-1,3)
    vY = p.y * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}"""
        const val GRADIENT_FRAGMENT = """#version 300 es
precision mediump float;
uniform vec3 uTop;
uniform vec3 uBottom;
in float vY;
out vec4 outColor;
void main() { outColor = vec4(mix(uBottom, uTop, clamp(vY, 0.0, 1.0)), 1.0); }"""
        const val VERTEX = """#version 300 es
uniform mat4 uMvp;
uniform mat4 uView;
in vec3 aPos;
out vec3 vViewPos;
out vec3 vWorldPos;
void main() {
    vWorldPos = aPos;
    vViewPos = (uView * vec4(aPos, 1.0)).xyz;
    gl_Position = uMvp * vec4(aPos, 1.0);
}"""
        const val FRAGMENT = """#version 300 es
precision mediump float;
uniform vec3 uColor;
uniform vec3 uCapColor;
uniform vec3 uLine;
uniform float uFlat;
uniform vec4 uClipPlane;
uniform int uClipEnabled;
in vec3 vViewPos;
in vec3 vWorldPos;
out vec4 outColor;
void main() {
    if (uClipEnabled != 0) {
        if (dot(uClipPlane.xyz, vWorldPos) + uClipPlane.w < 0.0) discard;
    }
    if (uFlat > 0.5) { outColor = vec4(uLine, 1.0); return; }
    // Flächennormale aus Bildschirmableitungen; dem Betrachter zugewandt ausrichten (+z im Sichtraum).
    vec3 n = normalize(cross(dFdx(vViewPos), dFdy(vViewPos)));
    if (n.z < 0.0) n = -n;
    float diff = clamp(dot(n, normalize(vec3(-0.35, 0.45, 0.82))), 0.0, 1.0);
    vec3 col = gl_FrontFacing ? uColor : uCapColor;
    outColor = vec4(col * (0.42 + 0.58 * diff), 1.0);  // kräftiges Grundlicht: auch abgewandte Seiten bleiben vor dunklem Grund lesbar
}"""
    }
}

/** Kanten als Polylinien (für das Antippen): aufeinanderfolgende Punkte x y z …, je Kante [counts] Punkte. */
internal class EdgePickData(val polylines: FloatArray, val counts: IntArray)

/** Flächen-ID je Dreieck (für das Antippen von Flächen). */
internal class FacePickData(val triangleFace: IntArray)

/**
 * 3D-Ansicht: schattierter Körper ([mesh], optional), Skizzenlinien im Raum und gewählte Kanten. Bedienung: Ziehen = Orbit,
 * Pinch / Mausrad = Zoom; Blickrichtung und Zoom liegen in [camera] (damit Übergänge sie animieren können). Z zeigt nach oben.
 * Mit [pickEdges] und [onPickEdge] lassen sich Kanten antippen (nur sichtbare), mit [pickFaces] und [onPickFace] Flächen
 * (die vorderste unter dem Finger; `null` = ins Leere getippt). Ist beides gesetzt, haben Kanten Vorrang.
 */
@Composable
internal fun MeshViewport(
    mesh: Mesh?, camera: ViewCamera, base: Color, background: Color, modifier: Modifier = Modifier, edges: FloatArray? = null,
    lineColor: Color = Color(0xFF1B1B25), highlight: FloatArray? = null, sketchLines: FloatArray? = null, sketchColor: Color = Color(0xFF1A66F2),
    backgroundBottom: Color = background, highlightColor: Color = Color(0xFFFF8C00),
    capColor: Color = Color(0xFFD46A55), clipPlane: ClipPlane? = null,
    grid: GridLines? = null, gridColor: Color = Color(0xFFD0D6E0), gridMajorColor: Color = Color(0xFFB4BDCC),
    pickEdges: EdgePickData? = null, onPickEdge: ((Int) -> Unit)? = null,
    pickFaces: FacePickData? = null, onPickFace: ((FaceHit?) -> Unit)? = null,
) {
    val renderer = remember { MeshRenderer() }
    val currentOnPick by rememberUpdatedState(onPickEdge)
    val currentOnPickFace by rememberUpdatedState(onPickFace)

    AndroidView(
        factory = { ctx: Context ->
            GlTextureView(ctx, renderer) // zeichnet nur auf Anfrage (requestRender), wie zuvor RENDERMODE_WHEN_DIRTY
        },
        onRelease = { it.release() },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(mesh, pickEdges, pickFaces, sketchLines, clipPlane) {
                detectTapGestures { tap ->
                    val m = mesh ?: return@detectTapGestures
                    val cam = OrthoCamera(camera.yaw, camera.pitch, camera.zoom, SceneFrame.of(m.positions, sketchLines), size.width, size.height)
                    // Kanten zuerst; mit beiden (Messen) trifft ein Tipp neben jede Kante die Fläche darunter
                    val edge = pickEdges?.let { EdgePicker.pick(cam, tap.x, tap.y, it.polylines, it.counts, m.positions, m.triangles, clipPlane = clipPlane) } ?: -1
                    if (pickEdges != null && (edge >= 0 || pickFaces == null)) {
                        currentOnPick?.invoke(edge)
                    } else if (pickFaces != null) {
                        currentOnPickFace?.invoke(FacePicker.pick(cam, tap.x, tap.y, m.positions, m.triangles, pickFaces.triangleFace, clipPlane = clipPlane))
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    camera.yaw += pan.x * 0.01f
                    camera.pitch = (camera.pitch + pan.y * 0.01f).coerceIn(-1.5f, 1.6f)
                    camera.zoom = (camera.zoom * gestureZoom).coerceIn(0.1f, 20f)
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Scroll) {
                            camera.zoom = (camera.zoom * exp(-e.changes.first().scrollDelta.y * 0.1f)).coerceIn(0.1f, 20f)
                            e.changes.forEach { it.consume() }
                        }
                    }
                }
            },
        update = { v ->
            renderer.yaw = camera.yaw; renderer.pitch = camera.pitch; renderer.zoom = camera.zoom
            renderer.baseColor = floatArrayOf(base.red, base.green, base.blue)
            renderer.background = floatArrayOf(background.red, background.green, background.blue)
            renderer.backgroundBottom = floatArrayOf(backgroundBottom.red, backgroundBottom.green, backgroundBottom.blue)
            renderer.highlightColor = floatArrayOf(highlightColor.red, highlightColor.green, highlightColor.blue)
            renderer.capColor = floatArrayOf(capColor.red, capColor.green, capColor.blue)
            renderer.lineColor = floatArrayOf(lineColor.red, lineColor.green, lineColor.blue)
            renderer.sketchColor = floatArrayOf(sketchColor.red, sketchColor.green, sketchColor.blue)
            renderer.clipPlane = clipPlane
            renderer.setMesh(mesh)
            renderer.setEdges(edges)
            renderer.setHighlight(highlight)
            renderer.setSketchLines(sketchLines)
            renderer.gridColor = floatArrayOf(gridColor.red, gridColor.green, gridColor.blue)
            renderer.gridMajorColor = floatArrayOf(gridMajorColor.red, gridMajorColor.green, gridMajorColor.blue)
            renderer.setGrid(grid)
            v.requestRender()
        },
    )
}
