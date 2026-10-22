// WhatsApp message helpers: phone normalisation, wa.me links and templates.
// Run: node tests/whatsapp.test.js   (also run by test_whatsapp.py when Node.js is installed)
"use strict";
const fs = require("fs");
const path = require("path");
const assert = require("assert");
const vm = require("vm");

const QT = {
  h: () => "", esc: (s) => String(s), icon: () => "", el: () => ({}), $: () => null, $$: () => [],
  raw: (x) => x, opt: () => "", state: { meta: { settings: { business_name: "Demo Travels", business_phone: "+91 98470 00000" } } },
  labels: { method: { upi: "UPI", cash: "Cash" } },
  date: (s) => (s ? s.slice(8, 10) + "/" + s.slice(5, 7) + "/" + s.slice(0, 4) : ""),
  time: (s) => String(s).slice(11, 16),
  today: () => "2026-10-21",
  money: (m, cur) => (cur && cur !== "INR" ? cur + " " : "₹") + (m / 100).toLocaleString("en-IN", { minimumFractionDigits: 2 }),
};
const ctx = { window: { QT }, navigator: {}, document: {}, encodeURIComponent };
vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(__dirname, "..", "static", "js", "whatsapp.js"), "utf8"), ctx);
const wa = QT.wa;
let n = 0;
const t = (name, fn) => { fn(); n++; console.log("ok -", name); };

