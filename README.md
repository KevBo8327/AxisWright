# ParamWorker

[Deutsch](#deutsch) | [English](#english)

---

<a name="deutsch"></a>
# ParamWorker (Deutsch)

ParamWorker ist ein freies, quelloffenes (GPL-3.0-or-later) parametrisches 3D-CAD-Programm für Android-Tablets, Smartphones und Chromebooks. Es kombiniert exakte B-Rep-Geometrie auf Basis von Open CASCADE Technology mit einer modernen Touch- und Stiftbedienung sowie einem optionalen OpenSCAD-kompatiblen Skript-Editor mit Manifold-Geometriekern.

Die App arbeitet vollständig **offline**: ohne Benutzerkonto, ohne Cloud-Zwang, ohne Werbung, ohne Tracking und ohne Internetberechtigung.

## Screenshots

| Startseite | 2D-Skizze mit Bedingungen | 3D-Modell mit Verrundung |
|---|---|---|
| ![Startseite](docs/store/screenshots/phone-1-start.png) | ![Skizze](docs/store/screenshots/phone-2-skizze.png) | ![Modell](docs/store/screenshots/phone-3-modell.png) |

| Parameter-Tabelle | Export-Menü | Tablet-Ansicht |
|---|---|---|
| ![Parameter](docs/store/screenshots/phone-4-parameter.png) | ![Export](docs/store/screenshots/phone-5-export.png) | ![Tablet](docs/store/screenshots/tablet10-3-modell.png) |

## Funktionen

- **Skizzieren (2D):**
  - Grundformen: Linie (auch tangential), Rechteck (Ecke, Mitte, 3 Punkte), Kreis (Mitte, 2/3 Punkte, tangential), Bogen, Langloch, regelmäßiges Vieleck, Text als Skizzenelement (für Gravur oder erhabene Beschriftung).
  - 2D-Werkzeuge: Trimmen, Verlängern, Versatz, Ecken runden, Spiegeln, Muster (Reihe und Kreis), Transformieren (Verschieben, Drehen, Skalieren und Kopieren der Auswahl).
  - Geometrische Bedingungen: 16 Constraints (horizontal, vertikal, parallel, senkrecht, tangential, konzentrisch, symmetrisch u. v. m.).
  - Bemaßung: 8 Maßarten mit Maßlinie, Pfeilen und Maßhilfslinien; Freiheitsgrad-Analyse (vollständig bestimmt).
- **Körper (3D):**
  - Features: Extrudieren (Hinzufügen, Abtragen, Schnittmenge, durch alles, zwei Seiten mit verschiedenen Längen, bis Fläche), Drehen (Revolve um Skizzen- oder Hilfsachse), Bohrungen (einfach, mit Senkung/Kegelsenkung und Normgrößen M3–M10), Verrunden, Anfasen, Schale (Aushöhlen).
  - Muster & Symmetrie: Reihen-, Raster- und Kreismuster, Spiegeln von Features oder des gesamten Körpers.
  - Bezugselemente: Skizzen auf ebenen Körperflächen oder versetzten/gedrehten Arbeitsebenen; Körperkanten in Skizzen projizieren.
  - Ansichten: Schnittansicht mit dynamischer Schnittebene zur Kontrolle von Innenkonturen und Hohlräumen.
  - Parametrischer Modellaufbau: Chronologische Zeitleiste; jeder Schritt bleibt nachträglich editierbar.
- **Parameter & Messen:**
  - Parameter-Tabelle für Variablen und mathematische Ausdrücke (z. B. `wand * 2`).
  - 3D-Messwerkzeug für Längen, Radien, Abstände, Winkel und Flächeninhalt.
- **Austausch & Export:**
  - Export als STEP (exakte B-Rep-Geometrie für andere CAD-Programme).
  - Export als STL und 3MF für den 3D-Druck.
  - Projektdateien (`.paramworker.json`) exportieren und importieren.
  - Integrierter Skript-Editor für OpenSCAD-Dateien mit Customizer-Schiebereglern.

## Lizenz

ParamWorker ist freie Software unter der **GNU General Public License v3.0 oder höher (GPL-3.0-or-later)**.
Copyright © 2026 Kevin Bodenstein.

Vollständiger Lizenztext: siehe [LICENSE](LICENSE).

Eingebundene Fremdbibliotheken:
- **Open CASCADE Technology (OCCT)** 7.9.3: LGPL 2.1 mit Open-CASCADE-Ausnahme. Da der Quelltext und die Build-Skripte von ParamWorker öffentlich sind, ist das Neu-Linken und Austauschen der Bibliotheken nach LGPL 2.1 § 6 uneingeschränkt möglich.
- **Manifold** (Apache 2.0) und **Clipper2** (Boost Software License 1.0).
- **Haze**, **AndroidX**, **Kotlin** (Apache 2.0).

## Aus dem Quelltext bauen

### Voraussetzungen
- Android SDK (compileSdk 36, minSdk 26)
- JDK 17 oder 21
- Android NDK `28.2.13676358`
- CMake `3.31.6` im Android SDK (`$ANDROID_HOME/cmake/3.31.6`)
- PowerShell 7 (`pwsh` unter Linux/macOS bzw. `powershell.exe` unter Windows)

### 1. OpenCascade bauen
OpenCascade wird als native B-Rep-Engine für `:core:brep` benötigt und einmalig für die Ziel-ABIs kompiliert:
```powershell
git clone --depth 1 --branch V7_9_3 https://github.com/Open-Cascade-SAS/OCCT.git third_party/occt
./scripts/build-occt.ps1 -Abis x86_64,arm64-v8a
```
Unter Linux/macOS kann das Skript direkt mit PowerShell 7 (`pwsh ./scripts/build-occt.ps1 -Abis x86_64,arm64-v8a`) ausgeführt werden.

### 2. Build-Varianten: FOSS und Play
Das Projekt bietet zwei Varianten:
- **`foss`**: Für F-Droid und manuelle APK-Downloads. Enthält im Projektmenü den Eintrag „Unterstützen …“ mit Spendenlinks.
- **`play`**: Für Google Play. Enthält keinerlei In-App-Spendenhinweise (gemäß Google-Play-Richtlinien).

> **Hinweis zu F-Droid:** F-Droid baut ausschließlich die `foss`-Variante (`assembleFossRelease`).

### 3. Kompilieren und Testen
```bash
# Unit-Tests beider Varianten ausführen
./gradlew testPlayDebugUnitTest testFossDebugUnitTest test

# Debug-APKs bauen
./gradlew :app:assembleFossDebug
./gradlew :app:assemblePlayDebug

# Release für Google Play bauen (AAB)
./gradlew :app:bundlePlayRelease -PallowDebugSignedRelease=true
```

## Mitwirken

Beiträge zur Weiterentwicklung sind herzlich willkommen!
- Bitte lies unsere [Beitragsrichtlinien (CONTRIBUTING.md)](CONTRIBUTING.md) vor der Einreichung von Pull Requests.
- Vor dem Zusammenführen von Beiträgen ist die Zustimmung zu unserer [Individual Contributor License Agreement (CLA.md)](CLA.md) erforderlich.
- Wir pflegen einen respektvollen Umgang gemäß unserem [Verhaltenskodex (CODE_OF_CONDUCT.md)](CODE_OF_CONDUCT.md).

## Spenden

ParamWorker ist komplett kostenlos, werbefrei und ohne Abos. Wenn dir das Projekt gefällt und du die Weiterentwicklung unterstützen möchtest, freuen wir uns über eine freiwillige Spende:
- **Liberapay:** [https://liberapay.com/PLACEHOLDER](https://liberapay.com/PLACEHOLDER)
- **GitHub Sponsors:** [https://github.com/sponsors/PLACEHOLDER](https://github.com/sponsors/PLACEHOLDER)

## Marke

Name und Logo ParamWorker sind Kennzeichen von Kevin Bodenstein (nicht eingetragen) und nicht Teil der GPL-Lizenz. Details und Richtlinien für Forks findest du in [TRADEMARK.md](TRADEMARK.md).

---

<a name="english"></a>
# ParamWorker (English)

ParamWorker is a free and open-source (GPL-3.0-or-later) parametric 3D CAD application designed for Android tablets, smartphones, and Chromebooks. It provides exact B-rep geometry powered by Open CASCADE Technology, tailored for touch, pen/stylus, and mouse interactions, alongside an optional OpenSCAD-compatible script editor powered by Manifold.

The app works entirely **offline**: no user accounts, no cloud sync required, no ads, no telemetry/tracking, and no internet permission.

## Screenshots

| Start Page | 2D Sketch with Constraints | 3D Model with Fillets |
|---|---|---|
| ![Start Page](docs/store/screenshots/phone-1-start.png) | ![Sketch](docs/store/screenshots/phone-2-skizze.png) | ![Model](docs/store/screenshots/phone-3-modell.png) |

| Parameter Table | Export Menu | Tablet Layout |
|---|---|---|
| ![Parameters](docs/store/screenshots/phone-4-parameter.png) | ![Export](docs/store/screenshots/phone-5-export.png) | ![Tablet](docs/store/screenshots/tablet10-3-modell.png) |

## Features

- **2D Sketching:**
  - Shapes: Lines (including tangent), Rectangles (corner, center, 3-point), Circles (center, 2/3 points, tangent), Arcs, Slots, Regular Polygons, Text as sketch element (for engraving or embossed text).
  - 2D Operations: Trim, Extend, Offset, Fillet corners, Mirror, Linear and Circular Patterns, Transform (Move, Rotate, Scale, and Copy selection).
  - Constraints: 16 geometric constraints (horizontal, vertical, parallel, perpendicular, tangent, concentric, symmetric, etc.).
  - Dimensions: 8 dimension types with dimension lines, arrows, and extension lines; degree-of-freedom solver analysis.
- **3D Modeling:**
  - Operations: Extrude (Add, Cut, Intersect, Through-All, Two Sides with different lengths, Up to Face), Revolve, Holes (simple, counterbore, countersink, standard metric sizes M3–M10), Fillets, Chamfers, Shell (Hollow out).
  - Patterns & Symmetry: Linear, Grid, and Circular patterns; Body and Feature Mirroring.
  - References: Sketches on planar faces or offset/rotated work planes; edge projection into sketches.
  - Views: Section view with dynamic cutting plane to inspect internal cavities and features.
  - History Tree: Parametric chronological feature timeline; every step remains editable.
- **Parameters & Measurement:**
  - Parameter table supporting named variables and math formulas (e.g., `wall * 2`).
  - 3D measurement tool for distances, radii, angles, and surface areas.
- **Exchange & Export:**
  - Export STEP files (exact B-rep geometry for standard CAD applications).
  - Export STL and 3MF files for 3D printing.
  - Export and import project files (`.paramworker.json`).
  - Integrated script editor for OpenSCAD files with Customizer parameter sliders.

## License

ParamWorker is free software licensed under the **GNU General Public License v3.0 or later (GPL-3.0-or-later)**.
Copyright © 2026 Kevin Bodenstein.

See the full license text in [LICENSE](LICENSE).

Third-party libraries:
- **Open CASCADE Technology (OCCT)** 7.9.3: LGPL 2.1 with Open CASCADE exception. Since ParamWorker's source code and build scripts are publicly available, relinking and replacing the libraries under LGPL 2.1 § 6 is fully supported.
- **Manifold** (Apache 2.0) and **Clipper2** (Boost Software License 1.0).
- **Haze**, **AndroidX**, **Kotlin** (Apache 2.0).

## Building from Source

### Prerequisites
- Android SDK (compileSdk 36, minSdk 26)
- JDK 17 or 21
- Android NDK `28.2.13676358`
- CMake `3.31.6` in the Android SDK (`$ANDROID_HOME/cmake/3.31.6`)
- PowerShell 7 (`pwsh` on Linux/macOS or `powershell.exe` on Windows)

### 1. Build OpenCascade
OpenCascade is required for `:core:brep` and is compiled once per target ABI:
```powershell
git clone --depth 1 --branch V7_9_3 https://github.com/Open-Cascade-SAS/OCCT.git third_party/occt
./scripts/build-occt.ps1 -Abis x86_64,arm64-v8a
```
On Linux/macOS, run with PowerShell 7 (`pwsh ./scripts/build-occt.ps1 -Abis x86_64,arm64-v8a`).

### 2. Build Variants: FOSS and Play
The application defines two flavors:
- **`foss`**: Targeted for F-Droid and independent APK distribution. Includes a "Support development…" menu entry with donation links.
- **`play`**: Targeted for Google Play. Does not contain any donation links or UI (complying with Google Play policies).

> **Note on F-Droid:** F-Droid builds the `foss` flavor (`assembleFossRelease`).

### 3. Build & Test Commands
```bash
# Run unit tests across all variants
./gradlew testPlayDebugUnitTest testFossDebugUnitTest test

# Assemble debug APKs
./gradlew :app:assembleFossDebug
./gradlew :app:assemblePlayDebug

# Build release AAB for Google Play
./gradlew :app:bundlePlayRelease -PallowDebugSignedRelease=true
```

## Contributing

Contributions are warmly welcome!
- Please read our [Contribution Guidelines (CONTRIBUTING.md)](CONTRIBUTING.md) before submitting pull requests.
- Submitting contributions requires agreeing to our [Individual Contributor License Agreement (CLA.md)](CLA.md).
- We maintain a welcoming and inclusive environment in accordance with our [Code of Conduct (CODE_OF_CONDUCT.md)](CODE_OF_CONDUCT.md).

## Donations

ParamWorker is completely free, ad-free, and has no paywalls or subscriptions. If you enjoy the app and want to support ongoing development, consider a voluntary donation:
- **Liberapay:** [https://liberapay.com/PLACEHOLDER](https://liberapay.com/PLACEHOLDER)
- **GitHub Sponsors:** [https://github.com/sponsors/PLACEHOLDER](https://github.com/sponsors/PLACEHOLDER)

## Trademark

The name and logo ParamWorker are unregistered trademarks of Kevin Bodenstein and are not covered by the GPL-3.0 license. For guidance on naming forks, see [TRADEMARK.md](TRADEMARK.md).
