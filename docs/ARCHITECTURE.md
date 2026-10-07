# Architektur

```
:app (Compose, Android)
  CadScreen ── adaptive Layouts (Breakpoints 600/840 dp), Beispiele / Öffnen / STL-Export
  CadState  ── Quelltext, Overrides, Entprellung, Cancel;
               ruft CadLang.render + GeometryKernel.build auf Dispatchers.Default
  CustomizerPanel / MeshViewport (OpenGL ES 3) / WireViewport (Fallback) / Editor + Snippet-Leiste + Konsole
        │
:core:project (reines Kotlin/JVM, explicitApi)
  ProjectRepository ── Dateiablage je Projekt (project.scad + project.properties), atomare Schreibzugriffe
  UndoHistory<T>    ── Schnappschuss-Verlauf mit Zusammenfassen gleicher Änderungen (Tippen, Slider)
        │
:core:geometry (Android-Library, NDK/CMake)
  Mesh (Volumen, Hülle, STL) · ProgramEncoder (Node-Baum → Postfix-Programm) · GeometryKernel
  libcadgeom.so  ── JNI: ein Aufruf nativeBuild(DoubleArray) → [positions, triangles]
      Stack-Interpreter (cadgeom.cpp) über Manifold (3D) + CrossSection/Clipper2 (2D)
        │
:core:lang (reines Kotlin/JVM, explicitApi)
  Lexer → Parser → AST → Evaluator → Node-Baum (Csg.kt)
  Customizer: ParamSpec-Liste aus Kommentar-Annotationen
  CadLang: render(source, overrides, files, isCancelled), parameters(source)

third_party/  manifold (v3.5.4, Apache 2.0) · Clipper2 (Boost) – im Repo, kein Download beim Build
```

## Skizzier-Oberfläche (`:core:sketchedit` + `:app`)

```
Zeigerereignisse (Maus/Stift/Touch, Pixel)                    Compose (nur Darstellung und Eingabe-Weiterleitung)
   SketchCanvas ──pointerDown/Move/Up, hover, zoom, pan──►  SketchEditor  (reines Kotlin, JVM-getestet)
                                                              ├─ Tool-Zustandsautomat: Auswahl · Linie · Rechteck · Kreis · Bogen
                                                              ├─ Snapping (Punkt › Mitte › Kurve › Achse › Raster) + HitTest
                                                              ├─ Auto-Constraints, ConstraintActions (nach Auswahl), Maße
                                                              ├─ Ziehen: DragSession des Solvers
                                                              ├─ Verlauf: UndoHistory<Sketch> (Snapshots, `Sketch.restore` in place)
                                                              └─ onChange (Neuzeichnen) · onSketchChanged (Geometrie → 3D-Neuaufbau)
EditorHandle: onChange → version++ (Compose-Beobachtung)    WorkspaceState: Dokument + Editor + Extrusion + Build-Ausgabe
```

- **Ein Editor-Zustandsautomat statt Gesten im UI:** Die Interaktionslogik (inkl. Fangen, Auto-Constraints, Ziehen, Verlauf) hängt weder an
  Compose noch an Android und ist mit simulierten Zeigerereignissen testbar (42 Tests). Compose übersetzt nur Eingaben und zeichnet.
- **Stift/Maus/Touch** werden als `PointerKind` unterschieden: Stift zieht = eine Linie (kein Linienzug), Touch hat größere Trefferzonen und
  Zwei-Finger-Zoom, Maus hat Hover-Fang, Mausrad und Tastenkürzel; das Radierer-Ende des Stifts löscht.
- **Abgelehnte Aktionen**: Eine Constraint-/Maß-Aktion wird auf einer Kopie ausprobiert (hinzufügen, lösen); bei Widerspruch oder wenn sie nichts
  Neues sagt, wird sie zurückgenommen und die Ursache gemeldet – die Skizze bleibt konsistent.
