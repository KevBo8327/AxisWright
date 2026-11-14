// JNI-Brücke zwischen dem Kotlin-CSG-Baum und Manifold.
//
// Kotlin übersetzt den Baum in ein flaches "Programm" (DoubleArray, Postfix-Notation, siehe
// ProgramEncoder.kt). Dieser Interpreter führt es auf einem Stapel aus Körpern (Manifold) und
// 2D-Formen (CrossSection) aus und liefert Dreiecksnetz-Puffer zurück. Ein einziger JNI-Aufruf
// pro Auswertung – keine Handles, kein Lebenszyklus-Management über die Sprachgrenze.
//
// Die Opcodes müssen mit ProgramEncoder.kt (Object `Op`) übereinstimmen.

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

#include "manifold/cross_section.h"
#include "manifold/manifold.h"

using manifold::CrossSection;
using manifold::Manifold;
using manifold::MeshGL;
using manifold::MeshGL64;
using manifold::OpType;
using manifold::Polygons;
using manifold::SimplePolygon;
using manifold::mat2x3;
using manifold::mat3x4;
using manifold::vec2;
using manifold::vec3;

namespace {

enum Op : int {
  CUBE = 1,
  SPHERE = 2,
  CYLINDER = 3,
  POLYHEDRON = 4,
  CIRCLE = 5,
  SQUARE = 6,
  POLYGON = 7,
  TRANSFORM = 8,
  UNION = 9,
  DIFFERENCE = 10,
  INTERSECTION = 11,
  HULL = 12,
  MINKOWSKI = 13,
  LINEAR_EXTRUDE = 14,
  ROTATE_EXTRUDE = 15,
  OFFSET = 16,
};

struct Item {
  bool is2d = false;
  Manifold solid;
  CrossSection shape;
  static Item of(Manifold m) {
    Item i;
    i.solid = std::move(m);
    return i;
  }
  static Item of(CrossSection c) {
    Item i;
    i.is2d = true;
    i.shape = std::move(c);
    return i;
  }
};

[[noreturn]] void fail(const std::string& msg) { throw std::runtime_error(msg); }

constexpr int kMaxSegments = 65536;
constexpr size_t kMaxVertices = 2'000'000;
constexpr size_t kMaxTriangles = 2'000'000;

class Machine {
 public:
  Machine(const double* prog, size_t len) : p_(prog), end_(prog + len) {}

  Manifold run() {
    while (p_ < end_) step(nextInt());
    if (st_.empty()) return Manifold();
    if (st_.size() > 1) combine(OpType::Add, st_.size());
    Item top = std::move(st_.back());
    if (top.is2d) fail("Das Ergebnis ist eine 2D-Form; extrudiere sie (linear_extrude/rotate_extrude).");
    return std::move(top.solid);
  }

 private:
  const double* p_;
  const double* end_;
  std::vector<Item> st_;

  size_t remaining() const { return static_cast<size_t>(end_ - p_); }
  double next() {
    if (p_ >= end_) fail("Ungültiges Programm (Ende erreicht)");
    return *p_++;
  }
  int nextInt() {
    double v = next();
    if (!std::isfinite(v) || v < std::numeric_limits<int>::min() || v > std::numeric_limits<int>::max())
      fail("Ungültiger Ganzzahlwert im Programm");
    return static_cast<int>(std::lround(v));
  }
  size_t nextStackCount(const char* what) {
    int n = nextInt();
    if (n < 0 || static_cast<size_t>(n) > st_.size()) fail(std::string(what) + ": ungültige Operandenanzahl");
    return static_cast<size_t>(n);
  }
  int nextSegments(const char* what) {
    int seg = nextInt();
    if (seg < 0 || seg > kMaxSegments) fail(std::string(what) + ": Segmentanzahl außerhalb des gültigen Bereichs");
    return seg;
  }
  bool nextBool() { return next() != 0.0; }

  Item pop() {
    if (st_.empty()) fail("Ungültiges Programm (leerer Stapel)");
    Item i = std::move(st_.back());
    st_.pop_back();
    return i;
  }

  std::vector<Item> popN(size_t n) {
    if (st_.size() < n) fail("Ungültiges Programm (zu wenige Operanden)");
    std::vector<Item> out(std::make_move_iterator(st_.end() - n), std::make_move_iterator(st_.end()));
    st_.resize(st_.size() - n);
    return out;
  }

  static void check(const Manifold& m, const char* what) {
    auto e = m.Status();
    if (e != Manifold::Error::NoError) fail(std::string(what) + ": ungültige Geometrie (Fehlercode " + std::to_string(static_cast<int>(e)) + ")");
  }

