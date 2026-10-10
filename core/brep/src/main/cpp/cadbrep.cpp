// JNI-Brücke zu OpenCascade (B-Rep): Skizzenprofil → Körper, Booleans, Verrundung, Tessellierung.
//
// Körper werden in einer nativen Registry gehalten und über long-Handles angesprochen; die Kotlin-Seite
// (BrepShape) gibt sie per close() frei. Flächen und Kanten werden über ihren 1-basierten Index in
// TopExp::MapShapes angesprochen – dieser Index ist nur innerhalb **eines** Körpers stabil.
//
// Stabile Flächennamen („Topological Naming“): Jeder Körper trägt je Fläche einen frei wählbaren 64-Bit-Namen
// (0 = ohne Namen). Beim Extrudieren vergibt der Aufrufer Namen für Seiten-, Boden- und Deckfläche; Boolesche
// Operationen, Transformationen und Verrundungen reichen sie über die OCCT-Änderungshistorie (Modified/Generated/
// IsDeleted) an die Ergebnisflächen weiter. Kanten werden über die Namen ihrer beiden Nachbarflächen gefunden.
//
// Profil-Kodierung (DoubleArray): [nWires, { nSegs, { type, params... }... }...]; erste Kontur = außen,
// weitere = Löcher. type 0 = Linie (x1 y1 x2 y2), 1 = Bogen (x1 y1 x2 y2 cx cy ccw), 2 = Kreis (cx cy r).

#include <jni.h>

#include <BRepAdaptor_Curve.hxx>
#include <BRepAdaptor_Surface.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeVertex.hxx>
#include <BRepExtrema_DistShapeShape.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepFilletAPI_MakeChamfer.hxx>
#include <BRepFilletAPI_MakeFillet.hxx>
#include <BRepGProp.hxx>
#include <IFSelect_ReturnStatus.hxx>
#include <STEPControl_Writer.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepOffsetAPI_MakeThickSolid.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepPrimAPI_MakeRevol.hxx>
#include <gp_Ax1.hxx>
#include <BRep_Tool.hxx>
#include <GCPnts_AbscissaPoint.hxx>
#include <GeomAbs_SurfaceType.hxx>
#include <GCPnts_TangentialDeflection.hxx>
#include <GProp_GProps.hxx>
#include <Poly_Triangulation.hxx>
#include <ShapeFix_Face.hxx>
#include <ShapeUpgrade_UnifySameDomain.hxx>
#include <BRepTools_History.hxx>
#include <Standard_Failure.hxx>
#include <TopExp.hxx>
#include <TopTools_IndexedDataMapOfShapeListOfShape.hxx>
#include <TopTools_ListIteratorOfListOfShape.hxx>
#include <TopLoc_Location.hxx>
#include <TopTools_IndexedMapOfShape.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Face.hxx>
#include <TopoDS_Shape.hxx>
#include <TopoDS_Wire.hxx>
#include <gp_Ax2.hxx>
#include <gp_Circ.hxx>
#include <gp_Pln.hxx>
#include <gp_Pnt.hxx>
#include <gp_Trsf.hxx>
#include <gp_Vec.hxx>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

using Aliases = std::vector<std::vector<jlong>>;

constexpr double kPi = 3.14159265358979323846;

struct Entry {
  TopoDS_Shape shape;
  std::vector<jlong> faceTags;  // je Fläche (Index = TopExp::MapShapes-Index − 1), 0 = ohne Namen
  Aliases faceAliases;          // je Fläche weitere Namen (z. B. nach dem Zusammenführen zweier Flächen), ohne den Hauptnamen
};

// Fügt [tag] und [more] zu den Namen einer Fläche hinzu (Hauptname [primary] und Zweitnamen [aliases]); ohne Doppelte.
void addNames(jlong& primary, std::vector<jlong>& aliases, jlong tag, const std::vector<jlong>& more) {
  auto add = [&](jlong t) {
    if (t == 0) return;
    if (primary == 0) { primary = t; return; }
    if (t == primary || std::find(aliases.begin(), aliases.end(), t) != aliases.end()) return;
    aliases.push_back(t);
  };
  add(tag);
  for (jlong t : more) add(t);
}

std::mutex g_mutex;
std::unordered_map<jlong, Entry> g_shapes;
jlong g_next = 1;

TopTools_IndexedMapOfShape facesOf(const TopoDS_Shape& s) {
  TopTools_IndexedMapOfShape faces;
  TopExp::MapShapes(s, TopAbs_FACE, faces);
  return faces;
}

jlong store(const TopoDS_Shape& s, std::vector<jlong> tags = {}, Aliases aliases = {}) {
  if (s.IsNull()) throw std::runtime_error("Ergebnis ist leer");
  size_t n = static_cast<size_t>(facesOf(s).Extent());
  tags.resize(n, 0);
  aliases.resize(n);
  std::lock_guard<std::mutex> lock(g_mutex);
  jlong id = g_next++;
  g_shapes.emplace(id, Entry{s, std::move(tags), std::move(aliases)});
  return id;
}

Entry getEntry(jlong id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  auto it = g_shapes.find(id);
  if (it == g_shapes.end()) throw std::runtime_error("Ungültiges oder bereits freigegebenes Körper-Handle");
  return it->second;
}

TopoDS_Shape get(jlong id) { return getEntry(id).shape; }

// Reicht die Namen der Flächen von [from] an die Ergebnisflächen weiter. [derived] liefert zu einer alten Fläche die
// daraus entstandenen Flächen im Ergebnis. Ist eine Ergebnisfläche aus mehreren benannten Flächen entstanden, wird der erste
// Name ihr Hauptname, die übrigen werden Zweitnamen (sie finden die Fläche weiterhin).
template <typename F>
void propagate(const Entry& from, const TopTools_IndexedMapOfShape& newFaces, std::vector<jlong>& newTags, Aliases& newAliases, F&& derived) {
  TopTools_IndexedMapOfShape oldFaces = facesOf(from.shape);
  newAliases.resize(newTags.size());
  for (int i = 1; i <= oldFaces.Extent(); ++i) {
    jlong tag = from.faceTags[static_cast<size_t>(i - 1)];
    const std::vector<jlong>& more = from.faceAliases[static_cast<size_t>(i - 1)];
    if (tag == 0 && more.empty()) continue;
    for (const TopoDS_Shape& t : derived(oldFaces(i))) {
      int idx = newFaces.FindIndex(t);
      if (idx > 0) addNames(newTags[static_cast<size_t>(idx - 1)], newAliases[static_cast<size_t>(idx - 1)], tag, more);
    }
  }
}

