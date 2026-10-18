"""
Erzeugt App-Icon, Startbildschirm-Motiv und Store-Grafiken von ParamWorker aus EINER Formenliste:

- app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml  (adaptives Vektor-Icon, 108 × 108)
- app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
- docs/store/icon-512.png        (Play-Store-Symbol, 512 × 512, randlos ohne Rundung/Schatten – Google maskiert selbst)
- docs/store/feature-graphic.png (Play-Store-Grafik, 1024 × 500)
- app/src/main/res/drawable/ic_splash.xml        (Startbildschirm: detailliertere Fassung des Motivs)
- app/src/main/res/drawable/splash_branding.xml  (Startbildschirm ab Android 12: Schriftzug PARAMWORKER, 200 × 80 dp)

Motiv (Stil „Blaupause“): Auf einer leuchtend türkisen Skizzenfläche steht ein blauer Block mit Tasche; ein orangefarbener
Extrusionspfeil steigt auf – Skizze → Körper, der Kern der App. Hintergrund: Marineblau mit grobem Raster.
Alle Maße im 108er-Raster; sichtbar ist auf jedem Gerät nur der Kreis r = 33 um die Mitte (wird geprüft).
Aufruf: python scripts/make-icon.py
"""
import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
STORE = os.path.join(ROOT, "docs", "store")

# ---- Farben ------------------------------------------------------------------------------------------
BG_FROM, BG_TO = "#0C1630", "#16305A"       # Hintergrund-Verlauf (oben links → unten rechts)
GRID = "#2A4A7A"
PLATE, PLATE_EDGE, POINT = "#1FB8D9", "#7FF2FF", "#C9FBFF"
TOP, LEFT, RIGHT, EDGE = "#4DB3EA", "#2A6CC4", "#1F4FA0", "#9FE6FF"
FLOOR, WALL_A, WALL_B = "#153B7C", "#2E7BD0", "#2560B4"
ARROW, ARROW_LIGHT = "#FF8A1F", "#FFC04D"
DIM = "#8FE3FF"
SPLASH_BG = "#0F1D3D"

# ---- Isometrische Projektion -------------------------------------------------------------------------
S, CX, Y0 = 16.5, 54.0, 59.0                  # Maßstab, Bildmitte x, Lage der Ebene z = 0
C30, S30 = math.cos(math.radians(30)), 0.5
def iso(x, y, z=0.0): return (CX + (x - y) * C30 * S, Y0 + (x + y) * S30 * S - z * S)

PH = 1.12                                       # halbe Kantenlänge der Skizzenfläche
BH, H = 0.58, 1.25                               # halbe Kantenlänge und Höhe des Blocks
Q, D = 0.3, 0.45                               # halbe Öffnung und Tiefe der Tasche

def plate(): return [iso(PH, -PH), iso(PH, PH), iso(-PH, PH), iso(-PH, -PH)]
def top(): return [iso(-BH, -BH, H), iso(BH, -BH, H), iso(BH, BH, H), iso(-BH, BH, H)]
def left(): return [iso(-BH, BH, H), iso(BH, BH, H), iso(BH, BH), iso(-BH, BH)]
def right(): return [iso(BH, -BH, H), iso(BH, BH, H), iso(BH, BH), iso(BH, -BH)]
def opening(): return [iso(-Q, -Q, H), iso(Q, -Q, H), iso(Q, Q, H), iso(-Q, Q, H)]
def wall_back(): return [iso(-Q, -Q, H), iso(Q, -Q, H), iso(Q, -Q, H - D), iso(-Q, -Q, H - D)]     # Innenseite zeigt zum Betrachter
def wall_left(): return [iso(-Q, -Q, H), iso(-Q, Q, H), iso(-Q, Q, H - D), iso(-Q, -Q, H - D)]
def silhouette(): return [iso(-BH, -BH, H), iso(BH, -BH, H), iso(BH, -BH), iso(BH, BH), iso(-BH, BH), iso(-BH, BH, H)]

