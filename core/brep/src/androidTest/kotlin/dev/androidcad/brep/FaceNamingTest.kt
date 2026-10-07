package dev.androidcad.brep

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prototyp „Topological Naming“: Flächen behalten ihren Namen über Boolesche Operationen, Transformationen und
 * Verrundungen, und eine Auswahl über den Namen findet nach Parameteränderungen dieselbe Fläche wieder.
 */
class FaceNamingTest {
    private companion object {
        const val BOTTOM = 1L
        const val TOP = 2L
        const val FRONT = 101L // Segment 1: y = 0
        const val RIGHT = 102L // Segment 2: x = w
        const val BACK = 103L
        const val LEFT = 104L
        const val HOLE = 500L
    }

    /** Lage einer Fläche, aus dem Netz berechnet: Fläche (mm²), Schwerpunkt, mittlere Normale. */
    private class FaceGeo(val area: Double, val c: DoubleArray, val n: DoubleArray)

    private fun geo(shape: BrepShape, faceId: Int): FaceGeo {
        val m = shape.tessellate(0.02, 0.2)
        var area = 0.0
        val c = DoubleArray(3)
        val n = DoubleArray(3)
        for (t in 0 until m.triangleCount) {
            if (m.triangleFace[t] != faceId) continue
            val p = Array(3) { k -> DoubleArray(3) { d -> m.positions[3 * m.triangles[3 * t + k] + d].toDouble() } }
            val u = DoubleArray(3) { p[1][it] - p[0][it] }
            val v = DoubleArray(3) { p[2][it] - p[0][it] }
            val cr = doubleArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            val a = 0.5 * sqrt(cr[0] * cr[0] + cr[1] * cr[1] + cr[2] * cr[2])
            area += a
            for (d in 0..2) { c[d] += a * (p[0][d] + p[1][d] + p[2][d]) / 3; n[d] += 0.5 * cr[d] }
        }
        for (d in 0..2) c[d] /= area
        val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        for (d in 0..2) n[d] /= len
        return FaceGeo(area, c, n)
    }

