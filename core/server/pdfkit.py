"""A small PDF writer (standard library only) for the app's documents.

Uses the built-in Helvetica fonts (WinAnsi encoding), so no font files are needed.
Characters outside Windows-1252 (e.g. the rupee sign or emoji) are replaced; callers
write currency as "INR". Coordinates are PDF points from the bottom-left of an A4 page.
"""
import zlib

A4 = (595.28, 841.89)

# Glyph widths (per 1000 em) for ASCII 32..126, from the Adobe Helvetica AFM files.
_HELV = [278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
         556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
         1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
         667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
         333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
         556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584]
_HELV_B = [278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
           556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
           975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
           667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
           333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
           611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584]
_EXTRA = {"•": 350, "–": 556, "—": 1000, "·": 278, "’": 222, "×": 584}
FONTS = {"R": ("F1", "Helvetica", _HELV), "B": ("F2", "Helvetica-Bold", _HELV_B)}


def clean(text):
    """Map text to what the built-in fonts can show."""
    s = str(text if text is not None else "")
    s = s.replace("₹", "INR ").replace("→", "->").replace("←", "<-").replace("‘", "'").replace("’", "'")
    s = s.replace("“", '"').replace("”", '"').replace("\t", " ")
    out = []
    for ch in s:
        try:
            ch.encode("cp1252")
            out.append(ch)
        except UnicodeEncodeError:
            out.append("?")
    return "".join(out)


def text_width(text, size, style="R"):
    widths = FONTS[style][2]
    w = 0
    for ch in clean(text):
        o = ord(ch)
        w += widths[o - 32] if 32 <= o <= 126 else _EXTRA.get(ch, 556)
    return w * size / 1000.0


def wrap(text, size, width, style="R"):
    """Split text into lines no wider than width (breaks long words when needed)."""
    lines = []
    for para in clean(text).split("\n"):
        words = para.split(" ")
        cur = ""
        for word in words:
            cand = word if not cur else cur + " " + word
            if text_width(cand, size, style) <= width:
                cur = cand
                continue
            if cur:
                lines.append(cur)
            while text_width(word, size, style) > width and len(word) > 1:
                cut = len(word)
                while cut > 1 and text_width(word[:cut], size, style) > width:
                    cut -= 1
                lines.append(word[:cut])
                word = word[cut:]
            cur = word
        lines.append(cur)
    return lines


def _esc(s):
    return clean(s).encode("cp1252").replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


class Page:
    def __init__(self, doc):
        self.doc = doc
        self.ops = []

    def _c(self, color, stroke=False):
        r, g, b = hexrgb(color)
        self.ops.append(("%.3f %.3f %.3f %s" % (r, g, b, "RG" if stroke else "rg")).encode())

    # -------- shapes
    def rect(self, x, y, w, h, fill=None, stroke=None, lw=0.8, r=0):
        self.ops.append(b"q")
        if fill:
            self._c(fill)
        if stroke:
            self._c(stroke, True)
            self.ops.append(("%.2f w" % lw).encode())
        self._path_rect(x, y, w, h, r)
        self.ops.append(b"B" if fill and stroke else b"f" if fill else b"S")
        self.ops.append(b"Q")

    def _path_rect(self, x, y, w, h, r):
        if not r:
            self.ops.append(("%.2f %.2f %.2f %.2f re" % (x, y, w, h)).encode())
            return
        k = 0.5523 * r
        p = [("%.2f %.2f m" % (x + r, y)), ("%.2f %.2f l" % (x + w - r, y)),
             ("%.2f %.2f %.2f %.2f %.2f %.2f c" % (x + w - r + k, y, x + w, y + r - k, x + w, y + r)),
             ("%.2f %.2f l" % (x + w, y + h - r)),
             ("%.2f %.2f %.2f %.2f %.2f %.2f c" % (x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)),
             ("%.2f %.2f l" % (x + r, y + h)),
             ("%.2f %.2f %.2f %.2f %.2f %.2f c" % (x + r - k, y + h, x, y + h - r + k, x, y + h - r)),
             ("%.2f %.2f l" % (x, y + r)),
             ("%.2f %.2f %.2f %.2f %.2f %.2f c h" % (x, y + r - k, x + r - k, y, x + r, y))]
        self.ops.append(" ".join(p).encode())

    def gradient_rect(self, x, y, w, h, c1, c2, r=0, vertical=False):
        """Linear gradient fill (left->right, or bottom->top) clipped to a (rounded) rectangle."""
        name = self.doc.shading(c1, c2, (x, y, x, y + h) if vertical else (x, y, x + w, y))
        self.ops.append(b"q")
        self._path_rect(x, y, w, h, r)
        self.ops.append(("W n /%s sh Q" % name).encode())

    def line(self, x1, y1, x2, y2, color="#E2E8F2", lw=0.8, dash=None):
        self.ops.append(b"q")
        self._c(color, True)
        self.ops.append(("%.2f w 1 J" % lw).encode())
        if dash:
            self.ops.append(("[%s] 0 d" % " ".join("%.1f" % d for d in dash)).encode())
        self.ops.append(("%.2f %.2f m %.2f %.2f l S Q" % (x1, y1, x2, y2)).encode())

    def circle(self, cx, cy, rad, fill=None, stroke=None, lw=1):
        k = 0.5523 * rad
        self.ops.append(b"q")
        if fill:
            self._c(fill)
        if stroke:
            self._c(stroke, True)
            self.ops.append(("%.2f w" % lw).encode())
        self.ops.append(("%.2f %.2f m %.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c "
                         "%.2f %.2f %.2f %.2f %.2f %.2f c %.2f %.2f %.2f %.2f %.2f %.2f c h" % (
                             cx + rad, cy, cx + rad, cy + k, cx + k, cy + rad, cx, cy + rad,
                             cx - k, cy + rad, cx - rad, cy + k, cx - rad, cy,
                             cx - rad, cy - k, cx - k, cy - rad, cx, cy - rad,
                             cx + k, cy - rad, cx + rad, cy - k, cx + rad, cy)).encode())
        self.ops.append(b"B" if fill and stroke else b"f" if fill else b"S")
        self.ops.append(b"Q")

    def plane(self, x, y, size, color="#FFFFFF", angle=0):
        """The plane mark (same outline as the app icon), centred on x, y."""
        import math
        pts = [("m", 14, 38), ("l", 44, 22), ("c", 47, 20.4, 50, 22.4, 48.6, 25.2), ("l", 46, 30), ("l", 28, 40),
               ("l", 22, 50), ("l", 18, 48), ("l", 20, 40), ("l", 14, 38)]
        s = size / 40.0
        ca, sa = math.cos(math.radians(angle)), math.sin(math.radians(angle))

        def tr(px, py):
            dx, dy = (px - 31) * s, (36 - py) * s  # flip SVG y axis, centre on the mark
            return x + dx * ca - dy * sa, y + dx * sa + dy * ca
        parts = []
        for p in pts:
            if p[0] == "c":
                a, b, c = tr(p[1], p[2]), tr(p[3], p[4]), tr(p[5], p[6])
                parts.append("%.2f %.2f %.2f %.2f %.2f %.2f c" % (a + b + c))
            else:
                parts.append("%.2f %.2f %s" % (tr(p[1], p[2]) + (p[0],)))
        self.ops.append(b"q")
        self._c(color)
        self.ops.append((" ".join(parts) + " h f Q").encode())

    # -------- text
    def text(self, x, y, s, size=10, style="R", color="#0D1833", align="left", spacing=0):
        s = clean(s)
        if not s:
            return 0
        w = text_width(s, size, style) + spacing * max(len(s) - 1, 0)
        if align == "right":
            x -= w
        elif align == "center":
            x -= w / 2
        self.ops.append(b"BT")
        self._c(color)
        self.ops.append(("%.2f Tc" % spacing).encode())  # character spacing is graphics state: always set it
        self.ops.append(("/%s %.2f Tf %.2f %.2f Td (" % (FONTS[style][0], size, x, y)).encode() + _esc(s) + b") Tj ET")
        return w

    def para(self, x, y, s, size, width, style="R", color="#0D1833", leading=None):
        """Wrapped text starting with its first baseline at y. Returns the y below the last line."""
        leading = leading or size * 1.35
        for ln in wrap(s, size, width, style):
            self.text(x, y, ln, size, style, color)
            y -= leading
        return y

    def link(self, x, y, w, h, uri):
        self.doc.links.append((self, (x, y, x + w, y + h), uri))


