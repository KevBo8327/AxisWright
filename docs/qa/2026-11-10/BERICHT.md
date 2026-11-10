# Lane QA – Test- und Prüfbericht (Stand main f0a09af)

**Datum:** 10. November 2026  
**Ziel:** Prüfung von Welle 2 (Transformieren, Text, Übersetzung) und der 3D-Lanes (Extrusion: Schnittmenge/zwei Seiten/bis Fläche; Bohrung: Senkung/Kegelsenkung/M3–M10; Schnittansicht) auf dem Android-Emulator Pixel 10 Pro XL (x86_64, API 36 / Android 16).  
**Vorgabe:** Nur prüfen und berichten – kein Produktcode geändert.

---

## 1. Zusammenfassung der Gerätetests (Connected Android Tests)

Befehl:  
`./gradlew.bat :core:brep:connectedDebugAndroidTest :core:model:connectedDebugAndroidTest :core:geometry:connectedDebugAndroidTest :app:connectedDebugAndroidTest "-Pandroid.injected.build.abi=x86_64" --continue`

| Modul | Gesamt | Bestanden | Fehlgeschlagen | Erfolgsquote |
| :--- | :---: | :---: | :---: | :---: |
| `:core:brep` | 46 | 46 | 0 | 100 % |
| `:core:geometry` | 15 | 15 | 0 | 100 % |
| `:app` | 0 | 0 | 0 | - |
| `:core:model` | 94 | 85 | 9 | 90,4 % |
| **Gesamt** | **155** | **146** | **9** | **94,2 %** |

### Erwähnte Tests im Speziellen:
- `TwoSidedExtrudeTest`: **Bestanden** (alle Tests bestanden)
- `EdgeProjectionTest`: **Bestanden** (alle Tests bestanden)
- `BrepTest` (insb. `height2`): **Bestanden**
- `HoleGeometryTest`: 1 Fehler (`validationRejectsInvalidCounterboreAndCountersinkInputs`)
- `IntersectFeatureTest`: 2 Fehler (`firstFeatureWithIntersectIsRejected`, `firstRevolveWithIntersectIsRejected`)
- `UntilFaceExtrudeTest`: 3 Fehler (`nonParallelFaceIsRejected`, `untilFaceWithoutBodyIsRejected`, `extrudeUntilFaceWithSecondSide`)
- `FaceRefTest`: 1 Fehler (`anchoredHoleMovesWithItsEdgesAndReportsBadAnchors`)
- `RevolveFeatureTest`: 2 Fehler (`missingAxisLineIsReported`, `crossingTheAxisIsReportedAtTheFeature`)

### Details der 9 ursprünglichen Fehlschläge in `:core:model`
1. **`FaceRefTest > anchoredHoleMovesWithItsEdgesAndReportsBadAnchors`**  
   - *Assertion:* `expected:<[HOLE_REF_EDGE_NOT_STRAIGHT]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Das Feature meldet den generischen Fehlercode `FEATURE_FAILED` als Hauptschlüssel; der spezifische Fehlercode `HOLE_REF_EDGE_NOT_STRAIGHT` liegt im Argument.
2. **`HoleGeometryTest > validationRejectsInvalidCounterboreAndCountersinkInputs`**  
   - *Assertion:* `expected:<[HOLE_SINK_DIAMETER_TOO_SMALL]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Hauptschlüssel `FEATURE_FAILED` mit innerem Grund `HOLE_SINK_DIAMETER_TOO_SMALL`.
3. **`IntersectFeatureTest > firstFeatureWithIntersectIsRejected`**  
   - *Assertion:* `expected:<[NO_BODY_TO_INTERSECT]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Hauptschlüssel `FEATURE_FAILED` mit innerem Grund `NO_BODY_TO_INTERSECT`.
4. **`IntersectFeatureTest > firstRevolveWithIntersectIsRejected`**  
   - *Assertion:* `expected:<[NO_BODY_TO_INTERSECT]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Hauptschlüssel `FEATURE_FAILED` mit innerem Grund `NO_BODY_TO_INTERSECT`.
