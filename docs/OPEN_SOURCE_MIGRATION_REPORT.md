# Bericht zur Open-Source-Umstellung (GPL-3.0-or-later) – ParamWorker

**Datum:** 11. November 2026  
**Bearbeiter:** Orca Dispatched Worker (`term_e8dccea1-5194-418a-bd0c-f81089526617`)  
**Commit-Hash:** `935866840e16bd3e2f90a0533ed8dd7d8a9508c7`  
**Zweig:** `lane-os`

---

## 1. Übersicht der durchgeführten Maßnahmen

1. **Vollständige Entfernung von Pro & Google Play Billing:**
   - Gelöscht: `Pro.kt`, `ProTest.kt`, `google_play_notices.txt`.
   - Abhängigkeit `com.android.billingclient:billing-ktx` aus `gradle/libs.versions.toml` und `app/build.gradle.kts` entfernt.
   - Alle Funktionssperren (`Pro.allows()`, `ProMark()`, `ProDialog`, Wiederherstellungs- und Kauf-Menüpunkte) aus `AppRoot.kt`, `CadScreen.kt`, `ModelScreen.kt`, `WorkspaceScreen.kt` und `MainActivity.kt` restlos entfernt.
   - STEP-Export, 3MF-Export und Parameter-Tabelle sind uneingeschränkt für alle Nutzer frei nutzbar.
   - Alle zugehörigen Strings in EN (`values`) und DE (`values-de`) aus `strings_dialogs.xml`, `strings_start.xml` und `strings_legal.xml` bereinigt.

2. **Entfernung der INTERNET-Berechtigung:**
   - Die App fordert keine `INTERNET`- oder `BILLING`-Berechtigung an.
   - Merged-Manifeste beider Varianten (`play` und `foss`) enthalten weder `android.permission.INTERNET` noch `com.android.vending.BILLING`.
   - Die App arbeitet zu 100 % offline. Externe Links (Spenden, Quellcode, Feedback) nutzen System-Intents (`ACTION_VIEW`, `ACTION_SENDTO`).

3. **Anpassung von Datenschutz, Impressum und Store-Listing:**
   - `docs/privacy/index.html`: Kauf- und Billing-Abschnitte entfernt; klarer Hinweis, dass keine Internetverbindung hergestellt wird.
   - `strings_legal.xml` (DE & EN): Datenschutzhinweis auf Offline-Betrieb umgestellt, Google Play Billing entfernt, vereinfachter OpenCascade-LGPL-Hinweis eingepflegt.
   - `docs/store/listing.md`: Alle Pro-Verweise entfernt, Hinweis „Open Source (GPL-3.0)“ ergänzt.

4. **Build-Varianten (`play` und `foss`):**
   - Geschmacksdimension `distribution` in `app/build.gradle.kts` mit `play` und `foss` eingerichtet.
   - `foss`: Eigener Menüeintrag im Projektmenü „Unterstützen …“ (EN: „Support development …“) mit Dialog und Knöpfen für **Liberapay** und **GitHub Sponsors** (`ACTION_VIEW`).
   - `play`: Strikt ohne jeden Spendenverweis in Code und Ressourcen (Google Play Policy Konformität).
   - Beide: Link „Quellcode“ im Lizenzdialog.
   - URLs als Konstanten in `Distribution.kt` an zentraler Stelle mit klar sichtbaren Platzhaltern und `// TODO(Nutzer):`-Kommentaren.
   - CI-Workflow (`.github/workflows/ci.yml`), `scripts/play-check.py` und README-Befehle auf `bundlePlayRelease` und die neuen Tasks angepasst.

5. **Lizenzierung:**
   - `LICENSE` mit dem offiziellen, vollständigen GPL-3.0-Text angelegt.
   - Eigener Code unter **GPL-3.0-or-later**, Copyright „© 2026 Kevin Bodenstein“.
   - Lizenzdialog (`LicensesDialog.kt`): Eigener Code an erster Position; OpenCascade-LGPL-2.1-Hinweis vereinfacht; L-03 in `docs/PROJEKT_UND_LIZENZANALYSE.md` als erledigt markiert.

6. **Beitragsregeln & Community-Dokumente:**
   - `CONTRIBUTING.md` (DE & EN): Build, Tests, Code-Stil, Commit-Regeln, Pull-Request-Ablauf.
   - `CLA.md`: Individual Contributor License Agreement basierend auf Apache ICLA v2.2 (Rechteinhaber: Kevin Bodenstein) mit deutlichem Entwurf-Hinweis.
   - `CODE_OF_CONDUCT.md`: Contributor Covenant 2.1 mit Kontaktadresse `kb-it-services@outlook.de`.
   - `TRADEMARK.md`: Markenrichtlinie zum Schutz des Namens und Logos „ParamWorker“.
   - `.github/PULL_REQUEST_TEMPLATE.md`: PR-Vorlage mit CLA-Bestätigungshäkchen.

