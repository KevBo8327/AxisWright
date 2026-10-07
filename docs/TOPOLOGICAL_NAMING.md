# Topological Naming – Prototyp und Entscheidung

**Problem:** Ein Feature wie „verrunde diese Kante“ oder „setze eine Skizze auf diese Fläche“ muss auf eine Fläche/Kante
des Körpers verweisen. Die Indizes von OpenCascade (`TopExp::MapShapes`) ändern sich bei jedem Neuaufbau – nach einer
Maßänderung zeigt „Fläche 7“ womöglich auf etwas anderes. Das ist das klassische *Topological Naming Problem*.

## Lösung: Namen aus der Modellhistorie

Jede Körperfläche trägt einen **64-Bit-Namen**, der beim Erzeugen vergeben und durch alle Operationen mitgeführt wird.

```
FaceRef(feature, role, element)  →  Feature (16 Bit) | Rolle (8 Bit) | Skizzenelement (40 Bit)
```

- **Vergabe (Extrusion):** Seitenflächen erhalten `SIDE` + ID des Skizzenelements (Linie/Bogen/Kreis), aus dem sie entstehen;
  die Stirnflächen `BOTTOM`/`TOP` (untere/obere Seite der Skizzenebene, unabhängig von der Richtung der Extrusion).
  Zuordnung Profilsegment ↔ Kante der Grundfläche über den Kurvenmittelpunkt (unabhängig von Reparaturen durch `ShapeFix`).
- **Weitergabe:** über die OCCT-Änderungshistorie – `Modified()`/`IsDeleted()` bei Booleschen Operationen und Verrundung,
  `ModifiedShape()` bei Transformationen, `Generated()` für neue Flächen (Verrundung erhält den für die Kante vergebenen Namen).
  Unveränderte Flächen leben unverändert weiter.
- **Kanten** bekommen keine eigenen Namen: Eine Kante wird über die **Namen ihrer beiden Nachbarflächen** bezeichnet
  (`edgesBetween(a, b)`). Das ist stabil, solange die Flächen es sind, und spart die Historie für Kanten.
- **Auflösen:** `BuildResult.faces(ref)` liefert die aktuellen Flächen-IDs: **eine** (normal), **keine** (weggeschnitten /
  Element gelöscht → Fehler am referenzierenden Feature melden) oder **mehrere** (eine spätere Operation hat die Fläche zerteilt).

Umsetzung: `core/brep/src/main/cpp/cadbrep.cpp` (Registry mit Namen je Fläche, `propagate`), `BrepShape.faceTags()/facesWithTag()/edgesBetween()`,
`:core:model` `FaceRef`, `Region.toProfile(feature)`, `Document.build`.

## Was die Tests zeigen (alle auf dem Emulator grün)

`FaceNamingTest` (12) und `FaceRefTest` (4):
- alle sechs Flächen eines Quaders werden richtig benannt (Lage und Normale geprüft); auch bei negativer und symmetrischer Extrusion
- **Stabilität:** „rechte Seite“, „Deckel“, „Bohrungswand der Ecke“ liegen nach Änderung von Breite, Tiefe, Dicke, Randabstand und Durchmesser
  (jeweils vollständiger Neuaufbau aus der Historie) wieder an der richtigen Stelle
- Namen überleben Schnitt mit Zylinder, Vereinigung (Zapfen auf Platte), Verschieben und Drehen und eine Kette aus Schnitt → Verschieben → Verrundung
- **Zerteilte Fläche** (Schlitz): beide Teile tragen den Namen · **weggeschnittene Fläche:** Name findet nichts; die neue Wand gehört dem Abtrag-Feature
- Kanten über Nachbarflächen: Deckel/rechts, senkrechte Ecke, Zylindernaht; gegenüberliegende Flächen → keine Kante
- Verrundung: Kante per Namen finden, Rundung erhält den Namen, Nachbarflächen behalten ihren; für zwei verschiedene Plattengrößen

## Grenzen (bekannt, nicht gelöst)

1. **Zerteilte Flächen** liefern mehrere IDs. Ein Feature, das genau eine Fläche braucht, muss entscheiden (alle? die größte?).
   Vorschlag: Referenz speichert zusätzlich einen Index nach festem Kriterium (z. B. Schwerpunkt entlang der Hauptachse).