// Alle Namen einer Fläche (Haupt- und Zweitnamen).
bool hasName(const Entry& e, size_t face, jlong tag) {
  if (tag == 0) return false;
  if (e.faceTags[face] == tag) return true;
  const std::vector<jlong>& a = e.faceAliases[face];
  return std::find(a.begin(), a.end(), tag) != a.end();
}

// Verlauf einer Modellieroperation mit Modified()/IsDeleted(): unveränderte Flächen leben unverändert weiter.
template <typename Algo>
std::vector<TopoDS_Shape> survivors(Algo& algo, const TopoDS_Shape& face) {
  std::vector<TopoDS_Shape> out;
  for (TopTools_ListIteratorOfListOfShape it(algo.Modified(face)); it.More(); it.Next()) out.push_back(it.Value());
  if (out.empty() && !algo.IsDeleted(face)) out.push_back(face);
  return out;
}

// ---- Profil → Fläche ----------------------------------------------------------------------------

class Reader {
 public:
  Reader(const double* p, size_t n) : p_(p), end_(p + n) {}
  double next() {
    if (p_ >= end_) throw std::runtime_error("Ungültiges Profil (Ende erreicht)");
    return *p_++;
  }
  int nextInt() { return static_cast<int>(std::lround(next())); }

 private:
  const double* p_;
  const double* end_;
};

TopoDS_Wire readWire(Reader& r, std::vector<gp_Pnt>& mids) {
  int nSegs = r.nextInt();
  if (nSegs <= 0) throw std::runtime_error("Kontur ohne Segmente");
  BRepBuilderAPI_MakeWire wire;
  for (int i = 0; i < nSegs; ++i) {
    int type = r.nextInt();
    TopoDS_Edge edge;
    if (type == 0) {
      gp_Pnt a(r.next(), r.next(), 0), b(0, 0, 0);
      double x2 = r.next(), y2 = r.next();
      b = gp_Pnt(x2, y2, 0);
      BRepBuilderAPI_MakeEdge me(a, b);
      if (!me.IsDone()) throw std::runtime_error("Linie mit Länge 0");
      edge = me.Edge();
    } else if (type == 1) {
      double x1 = r.next(), y1 = r.next(), x2 = r.next(), y2 = r.next(), cx = r.next(), cy = r.next();
      bool ccw = r.nextInt() != 0;
      double radius = std::hypot(x1 - cx, y1 - cy);
      if (radius < 1e-9) throw std::runtime_error("Bogen mit Radius 0");
      // Ein im Uhrzeigersinn durchlaufener Bogen ist ein gegen den Uhrzeigersinn durchlaufener auf einem Kreis mit umgekehrter Achse.
      gp_Circ circ(gp_Ax2(gp_Pnt(cx, cy, 0), ccw ? gp_Dir(0, 0, 1) : gp_Dir(0, 0, -1)), radius);
      BRepBuilderAPI_MakeEdge me(circ, gp_Pnt(x1, y1, 0), gp_Pnt(x2, y2, 0));
      if (!me.IsDone()) throw std::runtime_error("Ungültiger Bogen");
      edge = me.Edge();
    } else if (type == 2) {
      double cx = r.next(), cy = r.next(), radius = r.next();
      if (radius < 1e-9) throw std::runtime_error("Kreis mit Radius 0");
      BRepBuilderAPI_MakeEdge me(gp_Circ(gp_Ax2(gp_Pnt(cx, cy, 0), gp_Dir(0, 0, 1)), radius));
      if (!me.IsDone()) throw std::runtime_error("Ungültiger Kreis");
      edge = me.Edge();
    } else {
      throw std::runtime_error("Unbekannter Segmenttyp " + std::to_string(type));
    }
    {
      BRepAdaptor_Curve c(edge);
      mids.push_back(c.Value(0.5 * (c.FirstParameter() + c.LastParameter())));
    }
    wire.Add(edge);
    if (!wire.IsDone()) throw std::runtime_error("Kontur nicht zusammenhängend (Segmente stoßen nicht aneinander)");
  }
  TopoDS_Wire result = wire.Wire();
  // ShapeFix_Face würde offene Konturen später stillschweigend schließen – das soll ein Fehler sein.
  if (!BRep_Tool::IsClosed(result)) throw std::runtime_error("Kontur ist nicht geschlossen");
  return result;
}

TopoDS_Face readProfile(const double* prog, size_t len, std::vector<gp_Pnt>& mids) {
  Reader r(prog, len);
  int nWires = r.nextInt();
  if (nWires <= 0) throw std::runtime_error("Profil ohne Kontur");
  TopoDS_Wire outer = readWire(r, mids);
  BRepBuilderAPI_MakeFace mk(gp_Pln(), outer, Standard_True);
  if (!mk.IsDone()) throw std::runtime_error("Aus der äußeren Kontur lässt sich keine Fläche bilden (nicht geschlossen?)");
  for (int i = 1; i < nWires; ++i) mk.Add(readWire(r, mids));
  ShapeFix_Face fix(mk.Face());
  fix.FixOrientation(); // Löcher müssen entgegengesetzt zur Außenkontur laufen
  fix.Perform();
  return fix.Face();
}

// ---- Hilfen -------------------------------------------------------------------------------------

template <typename F>
auto guarded(JNIEnv* env, F&& f) -> decltype(f()) {
  try {
    return f();
  } catch (const Standard_Failure& e) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, (std::string("OpenCascade: ") + e.GetMessageString()).c_str());
  } catch (const std::exception& e) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, e.what());
  } catch (...) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, "Unbekannter nativer Fehler");
  }
  return decltype(f())();
}