7. **F-Droid Vorbereitung:**
   - Metadatenstruktur unter `fastlane/metadata/android/` für `de-DE` und `en-US` angelegt:
     `title.txt`, `short_description.txt`, `full_description.txt`, `changelogs/902.txt`, `icon.png`, `featureGraphic.png`, `phoneScreenshots/`, `sevenInchScreenshots/`, `tenInchScreenshots/`.
   - Keine Einreichung vorgenommen.

8. **Dokumentation & Roadmap:**
   - `README.md` neu strukturiert (DE und EN): Features, Screenshots, Open Source Lizenz, Bauanleitung inkl. OpenCascade C++ Kompilierung (`scripts/build-occt.ps1`) und F-Droid Hinweis, Mitwirken, Spenden.
   - `docs/ROADMAP.md`: Phase 4 durch „Open Source“ ersetzt, alte Freemium-Pläne als „verworfen am 11.11.2026“ markiert, Phase 5 um F-Droid ergänzt.

---

## 2. Dateien

### Gelöschte Dateien
- `app/src/main/assets/licenses/google_play_notices.txt`
- `app/src/main/kotlin/dev/androidcad/app/Pro.kt`
- `app/src/test/kotlin/dev/androidcad/app/ProTest.kt`

### Neue Dateien
- `LICENSE`
- `CLA.md`
- `CODE_OF_CONDUCT.md`
- `CONTRIBUTING.md`
- `TRADEMARK.md`
- `.github/PULL_REQUEST_TEMPLATE.md`
- `app/src/foss/kotlin/dev/androidcad/app/Distribution.kt`
- `app/src/foss/res/values/strings.xml`
- `app/src/foss/res/values-de/strings.xml`
- `app/src/play/kotlin/dev/androidcad/app/Distribution.kt`
- `app/src/main/assets/licenses/gpl_3.0.txt`
- `fastlane/metadata/android/de-DE/...`
- `fastlane/metadata/android/en-US/...`

### Geänderte Dateien
- `.github/workflows/ci.yml`
- `README.md`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`
- `scripts/play-check.py`
- `docs/PROJEKT_UND_LIZENZANALYSE.md`
- `docs/ROADMAP.md`
- `docs/privacy/index.html`
- `docs/store/listing.md`
- `app/src/main/kotlin/dev/androidcad/app/AppRoot.kt`
- `app/src/main/kotlin/dev/androidcad/app/CadIcons.kt`
- `app/src/main/kotlin/dev/androidcad/app/CadScreen.kt`
- `app/src/main/kotlin/dev/androidcad/app/ImprintDialog.kt`
- `app/src/main/kotlin/dev/androidcad/app/LicensesDialog.kt`
- `app/src/main/kotlin/dev/androidcad/app/MainActivity.kt`
- `app/src/main/kotlin/dev/androidcad/app/ModelScreen.kt`
- `app/src/main/kotlin/dev/androidcad/app/ProjectMenu.kt`
- `app/src/main/kotlin/dev/androidcad/app/WorkspaceScreen.kt`
- `app/src/main/res/values/strings_dialogs.xml`
- `app/src/main/res/values-de/strings_dialogs.xml`
- `app/src/main/res/values/strings_legal.xml`
- `app/src/main/res/values-de/strings_legal.xml`
- `app/src/main/res/values/strings_start.xml`
- `app/src/main/res/values-de/strings_start.xml`

---

## 3. Nachweise und Prüfungen

### 1. Gradle Prüflauf (Vollständig Grün, Exit Code 0)
Ausgeführter Befehl:
```powershell
./gradlew.bat testPlayDebugUnitTest testFossDebugUnitTest test :app:assemblePlayDebug :app:assembleFossDebug :app:lintPlayDebug :app:lintFossDebug :core:model:compileDebugAndroidTestKotlin :core:brep:compileDebugAndroidTestKotlin "-Pandroid.injected.build.abi=x86_64"
```
Ergebnis:
```
BUILD SUCCESSFUL in 2m 53s
156 actionable tasks: 133 executed, 23 up-to-date
Exit Code: 0
```
- Alle Unit-Tests (inkl. `LicensesTest`) grün.
- Beide Debug-APKs (`assemblePlayDebug`, `assembleFossDebug`) erfolgreich assembliert.
- Lint für beide Varianten fehlerfrei (keine fehlenden Übersetzungen, keine Warnungen/Fehler).
- Native C++ Kompilierung für `:core:brep` und `:core:model` erfolgreich.

### 2. Prüfung der Berechtigungen (Merged Manifests)
Abfrage nach `INTERNET` und `BILLING` in `app/build/intermediates/merged_manifests/`:
- `fossDebug/processFossDebugManifest/AndroidManifest.xml`: Keine Fundstellen für `INTERNET` oder `BILLING`.
- `playDebug/processPlayDebugManifest/AndroidManifest.xml`: Keine Fundstellen für `INTERNET` oder `BILLING`.
- Einzige verbliebene Berechtigung: Keine (reine Offline-Anwendung).

### 3. Prüfung der APK-Ressourcen auf Spenden-Strings (`aapt2 dump strings`)
- `playDebug.apk`: **0 Treffer** auf `Spenden`, `Unterstützen`, `support`, `donate`, `liberapay`, `sponsor`.
- `fossDebug.apk`: Enthält wie gefordert die Strings `menu_support`, `support_title`, `support_text`, `support_liberapay`, `support_github`.

### 4. Prüfung auf `com.android.billingclient`
Suche in den assemblierten DEX-Dateien beider APKs:
- `app-play-debug.apk`: 0 Vorkommen von `billingclient`.
- `app-foss-debug.apk`: 0 Vorkommen von `billingclient`.

### 5. `scripts/play-check.py`
33 von 34 Prüfungen bestanden (`OK: 33, WARN: 0, FAIL: 1`).
Einziger Fehlschlag: `Signing: keystore.properties fehlt` (erwartetes Verhalten auf Entwickler-Rechnern ohne private Release-Schlüssel).

---

## 4. Offene Fragen & Hinweise für den Nutzer

1. **Platzhalter für URLs (`// TODO(Nutzer): ...`):**
   - In `app/src/foss/kotlin/dev/androidcad/app/Distribution.kt` und `app/src/play/kotlin/dev/androidcad/app/Distribution.kt` sind folgende Platzhalter hinterlegt und vor dem ersten Release durch reale URLs zu ersetzen:
     - `https://liberapay.com/PLACEHOLDER`
     - `https://github.com/sponsors/PLACEHOLDER`
     - `https://PLACEHOLDER/source`