  // Zerlegt n Operanden und prüft, dass sie alle 2D oder alle 3D sind.
  void combine(OpType op, size_t n) {
    if (n == 0) {
      st_.push_back(Item::of(Manifold()));
      return;
    }
    auto items = popN(n);
    if (n == 1) {
      st_.push_back(std::move(items[0]));
      return;
    }
    bool is2d = items[0].is2d;
    for (auto& it : items)
      if (it.is2d != is2d) fail("2D- und 3D-Objekte lassen sich nicht kombinieren");
    if (is2d) {
      std::vector<CrossSection> v;
      for (auto& it : items) v.push_back(std::move(it.shape));
      st_.push_back(Item::of(CrossSection::BatchBoolean(v, op)));
    } else {
      std::vector<Manifold> v;
      for (auto& it : items) v.push_back(std::move(it.solid));
      st_.push_back(Item::of(Manifold::BatchBoolean(v, op)));
    }
  }

  void step(int op) {
    switch (op) {
      case CUBE: {
        double x = next(), y = next(), z = next();
        bool center = nextBool();
        st_.push_back(Item::of(Manifold::Cube(vec3(x, y, z), center)));
        break;
      }
      case SPHERE: {
        double r = next();
        int seg = nextSegments("sphere");
        st_.push_back(Item::of(Manifold::Sphere(r, seg)));
        break;
      }
      case CYLINDER: {
        double h = next(), r1 = next(), r2 = next();
        bool center = nextBool();
        int seg = nextSegments("cylinder");
        st_.push_back(Item::of(Manifold::Cylinder(h, r1, r2, seg, center)));
        break;
      }
      case POLYHEDRON: polyhedron(); break;
      case CIRCLE: {
        double r = next();
        int seg = nextSegments("circle");
        st_.push_back(Item::of(CrossSection::Circle(r, seg)));
        break;
      }
      case SQUARE: {
        double x = next(), y = next();
        bool center = nextBool();
        st_.push_back(Item::of(CrossSection::Square(vec2(x, y), center)));
        break;
      }
      case POLYGON: polygon(); break;
      case TRANSFORM: transform(); break;
      case UNION: combine(OpType::Add, nextStackCount("union")); break;
      case DIFFERENCE: combine(OpType::Subtract, nextStackCount("difference")); break;
      case INTERSECTION: combine(OpType::Intersect, nextStackCount("intersection")); break;
      case HULL: hull(nextStackCount("hull")); break;
      case MINKOWSKI: minkowski(nextStackCount("minkowski")); break;
      case LINEAR_EXTRUDE: linearExtrude(); break;
      case ROTATE_EXTRUDE: rotateExtrude(); break;
      case OFFSET: offset(); break;
      default: fail("Unbekannter Opcode " + std::to_string(op));
    }
  }

  void polyhedron() {
    int np = nextInt();
    if (np < 0 || static_cast<size_t>(np) > remaining() / 3) fail("polyhedron: ungültige Punktanzahl");
    MeshGL64 mesh;
    mesh.numProp = 3;
    const size_t nCoords = static_cast<size_t>(np) * 3;
    mesh.vertProperties.reserve(nCoords);
    for (size_t i = 0; i < nCoords; ++i) mesh.vertProperties.push_back(next());
    int nf = nextInt();
    if (nf < 0 || static_cast<size_t>(nf) > remaining()) fail("polyhedron: ungültige Flächenanzahl");
    for (int f = 0; f < nf; ++f) {
      int k = nextInt();
      if (k < 0 || static_cast<size_t>(k) > remaining()) fail("polyhedron: ungültige Eckenzahl");
      std::vector<uint64_t> idx(static_cast<size_t>(k));
      for (int j = 0; j < k; ++j) {
        int v = nextInt();
        if (v < 0 || v >= np) fail("polyhedron: Punktindex außerhalb des gültigen Bereichs");
        idx[static_cast<size_t>(j)] = static_cast<uint64_t>(v);
      }
      // OpenSCAD-Konvention: Flächen im Uhrzeigersinn von außen gesehen; Manifold erwartet gegen den
      // Uhrzeigersinn (Orientierung wird unten bei Bedarf korrigiert). Konvexe Flächen: Fächer-Triangulation.
      for (int j = 1; j + 1 < k; ++j) {
        mesh.triVerts.push_back(idx[0]);
        mesh.triVerts.push_back(idx[static_cast<size_t>(j + 1)]);
        mesh.triVerts.push_back(idx[static_cast<size_t>(j)]);
      }
    }
    // OpenSCAD korrigiert die Orientierung geschlossener Polyeder stillschweigend; Modelle liefern beide
    // Windungsrichtungen. Daher: bei negativem signiertem Volumen alle Dreiecke umdrehen.
    double signedVolume = 0;
    for (size_t t = 0; t + 2 < mesh.triVerts.size(); t += 3) {
      const double* a = &mesh.vertProperties[mesh.triVerts[t] * 3];
      const double* b = &mesh.vertProperties[mesh.triVerts[t + 1] * 3];
      const double* c = &mesh.vertProperties[mesh.triVerts[t + 2] * 3];
      signedVolume += a[0] * (b[1] * c[2] - b[2] * c[1]) + a[1] * (b[2] * c[0] - b[0] * c[2]) + a[2] * (b[0] * c[1] - b[1] * c[0]);
    }
    if (signedVolume < 0)
      for (size_t t = 0; t + 2 < mesh.triVerts.size(); t += 3) std::swap(mesh.triVerts[t + 1], mesh.triVerts[t + 2]);
    // Gemeinsame Punkte sind bereits über Indizes geteilt; Merge fasst zusätzlich deckungsgleiche zusammen.
    mesh.Merge();
    Manifold m(mesh);
    check(m, "polyhedron");
    st_.push_back(Item::of(std::move(m)));
  }

