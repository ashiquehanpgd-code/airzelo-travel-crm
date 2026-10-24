import Foundation
import CoreGraphics

extension Agency {
    /// An issued invoice or credit note as PDF. Everything printed comes from the document's frozen snapshot
    /// (company, customer, lines and taxes as issued), so later edits to the profile or booking never change it.
    public func invoicePDF(_ iid: Int) throws -> (data: Data, fileName: String) {
        let inv = try getInvoice(iid)
        let r = inv.row, doc = inv.doc, biz = doc["business"], cust = doc["customer"], bk = doc["booking"]
        let isCN = r.str("doc_type") == "credit_note"
        let cur = r.str("currency")
        let brand = (biz["brand_color"].stringValue ?? "").range(of: "^#[0-9A-Fa-f]{6}$", options: .regularExpression) != nil
            ? biz["brand_color"].stringValue! : "#1B3FCC"
        let name = (biz["business_name"].stringValue ?? "").isEmpty ? "Travel Agency" : biz["business_name"].stringValue!
        let p = PDFCanvas(title: "\(isCN ? "Credit note" : "Invoice") \(r.str("number"))", author: name)
        let W = p.width, M: CGFloat = 40
        p.newPage()
        p.rect(0, 0, W, 6, fill: brand)
        var y: CGFloat = 46
        if let logo = logoImage(biz["logo_file"].stringValue ?? "") {
            p.image(logo, M, y - 22, 44, 44)
        }
        let lx = M + (logoImage(biz["logo_file"].stringValue ?? "") != nil ? 56 : 0)
        p.text(lx, y, name, 16, .bold, "#0D1833", maxWidth: 300)
        var yy = y + 14
        let legal = biz["legal_name"].stringValue ?? ""
        for line in [legal != name ? legal : "", biz["business_address"].stringValue ?? "",
                     [biz["business_phone"].stringValue ?? "", biz["business_email"].stringValue ?? "", biz["business_website"].stringValue ?? ""]
                        .filter { !$0.isEmpty }.joined(separator: "  |  "),
                     (biz["business_gstin"].stringValue ?? "").isEmpty ? "" : "GSTIN " + biz["business_gstin"].stringValue!]
        where !line.isEmpty {
            for l in PDFCanvas.wrap(line, 8.5, 300) { p.text(lx, yy, l, 8.5, .regular, "#64738F"); yy += 11.5 }
        }
        let gstin = !(biz["business_gstin"].stringValue ?? "").isEmpty
        let title = isCN ? "CREDIT NOTE" : (gstin ? "TAX INVOICE" : "INVOICE")
        p.text(W - M, y, title, 18, .bold, brand, align: .right, spacing: 0.8)
        p.text(W - M, y + 18, r.str("number"), 10, .bold, "#0D1833", align: .right)
        p.text(W - M, y + 31, "Date: " + TicketDates.dateLabel(r.str("issue_date")), 8.5, .regular, "#64738F", align: .right)
        if isCN, let against = doc["against"].stringValue { p.text(W - M, y + 43, "Against invoice " + against, 8.5, .regular, "#64738F", align: .right) }
        y = max(yy, y + 50) + 16

        // bill to + booking
        let half = (W - 2 * M - 16) / 2
        p.rect(M, y, half, 86, fill: "#F5F8FD", radius: 8)
        p.text(M + 12, y + 16, "BILL TO", 7.5, .bold, "#64738F", spacing: 0.5)
        var cy = y + 32
        p.text(M + 12, cy, cust["name"].stringValue ?? "", 11, .bold, "#0D1833", maxWidth: half - 24); cy += 13
        for line in [cust["address"].stringValue ?? "", [cust["city"].stringValue ?? "", cust["state"].stringValue ?? ""].filter { !$0.isEmpty }.joined(separator: ", "),
                     cust["phone"].stringValue ?? "", (cust["gstin"].stringValue ?? "").isEmpty ? "" : "GSTIN " + cust["gstin"].stringValue!] where !line.isEmpty {
            p.text(M + 12, cy, line, 8.5, .regular, "#0D1833", maxWidth: half - 24); cy += 11.5
            if cy > y + 82 { break }
        }
        let bx = M + half + 16
        p.rect(bx, y, half, 86, fill: "#F5F8FD", radius: 8)
        p.text(bx + 12, y + 16, "BOOKING", 7.5, .bold, "#64738F", spacing: 0.5)
        var by = y + 32
        for (k, v) in [("Booking", bk["ref"].stringValue ?? ""), ("PNR", bk["pnr"].stringValue ?? ""),
                       ("Travel date", TicketDates.dateLabel(bk["travel_date"].stringValue)),
                       ("Place of supply", bk["place_of_supply"].stringValue ?? (biz["business_state"].stringValue ?? ""))] where !v.isEmpty {
            p.text(bx + 12, by, k, 8.5, .regular, "#64738F")
            p.text(bx + half - 12, by, v, 8.5, .bold, "#0D1833", align: .right, maxWidth: half - 110)
            by += 13
        }
        y += 104

        // lines
        p.rect(M, y, W - 2 * M, 22, fill: "#0A1A3F", radius: 5)
        p.text(M + 10, y + 14.5, "DESCRIPTION", 7.5, .bold, "#FFFFFF", spacing: 0.4)
        p.text(W - M - 120, y + 14.5, "SAC", 7.5, .bold, "#FFFFFF", spacing: 0.4)
        p.text(W - M - 10, y + 14.5, "AMOUNT (\(cur))", 7.5, .bold, "#FFFFFF", align: .right, spacing: 0.4)
        y += 30
        for l in doc["lines"].arrayValue ?? [] {
            let descLines = PDFCanvas.wrap(l["description"].stringValue ?? "", 9, W - 2 * M - 200, .bold)
            for (i, d) in descLines.enumerated() { p.text(M + 10, y + CGFloat(i) * 12, d, 9, .bold, "#0D1833") }
            var ly = y + CGFloat(descLines.count) * 12
            if let det = l["detail"].stringValue, !det.isEmpty {
                for d in PDFCanvas.wrap(det, 8, W - 2 * M - 200) { p.text(M + 10, ly, d, 8, .regular, "#64738F"); ly += 10.5 }
            }
            p.text(W - M - 120, y, l["sac"].stringValue ?? "", 8.5, .regular, "#0D1833")
            p.text(W - M - 10, y, cur == "INR" ? indianGrouping(fmt(l["amount"].intValue ?? 0)) : fmt(l["amount"].intValue ?? 0), 9.5, .bold, "#0D1833", align: .right)
            y = ly + 8
            p.line(M, y - 4, W - M, y - 4, color: "#E2E8F2", width: 0.6)
            y += 6
        }

        // totals
        var rows: [(String, Int, Bool)] = [("Subtotal", r.int("subtotal"), false)]
        for t in inv.taxes { rows.append((t["label"].stringValue ?? "Tax", t["amount"].intValue ?? 0, false)) }
        rows.append((isCN ? "Total credit" : "Total", r.int("total"), true))
        let tx = W - M - 230
        for (label, v, strong) in rows {
            if strong { p.rect(tx - 10, y - 4, W - M - tx + 10, 24, fill: "#EAF0FF", radius: 5) }
            p.text(tx, y + 11, label, strong ? 10.5 : 9, strong ? .bold : .regular, strong ? brand : "#0D1833")
            p.text(W - M - 10, y + 11, money(v, cur), strong ? 11 : 9.5, .bold, strong ? brand : "#0D1833", align: .right)
            y += strong ? 30 : 18
        }
        if !isCN, let rec = inv.received, let out = inv.outstanding {
            y += 4
            p.text(tx, y + 8, "Received to date", 8.5, .regular, "#64738F")
            p.text(W - M - 10, y + 8, money(rec, inv.base), 8.5, .regular, "#64738F", align: .right)
            p.text(tx, y + 22, "Balance due", 8.5, .bold, "#0D1833")
            p.text(W - M - 10, y + 22, money(max(out, 0), inv.base), 8.5, .bold, out > 0 ? "#D33447" : "#0E9F6B", align: .right)
            y += 34
        }
        y += 18
        if let terms = biz["invoice_terms"].stringValue, !terms.isEmpty {
            p.text(M, y, "TERMS", 7.5, .bold, "#64738F", spacing: 0.5)
            y = p.paragraph(M, y + 13, terms, 8.5, width: W - 2 * M, .regular, "#0D1833") + 6
        }
        if let notes = r.optStr("notes"), !notes.isEmpty {
            p.text(M, y, "NOTES", 7.5, .bold, "#64738F", spacing: 0.5)
            y = p.paragraph(M, y + 13, notes, 8.5, width: W - 2 * M, .regular, "#0D1833") + 6
        }
        if let footer = biz["invoice_footer"].stringValue, !footer.isEmpty {
            p.text(W / 2, y + 14, footer, 9.5, .bold, brand, align: .center)
        }
        if r.str("status") == "cancelled" {
            p.ctx.saveGState()
            p.ctx.translateBy(x: W / 2, y: p.height / 2)
            p.ctx.rotate(by: .pi / 6)
            p.ctx.translateBy(x: -W / 2, y: -p.height / 2)
            p.text(W / 2, p.height / 2, "CANCELLED", 72, .bold, "#D33447", align: .center)
            p.ctx.restoreGState()
            p.text(M, p.height - 50, "Cancelled: " + (r.optStr("cancel_reason") ?? ""), 9, .bold, "#D33447")
        }
        p.line(M, p.height - 36, W - M, p.height - 36)
        p.text(M, p.height - 22, "Computer-generated document. Generated on this device with AirZelo.", 7.5, .regular, "#64738F")
        let safeNumber = r.str("number").replacingOccurrences(of: "[^A-Za-z0-9-]+", with: "-", options: .regularExpression)
        return (p.finish(), "\(isCN ? "CreditNote" : "Invoice")_\(safeNumber).pdf")
    }
}