2. **Verschmolzene Flächen** (zwei benannte Flächen werden eine): die erste gewinnt – die Referenz auf die zweite findet nichts mehr.
3. **Skizzenelement ersetzt statt verändert** (z. B. künftiges „Trimmen“ erzeugt neue IDs): Referenzen auf die Seitenfläche reißen ab.
   Trimmen/Verlängern/Verrunden müssen die ID des Ursprungselements erben.
4. **Ecken-Verrundungen** (Eckflächen an Knoten) und andere künftige Features müssen ihre neuen Flächen selbst benennen
   (`Generated`-Flächen ohne Namen bleiben unbenannt = nicht referenzierbar).
5. Namen gelten nur für Flächen, die ein **Feature der Historie** erzeugt hat; importierte STEP-Körper brauchen eine andere Strategie.
6. Keine geometrische Reparatur: Verschwindet eine Fläche, wird nicht geraten, welche „am ehesten“ gemeint war.
   Mögliche spätere Ergänzung: letzte bekannte Lage (Schwerpunkt, Normale) in der Referenz speichern und dem Nutzer
   „Fläche neu wählen“ mit Vorschlag anbieten.

## Umgesetzt: Verrundung und Fase

`FilletFeature(edges: List<EdgeRef>, size, kind)`; `EdgeRef` = zwei `FaceRef`s. `Document.build` löst jede Kante mit `edgesBetween` auf,
rundet/fast und benennt die neuen Flächen (`FaceRole.ROUND`, Element = Index der Kante). Fehlt eine Kante, entsteht ein Fehler am Feature und der
bisherige Körper bleibt erhalten. `Document.remove` entfernt Features, die Flächen eines entfernten Features referenzieren (auch in Ketten).
Im Viewer liefert `edgeFaceTags()` je Kante die Nachbarnamen; Nähte und unbenannte Flächen sind nicht wählbar.

## Umgesetzt: Bohrung

`HoleFeature(face: FaceRef, Punkt, Durchmesser, Tiefe?)`: Die Fläche wird über ihren Namen gefunden (bei zerteilten Flächen die ebene, die dem Punkt am nächsten liegt),
die Ebene kommt aus OpenCascade (`BrepShape.planarFaces()`: Punkt + Außennormale), der Punkt wird projiziert, ein Zylinder (1 mm außerhalb beginnend) abgetragen.
Wand = `FaceRef(id, SIDE, 1)`, Sackloch-Boden = `FaceRef(id, TOP)` – darauf lassen sich weitere Bohrungen setzen. Die **Position bleibt absolut** (Raumpunkt): Ändert sich die Platte
so, dass der Punkt außerhalb liegt, bohrt das Feature ins Leere – deshalb gibt es die **parametrische Position**: `HoleFeature.anchors` = zwei `EdgeDistance`s
(`EdgeRef` + vorzeichenbehafteter Abstand). Beim Aufbau werden die zwei Kanten über ihre Nachbarflächen gefunden (gerade Kanten, die in der Ebene der Fläche liegen), und
`HolePlacement.solve` löst nach dem Punkt auf. Das Vorzeichen bezieht sich auf `Außennormale × kanonische Kantenrichtung` (kleinerer Endpunkt zuerst) und ist damit unabhängig davon,
wie OpenCascade die Kante gerade orientiert. Die Kanten selbst sind über die Flächennamen stabil – die Bohrung wandert mit Maßänderungen mit. Grenzen: nur gerade Referenzkanten,
nicht parallel; die absolute Position `x/y/z` bleibt gespeichert, um bei zerteilten Flächen das richtige Stück zu wählen.

## Umgesetzt: Skizze auf Fläche

`SketchFeature.onFace: FaceRef?`: `Document.build` löst die Fläche auf, berechnet die Ebene mit `FacePlane.workplane` und stellt sie den Extrusionen der Skizze zur Verfügung (`BuildResult.sketchPlanes`);
die App übernimmt sie ins Dokument, damit Anzeige und Speicherung stimmen. Existiert die Fläche nicht mehr, meldet die Skizze einen Fehler und die zuletzt gespeicherte Ebene bleibt als Rückfall.
**Projizierte Referenzkanten:** `SketchFeature.projections` (`ProjectedEdge(EdgeRef, line | circle)`) verknüpft ein festes Konstruktionselement der Skizze mit einer Körperkante. `EdgeProjection` findet die Kante über ihre Nachbarflächen (`edgesBetween`), legt sie in die Skizzenebene (Geraden kanonisch orientiert wie bei `HolePlacement`, Kreise/Bögen als Mittelpunkt + Radius, native `edgeInfo` liefert dafür Kreismittelpunkt und Radius) und setzt Punkte und Radius vor dem Lösen neu. `BuildResult.projected` trägt die Lage in die lebende Skizze zurück (`Document.applyProjections`; der Aufbau läuft auf einer Kopie). Gelöschte Elemente werden vergessen, fehlende Kanten melden einen Fehler an der Skizze.
**Grenzen:** nur Kanten, die ganz in der Ebene liegen; nicht eindeutige Kanten (mehrere Kanten zwischen denselben Nachbarflächen) werden nicht angeboten; projiziert wird immer der gesamte Umriss der Fläche, nicht einzelne Kanten. Eigene Skizzengeometrie ohne Maß an einer Referenz bleibt an ihrer lokalen Position.