jfloatArray toJ(JNIEnv* env, const std::vector<float>& v) {
  jfloatArray a = env->NewFloatArray(static_cast<jsize>(v.size()));
  if (!v.empty()) env->SetFloatArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
  return a;
}
jintArray toJ(JNIEnv* env, const std::vector<jint>& v) {
  jintArray a = env->NewIntArray(static_cast<jsize>(v.size()));
  if (!v.empty()) env->SetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
  return a;
}
jdoubleArray toJ(JNIEnv* env, const std::vector<double>& v) {
  jdoubleArray a = env->NewDoubleArray(static_cast<jsize>(v.size()));
  if (!v.empty()) env->SetDoubleArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
  return a;
}

jlong booleanOp(jlong ha, jlong hb, int op) {
  Entry a = getEntry(ha), b = getEntry(hb);
  auto run = [&](auto&& m, const char* what) -> jlong {
    if (!m.IsDone() || m.HasErrors()) throw std::runtime_error(what);
    TopoDS_Shape result = m.Shape();
    TopTools_IndexedMapOfShape faces = facesOf(result);
    std::vector<jlong> tags(static_cast<size_t>(faces.Extent()), 0);
    Aliases aliases;
    auto derived = [&](const TopoDS_Shape& f) { return survivors(m, f); };
    propagate(a, faces, tags, aliases, derived);
    propagate(b, faces, tags, aliases, derived);
    return store(result, std::move(tags), std::move(aliases));
  };
  if (op == 0) return run(BRepAlgoAPI_Fuse(a.shape, b.shape), "Vereinigung fehlgeschlagen");
  if (op == 1) return run(BRepAlgoAPI_Cut(a.shape, b.shape), "Abtragen fehlgeschlagen");
  return run(BRepAlgoAPI_Common(a.shape, b.shape), "Schnittmenge fehlgeschlagen");
}

jlong transformed(jlong h, const gp_Trsf& t) {
  Entry e = getEntry(h);
  BRepBuilderAPI_Transform tr(e.shape, t, Standard_True);
  TopoDS_Shape result = tr.Shape();
  TopTools_IndexedMapOfShape faces = facesOf(result);
  std::vector<jlong> tags(static_cast<size_t>(faces.Extent()), 0);
  Aliases aliases;
  propagate(e, faces, tags, aliases, [&](const TopoDS_Shape& f) { return std::vector<TopoDS_Shape>{tr.ModifiedShape(f)}; });
  return store(result, std::move(tags), std::move(aliases));
}

// Liest Profil und Segmentnamen aus den Java-Feldern.
struct ProfileInput {
  TopoDS_Face face;
  std::vector<gp_Pnt> mids;  // Mittelpunkt je Profilsegment (Zuordnung der Seitenflächen)
  std::vector<jlong> segTags;
};

ProfileInput readProfileInput(JNIEnv* env, jdoubleArray profile, jlongArray segmentTags) {
  jsize n = env->GetArrayLength(profile);
  std::vector<double> prog(static_cast<size_t>(n));
  env->GetDoubleArrayRegion(profile, 0, n, prog.data());
  ProfileInput in;
  jsize nt = env->GetArrayLength(segmentTags);
  in.segTags.resize(static_cast<size_t>(nt));
  if (nt > 0) env->GetLongArrayRegion(segmentTags, 0, nt, in.segTags.data());
  in.face = readProfile(prog.data(), prog.size(), in.mids);
  if (!in.segTags.empty() && in.segTags.size() != in.mids.size()) throw std::runtime_error("Anzahl der Segmentnamen passt nicht zum Profil");
  return in;
}

// Benennt die Flächen eines Zugkörpers (Extrusion, Drehung): Anfangs- und Endfläche sowie die Seitenflächen, die aus den
// Profilsegmenten entstehen. Jede Kante der (ggf. reparierten) Grundfläche wird über ihren Mittelpunkt dem Segment zugeordnet.
template <typename Sweep>
jlong storeSweep(Sweep& sweep, const ProfileInput& in, jlong firstTag, jlong lastTag) {
  TopoDS_Shape result = sweep.Shape();
  TopTools_IndexedMapOfShape faces = facesOf(result);
  std::vector<jlong> tags(static_cast<size_t>(faces.Extent()), 0);
  auto tagFace = [&](const TopoDS_Shape& f, jlong tag) {
    if (f.IsNull() || tag == 0) return;
    int idx = faces.FindIndex(f);
    if (idx > 0) tags[static_cast<size_t>(idx - 1)] = tag;
  };
  tagFace(sweep.FirstShape(), firstTag);
  tagFace(sweep.LastShape(), lastTag);
  if (!in.segTags.empty()) {
    TopTools_IndexedMapOfShape baseEdges;
    TopExp::MapShapes(in.face, TopAbs_EDGE, baseEdges);
    for (int i = 1; i <= baseEdges.Extent(); ++i) {
      const TopoDS_Edge& e = TopoDS::Edge(baseEdges(i));
      BRepAdaptor_Curve c(e);
      gp_Pnt m = c.Value(0.5 * (c.FirstParameter() + c.LastParameter()));
      size_t best = 0;
      double bestD = 1e300;
      for (size_t j = 0; j < in.mids.size(); ++j) {
        double d = m.Distance(in.mids[j]);
        if (d < bestD) { bestD = d; best = j; }
      }
      if (bestD > 1e-5) continue;
      for (TopTools_ListIteratorOfListOfShape it(sweep.Generated(e)); it.More(); it.Next()) tagFace(it.Value(), in.segTags[best]);
    }
    // Rückfall: Manche Flächen liefert Generated() nicht als Teil des Ergebnisses (beim Drehen die ebenen Ringflächen aus Kanten
    // quer zur Achse). Eine solche Fläche enthält den Mittelpunkt genau eines Profilsegments – das, aus dem sie entstanden ist
    // (Nachbarflächen berühren nur dessen Endpunkte; Anfangs- und Endfläche sind schon benannt).
    for (int fi = 1; fi <= faces.Extent(); ++fi) {
      if (tags[static_cast<size_t>(fi - 1)] != 0) continue;
      int found = -1;
      for (size_t j = 0; j < in.mids.size(); ++j) {
        BRepExtrema_DistShapeShape dist(BRepBuilderAPI_MakeVertex(in.mids[j]).Vertex(), faces(fi));
        if (!dist.IsDone() || dist.Value() > 1e-6) continue;
        if (found >= 0) { found = -2; break; }
        found = static_cast<int>(j);
      }
      if (found >= 0) tags[static_cast<size_t>(fi - 1)] = in.segTags[static_cast<size_t>(found)];
    }
  }
  return store(result, std::move(tags));
}

