"""
Prüft das Release-Paket und die Store-Unterlagen gegen die Vorgaben von Google Play (Stand November 2026).

Aufruf (nach ./gradlew :app:bundlePlayRelease):  python scripts/play-check.py [--allow-debug-signed]
Ausgabe: je Prüfung OK / WARNUNG / FEHLER, Exit-Code 1 bei mindestens einem FEHLER.

Geprüft wird, was sich am Paket und an den Dateien maschinell feststellen lässt. Formulare in der Play Console (Datensicherheit,
Inhaltsbewertung, Zielgruppe, Händlerstatus) und Richtlinien zum Verhalten der App lassen sich so nicht prüfen – siehe
docs/PRUEFUNG_LIZENZEN_PLAYSTORE.md und docs/store/play-console.md.
"""
import argparse
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
APK_DIR = os.path.join(ROOT, "app", "build", "outputs", "apk", "play", "release")
AAB = os.path.join(ROOT, "app", "build", "outputs", "bundle", "playRelease", "app-play-release.aab")
STORE = os.path.join(ROOT, "docs", "store")
FASTLANE = os.path.join(ROOT, "fastlane", "metadata", "android")

PACKAGE = "io.github.kevbo8327.axiswright"
MIN_TARGET_SDK = 35        # Pflicht für neue Apps und Updates seit 31.08.2025
RECOMMENDED_TARGET_SDK = 36  # Pflicht ab 31.08.2026 (Android 16)
PAGE = 16384               # 16-KB-Seiten: Pflicht für native Bibliotheken bei targetSdk ≥ 35 seit 01.11.2025
MAX_DOWNLOAD = 200 * 1024 * 1024  # Grenze für die komprimierte Downloadgröße je Gerät
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024  # Google-Play-Grenze: 8 MB je Screenshot

ALLOW_DEBUG_SIGNED = False
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

def jdk_tool(name):
    """Findet ein JDK-Werkzeug (java, keytool) über JAVA_HOME oder Android Studio JBR."""
    for base in (os.environ.get("JAVA_HOME", ""), r"C:\Program Files\Android\Android Studio\jbr"):
        if not base: continue
        for ext in ("", ".exe"):
            p = os.path.join(base, "bin", name + ext)
            if os.path.isfile(p): return p
    return name

def run(args):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

def find_apks():
    """Sucht gebaute playRelease-APKs (Split-APKs AxisWright-*-play-*.apk oder app-play-release.apk)."""
    if not os.path.isdir(APK_DIR): return []
    apks = sorted(glob.glob(os.path.join(APK_DIR, "AxisWright-*-play-*.apk")))
    legacy = os.path.join(APK_DIR, "app-play-release.apk")
    if os.path.isfile(legacy) and legacy not in apks: apks.append(legacy)
    return apks

# ---- Protobuf-Reader für base/manifest/AndroidManifest.xml im AAB -----------------------------------------
def _pb_fields(data):
    """Zerlegt einen Protobuf-Byte-Buffer in (field_number, wire_type, value)-Tupel."""
    i, n = 0, len(data)
    while i < n:
        tag = 0; shift = 0
        while True:
            b = data[i]; i += 1
            tag |= (b & 0x7F) << shift
            if not (b & 0x80): break
            shift += 7
        fn, wt = tag >> 3, tag & 7
        if wt == 0:
            v = 0; shift = 0
            while True:
                b = data[i]; i += 1
                v |= (b & 0x7F) << shift
                if not (b & 0x80): break
                shift += 7
            yield fn, wt, v
        elif wt == 2:
            ln = 0; shift = 0
            while True:
                b = data[i]; i += 1
                ln |= (b & 0x7F) << shift
                if not (b & 0x80): break
                shift += 7
            yield fn, wt, data[i:i + ln]; i += ln
        elif wt == 1:
            yield fn, wt, data[i:i + 8]; i += 8
        elif wt == 5:
            yield fn, wt, data[i:i + 4]; i += 4
        else:
            break

