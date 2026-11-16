# Lane QA2 – Emulator-Prüfung nach Stabilitätsrunde (Stand `main` `cc5daf1`)

- **Geprüfter Stand:** `cc5daf1` (*Zusammenführen: Formelfelder für Muster, Arbeitsebene und Bohrtiefe (Lane EX)*)
- **Zweig:** `lane-qa2`
- **Gerät / Emulator:** AVD `Pixel_10_Pro_XL` (`sdk_gphone16k_x86_64`, API 37 / Android 17, ABI `x86_64`)
- **Getestete App-Varianten:** `fossDebug` (`io.github.kevbo8327.axiswright`, `0.9.2-beta`) und `playDebug` (`io.github.kevbo8327.axiswright`, `0.9.2-beta`); Parallelinstallation der Alt-App `io.github.kevbo8327.paramworker` unverändert belassen.

---

## 1. Gerätetests

Ausgeführter Befehl:
```sh
./gradlew.bat :core:brep:connectedDebugAndroidTest :core:model:connectedDebugAndroidTest :core:geometry:connectedDebugAndroidTest -Pandroid.injected.build.abi=x86_64 --continue
```

| Modul | Gesamt | Bestanden | Fehlgeschlagen | Übersprungen | Hervorgehobene Suiten |
| --- | ---: | ---: | ---: | ---: | --- |
| `:core:brep` | 50 | 50 | 0 | 0 | `LifecycleTest` (3/3), `StepTest` (2/2), `FaceNamingTest` (18/18), `BrepTest` (16/16), `RevolveTest` (8/8), `ShellTest` (2/2), `MeasureTest` (1/1) |
| `:core:model` | 95 | 94 | 1 | 0 | `ShellFeatureTest` (4/4), `EdgeProjectionTest` (6/7) |
| `:core:geometry` | 17 | 17 | 0 | 0 | `KernelBoundsTest` (2/2), `KernelTest` (13/13), `ReferenceModelTest` (2/2) |
| **Summe** | **162** | **161** | **1** | **0** | |

### Fehlschlag-Analyse (`:core:model`)

- **Test:** `dev.androidcad.model.EdgeProjectionTest#aProjectedEdgeSurvivesWhenItsFaceIsMergedWithAnother` (`core/model/src/androidTest/kotlin/dev/androidcad/model/EdgeProjectionTest.kt:172`)
- **Assertion / Stacktrace-Kern:**
  ```text
  java.lang.AssertionError: Expected <2>, actual <1>.
      at kotlin.test.DefaultAsserter.fail(DefaultAsserter.kt:16)
      ...
      at dev.androidcad.model.EdgeProjectionTest.aProjectedEdgeSurvivesWhenItsFaceIsMergedWithAnother(EdgeProjectionTest.kt:172)
  ```
- **Einordnung:** **Falscher Test** (kein Produktfehler).
  **Begründung:** Im Testaufbau werden Block A `(0..40, 0..20, Höhe 10)` und Block B `(40..60, 0..20, Höhe 5)` vereinigt (`fuse`). Bereits im Ausgangszustand (`Höhe B = 5`) liegen die Vorderseiten (`y = 0`) von A und B koplanar und werden von OpenCascade (`UnifySameDomain` beim Booleschen Vereinigen) zu einer einzigen L-förmigen Vorderseite unter dem Hauptnamen von A verschmolzen (`B`s Vorderseite ist nur noch Zweitname). `EdgeProjection.edgesOf` (`Document.kt` → `r.projectable`) benennt Flächenumrisskanten über die Hauptnamen der angrenzenden Flächen (`body.edgeFaceTags()`). Deshalb existiert `frontEdge` mit `FaceRef(b.id, SIDE, rect.edges[0].value)` in `r.projectable` nicht unter B's Hauptnamen, und `wanted` enthält vor dem Erhöhen von B auf `10.0` nur `rightEdge` (`1` statt erwarteter `2`). Die eigentliche Zweitnamen-Auflösung für projizierte Kanten nach dem Verschmelzen (`EdgeProjection.find`) arbeitet korrekt.

---

## 2. Sichtprüfung (Emulator)

