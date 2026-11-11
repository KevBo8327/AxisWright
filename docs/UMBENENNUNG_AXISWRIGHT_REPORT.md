# Abschlussbericht: Umbenennung ParamWorker → AxisWright & GitHub-Texte auf Englisch

**Datum:** 11.11.2026  
**Zweig:** `lane-rn`  
**Commits:**
- `0acda30` Umbenennung: ParamWorker zu AxisWright (App-Kennung, Grafiken, Formate und Texte)
- `7d5b24b` Doku: GitHub- und Projekttexte auf Englisch umgestellt sowie deutsche Fassungen ergänzt

---

## 1. Durchgeführte Arbeiten

### Hauptauftrag: Umbenennung ParamWorker → AxisWright
1. **Sichtbarer Name & UI:**
   - `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`: `app_name` auf `AxisWright`.
   - `strings_dialogs.xml` (EN & DE): `help_version` und `help_title_welcome` auf `AxisWright`.
   - `strings_legal.xml` (EN & DE): `legal_liability_text`, `legal_privacy_text`, `legal_app_name` und `legal_lib_occt_note` auf `AxisWright`.
   - `app/src/foss/res/values/strings.xml` & `values-de/strings.xml`: `support_text` im Spendendialog auf `AxisWright`.
   - `Theme.kt`, `MainActivity.kt`: `Theme.ParamWorker` / `ParamWorkerTheme` umbenannt in `Theme.AxisWright` / `AxisWrightTheme`.
   - `app/src/main/res/values/themes.xml` & `values-v31/themes.xml`, `AndroidManifest.xml`: Auf `Theme.AxisWright` aktualisiert.
   - `locales_config.xml`: Kommentar auf `AxisWright` angepasst.
   - `ProjectMenu.kt`: Feedback-Betreff und System-Body auf `AxisWright $version – Feedback` gesetzt.
   - `ImprintDialog.kt`: `PRIVACY_URL` auf `https://sites.google.com/view/axiswright/datenschutz` gesetzt.
   - `samples/streaming_halterung.scad`, `app/src/main/assets/examples/Streaminghalterung.scad`: Copyright-/Beispiel-Kommentar auf `AxisWright` angepasst.
2. **App-Kennung & Manifest:**
   - `app/build.gradle.kts`: `applicationId = "io.github.kevbo8327.axiswright"`.
   - Merged Manifests (`fossDebug` & `playDebug`): `package="io.github.kevbo8327.axiswright"`, Label `@string/app_name` (löst zu `AxisWright` auf).
3. **Dateiformat & Abwärtskompatibilität:**
   - `core/model/.../DocumentCodec.kt`: `FORMAT = "axiswright-model"`, `LEGACY_FORMATS` enthält `"paramworker-model"`, `"partwright-model"`, `"androidcad-model"`.
   - `ModelScreen.kt`: Export-Endung `.axiswright.json`.
   - `ProjectMenu.kt`: Import erkennt und bereinigt `.axiswright`, `.paramworker` und `.partwright`.
   - Tests in `DocumentCodecTest.kt` und `SketchTextTest.kt`: Alte Formate werden gelesen und beim Speichern als `axiswright-model` geschrieben.
4. **Grafiken & make-icon.py:**
   - `scripts/make-icon.py`: Linienschrift um `X` (Diagonalkreuz) und `S` (C1-stetig verbundene, 180° rotationssymmetrische Kurven) erweitert.
   - Kerning für Buchstabenpaare in `AXISWRIGHT` optimiert.
   - Neu generiert:
     - `app/src/main/res/drawable/splash_branding.xml` (Vektor-Schriftzug AXISWRIGHT, 200 × 80 dp)
     - `docs/store/feature-graphic.png` (1024 × 500)
     - `docs/store/splash-preview.png` (1080 × 2400)
5. **Dokumente & Richtlinien:**
   - `docs/privacy/index.html`: Titel und Überschriften auf AxisWright.
   - `docs/store/listing.md`: Titel und Beschreibungen (DE & EN) auf AxisWright.
   - `fastlane/metadata/android/`: `title.txt` und `full_description.txt` (de-DE und en-US) auf AxisWright.
   - `docs/PRUEFUNG_LIZENZEN_PLAYSTORE.md`: Name und Paketkennung aktualisiert.
   - `docs/ROADMAP.md`: Phase 0 um Stand 11.11.2026 (AxisWright, Kennung, Format) ergänzt.
   - `scripts/play-check.py`: `PACKAGE = "io.github.kevbo8327.axiswright"`, Prüfung alter Namen ("Partwright" & "ParamWorker"), Lizenzhinweis AxisWright.
   - `.github/workflows/ci.yml`: Artifact-Name auf `axiswright-debug-apks`.

