"""
Prüft das Release-Paket und die Store-Unterlagen gegen die Vorgaben von Google Play (Stand Oktober 2026).

Aufruf (nach ./gradlew :app:bundleRelease :app:assembleRelease):  python scripts/play-check.py
Ausgabe: je Prüfung OK / WARNUNG / FEHLER, Exit-Code 1 bei mindestens einem FEHLER.

Geprüft wird, was sich am Paket und an den Dateien maschinell feststellen lässt. Formulare in der Play Console (Datensicherheit,
Inhaltsbewertung, Zielgruppe, Händlerstatus) und Richtlinien zum Verhalten der App lassen sich so nicht prüfen – siehe
docs/PRUEFUNG_LIZENZEN_PLAYSTORE.md.
"""
import glob
import os
import re
import struct
import subprocess
import sys
import zipfile

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
AAB = os.path.join(ROOT, "app", "build", "outputs", "bundle", "release", "app-release.aab")
STORE = os.path.join(ROOT, "docs", "store")

PACKAGE = "io.github.kevbo8327.paramworker"
MIN_TARGET_SDK = 35        # Pflicht für neue Apps und Updates seit 31.08.2025
RECOMMENDED_TARGET_SDK = 36  # Pflicht ab 31.08.2026 (Android 16)
PAGE = 16384               # 16-KB-Seiten: Pflicht für native Bibliotheken bei targetSdk ≥ 35 seit 01.11.2025
MAX_DOWNLOAD = 200 * 1024 * 1024  # Grenze für die komprimierte Downloadgröße je Gerät

results = []

def ok(msg): results.append(("OK", msg))
def warn(msg): results.append(("WARNUNG", msg))
def fail(msg): results.append(("FEHLER", msg))

def tool(name):
    """Neueste Version eines build-tools-Programms (aapt2, apksigner)."""
    for d in sorted(glob.glob(os.path.join(SDK, "build-tools", "*")), reverse=True):
        for ext in ("", ".exe", ".bat"):
            p = os.path.join(d, name + ext)
            if os.path.isfile(p): return p
    return None