# Extrusionspfeil: senkrecht (in der Isometrie bildschirmparallel) vor der rechten Blockseite
AX, AY = 0.95, -0.3
AB = iso(AX, AY, 0.12); ATIP = iso(AX, AY, 2.0); AHEAD = iso(AX, AY, 1.45)
SHAFT, HEAD = 1.9, 5.2                          # halbe Breite von Schaft und Spitze
def arrow():
    x = AB[0]
    return [(x - SHAFT, AB[1]), (x - SHAFT, AHEAD[1]), (x - HEAD, AHEAD[1]), (x, ATIP[1]),
            (x + HEAD, AHEAD[1]), (x + SHAFT, AHEAD[1]), (x + SHAFT, AB[1])]
def arrow_light():   # hellere linke Hälfte für Plastizität
    x = AB[0]
    return [(x - SHAFT, AB[1]), (x - SHAFT, AHEAD[1]), (x - HEAD, AHEAD[1]), (x, ATIP[1]), (x, AB[1])]

# Höhenmaß links am Block
DIM_X = iso(-BH, BH)[0] - 6.5
DIM_BOTTOM, DIM_TOP = iso(-BH, BH)[1], iso(-BH, BH, H)[1]

# Skizzenpunkte auf der Fläche
POINTS = [iso(-PH, PH * 0.15), iso(PH * 0.15, PH), iso(PH * 0.6, -PH)]

# ---- Formenliste ---------------------------------------------------------------------------------------
# ("fill", pts, farbe, deckkraft) · ("line", [a, b], farbe, breite) · ("dot", mitte, radius, farbe) · ("clip", pts, [formen])
# ("polyline", pts, farbe, breite, deckkraft) · ("glow", mitte, radius, farbe, deckkraft) – weicher, nach außen auslaufender Schein
def shapes(mono=False):
    W = "#FFFFFF"
    if mono:  # Themensymbol (nur Form zählt): Flächen-Umriss, Block mit Öffnung, Pfeil
        return [("outline", plate(), W, 1.6), ("hole", silhouette(), opening()), ("fill", arrow(), W, 1.0)]
    return [
        ("fill", plate(), PLATE, 0.55), ("outline", plate(), PLATE_EDGE, 1.0),
        *[("dot", p, 1.25, POINT) for p in POINTS],
        ("fill", left(), LEFT, 1.0), ("fill", right(), RIGHT, 1.0), ("fill", top(), TOP, 1.0),
        ("fill", opening(), FLOOR, 1.0),
        ("clip", opening(), [("fill", wall_back(), WALL_A, 1.0), ("fill", wall_left(), WALL_B, 1.0)]),
        ("outline", top(), EDGE, 0.7), ("line", [iso(BH, BH, H), iso(BH, BH)], EDGE, 0.7),
        ("line", [(DIM_X - 2, DIM_BOTTOM), (DIM_X + 2, DIM_BOTTOM)], DIM, 0.9),
        ("line", [(DIM_X - 2, DIM_TOP), (DIM_X + 2, DIM_TOP)], DIM, 0.9),
        ("line", [(DIM_X, DIM_BOTTOM - 0.4), (DIM_X, DIM_TOP + 0.4)], DIM, 0.9),
        ("fill", arrow(), ARROW, 1.0), ("fill", arrow_light(), ARROW_LIGHT, 0.55),
    ]

def dashed(a, b, n=7, ratio=0.55):
    """Gestrichelte Linie als einzelne Striche (VectorDrawable kennt keine Strichelung)."""
    out = []
    for i in range(n):
        t0, t1 = i / n, (i + ratio) / n
        out.append([(a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0), (a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1)])
    return out