def _parse_xml_elem(buf):
    name, attrs, children = "", {}, []
    for fn, wt, val in _pb_fields(buf):
        if fn == 3 and wt == 2:
            name = val.decode("utf-8", "replace")
        elif fn == 4 and wt == 2:
            aname, aval, prim = "", "", None
            for afn, awt, aval_raw in _pb_fields(val):
                if afn == 2 and awt == 2: aname = aval_raw.decode("utf-8", "replace")
                elif afn == 3 and awt == 2: aval = aval_raw.decode("utf-8", "replace")
                elif afn == 5 and awt == 2:
                    for pfn, pwt, pval in _pb_fields(aval_raw):
                        if pfn == 1 and pwt == 2:
                            for sfn, swt, sval in _pb_fields(pval):
                                if sfn == 1 and swt == 0: prim = sval
            if aname:
                attrs[aname] = aval if aval else (str(prim) if prim is not None else "")
        elif fn == 5 and wt == 2:
            for cfn, cwt, cval in _pb_fields(val):
                if cfn == 1 and cwt == 2:
                    children.append(_parse_xml_elem(cval))
    return name, attrs, children

def parse_aab_manifest(aab_path):
    with zipfile.ZipFile(aab_path) as z:
        data = z.read("base/manifest/AndroidManifest.xml")
    for fn, wt, val in _pb_fields(data):
        if fn == 1 and wt == 2:
            return _parse_xml_elem(val)
    raise ValueError("base/manifest/AndroidManifest.xml konnte nicht gelesen werden")

def _walk_elems(elem):
    yield elem
    for c in elem[2]:
        yield from _walk_elems(c)