    private fun one(shape: BrepShape, tag: Long): Int {
        val f = shape.facesWithTag(tag)
        assertEquals(1, f.size, "Name $tag sollte genau eine Fläche bezeichnen, gefunden: $f")
        return f.single()
    }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-3) = assertTrue(abs(expected - actual) <= tol, "erwartet $expected, erhalten $actual")

    private fun taggedRect(w: Double, h: Double) = Profile(
        listOf(
            ProfileSegment.Line(0.0, 0.0, w, 0.0, FRONT),
            ProfileSegment.Line(w, 0.0, w, h, RIGHT),
            ProfileSegment.Line(w, h, 0.0, h, BACK),
            ProfileSegment.Line(0.0, h, 0.0, 0.0, LEFT),
        ),
    )

    private fun box(w: Double, h: Double, height: Double) = BrepShape.extrude(taggedRect(w, h), height, bottomTag = BOTTOM, topTag = TOP)

    @Test
    fun extrusionNamesAllSixFaces() {
        box(20.0, 30.0, 10.0).use { b ->
            assertEquals(6, b.faceCount)
            assertEquals(setOf(BOTTOM, TOP, FRONT, RIGHT, BACK, LEFT), b.faceTags().toSet())
            val front = geo(b, one(b, FRONT)); near(0.0, front.c[1]); near(-1.0, front.n[1]); near(20.0 * 10.0, front.area)
            val right = geo(b, one(b, RIGHT)); near(20.0, right.c[0]); near(1.0, right.n[0])
            val top = geo(b, one(b, TOP)); near(10.0, top.c[2]); near(1.0, top.n[2])
            val bottom = geo(b, one(b, BOTTOM)); near(0.0, bottom.c[2]); near(-1.0, bottom.n[2])
        }
    }

    @Test
    fun namesAreStableWhenParametersChange() {
        for ((w, h, height) in listOf(Triple(20.0, 30.0, 10.0), Triple(55.0, 12.0, 3.0), Triple(8.0, 8.0, 40.0))) {
            box(w, h, height).use { b ->
                near(w, geo(b, one(b, RIGHT)).c[0]) // „rechte Seite“ bleibt rechts, egal wie breit
                near(h, geo(b, one(b, BACK)).c[1])
                near(height, geo(b, one(b, TOP)).c[2])
            }
        }
    }

    @Test
    fun negativeAndSymmetricDirectionKeepBottomBelowTop() {
        box(10.0, 10.0, -6.0).use { b ->
            near(-6.0, geo(b, one(b, BOTTOM)).c[2]); near(0.0, geo(b, one(b, TOP)).c[2])
        }
        BrepShape.extrude(taggedRect(10.0, 10.0), 6.0, symmetric = true, bottomTag = BOTTOM, topTag = TOP).use { b ->
            near(-3.0, geo(b, one(b, BOTTOM)).c[2]); near(3.0, geo(b, one(b, TOP)).c[2])
        }
    }

    @Test
    fun untaggedProfilesStillWork() {
        BrepShape.extrude(Profile.rectangle(0.0, 0.0, 5.0, 5.0), 5.0).use { b ->
            assertTrue(b.faceTags().all { it == 0L })
            assertEquals(6, b.faceCount)
        }
    }

    @Test
    fun namesSurviveACutWithACylinderAndNameTheNewWall() {
        box(40.0, 30.0, 10.0).use { plate ->
            val hole = Profile(listOf(ProfileSegment.Circle(20.0, 15.0, 4.0, HOLE)))
            BrepShape.extrude(hole, 30.0, bottomTag = 600, topTag = 601).use { tool ->
                tool.translate(0.0, 0.0, -10.0).use { moved ->
                    plate.cut(moved).use { r ->
                        near(40.0 * 30.0 - PI * 16, geo(r, one(r, TOP)).area, 0.3) // Deckel hat jetzt ein Loch
                        near(10.0, geo(r, one(r, TOP)).c[2])
                        near(40.0 * 30.0 - PI * 16, geo(r, one(r, BOTTOM)).area, 0.3)
                        near(2 * PI * 4 * 10.0, geo(r, one(r, HOLE)).area, 0.5) // Bohrungswand = neue, benannte Fläche
                        assertEquals(7, r.faceCount)
                        assertTrue(r.facesWithTag(600).isEmpty() && r.facesWithTag(601).isEmpty(), "Stirnflächen des Werkzeugs liegen außerhalb")
                    }
                }
            }
        }
    }

    @Test
    fun aSplitFaceKeepsItsNameOnBothPieces() {
        box(40.0, 30.0, 10.0).use { b ->
            // Schlitz über die ganze Breite teilt Deckel und Boden in je zwei Stücke
            val slot = Profile(listOf(
                ProfileSegment.Line(15.0, -5.0, 25.0, -5.0), ProfileSegment.Line(25.0, -5.0, 25.0, 35.0),
                ProfileSegment.Line(25.0, 35.0, 15.0, 35.0), ProfileSegment.Line(15.0, 35.0, 15.0, -5.0),
            ))
            BrepShape.extrude(slot, 30.0).use { s ->
                s.translate(0.0, 0.0, -10.0).use { t ->
                    b.cut(t).use { r ->
                        assertEquals(2, r.facesWithTag(TOP).size)
                        assertEquals(2, r.facesWithTag(FRONT).size)
                        assertEquals(1, r.facesWithTag(RIGHT).size)
                    }
                }
            }
        }
    }

    @Test
    fun aRemovedFaceDisappearsAndIsReportedAsMissing() {
        box(20.0, 30.0, 10.0).use { b ->
            val cutter = BrepShape.extrude(
                Profile(listOf(
                    ProfileSegment.Line(15.0, -5.0, 25.0, -5.0, 901), ProfileSegment.Line(25.0, -5.0, 25.0, 35.0, 902),
                    ProfileSegment.Line(25.0, 35.0, 15.0, 35.0, 903), ProfileSegment.Line(15.0, 35.0, 15.0, -5.0, 904),
                )), 20.0, bottomTag = 905, topTag = 906,
            )
            cutter.use { c ->
                c.translate(0.0, 0.0, -5.0).use { t ->
                    b.cut(t).use { r ->
                        assertTrue(r.facesWithTag(RIGHT).isEmpty(), "Die rechte Seite wurde weggeschnitten – der Name darf nichts mehr finden")
                        val newRight = geo(r, one(r, 904)) // die neue rechte Wand stammt aus dem Werkzeug (x = 15)
                        near(15.0, newRight.c[0]); near(1.0, newRight.n[0])
                        near(15.0, geo(r, one(r, BACK)).area / 10.0 / 1.0, 1e-6) // Rückwand ist nur noch 15 breit
                    }
                }
            }
        }
    }

    @Test
    fun fusedBodiesKeepNamesFromBothParts() {
        box(40.0, 30.0, 10.0).use { base ->
            val boss = BrepShape.extrude(
                Profile(listOf(ProfileSegment.Circle(20.0, 15.0, 5.0, 700))), 8.0, bottomTag = 701, topTag = 702,
            )
            boss.use { c ->
                c.translate(0.0, 0.0, 10.0).use { up ->
                    base.union(up).use { r ->
                        near(18.0, geo(r, one(r, 702)).c[2]) // Deckel des Zapfens
                        near(10.0, geo(r, one(r, TOP)).c[2]) // Deckel der Platte (jetzt mit Kreisring-Kontur)
                        near(40.0 * 30.0 - PI * 25, geo(r, one(r, TOP)).area, 0.3)
                        near(2 * PI * 5 * 8.0, geo(r, one(r, 700)).area, 0.5)
                    }
                }
            }
        }
    }

    @Test
    fun mirrorKeepsNamesVolumeAndFlipsNormalsAcrossThePlane() {
        box(20.0, 30.0, 10.0).use { b ->
            // Spiegelebene x = −5 (Normale +x): der Quader x ∈ [0, 20] landet bei x ∈ [−30, −10]
            b.mirror(-5.0, 0.0, 0.0, 1.0, 0.0, 0.0).use { m ->
                near(b.volume(), m.volume(), 1e-6)
                assertEquals(6, m.faceCount)
                val right = geo(m, one(m, RIGHT)) // war x = 20, Normale +x → jetzt x = −30, Normale −x
                near(-30.0, right.c[0]); near(-1.0, right.n[0])
                val left = geo(m, one(m, LEFT))
                near(-10.0, left.c[0]); near(1.0, left.n[0])
                near(10.0, geo(m, one(m, TOP)).c[2]); near(1.0, geo(m, one(m, TOP)).n[2]) // z bleibt
                // das Spiegelbild lässt sich weiterverwenden (Boolesche Operation)
                box(20.0, 30.0, 10.0).use { other -> other.union(m).use { near(2 * b.volume(), it.volume(), 1e-6) } }
            }
            // schräge Ebene: Spiegelung ist ihre eigene Umkehrung
            b.mirror(3.0, 4.0, 5.0, 1.0, 2.0, 3.0).use { m -> m.mirror(3.0, 4.0, 5.0, 1.0, 2.0, 3.0).use { back ->
                near(20.0 + 0.0, geo(back, one(back, RIGHT)).c[0], 1e-6)
                near(b.volume(), back.volume(), 1e-6)
            } }
        }
    }

    @Test
    fun unifyingAMirroredHalfRemovesTheSeamAndKeepsBothNames() {
        box(20.0, 30.0, 10.0).use { b ->
            // Spiegelbild an x = 0 mit eigenen Namen (+1000), vereinigt: Deck-, Boden-, Vorder- und Rückfläche liegen je zweimal nebeneinander
            b.mirror(0.0, 0.0, 0.0, 1.0, 0.0, 0.0).use { m -> m.retag { if (it == 0L) 0L else it + 1000L }.use { r -> b.union(r).use { u ->
                assertTrue(u.faceCount > 6, "vor dem Zusammenführen mit Naht")
                u.unifyFaces().use { one ->
                    near(u.volume(), one.volume(), 1e-6)
                    assertEquals(6, one.faceCount, "ein Quader 40 × 30 × 10 ohne Naht")
                    // die Deckfläche heißt nach dem älteren Namen, findet sich aber auch unter dem gespiegelten
                    val top = one(one, TOP)
                    assertEquals(listOf(top), one.facesWithTag(TOP + 1000L))
                    near(40.0 * 30.0, geo(one, top).area)
                    assertTrue(one.faceAliases().getValue(top).contains(TOP + 1000L))
                    // Kante zwischen gespiegelter Deckfläche und gespiegelter rechter Fläche (jetzt bei x = −20)
                    val e = one.edgesBetween(TOP + 1000L, RIGHT + 1000L)
                    assertEquals(1, e.size)
                    assertEquals(e, one.edgesBetween(TOP, RIGHT + 1000L))
                    // Zweitnamen überstehen weitere Operationen (Verrundung) und das Umbenennen
                    one.fillet(e, 2.0).use { f ->
                        assertEquals(1, f.facesWithTag(TOP + 1000L).size)
                        f.retag { if (it == 0L) 0L else it + 1L }.use { g -> assertEquals(1, g.facesWithTag(TOP + 1001L).size) }
                    }
                }
            } } }
        }
    }

    @Test
    fun retagRenamesFacesWithoutChangingTheShape() {
        box(20.0, 30.0, 10.0).use { b ->
            b.retag { if (it == 0L) 0L else it + 1000L }.use { r ->
                assertEquals(b.volume(), r.volume(), 1e-9)
                near(20.0, geo(r, one(r, RIGHT + 1000L)).c[0])
                assertEquals(0, r.facesWithTag(RIGHT).size)
                near(10.0, geo(r, one(r, TOP + 1000L)).c[2])
            }
        }
    }

    @Test
    fun transformsKeepNames() {
        box(20.0, 30.0, 10.0).use { b ->
            b.translate(5.0, 6.0, 7.0).use { t ->
                near(25.0, geo(t, one(t, RIGHT)).c[0]); near(17.0, geo(t, one(t, TOP)).c[2])
            }
            // 90° um z: x → y, y → −x
            val rot = doubleArrayOf(0.0, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            b.transform(rot).use { t ->
                val right = geo(t, one(t, RIGHT))
                near(1.0, right.n[1]); near(20.0, right.c[1]) // Normale +x wurde zu +y
            }
        }
    }

    @Test
    fun edgesAreFoundByTheirTwoNeighbourFaces() {
        box(20.0, 30.0, 10.0).use { b ->
            val topRight = b.edgesBetween(TOP, RIGHT)
            assertEquals(1, topRight.size)
            val e = b.edges().single { it.id == topRight.single() }
            near(30.0, e.length); near(20.0, e.start.first); near(10.0, e.start.third)
            assertEquals(b.edgesBetween(RIGHT, TOP), topRight, "Reihenfolge der Namen ist egal")
            val corner = b.edges().single { it.id == b.edgesBetween(FRONT, RIGHT).single() }
            near(10.0, corner.length) // senkrechte Ecke
            assertTrue(b.edgesBetween(FRONT, BACK).isEmpty(), "gegenüberliegende Flächen treffen sich nicht")
        }
        // Zylindernaht: beide Nachbarn sind dieselbe Fläche
        BrepShape.extrude(Profile(listOf(ProfileSegment.Circle(0.0, 0.0, 3.0, HOLE))), 5.0, bottomTag = BOTTOM, topTag = TOP).use { c ->
            assertEquals(1, c.edgesBetween(HOLE, HOLE).size)
            assertEquals(1, c.edgesBetween(TOP, HOLE).size)
        }
    }

    @Test
    fun filletFindsEdgeByNamesAndNamesTheNewFaceStablyAcrossRebuilds() {
        val fillet = 900L
        for ((w, h) in listOf(20.0 to 30.0, 45.0 to 18.0)) {
            box(w, h, 10.0).use { b ->
                val edge = b.edgesBetween(TOP, RIGHT)
                assertEquals(1, edge.size)
                b.fillet(edge, 2.0, listOf(fillet)).use { r ->
                    // Viertelzylinder: Fläche = (π/2) · R · Länge, Länge = Tiefe der Platte
                    near(PI / 2 * 2.0 * h, geo(r, one(r, fillet)).area, 0.05)
                    assertTrue(r.facesWithTag(TOP).size == 1 && r.facesWithTag(RIGHT).size == 1, "Nachbarflächen behalten ihre Namen")
                    near(w - 2.0, geo(r, one(r, TOP)).area / h + 0.0, 0.05) // Deckel ist um den Radius schmaler
                    assertEquals(1, r.edgesBetween(TOP, fillet).size, "Neue Kante zwischen Deckel und Rundung ist wieder über Namen auffindbar")
                }
            }
        }
    }

    @Test
    fun chamferNamesItsFaceAndRemovesTheExpectedVolume() {
        for ((w, h) in listOf(20.0 to 30.0, 45.0 to 18.0)) {
            box(w, h, 10.0).use { b ->
                val edge = b.edgesBetween(TOP, RIGHT)
                b.chamfer(edge, 2.0, listOf(910L)).use { r ->
                    near(w * h * 10.0 - 0.5 * 2.0 * 2.0 * h, r.volume(), 1e-6) // Dreieckprisma abgetragen
                    near(sqrt(2.0) * 2.0 * h, geo(r, one(r, 910)).area, 0.05) // schräge Fläche
                    assertEquals(1, r.facesWithTag(TOP).size)
                    assertEquals(1, r.edgesBetween(TOP, 910).size)
                }
            }
        }
    }

    @Test
    fun planarFacesReportOutwardNormalsAndSkipCurvedFaces() {
        box(20.0, 30.0, 10.0).use { b ->
            val planes = b.planarFaces()
            assertEquals(6, planes.size)
            fun face(tag: Long) = planes[one(b, tag) - 1]!!
            near(1.0, face(RIGHT).normal.first); near(0.0, face(RIGHT).distanceTo(20.0, 5.0, 5.0)) // Punkt auf der Ebene
            near(1.0, face(TOP).normal.third); near(10.0, face(TOP).origin.third)
            near(-1.0, face(BOTTOM).normal.third)
            near(-1.0, face(FRONT).normal.second)
            near(1.0, face(BACK).normal.second)
            near(-1.0, face(LEFT).normal.first)
            // Abstand: Punkt über dem Deckel liegt außen (positiv), Punkt im Körper innen (negativ)
            assertTrue(face(TOP).distanceTo(5.0, 5.0, 12.0) > 0 && face(TOP).distanceTo(5.0, 5.0, 3.0) < 0)
        }
        BrepShape.extrude(Profile(listOf(ProfileSegment.Circle(0.0, 0.0, 3.0, HOLE))), 5.0, bottomTag = BOTTOM, topTag = TOP).use { c ->
            val planes = c.planarFaces()
            assertEquals(null, planes[one(c, HOLE) - 1]) // Zylinderwand ist nicht eben
            assertTrue(planes[one(c, TOP) - 1] != null)
        }
        // nach Verschieben und Drehen zeigt die Normale mit
        box(20.0, 30.0, 10.0).use { b ->
            val rot = doubleArrayOf(0.0, -1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            b.transform(rot).use { t -> near(1.0, t.planarFaces()[one(t, RIGHT) - 1]!!.normal.second) }
        }
        // Löcher: Innenfläche eines Zylinderschnitts hat gekrümmte Wand, Deckel bleibt eben
        box(40.0, 30.0, 10.0).use { plate ->
            BrepShape.extrude(Profile(listOf(ProfileSegment.Circle(20.0, 15.0, 4.0, HOLE))), 30.0).use { h ->
                h.translate(0.0, 0.0, -10.0).use { m ->
                    plate.cut(m).use { r ->
                        val planes = r.planarFaces()
                        assertTrue(planes[one(r, TOP) - 1] != null && planes[one(r, HOLE) - 1] == null)
                    }
                }
            }
        }
    }

    @Test
    fun edgeFaceTagsListsTheTwoNeighbourNamesPerEdge() {
        box(20.0, 30.0, 10.0).use { b ->
            val pairs = b.edgeFaceTags()
            assertEquals(b.edgeCount, pairs.size)
            assertTrue(pairs.all { (x, y) -> x <= y })
            val topRight = b.edgesBetween(TOP, RIGHT).single()
            assertEquals(minOf(TOP, RIGHT) to maxOf(TOP, RIGHT), pairs[topRight - 1])
        }
    }

    @Test
    fun namesCarryThroughALongChainOfOperations() {
        box(40.0, 30.0, 10.0).use { plate ->
            val hole = BrepShape.extrude(Profile(listOf(ProfileSegment.Circle(20.0, 15.0, 4.0, HOLE))), 30.0)
            hole.use { h ->
                h.translate(0.0, 0.0, -10.0).use { hm ->
                    plate.cut(hm).use { drilled ->
                        drilled.translate(100.0, 0.0, 0.0).use { moved ->
                            moved.fillet(moved.edgesBetween(TOP, RIGHT), 1.5, listOf(900L)).use { r ->
                                assertEquals(1, r.facesWithTag(HOLE).size)
                                assertEquals(1, r.facesWithTag(900).size)
                                near(110.0, geo(r, one(r, TOP)).c[2] + 100.0) // Platte + Versatz in x, Höhe bleibt 10
                            }
                        }
                    }
                }
            }
        }
    }
}
