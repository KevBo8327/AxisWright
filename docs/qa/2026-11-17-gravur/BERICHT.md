# Lane GR – Prüfbericht Gravur, Text-Tessellierung und Schnittansicht

- **Ausgangsstand:** `1221112` (*Lane GR: Text-Polylinien bereinigen, fehlgeschlagene Flächen mit Delabella nachvernetzen und melden*)
- **Zweig:** `lane-gr`
- **Gerät / Emulator:** AVD `Pixel_10_Pro_XL` (`sdk_gphone16k_x86_64`, Android 17 / API 37, ABI `x86_64`)
- **Getestete App-Variante:** `fossDebug` (`io.github.kevbo8327.axiswright`)
- **Behobene Fehler (eigene Commits):**
  - `cfa794a`: Lochorientierung in `readProfile` (`cadbrep.cpp`) und Ebenennormalen gespiegelter Flächen in `faceGeometry` (`!pl.Direct()`) korrigiert.
  - `85c5e4f`: Überlappende Teilkonturen von Schriften (z. B. Roboto `'B'`) in `AndroidTextOutlines.kt` per `Path.Op.UNION` aufgelöst.

---

## 1. Gerätetests (`connectedDebugAndroidTest`)

Ausgeführter Befehl (`ANDROID_SERIAL=emulator-5554`):
```sh
./gradlew.bat :core:brep:connectedDebugAndroidTest :core:model:connectedDebugAndroidTest :core:geometry:connectedDebugAndroidTest -Pandroid.injected.build.abi=x86_64 --continue
```

### 1.1 Erstlauf auf Ausgangsstand `1221112` (mit Befunden)

| Modul | Gesamt | Bestanden | Fehlgeschlagen | Bemerkung |
| --- | ---: | ---: | ---: | --- |
| `:core:geometry` | 17 | 17 | 0 | `KernelBoundsTest`, `KernelTest`, `ReferenceModelTest` grün |
| `:core:model` | 95 | 64 | 31 | Durch fehlende `fix.FixOrientation()`-Ausrichtung in `readProfile` addierten Löcher Material statt zu subtrahieren (z. B. `PlateModelTest`: `32904.78` statt `31095.22`) |
| `:core:brep` | 54 | *(abgebrochen)* | *(Hänger + 3+1)* | `boxWithShortSegmentTextB8EngravingIsWatertightAndComplete` hing > 29 Min. bei 1,3 GB RSS / 2,45 GB `VmData` (`MemoryLimiter > 1024 MB`), weil `B`-/`8`-Löcher in `readProfile` nicht umgedreht wurden; `MeasureTest#distanceAndAreaOnABoxWithAHole`, `BrepTest#profileWithCircularHole` und `BrepTest#boxMinusTextLikeIslandContourHasOutwardTriangleWinding` scheiterten aus demselben Grund. Nach Behebung von `readProfile` scheiterte noch `BrepTest#mirroredShapeHasOutwardTriangleWinding` (`dot=-1.0`), weil `faceGeometry` bei gespiegelten, linkshändigen `gp_Ax3`-Ebenen (`!pl.Direct()`) die umgekehrte Normale lieferte (während `tessellate` + `mesh.volume() = +6000.0` korrekt nach außen zeigten). |

### 1.2 Ursachen und Behebung (`cfa794a`)

1. **`core/brep/src/main/cpp/cadbrep.cpp` (`readProfile`):**
   In `1221112` war `fix.FixOrientation()` vor `fix.Perform()` entfernt worden. `ShapeFix_Face::Perform()` drehte bei mehreren Konturen auf `gp_Pln` die Loch-Wires nicht automatisch um, sodass Löcher als positives Material extrudiert wurden und in `boxWithShortSegmentTextB8EngravingIsWatertightAndComplete` selbstüberschneidende B-Reps erzeugten. `fix.FixOrientation()` vor `fix.Perform()` (mit `fix.FixSmallAreaWireMode() = 0`) wurde wiederhergestellt.