  void polygon() {
    int npaths = nextInt();
    if (npaths < 0 || static_cast<size_t>(npaths) > remaining()) fail("polygon: ungültige Konturanzahl");
    Polygons polys;
    for (int i = 0; i < npaths; ++i) {
      int n = nextInt();
      if (n < 0 || static_cast<size_t>(n) > remaining() / 2) fail("polygon: ungültige Punktanzahl");
      SimplePolygon sp;
      sp.reserve(static_cast<size_t>(n));
      for (int j = 0; j < n; ++j) {
        double x = next(), y = next();
        sp.emplace_back(x, y);
      }
      polys.push_back(std::move(sp));
    }
    st_.push_back(Item::of(CrossSection(polys, CrossSection::FillRule::EvenOdd)));
  }

  void transform() {
    double m[16];
    for (double& v : m) v = next();
    Item it = pop();
    if (it.is2d) {
      mat2x3 t(vec2(m[0], m[4]), vec2(m[1], m[5]), vec2(m[3], m[7]));
      st_.push_back(Item::of(it.shape.Transform(t)));
    } else {
      mat3x4 t(vec3(m[0], m[4], m[8]), vec3(m[1], m[5], m[9]), vec3(m[2], m[6], m[10]), vec3(m[3], m[7], m[11]));
      st_.push_back(Item::of(it.solid.Transform(t)));
    }
  }

  void hull(size_t n) {
    if (n == 0) {
      st_.push_back(Item::of(Manifold()));
      return;
    }
    auto items = popN(n);
    bool is2d = items[0].is2d;
    for (auto& it : items)
      if (it.is2d != is2d) fail("hull: 2D und 3D lassen sich nicht mischen");
    if (is2d) {
      std::vector<CrossSection> v;
      for (auto& it : items) v.push_back(std::move(it.shape));
      st_.push_back(Item::of(CrossSection::Hull(v)));
    } else {
      std::vector<Manifold> v;
      for (auto& it : items) v.push_back(std::move(it.solid));
      st_.push_back(Item::of(Manifold::Hull(v)));
    }
  }

  void minkowski(size_t n) {
    if (n == 0) {
      st_.push_back(Item::of(Manifold()));
      return;
    }
    auto items = popN(n);
    for (auto& it : items)
      if (it.is2d) fail("minkowski: nur 3D-Körper werden unterstützt");
    Manifold acc = std::move(items[0].solid);
    for (size_t i = 1; i < items.size(); ++i) acc = acc.MinkowskiSum(items[i].solid);
    st_.push_back(Item::of(std::move(acc)));
  }