// Teilform zum Messen: kind 0 = Fläche, 1 = Kante; id 1-basiert wie in faceGeometry/edgeInfo.
TopoDS_Shape subShape(const TopoDS_Shape& s, jint kind, jint id) {
  TopTools_IndexedMapOfShape m;
  TopExp::MapShapes(s, kind == 0 ? TopAbs_FACE : TopAbs_EDGE, m);
  if (id < 1 || id > m.Extent()) throw std::runtime_error("Flächen- bzw. Kanten-ID außerhalb des gültigen Bereichs");
  return m(id);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_extrude(JNIEnv* env, jclass, jdoubleArray profile, jdouble height,
                                                                    jlongArray segmentTags, jlong bottomTag, jlong topTag) {
  return guarded(env, [&]() -> jlong {
    ProfileInput in = readProfileInput(env, profile, segmentTags);
    BRepPrimAPI_MakePrism prism(in.face, gp_Vec(0, 0, height));
    if (!prism.IsDone()) throw std::runtime_error("Extrusion fehlgeschlagen");
    return storeSweep(prism, in, bottomTag, topTag);
  });
}

// Drehen: Profil um die Achse durch (axis[0], axis[1]) mit der Richtung (axis[2], axis[3]) – beides in der Profilebene – um
// [angle] Grad (0 < angle ≤ 360, gegen den Uhrzeigersinn um die Achsrichtung). [startTag] benennt die Fläche in der Profilebene,
// [endTag] die gedrehte; bei 360° gibt es beide nicht.
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_revolve(JNIEnv* env, jclass, jdoubleArray profile, jdoubleArray axis, jdouble angle,
                                                                    jlongArray segmentTags, jlong startTag, jlong endTag) {
  return guarded(env, [&]() -> jlong {
    if (env->GetArrayLength(axis) != 4) throw std::runtime_error("Drehachse braucht 4 Werte (Punkt, Richtung)");
    double a[4];
    env->GetDoubleArrayRegion(axis, 0, 4, a);
    if (std::hypot(a[2], a[3]) < 1e-12) throw std::runtime_error("Drehachse ohne Richtung");
    if (!(angle > 0.0 && angle <= 360.0)) throw std::runtime_error("Drehwinkel muss zwischen 0 und 360 Grad liegen");
    ProfileInput in = readProfileInput(env, profile, segmentTags);
    gp_Ax1 ax(gp_Pnt(a[0], a[1], 0), gp_Dir(a[2], a[3], 0));
    const bool full = angle >= 360.0 - 1e-9;
    std::unique_ptr<BRepPrimAPI_MakeRevol> revol = full ? std::make_unique<BRepPrimAPI_MakeRevol>(in.face, ax)
                                                        : std::make_unique<BRepPrimAPI_MakeRevol>(in.face, ax, angle * kPi / 180.0);
    if (!revol->IsDone()) throw std::runtime_error("Drehen fehlgeschlagen");
    return storeSweep(*revol, in, full ? 0 : startTag, full ? 0 : endTag);
  });
}

JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_boolean(JNIEnv* env, jclass, jlong a, jlong b, jint op) {
  return guarded(env, [&]() -> jlong { return booleanOp(a, b, op); });
}

// kind: 0 = Verrundung (Radius), 1 = Fase (symmetrischer Abstand).
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_fillet(JNIEnv* env, jclass, jlong h, jintArray edgeIds, jlongArray edgeTags,
                                                                   jdouble radius, jint kind) {
  return guarded(env, [&]() -> jlong {
    Entry e = getEntry(h);
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(e.shape, TopAbs_EDGE, edges);
    jsize n = env->GetArrayLength(edgeIds);
    std::vector<jint> ids(static_cast<size_t>(n));
    env->GetIntArrayRegion(edgeIds, 0, n, ids.data());
    jsize nt = env->GetArrayLength(edgeTags);
    std::vector<jlong> tagsForEdges(static_cast<size_t>(nt));
    if (nt > 0) env->GetLongArrayRegion(edgeTags, 0, nt, tagsForEdges.data());
    if (nt != 0 && nt != n) throw std::runtime_error("Anzahl der Namen passt nicht zur Anzahl der Kanten");
    auto run = [&](auto&& mk) -> jlong {
    for (jint id : ids) {
      if (id < 1 || id > edges.Extent()) throw std::runtime_error("Kanten-ID außerhalb des gültigen Bereichs");
      mk.Add(radius, TopoDS::Edge(edges(id)));
    }
    mk.Build();
    if (!mk.IsDone()) throw std::runtime_error(kind == 1 ? "Fase fehlgeschlagen (Abstand zu groß?)" : "Verrundung fehlgeschlagen (Radius zu groß?)");
    TopoDS_Shape result = mk.Shape();
    TopTools_IndexedMapOfShape faces = facesOf(result);
    std::vector<jlong> tags(static_cast<size_t>(faces.Extent()), 0);
    Aliases aliases;
    propagate(e, faces, tags, aliases, [&](const TopoDS_Shape& f) { return survivors(mk, f); });
    // Die Rundungsflächen entstehen aus den Kanten und erhalten den für die Kante vergebenen Namen.
    for (size_t i = 0; i < tagsForEdges.size(); ++i) {
      if (tagsForEdges[i] == 0) continue;
      for (TopTools_ListIteratorOfListOfShape it(mk.Generated(edges(ids[i]))); it.More(); it.Next()) {
        int idx = faces.FindIndex(it.Value());
        if (idx > 0) { tags[static_cast<size_t>(idx - 1)] = tagsForEdges[i]; aliases[static_cast<size_t>(idx - 1)].clear(); }
      }
    }
    return store(result, std::move(tags), std::move(aliases));
    };
    if (kind == 1) return run(BRepFilletAPI_MakeChamfer(e.shape));
    return run(BRepFilletAPI_MakeFillet(e.shape));
  });
}

// Schale: entfernt die Flächen [faceIds] (Öffnungen) und macht den Körper zu einer Wand der Stärke [thickness] nach innen.
// Flächen, die bleiben, behalten ihre Namen; die Innenfläche, die aus einer Fläche entsteht, heißt nach [innerTags]
// (Paare: alter Hauptname, neuer Name).
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_shell(JNIEnv* env, jclass, jlong h, jintArray faceIds, jdouble thickness,
                                                                  jlongArray innerTags) {
  return guarded(env, [&]() -> jlong {
    if (!(thickness > 0)) throw std::runtime_error("Wandstärke muss größer als 0 sein");
    Entry e = getEntry(h);
    TopTools_IndexedMapOfShape oldFaces = facesOf(e.shape);
    jsize n = env->GetArrayLength(faceIds);
    std::vector<jint> ids(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(faceIds, 0, n, ids.data());
    TopTools_ListOfShape closing;
    for (jint id : ids) {
      if (id < 1 || id > oldFaces.Extent()) throw std::runtime_error("Flächen-ID außerhalb des gültigen Bereichs");
      closing.Append(oldFaces(id));
    }
    jsize np = env->GetArrayLength(innerTags);
    std::vector<jlong> pairs(static_cast<size_t>(np));
    if (np > 0) env->GetLongArrayRegion(innerTags, 0, np, pairs.data());
    std::unordered_map<jlong, jlong> inner;
    for (size_t i = 0; i + 1 < pairs.size(); i += 2) inner[pairs[i]] = pairs[i + 1];

    BRepOffsetAPI_MakeThickSolid mk;
    // Schnitt statt Bogen an den Kanten: innen entstehen scharfe Ecken wie bei der Außenform
    mk.MakeThickSolidByJoin(e.shape, closing, -thickness, 1e-4, BRepOffset_Skin, Standard_False, Standard_False, GeomAbs_Intersection);
    if (!mk.IsDone()) throw std::runtime_error("Schale fehlgeschlagen (Wand zu dick für eine Stelle des Körpers?)");
    TopoDS_Shape result = mk.Shape();
    TopTools_IndexedMapOfShape faces = facesOf(result);
    std::vector<jlong> tags(static_cast<size_t>(faces.Extent()), 0);
    Aliases aliases(tags.size());
    for (int i = 1; i <= oldFaces.Extent(); ++i) {
      jlong tag = e.faceTags[static_cast<size_t>(i - 1)];
      int kept = faces.FindIndex(oldFaces(i));
      if (kept > 0) addNames(tags[static_cast<size_t>(kept - 1)], aliases[static_cast<size_t>(kept - 1)], tag, e.faceAliases[static_cast<size_t>(i - 1)]);
    }
    // Gültig und kleiner als vorher? Bei zu dicker Wand liefert OCCT sonst ein unbrauchbares Ergebnis, ohne zu scheitern.
    GProp_GProps before, after;
    BRepGProp::VolumeProperties(e.shape, before);
    BRepGProp::VolumeProperties(result, after);
    if (!BRepCheck_Analyzer(result).IsValid() || !(after.Mass() > 0) || after.Mass() >= before.Mass())
      throw std::runtime_error("Schale fehlgeschlagen (Wand zu dick für eine Stelle des Körpers?)");
    // Innenflächen: Modified() meldet sie nicht. Eine Innenfläche liegt im Abstand der Wandstärke vor der Außenfläche, aus der sie
    // entstand – ihr Mittelpunkt hat zu dieser Fläche den kleinsten Abstand.
    // shortcut: Zuordnung über den Flächenmittelpunkt, bei sehr verwinkelten Körpern mit dünnen Wänden ggf. falsch; dann über
    // die Offset-Historie (BRepOffset_MakeOffset) gehen.
    for (int fi = 1; fi <= faces.Extent(); ++fi) {
      if (tags[static_cast<size_t>(fi - 1)] != 0) continue;
      BRepAdaptor_Surface surf(TopoDS::Face(faces(fi)));
      gp_Pnt mid = surf.Value(0.5 * (surf.FirstUParameter() + surf.LastUParameter()), 0.5 * (surf.FirstVParameter() + surf.LastVParameter()));
      TopoDS_Vertex v = BRepBuilderAPI_MakeVertex(mid).Vertex();
      int best = 0;
      double bestD = 1e300;
      for (int i = 1; i <= oldFaces.Extent(); ++i) {
        BRepExtrema_DistShapeShape dist(v, oldFaces(i));
        if (dist.IsDone() && dist.Value() < bestD) { bestD = dist.Value(); best = i; }
      }
      if (best == 0) continue;
      const bool opened = std::find(ids.begin(), ids.end(), best) != ids.end();
      // Rand an der Öffnung: liegt in der Ebene der entfernten Fläche und heißt wie sie (Kante außen verrunden …)
      if (opened && bestD < 1e-6) { tags[static_cast<size_t>(fi - 1)] = e.faceTags[static_cast<size_t>(best - 1)]; continue; }
      if (std::abs(bestD - thickness) > 1e-4 * std::max(1.0, thickness)) continue;
      auto it = inner.find(e.faceTags[static_cast<size_t>(best - 1)]);
      if (it != inner.end()) tags[static_cast<size_t>(fi - 1)] = it->second;
    }
    return store(result, std::move(tags), std::move(aliases));
  });
}

JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_translate(JNIEnv* env, jclass, jlong h, jdouble x, jdouble y, jdouble z) {
  return guarded(env, [&]() -> jlong {
    gp_Trsf t;
    t.SetTranslation(gp_Vec(x, y, z));
    return transformed(h, t);
  });
}

// Starre Bewegung (Drehung + Verschiebung): 12 Werte zeilenweise einer 3x4-Matrix [R | t].
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_transform(JNIEnv* env, jclass, jlong h, jdoubleArray m) {
  return guarded(env, [&]() -> jlong {
    if (env->GetArrayLength(m) != 12) throw std::runtime_error("Transformation braucht 12 Werte (3x4)");
    double v[12];
    env->GetDoubleArrayRegion(m, 0, 12, v);
    gp_Trsf t;
    t.SetValues(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11]);
    return transformed(h, t);
  });
}

// Spiegelung an der Ebene durch (px, py, pz) mit der Normalen (nx, ny, nz): 6 Werte.
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_mirror(JNIEnv* env, jclass, jlong h, jdoubleArray plane) {
  return guarded(env, [&]() -> jlong {
    if (env->GetArrayLength(plane) != 6) throw std::runtime_error("Spiegelebene braucht 6 Werte (Punkt, Normale)");
    double v[6];
    env->GetDoubleArrayRegion(plane, 0, 6, v);
    gp_Trsf t;
    t.SetMirror(gp_Ax2(gp_Pnt(v[0], v[1], v[2]), gp_Dir(v[3], v[4], v[5])));
    return transformed(h, t);
  });
}

// Derselbe Körper mit neuen Flächennamen: ein Hauptname je Fläche (in der Reihenfolge von faceTags) und Zweitnamen als Paare
// (Flächen-ID, Name) – sie ersetzen die bisherigen Zweitnamen vollständig.
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_retag(JNIEnv* env, jclass, jlong h, jlongArray tags, jlongArray aliasPairs) {
  return guarded(env, [&]() -> jlong {
    Entry e = getEntry(h);
    jsize n = env->GetArrayLength(tags);
    if (static_cast<size_t>(n) != e.faceTags.size()) throw std::runtime_error("Anzahl der Flächennamen passt nicht zur Anzahl der Flächen");
    std::vector<jlong> t(static_cast<size_t>(n));
    if (n > 0) env->GetLongArrayRegion(tags, 0, n, t.data());
    jsize na = env->GetArrayLength(aliasPairs);
    std::vector<jlong> pairs(static_cast<size_t>(na));
    if (na > 0) env->GetLongArrayRegion(aliasPairs, 0, na, pairs.data());
    Aliases aliases(static_cast<size_t>(n));
    for (size_t i = 0; i + 1 < pairs.size(); i += 2) {
      jlong face = pairs[i];
      if (face < 1 || face > n) throw std::runtime_error("Flächen-ID eines Zweitnamens außerhalb des gültigen Bereichs");
      addNames(t[static_cast<size_t>(face - 1)], aliases[static_cast<size_t>(face - 1)], pairs[i + 1], {});
    }
    return store(e.shape, std::move(t), std::move(aliases));
  });
}

// Zweitnamen aller Flächen als Paare (Flächen-ID, Name).
JNIEXPORT jlongArray JNICALL Java_dev_androidcad_brep_NativeBrep_faceAliases(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jlongArray {
    Entry e = getEntry(h);
    std::vector<jlong> out;
    for (size_t i = 0; i < e.faceAliases.size(); ++i)
      for (jlong t : e.faceAliases[i]) { out.push_back(static_cast<jlong>(i + 1)); out.push_back(t); }
    jlongArray a = env->NewLongArray(static_cast<jsize>(out.size()));
    if (!out.empty()) env->SetLongArrayRegion(a, 0, static_cast<jsize>(out.size()), out.data());
    return a;
  });
}