def detail_shapes():
    """Startbildschirm: wie das Icon, dazu Lichtschein, Skizzenlinien, verdeckte Kanten und leicht durchscheinender Block."""
    hidden = [(iso(-BH, -BH), iso(-BH, -BH, H)), (iso(-BH, -BH), iso(BH, -BH)), (iso(-BH, -BH), iso(-BH, BH))]
    sketch = [(iso(-PH, 0), iso(PH, 0)), (iso(0, -PH), iso(0, PH))]
    return [
        ("glow", (54, 52), 35.0, "#2BD4FF", 0.42),
        ("fill", plate(), PLATE, 0.55), ("outline", plate(), PLATE_EDGE, 1.0),
        *[("line", seg, PLATE_EDGE, 0.45) for a, b in sketch for seg in dashed(a, b, 9)],
        *[("dot", p, 1.25, POINT) for p in POINTS],
        *[("line", seg, EDGE, 0.45) for a, b in hidden for seg in dashed(a, b, 5)],
        ("fill", left(), LEFT, 0.9), ("fill", right(), RIGHT, 0.9), ("fill", top(), TOP, 0.92),
        ("fill", opening(), FLOOR, 1.0),
        ("clip", opening(), [("fill", wall_back(), WALL_A, 1.0), ("fill", wall_left(), WALL_B, 1.0)]),
        ("outline", top(), EDGE, 0.7), ("outline", left(), EDGE, 0.5), ("outline", right(), EDGE, 0.5),
        ("outline", opening(), EDGE, 0.45),
        ("line", [(DIM_X - 2, DIM_BOTTOM), (DIM_X + 2, DIM_BOTTOM)], DIM, 0.8),
        ("line", [(DIM_X - 2, DIM_TOP), (DIM_X + 2, DIM_TOP)], DIM, 0.8),
        ("line", [(DIM_X, DIM_BOTTOM - 0.4), (DIM_X, DIM_TOP + 0.4)], DIM, 0.8),
        ("fill", [(DIM_X, DIM_TOP + 0.3), (DIM_X - 1.2, DIM_TOP + 2.6), (DIM_X + 1.2, DIM_TOP + 2.6)], DIM, 1.0),
        ("fill", [(DIM_X, DIM_BOTTOM - 0.3), (DIM_X - 1.2, DIM_BOTTOM - 2.6), (DIM_X + 1.2, DIM_BOTTOM - 2.6)], DIM, 1.0),
        ("fill", arrow(), ARROW, 1.0), ("fill", arrow_light(), ARROW_LIGHT, 0.55),
    ]