- **Degenerierte Lösungen** (z. B. „horizontal + vertikal“ → Linie wird zum Punkt) gelten als Widerspruch.
- **Nebenläufigkeit:** Die Skizze wird nur auf dem UI-Thread verändert. Der 3D-Neuaufbau arbeitet auf `Document.snapshot()` im Hintergrund.
- Stolperstein Compose: Ein **ungenutzter** Composable-Parameter löst beim Überspringen von Rekompositionen nichts aus – Zustand, der eine Änderung
  anzeigen soll (z. B. der Dialogzustand), muss als echter, im Rumpf benutzter Parameter übergeben werden.

## Modell: Skizze → Körper (`:core:model`)

```
Document (Feature-Historie)                        Build:  für jedes Feature der Reihe nach
  SketchFeature(plane, Sketch)  ──solve──►  Constraints erfüllt, Maße gelten
  ExtrudeFeature(sketch, distance, op, regionPoints, symmetric)
        │  regionPoints → Sketch.regionAt(x, y) → Region ──toProfile()──► Profile (Linien/Bögen/Kreise, Löcher)
        │  BrepShape.extrude(profile, distance, symmetric) ──transform(Workplane)──► Körper im Raum
        └─ ADD: body ∪ tool    CUT: body − tool        Fehler je Feature → BuildResult.errors, Aufbau läuft weiter
BuildResult.body (BrepShape, OpenCascade) ──tessellate──► Dreiecke + Flächen-ID je Dreieck + Kanten-Polylinien
```

- **Parametrik** heißt: Maße der Skizze (`Sketch.setDimension`) und Features (`ExtrudeFeature.copy(distance = …)`) ändern, dann
  `build()` – die Historie wird komplett neu abgespielt (derzeit ~10 ms für ein Teil mit wenigen Features).
- **Flächenauswahl über Punkte** (statt Listenindex): Ein Auswahlpunkt in Skizzenkoordinaten bleibt gültig, wenn Maße die Skizze verändern.
- **Arbeitsebene**: lokale x/y/Normale rechtshändig; `XZ` hat Normale −Y, damit lokales y = +Z; Extrusion läuft entlang der Normalen.
- Vorsicht (noch offen): Referenzen auf Flächen/Kanten des Körpers (Verrundung, Skizze auf Fläche) brauchen stabile Namen
  (Topological Naming); die IDs aus der Tessellierung gelten nur innerhalb eines Körpers.

## Skizzen-Engine (`:core:sketch`)

Reines Kotlin/JVM, keine Android-Abhängigkeit. Ein `Sketch` hält Punkte, Linien, Kreise, Bögen und `Constraint`s
(unveränderliche Werte mit typisierten IDs); die Koordinaten sind die **Startwerte** des Solvers.

```
Sketch ──compile──► Gleichungssystem  F(x)=0   (x = Koordinaten aller Punkte + Kreisradien; Eq = Gruppe von Residuen
                                                  mit eigener Variablenliste, wird numerisch abgeleitet)
        ──Lm──────► Levenberg-Marquardt: min ½‖F‖², Dämpfung λ → kleinste Änderung gegenüber der Zeichnung
        ──analyze─► JᵀJ → Jacobi-Eigenzerlegung: Rang/Freiheitsgrade, Nullraum → „bestimmte“ Variablen;
                    Gram-Schmidt über die Zeilen → überflüssige Constraints
DragSession ──────► 1) Constraints stark gewichtet + weiches Ziehziel, 2) Politur ohne Ziehziel (exakt, 1e-9)
regions() ────────► Union-Find der Punkte (Coincident / Abstand ≤ 1e-6) → Ketten mit Grad 2 → Konturen,
                    exakte Fläche (Greensche Formel), Verschachtelung über Stützpunkt-Polygone
```

## Entscheidungen