// IDs der Flächen, die [tag] als Haupt- oder Zweitnamen tragen.
JNIEXPORT jintArray JNICALL Java_dev_androidcad_brep_NativeBrep_facesWithTag(JNIEnv* env, jclass, jlong h, jlong tag) {
  return guarded(env, [&]() -> jintArray {
    Entry e = getEntry(h);
    std::vector<jint> out;
    for (size_t i = 0; i < e.faceTags.size(); ++i) if (hasName(e, i, tag)) out.push_back(static_cast<jint>(i + 1));
    return toJ(env, out);
  });
}

// IDs der Kanten zwischen einer Fläche namens [a] und einer namens [b] (Haupt- oder Zweitnamen; a == b: Kanten innerhalb einer
// Fläche, z. B. die Naht eines Zylinders).
JNIEXPORT jintArray JNICALL Java_dev_androidcad_brep_NativeBrep_edgesBetween(JNIEnv* env, jclass, jlong h, jlong a, jlong b) {
  return guarded(env, [&]() -> jintArray {
    Entry e = getEntry(h);
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(e.shape, TopAbs_EDGE, edges);
    TopTools_IndexedMapOfShape faces = facesOf(e.shape);
    TopTools_IndexedDataMapOfShapeListOfShape ancestors;
    TopExp::MapShapesAndAncestors(e.shape, TopAbs_EDGE, TopAbs_FACE, ancestors);
    std::vector<jint> out;
    for (int i = 1; i <= edges.Extent(); ++i) {
      int idx = ancestors.FindIndex(edges(i));
      if (idx <= 0) continue;
      std::vector<size_t> fs;
      for (TopTools_ListIteratorOfListOfShape it(ancestors.FindFromIndex(idx)); it.More(); it.Next()) {
        int fi = faces.FindIndex(it.Value());
        if (fi > 0 && std::find(fs.begin(), fs.end(), static_cast<size_t>(fi - 1)) == fs.end()) fs.push_back(static_cast<size_t>(fi - 1));
      }
      if (fs.empty()) continue;
      size_t f1 = fs[0], f2 = fs.size() > 1 ? fs[1] : fs[0];
      if ((hasName(e, f1, a) && hasName(e, f2, b)) || (hasName(e, f1, b) && hasName(e, f2, a))) out.push_back(i);
    }
    return toJ(env, out);
  });
}

