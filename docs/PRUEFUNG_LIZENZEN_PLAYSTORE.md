# Prüfung: Lizenzen und Google-Play-Vorgaben (AxisWright 0.9.0-beta, Stand 18.10.2026 / aktualisiert 11.11.2026)

Anlass: Die App wird im Play Store und über FOSS-Kanäle kostenlos und werbefrei als freie Open-Source-Software bereitgestellt.
Geprüft wurden alle Bestandteile, die im Release-Paket landen, und die Vorgaben von Google Play, soweit sie sich am Paket,
an den Store-Unterlagen und am Code feststellen lassen. **Keine Rechtsberatung** – bei Zweifeln (v. a. Markenrecht, Impressum,
Händlerstatus) eine Fachanwältin oder einen Fachanwalt fragen.

Automatischer Test: `python scripts/play-check.py` (nach `./gradlew :app:bundleRelease :app:assembleRelease`).

## Ergebnis in Kürze

| Bereich | Ergebnis |
|---|---|
| Freier Vertrieb erlaubt? | **Ja.** Alle Fremdlizenzen erlauben Vertrieb; eigener Code steht unter GPL-3.0-or-later. |
| Lizenzpflichten erfüllt? | **Ja, nach den Korrekturen vom 18.10.2026** (5 fehlende Hinweise ergänzt, LGPL-Quelltextangebot statt Verweis auf den privaten Quelltext). |
| Technische Play-Vorgaben | **Erfüllt** (targetSdk 36, 64 Bit, 16-KB-Seiten, keine Berechtigungen (weder Internet noch Billing, siehe B1), nicht debuggable, Größe). |
| Offen beim Entwickler | Upload-Schlüssel, Datenschutzerklärung ausfüllen + veröffentlichen, Play-Console-Formulare, Händlerstatus/Impressum, Herkunft des Beispiels „Streaminghalterung“ (erledigt 21.10.2026: Eigenentwicklung); Markenprüfung „AxisWright“ (erledigt 22.10.2026, keine Treffer in Klasse 9/42). |

---

## Teil A – Lizenzen

### A1. Bestandteile im Release-Paket

| Bestandteil | Version | Lizenz | Pflichten bei Vertrieb als Binärdatei | Stand |
|---|---|---|---|---|
| Open CASCADE Technology (Geometriekern, eigene `libTK*.so`) | 7.9.3, unverändert | **LGPL 2.1 + OCCT-Ausnahme** | Lizenztext beilegen; Austauschbarkeit der Bibliothek; Quelltext der Bibliothek zugänglich machen (§6); keine Bedingungen, die Änderung/Reverse Engineering für Fehlersuche verbieten | ✅ siehe A2 |
| ├ DELABELLA (in OCCT, Triangulierung) | – | MIT | Copyright- und Lizenzhinweis beilegen | ✅ ergänzt 18.10. |
| └ flex-Kopfdatei (in OCCT, STEP-Leser) | – | BSD (Univ. of California) | Hinweis in der Dokumentation | ✅ ergänzt 18.10. |
| └ Bison-Parser (in OCCT, STEP-Leser) | – | GPL **mit Bison-Ausnahme** | keine (Ausnahme erlaubt beliebige Lizenz des erzeugten Parsers) | ✅ |
| Manifold (Skript-Editor, statisch in `libcadgeom.so`) | 3.5.4 | Apache 2.0 | Lizenztext; NOTICE-Datei falls vorhanden (keine vorhanden) | ✅ |
| ├ `svd.h` | – | MIT (© 2019 wi-re) | Hinweis beilegen | ✅ ergänzt 18.10. |
| ├ `tri_dist.h` (aus NVIDIA PhysX) | – | BSD 3-Clause | Hinweis beilegen; NVIDIA-Name nicht zur Werbung | ✅ ergänzt 18.10. |
| ├ `math.h` (aus FreeBSD msun über musl) | – | Sun-Hinweis + MIT (musl) | Hinweis erhalten | ✅ ergänzt 18.10. |
| └ QuickHull, linalg | – | Public Domain | keine | ✅ |
| Clipper2 (über Manifold) | – | Boost Software License 1.0 | bei Binärvertrieb keine (Text trotzdem beigelegt) | ✅ |
| AndroidX / Jetpack Compose / Material 3 / Splashscreen | BOM 2026.03.01 | Apache 2.0 | Lizenztext | ✅ |
| Kotlin-Standardbibliothek, kotlinx.coroutines | 2.3.20 / 1.10.2 | Apache 2.0 | Lizenztext | ✅ |
| Haze (Glas-Effekt) | 1.6.10 | Apache 2.0 | Lizenztext | ✅ |
| Eigener Code (Kotlin, `cadbrep.cpp`, `cadgeom.cpp`, Skript-Interpreter) | – | proprietär (Repository privat) | – | ✅ |
| App-Symbol, Startbild, Schriftzug, Bedienungssymbole | – | eigene Werke (per Skript erzeugt) | – | ✅ |
| Schrift | – | Systemschrift (Roboto des Geräts), keine Schrift mitgeliefert | – | ✅ |