### Zusatzauftrag: GitHub-Texte auf Englisch
1. `README.md`: Vollständig auf Englisch, aktuell mit allen Features, Screenshots, Build-Anleitung, FOSS/Play-Varianten und Link `[Deutsch: README.de.md](README.de.md)` an oberster Stelle.
2. `README.de.md`: Vollständige deutsche Fassung mit Link `[English: README.md](README.md)`.
3. `CONTRIBUTING.md`: Englisch als Hauptdatei; `CONTRIBUTING.de.md` als deutsche Fassung.
4. `TRADEMARK.md`: Englisch als Hauptdatei; `TRADEMARK.de.md` als deutsche Fassung.
5. `CODE_OF_CONDUCT.md`: Englisch.
6. `CLA.md`: Englisch mit Warnhinweis oben.
7. `.github/PULL_REQUEST_TEMPLATE.md`: Englisch mit funktionierendem Link auf `../CLA.md`.
8. `.github/ISSUE_TEMPLATE/bug_report.md` & `feature_request.md`: Neu angelegt (Englisch, kurz).
9. `docs/github-about.md`: Neu angelegt mit Repo-Beschreibung (288 / max 350 Zeichen) und 12 Topics.

---

## 2. grep-Fundstellen für `paramworker` (Punkt 6)

Nach Abschluss verbleiben ausschließlich bewusste Stellen für Abwärtskompatibilität und historische Dokumente:
- `app/src/main/kotlin/dev/androidcad/app/ProjectMenu.kt:272`: Import alter `.paramworker.json`-Dateien (removeSuffix).
- `core/model/src/main/kotlin/dev/androidcad/model/DocumentCodec.kt:28-29`: `LEGACY_FORMATS` enthält `"paramworker-model"`.
- `core/model/src/test/kotlin/dev/androidcad/model/DocumentCodecTest.kt:99`: Testet das Lesen alter `"paramworker-model"`-Dateien.
- `docs/OPEN_SOURCE_MIGRATION_REPORT.md`: Historischer Bericht (darf nicht verändert werden).
- `docs/PROJEKT_UND_LIZENZANALYSE.md`: Historischer Bericht (Stand 19.10.2026).
- `docs/ROADMAP.md:38-40, 65`: Erwähnung der vorherigen Namensstufen im Phase-0-Verlauf und historischer Phase-1-Eintrag.
- `scripts/play-check.py:156`: Prüfung auf Alt-Namen („Partwright“ / „ParamWorker“) in Store-Texten.

---

## 3. Nachweise und Build-Ergebnisse

1. **Gradle Verification Suite:**
   ```powershell
   ./gradlew.bat testPlayDebugUnitTest testFossDebugUnitTest test :app:assemblePlayDebug :app:assembleFossDebug :app:lintPlayDebug :app:lintFossDebug :core:model:compileDebugAndroidTestKotlin :core:brep:compileDebugAndroidTestKotlin "-Pandroid.injected.build.abi=x86_64"
   ```
   **Ergebnis:** `BUILD SUCCESSFUL in 16s` (270 actionable tasks: 6 executed, 264 up-to-date; vorheriger vollständiger Lauf: `BUILD SUCCESSFUL in 1m 55s`, 270 tasks).
2. **Merged Manifests:**
   - `app/build/intermediates/merged_manifests/fossDebug/processFossDebugManifest/AndroidManifest.xml`:
     `package="io.github.kevbo8327.axiswright"`, `android:label="@string/app_name"` (AxisWright), `android:theme="@style/Theme.AxisWright.Starting"`.
   - `app/build/intermediates/merged_manifests/playDebug/processPlayDebugManifest/AndroidManifest.xml`:
     `package="io.github.kevbo8327.axiswright"`, `android:label="@string/app_name"` (AxisWright), `android:theme="@style/Theme.AxisWright.Starting"`.
3. **Store & Lizenz-Prüfung (`scripts/play-check.py`):**
   Alle 24 Text-, Symbol-, Screenshot- und Lizenz-Prüfungen grün (Titel 28/30, Kurzbeschreibung 75 bzw. 62/80, Langbeschreibung 2692 bzw. 2437/4000).
4. **Link-Prüfung:**
   Alle relativen Links zwischen README, CONTRIBUTING, TRADEMARK, CLA, PR-/Issue-Templates und Docs geprüft und intakt.

---

## 4. Hinweise für die Sichtprüfung

1. **Startbildschirm (Splash Screen):**
   - Auf Android 12+ erscheint unten das Branding `splash_branding.xml` mit dem Schriftzug **AXISWRIGHT** in geometrischer Linienschrift.
2. **App-Icon:**
   - Das Blueprint-Motiv (türkise Fläche, blauer Körper, oranger Pfeil) bleibt erhalten, das Store-Symbol und Feature-Graphic enthalten das aktuelle Design bzw. den Namen AxisWright.
3. **Dateiformat & Export/Import:**
   - Ein Export aus dem 3D-Modellmenü erzeugt standardmäßig eine `.axiswright.json`-Datei mit `"format": "axiswright-model"`.
   - Beim Importieren über das Projektmenü können weiterhin alte `.paramworker.json`-, `.partwright.json`- sowie `.scad`-Dateien ausgewählt werden; die Dateiendung wird beim Namen sauber abgeschnitten und beim Speichern ins neue Format migriert.