def run(args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

# ---- 1. Paket ------------------------------------------------------------------------------------------
def check_package():
    if not os.path.isfile(APK) or not os.path.isfile(AAB):
        fail("Release-Paket fehlt: erst ./gradlew :app:bundleRelease :app:assembleRelease"); return
    aapt2 = tool("aapt2")
    badging = run([aapt2, "dump", "badging", APK])
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
    if pkg and pkg.group(1) == PACKAGE: ok(f"Paketname {PACKAGE}, versionCode {pkg.group(2)}, versionName {pkg.group(3)}")
    else: fail(f"Paketname stimmt nicht: {pkg.group(1) if pkg else '?'}")
    target = int(re.search(r"targetSdkVersion:'(\d+)'", badging).group(1))
    if target < MIN_TARGET_SDK: fail(f"targetSdk {target} < {MIN_TARGET_SDK}")
    elif target < RECOMMENDED_TARGET_SDK: warn(f"targetSdk {target}: ab 31.08.2026 ist {RECOMMENDED_TARGET_SDK} Pflicht")
    else: ok(f"targetSdk {target}")
    perms = re.findall(r"uses-permission: name='([^']+)'", badging)
    # vom Build selbst erzeugt (AndroidX), keine Nutzerberechtigung und nicht in der Datensicherheit anzugeben
    internal = [p for p in perms if p.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")]
    # aus der Play Billing Library (Einmalkauf „ParamWorker Pro“): erlaubt nur die Bindung an den Play Store, keine Nutzerdaten
    billing = "com.android.vending.BILLING"
    if billing in perms: ok("Berechtigung com.android.vending.BILLING (In-App-Kauf über Google Play)")
    other = [p for p in perms if p not in internal and p != billing]
    if other: warn("Berechtigungen (in der Datensicherheit begründen): " + ", ".join(other))
    else: ok("keine Berechtigungen (passt zu „keine Daten erhoben“, kein Internet)")
    abis = re.search(r"native-code: (.*)", badging)
    abis = abis.group(1).replace("'", "").split() if abis else []
    if "arm64-v8a" in abis: ok("64-Bit-Pflicht erfüllt: " + ", ".join(abis))
    else: fail("arm64-v8a fehlt (64-Bit-Pflicht)")
    if "application-debuggable" in badging: fail("Release ist debuggable")
    else: ok("nicht debuggable")
    tree = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", APK])
    # exportierte Komponenten ohne Schutz durch eine Berechtigung (der AndroidX-ProfileInstallReceiver verlangt DUMP)
    blocks = re.split(r"\n\s+E: (?=activity|receiver|service|provider)", tree)[1:]
    open_ = [b.split("\n")[0] for b in blocks if "exported(0x01010010)=true" in b and ":permission(" not in b.split("E: intent-filter")[0]]
    if len(open_) <= 1: ok(f"ungeschützt exportiert: {len(open_)} Komponente (Startaktivität)")
    else: warn(f"{len(open_)} ungeschützt exportierte Komponenten – prüfen")
    size = os.path.getsize(APK)
    (ok if size < MAX_DOWNLOAD else fail)(f"Universal-APK {size / 2**20:.1f} MB (je Gerät aus dem AAB deutlich weniger; Grenze 200 MB)")
    ok(f"AAB {os.path.getsize(AAB) / 2**20:.1f} MB")
    java = os.path.join(os.environ.get("JAVA_HOME", r"C:\Program Files\Android\Android Studio\jbr"), "bin", "java")
    jar = os.path.join(os.path.dirname(tool("aapt2")), "lib", "apksigner.jar")
    certs = run([java, "-jar", jar, "verify", "--print-certs", APK])
    if "CN=Android Debug" in certs: fail("signiert mit dem Debug-Schlüssel – Upload-Schlüssel anlegen (keystore.properties, siehe README)")
    elif "Signer #1 certificate DN" in certs: ok("signiert mit eigenem Upload-Schlüssel")
    else: fail("Signatur nicht prüfbar")

# ---- 2. Native Bibliotheken: 16-KB-Seiten --------------------------------------------------------------
def load_aligns(data):
    """p_align aller PT_LOAD-Segmente einer 64-Bit-ELF-Datei."""
    if data[:4] != b"\x7fELF" or data[4] != 2: return []
    phoff, = struct.unpack_from("<Q", data, 0x20)
    phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    out = []
    for i in range(phnum):
        p_type, = struct.unpack_from("<I", data, phoff + i * phentsize)
        if p_type == 1: out.append(struct.unpack_from("<Q", data, phoff + i * phentsize + 0x30)[0])
    return out

def check_native():
    if not os.path.isfile(APK): return
    bad, unaligned, n = [], [], 0
    with zipfile.ZipFile(APK) as z, open(APK, "rb") as raw:
        for info in z.infolist():
            if not info.filename.endswith(".so"): continue
            n += 1
            if any(a < PAGE for a in load_aligns(z.read(info))): bad.append(info.filename)
            if info.compress_type == zipfile.ZIP_STORED:  # unkomprimiert: muss im ZIP auf 16 KB liegen
                raw.seek(info.header_offset)
                hdr = raw.read(30)
                name_len, extra_len = struct.unpack_from("<HH", hdr, 26)
                if (info.header_offset + 30 + name_len + extra_len) % PAGE: unaligned.append(info.filename)
    if bad: fail(f"{len(bad)} Bibliotheken nicht für 16-KB-Seiten gebaut: " + ", ".join(sorted(bad)[:6]))
    else: ok(f"alle {n} nativen Bibliotheken mit 16-KB-Segmenten (LOAD-Ausrichtung ≥ 16384)")
    if unaligned: fail(f"{len(unaligned)} unkomprimierte Bibliotheken im APK nicht 16-KB-ausgerichtet")
    else: ok("Lage der Bibliotheken im APK passt zu 16-KB-Seiten")

# ---- 3. Store-Grafiken ---------------------------------------------------------------------------------
def check_graphics():
    icon = os.path.join(STORE, "icon-512.png")
    im = Image.open(icon)
    if im.size != (512, 512) or os.path.getsize(icon) > 1024 * 1024: fail("App-Symbol: 512 × 512 PNG, höchstens 1 MB")
    elif im.mode != "RGBA": warn(f"App-Symbol ist {im.mode}: Google verlangt ein 32-Bit-PNG (RGBA)")
    else: ok("App-Symbol 512 × 512, 32-Bit-PNG")
    fg = Image.open(os.path.join(STORE, "feature-graphic.png"))
    if fg.size != (1024, 500): fail(f"Grafik für den Eintrag {fg.size}, verlangt 1024 × 500")
    elif fg.mode not in ("RGB", "L"): fail("Grafik für den Eintrag: 24-Bit-PNG oder JPEG ohne Alphakanal")
    else: ok("Grafik für den Eintrag 1024 × 500 ohne Alphakanal")
    for kind in ("phone", "tablet7", "tablet10"):
        shots = sorted(glob.glob(os.path.join(STORE, "screenshots", f"{kind}-*.png")))
        problems = []
        for s in shots:
            img = Image.open(s); w, h = img.size
            if img.mode not in ("RGB", "L"): problems.append(f"{os.path.basename(s)} mit Alphakanal")
            if not (320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)):
                problems.append(f"{os.path.basename(s)} {w}×{h}")
        if not 2 <= len(shots) <= 8: fail(f"Screenshots {kind}: {len(shots)} (2 bis 8 verlangt)")
        elif problems: fail(f"Screenshots {kind}: Größe/Seitenverhältnis: " + ", ".join(problems))
        else: ok(f"Screenshots {kind}: {len(shots)}, Größe und Seitenverhältnis passen")

# ---- 4. Store-Texte und Name ---------------------------------------------------------------------------
def check_listing():
    text = open(os.path.join(STORE, "listing.md"), encoding="utf-8").read()
    for lang, title_h, short_h, long_h in (("Deutsch", "**Titel**", "**Kurzbeschreibung**", "**Langbeschreibung**"),
                                          ("English", "**Title**", "**Short description**", "**Full description**")):
        part = text.split(f"## {lang}", 1)[1].split("\n## ", 1)[0]
        def field(head, nxt):
            body = part.split(head, 1)[1]
            return (body.split(nxt, 1)[0] if nxt else body).strip()
        title, short, full = field(title_h, short_h), field(short_h, long_h), field(long_h, None)
        for name, value, limit in (("Titel", title, 30), ("Kurzbeschreibung", short, 80), ("Langbeschreibung", full, 4000)):
            (ok if 0 < len(value) <= limit else fail)(f"{lang}: {name} {len(value)}/{limit} Zeichen")
        if "Partwright" in part: fail(f"{lang}: alter Name „Partwright“ im Store-Text")
        hits = [w for w in ("beste", "best ", "Nr. 1", "#1", "kostenlos", "free download", "Top ") if w.lower() in (title + short).lower()]
        if hits: warn(f"{lang}: Titel/Kurzbeschreibung enthalten {hits} (Metadaten-Richtlinie)")
    shots_with_old_name = []  # Bilder lassen sich nicht lesen – Hinweis über das Änderungsdatum gegenüber der Umbenennung
    rename = os.path.getmtime(os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml"))
    for s in glob.glob(os.path.join(STORE, "screenshots", "*.png")):
        if os.path.getmtime(s) < rename: shots_with_old_name.append(os.path.basename(s))
    if shots_with_old_name: warn(f"{len(shots_with_old_name)} Screenshots älter als der aktuelle App-Name/Stand – neu aufnehmen")

# ---- 5. Datenschutzerklärung ---------------------------------------------------------------------------
def check_privacy():
    html = open(os.path.join(ROOT, "docs", "privacy", "index.html"), encoding="utf-8").read()
    holes = sorted(set(re.findall(r"\[[A-ZÄÖÜ\- ]{3,}\]", html)))
    if holes: fail("Datenschutzerklärung hat Platzhalter: " + ", ".join(holes) + " – ausfüllen und öffentlich erreichbar machen")
    else: ok("Datenschutzerklärung ohne Platzhalter (öffentliche URL in der Play Console eintragen)")
    imprint = open(os.path.join(ROOT, "app", "src", "main", "kotlin", "dev", "androidcad", "app", "ImprintDialog.kt"), encoding="utf-8").read()
    holes = sorted(set(re.findall(r'"(\[[A-ZÄÖÜ\- ]{3,}\])"', imprint)))
    if holes: fail("Impressum in der App hat Platzhalter: " + ", ".join(holes) + " – in ImprintDialog.kt (object Imprint) eintragen")
    else: ok("Impressum in der App ausgefüllt")

# ---- 6. Lizenztexte -----------------------------------------------------------------------------------
def check_licenses():
    kt = open(os.path.join(ROOT, "app", "src", "main", "kotlin", "dev", "androidcad", "app", "LicensesDialog.kt"), encoding="utf-8").read()
    files = sorted(set(re.findall(r'"([a-z0-9_.\-]+\.txt)"', kt)))
    missing = [f for f in files if not os.path.isfile(os.path.join(ROOT, "app", "src", "main", "assets", "licenses", f))]
    if missing: fail("Lizenztexte fehlen in assets/licenses: " + ", ".join(missing))
    else: ok(f"alle {len(files)} in der Lizenzseite genannten Lizenztexte liegen bei")
    # Fremdcode mit eigener Hinweispflicht in mitgelieferten Bibliotheken (siehe Prüfbericht)
    required = {"MIT (manifold svd.h)": "wi-re", "BSD-3 (NVIDIA PhysX in manifold)": "NVIDIA",
                "MIT (Delabella in OCCT)": "Sokalski", "BSD (flex in OCCT)": "Regents of the University of California",
                "MIT (musl in manifold)": "Rich Felker", "strtod (Lucent) in OCCT": "Lucent Technologies",
                "Unicode, Inc. in OCCT": "Unicode, Inc.", "LLVM-Ausnahme (libc++_shared.so)": "LLVM Exceptions"}
    texts = "".join(open(f, encoding="utf-8").read() for f in glob.glob(os.path.join(ROOT, "app", "src", "main", "assets", "licenses", "*.txt")))
    for what, marker in required.items():
        (ok if marker in texts else fail)(f"Hinweis vorhanden: {what}")

for check in (check_package, check_native, check_graphics, check_listing, check_privacy, check_licenses):
    try:
        check()
    except Exception as e:  # eine fehlerhafte Prüfung soll die übrigen nicht verhindern
        fail(f"{check.__name__}: {e}")

width = max(len(s) for s, _ in results)
for status, msg in results:
    print(f"{status:<{width}}  {msg}")
errors = sum(1 for s, _ in results if s == "FEHLER")
print(f"\n{len(results)} Prüfungen: {errors} Fehler, {sum(1 for s, _ in results if s == 'WARNUNG')} Warnungen")
sys.exit(1 if errors else 0)