# ---- 1. Paket ------------------------------------------------------------------------------------------
def check_package():
    apks = find_apks()
    has_aab = os.path.isfile(AAB)
    if not has_aab and not apks:
        fail("Release-Paket fehlt: erst ./gradlew :app:bundlePlayRelease"); return
    if has_aab:
        ok(f"AAB {os.path.getsize(AAB) / 2**20:.1f} MB")
    else:
        warn("AAB fehlt: ./gradlew :app:bundlePlayRelease ausführen")

    if apks:
        aapt2 = tool("aapt2")
        badging = run([aapt2, "dump", "badging", apks[0]])
        pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", badging)
        if pkg and pkg.group(1) == PACKAGE: ok(f"Paketname {PACKAGE}, versionCode {pkg.group(2)}, versionName {pkg.group(3)}")
        else: fail(f"Paketname stimmt nicht: {pkg.group(1) if pkg else '?'}")
        target = int(re.search(r"targetSdkVersion:'(\d+)'", badging).group(1))
        if target < MIN_TARGET_SDK: fail(f"targetSdk {target} < {MIN_TARGET_SDK}")
        elif target < RECOMMENDED_TARGET_SDK: warn(f"targetSdk {target}: ab 31.08.2026 ist {RECOMMENDED_TARGET_SDK} Pflicht")
        else: ok(f"targetSdk {target}")
        perms = re.findall(r"uses-permission: name='([^']+)'", badging)
        internal = [p for p in perms if p.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")]
        other = [p for p in perms if p not in internal]
        if other: warn("Berechtigungen (in der Datensicherheit begründen): " + ", ".join(other))
        else: ok("keine Berechtigungen (passt zu „keine Daten erhoben“, kein Internet)")
        abis = set()
        for apk in apks:
            b = run([aapt2, "dump", "badging", apk])
            m = re.search(r"native-code: (.*)", b)
            if m: abis.update(m.group(1).replace("'", "").split())
        if "arm64-v8a" in abis: ok("64-Bit-Pflicht erfüllt: " + ", ".join(sorted(abis)))
        else: fail("arm64-v8a fehlt (64-Bit-Pflicht)")
        if "application-debuggable" in badging: fail("Release ist debuggable")
        else: ok("nicht debuggable")
        tree = run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apks[0]])
        blocks = re.split(r"\n\s+E: (?=activity|receiver|service|provider)", tree)[1:]
        open_ = [b.split("\n")[0] for b in blocks if "exported(0x01010010)=true" in b and ":permission(" not in b.split("E: intent-filter")[0]]
        if len(open_) <= 1: ok(f"ungeschützt exportiert: {len(open_)} Komponente (Startaktivität)")
        else: warn(f"{len(open_)} ungeschützt exportierte Komponenten – prüfen")
        for apk in apks:
            size = os.path.getsize(apk)
            (ok if size < MAX_DOWNLOAD else fail)(f"APK {os.path.basename(apk)} {size / 2**20:.1f} MB (Grenze 200 MB)")
        jar = os.path.join(os.path.dirname(tool("aapt2")), "lib", "apksigner.jar")
        certs = run([jdk_tool("java"), "-jar", jar, "verify", "--print-certs", apks[0]])
        _check_certs(certs)
    else:
        manifest = parse_aab_manifest(AAB)
        _, root_attrs, _ = manifest
        pkg_name = root_attrs.get("package", "?")
        vc = root_attrs.get("versionCode", "?")
        vn = root_attrs.get("versionName", "?")
        if pkg_name == PACKAGE: ok(f"Paketname {PACKAGE}, versionCode {vc}, versionName {vn}")
        else: fail(f"Paketname stimmt nicht: {pkg_name}")

        uses_sdk = next((e for e in _walk_elems(manifest) if e[0] == "uses-sdk"), None)
        target = int(uses_sdk[1].get("targetSdkVersion", "0")) if uses_sdk else 0
        if target < MIN_TARGET_SDK: fail(f"targetSdk {target} < {MIN_TARGET_SDK}")
        elif target < RECOMMENDED_TARGET_SDK: warn(f"targetSdk {target}: ab 31.08.2026 ist {RECOMMENDED_TARGET_SDK} Pflicht")
        else: ok(f"targetSdk {target}")

        perms = [e[1].get("name", "") for e in _walk_elems(manifest) if e[0] == "uses-permission"]
        internal = [p for p in perms if p.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")]
        other = [p for p in perms if p and p not in internal]
        if other: warn("Berechtigungen (in der Datensicherheit begründen): " + ", ".join(other))
        else: ok("keine Berechtigungen (passt zu „keine Daten erhoben“, kein Internet)")

        with zipfile.ZipFile(AAB) as z:
            abis = sorted({p.split("/")[2] for p in z.namelist() if p.startswith("base/lib/") and p.endswith(".so")})
        if "arm64-v8a" in abis: ok("64-Bit-Pflicht erfüllt: " + ", ".join(abis))
        else: fail("arm64-v8a fehlt (64-Bit-Pflicht)")

        app_elem = next((e for e in _walk_elems(manifest) if e[0] == "application"), None)
        if app_elem and app_elem[1].get("debuggable") in ("true", "-1", "1"): fail("Release ist debuggable")
        else: ok("nicht debuggable")

        open_ = [
            f"{e[0]}:{e[1].get('name', '?')}"
            for e in _walk_elems(manifest)
            if e[0] in ("activity", "receiver", "service", "provider")
            and e[1].get("exported") in ("true", "-1", "1")
            and not e[1].get("permission")
        ]
        if len(open_) <= 1: ok(f"ungeschützt exportiert: {len(open_)} Komponente (Startaktivität)")
        else: warn(f"{len(open_)} ungeschützt exportierte Komponenten – prüfen: " + ", ".join(open_))

        certs = run([jdk_tool("keytool"), "-printcert", "-jarfile", AAB])
        _check_certs(certs)

def _check_certs(certs):
    if "CN=Android Debug" in certs:
        msg = "signiert mit dem Debug-Schlüssel – vor Play-Upload eigenen Upload-Schlüssel anlegen (keystore.properties, siehe README)"
        (warn if ALLOW_DEBUG_SIGNED else fail)(msg)
    elif "Signer #1" in certs or "Eigentümer:" in certs or "Owner:" in certs:
        ok("signiert mit eigenem Upload-Schlüssel")
    else:
        fail("Signatur nicht prüfbar")

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
    apks = find_apks()
    targets = apks if apks else ([AAB] if os.path.isfile(AAB) else [])
    if not targets: return
    bad, unaligned, n = [], [], 0
    for pkg_path in targets:
        with zipfile.ZipFile(pkg_path) as z, open(pkg_path, "rb") as raw:
            for info in z.infolist():
                if not info.filename.endswith(".so"): continue
                n += 1
                if any(a < PAGE for a in load_aligns(z.read(info))): bad.append(info.filename)
                if pkg_path.endswith(".apk") and info.compress_type == zipfile.ZIP_STORED:
                    raw.seek(info.header_offset)
                    hdr = raw.read(30)
                    name_len, extra_len = struct.unpack_from("<HH", hdr, 26)
                    if (info.header_offset + 30 + name_len + extra_len) % PAGE: unaligned.append(info.filename)
    if bad: fail(f"{len(bad)} Bibliotheken nicht für 16-KB-Seiten gebaut: " + ", ".join(sorted(bad)[:6]))
    else: ok(f"alle {n} nativen Bibliotheken mit 16-KB-Segmenten (LOAD-Ausrichtung ≥ 16384)")
    if apks:
        if unaligned: fail(f"{len(unaligned)} unkomprimierte Bibliotheken im APK nicht 16-KB-ausgerichtet")
        else: ok("Lage der Bibliotheken im APK passt zu 16-KB-Seiten")

# ---- 3. Store-Grafiken ---------------------------------------------------------------------------------
def _check_shot_set(label, shots):
    problems = []
    for s in shots:
        img = Image.open(s); w, h = img.size
        if img.mode not in ("RGB", "L"): problems.append(f"{os.path.basename(s)} mit Alphakanal")
        if os.path.getsize(s) > MAX_SCREENSHOT_BYTES: problems.append(f"{os.path.basename(s)} > 8 MB")
        if not (320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)):
            problems.append(f"{os.path.basename(s)} {w}×{h}")
    if not 2 <= len(shots) <= 8: fail(f"Screenshots {label}: {len(shots)} (2 bis 8 verlangt)")
    elif problems: fail(f"Screenshots {label}: " + ", ".join(problems))
    else: ok(f"Screenshots {label}: {len(shots)}, Größe, Format (RGB) und Seitenverhältnis passen")

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
        for lang in ("de", "en"):
            shots = sorted(glob.glob(os.path.join(STORE, "screenshots", f"{kind}-{lang}-*.png")))
            _check_shot_set(f"{kind} ({lang})", shots)
    for locale, folder_map in (("de-DE", "phoneScreenshots"), ("de-DE", "sevenInchScreenshots"), ("de-DE", "tenInchScreenshots"),
                               ("en-US", "phoneScreenshots"), ("en-US", "sevenInchScreenshots"), ("en-US", "tenInchScreenshots")):
        shots = sorted(glob.glob(os.path.join(FASTLANE, locale, "images", folder_map, "*.png")))
        _check_shot_set(f"fastlane/{locale}/{folder_map}", shots)

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
        if "Partwright" in part or "ParamWorker" in part: fail(f"{lang}: alter Name („Partwright“ / „ParamWorker“) im Store-Text")
        hits = [w for w in ("beste", "best ", "Nr. 1", "#1", "kostenlos", "free download", "Top ") if w.lower() in (title + short).lower()]
        if hits: warn(f"{lang}: Titel/Kurzbeschreibung enthalten {hits} (Metadaten-Richtlinie)")
    for locale in ("de-DE", "en-US"):
        for cl in sorted(glob.glob(os.path.join(FASTLANE, locale, "changelogs", "*.txt"))):
            cl_text = open(cl, encoding="utf-8").read().strip()
            (ok if 0 < len(cl_text) <= 500 else fail)(f"fastlane/{locale}/changelogs/{os.path.basename(cl)}: {len(cl_text)}/500 Zeichen")
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
                "Unicode, Inc. in OCCT": "Unicode, Inc.", "LLVM-Ausnahme (libc++_shared.so)": "LLVM Exceptions",
                "GPL-3.0 (AxisWright)": "GNU GENERAL PUBLIC LICENSE"}
    texts = "".join(open(f, encoding="utf-8").read() for f in glob.glob(os.path.join(ROOT, "app", "src", "main", "assets", "licenses", "*.txt")))
    for what, marker in required.items():
        (ok if marker in texts else fail)(f"Hinweis vorhanden: {what}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Prüft Play-Release-Paket und Store-Metadaten.")
    parser.add_argument("--allow-debug-signed", action="store_true", help="Debug-Signatur nur als Warnung statt Fehler melden (für lokale Testläufe ohne Upload-Key)")
    ALLOW_DEBUG_SIGNED = parser.parse_args().allow_debug_signed

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