| Punkt | Ergebnis | Screenshot | Befund |
| --- | --- | --- | --- |
| 1. Name/Start | OK | [01_launcher_app_name.png](01_launcher_app_name.png), [01_splash_axiswright.png](01_splash_axiswright.png), [01_start_screen.png](01_start_screen.png), [01_welcome_dialog.png](01_welcome_dialog.png), [01_model_topbar.png](01_model_topbar.png), [01_model_3d_topbar.png](01_model_3d_topbar.png), [01_imprint.png](01_imprint.png), [01_licenses.png](01_licenses.png), [01_license_gpl3.png](01_license_gpl3.png), [01_license_source_link.png](01_license_source_link.png) | App-Name „AxisWright“ im Launcher (neben Alt-App „ParamWorker“); Splash zeigt „AXISWRIGHT“; Startseite und Kopfzeile zeigen AxisWright bzw. Modell-/Skizzennamen ohne „ParamWorker“ oder „(Pro)“; Impressum & Datenschutz vollständig (§ 5 DDG); Lizenzseite listet AxisWright („GNU GPL 3.0 or later (GPL-3.0-or-later)“) als ersten Eintrag samt Volltext-Dialog; „Quellcode“ öffnet Browser (Chrome) ohne Absturz. |
| 2. Varianten (`foss` / `play`) | OK | `foss`: [02_foss_menu_top.png](02_foss_menu_top.png), [02_foss_menu_bottom.png](02_foss_menu_bottom.png), [02_foss_support_dialog.png](02_foss_support_dialog.png), [02_foss_liberapay_browser.png](02_foss_liberapay_browser.png), [02_foss_github_browser.png](02_foss_github_browser.png), [02_foss_export_menu.png](02_foss_export_menu.png), [02_foss_overflow_menu.png](02_foss_overflow_menu.png)<br>`play`: [02_play_menu.png](02_play_menu.png), [02_play_export_menu.png](02_play_export_menu.png), [02_play_parameters.png](02_play_parameters.png) | **foss:** Projektmenü enthält „Unterstützen …“; Spenden-Dialog öffnet mit „Liberapay“ und „GitHub Sponsors“, beide öffnen den Browser (Platzhalter-URLs gemäß `Distribution.kt`) ohne Absturz. **play:** Kein Spenden-/Unterstützen-Eintrag im Projektmenü. **Beide:** Keine Pro-Kennzeichnung; STEP-Export, 3MF-Export und Parameter-Tabelle frei nutzbar. |
| 3. Schieberegler/Undo (T-08) | OK | [03_extrude_before_drag.png](03_extrude_before_drag.png), [03_slider_after_drag.png](03_slider_after_drag.png), [03_slider_after_one_undo.png](03_slider_after_one_undo.png) | Extrusionslänge per Schieberegler flüssig von `10 mm` auf `67 mm` gezogen; genau **ein** Tipp auf „Rückgängig“ setzt den Wert vollständig auf `10 mm` zurück. |
| 4. Speichern beim Verlassen (T-14) | OK | [04_reopen_extrude_42.png](04_reopen_extrude_42.png), [04_relaunch_after_kill.png](04_relaunch_after_kill.png), [04_reopen_after_kill.png](04_reopen_after_kill.png) | Änderung vorgenommen (Extrusion auf `40 mm`), Home-Taste gedrückt, Prozess per `adb shell am kill` beendet; nach Neustart ist das Modell unbeschädigt und der letzte Stand (`40 mm`) vollständig erhalten. |
| 5. Formelfelder (EX) | OK | [05_parameter_dialog_empty.png](05_parameter_dialog_empty.png), [05_parameters_set.png](05_parameters_set.png), [05_plane_formula_minus_s.png](05_plane_formula_minus_s.png), [05_hole_depth_formula.png](05_hole_depth_formula.png), [05_pattern_distance_s.png](05_pattern_distance_s.png), [05_circular_angle_w2.png](05_circular_angle_w2.png), [05_parameters_changed.png](05_parameters_changed.png), [05_follow_ebene.png](05_follow_ebene.png), [05_follow_bohrung.png](05_follow_bohrung.png), [05_follow_muster1.png](05_follow_muster1.png), [05_follow_muster2.png](05_follow_muster2.png) | Parameter `s=22,5`, `w=45`, `t=12,5` angelegt; Formeln `-s` (Arbeitsebene → `-22.5`), `t*0,8` (Bohrtiefe; Zwischenstand in `05_hole_depth_formula.png` vor Endwert von `t`, nach finalem Modellstand korrekt auf `8` bei `t=10`), `s` (Reihenmuster → `22.5`) und `w*2` (Kreismuster → `90`) eingetragen. Nach Änderung auf `s=15`, `t=10`, `w=30` („4 Werte folgen den Parametern“) wandern alle vier Werte korrekt mit (`-15`, `8`, `15`, `60`). |
| 6. Solver/Skizze (S4) | OK | [06_tangent_before.png](06_tangent_before.png), [06_tangent_after.png](06_tangent_after.png), [06_angle_near_180.png](06_angle_near_180.png), [06_thin_ring_extruded.png](06_thin_ring_extruded.png) | Tangentiale Linie über den Kreismittelpunkt auf die gegenüberliegende Seite gezogen – Tangente bleibt ohne Seitensprung stabil; Winkelmaß nahe `180°` (`175°`) stabil bearbeitbar; dünner Ring (zwei konzentrische Kreise mit `0,5 mm` Wandabstand) wird als eine Fläche mit Loch sauber extrudiert. |
| 7. Geometrie (S1/S2) | OK | [07_fillet_applied.png](07_fillet_applied.png), [07_step_export.png](07_step_export.png), [07_measure_length.png](07_measure_length.png), [07_measure_distance.png](07_measure_distance.png) | Quader → `Schale 1` → `Körperspiegelung 1` → `Verrundung 1` (`Radius 2`) auf Innenkante erfolgreich; STEP-Export zweimal schnell hintereinander ohne Absturz/Einfrieren; Messen liefert Kantenlänge (`66,00 mm`) sowie Abstand/Winkel paralleler Kanten (`4,00 mm`, `0,00°`). |
| 8. 3D-Ansicht (T-16) | OK | [08_3d_after_10_cycles.png](08_3d_after_10_cycles.png) | Mehrfacher Wechsel zwischen Skizze und 3D sowie 10× Hintergrund/Vordergrund-Wechsel ohne schwarze Textur, Hänger oder Absturz. |
| 9. Alte Dateien | OK | [09_paramworker_original.png](09_paramworker_original.png), [09_axiswright_imported.png](09_axiswright_imported.png), [09_axiswright_imported_info.png](09_axiswright_imported_info.png) | `.paramworker.json` (`Modell 4` aus `ParamWorker` mit `Bohrung 1–3` und `Verrundung 1`) in AxisWright importiert; öffnet als `Modell 4 (Import)` und baut geometrisch identisch (`3905,65 cm³ · 13 Flächen`). |

