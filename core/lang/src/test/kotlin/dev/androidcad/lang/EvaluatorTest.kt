package dev.androidcad.lang

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EvaluatorTest {
    private fun render(src: String, overrides: Map<String, String> = emptyMap()) = CadLang.render(src, overrides)

    /** Flacht die implizite Wurzel-Gruppe ab. */
    private fun top(src: String, overrides: Map<String, String> = emptyMap()): List<Node> =
        (render(src, overrides).root as Group).children

    @Test
    fun cubeWithVariableAndArithmetic() {
        val n = top("w = 2 * 5; cube([w, w / 2, 1 + 1], center = true);").single()
        val c = assertIs<Cube>(n)
        assertEquals(Triple(10.0, 5.0, 2.0), c.size)
        assertTrue(c.center)
    }

    @Test
    fun transformChainBuildsNestedTree() {
        val n = top("translate([1,2,3]) rotate(90) cube(1);").single()
        val t = assertIs<Transform>(n)
        assertEquals(Triple(1.0, 2.0, 3.0), t.matrix.apply(0.0, 0.0, 0.0))
        val r = assertIs<Transform>(t.children.single())
        val p = r.matrix.apply(1.0, 0.0, 0.0)
        assertEquals(0.0, p.first, 1e-12)
        assertEquals(1.0, p.second, 1e-12)
    }

    @Test
    fun rotateEulerAppliesXThenYThenZ() {
        val t = assertIs<Transform>(top("rotate([90, 0, 90]) cube(1);").single())
        // (0,1,0) -> Rx90 -> (0,0,1) -> Rz90 -> (0,0,1)
        val p = t.matrix.apply(0.0, 1.0, 0.0)
        assertEquals(0.0, p.first, 1e-12); assertEquals(0.0, p.second, 1e-12); assertEquals(1.0, p.third, 1e-12)
    }

    @Test
    fun forLoopProducesGroupWithOneNodePerIteration() {
        val n = top("for (i = [0:2]) translate([i*10, 0, 0]) cube(1);").single()
        assertEquals(3, assertIs<Group>(n).children.size)
    }

    @Test
    fun rangeWithStepAndNegativeStep() {
        assertEquals(4, assertIs<Group>(top("for (i = [0:5:15]) cube(1);").single()).children.size)
        assertEquals(3, assertIs<Group>(top("for (i = [10:-5:0]) cube(1);").single()).children.size)
        assertTrue(top("for (i = [5:1]) cube(1);").isEmpty())
    }

    @Test
    fun moduleWithDefaultsNamedArgsAndChildren() {
        val src = """
            module box(w = 1, h = 2) { cube([w, w, h]); }
            module wrap() { translate([1,0,0]) children(); }
            box(h = 5);
            wrap() box(3, 3);
        """.trimIndent()
        val nodes = top(src)
        assertEquals(Cube(Triple(1.0, 1.0, 5.0), false), nodes[0])
        val t = assertIs<Transform>(nodes[1])
        assertEquals(Cube(Triple(3.0, 3.0, 3.0), false), t.children.single())
    }

    @Test
    fun childrenIndexSelectsSingleChild() {
        val src = """
            module pick() { children(1); }
            pick() { cube(1); sphere(2); }
        """.trimIndent()
        assertIs<Sphere>(top(src).single())
    }

    @Test
    fun functionsRecursionAndVectors() {
        val src = """
            function fac(n) = n <= 1 ? 1 : n * fac(n - 1);
            cube(fac(5));
        """.trimIndent()
        assertEquals(Triple(120.0, 120.0, 120.0), assertIs<Cube>(top(src).single()).size)
    }

    @Test
    fun listComprehensionAndIndexing() {
        val src = """
            v = [for (i = [1:4]) if (i % 2 == 0) i * i];
            cube([v[0], v[1], len(v)]);
        """.trimIndent()
        assertEquals(Triple(4.0, 16.0, 2.0), assertIs<Cube>(top(src).single()).size)
    }

    @Test
    fun letAndEachInListComprehension() {
        val src = """
            v = [each [1,2], for (i=[3:4]) let (k = i * 2) k];
            cube([v[0], v[1], v[2] + v[3]]);
        """.trimIndent()
        assertEquals(Triple(1.0, 2.0, 14.0), assertIs<Cube>(top(src).single()).size)
    }

    @Test
    fun specialVariablesAreDynamicallyScoped() {
        val src = """
            module ball() { sphere(r = 10); }
            ${'$'}fn = 24;
            ball();
        """.trimIndent()
        assertEquals(24, assertIs<Sphere>(top(src).single()).fragments)
    }

    @Test
    fun specialVariableAsCallArgumentAppliesToCallAndModuleBody() {
        assertEquals(8, assertIs<Cylinder>(top("cylinder(r = 5, h = 1, ${'$'}fn = 8);").single()).fragments)
        val src = "module ball(r) { sphere(r); }\nball(3, ${'$'}fn = 16);"
        assertEquals(16, assertIs<Sphere>(top(src).single()).fragments)
        // gilt auch für die Kinder eines Aufrufs
        val nested = assertIs<Transform>(top("translate([1,0,0], ${'$'}fn = 12) sphere(2);").single())
        assertEquals(12, assertIs<Sphere>(nested.children.single()).fragments)
    }

    @Test
    fun fragmentCountFollowsFaAndFs() {
        // r=10: min(360/12=30, 2*pi*10/2=31.4) = 30
        assertEquals(30, assertIs<Sphere>(top("sphere(10);").single()).fragments)
        // sehr kleiner Radius: mindestens 5
        assertEquals(5, assertIs<Sphere>(top("sphere(0.1);").single()).fragments)
    }

    @Test
    fun cylinderRadiusDiameterAndCone() {
        val c = assertIs<Cylinder>(top("cylinder(h = 4, d = 10);").single())
        assertEquals(5.0, c.r1); assertEquals(5.0, c.r2)
        val cone = assertIs<Cylinder>(top("cylinder(h = 4, r1 = 3, r2 = 1);").single())
        assertEquals(3.0, cone.r1); assertEquals(1.0, cone.r2)
        val pos = assertIs<Cylinder>(top("cylinder(2, 3, 1, true);").single())
        assertEquals(3.0, pos.r1); assertEquals(1.0, pos.r2); assertTrue(pos.center)
    }

    @Test
    fun booleansKeepChildOrder() {
        val d = assertIs<Boolean3D>(top("difference() { cube(10); sphere(3); }").single())
        assertEquals(BoolOp.DIFFERENCE, d.op)
        assertIs<Cube>(d.children[0]); assertIs<Sphere>(d.children[1])
    }

    @Test
    fun disableModifierDropsSubtree() {
        assertTrue(top("*cube(1); sphere(1);").size == 1)
    }

    @Test
    fun ifElseAndTernary() {
        assertIs<Sphere>(top("if (1 > 2) cube(1); else sphere(1);").single())
        assertIs<Cube>(top("x = 5; if (x == 5) { cube(1); }").single())
    }

    @Test
    fun overridesReplaceTopLevelAssignments() {
        val nodes = top("size = 5; cube(size);", mapOf("size" to "7"))
        assertEquals(Triple(7.0, 7.0, 7.0), assertIs<Cube>(nodes.single()).size)
    }

    @Test
    fun stringOverrideAndComparison() {
        val src = """mode = "a"; if (mode == "b") cube(1); else sphere(1);"""
        assertIs<Sphere>(top(src).single())
        assertIs<Cube>(top(src, mapOf("mode" to "\"b\"")).single())
    }

    @Test
    fun trigFunctionsUseDegrees() {
        val c = assertIs<Cube>(top("cube([sin(90), cos(0), atan2(1, 1)]);").single())
        assertEquals(1.0, c.size.first, 1e-12); assertEquals(1.0, c.size.second, 1e-12); assertEquals(45.0, c.size.third, 1e-9)
    }

    @Test
    fun echoCollectsOutput() {
        val r = render("""echo("hallo", x = 2 + 1);""")
        assertEquals(listOf("""ECHO: "hallo", x = 3"""), r.echoes)
    }

    @Test
    fun unknownModuleWarnsButKeepsChildren() {
        val r = render("frobnicate() cube(1);")
        assertEquals(1, r.warnings.size)
        assertIs<Cube>((r.root as Group).children.single())
    }

    @Test
    fun syntaxErrorHasLineNumber() {
        val e = assertFailsWith<CadLangException> { render("cube(1);\ncube(;") }
        assertEquals(2, e.line)
    }

    @Test
    fun infiniteRecursionIsStopped() {
        assertFailsWith<CadLangException> { render("module a() { a(); } a();") }
        assertFailsWith<CadLangException> { render("function f(x) = f(x); cube(f(1));") }
    }

    @Test
    fun cancellationStopsEvaluation() {
        assertFailsWith<CadLangException> {
            CadLang.render("for (i=[0:100000]) cube(1);", isCancelled = { true })
        }
    }

    @Test
    fun includeAndUse() {
        val files = SourceProvider { path ->
            when (path) {
                "lib.scad" -> "k = 3; module peg() { cube(k); }"
                else -> null
            }
        }
        val inc = CadLang.render("include <lib.scad>\npeg();", files = files).root as Group
        assertEquals(Triple(3.0, 3.0, 3.0), assertIs<Cube>(inc.children.single()).size)
        val used = CadLang.render("use <lib.scad>\npeg();", files = files).root as Group
        assertEquals(Triple(3.0, 3.0, 3.0), assertIs<Cube>(used.children.single()).size)
        assertFailsWith<CadLangException> { CadLang.render("include <fehlt.scad>", files = files) }
    }

    @Test
    fun polyhedronValidatesIndices() {
        val ok = "polyhedron(points=[[0,0,0],[1,0,0],[0,1,0],[0,0,1]], faces=[[0,1,2],[0,1,3],[1,2,3],[0,2,3]]);"
        assertIs<Polyhedron>(top(ok).single())
        assertFailsWith<CadLangException> { render("polyhedron(points=[[0,0,0]], faces=[[0,1,2]]);") }
    }

    @Test
    fun extrudeAndOffsetParameters() {
        val e = assertIs<LinearExtrude>(top("linear_extrude(height = 5, center = true, scale = 0.5) square(2);").single())
        assertEquals(5.0, e.height); assertTrue(e.center); assertEquals(0.5, e.scaleX)
        val o = assertIs<Offset>(top("offset(delta = 1) square(2);").single())
        assertEquals(1.0, o.amount); assertTrue(!o.round)
        val r = assertIs<Offset>(top("offset(r = -1) square(4);").single())
        assertTrue(r.round)
    }

    @Test
    fun multmatrixAndMirror() {
        val t = assertIs<Transform>(top("mirror([1,0,0]) cube(1);").single())
        assertContentEquals(doubleArrayOf(-1.0, 0.0, 0.0), t.matrix.apply(1.0, 0.0, 0.0).toList().toDoubleArray())
        val m = assertIs<Transform>(top("multmatrix([[1,0,0,5],[0,1,0,6],[0,0,1,7],[0,0,0,1]]) cube(1);").single())
        assertEquals(Triple(5.0, 6.0, 7.0), m.matrix.apply(0.0, 0.0, 0.0))
    }

    @Test
    fun colorByNameAndVector() {
        val c = assertIs<Colored>(top("color(\"red\") cube(1);").single())
        assertEquals(listOf(1.0, 0.0, 0.0, 1.0), c.rgba)
        val v = assertIs<Colored>(top("color([0.5, 0.5, 0.5], 0.25) cube(1);").single())
        assertEquals(0.25, v.rgba[3])
    }
}