# ---- Schriftzug PARAMWORKER (eigene geometrische Linienschrift – keine fremde Schriftart nötig) -------------
CAP, LW = 10.0, 6.4                            # Versalhöhe, Buchstabenbreite (Einheiten)
def _arc(cx, cy, rx, ry, a0, a1, n=10):
    return [(cx + rx * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + ry * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]
GLYPHS = {
    "P": [[(0, CAP), (0, 0), (3.6, 0)] + _arc(3.6, 2.8, 2.8, 2.8, -90, 90) + [(0, 5.6)]],
    "A": [[(0, CAP), (LW / 2, 0), (LW, CAP)], [(1.25, 6.6), (LW - 1.25, 6.6)]],
    "R": [[(0, CAP), (0, 0), (3.6, 0)] + _arc(3.6, 2.8, 2.8, 2.8, -90, 90) + [(0, 5.6)], [(3.4, 5.6), (LW, CAP)]],
    "T": [[(0, 0), (LW, 0)], [(LW / 2, 0), (LW / 2, CAP)]],
    "W": [[(0, 0), (1.9, CAP), (4.2, 2.6), (6.5, CAP), (8.4, 0)]],
    "I": [[(0, 0), (0, CAP)]],
    "G": [[(LW, 1.6)] + _arc(3.2, 3.2, 3.2, 3.2, -30, -180, 8) + [(0, 6.8)] + _arc(3.2, 6.8, 3.2, 3.2, 180, 0, 8) + [(LW, 5.2), (3.6, 5.2)]],
    "H": [[(0, 0), (0, CAP)], [(LW, 0), (LW, CAP)], [(0, 5), (LW, 5)]],
    "M": [[(0, CAP), (0, 0), (3.8, 6.4), (7.6, 0), (7.6, CAP)]],
    "O": [_arc(3.3, 5, 3.3, 5, 0, 360, 32)],
    "K": [[(0, 0), (0, CAP)], [(LW, 0), (0, 6.2)], [(2.3, 4.5), (LW, CAP)]],
    "E": [[(5.8, 0), (0, 0), (0, CAP), (5.8, CAP)], [(0, 5), (4.8, 5)]],
}
WIDTH = {"W": 8.4, "I": 0.0, "M": 7.6, "E": 5.8}
TRACK = 2.9                                    # Laufweite zwischen Buchstaben
KERN = {("P", "A"): -0.9, ("R", "A"): -0.6, ("A", "M"): -0.3, ("M", "W"): -0.2, ("W", "O"): -0.5, ("O", "R"): -0.1, ("R", "K"): -0.2, ("K", "E"): -0.4, ("E", "R"): -0.1}   # Unterschneidung je Paar

def wordmark(text="PARAMWORKER"):
    """Linienzüge des Schriftzugs (Einheiten, oben links = 0,0) und seine Breite."""
    lines, x = [], 0.0
    for i, ch in enumerate(text):
        for pl in GLYPHS[ch]: lines.append([(x + px, py) for px, py in pl])
        x += WIDTH.get(ch, LW) + TRACK + (KERN.get((ch, text[i + 1]), 0.0) if i + 1 < len(text) else 0.0)
    return lines, x - TRACK

def wordmark_shapes(ox, oy, scale, color, width):
    lines, _ = wordmark()
    return [("polyline", [(ox + x * scale, oy + y * scale) for x, y in pl], color, width, 1.0) for pl in lines]

def all_points(items):
    for it in items:
        k = it[0]
        if k in ("fill", "outline", "line", "polyline"): yield from it[1]
        elif k == "glow": yield (it[1][0] + it[2], it[1][1])
        elif k == "dot": yield it[1]
        elif k == "hole": yield from it[1]
        elif k == "clip": yield from all_points(it[2])

def check_safe_zone():
    worst = max(math.hypot(x - 54, y - 54) for x, y in all_points(shapes()))
    assert worst <= 33.0, f"Motiv ragt aus dem sichtbaren Bereich: {worst:.1f} > 33"
    # Startbildschirm ohne Icon-Hintergrund: sichtbar ist ein Kreis von 192 der 288 dp, im 108er-Raster r = 36
    splash = max(math.hypot(x - 54, y - 54) for x, y in all_points(detail_shapes()))
    assert splash <= 36.0, f"Startbild-Motiv ragt aus dem sichtbaren Kreis: {splash:.1f} > 36"
    return worst

# ---- Vektor-XML ----------------------------------------------------------------------------------------
def f(v): return f"{v:.2f}".rstrip("0").rstrip(".")
def poly(pts): return "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts) + " Z"
def circle(c, r): return f"M{f(c[0] - r)},{f(c[1])} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0 Z"
def alpha(a): return "" if a >= 1 else f' android:fillAlpha="{f(a)}"'

def xml_items(items, indent="    "):
    out = []
    for it in items:
        k = it[0]
        if k == "fill":
            out.append(f'{indent}<path android:fillColor="{it[2]}"{alpha(it[3])} android:pathData="{poly(it[1])}" />')
        elif k == "outline":
            out.append(f'{indent}<path android:strokeColor="{it[2]}" android:strokeWidth="{f(it[3])}" android:strokeLineJoin="round" android:pathData="{poly(it[1])}" />')
        elif k == "line":
            (a, b) = it[1]
            out.append(f'{indent}<path android:strokeColor="{it[2]}" android:strokeWidth="{f(it[3])}" android:strokeLineCap="round" '
                       f'android:pathData="M{f(a[0])},{f(a[1])} L{f(b[0])},{f(b[1])}" />')
        elif k == "dot":
            out.append(f'{indent}<path android:fillColor="{it[3]}" android:pathData="{circle(it[1], it[2])}" />')
        elif k == "polyline":
            d = "M" + " L".join(f"{f(x)},{f(y)}" for x, y in it[1])
            out.append(f'{indent}<path android:strokeColor="{it[2]}" android:strokeWidth="{f(it[3])}" android:strokeLineCap="square" '
                       f'android:strokeLineJoin="miter" android:pathData="{d}" />')
        elif k == "glow":
            c, r = it[1], it[2]
            a = format(int(255 * it[4]), "02X")
            out.append(f'{indent}<path android:pathData="{circle(c, r)}">\n{indent}    <aapt:attr name="android:fillColor">\n'
                       f'{indent}        <gradient android:type="radial" android:centerX="{f(c[0])}" android:centerY="{f(c[1])}" android:gradientRadius="{f(r)}">\n'
                       f'{indent}            <item android:offset="0" android:color="#{a}{it[3][1:]}" />\n'
                       f'{indent}            <item android:offset="1" android:color="#00{it[3][1:]}" />\n'
                       f'{indent}        </gradient>\n{indent}    </aapt:attr>\n{indent}</path>')
        elif k == "hole":
            out.append(f'{indent}<path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{poly(it[1])} {poly(it[2])}" />')
        elif k == "clip":
            out.append(f'{indent}<group>\n{indent}    <clip-path android:pathData="{poly(it[1])}" />')
            out.extend(xml_items(it[2], indent + "    "))
            out.append(f"{indent}</group>")
    return out

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- erzeugt von scripts/make-icon.py – nicht von Hand ändern -->\n'
OPEN = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
        '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n')