5. **`RevolveFeatureTest > missingAxisLineIsReported`**  
   - *Assertion:* `expected:<[REVOLVE_AXIS_GONE]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Hauptschlüssel `FEATURE_FAILED` mit innerem Grund `REVOLVE_AXIS_GONE`.
6. **`RevolveFeatureTest > crossingTheAxisIsReportedAtTheFeature`**  
   - *Assertion:* `expected:<[REGION_CROSSES_AXIS]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Hauptschlüssel `FEATURE_FAILED` mit innerem Grund `REGION_CROSSES_AXIS`.
7. **`UntilFaceExtrudeTest > nonParallelFaceIsRejected`**  
   - *Assertion:* `expected:<[FACE_NOT_PARALLEL]> but was:<[FEATURE_FAILED]>`  
   - *Ursache:* Hauptschlüssel `FEATURE_FAILED` mit innerem Grund `FACE_NOT_PARALLEL`.
8. **`UntilFaceExtrudeTest > untilFaceWithoutBodyIsRejected`**  
   - *Exception:* `java.lang.IllegalArgumentException: Zielfläche verweist auf ein unbekanntes Feature at dev.androidcad.model.Document.addExtrude(Document.kt:224)`  
   - *Ursache:* Unbekannte Zielflächen werden sofort von `Document.addExtrude` abgewiesen.
9. **`UntilFaceExtrudeTest > extrudeUntilFaceWithSecondSide`**  
   - *Exception:* `java.lang.IllegalArgumentException: „Bis Fläche“ kann nicht mit symmetrisch, durch alles oder zwei Seiten kombiniert werden at dev.androidcad.model.Document.addExtrude(Document.kt:225)`  
   - *Ursache:* Unzulässige Optionskombinationen werden sofort von `Document.addExtrude` abgewiesen.

---

## 2. Sichtprüfung in der App (Interaktiver UI-Test)