  void linearExtrude() {
    double h = next();
    bool center = nextBool();
    double twist = next();
    int slices = nextSegments("linear_extrude");
    double sx = next(), sy = next();
    Item it = pop();
    if (!it.is2d) fail("linear_extrude: 2D-Form erwartet");
    if (it.shape.IsEmpty() || h == 0.0) {
      st_.push_back(Item::of(Manifold()));
      return;
    }
    bool divide = twist != 0.0 || sx != 1.0 || sy != 1.0;
    // OpenSCAD dreht positive Twist-Werte im Uhrzeigersinn, Manifold gegen den Uhrzeigersinn.
    Manifold m = Manifold::Extrude(it.shape.ToPolygons(), std::fabs(h), divide ? std::max(slices, 1) : 0, -twist, vec2(sx, sy));
    if (h < 0) m = m.Mirror(vec3(0, 0, 1));
    if (center) m = m.Translate(vec3(0, 0, -std::fabs(h) / 2));
    st_.push_back(Item::of(std::move(m)));
  }

  void rotateExtrude() {
    double angle = next();
    int seg = nextSegments("rotate_extrude");
    Item it = pop();
    if (!it.is2d) fail("rotate_extrude: 2D-Form erwartet");
    if (it.shape.IsEmpty()) {
      st_.push_back(Item::of(Manifold()));
      return;
    }
    st_.push_back(Item::of(Manifold::Revolve(it.shape.ToPolygons(), seg, angle)));
  }

  void offset() {
    double amount = next();
    bool round = nextBool();
    bool chamfer = nextBool();
    int seg = nextSegments("offset");
    Item it = pop();
    if (!it.is2d) fail("offset: 2D-Form erwartet");
    auto jt = round ? CrossSection::JoinType::Round : (chamfer ? CrossSection::JoinType::Bevel : CrossSection::JoinType::Miter);
    st_.push_back(Item::of(it.shape.Offset(amount, jt, 1000.0, seg)));
  }
};

void throwJava(JNIEnv* env, const char* msg) {
  jclass cls = env->FindClass("java/lang/IllegalStateException");
  if (cls != nullptr) env->ThrowNew(cls, msg);
}

}  // namespace

// Rückgabe: Object[2] = { float[] positions (xyz, 3 je Punkt), int[] triangles (3 Indizes je Dreieck) }
extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_androidcad_geometry_NativeKernel_nativeBuild(JNIEnv* env, jclass, jdoubleArray program) {
  try {
    if (!program) fail("Programm fehlt");
    jsize len = env->GetArrayLength(program);
    if (len < 0) fail("Ungültige Programmlänge");
    std::vector<double> prog(static_cast<size_t>(len));
    if (len > 0) env->GetDoubleArrayRegion(program, 0, len, prog.data());

    Manifold result = Machine(prog.data(), prog.size()).run();
    MeshGL mesh = result.GetMeshGL();

    const size_t nv = mesh.NumVert();
    const size_t nt = mesh.NumTri();
    const size_t maxJsize = static_cast<size_t>(std::numeric_limits<jsize>::max());
    if (nv > kMaxVertices || nt > kMaxTriangles || nv > maxJsize / 3 || nt > maxJsize / 3) {
      fail("Netz überschreitet Größenlimit");
    }
    std::vector<float> pos(nv * 3);
    for (size_t i = 0; i < nv; ++i)
      for (size_t k = 0; k < 3; ++k) pos[i * 3 + k] = mesh.vertProperties[i * mesh.numProp + k];

    jfloatArray jpos = env->NewFloatArray(static_cast<jsize>(pos.size()));
    if (!jpos) return nullptr;
    if (!pos.empty()) env->SetFloatArrayRegion(jpos, 0, static_cast<jsize>(pos.size()), pos.data());

    jintArray jidx = env->NewIntArray(static_cast<jsize>(nt * 3));
    if (!jidx) {
      env->DeleteLocalRef(jpos);
      return nullptr;
    }
    if (nt > 0) env->SetIntArrayRegion(jidx, 0, static_cast<jsize>(nt * 3), reinterpret_cast<const jint*>(mesh.triVerts.data()));

    jclass objCls = env->FindClass("java/lang/Object");
    if (!objCls) {
      env->DeleteLocalRef(jpos);
      env->DeleteLocalRef(jidx);
      return nullptr;
    }
    jobjectArray out = env->NewObjectArray(2, objCls, nullptr);
    if (!out) {
      env->DeleteLocalRef(jpos);
      env->DeleteLocalRef(jidx);
      return nullptr;
    }
    env->SetObjectArrayElement(out, 0, jpos);
    env->SetObjectArrayElement(out, 1, jidx);
    env->DeleteLocalRef(jpos);
    env->DeleteLocalRef(jidx);
    return out;
  } catch (const std::exception& e) {
    throwJava(env, e.what());
  } catch (...) {
    throwJava(env, "Unbekannter nativer Fehler");
  }
  return nullptr;
}
