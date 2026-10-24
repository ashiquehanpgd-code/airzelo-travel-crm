import Foundation
import CoreGraphics
import CoreText

/// A small page-drawing helper over CoreGraphics + CoreText. Coordinates are top-left based (y grows
/// downwards), A4 portrait, so layouts read like the desktop's pdfkit.py. Works on iOS and macOS, offline.
public final class PDFCanvas {
    public static let A4 = CGSize(width: 595.28, height: 841.89)
    let data = NSMutableData()
    let ctx: CGContext
    var mediaBox: CGRect
    public private(set) var pageCount = 0
    public var width: CGFloat { mediaBox.width }
    public var height: CGFloat { mediaBox.height }

    public init(title: String, author: String) {
        mediaBox = CGRect(origin: .zero, size: PDFCanvas.A4)
        let info: [CFString: Any] = [kCGPDFContextTitle: title, kCGPDFContextAuthor: author, kCGPDFContextCreator: "AirZelo for iPhone"]
        ctx = CGContext(consumer: CGDataConsumer(data: data as CFMutableData)!, mediaBox: &mediaBox, info as CFDictionary)!
    }

    public func newPage() {
        if pageCount > 0 { ctx.endPDFPage() }
        ctx.beginPDFPage(nil)
        pageCount += 1
    }

    public func finish() -> Data {
        if pageCount > 0 { ctx.endPDFPage() }
        ctx.closePDF()
        return data as Data
    }

    // MARK: colours

