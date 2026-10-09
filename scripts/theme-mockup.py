"""Aufruf: python scripts/theme-mockup.py → docs/design/farbkonzept-*.png

Vorschau des Farbkonzepts „Blaupause“ (dunkel): Modellansicht und Skizze, Tablet quer (1280 × 800, 2× gerendert)."""
import math
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

K = 2
W, H = 1280 * K, 800 * K
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "docs", "design")

# ---- Palette ------------------------------------------------------------------------------------------
P = dict(
    bg="#0A1226", surface="#101B36", surface2="#16244A", outline="#2A3C66",
    text="#E8F1FF", muted="#93A8CC", accent="#34D1F0", accent_dim="#1C6E8C",
    select="#FF9A2E", error="#FF5D6C", dim="#FFD66B",
    vp_top="#0E1A36", vp_bottom="#1D3160",
    m_top="#C9D8EE", m_left="#8EA7CC", m_right="#647FAA", m_edge="#0A1226",
    grid="#15284A", grid_major="#1F3A66", axis_x="#FF6B7A", axis_y="#5FD39A",
    construction="#6F86B0",
)
def c(h, a=255): return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (a,)

SYMBOLS = set("✎⬆◜▦◸↖☑╱✂↶↷⤢✓⋮‹›✕◇▾⊕⋯—↔|=Ø")
def font(size, bold=False, symbol=False):
    names = ["seguisym.ttf"] if symbol else (["segoeuib.ttf"] if bold else ["segoeui.ttf"])
    for n in names + ["seguisym.ttf", "arial.ttf"]:
        try: return ImageFont.truetype(n, size * K)
        except OSError: pass
    return ImageFont.load_default()

def S(*v): return tuple(x * K for x in v)

def vertical_gradient(w, h, top, bottom):
    g = Image.new("RGBA", (1, 256)); px = g.load(); a, b = c(top), c(bottom)
    for y in range(256): px[0, y] = tuple(int(a[i] + (b[i] - a[i]) * y / 255) for i in range(4))
    return g.resize((w, h), Image.BICUBIC)

def glass(img, box, blur, radius=16):
    """Glas-Fläche: mit [blur] echter Unschärfe des Hintergrunds (Haze über der Skizze), sonst nachgebildete Optik."""
    x0, y0, x1, y1 = [v * K for v in box]
    r = radius * K
    mask = Image.new("L", img.size, 0); ImageDraw.Draw(mask).rounded_rectangle([x0, y0, x1, y1], r, fill=255)
    if blur:
        region = img.crop((x0, y0, x1, y1)).filter(ImageFilter.GaussianBlur(14 * K))
        img.paste(region, (x0, y0), mask.crop((x0, y0, x1, y1)))
        tint = c(P["surface2"], 120)
    else:
        tint = c(P["surface2"], 185)
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
    d.rounded_rectangle([x0, y0, x1, y1], r, fill=tint)
    # sanfter Lichtverlauf von oben und feiner heller Rand
    hl = vertical_gradient(x1 - x0, y1 - y0, "#FFFFFF", "#FFFFFF")
    alpha = vertical_gradient(x1 - x0, y1 - y0, "#000000", "#000000").getchannel("A")
    hl_alpha = Image.linear_gradient("L").resize((x1 - x0, y1 - y0)).point(lambda v: int((255 - v) * 0.07))
    hl.putalpha(Image.composite(hl_alpha, Image.new("L", hl_alpha.size, 0), mask.crop((x0, y0, x1, y1))))
    layer.alpha_composite(hl, (x0, y0))
    d.rounded_rectangle([x0, y0, x1, y1], r, outline=c("#9FDFFF", 70), width=K)
    img.alpha_composite(layer)

def text(img, xy, s, size=14, color="text", bold=False, anchor="la"):
    sym = any(ch in SYMBOLS for ch in s)
    ImageDraw.Draw(img).text(S(*xy), s, font=font(size, bold, sym), fill=c(P[color]), anchor=anchor)

def overlay(img, draw_fn):
    """Halbtransparentes zeichnen: auf eigener Ebene, dann einmischen (PIL mischt beim direkten Zeichnen nicht)."""
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0)); draw_fn(ImageDraw.Draw(layer)); img.alpha_composite(layer)