2. **Rechtliche Prüfung des CLA-Entwurfs (`CLA.md`):**
   - Die CLA basiert auf der Apache Individual Contributor License Agreement v2.2. Sie ist oben ausdrücklich als Entwurf gekennzeichnet und sollte vor der formellen Aufforderung an Mitwirkende anwaltlich geprüft werden.
3. **F-Droid Einreichung:**
   - Die Fastlane-Metadaten sind unter `fastlane/metadata/android/` vollständig vorbereitet. Die eigentliche Einreichung bei F-Droid (Merge Request im F-Droid Data Repo) erfolgt nach Veröffentlichung des Repositories.

---

## 5. Anleitung zur Sichtprüfung beider Varianten

### Variante `play` testen:
1. APK installieren oder ausführen: `./gradlew.bat installPlayDebug`
2. App öffnen und in ein beliebiges Modell wechseln.
3. **Projektmenü oben rechts öffnen:**
   - Prüfen: Es gibt **keinen** Eintrag „Unterstützen …“ / „Support development …“.
4. **Export prüfen:**
   - Menüpunkt „Exportieren“ aufrufen: STEP und 3MF lassen sich ohne Pro-Dialog direkt exportieren.
5. **Parameter prüfen:**
   - Parameter-Tabelle im Modell aufrufen: Werte lassen sich uneingeschränkt anlegen und editieren.
6. **Lizenzdialog prüfen:**
   - Menüpunkt „Lizenzen & Open Source“ aufrufen.
   - Oberster Eintrag: „ParamWorker – Parametrischer 3D-CAD-Modellierer für Android (GPL-3.0-or-later)“.
   - Knopf „Quellcode“ antippen: Öffnet Intent mit der Quellcode-URL.

### Variante `foss` testen:
1. APK installieren oder ausführen: `./gradlew.bat installFossDebug`
2. App öffnen und in ein beliebiges Modell wechseln.
3. **Projektmenü oben rechts öffnen:**
   - Prüfen: Der Eintrag „Unterstützen …“ (mit Herz-Icon) ist vorhanden.
4. **Spenden-Dialog prüfen:**
   - „Unterstützen …“ antippen: Dialog mit Erklärung und den Knöpfen „Liberapay“ und „GitHub Sponsors“ öffnet sich.
   - Knöpfe antippen: Öffnet Browser-Intent zur jeweiligen Spendenplattform.
5. **Export & Lizenzen:**
   - Wie bei `play` sind alle Exporte und Parameter frei zugänglich und die Lizenzseite identisch.