// Führt Flächen, die auf derselben Fläche liegen (z. B. die beiden Hälften nach einer Spiegelung), zu einer zusammen. Die neue
// Fläche trägt den kleinsten der beteiligten Namen (das älteste Feature) als Hauptnamen, alle übrigen als Zweitnamen.
JNIEXPORT jlong JNICALL Java_dev_androidcad_brep_NativeBrep_unifyFaces(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jlong {
    Entry e = getEntry(h);
    ShapeUpgrade_UnifySameDomain u(e.shape, Standard_True, Standard_True, Standard_False);
    u.Build();
    TopoDS_Shape result = u.Shape();
    Handle(BRepTools_History) hist = u.History();
    TopTools_IndexedMapOfShape faces = facesOf(result);
    std::vector<std::vector<jlong>> names(static_cast<size_t>(faces.Extent()));
    TopTools_IndexedMapOfShape oldFaces = facesOf(e.shape);
    for (int i = 1; i <= oldFaces.Extent(); ++i) {
      std::vector<jlong> own;
      if (e.faceTags[static_cast<size_t>(i - 1)] != 0) own.push_back(e.faceTags[static_cast<size_t>(i - 1)]);
      for (jlong t : e.faceAliases[static_cast<size_t>(i - 1)]) own.push_back(t);
      if (own.empty()) continue;
      std::vector<TopoDS_Shape> targets;
      if (!hist.IsNull()) {
        for (TopTools_ListIteratorOfListOfShape it(hist->Modified(oldFaces(i))); it.More(); it.Next()) targets.push_back(it.Value());
        if (targets.empty() && !hist->IsRemoved(oldFaces(i))) targets.push_back(oldFaces(i));
      } else {
        targets.push_back(oldFaces(i));
      }
      for (const TopoDS_Shape& t : targets) {
        int idx = faces.FindIndex(t);
        if (idx <= 0) continue;
        auto& n = names[static_cast<size_t>(idx - 1)];
        for (jlong x : own) if (std::find(n.begin(), n.end(), x) == n.end()) n.push_back(x);
      }
    }
    std::vector<jlong> tags(names.size(), 0);
    Aliases aliases(names.size());
    for (size_t i = 0; i < names.size(); ++i) {
      if (names[i].empty()) continue;
      std::sort(names[i].begin(), names[i].end());
      tags[i] = names[i][0];
      aliases[i].assign(names[i].begin() + 1, names[i].end());
    }
    return store(result, std::move(tags), std::move(aliases));
  });
}