def chip(img, box, label, selected=False, size=13):
    x0, y0, x1, y1 = S(*box)
    overlay(img, lambda d: d.rounded_rectangle([x0, y0, x1, y1], 9 * K, fill=c(P["accent_dim"], 140) if selected else c("#FFFFFF", 14),
                        outline=c(P["accent"] if selected else P["outline"]), width=K))
    text(img, ((box[0] + box[2]) / 2, (box[1] + box[3]) / 2), label, size, "text", anchor="mm")

def top_bar(img, title, right):
    d = ImageDraw.Draw(img)
    d.rectangle(S(0, 0, 1280, 52), fill=c(P["surface"]))
    d.line(S(0, 52, 1280, 52), fill=c(P["outline"]), width=K)
    text(img, (20, 26), title, 17, bold=True, anchor="lm")
    x = 1260
    for s, col in reversed(right):
        w = ImageDraw.Draw(img).textlength(s, font=font(14)) / K
        x -= w
        text(img, (x, 26), s, 14, col, anchor="lm")
        x -= 26

def tool_rail(img, items, active, blur, y0=72):
    h = 14 + 64 * len(items)
    glass(img, (12, y0, 84, y0 + h), blur, 14)
    d = ImageDraw.Draw(img)
    for i, (glyph, label) in enumerate(items):
        y = y0 + 8 + i * 64
        if i == active:
            overlay(img, lambda o: o.rounded_rectangle(S(18, y, 78, y + 58), 10 * K, fill=c(P["accent_dim"], 150), outline=c(P["accent"]), width=K))
        text(img, (48, y + 22), glyph, 20, "accent" if i == active else "text", anchor="mm")
        text(img, (48, y + 46), label, 11, "text" if i == active else "muted", anchor="mm")

# ---- 3D-Ansicht -----------------------------------------------------------------------------------------
def iso(cx, cy, s, x, y, z=0.0):
    return (cx + (x - y) * math.cos(math.radians(30)) * s, cy + (x + y) * 0.5 * s - z * s)