| Skizzen-Solver | Eigener LM-Solver statt SolveSpace (GPL) / planegcs (LGPL, C++/Eigen) | Keine Lizenz- und NDK-Last, voll testbar auf der JVM, ausreichend schnell (8×8-Gitter: 3,4 ms je Ziehschritt) |
| Ableitungen | Numerisch (zentrale Differenzen) je Gleichungsblock über dessen eigene Variablen | Wenig Code, keine Ableitungsfehler; Kosten bleiben klein, da jeder Block nur wenige Variablen hat. Bei sehr großen Skizzen durch exakte Ableitungen ersetzbar |
| Ziehen | Zweiphasig: gewichtetes Ziehen + Politur | Constraints sind nie verletzt, das Ziel wird bestmöglich angenähert; bei Widerspruch bleibt der letzte gültige Stand |
| Überbestimmung | Widerspruch = LM konvergiert nicht (Skizze bleibt unverändert, Verdächtige gemeldet); Redundanz = Zeile linear abhängig von früheren | Reihenfolge der Constraints bestimmt, welches als „überflüssig“ gilt |
| Bogen | Mittelpunkt + Start + Ende, immer gegen den Uhrzeigersinn; Radius implizit (interne Gleichung `‖s−c‖ = ‖e−c‖`) | Keine eigene Radiusvariable nötig; Orientierung eindeutig |
| Punktidentität | Gemeinsame `PointId` oder `Coincident`-Constraint (kein Zusammenführen/Umschreiben von Referenzen) | Einfach, rückgängig-fähig; Regionen fassen verbundene Punkte per Union-Find zusammen |

## Entscheidungen (App-Rahmen)

| Thema | Entscheidung | Grund |
|---|---|---|
| Sprachkern | Eigener Interpreter in Kotlin, JVM-testbar | GPL-Vermeidung; schnelle Tests ohne Gerät; Evaluation ist nicht der Flaschenhals (Geometrie ist es) |
| Ergebnis der Auswertung | Reiner Datenbaum (`Node`), Segmentanzahl (`$fn/$fa/$fs`) bereits aufgelöst | Geometriekern bleibt austauschbar und kennt keine Sprachsemantik |
| JNI-Schnittstelle | Flaches Postfix-Programm (`DoubleArray`), **ein** Aufruf je Auswertung, Netz als Arrays zurück | Keine Handles/Lebenszyklen über die Sprachgrenze, wenig JNI-Overhead, leicht testbar |
| Orientierung von Polyedern | Signiertes Volumen prüfen, bei Bedarf Dreiecke umdrehen | OpenSCAD korrigiert stillschweigend; reale Modelle liefern beide Windungsrichtungen |
| Transformationen | Eine `Mat4` pro `Transform`-Knoten | Einheitliche Schnittstelle zu Manifold |
| Abbruch | `isCancelled`-Lambda im Sprachkern (je Anweisung); nativer Aufruf selbst nicht unterbrechbar | Slider-Ziehen verwirft alte Auswertungen; Geometrie dauert typisch < 100 ms |
| Ressourcenlimits | Rekursionstiefe 400, Bereiche ≤ 5 Mio. Elemente | Schutz vor Endlosschleifen in Nutzercode |
| Customizer | Eigene Annotation-Auswertung, Werte als Literal-Strings | Überschreibungen laufen durch denselben Parser wie Quelltext |
| 3D-Ansicht | GLSurfaceView, ES 3, orthografisch, Flat-Shading per `dFdx/dFdy` | Echter Tiefenpuffer; keine Normalen-Puffer; Maler-Algorithmus auf der Canvas versagte bei großen Dreiecken |
| Native Builds | `-O2` auch in Debug; 16-KB-Page-Größe; `c++_shared`; abiFilters arm64-v8a + x86_64 | Aussagekräftige Messungen; Play-Pflicht; Googlebooks mit Intel |
| UI-Breakpoints | Fensterbreite statt Gerätetyp | Googlebook-Fenster sind frei skalierbar |
| Projektablage | Interner Speicher (`filesDir/projects/<id>/`), mehrere Projekte, Import/Export per SAF statt Schreibzugriff auf fremde Dateien | Funktioniert ohne Berechtigungen, robust gegen Prozess-Tod; kein Rückschreiben in externe Dokumente |
| Autosave | 700 ms nach der letzten Änderung (Entprellung) + synchron in `onStop`/vor Projektwechsel; Versionszähler gegen Überholen | Kein Datenverlust beim Hintergrundgehen, keine Schreibflut beim Tippen |
| Undo/Redo | Schnappschüsse (Text, Cursor, Parameterwerte), Zusammenfassen per Schlüssel (`text`, `param:<name>`) innerhalb 800 ms, max. 200 Schritte | Slider-Ziehen und Tippen = je ein Schritt; Cursor wird wiederhergestellt. Verlauf wird nicht gespeichert |
| Zustand | `CadState` lebt in der Activity (`lifecycleScope`), nicht in der Komposition | Übersteht Konfigurationswechsel; nach Prozess-Tod aus dem zuletzt geöffneten Projekt rekonstruiert |