// Kürzester Abstand zweier Flächen/Kanten: 7 Werte – Abstand, nächster Punkt auf a (x y z), nächster Punkt auf b (x y z).
JNIEXPORT jdoubleArray JNICALL Java_dev_androidcad_brep_NativeBrep_distance(JNIEnv* env, jclass, jlong h, jint aKind, jint aId, jint bKind, jint bId) {
  return guarded(env, [&]() -> jdoubleArray {
    TopoDS_Shape s = get(h);
    BRepExtrema_DistShapeShape d(subShape(s, aKind, aId), subShape(s, bKind, bId));
    if (!d.IsDone() || d.NbSolution() < 1) throw std::runtime_error("Abstand ließ sich nicht bestimmen");
    gp_Pnt p = d.PointOnShape1(1), q = d.PointOnShape2(1);
    return toJ(env, std::vector<double>{d.Value(), p.X(), p.Y(), p.Z(), q.X(), q.Y(), q.Z()});
  });
}

// Flächeninhalt der Fläche [id] (mm²), exakt aus der Geometrie.
JNIEXPORT jdouble JNICALL Java_dev_androidcad_brep_NativeBrep_faceArea(JNIEnv* env, jclass, jlong h, jint id) {
  return guarded(env, [&]() -> jdouble {
    GProp_GProps props;
    BRepGProp::SurfaceProperties(subShape(get(h), 0, id), props);
    return props.Mass();
  });
}

// Schreibt den Körper als STEP-Datei (AP214, Millimeter) nach [path].
JNIEXPORT void JNICALL Java_dev_androidcad_brep_NativeBrep_writeStep(JNIEnv* env, jclass, jlong h, jstring path) {
  guarded(env, [&]() -> int {
    TopoDS_Shape s = get(h);  // Kopie des Verweises: der Körper bleibt gültig, auch wenn das Handle währenddessen freigegeben wird
    const char* p = env->GetStringUTFChars(path, nullptr);
    std::string file(p);
    env->ReleaseStringUTFChars(path, p);
    STEPControl_Writer writer;
    if (writer.Transfer(s, STEPControl_AsIs) != IFSelect_RetDone) throw std::runtime_error("Körper ließ sich nicht nach STEP übertragen");
    if (writer.Write(file.c_str()) != IFSelect_RetDone) throw std::runtime_error("STEP-Datei ließ sich nicht schreiben");
    return 0;
  });
}

JNIEXPORT jdouble JNICALL Java_dev_androidcad_brep_NativeBrep_volume(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jdouble {
    GProp_GProps props;
    BRepGProp::VolumeProperties(get(h), props);
    return props.Mass();
  });
}

JNIEXPORT jintArray JNICALL Java_dev_androidcad_brep_NativeBrep_counts(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jintArray {
    TopoDS_Shape s = get(h);
    TopTools_IndexedMapOfShape faces, edges;
    TopExp::MapShapes(s, TopAbs_FACE, faces);
    TopExp::MapShapes(s, TopAbs_EDGE, edges);
    return toJ(env, std::vector<jint>{faces.Extent(), edges.Extent()});
  });
}

// Je Kante 13 Werte: id, x1 y1 z1 (Start), x2 y2 z2 (Ende), Länge, Kurventyp (GeomAbs_CurveType),
// cx cy cz (Mittelpunkt) und Radius – die letzten vier nur bei Kreisen, sonst 0.
JNIEXPORT jdoubleArray JNICALL Java_dev_androidcad_brep_NativeBrep_edgeInfo(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jdoubleArray {
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(get(h), TopAbs_EDGE, edges);
    std::vector<double> out;
    for (int i = 1; i <= edges.Extent(); ++i) {
      BRepAdaptor_Curve c(TopoDS::Edge(edges(i)));
      gp_Pnt a = c.Value(c.FirstParameter()), b = c.Value(c.LastParameter());
      double cx = 0, cy = 0, cz = 0, r = 0;
      if (c.GetType() == GeomAbs_Circle) {
        gp_Circ circ = c.Circle();
        cx = circ.Location().X(); cy = circ.Location().Y(); cz = circ.Location().Z();
        r = circ.Radius();
      }
      out.insert(out.end(), {static_cast<double>(i), a.X(), a.Y(), a.Z(), b.X(), b.Y(), b.Z(),
                             GCPnts_AbscissaPoint::Length(c), static_cast<double>(c.GetType()), cx, cy, cz, r});
    }
    return toJ(env, out);
  });
}