Alle Lizenztexte liegen in `app/src/main/assets/licenses/` und sind in der App unter **Projektmenü › Lizenzen** einsehbar
(der automatische Test prüft, dass jede genannte Datei vorhanden ist und die fünf neuen Hinweise enthalten sind).

### A2. LGPL 2.1 (OCCT) im Detail – wichtigster Punkt

1. **Dynamisch gelinkt:** OCCT liegt als eigene `.so`-Dateien im Paket (`scripts/build-occt.ps1`, `BUILD_SHARED_LIBS`), der eigene Code
   ruft sie über JNI auf. Das ist der „geeignete Mechanismus für gemeinsame Bibliotheken“ nach §6(b); der eigene Code wird dadurch
   **nicht** LGPL-pflichtig. ✅
2. **Lizenztext und OCCT-Ausnahme** beigelegt. ✅
3. **Quelltext der Bibliothek:** Bis 18.10. verwies die Lizenzseite auf die Bauanleitung „im Quelltext der App“ – der ist aber privat
   und damit nicht erreichbar. **Korrigiert:** schriftliches Angebot nach §6(c) in der Lizenzseite – vollständiger OCCT-7.9.3-Quelltext
   samt Bauanleitung auf Anfrage an kb-it-services@outlook.de, kostenlos, mindestens drei Jahre. ✅
   → **Pflicht für dich:** Anfragen tatsächlich beantworten können. Empfehlung: OCCT-Quelltext (Tag V7_9_3) und `scripts/build-occt.ps1`
   als ZIP aufbewahren; noch einfacher wäre ein kleines **öffentliches** Repository nur mit diesen beiden Dingen und ein Link in der Lizenzseite.
4. **Keine einschränkenden Bedingungen:** Falls später Nutzungsbedingungen (EULA) dazukommen, müssen sie für die OCCT-Bibliotheken
   Änderung und Reverse Engineering zur Fehlersuche ausdrücklich erlauben (§6). Die Play-Store-Bedingungen selbst gelten als unproblematisch.
5. **Unverändert:** Der Build verwendet den unveränderten Tag V7_9_3 (nur Build-Optionen, keine Patches). Sollte OCCT je gepatcht werden,
   müssen die Änderungen mit angeboten werden.

### A3. Weitere Punkte

- **OpenSCAD:** Der Skript-Editor ist eine **eigene** Umsetzung der OpenSCAD-Sprache (`core/lang`), kein OpenSCAD-Code (GPL) übernommen.
  Eine Programmiersprache und ihr Dateiformat sind nicht urheberrechtlich geschützt. Der Name wird nur beschreibend benutzt
  („Skript-Editor für OpenSCAD-Dateien“) – so beibehalten, nicht im App-Titel oder Logo verwenden. ✅