## Teststrategie
- `:core:lang` JVM-Unit-Tests (Parser, Evaluator, Customizer, Referenzmodell in allen Modi).
- `:core:geometry` Instrumented Tests: analytische Volumina (Würfel, Zylinder-n-Eck, Pyramidenstumpf, Torus, Polyeder-Tetraeder …),
  Fehlerpfade, STL-Größe, und **Gate A**: Volumenvergleich aller Teile der Halterung mit von OpenSCAD erzeugten STLs.

## Offene technische Fragen
- Multithreading (Manifold-TBB) – Nutzen auf Tablet-CPUs vs. Größe/Komplexität.
- Googlebook: ABI (x86_64 vermutlich), Dateizugriff (SAF-Ordner), Fensterverhalten – auf echter Hardware prüfen.
- Unterbrechbare native Aufrufe (Manifold-Kontext/Cancel) für sehr große Modelle.
- Filament als Renderer vs. aktueller schlanker GL-Renderer.

## Skizzenbearbeitung (`SketchOps`)

`core:sketch/SketchOps.kt` enthält die geometrische Schicht für Trimmen, Verlängern, Versatz, Eckenverrundung und Spiegeln (ohne Oberfläche, vollständig auf der JVM getestet). Grundlage sind Schnittpunkte zwischen Strecken, Kreisen und Bögen (`Curve`). Neue Punkte an Schnittstellen verwenden vorhandene Punkte wieder oder werden per `PointOnLine`/`PointOnRound` an die schneidende Kurve gebunden; Maße, die durch das Kürzen ungültig werden (Länge, Gleichlänge, Mittelpunkt), entfallen. Elementkennungen bleiben, wo es geht (die erste Hälfte einer getrimmten Linie behält ihre ID), damit Flächennamen der Extrusion stabil bleiben. Der `SketchEditor` führt jede Operation über `runOp` aus: Kopie der Skizze → Operation → Lösen → bei Fehler oder Widerspruch wiederherstellen, sonst festschreiben (Verlauf).
Grenzen: Versatz einer Linienkette endet an Knoten mit Bögen/Kreisen; offene Ketten behalten ein Gleiten der Enden; Eckenverrundung nur zwischen zwei Linien; Fase und Linien-Muster in 2D fehlen.

## Bildschirmaufbau (Modellmodus)

