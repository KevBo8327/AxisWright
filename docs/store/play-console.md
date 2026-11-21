# Google Play Console – Ausfüllhilfe für AxisWright

Fertige Angaben und Antworten zum Abschreiben für die Neuanlage und Einrichtung von **AxisWright** (`io.github.kevbo8327.axiswright`) in der Google Play Console.

---

## 1. App anlegen & Grundeinstellungen

- **App-Name:** `AxisWright – CAD für Android` (bzw. `AxisWright`)
- **Standardsprache:** Deutsch (`de-DE`) oder Englisch (`en-US`) – beide Store-Einträge sind vorbereitet
- **App oder Spiel:** App
- **Kostenlos oder kostenpflichtig:** Kostenlos (keine In-App-Käufe, keine Werbung, GPL-3.0-or-later)
- **Kategorie:** Produktivität
- **Tags (optional):** Produktivität / Tools / Konstruktion (falls verfügbar)
- **Kontaktangaben für den Store-Eintrag:**
  - **E-Mail-Adresse:** `kb-it-services@outlook.de`
  - **Telefonnummer (optional/EU-DSA):** `+49 3322 8312325`
  - **Website (optional):** `https://kevbo8327.github.io/AxisWright/`

> **Voraussetzung GitHub Pages:** In den GitHub-Repository-Einstellungen unter *Settings → Pages* muss als Quelle der Zweig `main` und der Ordner `/docs` aktiviert sein, damit die Datenschutz-URL erreichbar ist.

---

## 2. Ablauf: Von der Neuanlage bis zur Veröffentlichung

1. **App anlegen:** Namen, Standardsprache, *App*, *Kostenlos* wählen und Erklärungen zu Programmrichtlinien/US-Exportgesetzen bestätigen.
2. **Pflichtangaben unter „App-Inhalte“ ausfüllen** (Abschnitt 3 unten): Datenschutzerklärung, App-Zugriff, Werbung, Inhaltsbewertung (IARC), Zielgruppe, Datensicherheit sowie Regierungs-/Finanz-/Gesundheits-Erklärungen.
3. **Store-Eintrag einrichten** (Abschnitt 4 unten): Texte (`de-DE` und `en-US`), App-Symbol (`512 × 512`), Grafik (`1024 × 500`) und Screenshots (`phone`, `tablet7`, `tablet10` je Sprache) hochladen.
4. **Interner Test:**
   - Im Track *Interner Test* ein Release anlegen.
   - Signiertes **AAB** (`./gradlew.bat :app:bundlePlayRelease` mit eigenem Upload-Schlüssel in `keystore.properties`) hochladen (`versionCode` `904`, `0.9.4-beta`).
   - Play App Signing bestätigen (von Google verwalteter App-Signaturschlüssel, eigener Schlüssel bleibt Upload-Schlüssel).
   - Release-Notes aus `fastlane/metadata/android/{de-DE,en-US}/changelogs/904.txt` übernehmen und eigenen Account testen.
5. **Geschlossener Test (Pflicht für neue private Entwicklerkonten):**
   - Track *Geschlossener Test* einrichten und dasselbe AAB hochstufen/freigeben.
   - Mindestens **12 Tester** einladen (Opt-in-Link annehmen lassen) und den Test **mindestens 14 Tage ununterbrochen** aktiv laufen lassen, bevor der Antrag auf Produktionszugriff gestellt wird.
6. **Produktion:** Nach Ablauf der 14 Tage und Freigabe des Produktionszugriffs das geprüfte Release in *Produktion* ausrollen.

---

## 3. App-Inhalte (fertige Antworten)

### Datenschutzerklärung
- **URL:** `https://kevbo8327.github.io/AxisWright/privacy/`
  *(Zweisprachige Seite DE/EN ohne Tracker, externe Schriften oder Skripte; Stand: 17.11.2026)*

### App-Zugriff
- **Frage:** Sind Teile der App eingeschränkt (Anmeldung, Abo, Geoblocking o. Ä.)?
- **Antwort:** **Alle Funktionen sind ohne speziellen Zugriff verfügbar** (kein Login, kein Konto, keine Paywall).

### Werbung
- **Frage:** Enthält Ihre App Werbung?
- **Antwort:** **Nein, meine App enthält keine Werbung.**

### Inhaltsbewertung (IARC-Fragebogen)
- **E-Mail-Adresse:** `kb-it-services@outlook.de`
- **Kategorie:** **Dienstprogramm, Produktivität, Kommunikation oder Sonstiges** *(Utility, Productivity, Communication, or Other)*
- **Alle Inhaltsfragen (Gewalt, Sexualität, Sprache, Drogen, Glücksspiel, beängstigende Inhalte):** **Nein**
- **Nutzerinteraktion / Austausch nutzergenerierter Inhalte (Chat, Kommentare, Profile):** **Nein**
- **Standortfreigabe an andere Nutzer:** **Nein**
- **Erwerb digitaler Güter / In-App-Käufe:** **Nein**
- **Webbrowser / Suchmaschine / Drittanbieter-Inhalte:** **Nein**
- *(Erwartetes Ergebnis: USK ab 0 / PEGI 3 / Everyone)*