    public static func color(_ hex: String, alpha: CGFloat = 1) -> CGColor {
        var h = hex.trimmingCharacters(in: .whitespaces)
        if h.hasPrefix("#") { h.removeFirst() }
        guard h.count == 6, let v = UInt32(h, radix: 16) else { return CGColor(red: 0, green: 0, blue: 0, alpha: alpha) }
        return CGColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: alpha)
    }

    // MARK: shapes (top-left coordinates)

    func flip(_ r: CGRect) -> CGRect { CGRect(x: r.minX, y: height - r.maxY, width: r.width, height: r.height) }

    public func rect(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, fill: String? = nil, stroke: String? = nil, lineWidth: CGFloat = 0.8, radius: CGFloat = 0) {
        let r = flip(CGRect(x: x, y: y, width: w, height: h))
        let path = CGPath(roundedRect: r, cornerWidth: min(radius, w / 2, h / 2), cornerHeight: min(radius, w / 2, h / 2), transform: nil)
        if let fill {
            ctx.setFillColor(PDFCanvas.color(fill))
            ctx.addPath(path)
            ctx.fillPath()
        }
        if let stroke {
            ctx.setStrokeColor(PDFCanvas.color(stroke))
            ctx.setLineWidth(lineWidth)
            ctx.addPath(path)
            ctx.strokePath()
        }
    }

    public func gradient(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ c1: String, _ c2: String, radius: CGFloat = 0) {
        let r = flip(CGRect(x: x, y: y, width: w, height: h))
        ctx.saveGState()
        ctx.addPath(CGPath(roundedRect: r, cornerWidth: min(radius, w / 2, h / 2), cornerHeight: min(radius, w / 2, h / 2), transform: nil))
        ctx.clip()
        let g = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [PDFCanvas.color(c1), PDFCanvas.color(c2)] as CFArray, locations: [0, 1])!
        ctx.drawLinearGradient(g, start: CGPoint(x: r.minX, y: r.midY), end: CGPoint(x: r.maxX, y: r.midY), options: [])
        ctx.restoreGState()
    }

    public func line(_ x1: CGFloat, _ y1: CGFloat, _ x2: CGFloat, _ y2: CGFloat, color: String = "#E2E8F2", width: CGFloat = 0.8, dash: [CGFloat]? = nil) {
        ctx.saveGState()
        ctx.setStrokeColor(PDFCanvas.color(color))
        ctx.setLineWidth(width)
        if let dash { ctx.setLineDash(phase: 0, lengths: dash) }
        ctx.move(to: CGPoint(x: x1, y: height - y1))
        ctx.addLine(to: CGPoint(x: x2, y: height - y2))
        ctx.strokePath()
        ctx.restoreGState()
    }

    public func circle(_ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, fill: String? = nil, stroke: String? = nil, lineWidth: CGFloat = 1) {
        let e = CGRect(x: cx - r, y: height - cy - r, width: 2 * r, height: 2 * r)
        if let fill { ctx.setFillColor(PDFCanvas.color(fill)); ctx.fillEllipse(in: e) }
        if let stroke { ctx.setStrokeColor(PDFCanvas.color(stroke)); ctx.setLineWidth(lineWidth); ctx.strokeEllipse(in: e) }
    }

    /// A small aeroplane glyph pointing right.
    public func plane(_ cx: CGFloat, _ cy: CGFloat, size s: CGFloat, color: String) {
        ctx.saveGState()
        ctx.translateBy(x: cx, y: height - cy)
        ctx.setFillColor(PDFCanvas.color(color))
        let p = CGMutablePath()
        p.move(to: CGPoint(x: s * 0.5, y: 0))
        p.addLine(to: CGPoint(x: s * 0.1, y: s * 0.08))
        p.addLine(to: CGPoint(x: -s * 0.15, y: s * 0.42))
        p.addLine(to: CGPoint(x: -s * 0.25, y: s * 0.42))
        p.addLine(to: CGPoint(x: -s * 0.08, y: s * 0.06))
        p.addLine(to: CGPoint(x: -s * 0.35, y: s * 0.06))
        p.addLine(to: CGPoint(x: -s * 0.45, y: s * 0.18))
        p.addLine(to: CGPoint(x: -s * 0.5, y: s * 0.18))
        p.addLine(to: CGPoint(x: -s * 0.42, y: 0))
        p.addLine(to: CGPoint(x: -s * 0.5, y: -s * 0.18))
        p.addLine(to: CGPoint(x: -s * 0.45, y: -s * 0.18))
        p.addLine(to: CGPoint(x: -s * 0.35, y: -s * 0.06))
        p.addLine(to: CGPoint(x: -s * 0.08, y: -s * 0.06))
        p.addLine(to: CGPoint(x: -s * 0.25, y: -s * 0.42))
        p.addLine(to: CGPoint(x: -s * 0.15, y: -s * 0.42))
        p.addLine(to: CGPoint(x: s * 0.1, y: -s * 0.08))
        p.closeSubpath()
        ctx.addPath(p)
        ctx.fillPath()
        ctx.restoreGState()
    }

    public func image(_ img: CGImage, _ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) {
        ctx.draw(img, in: flip(CGRect(x: x, y: y, width: w, height: h)))
    }

    // MARK: text

    public enum Weight { case regular, bold }
    public enum Align { case left, center, right }

    static func font(_ size: CGFloat, _ weight: Weight) -> CTFont {
        CTFontCreateWithName((weight == .bold ? "Helvetica-Bold" : "Helvetica") as CFString, size, nil)
    }

    static func attributed(_ s: String, _ size: CGFloat, _ weight: Weight, _ color: String, spacing: CGFloat = 0) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): font(size, weight),
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): PDFCanvas.color(color),
            NSAttributedString.Key(kCTKernAttributeName as String): spacing,
        ])
    }

    public static func textWidth(_ s: String, _ size: CGFloat, _ weight: Weight = .regular, spacing: CGFloat = 0) -> CGFloat {
        let line = CTLineCreateWithAttributedString(attributed(s, size, weight, "#000000", spacing: spacing))
        return CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
    }

    /// Draws one line of text with its baseline at y (top-left coordinates), truncated to maxWidth if given.
    public func text(_ x: CGFloat, _ y: CGFloat, _ s: String, _ size: CGFloat = 10, _ weight: Weight = .regular, _ color: String = "#0D1833",
                     align: Align = .left, spacing: CGFloat = 0, maxWidth: CGFloat? = nil) {
        guard !s.isEmpty else { return }
        var str = s
        if let maxWidth {
            while str.count > 1 && PDFCanvas.textWidth(str, size, weight, spacing: spacing) > maxWidth { str = String(str.dropLast(2)) + "…" }
        }
        let line = CTLineCreateWithAttributedString(PDFCanvas.attributed(str, size, weight, color, spacing: spacing))
        let w = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        let dx: CGFloat = align == .left ? 0 : (align == .center ? -w / 2 : -w)
        ctx.textPosition = CGPoint(x: x + dx, y: height - y)
        CTLineDraw(line, ctx)
    }

    /// Word-wraps text into lines that fit `width`.
    public static func wrap(_ s: String, _ size: CGFloat, _ width: CGFloat, _ weight: Weight = .regular) -> [String] {
        var lines: [String] = []
        for para in s.components(separatedBy: "\n") {
            var cur = ""
            for word in para.split(separator: " ", omittingEmptySubsequences: true) {
                let trial = cur.isEmpty ? String(word) : cur + " " + word
                if textWidth(trial, size, weight) <= width || cur.isEmpty { cur = trial } else { lines.append(cur); cur = String(word) }
            }
            lines.append(cur)
        }
        return lines
    }

    /// Draws a wrapped paragraph; returns the y below it.
    @discardableResult
    public func paragraph(_ x: CGFloat, _ y: CGFloat, _ s: String, _ size: CGFloat, width: CGFloat, _ weight: Weight = .regular,
                          _ color: String = "#0D1833", leading: CGFloat? = nil) -> CGFloat {
        var yy = y
        for l in PDFCanvas.wrap(s, size, width, weight) {
            text(x, yy, l, size, weight, color)
            yy += leading ?? size * 1.45
        }
        return yy
    }
}

/// Money text as on desktop documents: "INR 1,23,456.00" (Indian grouping for INR), "SAR 1234.50" otherwise.
public func money(_ minor: Int, _ currency: String = "INR") -> String {
    currency + " " + (currency == "INR" ? indianGrouping(fmt(minor)) : fmt(minor))
}

public func indianGrouping(_ s: String) -> String {
    let neg = s.hasPrefix("-")
    let body = neg ? String(s.dropFirst()) : s
    let parts = body.split(separator: ".", maxSplits: 1).map(String.init)
    var whole = parts[0].replacingOccurrences(of: ",", with: "")
    let frac = parts.count > 1 ? parts[1] : ""
    if whole.count > 3 {
        var head = String(whole.dropLast(3))
        let tail = String(whole.suffix(3))
        var groups: [String] = []
        while head.count > 2 { groups.insert(String(head.suffix(2)), at: 0); head = String(head.dropLast(2)) }
        if !head.isEmpty { groups.insert(head, at: 0) }
        whole = (groups + [tail]).joined(separator: ",")
    }
    return (neg ? "-" : "") + whole + (frac.isEmpty ? "" : "." + frac)
}