## Umgesetzt: Muster und Spiegelung

`PatternFeature(source, kind)` mit `PatternKind.Linear | Circular | Mirror`. `Document.patternTool` baut das Werkzeug der Quelle (Extrusion: `extrudeTool`, Bohrung: `holeTool` am aktuellen Körper) neu, bewegt es je Kopie (`translate`, starre `transform` als Rodrigues-Drehung um eine Achse durch einen Punkt, oder die neue native `mirror` – eine echte Spiegelung ist keine starre Bewegung und braucht eine eigene Funktion), benennt die Flächen der Kopie um (`BrepShape.retag`, native `retag`) und vereinigt alle Kopien **vor** dem Verknüpfen mit dem Körper – schlägt etwas fehl, bleibt der Körper unverändert (`combine`-Vertrag).
**Namen:** Kopie `k` (1-basiert) heißt `FaceRef(Muster-Feature, Rolle des Originals, (k shl 20) or Element des Originals)`; das Original behält seine Namen. Damit sind Kanten und Flächen jeder Kopie über Neuaufbauten stabil und lassen sich verrunden oder als Bohrungsfläche wählen. Grenzen: Elementnummern < 2^20, höchstens 200 Kopien.
**Grenzen:** Quelle muss eine Extrusion oder Bohrung sein (Muster von Mustern und Verrundungen siehe unten); Achsen/Ebenen sind Koordinatenachsen mit frei wählbarem Punkt (keine Achse durch eine Körperkante); Kopien außerhalb des Körpers schneiden nichts und melden keinen Fehler.

## Umgesetzt: Arbeitsebenen

`PlaneFeature(def, plane)` mit `PlaneDef.Offset(face, distance)` bzw. `PlaneDef.AtEdge(face, edge, angle)`. `DatumPlane.resolve` findet die Fläche über ihren Namen und die Drehachse über ihre zwei Nachbarflächen (nur gerade Kanten, die in der Fläche liegen), Offset = Flächenebene (`FacePlane`) entlang der Außennormalen verschoben, AtEdge = Ebene durch die Kante, Ursprung am kanonischen Kantenanfang, x entlang der Kante, Normale um den Winkel um die Kante gedreht (`n·cos t + (d × n)·sin t`). Skizzen mit `onPlane` übernehmen die Lage beim Aufbau (`BuildResult.datumPlanes`); fehlt die Fläche, meldet die Ebene einen Fehler und die zuletzt gespeicherte Lage bleibt. Löschen der Fläche/Kante entfernt die Ebene samt ihrer Skizzen.

## Umgesetzt: Muster von Mustern

Quelle eines Musters darf ein einfaches Muster sein (eine Verschachtelungsebene, geprüft beim Anlegen und Laden). Das Werkzeug der Quelle ist dann ihr ganzes Ergebnis (Werkzeug des Originals ∪ Kopien, `patternResultTool`), die Operation kommt vom ursprünglichen Feature (`patternOp`). `FaceRef.element` hat jetzt 40 Bit (`Long`, mit `Int`-Konstruktor für bestehenden Code); einfache Kopien heißen wie bisher `(k shl 20) or e` (gespeicherte Modelle bleiben gültig), verschachtelte `((j · 256 + k) shl 20) or e` mit äußerer Kopie j ≥ 1 und innerer Kopie k (0 = inneres Original) – immer eindeutig und von einfachen Kopien unterscheidbar (`PatternGeometry.nestedCopyTag`).

## Umgesetzt: Muster von Verrundungen

