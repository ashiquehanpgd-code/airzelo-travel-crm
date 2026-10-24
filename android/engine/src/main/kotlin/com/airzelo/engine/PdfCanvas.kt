package com.airzelo.engine

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small PDF writer in pure Kotlin (no Android graphics), ported from the desktop's standard-library
 * core/server/pdfkit.py, with the drawing API of the iPhone's PDF/PDFCanvas.swift so the document layouts port
 * line by line. Coordinates are top-left based (y grows downwards), A4 portrait. It uses the built-in Helvetica
 * fonts (WinAnsi encoding), so no font files are needed; characters outside Windows-1252 are replaced.
 */
class PdfCanvas(private val title: String, private val author: String) {
    companion object {
        const val A4_W = 595.28
        const val A4_H = 841.89

        // Glyph widths (per 1000 em) for ASCII 32..126, from the Adobe Helvetica AFM files (as in pdfkit.py).
        private val HELV = intArrayOf(278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
            1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
            333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
            556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584)
        private val HELV_B = intArrayOf(278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
            556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
            975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
            667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
            333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
            611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584)
        private val EXTRA = mapOf('•' to 350, '–' to 556, '—' to 1000, '·' to 278, '’' to 222, '×' to 584, '…' to 1000)
        private val CP1252 = charset("windows-1252")

        /** Maps text to what the built-in fonts can show. */
        fun clean(text: String?): String {
            val s = (text ?: "").replace("₹", "INR ").replace("→", "->").replace("←", "<-").replace("‘", "'").replace("’", "'")
                .replace("“", "\"").replace("”", "\"").replace("\t", " ")
            val enc = CP1252.newEncoder()
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val ch = String(Character.toChars(cp))
                out.append(if (cp < 0x10000 && enc.canEncode(ch[0])) ch else "?")
                i += Character.charCount(cp)
            }
            return out.toString()
        }

        fun textWidth(s: String, size: Double, bold: Boolean = false, spacing: Double = 0.0): Double {
            val widths = if (bold) HELV_B else HELV
            val c = clean(s)
            var w = 0
            for (ch in c) w += if (ch.code in 32..126) widths[ch.code - 32] else EXTRA[ch] ?: 556
            return w * size / 1000.0 + spacing * maxOf(c.length - 1, 0)
        }

        /** Word-wraps text into lines that fit [width] (as the iPhone layout does). */
        fun wrap(s: String, size: Double, width: Double, bold: Boolean = false): List<String> {
            val lines = ArrayList<String>()
            for (para in s.split("\n")) {
                var cur = ""
                for (word in para.split(" ").filter { it.isNotEmpty() }) {
                    val trial = if (cur.isEmpty()) word else "$cur $word"
                    if (textWidth(trial, size, bold) <= width || cur.isEmpty()) cur = trial else { lines.add(cur); cur = word }
                }
                lines.add(cur)
            }
            return lines
        }

        private fun n(v: Double) = String.format(Locale.ROOT, "%.2f", v)
        private fun c3(v: Double) = String.format(Locale.ROOT, "%.3f", v)

        fun rgb(hex: String): DoubleArray {
            val h = hex.trim().removePrefix("#")
            val v = if (h.length == 6) h.toIntOrNull(16) else null
            v ?: return doubleArrayOf(0.0, 0.0, 0.0)
            return doubleArrayOf(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
        }
    }

    enum class Align { LEFT, CENTER, RIGHT }

    val width = A4_W
    val height = A4_H
    private val pages = ArrayList<StringBuilder>()
    private val shadings = ArrayList<String>()
    private val images = ArrayList<PdfImage>()
    private val ops get() = pages.last()
    val pageCount get() = pages.size

    fun newPage() { pages.add(StringBuilder()) }

    private fun op(s: String) { ops.append(s).append('\n') }
    private fun color(hex: String, stroke: Boolean = false) {
        val c = rgb(hex); op("${c3(c[0])} ${c3(c[1])} ${c3(c[2])} ${if (stroke) "RG" else "rg"}")
    }
    private fun fy(y: Double) = height - y

    // ---- shapes (top-left coordinates)