def model_view():
    img = Image.new("RGBA", (W, H)); img.alpha_composite(vertical_gradient(W, H, P["vp_top"], P["vp_bottom"]))
    d = ImageDraw.Draw(img)
    # Bodenraster in der Isometrie, sehr dezent
    cx, cy, s = 640 * K, 470 * K, 150 * K
    for i in range(-6, 7):
        d.line([iso(cx, cy, s, i * 0.5, -3), iso(cx, cy, s, i * 0.5, 3)], fill=c(P["grid_major"], 110), width=K)
        d.line([iso(cx, cy, s, -3, i * 0.5), iso(cx, cy, s, 3, i * 0.5)], fill=c(P["grid_major"], 110), width=K)
    a, b, h = 1.3, 0.8, 0.45
    top = [iso(cx, cy, s, -a, -b, h), iso(cx, cy, s, a, -b, h), iso(cx, cy, s, a, b, h), iso(cx, cy, s, -a, b, h)]
    left = [iso(cx, cy, s, -a, b, h), iso(cx, cy, s, a, b, h), iso(cx, cy, s, a, b), iso(cx, cy, s, -a, b)]
    right = [iso(cx, cy, s, a, -b, h), iso(cx, cy, s, a, b, h), iso(cx, cy, s, a, b), iso(cx, cy, s, a, -b)]
    for poly, col in ((left, "m_left"), (right, "m_right"), (top, "m_top")):
        d.polygon(poly, fill=c(P[col]), outline=c(P["m_edge"]))
        d.line(poly + [poly[0]], fill=c(P["m_edge"]), width=2 * K)
    # Bohrungen
    for hx, hy in ((-0.85, -0.4), (0.85, 0.4), (-0.85, 0.4), (0.85, -0.4)):
        ex, ey = iso(cx, cy, s, hx, hy, h); rx, ry = 0.17 * s * 1.22, 0.17 * s * 0.7
        d.ellipse([ex - rx, ey - ry, ex + rx, ey + ry], fill=c("#2A3E66"), outline=c(P["m_edge"]), width=K)
    # gewählte Kante (orange)
    d.line([iso(cx, cy, s, -a, b, h), iso(cx, cy, s, a, b, h)], fill=c(P["select"]), width=5 * K)
    top_bar(img, "◇  Modell 2 ▾", [("400,00 cm³ · 6 Flächen", "muted"), ("Verlauf", "accent"), ("STL exportieren", "accent"), ("⋮", "text")])
    tool_rail(img, [("✎", "Skizze"), ("⬆", "Körper"), ("◜", "Kanten"), ("▦", "Muster")], 2, blur=False)
    glass(img, (100, 72, 330, 112), False, 20)
    text(img, (215, 92), "‹   ◜  Verrundung 1   ›", 14, anchor="mm")
    # Karte rechts
    x0, y0, x1 = 900, 72, 1266
    glass(img, (x0, y0, x1, 380), False)
    text(img, (x0 + 18, y0 + 26), "◜  Verrundung 1", 16, "accent", True, "lm")
    text(img, (x1 - 24, y0 + 26), "✕", 15, "muted", anchor="mm")
    ImageDraw.Draw(img).line(S(x0 + 1, y0 + 52, x1 - 1, y0 + 52), fill=c(P["outline"]), width=K)
    text(img, (x0 + 18, y0 + 76), "1 Kante gewählt – erneut tippen hebt die Wahl auf.", 12, "muted", anchor="lm")
    chip(img, (x0 + 18, y0 + 98, x0 + 168, y0 + 134), "◜ Verrundung", True)
    chip(img, (x0 + 178, y0 + 98, x0 + 300, y0 + 134), "◸ Fase")
    d = ImageDraw.Draw(img)
    overlay(img, lambda o: o.rounded_rectangle(S(x0 + 18, y0 + 152, x1 - 18, y0 + 204), 8 * K, outline=c(P["accent"]), width=K, fill=c(P["bg"], 120)))
    text(img, (x0 + 30, y0 + 160), "Radius (mm)", 11, "accent")
    text(img, (x0 + 30, y0 + 186), "2", 16, anchor="lm")
    d.rounded_rectangle(S(x0 + 18, y0 + 232, x0 + 160, y0 + 272), 20 * K, outline=c(P["outline"]), width=K)
    text(img, (x0 + 89, y0 + 252), "Abbrechen", 14, "accent", anchor="mm")
    d.rounded_rectangle(S(x0 + 176, y0 + 232, x1 - 18, y0 + 272), 20 * K, fill=c(P["select"]))
    text(img, ((x0 + 176 + x1 - 18) / 2, y0 + 252), "Anwenden", 14, "bg", True, anchor="mm")
    glass(img, (1160, 740, 1266, 780), False, 18)
    text(img, (1213, 760), "Ansicht ▾", 13, "accent", anchor="mm")
    # Achsenkreuz
    ox, oy = S(60, 760)
    d.line([ox, oy, ox + 34 * K, oy + 14 * K], fill=c(P["axis_x"]), width=2 * K)
    d.line([ox, oy, ox + 22 * K, oy - 16 * K], fill=c(P["axis_y"]), width=2 * K)
    d.line([ox, oy, ox, oy - 38 * K], fill=c("#6FA8FF"), width=2 * K)
    return img