2. **`core/brep/src/main/cpp/cadbrep.cpp` (`faceGeometry`):**
   Nach `BRepBuilderAPI_Transform(..., Standard_True)` mit Spiegelung sind die erzeugten `gp_Pln`-Ebenen linkshändig (`!pl.Direct()`), d. h. `dS/du × dS/dv = −Axis().Direction()`. `faceGeometry` prüft nun `if (!pl.Direct()) d.Reverse();` vor der Orientierungsprüfung, sodass die gelieferten Ebenennormalen mit den Dreiecksnormalen aus `tessellate()` übereinstimmen.

### 1.3 Erneuter Lauf nach Fix `cfa794a`

| Modul | Gesamt | Bestanden | Fehlgeschlagen | Übersprungen | Hervorgehobene Tests |
| --- | ---: | ---: | ---: | ---: | --- |
| `:core:brep` | 54 | 54 | 0 | 0 | `boxWithShortSegmentTextB8EngravingIsWatertightAndComplete` (0,67 s, bestanden), `mirroredShapeHasOutwardTriangleWinding`, `boxMinusTextLikeIslandContourHasOutwardTriangleWinding`, `boxMinusCylinderHasOutwardTriangleWinding`, `profileWithCircularHole`, `MeasureTest` (1/1), `FaceNamingTest` (18/18), `RevolveTest` (8/8), `ShellTest` (2/2), `StepTest` (2/2), `LifecycleTest` (3/3) |
| `:core:model` | 95 | 95 | 0 | 0 | Alle Suiten grün (`PlateModelTest`, `PatternFeatureTest`, `TwoWayPatternTest`, `EdgeProjectionTest` 7/7, `FaceRefTest`, `MirrorBodyTest`, `DatumPlaneTest`, `ShellFeatureTest`) |
| `:core:geometry` | 17 | 17 | 0 | 0 | `KernelBoundsTest` (2/2), `KernelTest` (13/13), `ReferenceModelTest` (2/2) |
| **Summe** | **166** | **166** | **0** | **0** | |

Zusätzlich geprüft: `./gradlew.bat :app:testFossDebugUnitTest` (alle App-Unit-Tests inkl. `AndroidTextOutlinesTest` und `MeshViewportTest` bestanden).

---

## 2. Sichtprüfung in `fossDebug` (`Quader 80×50×20`, Text `'AB8'` Gravur `2 mm`)

### 2.1 Erster Befund bei `'AB8'` und Behebung (`85c5e4f`)

- Beim Anlegen von Quader `80 × 50 × 20 mm` (`Sketch 1` + `Extrusion 1`, Volumen `80,00 cm³`) und Text `'AB8'` (`Height = 12 mm`, `Cut 1 = 2 mm` auf Vorderseite `y = 0`) meldete die App auf Stand `cfa794a`:
  `2 face(s) could not be tessellated (IDs: 75, 77)`
  und Logcat zeigte:
  - `W cadbrep : BRepMesh Erstlauf unvollständig (Status=0x6); starte Delabella-Nachvernetzung`
  - `E cadbrep : Fläche 75 ohne Triangulation (Status=0x6)` (Rückwand von `'B'`)
  - `E cadbrep : Fläche 77 ohne Triangulation (Status=0x6)` (Insel-Vorderseite von `'B'`)
  - `W Workspace: Tessellierung ohne Dreiecke für Flächen: 75, 77`
- **Ursache:** `Paint.getTextPath` liefert auf Android 17 (variable Roboto-Schrift) für `'B'` eine selbstüberschneidende Innenkontur (Mittelsteg schneidet den vertikalen Stamm zweimal zwischen `x = 29,86` und `x = 30,12`). `BRepMesh` (sowohl Standard als auch Delabella) verweigert die Triangulation mit `IMeshData_SelfIntersectingWire | IMeshData_Failure` (`0x6`).
- **Behebung (`85c5e4f` in `app/src/main/kotlin/dev/androidcad/app/AndroidTextOutlines.kt`):**
  `rawPath` wird vor `PathMeasure` mittels `path.op(rawPath, Path(rawPath), Path.Op.UNION)` über Skia PathOps bereinigt. Überlappende Teilkonturen werden dadurch in einfache, disjunkte Außen- und Lochkonturen aufgelöst.