    /** Rectangle path in PDF coordinates (x, bottom y), optionally with rounded corners. */
    private fun pathRect(x: Double, y: Double, w: Double, h: Double, radius: Double) {
        val r = minOf(radius, w / 2, h / 2)
        if (r <= 0) { op("${n(x)} ${n(y)} ${n(w)} ${n(h)} re"); return }
        val k = 0.5523 * r
        op("${n(x + r)} ${n(y)} m ${n(x + w - r)} ${n(y)} l " +
            "${n(x + w - r + k)} ${n(y)} ${n(x + w)} ${n(y + r - k)} ${n(x + w)} ${n(y + r)} c ${n(x + w)} ${n(y + h - r)} l " +
            "${n(x + w)} ${n(y + h - r + k)} ${n(x + w - r + k)} ${n(y + h)} ${n(x + w - r)} ${n(y + h)} c ${n(x + r)} ${n(y + h)} l " +
            "${n(x + r - k)} ${n(y + h)} ${n(x)} ${n(y + h - r + k)} ${n(x)} ${n(y + h - r)} c ${n(x)} ${n(y + r)} l " +
            "${n(x)} ${n(y + r - k)} ${n(x + r - k)} ${n(y)} ${n(x + r)} ${n(y)} c h")
    }

    fun rect(x: Double, y: Double, w: Double, h: Double, fill: String? = null, stroke: String? = null, lineWidth: Double = 0.8, radius: Double = 0.0) {
        if (fill == null && stroke == null) return
        op("q")
        if (fill != null) color(fill)
        if (stroke != null) { color(stroke, true); op("${n(lineWidth)} w") }
        pathRect(x, fy(y + h), w, h, radius)
        op(if (fill != null && stroke != null) "B" else if (fill != null) "f" else "S")
        op("Q")
    }

    /** Linear gradient, left to right, clipped to a (rounded) rectangle. */
    fun gradient(x: Double, y: Double, w: Double, h: Double, c1: String, c2: String, radius: Double = 0.0) {
        val a = rgb(c1); val b = rgb(c2)
        val by = fy(y + h)
        shadings.add("<< /ShadingType 2 /ColorSpace /DeviceRGB /Coords [${n(x)} ${n(by)} ${n(x + w)} ${n(by)}] /Extend [true true] " +
            "/Function << /FunctionType 2 /Domain [0 1] /C0 [${c3(a[0])} ${c3(a[1])} ${c3(a[2])}] /C1 [${c3(b[0])} ${c3(b[1])} ${c3(b[2])}] /N 1 >> >>")
        op("q")
        pathRect(x, by, w, h, radius)
        op("W n /Sh${shadings.size} sh Q")
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: String = "#E2E8F2", width: Double = 0.8, dash: List<Double>? = null) {
        op("q")
        color(color, true)
        op("${n(width)} w")
        if (dash != null) op("[${dash.joinToString(" ") { String.format(Locale.ROOT, "%.1f", it) }}] 0 d")
        op("${n(x1)} ${n(fy(y1))} m ${n(x2)} ${n(fy(y2))} l S Q")
    }

    fun circle(cx: Double, cy: Double, r: Double, fill: String? = null, stroke: String? = null, lineWidth: Double = 1.0) {
        if (fill == null && stroke == null) return
        val k = 0.5523 * r
        val y = fy(cy)
        op("q")
        if (fill != null) color(fill)
        if (stroke != null) { color(stroke, true); op("${n(lineWidth)} w") }
        op("${n(cx + r)} ${n(y)} m ${n(cx + r)} ${n(y + k)} ${n(cx + k)} ${n(y + r)} ${n(cx)} ${n(y + r)} c " +
            "${n(cx - k)} ${n(y + r)} ${n(cx - r)} ${n(y + k)} ${n(cx - r)} ${n(y)} c " +
            "${n(cx - r)} ${n(y - k)} ${n(cx - k)} ${n(y - r)} ${n(cx)} ${n(y - r)} c " +
            "${n(cx + k)} ${n(y - r)} ${n(cx + r)} ${n(y - k)} ${n(cx + r)} ${n(y)} c h")
        op(if (fill != null && stroke != null) "B" else if (fill != null) "f" else "S")
        op("Q")
    }

