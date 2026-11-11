# Mitwirken an ParamWorker / Contributing to ParamWorker

[Deutsch](#deutsch) | [English](#english)

---

<a name="deutsch"></a>
## Deutsch

Wir freuen uns über Beiträge zu ParamWorker! Als freies Open-Source-Projekt (GPL-3.0-or-later) lebt ParamWorker von der Gemeinschaft.

### 1. Rechtliche Voraussetzung: CLA
Vor dem Zusammenführen von Beiträgen ist die Zustimmung zu unserer [Individual Contributor License Agreement (CLA.md)](CLA.md) erforderlich. In Pull-Request-Vorlagen gibt es dafür ein Bestätigungshäkchen. Beachte auch den [Verhaltenskodex (CODE_OF_CONDUCT.md)](CODE_OF_CONDUCT.md) und die [Markenrichtlinie (TRADEMARK.md)](TRADEMARK.md).

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
- **Offline & Datenschutz:** ParamWorker stellt keine Internetverbindungen her. Füge keine Bibliotheken ein, die Netzwerkzugriff erfordern oder die `INTERNET`-Berechtigung ins Manifest einbringen.
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

---

<a name="english"></a>
## English

We welcome contributions to ParamWorker! As a free and open-source project (GPL-3.0-or-later), ParamWorker thrives on community involvement.

### 1. Legal Prerequisite: CLA
Before any contribution can be merged, agreement to our [Individual Contributor License Agreement (CLA.md)](CLA.md) is required. The pull request template includes a checkbox for this purpose. Please also review our [Code of Conduct (CODE_OF_CONDUCT.md)](CODE_OF_CONDUCT.md) and [Trademark Policy (TRADEMARK.md)](TRADEMARK.md).

### 2. Development Setup & Building
- **Requirements:**
  - JDK 17 or 21
  - Android SDK (compileSdk 36, minSdk 26)
  - Android NDK `28.2.13676358`
  - CMake `3.31.6`
  - PowerShell 7 (`pwsh`) for building OpenCascade (works on Linux/macOS as well)
- **Building OpenCascade:**
  Required for `:core:brep` and compiled once per ABI:
  ```powershell
  git clone --depth 1 --branch V7_9_3 https://github.com/Open-Cascade-SAS/OCCT.git third_party/occt
  ./scripts/build-occt.ps1 -Abis x86_64,arm64-v8a
  ```
- **Building the App:**
  - FOSS variant: `./gradlew :app:assembleFossDebug`
  - Play variant: `./gradlew :app:assemblePlayDebug`

### 3. Running Tests
All unit and lint tests must pass before submitting contributions:
```bash
./gradlew testPlayDebugUnitTest testFossDebugUnitTest test
./gradlew :app:lintPlayDebug :app:lintFossDebug
```

### 4. Code Style & Architecture
- **Localization:** All user-facing strings must be localized: English default in `app/src/main/res/values/strings*.xml` and German in `values-de/`. Missing translations will trigger lint errors (`MissingTranslation`).
- **Offline & Privacy:** ParamWorker does not make any internet connections. Do not include libraries or dependencies that require network access or pull in the `INTERNET` permission.
- **UI Architecture:** Use modern Jetpack Compose, Material 3, and established project patterns.

### 5. Commit Guidelines
- Commit messages should be in **German**, matching existing repository history.
- Use clear, descriptive imperative messages.
- Keep commits cohesive and minimal.

### 6. Pull Request Workflow
1. Fork the repository and create your topic branch (`feature/...` or `fix/...`).
2. Make your changes and verify that all tests pass.
3. Open a Pull Request against the `main` branch.
4. Check the CLA acceptance box in the Pull Request template.
