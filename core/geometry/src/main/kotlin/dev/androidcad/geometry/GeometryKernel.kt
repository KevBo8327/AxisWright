package dev.androidcad.geometry

import dev.androidcad.lang.Node

/** Fehler des Geometriekerns (ungültige Geometrie, nicht kombinierbare 2D/3D-Objekte, …). */
class GeometryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal object NativeKernel {
    init {
        System.loadLibrary("cadgeom")
    }

    /** Liefert `[FloatArray positions, IntArray triangles]`; wirft [IllegalStateException] bei Fehlern. */
    @JvmStatic
    external fun nativeBuild(program: DoubleArray): Array<Any?>
}

/** Wandelt einen CSG-Baum (aus `:core:lang`) mit Manifold in ein [Mesh] um. Thread-sicher, zustandslos. */
object GeometryKernel {
    fun build(root: Node): Mesh {
        val program = ProgramEncoder().encode(root)
        val out = try {
            NativeKernel.nativeBuild(program)
        } catch (e: IllegalStateException) {
            throw GeometryException(e.message ?: "Geometriefehler", e)
        }
        return Mesh(out[0] as FloatArray, out[1] as IntArray)
    }
}