    /** A small aeroplane glyph pointing right (the iPhone canvas outline). */
    fun plane(cx: Double, cy: Double, size: Double, color: String) {
        val s = size
        val pts = listOf(0.5 to 0.0, 0.1 to 0.08, -0.15 to 0.42, -0.25 to 0.42, -0.08 to 0.06, -0.35 to 0.06, -0.45 to 0.18, -0.5 to 0.18,
            -0.42 to 0.0, -0.5 to -0.18, -0.45 to -0.18, -0.35 to -0.06, -0.08 to -0.06, -0.25 to -0.42, -0.15 to -0.42, 0.1 to -0.08)
        val y0 = fy(cy)
        op("q")
        color(color)
        op(pts.mapIndexed { i, (px, py) -> "${n(cx + px * s)} ${n(y0 + py * s)} ${if (i == 0) "m" else "l"}" }.joinToString(" ") + " h f Q")
    }

    /** Draws a registered image scaled into the box. */
    fun image(img: PdfImage, x: Double, y: Double, w: Double, h: Double) {
        val idx = images.indexOf(img).let { if (it < 0) { images.add(img); images.size } else it + 1 }
        op("q ${n(w)} 0 0 ${n(h)} ${n(x)} ${n(fy(y + h))} cm /Im$idx Do Q")
    }

    /** Draws [body] rotated by [degrees] (counter-clockwise) around the point (cx, cy). */
    fun rotated(degrees: Double, cx: Double, cy: Double, body: () -> Unit) {
        val a = Math.toRadians(degrees)
        val y = fy(cy)
        op("q 1 0 0 1 ${n(cx)} ${n(y)} cm ${String.format(Locale.ROOT, "%.5f %.5f %.5f %.5f", cos(a), sin(a), -sin(a), cos(a))} 0 0 cm 1 0 0 1 ${n(-cx)} ${n(-y)} cm")
        body()
        op("Q")
    }

    // ---- text

    /** Draws one line of text with its baseline at y, truncated to [maxWidth] if given. */
    fun text(x: Double, y: Double, s: String, size: Double = 10.0, bold: Boolean = false, color: String = "#0D1833",
             align: Align = Align.LEFT, spacing: Double = 0.0, maxWidth: Double? = null) {
        var str = clean(s)
        if (str.isEmpty()) return
        if (maxWidth != null) {
            while (str.length > 1 && textWidth(str, size, bold, spacing) > maxWidth) str = str.dropLast(2) + "…"
        }
        val w = textWidth(str, size, bold, spacing)
        val dx = when (align) { Align.LEFT -> 0.0; Align.CENTER -> -w / 2; Align.RIGHT -> -w }
        op("BT")
        color(color)
        op("${n(spacing)} Tc /${if (bold) "F2" else "F1"} ${n(size)} Tf ${n(x + dx)} ${n(fy(y))} Td ${pdfString(str)} Tj ET")
    }

    /** Draws a wrapped paragraph; returns the y below it. */
    fun paragraph(x: Double, y: Double, s: String, size: Double, width: Double, bold: Boolean = false, color: String = "#0D1833", leading: Double? = null): Double {
        var yy = y
        for (l in wrap(s, size, width, bold)) { text(x, yy, l, size, bold, color); yy += leading ?: (size * 1.45) }
        return yy
    }

    // ---- output

    /** A PDF literal string in WinAnsi (Windows-1252) bytes. */
    private fun pdfString(s: String): String {
        val b = StringBuilder("(")
        for (byte in clean(s).toByteArray(CP1252)) {
            val ch = byte.toInt() and 0xFF
            when {
                ch == '\\'.code || ch == '('.code || ch == ')'.code -> b.append('\\').append(ch.toChar())
                ch < 32 || ch > 126 -> b.append('\\').append(String.format(Locale.ROOT, "%03o", ch))
                else -> b.append(ch.toChar())
            }
        }
        return b.append(")").toString()
    }

