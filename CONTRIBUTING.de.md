[English: CONTRIBUTING.md](CONTRIBUTING.md)

# Mitwirken an AxisWright

Wir freuen uns über Beiträge zu AxisWright! Als freies Open-Source-Projekt (GPL-3.0-or-later) lebt AxisWright von der Gemeinschaft.

### 1. Rechtliche Voraussetzung: CLA
Vor dem Zusammenführen von Beiträgen ist die Zustimmung zu unserer [Individual Contributor License Agreement (CLA.md)](CLA.md) erforderlich. In Pull-Request-Vorlagen gibt es dafür ein Bestätigungshäkchen. Beachte auch den [Verhaltenskodex (CODE_OF_CONDUCT.md)](CODE_OF_CONDUCT.md) und die [Markenrichtlinie (TRADEMARK.de.md)](TRADEMARK.de.md).

### 2. Entwicklungsumgebung & Bauen
- **Voraussetzungen:**
  - JDK 17 oder 21
  - Android SDK (compileSdk 36, minSdk 26)
  - Android NDK `28.2.13676358`
  - CMake `3.31.6`
  - PowerShell 7 (`pwsh`) zum Bauen von OpenCascade (auch unter Linux/macOS)
- **OpenCascade bauen:**
  Wird für `:core:brep` benötigt und einmalig je Zielarchitektur kompiliert:
  ```powershell
  git clone --depth 1 --branch V7_9_3 https://github.com/Open-Cascade-SAS/OCCT.git third_party/occt
  ./scripts/build-occt.ps1 -Abis x86_64,arm64-v8a
  ```
- **App bauen:**
  - FOSS-Variante: `./gradlew :app:assembleFossDebug`
  - Play-Variante: `./gradlew :app:assemblePlayDebug`

### 3. Tests ausführen
Vor jedem Commit oder Pull Request müssen die JVM- und Lint-Tests bestanden werden:
```bash
./gradlew testPlayDebugUnitTest testFossDebugUnitTest test
./gradlew :app:lintPlayDebug :app:lintFossDebug
```

### 4. Code-Stil & Richtlinien
- **Sprachen & Übersetzung:** Alle sichtbaren Texte müssen zweisprachig sein: Englisch als Standard in `app/src/main/res/values/strings*.xml`, Deutsch in `values-de/`. Fehlende Übersetzungen führen zu Lint-Fehlern (`MissingTranslation`).
- **Offline & Datenschutz:** AxisWright stellt keine Internetverbindungen her. Füge keine Bibliotheken ein, die Netzwerkzugriff erfordern oder die `INTERNET`-Berechtigung ins Manifest einbringen.
- **Komponenten:** Nutze vorhandene UI-Muster (Jetpack Compose, Material 3, schwebende Leisten mit Glas-Optik).

### 5. Commit-Regeln
- Commit-Nachrichten werden auf **Deutsch** verfasst.
- Klare, präzise Einzeiler im Imperativ (z. B. `Skizzen-Solver: Tangenten-Constraint für Ellipsen ergänzen`).
- Kleinteilige, in sich geschlossene Commits bevorzugen.

### 6. Ablauf für Pull Requests
1. Repository forken und eigenen Arbeitszweig erstellen (`feature/...` oder `fix/...`).
2. Änderungen implementieren und Tests lokal ausführen.
3. Pull Request gegen den Hauptzweig `main` öffnen.
4. CLA-Häkchen im Pull-Request-Formular setzen.