### Zielgruppe und Inhalte
- **Zielaltersgruppe:** **18 Jahre und älter** *(bzw. Erwachsene / ab 13, keinesfalls Altersgruppen unter 13 auswählen, damit keine Familienrichtlinien greifen)*
- **Unbeabsichtigte Ansprache von Kindern:** **Nein** (nüchterne CAD-Konstruktions-Oberfläche ohne kindliche Grafiken/Figuren)

### Datensicherheit (Data Safety)
- **Erhebt oder teilt Ihre App erforderliche Nutzerdaten?** **Nein**
- **Begründung / Hintergrund für Rückfragen:**
  - Die App fordert **keinerlei Android-Berechtigungen** an (auch keine `INTERNET`-Berechtigung).
  - Projekte, Skizzen, Parameter und Einstellungen werden ausschließlich **lokal** im privaten App-Speicher auf dem Gerät gespeichert.
  - Import und Export (STL, 3MF, STEP, `.axiswright.json`, `.scad`) erfolgen ausschließlich auf Initiative des Nutzers über das Android-System-Dateiauswahlfenster (Storage Access Framework).
  - „Feedback senden“ öffnet lediglich einen `ACTION_SENDTO`-Intent in der lokalen E-Mail-App des Nutzers; die App selbst versendet nichts und überträgt keine Daten.
  - Es sind keine Analyse-, Crash- oder Werbe-SDKs eingebunden.

### Weitere Erklärungen unter „App-Inhalte“
- **Regierungs-Apps:** **Nein** (keine Zugehörigkeit zu einer Behörde)
- **Finanzfunktionen:** **Nein** (keine Finanz-, Banking- oder Krypto-Funktionen)
- **Gesundheits-Apps:** **Nein** (keine medizinischen oder Gesundheitsfunktionen)
- **Nachrichten-Apps:** **Nein**
- **Werbe-ID (Advertising ID):** **Nein** (App verwendet keine Werbe-ID)

---

## 4. Store-Eintrag (DE & EN, Zeichenlängen geprüft)

Textquelle: [`docs/store/listing.md`](listing.md) bzw. `fastlane/metadata/android/{de-DE,en-US}/`.

### Deutsch (`de-DE`)

- **Titel (`28 / 30` Zeichen):**
```text
AxisWright – CAD für Android
```

- **Kurzbeschreibung (`75 / 80` Zeichen):**
```text
Parametrische 3D-Konstruktion auf dem Tablet: skizzieren, bemaßen, drucken.
```

- **Langbeschreibung (`2692 / 4000` Zeichen):**
```text
AxisWright ist ein parametrisches CAD-Programm für Android-Tablets und -Smartphones. Sie zeichnen eine Skizze, bemaßen sie und machen daraus ein Bauteil – mit echter, exakter Geometrie, die sich jederzeit über ihre Maße ändern lässt. Alles läuft direkt auf dem Gerät: ohne Konto, ohne Cloud, ohne eigene Server.

SKIZZIEREN
• Linie, Rechteck (Ecke, Mitte, 3 Punkte), Kreis (Mitte, 2 oder 3 Punkte, tangential), Bogen, Langloch, Vieleck, Text als Skizzenelement (Gravur/erhaben)
• Kürzen, Verlängern, Versatz, Ecken runden sowie Verschieben, Drehen, Skalieren und Kopieren der Auswahl
• 16 geometrische Bedingungen (parallel, senkrecht, tangential, symmetrisch, konzentrisch …) und 8 Maßarten
• Der Löser zeigt, ob die Skizze vollständig bestimmt ist – ändern Sie ein Maß, passt sich alles an
• Muster in der Skizze, deren Anzahl sich später ändern lässt

KÖRPER
• Extrusion (Hinzufügen, Abtragen, Schnittmenge; zwei Seiten mit verschiedenen Längen, bis Fläche), Drehen
• Bohrungen mit Senkung/Kegelsenkung und Normgrößen M3–M10
• Verrunden und Anfasen von Kanten, Aushöhlen zur Schale
• Reihen-, Raster- und Kreismuster, Spiegeln
• Skizzen auf Körperflächen und Arbeitsebenen; Körperkanten in die Skizze projizieren
• Schnittansicht zur Prüfung von Hohlräumen und Innengeometrie
• Der Modellaufbau bleibt als Verlauf erhalten: Jeder Schritt ist änderbar, das Modell baut sich neu auf

PARAMETER UND MESSEN
• Parameter-Tabelle: benannte Werte und Formeln wie „wand * 3“ für Maße, Längen, Winkel und Wandstärken
• Messen von Längen, Radien, Flächen, Abständen und Winkeln direkt im 3D-Bild
• Rückgängig und Wiederholen für Skizze und Modell

AUSTAUSCH
• Export als STL und 3MF für den 3D-Druck
• Export als STEP mit exakter Geometrie für andere CAD-Programme
• Projekte als Datei exportieren und wieder importieren
• Zusätzlich ein Skript-Editor für OpenSCAD-Dateien mit Schiebereglern für Parameter

FÜR FINGER, STIFT UND MAUS
• Bedienung für Touch ausgelegt, mit Stift-Unterstützung (auch Radierer) sowie Maus und Tastaturkürzeln
• Tablet- und Smartphone-Layout, helles und dunkles Design

IHRE DATEN BLEIBEN BEI IHNEN
Vollständig offline: keine Internetverbindung, keine Werbung, kein Tracking – Ihre Projekte bleiben auf Ihrem Gerät.

AxisWright ist freie Open-Source-Software (GPL-3.0-or-later). Alle Funktionen sind ohne Einschränkungen oder Abos für alle frei nutzbar.

Hinweis: AxisWright ist ein Konstruktionswerkzeug. Prüfen Sie Maße und Festigkeit Ihrer Teile vor der Fertigung selbst; für sicherheitsrelevante Bauteile ist die App nicht vorgesehen.

Der Geometriekern ist Open CASCADE Technology. AxisWright befindet sich in aktiver Entwicklung – Rückmeldungen sind sehr willkommen.
```

