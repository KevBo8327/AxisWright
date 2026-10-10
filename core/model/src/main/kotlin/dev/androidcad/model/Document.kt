package dev.androidcad.model

import dev.androidcad.brep.BrepException
import dev.androidcad.brep.BrepShape
import dev.androidcad.sketch.Region
import dev.androidcad.sketch.Sketch
import dev.androidcad.sketch.regionAt
import dev.androidcad.sketch.regions

/** Ergebnis von [Document.build]. [body] gehört dem Aufrufer und muss geschlossen werden. */
class BuildResult(
    val body: BrepShape?,
    val errors: Map<FeatureId, String>,
    /** Die beim Aufbau tatsächlich verwendete Ebene jeder Skizze (bei Skizzen auf Flächen aus der Fläche berechnet). */
    val sketchPlanes: Map<FeatureId, Workplane> = emptyMap(),
    /** Für jede Skizze auf einer Fläche: die Kanten der Fläche, die sich projizieren lassen (siehe [Document.addProjections]). */
    val projectable: Map<FeatureId, List<EdgeProjection.Geometry>> = emptyMap(),
    /** Aktuelle Lage der projizierten Kanten jeder Skizze (siehe [Document.applyProjections]). */
    val projected: Map<FeatureId, List<Pair<ProjectedEdge, EdgeProjection.Geometry>>> = emptyMap(),
    /** Die beim Aufbau berechnete Lage jeder Arbeitsebene ([PlaneFeature]). */
    val datumPlanes: Map<FeatureId, Workplane> = emptyMap(),
) : AutoCloseable {
    val isOk: Boolean get() = errors.isEmpty()

    /**
     * IDs der Körperflächen (wie in [dev.androidcad.brep.BrepMesh.triangleFace]), die [ref] bezeichnet. Leer, wenn die Fläche
     * nicht mehr existiert (weggeschnitten, Skizzenelement gelöscht); mehrere, wenn eine spätere Operation sie zerteilt hat.
     */
    fun faces(ref: FaceRef): List<Int> = body?.facesWithTag(ref.tag) ?: emptyList()

    /** IDs der Kanten, an denen die Flächen [a] und [b] aufeinandertreffen. */
    fun edgesBetween(a: FaceRef, b: FaceRef): List<Int> = body?.edgesBetween(a.tag, b.tag) ?: emptyList()

    override fun close() {
        body?.close()
    }
}

/**
 * Ein Modell als Feature-Historie: Skizzen und Operationen, die der Reihe nach angewendet werden.
 *
 * Skizzen und Features sind veränderlich (andere Maße, anderer Abstand …); [build] erzeugt den Körper jedes Mal
 * vollständig neu aus der Historie – das ist die parametrische Neuberechnung.
 */
class Document {
    private var nextId = 1
    private val items = ArrayList<Feature>()

    val features: List<Feature> get() = items.toList()

    /** Parameter-Tabelle (Reihenfolge zählt: ein Ausdruck darf frühere Parameter verwenden). */
    var parameters: List<Parameter> = emptyList()
        private set

    /** Welche Werte aus Ausdrücken kommen (Ausdruck je Wert); [applyParameters] überträgt sie. */
    var bindings: Map<ValueRef, String> = emptyMap()
        private set

    /** Ersetzt die Parameter-Tabelle; ungültige oder doppelte Namen werfen [IllegalArgumentException]. */
    fun setParameters(list: List<Parameter>) {
        list.forEach { p -> Parameter.nameProblem(p.name)?.let { throw IllegalArgumentException(it) } }
        require(list.map { it.name }.toSet().size == list.size) { "Parameternamen müssen eindeutig sein" }
        parameters = list
    }

    /** Lässt den Wert [ref] aus [expression] kommen (`null` = wieder fester Wert). Wirkt mit dem nächsten [applyParameters]. */
    fun bind(ref: ValueRef, expression: String?) {
        bindings = if (expression.isNullOrBlank()) bindings - ref else bindings + (ref to expression.trim())
    }

    /** Werte der Parameter (für die Anzeige und das Prüfen eines neuen Ausdrucks). */
    fun parameterValues(): ParameterResult = evaluateParameters(parameters)

    /**
     * Rechnet die Parameter aus und überträgt jeden gebundenen Ausdruck in seinen Wert (Skizzenmaß bzw. Feature). Bindungen an Maße
     * oder Features, die es nicht mehr gibt, fallen weg. Fehlerhafte Ausdrücke lassen den bisherigen Wert stehen und werden gemeldet.
     */
    fun applyParameters(): ParameterResult {
        val result = evaluateParameters(parameters)
        val errors = ArrayList(result.errors)
        val live = LinkedHashMap<ValueRef, String>()
        for ((ref, expr) in bindings) {
            val exists = when (ref) {
                is ValueRef.Dim -> (items.firstOrNull { it.id == ref.sketch } as? SketchFeature)?.sketch?.constraintEntries?.any { it.id == ref.constraint } == true
                is ValueRef.FeatureValue -> items.any { it.id == ref.feature }
            }
            if (!exists) continue
            live[ref] = expr
            try {
                applyValue(ref, dev.androidcad.lang.CadLang.evalNumber(expr, result.values))?.let { errors += "„$expr“: $it" }
            } catch (e: dev.androidcad.lang.CadLangException) {
                errors += "Ausdruck ${e.message}"
            }
        }
        bindings = live
        return ParameterResult(result.values, errors)
    }

    /**
     * Unabhängige Kopie (Skizzen werden kopiert, IDs bleiben gleich). Damit kann [build] auf einem Hintergrund-Thread
     * laufen, während das Original weiter bearbeitet wird.
     */
    fun snapshot(): Document {
        val copy = Document()
        copy.nextId = nextId
        copy.parameters = parameters
        copy.bindings = bindings
        copy.items.addAll(items.map { if (it is SketchFeature) it.copy(sketch = it.sketch.copy()) else it })
        return copy
    }

    internal val nextIdValue: Int get() = nextId