---

## 3. Fehlerliste

| Nr. | Bereich | Schweregrad | Beschreibung / Beleg |
| --- | --- | --- | --- |
| 1 | Gerätetest `:core:model` (`EdgeProjectionTest`) | **falsches Ergebnis** *(Testfehler, kein Produktfehler)* | `EdgeProjectionTest.aProjectedEdgeSurvivesWhenItsFaceIsMergedWithAnother` (`EdgeProjectionTest.kt:172`) schlägt mit `AssertionError: Expected <2>, actual <1>` fehl, weil der Test annimmt, `B`s Vorderseite behalte bei `Höhe B = 5` noch ihren eigenen Hauptnamen in `r.projectable`, obwohl `UnifySameDomain` sie bereits mit der koplanaren Vorderseite von `A` unter `A`s Hauptnamen verschmolzen hat. |

*(Hinweis ohne Fehlercharakter: Die Quellcode- und Spenden-URLs in `app/src/{foss,play}/kotlin/dev/androidcad/app/Distribution.kt` sind erwartungsgemäß noch als `PLACEHOLDER`-URLs hinterlegt, siehe dortigen `TODO(Nutzer)`-Kommentar; die App öffnet den Browser sauber ohne Absturz. In `adb logcat -d` traten während der Sichtprüfung keine `AndroidRuntime:E`-Abstürze auf.)*