    fun finish(): ByteArray {
        val objs = ArrayList<ByteArray?>()
        fun add(body: ByteArray?): Int { objs.add(body); return objs.size }
        fun add(body: String) = add(body.toByteArray(Charsets.ISO_8859_1))
        fun stream(dict: String, data: ByteArray) = add(("$dict /Length ${data.size} >>\nstream\n").toByteArray(Charsets.ISO_8859_1) + data + "\nendstream".toByteArray())
        val catalog = add(null)
        val pagesId = add(null)
        val f1 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val f2 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        val shRefs = shadings.map { add(it) }
        val imgRefs = images.map { im ->
            val smask = im.alpha?.let { a -> " /SMask ${stream("<< /Type /XObject /Subtype /Image /Width ${im.w} /Height ${im.h} /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode", deflate(a))} 0 R" } ?: ""
            stream("<< /Type /XObject /Subtype /Image /Width ${im.w} /Height ${im.h} /ColorSpace /${im.colorSpace} /BitsPerComponent 8 /Filter /${im.filter}$smask", im.data)
        }
        val shRes = shRefs.withIndex().joinToString(" ") { "/Sh${it.index + 1} ${it.value} 0 R" }
        val imRes = imgRefs.withIndex().joinToString(" ") { "/Im${it.index + 1} ${it.value} 0 R" }
        val pageIds = pages.map { pg ->
            val content = stream("<< /Filter /FlateDecode", deflate(pg.toString().toByteArray(Charsets.ISO_8859_1)))
            add("<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${n(width)} ${n(height)}] /Contents $content 0 R " +
                "/Resources << /Font << /F1 $f1 0 R /F2 $f2 0 R >>" + (if (shRefs.isNotEmpty()) " /Shading << $shRes >>" else "") +
                (if (imgRefs.isNotEmpty()) " /XObject << $imRes >>" else "") + " >> >>")
        }
        objs[catalog - 1] = "<< /Type /Catalog /Pages $pagesId 0 R >>".toByteArray()
        objs[pagesId - 1] = "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>".toByteArray()
        val info = add("<< /Title ${pdfString(title)} /Author ${pdfString(author)} /Producer (AirZelo for Android) >>")
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%".toByteArray()); out.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        val offsets = ArrayList<Int>()
        for ((i, body) in objs.withIndex()) {
            offsets.add(out.size())
            out.write("${i + 1} 0 obj\n".toByteArray()); out.write(body!!); out.write("\nendobj\n".toByteArray())
        }
        val xref = out.size()
        val tail = StringBuilder("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) tail.append(String.format(Locale.ROOT, "%010d 00000 n \n", o))
        tail.append("trailer\n<< /Size ${objs.size + 1} /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(tail.toString().toByteArray())
        return out.toByteArray()
    }
}

internal fun deflate(data: ByteArray): ByteArray {
    val d = Deflater()
    try {
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    } finally { d.end() }
}

/** A PDF image XObject: JPEG embedded as is, or an 8-bit non-interlaced PNG decoded (alpha kept as a soft mask). Port of pdfkit._image_object. */
class PdfImage private constructor(val w: Int, val h: Int, val colorSpace: String, val filter: String, val data: ByteArray, val alpha: ByteArray?) {
    companion object {
        private fun u16(d: ByteArray, o: Int) = ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)
        private fun u32(d: ByteArray, o: Int) = (u16(d, o).toLong() shl 16) or u16(d, o + 2).toLong()

        /** Null when the bytes are not an image that can be embedded. */
        fun from(data: ByteArray): PdfImage? = try { parse(data) } catch (e: Exception) { null }

        private fun parse(data: ByteArray): PdfImage? {
            if (data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) {
                var i = 2
                while (i + 9 < data.size) {
                    if (data[i] != 0xFF.toByte()) { i++; continue }
                    val m = data[i + 1].toInt() and 0xFF
                    if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
                    val seg = u16(data, i + 2)
                    if (m in 0xC0..0xC2) {
                        val h = u16(data, i + 5); val w = u16(data, i + 7)
                        val cs = when (data[i + 9].toInt() and 0xFF) { 1 -> "DeviceGray"; 3 -> "DeviceRGB"; 4 -> "DeviceCMYK"; else -> return null }
                        return PdfImage(w, h, cs, "DCTDecode", data, null)
                    }
                    i += 2 + seg
                }
                return null
            }
            val sig = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
            if (data.size < 8 || !data.copyOf(8).contentEquals(sig)) return null
            var pos = 8
            val idat = ByteArrayOutputStream()
            var palette: ByteArray? = null; var trns: ByteArray? = null
            var w = 0; var h = 0; var depth = 0; var ctype = -1; var interlace = 0
            while (pos + 8 <= data.size) {
                val len = u32(data, pos).toInt()
                val typ = String(data, pos + 4, 4, Charsets.ISO_8859_1)
                val body = data.copyOfRange(pos + 8, pos + 8 + len)
                when (typ) {
                    "IHDR" -> { w = u32(body, 0).toInt(); h = u32(body, 4).toInt(); depth = body[8].toInt() and 0xFF; ctype = body[9].toInt() and 0xFF; interlace = body[12].toInt() }
                    "PLTE" -> palette = body
                    "tRNS" -> trns = body
                    "IDAT" -> idat.write(body)
                }
                if (typ == "IEND") break
                pos += 12 + len
            }
            if (depth != 8 || interlace != 0 || ctype !in listOf(0, 2, 3, 4, 6) || w <= 0 || h <= 0 || w.toLong() * h > 16_000_000) return null
            val bpp = mapOf(0 to 1, 2 to 3, 3 to 1, 4 to 2, 6 to 4)[ctype]!!
            val stride = w * bpp
            val raw = inflate(idat.toByteArray(), h * (stride + 1))
            val out = ByteArray(h * stride)
            var prev = ByteArray(stride)
            for (r in 0 until h) {
                val f = raw[r * (stride + 1)].toInt()
                val line = raw.copyOfRange(r * (stride + 1) + 1, (r + 1) * (stride + 1))
                for (i in 0 until stride) {
                    val a = if (i >= bpp) line[i - bpp].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                    val x = line[i].toInt() and 0xFF
                    line[i] = when (f) {
                        1 -> x + a; 2 -> x + b; 3 -> x + ((a + b) shr 1)
                        4 -> { val pa = Math.abs(b - c); val pb = Math.abs(a - c); val pc = Math.abs(a + b - 2 * c)
                            x + (if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c) }
                        else -> x
                    }.toByte()
                }
                System.arraycopy(line, 0, out, r * stride, stride)
                prev = line
            }
            if (ctype == 3) {
                val pal = palette ?: return null
                val rgb = ByteArray(w * h * 3); val alpha = ByteArray(w * h)
                for ((k, idxB) in out.withIndex()) {
                    val idx = idxB.toInt() and 0xFF
                    if (idx * 3 + 2 < pal.size) System.arraycopy(pal, idx * 3, rgb, k * 3, 3)
                    alpha[k] = trns?.let { t -> if (idx < t.size) t[idx] else 0xFF.toByte() } ?: 0xFF.toByte()
                }
                return PdfImage(w, h, "DeviceRGB", "FlateDecode", deflate(rgb), if (trns != null) alpha else null)
            }
            if (ctype == 0 || ctype == 2) return PdfImage(w, h, if (ctype == 0) "DeviceGray" else "DeviceRGB", "FlateDecode", deflate(out), null)
            val nc = if (ctype == 4) 1 else 3
            val color = ByteArray(w * h * nc); val alpha = ByteArray(w * h)
            for (k in 0 until w * h) {
                System.arraycopy(out, k * (nc + 1), color, k * nc, nc)
                alpha[k] = out[k * (nc + 1) + nc]
            }
            return PdfImage(w, h, if (nc == 1) "DeviceGray" else "DeviceRGB", "FlateDecode", deflate(color), alpha)
        }

        private fun inflate(data: ByteArray, size: Int): ByteArray {
            val inf = Inflater()
            try {
                inf.setInput(data)
                val out = ByteArray(size)
                var n = 0
                while (n < size && !inf.finished()) {
                    val k = inf.inflate(out, n, size - n)
                    if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    n += k
                }
                if (n != size) throw IllegalArgumentException("short PNG data")
                return out
            } finally { inf.end() }
        }
    }
}