// Rückgabe: Object[6] = { float[] positions, int[] triangles, int[] triangleFace (1-basiert),
//                         float[] edgePositions, int[] edgePointCounts, int[] edgeIds (1-basiert) }
JNIEXPORT jobjectArray JNICALL Java_dev_androidcad_brep_NativeBrep_tessellate(JNIEnv* env, jclass, jlong h, jdouble linear, jdouble angular) {
  return guarded(env, [&]() -> jobjectArray {
    TopoDS_Shape shape = get(h);
    BRepMesh_IncrementalMesh mesher(shape, linear, Standard_False, angular, Standard_True);

    std::vector<float> pos;
    std::vector<jint> tris, triFace;
    TopTools_IndexedMapOfShape faces;
    TopExp::MapShapes(shape, TopAbs_FACE, faces);
    for (int fi = 1; fi <= faces.Extent(); ++fi) {
      TopoDS_Face face = TopoDS::Face(faces(fi));
      TopLoc_Location loc;
      Handle(Poly_Triangulation) tri = BRep_Tool::Triangulation(face, loc);
      if (tri.IsNull()) continue;
      const gp_Trsf trsf = loc.Transformation();
      const jint base = static_cast<jint>(pos.size() / 3);
      for (int i = 1; i <= tri->NbNodes(); ++i) {
        gp_Pnt p = tri->Node(i).Transformed(trsf);
        pos.insert(pos.end(), {static_cast<float>(p.X()), static_cast<float>(p.Y()), static_cast<float>(p.Z())});
      }
      const bool reversed = face.Orientation() == TopAbs_REVERSED;
      for (int i = 1; i <= tri->NbTriangles(); ++i) {
        int a, b, c;
        tri->Triangle(i).Get(a, b, c);
        if (reversed) std::swap(b, c);
        tris.insert(tris.end(), {base + a - 1, base + b - 1, base + c - 1});
        triFace.push_back(fi);
      }
    }

    std::vector<float> edgePos;
    std::vector<jint> edgeCounts, edgeIds;
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(shape, TopAbs_EDGE, edges);
    for (int ei = 1; ei <= edges.Extent(); ++ei) {
      BRepAdaptor_Curve c(TopoDS::Edge(edges(ei)));
      GCPnts_TangentialDeflection pts(c, angular, linear);
      for (int i = 1; i <= pts.NbPoints(); ++i) {
        gp_Pnt p = pts.Value(i);
        edgePos.insert(edgePos.end(), {static_cast<float>(p.X()), static_cast<float>(p.Y()), static_cast<float>(p.Z())});
      }
      edgeCounts.push_back(pts.NbPoints());
      edgeIds.push_back(ei);
    }

    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(6, objCls, nullptr);
    env->SetObjectArrayElement(out, 0, toJ(env, pos));
    env->SetObjectArrayElement(out, 1, toJ(env, tris));
    env->SetObjectArrayElement(out, 2, toJ(env, triFace));
    env->SetObjectArrayElement(out, 3, toJ(env, edgePos));
    env->SetObjectArrayElement(out, 4, toJ(env, edgeCounts));
    env->SetObjectArrayElement(out, 5, toJ(env, edgeIds));
    return out;
  });
}

// Je Fläche (Index i <-> Flächen-ID i + 1) 7 Werte: 1/0 (eben/nicht eben), Punkt auf der Ebene (x y z) und die nach außen
// zeigende Normale (x y z). Für nicht ebene Flächen stehen Nullen da.
JNIEXPORT jdoubleArray JNICALL Java_dev_androidcad_brep_NativeBrep_faceGeometry(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jdoubleArray {
    TopoDS_Shape shape = get(h);
    TopTools_IndexedMapOfShape faces = facesOf(shape);
    std::vector<double> out;
    for (int i = 1; i <= faces.Extent(); ++i) {
      TopoDS_Face face = TopoDS::Face(faces(i));
      BRepAdaptor_Surface surf(face);
      if (surf.GetType() == GeomAbs_Plane) {
        gp_Pln pl = surf.Plane();
        gp_Dir d = pl.Axis().Direction();
        if (face.Orientation() == TopAbs_REVERSED) d.Reverse();  // Außennormale des Körpers
        gp_Pnt o = pl.Location();
        out.insert(out.end(), {1.0, o.X(), o.Y(), o.Z(), d.X(), d.Y(), d.Z()});
      } else {
        out.insert(out.end(), {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0});
      }
    }
    return toJ(env, out);
  });
}

// Namen der Flächen (Index i <-> Flächen-ID i + 1).
JNIEXPORT jlongArray JNICALL Java_dev_androidcad_brep_NativeBrep_faceTags(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jlongArray {
    Entry e = getEntry(h);
    jlongArray a = env->NewLongArray(static_cast<jsize>(e.faceTags.size()));
    if (!e.faceTags.empty()) env->SetLongArrayRegion(a, 0, static_cast<jsize>(e.faceTags.size()), e.faceTags.data());
    return a;
  });
}

// Je Kante (Index i <-> Kanten-ID i + 1) die Namen der beiden Nachbarflächen, aufsteigend sortiert; hat die Kante nur eine
// Nachbarfläche (Naht eines Zylinders), steht ihr Name zweimal da.
JNIEXPORT jlongArray JNICALL Java_dev_androidcad_brep_NativeBrep_edgeFaceTags(JNIEnv* env, jclass, jlong h) {
  return guarded(env, [&]() -> jlongArray {
    Entry e = getEntry(h);
    TopTools_IndexedMapOfShape edges;
    TopExp::MapShapes(e.shape, TopAbs_EDGE, edges);
    TopTools_IndexedMapOfShape faces = facesOf(e.shape);
    TopTools_IndexedDataMapOfShapeListOfShape ancestors;
    TopExp::MapShapesAndAncestors(e.shape, TopAbs_EDGE, TopAbs_FACE, ancestors);
    std::vector<jlong> out;
    for (int i = 1; i <= edges.Extent(); ++i) {
      std::vector<jlong> t;
      int idx = ancestors.FindIndex(edges(i));
      if (idx > 0) {
        for (TopTools_ListIteratorOfListOfShape it(ancestors.FindFromIndex(idx)); it.More(); it.Next()) {
          int fi = faces.FindIndex(it.Value());
          if (fi > 0) t.push_back(e.faceTags[static_cast<size_t>(fi - 1)]);
        }
      }
      if (t.empty()) t = {0, 0};
      jlong a = t.front(), b = t.size() > 1 ? t[1] : t.front();
      out.push_back(std::min(a, b));
      out.push_back(std::max(a, b));
    }
    jlongArray arr = env->NewLongArray(static_cast<jsize>(out.size()));
    if (!out.empty()) env->SetLongArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
  });
}

JNIEXPORT void JNICALL Java_dev_androidcad_brep_NativeBrep_release(JNIEnv*, jclass, jlong h) {
  std::lock_guard<std::mutex> lock(g_mutex);
  g_shapes.erase(h);
}

}  // extern "C"