`ModelScreen.kt`: App-Leiste (Modellmenü, Volumen/Flächen und „STL exportieren“ – schmal im ⋮-Menü –, „Verlauf“, ⋮ → Skript-Editor). Das **3D-Bild füllt die ganze Fläche**; alles andere schwebt darüber und erscheint nur, wenn es gebraucht wird: `ToolGroups` (vier Gruppen Skizze/Körper/Kanten/Muster, ein Tipp klappt die Werkzeuge der Gruppe als `ToolStrip` auf; breit links, schmal unten mit schwebender Leiste), `StepChip` (aktueller Schritt, ‹ › blättert, Tipp öffnet den Verlauf), `HistoryCard` (ganzer Modellaufbau, `FeatureList`; offen/zu merkt `UiPrefs`), `SettingsCard` (Einstellungen des gewählten Features bzw. laufenden Werkzeugs, `FeatureControls`; einklappbar, ✕ = `WorkspaceState.closePanel`, sichtbar nach `panelVisible`), `ViewMenu` (Standardansichten, Einpassen), Fehlerband oben, Achsenkreuz unten links. Unter 720 dp Breite oder im Hochformat stehen die Werkzeuge unten und Einstellungen bzw. Verlauf als Blatt **unter** dem Bild (das Bild wird kleiner statt überdeckt); sonst schweben sie als Karten (Einstellungen oben rechts, Verlauf links), und das 3D-Bild rückt neben die offenen Karten, damit das Modell im freien Bereich zentriert ist. Die Kopfzeile wird erst unter 600 dp kompakt (Volumen und STL im ⋮-Menü). Der Skript-Editor ist ein eigener Bildschirm (`AppRoot`, „← Modell“, Zurück-Taste). Die Fläche reicht bis unter die Systemleisten, damit sie im Dunkelmodus nicht hell bleiben.

## Bildschirmaufbau (Skizzenmodus) und Symbole

Skizzenmodus (`SketchPanel`): Die **Zeichenfläche füllt den ganzen Bildschirm**. Kopfzeile `SketchTopBar` (Skizze, Rückgängig/Wiederholen/Löschen, „Fertig“; Ebene, „Ebene …“ bzw. „Kanten projizieren“, Raster und Einpassen breit als Knöpfe, schmal im ⋮-Menü). Werkzeuge über die gemeinsame `GroupBar` (Auswahl und Mehrfach direkt, Zeichnen/Bearbeiten/Muster zum Aufklappen; breit links, schmal unten mit schwebender `GroupFlyout`). Status als Plakette oben (`StatusPill`), Editor-Meldungen als Band darüber (`MessageBand`). `ContextBar` schwebt nur bei Bedarf: mit Auswahl die Bedingungen und Maße **direkt unter der Auswahl** (ohne Platz darunter darüber; Lage-Aktionen unter „Ausrichten ▾“), bei Zeichen- und Bearbeitungswerkzeugen Hinweis und Felder unten mittig. „Einpassen“ (`EditorHandle.fit`) hält links die Werkzeugleiste und oben die Plakette frei; beim Drehen (hoch ↔ quer) passt die Zeichenfläche neu ein, bei kleineren Größenänderungen bleibt die Bildmitte.
Symbole: `CadIcons.kt` zeichnet alle Werkzeugsymbole als dünne Linien im 24×24-Raster (SVG-Pfade, gestrichelte Teile für „wegfallend/neu“), in der Farbe des Knopfes – statt Unicode-Zeichen, die je nach Schrift unterschiedlich groß waren. Dieselben Symbole stehen in beiden Werkzeugleisten, im Feature-Baum und in den Überschriften der Seitenleiste.

## Gemeinsame Projektverwaltung (`ProjectHub`)

Modelle (`ModelRepository`, `files/models`) und Skripte (`ProjectRepository`, `files/projects`) behalten ihre eigene Ablage – vorhandene Dateien müssen nicht umziehen. `ProjectHub` führt sie für die Oberfläche zusammen: eine Liste aller Projekte (zuletzt geändert zuerst, Art als Symbol), Öffnen wechselt in den passenden Editor, Neu/Umbenennen/Duplizieren/Löschen wirken auf das aktive Projekt; welche Art zuletzt aktiv war, steht in `files/active-kind` und wird beim Start wieder geöffnet. Beide Editoren haben dieselbe App-Leiste mit dem gemeinsamen Projektmenü (`ProjectMenu`: Neues Modell/Skript, Beispiele beider Arten, Skript-Import, zuletzt bearbeitet, „Alle Projekte …“, Umbenennen/Duplizieren/Löschen). Die frühere Projektleiste des Skript-Editors (Projekte-Dialog, Namensfeld, Beispiele/Import/Export) ist darin aufgegangen; Rückgängig/Wiederholen des Quelltexts stehen als Symbole in der App-Leiste, „Quelltext exportieren“ im ⋮-Menü.
