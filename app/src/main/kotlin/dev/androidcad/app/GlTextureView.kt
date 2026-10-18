package dev.androidcad.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OpenGL-ES-3-Fläche auf einer [TextureView] mit eigenem Render-Thread, als Ersatz für GLSurfaceView.
 *
 * Warum nicht GLSurfaceView: Deren surfaceChanged wartet auf dem UI-Thread, bis der GL-Thread ein Bild in der neuen Größe gezeichnet hat.
 * Seit BLAST (Android 12+) ist das erste Bild nach dem Neuanlegen der Fläche an die Synchronisierung mit dem Fenster gebunden, und ein
 * zweites kann erst nach dem UI-Thread abgegeben werden – bei der Rückkehr aus einer anderen App blockierten sich beide (ANR in
 * surfaceChanged / eglSwapBuffers). Hier wartet der UI-Thread nie auf den Render-Thread: alles läuft über [handler].
 *
 * Der [renderer] bekommt dieselben Rückrufe wie unter GLSurfaceView (ohne GL10/EGLConfig): onSurfaceCreated nach jedem neuen Kontext
 * (die Fläche geht beim Wechsel in den Hintergrund verloren, der Renderer lädt dann alles neu hoch), onSurfaceChanged, onDrawFrame.
 */
internal class GlTextureView(context: Context, private val renderer: GLSurfaceView.Renderer) : TextureView(context), TextureView.SurfaceTextureListener {
    private val thread = HandlerThread("CadGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val drawPending = AtomicBoolean(false)

    // nur auf dem Render-Thread benutzt
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    /** Zeichnet bald ein neues Bild; mehrere Anfragen vor dem Zeichnen ergeben eines. Von jedem Thread aus. */
    fun requestRender() {
        if (drawPending.compareAndSet(false, true)) handler.post { drawPending.set(false); draw() }
    }

    /** Gibt EGL und den Render-Thread frei (wenn die Ansicht endgültig verschwindet). */
    fun release() {
        handler.post { destroyEgl() }
        thread.quitSafely()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        handler.post {
            if (!createEgl(st)) return@post
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, w, h)
            draw()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        // zweimal: das erste Bild landet noch im Puffer der alten Größe (die neue gilt erst ab dem nächsten Puffer), sonst bliebe ein Rand leer
        handler.post { if (surface != EGL14.EGL_NO_SURFACE) { renderer.onSurfaceChanged(null, w, h); draw(); draw() } }
    }

    // false: die Textur gibt der Render-Thread selbst frei, nach EGL – so muss der UI-Thread nicht auf ihn warten
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        handler.post { destroyEgl(); st.release() }
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private fun draw() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        renderer.onDrawFrame(null)
        EGL14.eglSwapBuffers(display, surface) // schlägt nur fehl, wenn die Fläche gerade verschwindet; dann kommt destroy ohnehin
    }

    private fun createEgl(st: SurfaceTexture): Boolean {
        destroyEgl()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fail("eglInitialize")
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return fail("eglChooseConfig")
        val config = configs[0]!!
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        surface = EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(EGL14.EGL_NONE), 0)
        if (surface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, surface, surface, eglContext)) return fail("eglMakeCurrent")
        return true
    }

    private fun destroyEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY; eglContext = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE
    }

    /** EGL-Fehler: Ansicht bleibt leer statt abzustürzen (der Rest der App funktioniert weiter). */
    private fun fail(step: String): Boolean {
        Log.e("GlTextureView", "$step fehlgeschlagen: 0x${Integer.toHexString(EGL14.eglGetError())}")
        destroyEgl()
        return false
    }
}