def vector(items):
    ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if any(it[0] == "glow" for it in items) else ""
    return HEADER + OPEN.format(ns=ns) + "\n".join(xml_items(items)) + "\n</vector>\n"

def branding_xml():
    """Schriftzug für den Startbildschirm ab Android 12 (vorgegebene Größe 200 × 80 dp)."""
    _, w = wordmark()
    scale = 184 / w
    items = wordmark_shapes((200 - w * scale) / 2, (80 - CAP * scale) / 2, scale, "#CFF6FF", 1.45 * scale)
    return (HEADER + '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="200dp" android:height="80dp" android:viewportWidth="200" android:viewportHeight="80">\n'
            + "\n".join(xml_items(items)) + "\n</vector>\n")

def background_xml():
    lines = [f'    <path android:strokeColor="{GRID}" android:strokeAlpha="0.55" android:strokeWidth="0.5" android:pathData="'
             + " ".join([f"M{v},0 V108" for v in range(6, 108, 12)] + [f"M0,{v} H108" for v in range(6, 108, 12)]) + '" />']
    return (HEADER + OPEN.format(ns='\n    xmlns:aapt="http://schemas.android.com/aapt"')
            + '    <path android:pathData="M0,0 H108 V108 H0 Z">\n        <aapt:attr name="android:fillColor">\n'
            f'            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108"\n'
            f'                android:startColor="{BG_FROM}" android:endColor="{BG_TO}" />\n'
            '        </aapt:attr>\n    </path>\n' + "\n".join(lines) + "\n</vector>\n")

ADAPTIVE = (HEADER + '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')

# ---- Raster (Store) -------------------------------------------------------------------------------------
def rgb(h): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))

def background_png(w, h, scale, ox, oy):
    small = Image.new("RGB", (64, 64)); px = small.load(); a, b = rgb(BG_FROM), rgb(BG_TO)
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    img = small.resize((w, h), Image.BICUBIC).convert("RGBA")
    grid = Image.new("RGBA", img.size, (0, 0, 0, 0)); g = ImageDraw.Draw(grid)
    step, lw = 12 * scale, max(1, int(0.5 * scale))
    gx = ox + 6 * scale
    while gx > 0: gx -= step
    while gx < w: g.line([(gx, 0), (gx, h)], fill=rgb(GRID) + (140,), width=lw); gx += step
    gy = oy + 6 * scale
    while gy > 0: gy -= step
    while gy < h: g.line([(0, gy), (w, gy)], fill=rgb(GRID) + (140,), width=lw); gy += step
    return Image.alpha_composite(img, grid)

def render(img, items, scale, ox, oy, clip=None):
    """Zeichnet [items] (108er-Raster) mit Faktor [scale] an Versatz ([ox], [oy]) in das RGBA-Bild [img]."""
    T = lambda p: (ox + p[0] * scale, oy + p[1] * scale)
    for it in items:
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0)); d = ImageDraw.Draw(layer); k = it[0]
        if k == "fill":
            d.polygon([T(p) for p in it[1]], fill=rgb(it[2]) + (int(255 * it[3]),))
        elif k == "outline":
            pts = [T(p) for p in it[1]]
            d.line(pts + [pts[0]], fill=rgb(it[2]) + (255,), width=max(1, round(it[3] * scale)), joint="curve")
        elif k == "line":
            d.line([T(p) for p in it[1]], fill=rgb(it[2]) + (255,), width=max(1, round(it[3] * scale)))
        elif k == "dot":
            c = T(it[1]); r = it[2] * scale
            d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=rgb(it[3]) + (255,))
        elif k == "polyline":
            d.line([T(p) for p in it[1]], fill=rgb(it[2]) + (int(255 * it[4]),), width=max(1, round(it[3] * scale)), joint="curve")
        elif k == "glow":
            c, r = T(it[1]), it[2] * scale
            glow = Image.new("L", (256, 256), 0); gp = glow.load()
            for yy in range(256):
                for xx in range(256):
                    t = math.hypot(xx - 127.5, yy - 127.5) / 127.5          # wie der Radialverlauf: innen [deckkraft], außen 0
                    gp[xx, yy] = int(255 * it[4] * max(0.0, 1 - t))
            g = glow.resize((int(2 * r), int(2 * r)), Image.BICUBIC)
            layer.paste(Image.new("RGBA", g.size, rgb(it[3]) + (255,)), (int(c[0] - r), int(c[1] - r)), g)
        elif k == "clip":
            mask = Image.new("L", img.size, 0); ImageDraw.Draw(mask).polygon([T(p) for p in it[1]], fill=255)
            render(layer, it[2], scale, ox, oy, clip=mask)
        if clip is not None:
            layer.putalpha(ImageChops.multiply(layer.getchannel("A"), clip))
        img.alpha_composite(layer)