- **Dateiformate:** STEP (ISO 10303, Export über OCCT), STL und 3MF (offene, lizenzfreie Spezifikation) dürfen frei unterstützt werden. ✅
- **Beispiele:** `Box.scad` ist eigener Inhalt. `Streaminghalterung.scad`: eigene Konstruktion (bestätigt 21.10.2026), Copyright-Kopf in der Datei.
  Stammt die Konstruktion von Thingiverse, Printables o. Ä., gilt deren Lizenz (oft CC BY-NC – dann **nicht** in einer verkauften App
  erlaubt). Ist sie eigene Arbeit oder mit einem KI-Werkzeug selbst erstellt, ist sie unproblematisch. ⚠️
- **App-Name „AxisWright“:** Websuche ohne Treffer in Software/CAD; Registerprüfung DPMA, EUIPO und WIPO am 22.10.2026 durch dich:
  keine Treffer in Klasse 9 oder 42. ✓
- **Eigenes Repository:** privat (GitHub antwortet ohne Anmeldung mit 404). Gut so – ein öffentliches Repository ohne Lizenz wäre zwar
  rechtlich geschützt, ließe aber jeden die App selbst bauen. ✅

---

## Teil B – Google-Play-Vorgaben

### B1. Automatisch geprüft (`scripts/play-check.py`, Lauf vom 18.10.2026)

| Prüfung | Ergebnis |
|---|---|
| Paketname `io.github.kevbo8327.axiswright`, versionCode 900 | ✅ |
| targetSdk 36 (Pflicht für neue Apps ab 31.08.2026: 36) | ✅ |
| Berechtigungen: Keine Nutzerberechtigungen, weder Internet noch Billing (vollständig entfernt). Keine Laufzeitberechtigungen. | ✅ (Stand 11.11.2026) |
| 64-Bit-Pflicht (arm64-v8a, x86_64) | ✅ |
| **16-KB-Seitengröße**: alle 62 nativen Bibliotheken mit LOAD-Ausrichtung ≥ 16 384, im Paket passend abgelegt | ✅ |
| Nicht debuggable; nur die Startaktivität ungeschützt exportiert (der AndroidX-ProfileInstallReceiver ist durch `DUMP` geschützt) | ✅ |
| Größe: AAB 82 MB, Universal-APK 112 MB – je Gerät deutlich weniger; Grenze 200 MB | ✅ |
| App-Symbol 512 × 512 als 32-Bit-PNG (war 24 Bit – **korrigiert** 18.10.) | ✅ |
| Grafik für den Eintrag 1024 × 500 ohne Alphakanal | ✅ |
| Screenshots Smartphone / 7″ / 10″: je 4, Größe und Seitenverhältnis gültig | ✅ |
| Store-Texte DE/EN: Titel 29/30, Kurzbeschreibung 75 bzw. 62/80, Langbeschreibung 2111 bzw. 1890/4000; kein alter Name; keine Superlative | ✅ |
| **Signatur**: Release ist noch mit dem Debug-Schlüssel signiert | ❌ Upload-Schlüssel anlegen (README) |
| **Datenschutzerklärung**: Platzhalter [NAME], [ANSCHRIFT], [E-MAIL-ADRESSE] (DE/EN) | ❌ ausfüllen und öffentlich hosten |
| Screenshots zeigen noch den alten Stand (Name, Startseite, Leisten) | ⚠️ neu aufnehmen |

### B2. Von Hand zu erledigen (Play Console – lässt sich nicht am Paket prüfen)

