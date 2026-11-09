# Plan: fehlende Skizzenwerkzeuge (Stand 12.10.2026)

Grundlage ist der Funktionsabgleich mit Shapr3D, Onshape, Fusion und FreeCAD in [ROADMAP.md](ROADMAP.md) („Funktionsabgleich“).
Die Arbeit wird auf **Subagenten** verteilt. Jeder Subagent arbeitet in einem **eigenen Git-Worktree** (`isolation: "worktree"`) auf einem
eigenen Zweig; der Hauptagent (Koordinator) führt die Zweige nacheinander zusammen, prüft auf dem Emulator und committet.

## Regeln für alle Subagenten

- Kleinste vollständige Änderung (Ponytail): vorhandene Muster nutzen (`SketchOps` für Geometrie-Operationen, `Tool` + `modifyAt`
  bzw. `drawUp` im `SketchEditor`, `ToolGroup`/`tool(…)` und `hintFor` in `WorkspaceScreen.kt`, Symbole in `CadIcons.kt`).
- **Neue Logik in eigene Dateien** (z. B. `SketchOpsSplit.kt`), an den gemeinsamen Stellen nur die nötigen Zeilen: `Tool`-Eintrag,
  die zwei vollständigen `when`-Listen in `SketchEditor` (`Tool.SELECT, Tool.TRIM, … -> null/Unit`), ein Werkzeugknopf, ein Hinweistext.
  So bleiben Konflikte beim Zusammenführen klein.
- Je Funktion mindestens ein JVM-Test in `core/sketch` bzw. `core/sketchedit` (Muster: `DimensionToolTest`, `ModifyToolsTest`);
  Gerätetests in `core/model`/`core/brep`, wenn sich das Profil für OCCT ändert.
- Prüfen: `./gradlew.bat testDebugUnitTest test :app:assembleDebug -Pandroid.injected.build.abi=x86_64` muss grün sein.
- **Kein Emulator** (es gibt nur einen, und der Nutzer testet parallel darauf); die Sichtprüfung macht der Koordinator nach dem Zusammenführen.
- Kommentare und Texte auf Deutsch, Stil wie im umgebenden Code; im eigenen Worktree-Zweig committen, nicht pushen – das Ergebnis ist dieser Zweig
  mit einer kurzen Zusammenfassung (geänderte Dateien, Tests, bekannte Grenzen).

## Welle 1 – vor dem Beta-Start (20.10.2026), drei Subagenten parallel

| Agent | Aufgabe | Berührt | Hinweise |
|---|---|---|---|
| **A1 Punkt** | Werkzeug **Punkt**: einzelner Punkt durch Tippen (mit Fang); bemaßbar, als Bezug für Bohrungsmitten | `SketchEditor` (`drawUp`), `Sketch.removeOrphanPoints` | Einzelpunkte werden heute als Waisen entfernt (`Sketch.kt`, `removeOrphanPoints`) – gewollte Punkte müssen davon ausgenommen und im `SketchCodec` gespeichert werden |
| **A2 Fase** | **Fase an einer Skizzenecke** als Variante des Werkzeugs „Ecke“ (Runden / Fase, Abstand) | `SketchOps` (neben `fillet`), `SketchEditor`, Varianten-Chips | wie `SketchOps.fillet`: beide Linien kürzen, Verbindungslinie einfügen, Bedingungen sinnvoll übernehmen |
| **A3 Teilen** | Werkzeug **Teilen**: Linie, Bogen oder Kreis an der angetippten Stelle auftrennen | `SketchOps` (neue Datei), `SketchEditor.modifyAt` | Bedingungen und Maße am geteilten Element auf die Teile verteilen oder entfernen (wie beim Trimmen) |

## Welle 2 – bis zur Version 1.0 (ca. 5.–10.11.2026), zwei Subagenten parallel

| Agent | Aufgabe | Berührt | Hinweise |
|---|---|---|---|
| **B1 Transformieren** ✅ (08.11.2026) | Auswahl **verschieben, drehen, skalieren, kopieren** (Werte im Dialog, Bezugspunkt antippen) | `SketchOps` (neue Datei), `SketchEditor`, Kontextleiste der Auswahl | Bedingungen der Auswahl: beim Kopieren mitkopieren, bei Verschieben/Drehen Festlegungen (Fixieren, Maße zum Ursprung) prüfen |
| **B2 Text** ✅ (08.11.2026) | **Text** als Skizzenelement (Text, Höhe, Lage, Drehung), Umrisse als geschlossene Konturen für Extrudieren/Gravieren | neues Element in `Sketch`/`SketchCodec`/`Regions`, App-Seite für Umrisse | Kern bleibt JVM-rein: Umrisse über eine Schnittstelle, die die App mit `Paint.getTextPath` + `Path.approximate` (ab API 26) erfüllt; Tests mit Ersatz-Umrissen. Schriftart mit freier Lizenz (z. B. aus dem System) und Lizenzseite prüfen |

## Welle 3 – nach 1.0, drei Subagenten (C1 nach Welle 2, C2/C3 jederzeit)

| Agent | Aufgabe | Berührt | Hinweise |
|---|---|---|---|
| **C1 Ellipse + Spline** | **Ellipse**/elliptischer Bogen, danach **Spline** durch Punkte | `Sketch`, `Compile`/Löser, `Regions`, `SketchCodec`, `Profile` + `cadbrep.cpp` (`gp_Elips`, `GeomAPI_Interpolate`) | ein Agent nacheinander, weil beide dieselben Kernstellen ändern; Format der Modelldatei erweitert → Formatversion beachten |
| **C2 DXF/SVG-Import** | Skizze aus **DXF** (LINE, ARC, CIRCLE, LWPOLYLINE) bzw. **SVG** (Pfade, nur gerade/Bögen) | neuer Leser im Kern (eigene Datei), Projektmenü/Skizze „Importieren …“ | Eingaben sind Fremddaten: Größen und Anzahl begrenzen, Fehler verständlich melden; Einheiten (mm/inch) abfragen |
| **C3 Kanten übernehmen** | Kanten anderer Körperflächen/Skizzen in die Skizze **projizieren**, auch **Schnittkanten** mit der Skizzenebene | `core/model` (wie die vorhandenen projizierten Flächenumrisse), `core/brep` | bleibt mit der Quelle verknüpft (Neuaufbau aktualisiert), Namen über die vorhandene Flächenbenennung |

## Ablauf für den Koordinator

1. Welle starten: je Aufgabe ein Subagent mit `isolation: "worktree"`, Auftrag = Zeile aus der Tabelle + „Regeln für alle Subagenten“.
2. Ergebnisse nacheinander zusammenführen (kleinste Änderung zuerst), Konflikte an den gemeinsamen Stellen (Tool-Liste, Werkzeugleiste) auflösen,
   alle Tests laufen lassen, auf dem Emulator in einem eigenen Testmodell prüfen, dann committen und pushen (nach Freigabe durch den Nutzer).
3. Roadmap abhaken.