Mit `PatternFeature.withFillets` wendet `Document.build` nach dem Muster die Verrundungen/Fasen der Quelle (`Document.patternFillets`: zwischen ursprünglichem Feature und – innerem – Muster, jede Kante hängt an einer Fläche des Originals) erneut an: Für jede Kopie werden die Kantenreferenzen umgeschrieben – Flächen des Originals → `FaceRef(Muster, Rolle, Kopie-Element)` (`copyElement` bzw. `nestedElement` für alle j, k), andere Flächen (z. B. die Plattenoberseite) bleiben – und über `edgesBetween` gesucht. Die neuen Rundungsflächen heißen `FaceRef(Muster, ROUND, Kopie-Element von (Verrundung · 1024 + Kante))`, bleiben also je Kopie stabil. Kopien, deren Kanten es nicht gibt (außerhalb des Körpers), werden übersprungen; schlägt die Verrundung fehl, meldet das Muster einen Fehler und behält die Kopien ohne Rundung.

## Umgesetzt: Körper spiegeln

`MirrorBodyFeature(origin, normal, datum)` spiegelt den bisherigen Körper (native `mirror`) und vereinigt ihn mit dem Original. Die Flächen des Spiegelbilds werden umbenannt (`MirrorNaming.mirrorTag`): Feature = die Spiegelung, Rolle bleibt, Element = `(Feature des Originals shl 25) or Element des Originals` – eindeutig, umkehrbar (`MirrorNaming.original`) und stabil über Neuaufbauten. Elemente ab 2²⁵ (Kopien großer/verschachtelter Muster, Spiegelbilder einer früheren Spiegelung) bleiben im Spiegelbild unbenannt; der Körper stimmt trotzdem, nur sind diese Flächen nicht als Referenz wählbar. Danach führt `unifyFaces` Flächen derselben Fläche zusammen (keine Naht): Jede Fläche hat einen **Hauptnamen** und beliebig viele **Zweitnamen**; die zusammengeführte Fläche trägt den kleinsten beteiligten Namen (ältestes Feature) als Haupt-, alle anderen als Zweitnamen. `facesWithTag` und `edgesBetween` suchen in beiden, `propagate` reicht beide weiter (trifft eine Ergebnisfläche auf mehrere benannte Vorgänger, werden die weiteren Namen Zweitnamen statt verloren zu gehen), `retag` bildet beide ab. Referenzen auf eine der beiden Hälften bleiben damit gültig. Mit Arbeitsebene folgt die Spiegelebene deren aufgelöster Lage (`BuildResult.datumPlanes`); wird die Ebene gelöscht, fällt die Spiegelung mit weg.

## Umgesetzt: Linearmuster mit zwei Richtungen

`PatternKind.Linear` hat optional `direction2/count2/spacing2`. Die Kopien werden zeilenweise gelegt; Kopie in Spalte a, Zeile b bekommt die Nummer `b · 256 + a` (`PatternGeometry.instances`), ihre Flächen heißen also `((b · 256 + a) shl 20) or Element` – dieselbe Form wie verschachtelte Muster (`nestedElement(b, a, e)`). Die erste Zeile (b = 0) heißt damit genau wie eine einfache Reihe (Umschalten zerstört keine Referenzen), und mehr oder weniger Zeilen lassen die Namen der übrigen Kopien unverändert. Weil die Namen schon beide Ebenen belegen, darf ein Raster weder Quelle eines weiteren Musters sein noch ein Muster vervielfältigen.

## Entscheidung für das Feature-Modell (M3)

- Features, die Flächen/Kanten brauchen (Verrundung, Fase, Skizze-auf-Fläche, Bohrung), speichern **`FaceRef`** (Kante = zwei `FaceRef`s)
  statt Indizes. Serialisierung: drei Zahlen je Referenz (`feature`, `role`, `element`) im `model.json`.
- `Document.build` löst die Referenzen **vor** dem jeweiligen Feature auf; unauflösbare erzeugen einen Fehler am Feature (rot in der Feature-Leiste),
  der Rest der Historie wird weiter gebaut (wie bisher).
- Jedes neue Feature, das Flächen erzeugt, **muss sie benennen** (Rolle + Element); dafür gibt es eine Prüfung im Test (jede Fläche des Körpers hat
  einen Namen, solange nur Features mit Namensvergabe beteiligt sind).
- Auswahl im Viewport: Tippen liefert die Flächen-ID des Netzes (`triangleFace`) → `faceTags()[id − 1]` → `FaceRef` → gespeichert.