| Punkt | Was zu tun ist | Hinweis |
|---|---|---|
| Entwicklerkonto | Konto (25 $), Identitätsprüfung | vor allem anderen, dauert Tage |
| **Händlerstatus (EU Digital Services Act)** | Kostenlose App ohne In-App-Käufe; Name, Anschrift und E-Mail im Impressum der App und Website. | Ggf. Geschäftsadresse statt Privatanschrift verwenden |
| **Impressum** | Für geschäftsmäßige Angebote in Deutschland Anbieterkennzeichnung (§ 5 DDG) – z. B. auf der Website/Datenschutzseite verlinken | anwaltlich klären lassen |
| Zahlungsprofil | Nicht erforderlich (kostenlose App ohne In-App-Käufe) | – |
| In-App-Kauf | Entfallen (alle Funktionen kostenlos und frei verfügbar) | Open Source (GPL-3.0) |
| Datenschutzerklärung | öffentliche URL eintragen | siehe B1 |
| Datensicherheit | Vollständig offline: keine Daten erhoben („Keine Daten erhoben“ im Formular). „Feedback senden“ öffnet nur die E-Mail-App des Nutzers (nichts erhoben). | Siehe B4 |
| Inhaltsbewertung (IARC) | Fragebogen: keine Gewalt, keine Nutzerinhalte, kein Austausch | ergibt „USK ab 0“ |
| Zielgruppe | Erwachsene/ab 13; **nicht** für Kinder (sonst Familienrichtlinien) | |
| Werbung | „Enthält keine Werbung“ | |
| App-Zugriff | keine Anmeldung nötig | |
| Kontakt-E-Mail im Eintrag | kb-it-services@outlook.de | Pflichtfeld |
| Sprachen | Nur den **deutschen** Eintrag veröffentlichen, solange die App nur Deutsch kann (sonst irreführende Metadaten) | Englisch ab Phase 3 |
| Geschlossener Test | ≥ 12 Tester über ≥ 14 Tage (neue private Konten) vor Produktionszugang | Beta ab 20.10. |
| Metadaten-Richtlinie | Andere Programmnamen nur beschreibend (STEP, OpenSCAD) – erfüllt; keine Leistungsversprechen, die die App nicht hält | ✅ |

### B3. Richtlinien zum Verhalten der App (Code-Durchsicht)

- Kein Netzwerkzugriff, keine Berechtigungen, keine eigenen Server, keine Analyse-/Werbe-SDKs, keine Hintergrunddienste → keine sensiblen Richtlinienbereiche betroffen. ✅
- Dateien nur über das System-Dateiauswahlfenster (Storage Access Framework) – keine Speicherberechtigung nötig. ✅
- Absturz-/ANR-Robustheit: Hänger nach App-Wechsel am 16.10. behoben (TextureView), unlesbare Dateien führen nie zum Absturz. Vitals im Test beobachten. ✅
- Barrierefreiheit: Inhaltsbeschreibungen für Bedienknöpfe vorhanden (keine Pflicht, aber geprüft beim Layout-Audit). ✅

### B4. Datensicherheitsformular nach Entfernung von Play Billing (Stand 11.11.2026)

- **Fakten:** Die App hat keine eigenen Server, keine Werbung, keine Analyse und keine In-App-Käufe mehr.
  Sie fordert keinerlei Berechtigungen an (kein Internet). Daher: Keine Datenerhebung („Keine Daten erhoben“).
- **Weitere Stellen anpassen:** Datenschutzerklärung (Stand 11.11.2026) auf der Google-Sites-Seite
  bestätigen; veraltete Kauf- und Lizenzhinweise zu Play Services / Billing wurden entfernt.

---

## Änderungen durch diese Prüfung (18.10.2026)

- 5 Lizenzhinweise ergänzt (`assets/licenses/`: `occt_delabella_mit.txt`, `occt_flex_bsd.txt`, `manifold_svd_mit.txt`, `physx_bsd_3.txt`,
  `manifold_math_sun_musl.txt`) und in der Lizenzseite verknüpft.
- OCCT-Hinweis: schriftliches Quelltextangebot statt Verweis auf den privaten App-Quelltext.
- Store-Symbol als 32-Bit-PNG.
- Neuer Test `scripts/play-check.py`.

## Nachtrag 01.11.2026 / 11.11.2026

- 01.11.2026: Temporärer Einbau von Play Billing für Pro-Kauf.
- 11.11.2026: Vollständige Entfernung des Pro-Modells & Play Billing: App ist 100 % freie Open-Source-Software (GPL-3.0), ohne Berechtigungen und komplett offline.