# ---- Skizze ---------------------------------------------------------------------------------------------
def sketch_view():
    img = Image.new("RGBA", (W, H), c("#0C1730")); d = ImageDraw.Draw(img)
    step = 20 * K
    for i, x in enumerate(range(0, W, step)):
        d.line([x, 52 * K, x, H], fill=c(P["grid_major"] if i % 5 == 0 else P["grid"]), width=K)
    for i, y in enumerate(range(52 * K, H, step)):
        d.line([0, y, W, y], fill=c(P["grid_major"] if i % 5 == 0 else P["grid"]), width=K)
    ox, oy = 640 * K, 452 * K
    d.line([0, oy, W, oy], fill=c(P["axis_x"], 150), width=K); d.line([ox, 52 * K, ox, H], fill=c(P["axis_y"], 150), width=K)
    # vollständig bestimmtes Rechteck (weiß)
    r = S(420, 250, 860, 640)
    d.rectangle(r, outline=c("#F4F8FF"), width=3 * K)
    # Hilfslinie gestrichelt
    for t in range(0, 20, 2):
        a = (r[0] + (r[2] - r[0]) * t / 20, r[1] + (r[3] - r[1]) * t / 20)
        b = (r[0] + (r[2] - r[0]) * (t + 1) / 20, r[1] + (r[3] - r[1]) * (t + 1) / 20)
        d.line([a, b], fill=c(P["construction"]), width=2 * K)
    # noch beweglicher Kreis (türkis)
    cx, cy, rr = 640 * K, 452 * K, 70 * K
    d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], outline=c(P["accent"]), width=3 * K)
    # gewählte Linie (orange) – oben
    d.line([r[0], r[1], r[2], r[1]], fill=c(P["select"]), width=4 * K)
    # Maße (hellgelb, dunkle Plakette)
    def dim(x, y, s):
        w = ImageDraw.Draw(img).textlength(s, font=font(13)) / K + 14
        overlay(img, lambda o: o.rounded_rectangle(S(x - w / 2, y - 12, x + w / 2, y + 12), 6 * K, fill=c("#0A1226", 220), outline=c(P["dim"], 160), width=K))
        text(img, (x, y), s, 13, "dim", anchor="mm")
    d.line([r[0], r[3] + 26 * K, r[2], r[3] + 26 * K], fill=c(P["dim"], 180), width=K)
    dim(640, 666, "120")
    d.line([r[2] + 44 * K, r[1], r[2] + 44 * K, r[3]], fill=c(P["dim"], 180), width=K)
    dim(904, 445, "80")
    dim(700, 395, "Ø 40")
    # Constraint-Symbole
    for x, y, s in ((640, 238, "—"), (408, 445, "|"), (872, 445, "|"), (640, 652, "—")):
        d.rounded_rectangle(S(x - 9, y - 9, x + 9, y + 9), 5 * K, fill=c(P["surface2"]), outline=c(P["outline"]), width=K)
        text(img, (x, y), s, 11, "muted", anchor="mm")
    # Punkte
    for px, py in ((r[0], r[1]), (r[2], r[1]), (r[2], r[3]), (r[0], r[3])):
        d.ellipse([px - 5 * K, py - 5 * K, px + 5 * K, py + 5 * K], fill=c("#F4F8FF"))
    top_bar(img, "✎  Skizze 1   XY", [("↶", "text"), ("↷", "text"), ("▦", "accent"), ("⤢", "text"), ("✓ Fertig", "select")])
    # Glas mit echter Unschärfe über der Zeichenfläche
    tool_rail(img, [("↖", "Auswahl"), ("☑", "Mehrfach"), ("╱", "Zeichnen"), ("✂", "Bearbeiten"), ("▦", "Muster")], 0, blur=True)
    glass(img, (560, 66, 720, 98), True, 16)
    text(img, (640, 82), "Vollständig bestimmt", 12, "text", anchor="mm")
    glass(img, (400, 182, 880, 216), True, 14)
    labels = [("— Horizontal", False), ("↔ Länge  120", True), ("= Gleich", False), ("⋯ Konstruktion", False), ("⊕ Ausrichten ▾", False)]
    x = 410
    for s, sel in labels:
        w = ImageDraw.Draw(img).textlength(s, font=font(12)) / K + 18
        chip(img, (x, 188, x + w, 210), s, sel, 12); x += w + 6
    # Legende der Zustandsfarben
    glass(img, (1000, 690, 1266, 784), True, 14)
    for i, (col, s) in enumerate((("text", "vollständig bestimmt"), ("accent", "noch beweglich"), ("error", "Widerspruch"), ("select", "Auswahl"))):
        y = 704 + i * 20
        ImageDraw.Draw(img).line(S(1016, y, 1046, y), fill=c(P[col] if col != "text" else "#F4F8FF"), width=3 * K)
        text(img, (1056, y), s, 12, "muted", anchor="lm")
    return img

if __name__ == "__main__":
    import os
    os.makedirs(OUT, exist_ok=True)
    a, b = model_view(), sketch_view()
    combo = Image.new("RGBA", (W, H * 2 + 40 * K), c("#05080F"))
    combo.alpha_composite(a, (0, 0)); combo.alpha_composite(b, (0, H + 40 * K))
    combo.convert("RGB").resize((1280, 1640), Image.LANCZOS).save(os.path.join(OUT, "farbkonzept-blaupause.png"))
    a.convert("RGB").resize((1280, 800), Image.LANCZOS).save(os.path.join(OUT, "farbkonzept-modell.png"))
    b.convert("RGB").resize((1280, 800), Image.LANCZOS).save(os.path.join(OUT, "farbkonzept-skizze.png"))
    print("ok")