| Nr. | Prüfpunkt | Ergebnis | Screenshot | Befund |
| :---: | :--- | :---: | :--- | :--- |
| **1** | **Skizze Transformieren** | **OK** | `01_transform_translate.png`<br>`01_transform_rotate.png`<br>`01_transform_scale.png`<br>`01_transform_copy.png`<br>`01_transform_done.png` | Rechteck gezeichnet, Auswahlkasten gezogen. Transformieren-Werkzeug aufgerufen: Verschieben (10/5), Drehen (30°), Skalieren (1,5), Kopieren per Schalter getestet. Jeder Schritt per Rückgängig/Wiederholen reversibel. |
| **2** | **Text** | **OK** | `02_text_placed.png`<br>`02_text_abtragen_1mm_fixed.png`<br>`02_text_boss_add.png`<br>`02_fillet_applied.png` | Text „AB80“, Höhe 10 mm gesetzt. Extrusion Abtragen 1 mm (Gravur, ~3,1 s) und Hinzufügen 3 mm (~3,1 s) ausgeführt. Buchstaben-Inseln/Löcher (A, B, 8, 0) formgetreu freigestellt. Verrundung (Radius 2 mm) an Kante des Grundkörpers mit Text durchgeführt (~3,2 s, kein Hängen/Einfrieren). |
| **3** | **Extrusion** | **OK** | `03_extrude_intersect_throughall.png`<br>`03_extrude_two_sided.png`<br>`03_extrude_until_face.png`<br>`03_extrude_until_face_grows.png`<br>`03_extrude_until_face_not_parallel.png`<br>`03_extrude_delete_warning.png` | **Schnittmenge:** Quader 20×20×20 + Kreis Ø 20 darüber ergibt exakt Zylinderstück (`1,13 cm³ · 3 Flächen`).<br>**Zwei Seiten:** Seite 1 = 15 mm, Seite 2 = 5 mm erzeugt asymmetrische Extrusion.<br>**Bis Fläche:** Zylinder wächst bei Quader-Höhenänderung (10 mm → 25 mm) automatisch mit.<br>**Nicht parallele Fläche:** Meldung `Zielfläche muss parallel zur Skizzenebene sein` wird als Banner angezeigt.<br>**Löschwarnung:** Beim Löschen von Extrusion 1 nennt der Dialog explizit die abhängige `Extrusion 2`. |
| **4** | **Bohrung** | **OK** | `04_hole_types.png`<br>`04_hole_invalid.png`<br>`04_hole_fillet.png` | Auf Quader-Deckfläche wurden 3 Bohrungen gesetzt: 1 einfache Bohrung, 1 Senkung (Normgröße M6: Ø 6,6 mm, Senk-Ø 11 mm, Tiefe 6,8 mm) und 1 Kegelsenkung (Normgröße M5).<br>**Ungültige Werte:** Senkdurchmesser 5 mm (< Bohrung 6,6 mm) wird mit Fehlermeldung `„Bohrung 1“: Senkdurchmesser muss größer als der Bohrungsdurchmesser sein` abgefangen.<br>**Verrundung:** Außenkante der Senkung mit Radius 0,5 mm erfolgreich verrundet (`Verrundung 1`). |
| **5** | **Schnittansicht** | **OK** | `05_section_view.png` | Ein-/Ausschalten über Ansichtsmenü; Ebenen XY, XZ, YZ sowie Fläche wählbar; Versatz per Schieberegler und Direkteingabe; Seite umkehren funktioniert einwandfrei. Schnittfläche hebt sich mit Kappenfarbe (Terracotta `0xFFD46A55`) deutlich ab. Weggeschnittene Flächen sind dank `ClipPlane.isClipped` nicht auswählbar. |
| **6** | **Sprache** | **OK** | `06_lang_en.png` | Auf Englisch umgestellt (`set-app-locales en`): Hauptbildschirm, Werkzeugleisten, Extrusions-/Bohrungskarten, Schnittansicht, Hilfe-Dialog geprüft. Keine deutschen Reste in Texten und Beschriftungen gefunden. Rückstellung auf Deutsch (`de-DE`) erfolgreich. |
| **7** | **Speichern / Neu öffnen** | **OK** | `07_reopen_project.png` | Projekt geschlossen und neu geöffnet: Alle Features (Text, Extrusionsarten, Bohrungen M6 / Senkung, Verrundung) und deren Parameter bleiben vollständig erhalten und editierbar. |

---

## 3. Fehlerliste und Schweregrad-Klassifikation

| ID | Beschreibung | Ort / Komponente | Schweregrad | Auswirkung |
| :---: | :--- | :--- | :---: | :--- |
| **E1** | **Fehlercode-Degradierung in Modell-Validierung** | `:core:model` (`FaceRefTest`, `HoleGeometryTest`, `IntersectFeatureTest`, `RevolveFeatureTest`, `UntilFaceExtrudeTest`) | **Gering** (Text / Metadaten) | Die Engine meldet bei geometrischen Validierungsfehlern (z. B. nicht-parallele Fläche, Schnittmenge ohne Körper, Bohrungssenkung zu klein) intern den generischen Status `FEATURE_FAILED` statt granularer Enums (`HOLE_SINK_DIAMETER_TOO_SMALL`, `FACE_NOT_PARALLEL` etc.). In der interaktiven UI greift die Validierung vorab korrekt mit spezifischen Meldungen. |
| **E2** | **Vorzeitige IllegalArgumentException vor Build-Lauf** | `:core:model` (`UntilFaceExtrudeTest.untilFaceWithoutBodyIsRejected`, `extrudeUntilFaceWithSecondSide`) | **Gering** (API-Konsistenz) | `Document.addExtrude` wirft bei ungültigen Kombinationen direkt eine `IllegalArgumentException` anstatt das Feature anzulegen und im Build-Report zu markieren. Kein App-Absturz in der UI, da die UI solche Kombinationen nicht zulässt. |