t("Indian numbers in common formats", () => {
  for (const p of ["+91 98470 11111", "9847011111", "09847011111", "+91-98470-11111", "919847011111", "0091 98470 11111", "(+91) 98470 11111"])
    assert.strictEqual(wa.normalize(p).digits, "919847011111", p);
});
t("Saudi and other Gulf numbers need a country code", () => {
  assert.strictEqual(wa.normalize("+966 50 123 4567").digits, "966501234567");
  assert.strictEqual(wa.normalize("00966501234567").digits, "966501234567");
  assert.strictEqual(wa.normalize("+971 50 123 4567").digits, "971501234567");
  assert.strictEqual(wa.normalize("0501234567").ok, false);
  assert.match(wa.normalize("0501234567").reason, /\+966/);
});
t("missing or invalid numbers are rejected with a reason", () => {
  for (const p of ["", null, "12345", "+91 12345 67890", "+966 12 345 6789", "abc"]) {
    const r = wa.normalize(p);
    assert.strictEqual(r.ok, false, String(p));
    assert.ok(r.reason.length > 5);
  }
});
t("wa.me link drops + and encodes the message", () => {
  const u = wa.url("919847011111", "Dear A & B,\nTotal: ₹1,000.00 #1?");
  assert.ok(u.startsWith("https://wa.me/919847011111?text="));
  assert.strictEqual(decodeURIComponent(u.split("?text=")[1]), "Dear A & B,\nTotal: ₹1,000.00 #1?");
  assert.ok(!/[\s&#]/.test(u.split("?text=")[1]));
});

const booking = {
  booking: { ref: "BK-26-00001", pnr: "QZ4M8P", customer_name: "RESHMA SURESH", travel_date: "2026-10-26", trip_type: "one_way", committed: 1 },
  calc: { customer_payable: 11096800, customer_credits: 0, customer_outstanding: 6096800 },
  segments: [{ airline: "IndiGo", flight_no: "6E 1407", origin: "COK", destination: "DXB", dep_at: "2026-10-26T04:10", direction: "outbound" }],
  passengers: [{ name: "ZARA SYNTHETIC", ticket_number: "312-1234567890" }],
  invoices: [],
};
const noPlaceholders = (s) => assert.ok(!/\{\{|\}\}|undefined|null|NaN/.test(s), s);

t("payment reminder uses real figures", () => {
  const m = wa.tpl.paymentReminder(booking, { due: "2026-10-25" });
  noPlaceholders(m);
  assert.match(m, /Dear RESHMA SURESH,/);
  assert.match(m, /Booking Reference: BK-26-00001 \(PNR QZ4M8P\)/);
  assert.match(m, /Total Amount: ₹1,10,968\.00/);
  assert.match(m, /Amount Paid: ₹50,000\.00/);
  assert.match(m, /Outstanding Balance: ₹60,968\.00/);
  assert.match(m, /Due Date: 25\/10\/2026/);
});
t("lines without data are left out", () => {
  const m = wa.tpl.paymentReminder(booking, {});
  assert.ok(!/Due Date/.test(m));
});
t("booking confirmation", () => {
  const m = wa.tpl.bookingConfirmation(booking);
  noPlaceholders(m);
  assert.match(m, /Route: COK – DXB/);
  assert.match(m, /Passenger: ZARA SYNTHETIC/);
  assert.match(m, /Payment Status: Partly paid/);
});
t("itinerary", () => {
  const m = wa.tpl.itinerary(booking);
  noPlaceholders(m);
  assert.match(m, /IndiGo 6E 1407/);
  assert.match(m, /Ticket 312-1234567890/);
});
t("invoice never claims a PDF unless the owner ticks it", () => {
  const inv = { number: "QT/INV/2026-27/0001", doc_type: "invoice", issue_date: "2026-10-21", total: 11096800, currency: "INR", status: "issued",
    doc: { customer: { name: "RESHMA" }, booking: { ref: "BK-26-00001", pnr: "QZ4M8P" } }, booking_calc: { received: 0, outstanding: 11096800, currency_note: "INR" } };
  const m = wa.tpl.invoice(inv, {});
  noPlaceholders(m);
  assert.ok(!/PDF/.test(m));
  assert.match(m, /Balance Due: ₹1,10,968\.00/);
  assert.match(m, /Payment Status: Payment pending/);
  assert.match(wa.tpl.invoice(inv, { pdf: true }), /PDF is attached/);
});
t("receipt", () => {
  const m = wa.tpl.receipt({ kind: "customer_receipt", txn_no: "RCT-000001", txn_date: "2026-10-21", amount: 5000000, currency: "INR", method: "upi", reference: "UTR9", customer_name: "RESHMA", allocations: [{ ref: "BK-26-00001" }] }, { outstanding: 6096800 });
  noPlaceholders(m);
  assert.match(m, /Amount Received: ₹50,000\.00/);
  assert.match(m, /Payment Method: UPI \(Ref UTR9\)/);
  assert.match(m, /Balance Outstanding: ₹60,968\.00/);
});
t("customer reminder lists open bookings", () => {
  const m = wa.tpl.customerReminder({ customer: { name: "RESHMA" }, balance: { outstanding: 6096800 },
    bookings: [{ ref: "BK-26-00001", pnr: "QZ4M8P", travel_date: "2026-10-26", customer_outstanding: 6096800 }, { ref: "BK-26-00002", customer_outstanding: 0 }] }, { due: "" });
  noPlaceholders(m);
  assert.match(m, /BK-26-00001 \(PNR QZ4M8P\), travel 26\/10\/2026: ₹60,968\.00/);
  assert.ok(!/BK-26-00002/.test(m));
});

// ---- ticket / payment messages built from GET /api/bookings/:id/ticket
const PD = { account_name: "DEMO TRAVELS", account_number: "50100012345678", ifsc: "ABCD0123456", branch: "MAIN ROAD", bank_name: "", upi_number: "9000000001", upi_apps: "Google Pay, PhonePe, BHIM UPI" };
const leg = (o) => Object.assign({ airline: "", flight_no: "", airline_pnr: "", origin_city: "", destination_city: "", dep_date: "", dep_time: "", arr_date: "", arr_time: "", cabin: "", baggage: "", layover: null }, o);
const td = (pay, status) => ({
  business: { name: "DEMO TRAVELS" },
  booking: { ref: "BK-26-00009", pnr: "K7Q2LM", status: status || "ticketed", status_label: status === "enquiry" ? "Enquiry - not confirmed" : "Ticketed", is_ticketed: (status || "ticketed") === "ticketed", is_confirmed: status !== "enquiry", is_cancelled: false },
  customer: { name: "FATHIMA RAHMAN", phone: "+966501234567" },
  passengers: [{ display_name: "Mrs FATHIMA RAHMAN", ticket_number: "065-2417545001" }, { display_name: "ZAYAN RAHMAN", ticket_number: "" }],
  journeys: [
    { label: "Onward journey", origin: "COK", origin_city: "Kochi", destination: "JED", destination_city: "Jeddah", legs: [
      leg({ airline: "IndiGo", flight_no: "6E 1407", origin: "COK", destination: "DXB", dep_date: "Mon, 02 Nov 2026", dep_time: "04:10", baggage: "30 kg", cabin: "Economy", layover: { airport: "DXB", city: "Dubai", duration: "2h 35m" } }),
      leg({ airline: "Emirates", flight_no: "EK 803", origin: "DXB", destination: "JED", arr_date: "Mon, 02 Nov 2026", arr_time: "11:05", baggage: "30 kg", cabin: "Economy" })] },
    { label: "Return journey", origin: "JED", origin_city: "Jeddah", destination: "COK", destination_city: "Kochi", legs: [
      leg({ airline: "Saudia", flight_no: "SV 756", origin: "JED", destination: "COK", dep_date: "Fri, 20 Nov 2026", dep_time: "23:30" })] }],
  payment: Object.assign({ committed: true, total: 13550000, received: 5000000, pending: 8550000 }, pay || {}),
  payment_details: PD,
});
t("ticket details message", () => {
  const m = wa.tpl.ticketDetails(td());
  noPlaceholders(m);
  for (const s of ["✈️ *DEMO TRAVELS — YOUR FLIGHT DETAILS*", "Dear FATHIMA RAHMAN 😊,", "🎫 Booking Reference: K7Q2LM", "*Onward journey*", "🛫 From: COK – Kochi",
    "🛬 To: JED – Jeddah", "📅 Departure: Mon, 02 Nov 2026 · 04:10", "📅 Arrival: Mon, 02 Nov 2026 · 11:05", "✈️ Airline: IndiGo, Emirates",
    "🔢 Flight Number: 6E 1407, EK 803", "🔁 Connection: DXB (Dubai) · 2h 35m layover", "*Return journey*", "🔢 Flight Number: SV 756",
    "👤 Passengers (2):", "Mrs FATHIMA RAHMAN (E-ticket 065-2417545001)", "• ZAYAN RAHMAN\n", "🧳 Baggage: 30 kg", "🎟️ Ticket Status: Ticketed",
    "We wish you a safe, comfortable, and wonderful journey! 🌍💙", "DEMO TRAVELS — Your Journey, Our Care."]) assert.ok(m.includes(s), s + "\n---\n" + m);
  assert.ok(!/Arrival: [^\n]*20 Nov/.test(m)); // the return arrival is not saved, so no arrival line is invented for it
  assert.strictEqual((m.match(/📅 Arrival/g) || []).length, 1);
  assert.ok(!/payment|50100012345678/i.test(m));
});
t("unconfirmed booking is never called a ticket", () => {
  const m = wa.tpl.ticketDetails(td(null, "enquiry"));
  assert.match(m, /Ticket Status: Enquiry - not confirmed ⚠️/);
  assert.match(m, /not confirmed yet/);
  assert.match(wa.tpl.ticketPdf(td(null, "enquiry"), {}), /YOUR ITINERARY/);
});
t("payment details message uses the exact DEMO TRAVELS bank and UPI details", () => {
  const m = wa.tpl.paymentDetails(td(), {});
  noPlaceholders(m);
  for (const s of ["💙 *DEMO TRAVELS — PAYMENT DETAILS*", "Thank you for booking with Demo Travels! ✈️", "🎫 Booking Reference: K7Q2LM",
    "💰 Total Amount: ₹1,35,500.00", "✅ Amount Received: ₹50,000.00", "⏳ Balance Pending: ₹85,500.00",
    "🏦 *BANK TRANSFER*\nAccount Name: DEMO TRAVELS\nAccount Number: 50100012345678\nIFSC Code: ABCD0123456\nBranch: MAIN ROAD",
    "📲 *UPI PAYMENT*\nUPI Number: 9000000001\nYou may use Google Pay, PhonePe, BHIM UPI, or another supported UPI application.",
    "Please verify the beneficiary details before confirming payment.", "Thank you for trusting DEMO TRAVELS! 💙✈️"]) assert.ok(m.includes(s), s + "\n---\n" + m);
  assert.ok(!/Bank:|Due Date/.test(m)); // empty bank name and no due date are left out
  assert.match(wa.tpl.paymentDetails(td(), { due: "2026-10-30" }), /📅 Due Date: 30\/10\/2026/);
  assert.ok(!/upi:\/\/|@/.test(m)); // no invented UPI ID or payment link
});
t("PDF message mentions the attachment only when ticked", () => {
  assert.ok(!/attached/.test(wa.tpl.ticketPdf(td(), {})));
  const m = wa.tpl.ticketPdf(td(), { pdf: true }, true);
  assert.match(m, /attached as a PDF 📎/);
  assert.match(m, /⏳ Balance Pending: ₹85,500.00/);
  assert.match(m, /Account Number: 50100012345678/);
  const paid = wa.tpl.ticketPdf(td({ received: 13550000, pending: 0 }), { pdf: true }, true);
  assert.match(paid, /Fully paid — thank you!/);
  assert.ok(!/50100012345678/.test(paid));
});
t("emoji and line breaks survive the wa.me link", () => {
  const m = wa.tpl.paymentDetails(td(), {});
  const u = wa.url("966501234567", m);
  assert.strictEqual(decodeURIComponent(u.split("?text=")[1]), m);
  assert.ok(!/[\s💙✈️]/.test(u));
});
t("customer reminder and invoice carry payment details from settings", () => {
  QT.state.meta.settings = Object.assign({}, QT.state.meta.settings, { pay_account_name: "DEMO TRAVELS", pay_account_number: "50100012345678", pay_ifsc: "ABCD0123456", pay_branch: "MAIN ROAD", pay_upi_number: "9000000001", pay_upi_apps: "Google Pay, PhonePe" });
  const r = wa.tpl.customerReminder({ customer: { name: "R" }, balance: { outstanding: 100 }, bookings: [{ ref: "QT-1", customer_outstanding: 100 }] }, {});
  assert.match(r, /IFSC Code: ABCD0123456/);
  const inv = { number: "QT/INV/1", doc_type: "invoice", issue_date: "2026-10-21", total: 100, currency: "INR", status: "issued", doc: { customer: { name: "R" }, booking: {} } };
  assert.match(wa.tpl.invoice(Object.assign({ booking_calc: { received: 0, outstanding: 100, currency_note: "INR" } }, inv), {}), /UPI Number: 9000000001/);
  assert.ok(!/UPI Number/.test(wa.tpl.invoice(Object.assign({ booking_calc: { received: 100, outstanding: 0, currency_note: "INR" } }, inv), {})));
});
console.log(n + " WhatsApp tests passed");