    /** Ersetzt den Inhalt (Laden aus einer Datei); prüft die Konsistenz, bei Fehler bleibt das Dokument unverändert. */
    internal fun load(nextId: Int, features: List<Feature>, parameters: List<Parameter> = emptyList(), bindings: Map<ValueRef, String> = emptyMap()) {
        val ids = features.map { it.id.value }
        require(ids.size == ids.toSet().size) { "Feature-IDs sind nicht eindeutig" }
        require(ids.all { it >= 1 && it < nextId }) { "Feature-ID außerhalb des gültigen Bereichs" }
        val seenSketches = HashSet<FeatureId>()
        for (f in features) when (f) {
            is SketchFeature -> seenSketches += f.id
            is ExtrudeFeature -> require(f.sketch in seenSketches) { "Extrusion „${f.name}“ verweist auf keine vorangehende Skizze" }
            is RevolveFeature -> require(f.sketch in seenSketches) { "Drehung „${f.name}“ verweist auf keine vorangehende Skizze" }
            is FilletFeature, is HoleFeature, is ShellFeature, is PatternFeature, is PlaneFeature, is MirrorBodyFeature -> {}
        }
        val before = HashSet<FeatureId>()
        for (f in features) {
            when (f) {
                is SketchFeature -> {
                    require(before.containsAll(f.references)) { "Skizze „${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                    f.onPlane?.let { p -> require(features.first { it.id == p } is PlaneFeature) { "Skizze „${f.name}“ liegt auf keiner Arbeitsebene" } }
                }
                is PlaneFeature -> {
                    require(before.containsAll(f.references)) { "Ebene „${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                    DatumPlane.validate(f.def)?.let { throw IllegalArgumentException("Ebene „${f.name}“: $it") }
                }
                is FilletFeature -> {
                    require(f.edges.isNotEmpty()) { "„${f.name}“ hat keine Kanten" }
                    require(before.containsAll(f.references)) { "„${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                }
                is HoleFeature -> require(before.containsAll(f.references)) { "„${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                is ShellFeature -> {
                    require(f.openFaces.isNotEmpty()) { "„${f.name}“ hat keine Öffnung" }
                    require(before.containsAll(f.references)) { "„${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                }
                is PatternFeature -> {
                    require(f.source in before) { "Muster „${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                    patternSourceProblem(features.first { it.id == f.source }, features, f.kind)?.let { throw IllegalArgumentException("Muster „${f.name}“: $it") }
                }
                is MirrorBodyFeature -> {
                    require(before.containsAll(f.references)) { "„${f.name}“ verweist auf ein Feature, das nicht davor liegt" }
                    f.datum?.let { d -> require(features.first { it.id == d } is PlaneFeature) { "„${f.name}“: Spiegelebene ist keine Arbeitsebene" } }
                    MirrorNaming.validate(f)?.let { throw IllegalArgumentException("„${f.name}“: $it") }
                }
                else -> {}
            }
            before += f.id
        }
        setParameters(parameters)
        this.nextId = nextId
        items.clear()
        items.addAll(features)
        this.bindings = bindings
    }

    fun addSketch(name: String, plane: Workplane = Workplane.XY, sketch: Sketch = Sketch()): SketchFeature =
        SketchFeature(FeatureId(nextId++), name, plane, sketch).also { items += it }

    /** Skizze auf einer ebenen Körperfläche; [plane] ist die aktuelle Ebene dieser Fläche (siehe [FacePlane.workplane]). */
    fun addSketchOnFace(name: String, face: FaceRef, plane: Workplane, sketch: Sketch = Sketch()): SketchFeature {
        require(items.any { it.id == face.feature }) { "Fläche verweist auf ein unbekanntes Feature" }
        return SketchFeature(FeatureId(nextId++), name, plane, sketch, face).also { items += it }
    }

    /** Arbeitsebene aus [def]; [plane] ist ihre aktuelle Lage (z. B. aus [DatumPlane.offset]). */
    fun addPlane(name: String, def: PlaneDef, plane: Workplane): PlaneFeature {
        DatumPlane.validate(def)?.let { throw IllegalArgumentException(it) }
        val known = items.map { it.id }.toSet()
        val f = PlaneFeature(FeatureId(nextId), name, def, plane)
        require(known.containsAll(f.references)) { "Ebene verweist auf ein unbekanntes Feature" }
        nextId++
        return f.also { items += it }
    }

    /** Skizze auf der Arbeitsebene [planeFeature]; sie übernimmt deren aktuelle Lage und folgt ihr beim Neuaufbau. */
    fun addSketchOnPlane(name: String, planeFeature: FeatureId, sketch: Sketch = Sketch()): SketchFeature {
        val p = items.firstOrNull { it.id == planeFeature } as? PlaneFeature ?: throw IllegalArgumentException("Keine Arbeitsebene: $planeFeature")
        return SketchFeature(FeatureId(nextId++), name, p.plane, sketch, onPlane = p.id).also { items += it }
    }

    fun addExtrude(
        name: String,
        sketch: SketchFeature,
        distance: Double,
        op: BodyOp = BodyOp.ADD,
        regionPoints: List<dev.androidcad.sketch.Vec2> = emptyList(),
        symmetric: Boolean = false,
    ): ExtrudeFeature {
        require(items.any { it === sketch }) { "Skizze gehört nicht zu diesem Dokument" }
        return ExtrudeFeature(FeatureId(nextId++), name, sketch.id, distance, op, regionPoints, symmetric).also { items += it }
    }

    /** Dreht Flächen der Skizze um [axis] (siehe [RevolveFeature]); [angle] in Grad. */
    fun addRevolve(
        name: String,
        sketch: SketchFeature,
        axis: RevolveAxis,
        angle: Double = 360.0,
        op: BodyOp = BodyOp.ADD,
        regionPoints: List<dev.androidcad.sketch.Vec2> = emptyList(),
        symmetric: Boolean = false,
    ): RevolveFeature {
        require(items.any { it === sketch }) { "Skizze gehört nicht zu diesem Dokument" }
        return RevolveFeature(FeatureId(nextId++), name, sketch.id, axis, angle, op, regionPoints, symmetric).also { items += it }
    }

    /** Ersetzt ein Feature gleicher ID (z. B. [ExtrudeFeature.copy] mit neuer Länge). */
    fun update(feature: Feature) {
        val i = items.indexOfFirst { it.id == feature.id }
        require(i >= 0) { "Unbekanntes Feature ${feature.id}" }
        items[i] = feature
    }

    fun feature(id: FeatureId): Feature = items.firstOrNull { it.id == id } ?: error("Feature $id existiert nicht")

    /** Benennt ein Feature um (die Skizze selbst bleibt dasselbe Objekt). */
    fun rename(id: FeatureId, name: String) {
        when (val f = feature(id)) {
            is SketchFeature -> update(f.copy(name = name))
            is ExtrudeFeature -> update(f.copy(name = name))
            is RevolveFeature -> update(f.copy(name = name))
            is FilletFeature -> update(f.copy(name = name))
            is HoleFeature -> update(f.copy(name = name))
            is ShellFeature -> update(f.copy(name = name))
            is PatternFeature -> update(f.copy(name = name))
            is PlaneFeature -> update(f.copy(name = name))
            is MirrorBodyFeature -> update(f.copy(name = name))
        }
    }

    /**
     * Vervielfältigt eine Extrusion oder Bohrung ([source]) nach [kind] (siehe [PatternFeature]). Ungültige Parameter werfen
     * [IllegalArgumentException].
     */
    fun addPattern(name: String, source: FeatureId, kind: PatternKind, withFillets: Boolean = false): PatternFeature {
        val src = items.firstOrNull { it.id == source } ?: throw IllegalArgumentException("Unbekanntes Feature $source")
        patternSourceProblem(src, items, kind)?.let { throw IllegalArgumentException(it) }
        PatternGeometry.validate(kind)?.let { throw IllegalArgumentException(it) }
        return PatternFeature(FeatureId(nextId++), name, source, kind, withFillets).also { items += it }
    }

    /** Das ursprüngliche Feature eines Musters (bei einem Muster von Mustern die Quelle des inneren Musters). */
    fun patternRoot(f: PatternFeature): FeatureId? = when (val src = items.firstOrNull { it.id == f.source }) {
        is PatternFeature -> src.source
        null -> null
        else -> src.id
    }

    /**
     * Verrundungen und Fasen, die ein Muster mit [PatternFeature.withFillets] auf seine Kopien überträgt: alle, die in der Historie
     * zwischen dem ursprünglichen Feature und dem (inneren) Muster liegen und deren Kanten jeweils an mindestens einer Fläche des
     * ursprünglichen Features hängen (z. B. die Oberkante eines Zapfens, die Senkung einer Bohrung).
     */
    fun patternFillets(f: PatternFeature): List<FilletFeature> {
        val root = patternRoot(f) ?: return emptyList()
        val until = (items.firstOrNull { it.id == f.source } as? PatternFeature)?.id ?: f.id
        val from = items.indexOfFirst { it.id == root }
        val to = items.indexOfFirst { it.id == until }
        if (from < 0 || to <= from) return emptyList()
        return items.subList(from + 1, to).filterIsInstance<FilletFeature>()
            .filter { fl -> fl.edges.isNotEmpty() && fl.edges.all { it.a.feature == root || it.b.feature == root } }
    }

    /**
     * Für jede Kopie eines Musters die Abbildung „Element des ursprünglichen Features → Element in dieser Kopie“ (Namen der Kopie,
     * siehe [PatternGeometry.copyElement] bzw. [PatternGeometry.nestedElement]).
     */
    private fun copyElementMaps(f: PatternFeature): List<(Long) -> Long> {
        val inner = items.firstOrNull { it.id == f.source } as? PatternFeature
        val outer = 1..f.kind.copies
        return if (inner == null) PatternGeometry.instances(f.kind).map { k -> { e: Long -> (k shl PatternGeometry.INSTANCE_SHIFT) or e } }
        else outer.flatMap { j -> (0..inner.kind.copies).map { k -> { e: Long -> PatternGeometry.nestedElement(j, k, e) } } }
    }

    /**
     * Wendet die Verrundungen/Fasen der Quelle auf alle Kopien an. Kanten einer Kopie, die es im Körper nicht gibt (Kopie außerhalb des
     * Körpers), werden übersprungen. [body] gehört weiter dem Aufrufer; zurück kommt ein neuer Körper (oder [body], wenn nichts zu tun war).
     */
    private fun applyPatternFillets(body: BrepShape, f: PatternFeature): BrepShape {
        val root = patternRoot(f) ?: return body
        val fillets = patternFillets(f)
        if (fillets.isEmpty()) return body
        fun map(ref: FaceRef, elem: (Long) -> Long) = if (ref.feature == root) FaceRef(f.id, ref.role, elem(ref.element)) else ref
        var current = body
        try {
            for (elem in copyElementMaps(f)) for ((fi, fl) in fillets.withIndex()) {
                require(fl.size > 0.0 && fl.size.isFinite()) { "Größe der Verrundung „${fl.name}“ muss größer als 0 sein" }
                val ids = LinkedHashMap<Int, Int>() // Kanten-ID → Index der Kante in der Verrundung
                fl.edges.forEachIndexed { i, ref ->
                    current.edgesBetween(map(ref.a, elem).tag, map(ref.b, elem).tag).forEach { ids.putIfAbsent(it, i) }
                }
                if (ids.isEmpty()) continue
                val tags = ids.values.map { faceTag(f.id, FaceRole.ROUND, elem(fi * 1024L + it)) }
                val next = when (fl.kind) {
                    EdgeKind.ROUND -> current.fillet(ids.keys, fl.size, tags)
                    EdgeKind.CHAMFER -> current.chamfer(ids.keys, fl.size, tags)
                }
                if (current !== body) current.close()
                current = next
            }
            return current
        } catch (e: Throwable) {
            if (current !== body) current.close()
            throw e
        }
    }

    /** Ändert die Definition einer Arbeitsebene; [plane] ist die neue Lage (falls schon bekannt, sonst die bisherige). */
    fun updatePlane(id: FeatureId, def: PlaneDef, plane: Workplane? = null) {
        val f = feature(id) as? PlaneFeature ?: throw IllegalArgumentException("Keine Arbeitsebene: $id")
        DatumPlane.validate(def)?.let { throw IllegalArgumentException(it) }
        update(f.copy(def = def, plane = plane ?: f.plane))
    }

    /** Legt die Skizze auf eine andere Ebene; Features, die auf ihr aufbauen, folgen beim nächsten [build]. */
    fun setPlane(id: FeatureId, plane: Workplane) {
        val f = sketch(id)
        update(f.copy(plane = plane))
    }

    /**
     * Projiziert Körperkanten in die Skizze [id] (siehe [BuildResult.projectable]): legt für jede noch nicht projizierte Kante ein
     * festes Konstruktionselement an und merkt sich die Kante, damit das Element beim Neuaufbau dem Körper folgt.
     * @return Anzahl der neu angelegten Elemente
     */
    fun addProjections(id: FeatureId, edges: List<EdgeProjection.Geometry>): Int {
        val f = sketch(id)
        val have = f.projections.map { it.edge }.toSet()
        val fresh = edges.filter { it.edge !in have }.distinctBy { it.edge }
        val known = items.map { it.id }.toSet()
        require(fresh.all { it.edge.a.feature in known && it.edge.b.feature in known }) { "Kante verweist auf ein unbekanntes Feature" }
        if (fresh.isEmpty()) return 0
        update(f.copy(projections = f.projections + fresh.map { EdgeProjection.add(f.sketch, it) }))
        return fresh.size
    }

    /**
     * Verrundung bzw. Fase an [edges] (Kanten, bezeichnet über ihre Nachbarflächen). Die referenzierten Features müssen
     * schon in der Historie stehen.
     */
    fun addFillet(name: String, edges: List<EdgeRef>, size: Double, kind: EdgeKind = EdgeKind.ROUND): FilletFeature {
        require(edges.isNotEmpty()) { "Mindestens eine Kante nötig" }
        val known = items.map { it.id }.toSet()
        require(edges.all { it.a.feature in known && it.b.feature in known }) { "Kante verweist auf ein unbekanntes Feature" }
        return FilletFeature(FeatureId(nextId++), name, edges, size, kind).also { items += it }
    }

    /** Höhlt den Körper aus: [openFaces] werden zu Öffnungen, die Wand ist [thickness] mm stark (siehe [ShellFeature]). */
    fun addShell(name: String, openFaces: List<FaceRef>, thickness: Double): ShellFeature {
        require(openFaces.isNotEmpty()) { "Mindestens eine Fläche als Öffnung nötig" }
        val known = items.map { it.id }.toSet()
        require(openFaces.all { it.feature in known }) { "Fläche verweist auf ein unbekanntes Feature" }
        return ShellFeature(FeatureId(nextId++), name, openFaces, thickness).also { items += it }
    }

    /** Bohrung in die Fläche [face]; die Position wird beim Bauen auf die Ebene der Fläche projiziert. [depth] `null` = durchgehend. */
    fun addHole(
        name: String, face: FaceRef, x: Double, y: Double, z: Double, diameter: Double, depth: Double? = null,
        anchors: Pair<EdgeDistance, EdgeDistance>? = null,
    ): HoleFeature {
        val known = items.map { it.id }.toSet()
        require(face.feature in known) { "Fläche verweist auf ein unbekanntes Feature" }
        require(anchors == null || anchors.toList().all { it.edge.a.feature in known && it.edge.b.feature in known }) { "Referenzkante verweist auf ein unbekanntes Feature" }
        return HoleFeature(FeatureId(nextId++), name, face, x, y, z, diameter, depth, anchors).also { items += it }
    }

    /**
     * Entfernt das Feature und alles, was von ihm abhängt: Extrusionen einer entfernten Skizze und Verrundungen/Fasen, die
     * Flächen eines entfernten Features referenzieren (auch über mehrere Stufen).
     */
    fun remove(id: FeatureId) {
        val gone = HashSet<FeatureId>().apply { add(id) }
        do {
            val before = gone.size
            for (f in items) when (f) {
                is ExtrudeFeature -> if (f.sketch in gone) gone += f.id
                is RevolveFeature -> if (f.sketch in gone) gone += f.id
                is FilletFeature -> if (f.references.any { it in gone }) gone += f.id
                is HoleFeature -> if (f.references.any { it in gone }) gone += f.id
                is ShellFeature -> if (f.references.any { it in gone }) gone += f.id
                is PatternFeature -> if (f.source in gone) gone += f.id
                is PlaneFeature -> if (f.references.any { it in gone }) gone += f.id
                is MirrorBodyFeature -> if (f.references.any { it in gone }) gone += f.id
                is SketchFeature -> if ((f.onFace != null && f.onFace.feature in gone) || (f.onPlane != null && f.onPlane in gone)) gone += f.id
            }
        } while (gone.size != before)
        items.removeAll { it.id in gone }
        bindings = bindings.filterKeys { r -> (if (r is ValueRef.Dim) r.sketch else (r as ValueRef.FeatureValue).feature) !in gone }
        // Projizierte Kanten entfernter Features: die Skizze bleibt, die Elemente darin bleiben als feste Konstruktionsgeometrie
        for (f in items.filterIsInstance<SketchFeature>()) {
            val kept = f.projections.filter { it.edge.a.feature !in gone && it.edge.b.feature !in gone }
            if (kept.size != f.projections.size) update(f.copy(projections = kept))
        }
    }

    fun sketch(id: FeatureId): SketchFeature = items.filterIsInstance<SketchFeature>().firstOrNull { it.id == id }
        ?: error("Skizze $id existiert nicht")

    /**
     * Wendet alle Features der Reihe nach an. Fehler eines Features (widersprüchliche Skizze, keine Fläche gefunden,
     * fehlgeschlagene Boolesche Operation) stoppen den Aufbau nicht, sondern werden in [BuildResult.errors] gesammelt.
     */
    fun build(): BuildResult {
        var body: BrepShape? = null
        val errors = LinkedHashMap<FeatureId, String>()
        val planes = LinkedHashMap<FeatureId, Workplane>()
        val projectable = LinkedHashMap<FeatureId, List<EdgeProjection.Geometry>>()
        val projected = LinkedHashMap<FeatureId, List<Pair<ProjectedEdge, EdgeProjection.Geometry>>>()
        val datum = LinkedHashMap<FeatureId, Workplane>()
        try {
            for (f in items) when (f) {
                is SketchFeature -> {
                    var plane = f.plane
                    if (f.onPlane != null) {
                        val resolved = datum[f.onPlane]
                        if (resolved != null) plane = resolved
                        else errors[f.id] = "Skizze „${f.name}“: die Arbeitsebene, auf der sie liegt, fehlt"
                    }
                    if (f.onFace != null) {
                        val resolved = body?.let { facePlane(it, f.onFace) }
                        if (resolved != null) plane = resolved
                        else errors[f.id] = "Skizze „${f.name}“: die Fläche, auf der sie liegt, existiert nicht mehr oder ist nicht eben"
                    }
                    planes[f.id] = plane
                    if (f.onFace != null && body != null) {
                        projectable[f.id] = EdgeProjection.edgesOf(body, plane, f.onFace)
                        syncProjections(body, f, plane, projected)?.let { errors[f.id] = it }
                    }
                    val r = f.sketch.solve()
                    if (!r.isSolved) errors[f.id] = "Skizze „${f.name}“: Constraints widersprechen sich"
                }
                is ExtrudeFeature -> attempt(f, errors) {
                    body = combine(body, extrudeTool(f, planes), f.op)
                }
                is RevolveFeature -> attempt(f, errors) {
                    body = combine(body, revolveTool(f, planes), f.op)
                }
                is FilletFeature -> attempt(f, errors) {
                    body = applyEdges(body, f)
                }
                is ShellFeature -> attempt(f, errors) {
                    body = applyShell(body, f)
                }
                is HoleFeature -> attempt(f, errors) {
                    body = combine(body, holeTool(body, f), BodyOp.CUT)
                }
                is PlaneFeature -> {
                    val resolved = body?.let { DatumPlane.resolve(it, f.def) }
                    if (resolved == null) errors[f.id] = "Ebene „${f.name}“: die Fläche oder Kante, aus der sie entsteht, existiert nicht mehr"
                    // Rückfall auf die zuletzt bekannte Lage, damit Skizzen darauf weiter gebaut werden
                    datum[f.id] = resolved ?: f.plane
                }
                is MirrorBodyFeature -> attempt(f, errors) {
                    // die Naht an der Spiegelebene entfernt combine (Flächen in einer Ebene werden eine)
                    body = combine(body, mirrorTool(body, f, datum), BodyOp.ADD)
                }
                is PatternFeature -> attempt(f, errors) {
                    body = combine(body, patternTool(body, f, planes), patternOp(f))
                    if (f.withFillets) {
                        val withRounds = try {
                            applyPatternFillets(body!!, f)
                        } catch (e: BrepException) {
                            errors[f.id] = "„${f.name}“: Verrundungen der Kopien fehlgeschlagen – ${e.message}"
                            body
                        }
                        if (withRounds !== body) { body?.close(); body = withRounds }
                    }
                }
            }
        } catch (e: Throwable) {
            body?.close()
            throw e
        }
        return BuildResult(body, errors, planes, projectable, projected, datum)
    }

    /** Führt den Aufbauschritt von [f] aus; scheitert er, landet der Grund in [errors] und der Aufbau geht mit dem nächsten Feature weiter. */
    private inline fun attempt(f: Feature, errors: MutableMap<FeatureId, String>, step: () -> Unit) {
        try {
            step()
        } catch (e: BrepException) {
            errors[f.id] = "„${f.name}“: ${e.message}"
        } catch (e: IllegalArgumentException) {
            errors[f.id] = "„${f.name}“: ${e.message}"
        }
    }

    /**
     * Überträgt die beim Aufbau gefundene Lage der projizierten Kanten ([BuildResult.projected]) in die Skizzen dieses Dokuments
     * (der Aufbau läuft auf einer Kopie) und löst sie neu, damit abhängige Geometrie mitwandert. Projektionen, deren Element in der
     * Skizze nicht mehr existiert, werden dabei vergessen.
     * @return `true`, wenn sich dadurch etwas geändert hat
     */
    fun applyProjections(projected: Map<FeatureId, List<Pair<ProjectedEdge, EdgeProjection.Geometry>>>): Boolean {
        var changed = false
        for ((id, list) in projected) {
            var f = items.firstOrNull { it.id == id } as? SketchFeature ?: continue
            val before = fingerprint(f.sketch)
            for ((p, g) in list) EdgeProjection.update(f.sketch, p, g)
            if (fingerprint(f.sketch) != before) {
                f.sketch.solve()
                changed = true
            }
            val live = f.projections.filter { EdgeProjection.isLive(f.sketch, it) }
            if (live.size != f.projections.size) {
                f = f.copy(projections = live)
                update(f)
                changed = true
            }
        }
        return changed
    }

    private fun fingerprint(s: Sketch): List<Double> =
        s.pointIds.flatMap { s.point(it).let { p -> listOf(p.x, p.y) } } + s.circleIds.map { s.circle(it).radius }

    /**
     * Setzt die projizierten Kanten der Skizze auf ihre aktuelle Lage im Körper. Liefert eine Fehlermeldung, wenn eine Kante nicht
     * mehr gefunden wird (ihr Element bleibt dann an der letzten Stelle stehen); `null` bei Erfolg.
     */
    private fun syncProjections(
        body: BrepShape, f: SketchFeature, plane: Workplane,
        found: MutableMap<FeatureId, List<Pair<ProjectedEdge, EdgeProjection.Geometry>>>,
    ): String? {
        var missing = 0
        val ok = ArrayList<Pair<ProjectedEdge, EdgeProjection.Geometry>>()
        for (p in f.projections) {
            if (!EdgeProjection.isLive(f.sketch, p)) continue // vom Nutzer gelöscht: keine Projektion mehr, kein Fehler
            val g = EdgeProjection.find(body, plane, p.edge)
            if (g == null || !EdgeProjection.update(f.sketch, p, g)) missing++ else ok += p to g
        }
        found[f.id] = ok
        return if (missing == 0) null else "Skizze „${f.name}“: $missing projizierte Kante(n) existieren nicht mehr oder liegen nicht mehr in der Ebene"
    }

    /** Ebene der benannten Fläche im aktuellen Körper (die erste ebene, falls sie zerteilt wurde); `null`, wenn es sie nicht gibt. */
    private fun facePlane(body: BrepShape, face: FaceRef): Workplane? {
        val ids = body.facesWithTag(face.tag)
        if (ids.isEmpty()) return null
        val planes = body.planarFaces()
        return ids.firstNotNullOfOrNull { planes.getOrNull(it - 1) }?.let(FacePlane::workplane)
    }

    /**
     * Werkzeug des Musters: das Werkzeug der Quelle (Extrusion bzw. Bohrung) wird je Kopie verschoben, gedreht oder gespiegelt,
     * mit Flächennamen der Kopie versehen und mit den übrigen zu **einem** Werkzeug vereinigt (ohne das Original – das ist schon
     * im Körper).
     */
    private fun patternTool(body: BrepShape?, f: PatternFeature, planes: Map<FeatureId, Workplane>): BrepShape {
        if (body == null) throw BrepException("es gibt noch keinen Körper")
        PatternGeometry.validate(f.kind)?.let { throw IllegalArgumentException(it) }
        val src = items.firstOrNull { it.id == f.source }
        if (src != null) patternSourceProblem(src, items, f.kind)?.let { throw BrepException(it) }
        val ids = PatternGeometry.instances(f.kind)
        // Muster eines Musters: kopiert wird dessen gesamtes Ergebnis (Original + Kopien), die Namen bekommen beide Kopienummern
        val nested = src is PatternFeature
        val original = when (src) {
            is ExtrudeFeature -> extrudeTool(src, planes)
            is RevolveFeature -> revolveTool(src, planes)
            is HoleFeature -> holeTool(body, src)
            is PatternFeature -> patternResultTool(body, src, planes)
            else -> throw BrepException("das vervielfältigte Feature existiert nicht mehr")
        }
        var all: BrepShape? = null
        try {
            PatternGeometry.placements(f.kind).forEachIndexed { index, placement ->
                val moved = when (placement) {
                    is Placement.Translate -> original.translate(placement.dx, placement.dy, placement.dz)
                    is Placement.Rotate -> original.transform(placement.matrix3x4)
                    is Placement.Reflect -> original.mirror(
                        placement.origin.x, placement.origin.y, placement.origin.z,
                        placement.normal.x, placement.normal.y, placement.normal.z,
                    )
                }
                val copy = moved.use {
                    it.retag { tag -> if (nested) PatternGeometry.nestedCopyTag(tag, f.id, index + 1) else PatternGeometry.copyTag(tag, f.id, ids[index]) }
                }
                all = all?.let { prev -> prev.use { p -> copy.use { c -> p.union(c) } } } ?: copy
            }
            return all ?: throw BrepException("das Muster hat keine Kopien")
        } catch (e: Throwable) {
            all?.close()
            throw e
        } finally {
            original.close()
        }
    }

    /** Spiegelbild des bisherigen Körpers an der Ebene von [f], mit den Flächennamen der Spiegelung ([MirrorNaming]). */
    private fun mirrorTool(body: BrepShape?, f: MirrorBodyFeature, datum: Map<FeatureId, Workplane>): BrepShape {
        if (body == null) throw BrepException("es gibt noch keinen Körper, der gespiegelt werden könnte")
        MirrorNaming.validate(f)?.let { throw IllegalArgumentException(it) }
        val (o, n) = if (f.datum != null) {
            val p = datum[f.datum] ?: throw BrepException("die Arbeitsebene, an der gespiegelt wird, fehlt")
            p.origin to p.normal
        } else f.origin to f.normal
        return body.mirror(o.x, o.y, o.z, n.x, n.y, n.z).use { it.retag { tag -> MirrorNaming.mirrorTag(tag, f.id) } }
    }

    /** Spiegelt den ganzen bisherigen Körper an einer Ebene (siehe [MirrorBodyFeature]). */
    fun addMirrorBody(name: String, origin: Vec3, normal: Vec3, datum: FeatureId? = null): MirrorBodyFeature {
        val f = MirrorBodyFeature(FeatureId(nextId), name, origin, normal, datum)
        if (datum != null) require(items.firstOrNull { it.id == datum } is PlaneFeature) { "Keine Arbeitsebene: $datum" }
        MirrorNaming.validate(f)?.let { throw IllegalArgumentException(it) }
        nextId++
        return f.also { items += it }
    }

    /** Gesamtes Ergebnis eines einfachen Musters als Werkzeug: Werkzeug der Quelle plus alle Kopien (Quelle eines Musters von Mustern). */
    private fun patternResultTool(body: BrepShape, p: PatternFeature, planes: Map<FeatureId, Workplane>): BrepShape {
        val base = when (val src = items.firstOrNull { it.id == p.source }) {
            is ExtrudeFeature -> extrudeTool(src, planes)
            is RevolveFeature -> revolveTool(src, planes)
            is HoleFeature -> holeTool(body, src)
            is PatternFeature -> throw BrepException("Muster lassen sich nur eine Ebene tief verschachteln")
            else -> throw BrepException("das vervielfältigte Feature existiert nicht mehr")
        }
        val copies = try { patternTool(body, p, planes) } catch (e: Throwable) { base.close(); throw e }
        return base.use { b -> copies.use { c -> b.union(c) } }
    }

    /** Operation eines Musters: die des ursprünglichen Features, auch über ein inneres Muster hinweg (Bohrungen tragen ab). */
    private fun patternOp(f: PatternFeature): BodyOp = when (val src = items.firstOrNull { it.id == f.source }) {
        is ExtrudeFeature -> src.op
        is RevolveFeature -> src.op
        is PatternFeature -> patternOp(src)
        else -> BodyOp.CUT
    }

    /** Alle ausgewählten Flächen der Skizze, extrudiert und in den Raum gestellt, vereinigt zu einem Werkzeugkörper. */
    private fun extrudeTool(f: ExtrudeFeature, planes: Map<FeatureId, Workplane>): BrepShape =
        sketchTool(f.sketch, f.regionPoints, planes) { region ->
            BrepShape.extrude(
                region.toProfile(f.id), f.distance, f.symmetric,
                bottomTag = faceTag(f.id, FaceRole.BOTTOM), topTag = faceTag(f.id, FaceRole.TOP),
            )
        }

    /** Ausgewählte Flächen der Skizze, um die Achse gedreht und in den Raum gestellt, vereinigt zu einem Werkzeugkörper. */
    private fun revolveTool(f: RevolveFeature, planes: Map<FeatureId, Workplane>): BrepShape {
        val sk = sketch(f.sketch)
        val axis = RevolveGeometry.resolve(sk.sketch, f.axis) ?: throw IllegalArgumentException("die Drehachse (Linie in „${sk.name}“) existiert nicht mehr")
        return sketchTool(f.sketch, f.regionPoints, planes, check = { regions ->
            RevolveGeometry.problem(regions, axis)?.let { throw IllegalArgumentException(it) }
        }) { region ->
            BrepShape.revolve(
                region.toProfile(f.id), axis.point.x, axis.point.y, axis.direction.x, axis.direction.y, f.angle, f.symmetric,
                startTag = faceTag(f.id, FaceRole.BOTTOM), endTag = faceTag(f.id, FaceRole.TOP),
            )
        }
    }

    /**
     * Werkzeug eines Skizzen-Features: die Flächen der Skizze [sketchId] an den [regionPoints] (leer = alle), je Fläche mit [piece] in der
     * Skizzenebene gebaut ([check] prüft vorher), in den Raum gestellt und zu einem Körper vereinigt.
     */
    private fun sketchTool(
        sketchId: FeatureId, regionPoints: List<dev.androidcad.sketch.Vec2>, planes: Map<FeatureId, Workplane>,
        check: (List<Region>) -> Unit = {},
        piece: (Region) -> BrepShape,
    ): BrepShape {
        val sk = sketch(sketchId)
        val plane = planes[sketchId] ?: sk.plane
        val regions: List<Region> =
            if (regionPoints.isEmpty()) sk.sketch.regions().regions
            else regionPoints.mapNotNull { sk.sketch.regionAt(it.x, it.y) }.distinct()
        require(regions.isNotEmpty()) { "keine geschlossene Fläche in „${sk.name}“ gefunden" }
        check(regions)
        var tool: BrepShape? = null
        try {
            for (region in regions) {
                val placed = piece(region).use { it.transform(plane.matrix3x4()) }
                tool = tool?.let { prev -> prev.use { p -> placed.use { p.union(it) } } } ?: placed
            }
            return tool!!
        } catch (e: Throwable) {
            tool?.close()
            throw e
        }
    }

    /**
     * Verknüpft Körper und Werkzeug. [tool] wird immer verbraucht; [body] nur bei Erfolg – schlägt die Operation fehl,
     * bleibt der bisherige Körper offen und unverändert, damit spätere Features weiterarbeiten können.
     *
     * Danach werden Flächen zusammengeführt, die auf derselben Fläche liegen (z. B. die Seiten zweier bündiger Blöcke) – sonst
     * bliebe eine Naht, und Verrundungen endeten an ihr. Die vereinte Fläche behält alle Namen (ältester als Hauptname), Bezüge
     * darauf bleiben gültig. Gelingt das Zusammenführen nicht, bleibt das Ergebnis mit Naht.
     */
    private fun combine(body: BrepShape?, tool: BrepShape, op: BodyOp): BrepShape {
        if (body == null) {
            if (op == BodyOp.CUT) {
                tool.close()
                throw BrepException("es gibt noch keinen Körper, von dem abgetragen werden könnte")
            }
            return tool
        }
        val result = try {
            when (op) {
                BodyOp.ADD -> body.union(tool)
                BodyOp.CUT -> body.cut(tool)
            }
        } finally {
            tool.close()
        }
        body.close()
        val joined = try { result.unifyFaces() } catch (e: BrepException) { return result }
        result.close()
        return joined
    }

    /**
     * Zylinder als Werkzeug für die Bohrung: Die Fläche wird über ihren Namen gefunden (bei mehreren Teilstücken die ebene,
     * die dem Punkt am nächsten liegt), der Punkt auf ihre Ebene projiziert; der Zylinder beginnt knapp außerhalb und läuft
     * entgegen der Außennormalen in den Körper.
     */
    private fun holeTool(body: BrepShape?, f: HoleFeature): BrepShape {
        if (body == null) throw BrepException("es gibt noch keinen Körper")
        require(f.diameter > 0.0 && f.diameter.isFinite()) { "Durchmesser muss größer als 0 sein" }
        require(f.depth == null || (f.depth > 0.0 && f.depth.isFinite())) { "Tiefe muss größer als 0 sein" }
        val ids = body.facesWithTag(f.face.tag)
        if (ids.isEmpty()) throw BrepException("die gewählte Fläche existiert nicht mehr (verändert oder entfernt)")
        val planes = body.planarFaces()
        val plane = ids.mapNotNull { planes.getOrNull(it - 1) }.minByOrNull { kotlin.math.abs(it.distanceTo(f.x, f.y, f.z)) }
            ?: throw BrepException("die gewählte Fläche ist nicht eben")
        val n = Vec3(plane.normal.first, plane.normal.second, plane.normal.third)
        val onFace = f.anchors?.let { anchoredPoint(body, plane, n, it) } ?: run {
            val d = plane.distanceTo(f.x, f.y, f.z)
            Vec3(f.x, f.y, f.z) + n * -d // fester Punkt, auf die Ebene projiziert
        }
        val lead = 1.0 // Zylinder beginnt 1 mm außerhalb, damit er die Fläche sicher durchstößt
        val origin = onFace + n * lead
        val helper = if (kotlin.math.abs(n.z) < 0.9) Vec3(0.0, 0.0, 1.0) else Vec3(1.0, 0.0, 0.0)
        val wp = Workplane(origin, n cross helper, n * -1.0)
        val profile = dev.androidcad.brep.Profile(listOf(dev.androidcad.brep.ProfileSegment.Circle(0.0, 0.0, f.diameter / 2, faceTag(f.id, FaceRole.SIDE, 1))))
        val length = (f.depth ?: THROUGH_ALL) + lead
        return BrepShape.extrude(profile, length, bottomTag = faceTag(f.id, FaceRole.BOTTOM), topTag = faceTag(f.id, FaceRole.TOP))
            .use { it.transform(wp.matrix3x4()) }
    }

    /** Position aus den zwei Kantenabständen: findet die geraden Referenzkanten in der Ebene der Fläche und löst nach dem Punkt auf. */
    private fun anchoredPoint(body: BrepShape, plane: dev.androidcad.brep.PlanarFace, n: Vec3, anchors: Pair<EdgeDistance, EdgeDistance>): Vec3 {
        val infos = body.edges()
        fun v(t: Triple<Double, Double, Double>) = Vec3(t.first, t.second, t.third)
        fun line(a: EdgeDistance): HolePlacement.Line {
            val ids = body.edgesBetween(a.edge.a.tag, a.edge.b.tag)
            if (ids.isEmpty()) throw BrepException("eine Referenzkante der Bohrung existiert nicht mehr (Fläche verändert oder entfernt)")
            val inPlane = ids.mapNotNull { id -> infos.getOrNull(id - 1) }.filter { // edges() in ID-Reihenfolge
                it.start.let { s -> kotlin.math.abs(plane.distanceTo(s.first, s.second, s.third)) } < 1e-4 &&
                    it.end.let { e -> kotlin.math.abs(plane.distanceTo(e.first, e.second, e.third)) } < 1e-4
            }
            val e = inPlane.firstOrNull() ?: throw BrepException("eine Referenzkante liegt nicht in der gewählten Fläche")
            if (!e.isLine) throw BrepException("Referenzkanten müssen gerade sein")
            return HolePlacement.Line(v(e.start), v(e.end))
        }
        return HolePlacement.solve(v(plane.origin), n, line(anchors.first), anchors.first.distance, line(anchors.second), anchors.second.distance)
            ?: throw BrepException("die beiden Referenzkanten der Bohrung sind parallel")
    }

    /** Findet die Öffnungen über ihre Namen und höhlt den Körper aus. Bei Fehler bleibt [body] unverändert. */
    private fun applyShell(body: BrepShape?, f: ShellFeature): BrepShape {
        if (body == null) throw BrepException("es gibt noch keinen Körper")
        require(f.thickness > 0.0 && f.thickness.isFinite()) { "Wandstärke muss größer als 0 sein" }
        val ids = f.openFaces.flatMap { ref ->
            body.facesWithTag(ref.tag).ifEmpty { throw BrepException("eine gewählte Fläche existiert nicht mehr (verändert oder entfernt)") }
        }.distinct()
        val result = body.shell(ids, f.thickness) { tag -> faceTag(f.id, FaceRole.SIDE, ShellFeature.innerElement(tag)) }
        body.close()
        return result
    }

    /** Findet die Kanten über ihre Nachbarflächen und rundet/fast sie. Bei Fehler bleibt [body] unverändert. */
    private fun applyEdges(body: BrepShape?, f: FilletFeature): BrepShape {
        if (body == null) throw BrepException("es gibt noch keinen Körper")
        require(f.size > 0.0 && f.size.isFinite()) { "Größe muss größer als 0 sein" }
        val ids = LinkedHashMap<Int, Int>() // Kanten-ID → Index der Referenz
        f.edges.forEachIndexed { i, ref ->
            val found = body.edgesBetween(ref.a.tag, ref.b.tag)
            if (found.isEmpty()) throw BrepException("eine gewählte Kante existiert nicht mehr (Fläche verändert oder entfernt)")
            for (id in found) ids.putIfAbsent(id, i)
        }
        val tags = ids.values.map { faceTag(f.id, FaceRole.ROUND, it) }
        val result = when (f.kind) {
            EdgeKind.ROUND -> body.fillet(ids.keys, f.size, tags)
            EdgeKind.CHAMFER -> body.chamfer(ids.keys, f.size, tags)
        }
        body.close()
        return result
    }

    private companion object {
        /**
         * Warum [src] nicht als Quelle eines Musters taugt (`null` = taugt): erlaubt sind Extrusionen, Bohrungen und einfache Muster
         * (deren Quelle selbst kein Muster ist – eine Verschachtelungsebene, siehe [PatternGeometry.nestedCopyTag]).
         */
        fun patternSourceProblem(src: Feature, all: List<Feature>, kind: PatternKind? = null): String? = when (src) {
            is ExtrudeFeature, is RevolveFeature, is HoleFeature -> null
            is PatternFeature -> when {
                all.firstOrNull { it.id == src.source } is PatternFeature -> "Muster lassen sich nur eine Ebene tief verschachteln"
                PatternGeometry.isTwoWay(src.kind) -> "Ein Raster (Muster mit zwei Richtungen) lässt sich nicht weiter vervielfältigen"
                kind != null && PatternGeometry.isTwoWay(kind) -> "Ein Muster von Mustern hat nur eine Richtung"
                else -> null
            }
            else -> "Nur Extrusionen, Drehungen, Bohrungen und Muster lassen sich vervielfältigen"
        }

        /** Länge des Bohrwerkzeugs für „durchgehend“ (mm) – länger als jedes Bauteil. */
        const val THROUGH_ALL = 100_000.0
    }
}