### English (`en-US`)

- **Title (`28 / 30` chars):**
```text
AxisWright – CAD for Android
```

- **Short description (`62 / 80` chars):**
```text
Parametric 3D design on your tablet: sketch, dimension, print.
```

- **Full description (`2437 / 4000` chars):**
```text
AxisWright is a parametric CAD app for Android tablets and phones. Draw a sketch, add dimensions and turn it into a part – with real, exact geometry that you can change at any time through its dimensions. Everything runs on your device: no account, no cloud, no servers of our own.

SKETCHING
• Line, rectangle (corner, centre, 3 points), circle (centre, 2 or 3 points, tangent), arc, slot, polygon, text as sketch element (engraving/embossed)
• Trim, extend, offset, round corners, plus move, rotate, scale and copy selection
• 16 geometric constraints (parallel, perpendicular, tangent, symmetric, concentric …) and 8 kinds of dimensions
• The solver shows whether the sketch is fully defined – change a dimension and everything follows
• Sketch patterns whose count you can change later

SOLIDS
• Extrude (add, cut, intersect; two sides with different lengths, up to face), revolve
• Holes with counterbore/countersink and standard sizes M3–M10
• Fillet and chamfer edges, shell into a hollow part
• Linear, grid and circular patterns, mirror
• Sketches on faces and work planes; project edges into a sketch
• Section view to inspect interior geometry and cavities
• The model history is kept: every step stays editable and the model rebuilds itself

PARAMETERS AND MEASURING
• Parameter table: named values and formulas like “wall * 3” for dimensions, lengths, angles and wall thickness
• Measure lengths, radii, areas, distances and angles right in the 3D view
• Undo and redo for sketch and model

EXCHANGE
• Export STL and 3MF for 3D printing
• Export STEP with exact geometry for other CAD software
• Export projects as files and import them again
• Plus a script editor for OpenSCAD files with sliders for parameters

FOR FINGER, PEN AND MOUSE
• Designed for touch, with stylus support (including the eraser), mouse and keyboard shortcuts
• Tablet and phone layouts, light and dark theme

YOUR DATA STAYS WITH YOU
Completely offline: no internet connection, no ads, no tracking – your projects stay on your device.

AxisWright is free open-source software (GPL-3.0-or-later). All features are freely available without paywalls or subscriptions.

Note: AxisWright is a design tool. Check the dimensions and strength of your parts yourself before manufacturing; the app is not intended for safety-critical components.

The geometry kernel is Open CASCADE Technology. AxisWright is in active development – feedback is very welcome.
```

### Grafiken (Pfadablage im Repo)

- **App-Symbol (`512 × 512`, 32-Bit-PNG):** `docs/store/icon-512.png`
- **Feature-Grafik (`1024 × 500`, ohne Alpha):** `docs/store/feature-graphic.png`
- **Screenshots Deutsch:** `fastlane/metadata/android/de-DE/images/{phoneScreenshots,sevenInchScreenshots,tenInchScreenshots}/` (und `docs/store/screenshots/*-de-*.png`)
- **Screenshots Englisch:** `fastlane/metadata/android/en-US/images/{phoneScreenshots,sevenInchScreenshots,tenInchScreenshots}/` (und `docs/store/screenshots/*-en-*.png`)
