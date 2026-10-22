// Travel Agency CRM - offline ticket reader.
//
// Reads the text of a ticket file entirely on this Mac:
//   * PDFs with embedded text  -> PDFKit (exact text, no OCR)
//   * scanned PDFs and images  -> Apple Vision on-device text recognition (no network)
// and prints JSON on stdout. Optionally writes a PNG preview of the first page.
//
// Build:  see build/README (universal: arm64-apple-macos12 + x86_64-apple-macos13, joined with lipo)
// Usage:  ta-ticket-reader <file> [preview.png]
import Foundation
import PDFKit
import Vision
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

struct PageText: Codable {
    var page: Int
    var method: String          // "pdf-text" or "ocr"
    var text: String
    var confidence: Double?     // mean OCR confidence 0...1 (OCR only)
}

struct Output: Codable {
    var ok: Bool
    var kind: String
    var pages: [PageText]
    var page_count: Int
    var preview: Bool
    var error: String?
}

func emit(_ o: Output) {
    let enc = JSONEncoder()
    if let d = try? enc.encode(o), let s = String(data: d, encoding: .utf8) { print(s) }
}

/// OCR one image with Vision and rebuild reading-order lines (items on the same row joined by wide gaps,
/// so table columns stay separable for the parser).
func ocr(_ image: CGImage) -> (String, Double) {
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.usesLanguageCorrection = false          // keep codes like 6E1407 / K7Q2LM intact
    req.recognitionLanguages = ["en-US"]
    let handler = VNImageRequestHandler(cgImage: image, options: [:])
    do { try handler.perform([req]) } catch { return ("", 0) }
    let obs = (req.results ?? []).compactMap { o -> (CGRect, String, Float)? in
        guard let c = o.topCandidates(1).first else { return nil }
        return (o.boundingBox, c.string, c.confidence)
    }
    if obs.isEmpty { return ("", 0) }
    // group into rows by vertical centre
    let sorted = obs.sorted { $0.0.midY > $1.0.midY }
    var rows: [[(CGRect, String, Float)]] = []
    for item in sorted {
        if let last = rows.last, let ref = last.first, abs(ref.0.midY - item.0.midY) < max(ref.0.height, item.0.height) * 0.55 {
            rows[rows.count - 1].append(item)
        } else {
            rows.append([item])
        }
    }
    var lines: [String] = []
    for row in rows {
        let cells = row.sorted { $0.0.minX < $1.0.minX }
        var line = ""
        var prevMax: CGFloat? = nil
        for c in cells {
            if let p = prevMax { line += (c.0.minX - p > 0.04) ? "    " : " " }
            line += c.1
            prevMax = c.0.maxX
        }
        lines.append(line)
    }
    let conf = obs.map { Double($0.2) }.reduce(0, +) / Double(obs.count)
    return (lines.joined(separator: "\n"), conf)
}

func render(_ page: PDFPage, scale: CGFloat) -> CGImage? {
    let box = page.bounds(for: .mediaBox)
    let w = Int(box.width * scale), h = Int(box.height * scale)
    guard w > 0, h > 0, let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    ctx.scaleBy(x: scale, y: scale)
    ctx.translateBy(x: -box.minX, y: -box.minY)
    page.draw(with: .mediaBox, to: ctx)
    return ctx.makeImage()
}

func writePNG(_ img: CGImage, to path: String, maxWidth: CGFloat = 900) {
    var image = img
    if CGFloat(img.width) > maxWidth {
        let s = maxWidth / CGFloat(img.width)
        let w = Int(CGFloat(img.width) * s), h = Int(CGFloat(img.height) * s)
        if let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
            ctx.interpolationQuality = .high
            ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            if let small = ctx.makeImage() { image = small }
        }
    }
    let url = URL(fileURLWithPath: path) as CFURL
    guard let dest = CGImageDestinationCreateWithURL(url, UTType.png.identifier as CFString, 1, nil) else { return }
    CGImageDestinationAddImage(dest, image, nil)
    CGImageDestinationFinalize(dest)
}

let args = CommandLine.arguments
guard args.count >= 2 else {
    emit(Output(ok: false, kind: "", pages: [], page_count: 0, preview: false, error: "usage: ta-ticket-reader <file> [preview.png]"))
    exit(2)
}
let path = args[1]
let previewPath = args.count >= 3 ? args[2] : nil
let url = URL(fileURLWithPath: path)
let maxPages = 12

if let doc = PDFDocument(url: url) {
    if doc.isLocked {
        emit(Output(ok: false, kind: "pdf", pages: [], page_count: doc.pageCount, preview: false, error: "The PDF is password protected"))
        exit(0)
    }
    var pages: [PageText] = []
    var preview = false
    for i in 0..<min(doc.pageCount, maxPages) {
        guard let page = doc.page(at: i) else { continue }
        let text = (page.string ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if i == 0, let p = previewPath, let img = render(page, scale: 1.5) { writePNG(img, to: p); preview = true }
        if text.count >= 40 {
            pages.append(PageText(page: i + 1, method: "pdf-text", text: text, confidence: nil))
        } else if let img = render(page, scale: 3.0) {
            let (t, c) = ocr(img)                              // scanned / image-only page
            pages.append(PageText(page: i + 1, method: "ocr", text: t, confidence: c))
        }
    }
    emit(Output(ok: true, kind: "pdf", pages: pages, page_count: doc.pageCount, preview: preview, error: nil))
    exit(0)
}

guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
    emit(Output(ok: false, kind: "", pages: [], page_count: 0, preview: false, error: "Not a readable PDF or image"))
    exit(0)
}
var image = img
if img.width < 1600 {   // small screenshots: upscale so small text OCRs well
    let s = 1600.0 / CGFloat(img.width)
    let w = Int(CGFloat(img.width) * s), h = Int(CGFloat(img.height) * s)
    if let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                           bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
        ctx.interpolationQuality = .high
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
        if let big = ctx.makeImage() { image = big }
    }
}
let (t, c) = ocr(image)
if let p = previewPath { writePNG(img, to: p) }
emit(Output(ok: true, kind: "image", pages: [PageText(page: 1, method: "ocr", text: t, confidence: c)], page_count: 1, preview: previewPath != nil, error: nil))