class Document:
    def __init__(self, title="", author=""):
        self.pages = []
        self.shadings = []
        self.links = []
        self.title = title
        self.author = author

    def new_page(self):
        p = Page(self)
        self.pages.append(p)
        return p

    def shading(self, c1, c2, coords):
        self.shadings.append((hexrgb(c1), hexrgb(c2), coords))
        return "Sh%d" % len(self.shadings)

    def bytes(self):
        objs = []

        def add(body):
            objs.append(body)
            return len(objs)
        catalog = add(None)
        pages = add(None)
        f1 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        f2 = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        sh_refs = []
        for c1, c2, co in self.shadings:
            sh_refs.append(add(("<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [%.2f %.2f %.2f %.2f] /Extend [true true] "
                                "/Function << /FunctionType 2 /Domain [0 1] /C0 [%.3f %.3f %.3f] /C1 [%.3f %.3f %.3f] /N 1 >> >>"
                                % (co + c1 + c2)).encode()))
        shading_res = " ".join("/Sh%d %d 0 R" % (i + 1, r) for i, r in enumerate(sh_refs))
        page_ids = []
        for pg in self.pages:
            data = zlib.compress(b"\n".join(pg.ops))
            content = add(b"<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(data) + data + b"\nendstream")
            annots = []
            for lp, rect, uri in self.links:
                if lp is pg:
                    annots.append(add(("<< /Type /Annot /Subtype /Link /Rect [%.2f %.2f %.2f %.2f] /Border [0 0 0] /A << /S /URI /URI (" % rect).encode()
                                      + _esc(uri) + b") >> >>"))
            page_ids.append(add(("<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %.2f %.2f] /Contents %d 0 R "
                                 "/Resources << /Font << /F1 %d 0 R /F2 %d 0 R >>%s >>%s >>"
                                 % (pages, A4[0], A4[1], content, f1, f2,
                                    (" /Shading << %s >>" % shading_res) if sh_refs else "",
                                    (" /Annots [%s]" % " ".join("%d 0 R" % a for a in annots)) if annots else "")).encode()))
        objs[catalog - 1] = ("<< /Type /Catalog /Pages %d 0 R >>" % pages).encode()
        objs[pages - 1] = ("<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % p for p in page_ids), len(page_ids))).encode()
        info = add(b"<< /Title (" + _esc(self.title) + b") /Author (" + _esc(self.author) + b") /Producer (Travel Agency CRM) >>")
        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        for i, body in enumerate(objs):
            offsets.append(len(out))
            out += b"%d 0 obj\n" % (i + 1) + body + b"\nendobj\n"
        xref = len(out)
        out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
        for off in offsets:
            out += b"%010d 00000 n \n" % off
        out += b"trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, catalog, info, xref)
        return bytes(out)