def store_icon():
    n = 512 * 3
    scale = n / 108 * 1.22                       # Store-Symbol: Motiv größer, keine Maske schneidet hier ab
    ox = oy = n / 2 - 54 * scale
    img = background_png(n, n, scale, ox, oy)
    render(img, shapes(), scale, ox, oy)
    return img.convert("RGB").resize((512, 512), Image.LANCZOS)

def feature_graphic():
    W, H, k = 1024, 500, 2
    scale = H * k / 108 * 1.25
    ox, oy = 250 * k - 54 * scale, H * k / 2 - 54 * scale
    img = background_png(W * k, H * k, scale, ox, oy)
    render(img, detail_shapes(), scale, ox, oy)
    d = ImageDraw.Draw(img)
    def font(names, size):
        for n in names:
            try: return ImageFont.truetype(n, size)
            except OSError: pass
        return ImageFont.load_default()
    x0, right = 470 * k, (W - 48) * k
    def fitted(names, size, text):
        f_ = font(names, size)
        while size > 10 and d.textlength(text, font=f_) > right - x0:
            size -= 2; f_ = font(names, size)
        return f_
    claim = "Parametric CAD for tablets & stylus"
    _, ww = wordmark()
    ws = (right - x0) / ww
    render(img, wordmark_shapes(0, 0, 1, "#FFFFFF", 1.5), ws, x0, 175 * k)
    d.text((x0, 300 * k), claim, font=fitted(["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"], 36 * k, claim), fill=rgb(DIM))
    return img.convert("RGB").resize((W, H), Image.LANCZOS)

def splash_preview():
    """Vorschau des Startbildschirms (Smartphone hoch, 1080 × 2400): Motiv in der Mitte, Schriftzug unten."""
    W, H = 1080, 2400
    img = Image.new("RGBA", (W, H), rgb(SPLASH_BG) + (255,))
    scale = 288 * 2.625 / 108 * 1.0               # 288 dp bei 420 dpi
    render(img, detail_shapes(), scale, W / 2 - 54 * scale, H / 2 - 54 * scale)
    _, ww = wordmark()
    bs = 184 / ww * 2.625
    render(img, wordmark_shapes(0, 0, 1, "#CFF6FF", 1.45), bs, (W - ww * bs) / 2, H - 80 * 2.625 - 120)
    return img.convert("RGB")

def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh: fh.write(text)

if __name__ == "__main__":
    worst = check_safe_zone()
    write(os.path.join(RES, "drawable", "ic_launcher_background.xml"), background_xml())
    write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), vector(shapes()))
    write(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), vector(shapes(mono=True)))
    write(os.path.join(RES, "drawable", "ic_splash.xml"), vector(detail_shapes()))
    write(os.path.join(RES, "drawable", "splash_branding.xml"), branding_xml())
    write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), ADAPTIVE)
    write(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher_round.xml"), ADAPTIVE)
    os.makedirs(STORE, exist_ok=True)
    store_icon().save(os.path.join(STORE, "icon-512.png"))
    feature_graphic().save(os.path.join(STORE, "feature-graphic.png"))
    splash_preview().save(os.path.join(STORE, "splash-preview.png"))
    print(f"ok (größter Abstand zur Mitte: {worst:.1f} von 33)")