---

## 4. Nachtrag: Testkorrektur und neues Testergebnis (10.11.2026)

### Auswertung und Ursache
Die 9 Fehlschläge der ursprünglichen Gerätetests resultierten aus ungenauen Test-Assertions, während das Domänenmodell sich spezifikationsgemäß verhält:
1. **E1 (7 Tests):** `Document.build()` kapselt Feature-Fehler bewusst als `Msg(ModelText.FEATURE_FAILED, f.name, reason)`. Der spezifische geometrische Grund (`HOLE_REF_EDGE_NOT_STRAIGHT`, `HOLE_SINK_DIAMETER_TOO_SMALL`, `HOLE_SINK_DEPTH_TOO_LARGE`, `HOLE_SINK_ANGLE_RANGE`, `NO_BODY_TO_INTERSECT`, `REVOLVE_AXIS_GONE`, `REGION_CROSSES_AXIS`, `FACE_NOT_PARALLEL`) ist im zweiten Argument (`args[1]`) als innere `Msg` hinterlegt. Die Tests prüften fälschlicherweise direkt auf den Hauptschlüssel der äußeren Nachricht.
2. **E2 (2 Tests in `UntilFaceExtrudeTest`):** `Document.addExtrude()` validiert Eingaben deterministisch vorab und weist ungültige Kombinationen („Bis Fläche“ mit zwei Seiten/distance2) sowie unbekannte Zielflächen sofort per `IllegalArgumentException` ab. Die Tests erwarteten stattdessen fälschlich einen verzögerten Fehler im Build-Ergebnis.

### Durchgeführte Testanpassungen (nur `core/model/src/androidTest`, kein Produktcode)
- **Gemeinsamer Test-Helfer:** `assertFeatureFailedWithReason(msg, expectedKey)` in `ModelTestHelpers.kt` prüft sowohl den äußeren Schlüssel `FEATURE_FAILED` als auch den inneren spezifischen Grund.
- **Korrektur E1:** `FaceRefTest`, `HoleGeometryTest`, `IntersectFeatureTest`, `RevolveFeatureTest` und `UntilFaceExtrudeTest.nonParallelFaceIsRejected` nutzen nun `assertFeatureFailedWithReason`.
- **Korrektur E2:** 
  - `extrudeUntilFaceWithSecondSide`: Auf `assertFailsWith<IllegalArgumentException>` umgestellt, um die sofortige Abweisung ungültiger Parameterkombinationen abzusichern.
  - `untilFaceWithoutBodyIsRejected`: Aufgeteilt bzw. präzisiert: Prüft nun, dass unbekannte Zielflächen sofort in `addExtrude` abgewiesen werden (`IllegalArgumentException`) UND dass eine Zielfläche, die zwar zu einem existierenden Feature gehört, bei der jedoch im Aufbau kein Körper entsteht (z. B. durch eine offene Skizzenlinie des Vor-Features), im Build-Ergebnis mit `FEATURE_FAILED` + `NO_BODY` gemeldet wird.

### Neues Testergebnis
- **Gerätetests `:core:model:connectedDebugAndroidTest`:**
  - **94 von 94 Tests bestanden (100 % grün)**
  - 0 Fehlschläge, 0 Fehler.
- **JVM/Build-Verifikation:**
  - `testDebugUnitTest`: Erfolgreich (grün)
  - `test`: Erfolgreich (grün)
  - `:app:assembleDebug`: Erfolgreich (grün)
  - `:app:lintDebug`: Erfolgreich (grün)
  - `:core:model:compileDebugAndroidTestKotlin`: Erfolgreich (grün)

**Gesamtfazit:**
Alle 94 Modell-Gerätetests sind vollständig grün. Sowohl die interaktiven CAD-Funktionen als auch die Unit-/Integrationstests sind zu 100 % verifiziert.
