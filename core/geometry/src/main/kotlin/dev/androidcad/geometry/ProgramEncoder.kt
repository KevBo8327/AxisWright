package dev.androidcad.geometry

import dev.androidcad.lang.Boolean3D
import dev.androidcad.lang.BoolOp
import dev.androidcad.lang.Circle
import dev.androidcad.lang.Colored
import dev.androidcad.lang.Cube
import dev.androidcad.lang.Cylinder
import dev.androidcad.lang.Group
import dev.androidcad.lang.Hull
import dev.androidcad.lang.LinearExtrude
import dev.androidcad.lang.Minkowski
import dev.androidcad.lang.Node
import dev.androidcad.lang.Offset
import dev.androidcad.lang.Polygon2D
import dev.androidcad.lang.Polyhedron
import dev.androidcad.lang.RotateExtrude
import dev.androidcad.lang.Sphere
import dev.androidcad.lang.Square
import dev.androidcad.lang.Transform

/** Opcodes des nativen Interpreters – identisch zu `enum Op` in `cadgeom.cpp`. */
internal object Op {
    const val CUBE = 1.0
    const val SPHERE = 2.0
    const val CYLINDER = 3.0
    const val POLYHEDRON = 4.0
    const val CIRCLE = 5.0
    const val SQUARE = 6.0
    const val POLYGON = 7.0
    const val TRANSFORM = 8.0
    const val UNION = 9.0
    const val DIFFERENCE = 10.0
    const val INTERSECTION = 11.0
    const val HULL = 12.0
    const val MINKOWSKI = 13.0
    const val LINEAR_EXTRUDE = 14.0
    const val ROTATE_EXTRUDE = 15.0
    const val OFFSET = 16.0
}

/**
 * Übersetzt den CSG-Baum in Postfix-Notation. Invariante: jeder Knoten hinterlässt genau
 * **einen** Eintrag auf dem Stapel des nativen Interpreters.
 */
internal class ProgramEncoder {
    private var buf = DoubleArray(256)
    private var size = 0

    fun encode(root: Node): DoubleArray {
        node(root)
        return buf.copyOf(size)
    }

    private fun put(v: Double) {
        if (size == buf.size) buf = buf.copyOf(size * 2)
        buf[size++] = v
    }

    private fun put(v: Int) = put(v.toDouble())
    private fun put(b: Boolean) = put(if (b) 1.0 else 0.0)

    /** Kinder kodieren und zu einem Eintrag vereinigen (implizite Vereinigung). */
    private fun children(list: List<Node>) {
        list.forEach(::node)
        if (list.size != 1) { put(Op.UNION); put(list.size) }
    }

    private fun node(n: Node) {
        when (n) {
            is Cube -> { put(Op.CUBE); put(n.size.first); put(n.size.second); put(n.size.third); put(n.center) }
            is Sphere -> { put(Op.SPHERE); put(n.radius); put(n.fragments) }
            is Cylinder -> { put(Op.CYLINDER); put(n.height); put(n.r1); put(n.r2); put(n.center); put(n.fragments) }
            is Polyhedron -> {
                put(Op.POLYHEDRON)
                put(n.points.size)
                for (p in n.points) { put(p.first); put(p.second); put(p.third) }
                put(n.faces.size)
                for (f in n.faces) { put(f.size); f.forEach { put(it) } }
            }
            is Circle -> { put(Op.CIRCLE); put(n.radius); put(n.fragments) }
            is Square -> { put(Op.SQUARE); put(n.size.first); put(n.size.second); put(n.center) }
            is Polygon2D -> {
                put(Op.POLYGON)
                val paths = n.paths ?: listOf(n.points.indices.toList())
                put(paths.size)
                for (path in paths) {
                    put(path.size)
                    for (i in path) { put(n.points[i].first); put(n.points[i].second) }
                }
            }
            is Transform -> { children(n.children); put(Op.TRANSFORM); n.matrix.m.forEach { put(it) } }
            is Group -> children(n.children)
            is Colored -> children(n.children) // Farbe wird im Mesh-Pfad noch nicht übertragen
            is Boolean3D -> {
                n.children.forEach(::node)
                put(when (n.op) { BoolOp.UNION -> Op.UNION; BoolOp.DIFFERENCE -> Op.DIFFERENCE; BoolOp.INTERSECTION -> Op.INTERSECTION })
                put(n.children.size)
            }
            is Hull -> { n.children.forEach(::node); put(Op.HULL); put(n.children.size) }
            is Minkowski -> { n.children.forEach(::node); put(Op.MINKOWSKI); put(n.children.size) }
            is LinearExtrude -> {
                children(n.children)
                put(Op.LINEAR_EXTRUDE); put(n.height); put(n.center); put(n.twist); put(n.slices); put(n.scaleX); put(n.scaleY)
            }
            is RotateExtrude -> { children(n.children); put(Op.ROTATE_EXTRUDE); put(n.angle); put(n.fragments) }
            is Offset -> { children(n.children); put(Op.OFFSET); put(n.amount); put(n.round); put(n.chamfer); put(n.fragments) }
        }
    }
}