### 2.2 Ergebnisse nach Fix `85c5e4f`

| Prüfung | Ergebnis | Beleg / Screenshot | Befund |
| --- | --- | --- | --- |
| Skizze `'AB8'` auf Vorderseite | OK | [01_sketch_ab8.png](01_sketch_ab8.png) | Text `'AB8'` (`12 mm`) sauber auf Vorderseite `80 × 20 mm` platziert. |
| Ohne Schnittansicht (Iso) | OK | [02_no_section_iso.png](02_no_section_iso.png) | Vollständig schattiert, keine Fehlerleiste, keine roten oder fehlenden Flächen in `A`, `B`, `8`. |
| Ohne Schnittansicht (Frontal) | OK | [03_no_section_front.png](03_no_section_front.png) | Alle Buchstaben und Inseln von `A`, `B`, `8` vollständig geschlossen. |
| Ohne Schnittansicht (Schräg links) | OK | [04_no_section_angle_left.png](04_no_section_angle_left.png) | Gravurtiefe (`2 mm`) und Seiten-/Bodenflächen in `B`/`8` lückenlos dargestellt, keine roten Flächen. |
| Ohne Schnittansicht (Schräg unten rechts) | OK | [05_no_section_angle_right.png](05_no_section_angle_right.png) | Auch aus spitzem Blickwinkel von unten rechts keine fehlenden oder roten Flächen. |
| Mit Schnittansicht (`XY`, `10,0 mm`, umgekehrt) | OK | [06_section_xy_10mm.png](06_section_xy_10mm.png) | Schnitt durch die Mitte von `'AB8'`: Kappenfarbe (`capColor`) erscheint ausschließlich an den freigelegten Innenflächen im Schnitt; Außenseiten bleiben grau. |
| Mit Schnittansicht (`XZ`, `11,0 mm`, umgekehrt) | OK | [07_section_xz_1mm.png](07_section_xz_1mm.png) | Längsschnitt durch den Quader: Kappenfarbe ausschließlich auf den Schnitt-Innenflächen. |

---

## 3. STL- und 3MF-Export & Netzprüfung

Aus `fossDebug` über `Export STL` bzw. `Export 3MF (3D printing)` nach `/sdcard/Download/` gespeichert (`Model 1.stl`: `25.684 Bytes`, `Model 1.3mf`: `6.640 Bytes`) und per Python-Prüfskript auf geschlossene Mannigfaltigkeit (Halbkanten-/Kanten-Zählung nach Verschweißen identischer Eckkoordinaten) geprüft:

| Datei | Verschweißte Knoten `V` | Dreiecke `F` | Entartete Dreiecke | Ungerichtete Kanten `E` | Euler `V − E + F` | Jede gerichtete Halbkante `= 1×` | Gegenkante `= 1×` | Jede ungerichtete Kante `= 2×` | Volumen |
| --- | ---: | ---: | ---: | ---: | ---: | --- | --- | --- | ---: |
| `Model 1.stl` | 258 | 512 | 0 | 768 | 2 | Ja (`0` Abweichungen) | Ja (`0` Abweichungen) | Ja (`0` Abweichungen) | `79.858,68 mm³` (`79,8587 cm³`) |
| `Model 1.3mf` | 258 | 512 | 0 | 768 | 2 | Ja (`0` Abweichungen) | Ja (`0` Abweichungen) | Ja (`0` Abweichungen) | `79.858,68 mm³` (`79,8587 cm³`) |

Das exportierte Netz ist vollständig wasserdicht und frei von offenen oder mehrfach belegten Kanten.

---

## 4. Logcat-Auswertung

Nach Einspielen von Fix `85c5e4f` traten beim Öffnen, Neuaufbauen und Exportieren des Modells in `adb logcat` **keine** Einträge zu `Tessellierung`, `ohne Triangulation`, `cadbrep` oder `AndroidRuntime`-Fehlern auf.
