/* WhatsApp: build a message from CRM records, preview it, copy it or open WhatsApp with it.
   Uses https://wa.me links only - nothing is ever sent without the owner pressing Send in WhatsApp.
   Kept separate so a WhatsApp Business Platform API sender can be added later behind QT.wa.send. */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  const wa = (QT.wa = {});

  // ---------------------------------------------------------------- phone numbers
  // Returns { ok, digits, display, reason }. digits = country code + number, no "+" (wa.me format).
  wa.normalize = function (raw) {
    const s = String(raw || "").trim();
    if (!s) return { ok: false, reason: "No phone number on record." };
    const all = s.replace(/\D/g, "");
    let d;
    if (/^\+/.test(s)) d = all;
    else if (all.startsWith("00")) d = all.slice(2);
    else if (all.length === 10 && /^[6-9]/.test(all)) d = "91" + all; // Indian mobile without code
    else if (all.length === 11 && /^0[6-9]/.test(all)) d = "91" + all.slice(1); // 0 98470 11111
    else if (all.length >= 11 && all.length <= 15 && !all.startsWith("0")) d = all; // already has a country code
    else if (/^0?5\d{8}$/.test(all)) return { ok: false, reason: "Gulf number without a country code. Add it, e.g. +966 for Saudi Arabia or +971 for the UAE." };
    else return { ok: false, reason: "“" + s + "” is not a valid WhatsApp number. Enter it with the country code, e.g. +91 98470 11111." };
    if (d.startsWith("91") && !/^91[6-9]\d{9}$/.test(d)) return { ok: false, reason: "Indian mobile numbers have 10 digits starting with 6–9 (+91 98470 11111)." };
    if (d.startsWith("966") && !/^9665\d{8}$/.test(d)) return { ok: false, reason: "Saudi mobile numbers look like +966 5X XXX XXXX." };
    if (d.length < 8 || d.length > 15) return { ok: false, reason: "A WhatsApp number has 8 to 15 digits including the country code." };
    const display = d.startsWith("91") ? "+91 " + d.slice(2, 7) + " " + d.slice(7) : d.startsWith("966") ? "+966 " + d.slice(3, 5) + " " + d.slice(5, 8) + " " + d.slice(8) : "+" + d;
    return { ok: true, digits: d, display };
  };
  wa.url = (digits, text) => "https://wa.me/" + (digits || "") + "?text=" + encodeURIComponent(text);

  wa.copy = async function (text) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (e) {
      const ta = el('<textarea style="position:fixed;top:-1000px;opacity:0"></textarea>');
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand("copy"); } catch (e2) { ok = false; }
      ta.remove();
      return ok;
    }
  };

  // ---------------------------------------------------------------- message helpers
  const biz = () => (QT.state.meta && QT.state.meta.settings) || {};
  const bizName = () => biz().business_name || "our agency";
  // "Label: value" or nothing when the value is empty, so no blank placeholders reach the customer
  const line = (label, value) => (value === null || value === undefined || value === "" ? null : label + ": " + value);
  const join = (parts) => parts.filter((p) => p !== null && p !== undefined && p !== false).join("\n").replace(/\n{3,}/g, "\n\n").trim();
  const signoff = () => {
    const b = biz();
    return [b.business_phone ? bizName() + " · " + b.business_phone : bizName(), b.business_email || null].filter(Boolean).join("\n");
  };
  const payLabel = (outstanding, paid) => (outstanding <= 0 ? "Fully paid" : paid > 0 ? "Partly paid" : "Payment pending");
  const routeOf = (segs, tripType) => {
    if (!segs || !segs.length) return null;
    const first = segs[0];
    const out = segs.filter((s) => s.direction !== "return");
    const last = (out[out.length - 1] || first);
    if (!first.origin && !last.destination) return null;
    return (first.origin || "?") + " – " + (last.destination || "?") + (tripType === "round_trip" ? " (round trip)" : "");
  };
  const paxNames = (pax) => (pax || []).map((p) => (p.title ? p.title + " " : "") + p.name).join(", ") || null;
  const dt = (s) => (s ? QT.date(s) + (String(s).length > 10 ? " " + QT.time(s) : "") : null);

  // Booking figures in the reporting currency: total billed (after credits), paid, outstanding
  function bookingFigures(d) {
    const c = d.calc;
    const total = (c.customer_payable || 0) - (c.customer_credits || 0);
    const outstanding = c.customer_outstanding || 0;
    return { total, outstanding, paid: total - outstanding };
  }

  // Agency bank / UPI details from Settings -> Payment details (same values as the ticket + payment PDF)
  wa.payDetails = function (pd) {
    if (pd) return pd;
    const s = biz();
    return { account_name: s.pay_account_name || "", account_number: s.pay_account_number || "", ifsc: s.pay_ifsc || "", branch: s.pay_branch || "",
      bank_name: s.pay_bank_name || "", upi_number: s.pay_upi_number || "", upi_apps: s.pay_upi_apps || "" };
  };
  wa.paymentBlock = function (pd) {
    pd = wa.payDetails(pd);
    const bank = [line("Account Name", pd.account_name), line("Account Number", pd.account_number), line("IFSC Code", pd.ifsc),
      line("Bank", pd.bank_name), line("Branch", pd.branch)].filter(Boolean);
    const apps = (pd.upi_apps || "").split(",").map((a) => a.trim()).filter(Boolean);
    const parts = [];
    if (bank.length) parts.push("🏦 *BANK TRANSFER*\n" + bank.join("\n"));
    if (pd.upi_number) parts.push("📲 *UPI PAYMENT*\nUPI Number: " + pd.upi_number + "\nYou may use " + (apps.length ? apps.join(", ") + ", or another" : "any") + " supported UPI application.");
    return parts.join("\n\n");
  };
  wa.hasPaymentDetails = (pd) => !!wa.paymentBlock(pd);
  const PAY_NOTE = "Please verify the beneficiary details before confirming payment. After completing the payment, kindly share your payment confirmation with us. 😊";
  const tagline = () => bizName().toUpperCase() + " — Your Journey, Our Care.";
  const money = (m) => QT.money(m);
  const when = (date, time) => [date, time].filter(Boolean).join(" · ") || null;
  const refLine = (b) => "🎫 Booking Reference: " + (b.pnr || b.ref);
  const statusText = (b) => b.status_label + (b.is_confirmed || b.is_cancelled ? "" : " ⚠️");
  // one block per journey (onward / return): route, times, flights, connections
  function journeyLines(j) {
    const first = j.legs[0], last = j.legs[j.legs.length - 1];
    const place = (code, c) => code + (c ? " – " + c : "");
    const airlines = [...new Set(j.legs.map((l) => l.airline).filter(Boolean))].join(", ");
    const flights = j.legs.map((l) => l.flight_no).filter(Boolean).join(", ");
    const out = [
      "🛫 From: " + place(j.origin, j.origin_city),
      "🛬 To: " + place(j.destination, j.destination_city),
      line("📅 Departure", when(first.dep_date, first.dep_time)),
      line("📅 Arrival", when(last.arr_date, last.arr_time)),
      line("✈️ Airline", airlines),
      line("🔢 Flight Number", flights),
    ];
    j.legs.forEach((l) => { if (l.layover) out.push("🔁 Connection: " + l.layover.airport + (l.layover.city ? " (" + l.layover.city + ")" : "") + (l.layover.duration ? " · " + l.layover.duration + " layover" : "")); });
    return out.filter(Boolean);
  }
  function itineraryLines(td) {
    if (!td.journeys.length) return [];
    if (td.journeys.length === 1) return journeyLines(td.journeys[0]);
    const out = [];
    td.journeys.forEach((j, i) => { if (i) out.push(""); out.push("*" + j.label + "*"); out.push(...journeyLines(j)); });
    return out;
  }
  const paxLines = (td) => {
    const p = td.passengers;
    if (!p.length) return null;
    const one = (x) => x.display_name + (x.ticket_number ? " (E-ticket " + x.ticket_number + ")" : "");
    return "👤 Passenger" + (p.length > 1 ? "s (" + p.length + "):\n" + p.map((x) => "   • " + one(x)).join("\n") : ": " + one(p[0]));
  };
  const bagLine = (td) => {
    const bags = [...new Set(td.journeys.flatMap((j) => j.legs.map((l) => l.baggage)).filter(Boolean))];
    return bags.length ? "🧳 Baggage: " + bags.join(" / ") : null;
  };
  const cabinLine = (td) => {
    const c = [...new Set(td.journeys.flatMap((j) => j.legs.map((l) => l.cabin)).filter(Boolean))];
    return c.length ? "💺 Cabin: " + c.join(" / ") : null;
  };
  function paymentSummary(td) {
    const p = td.payment;
    return [line("💰 Total Amount", money(p.total)), line("✅ Amount Received", money(p.received)), line("⏳ Balance Pending", money(p.pending))];
  }

  // ---------------------------------------------------------------- templates
  wa.tpl = {
    // ticket details as text (from GET /api/bookings/:id/ticket)
    ticketDetails(td) {
      const b = td.booking;
      return join([
        "✈️ *" + bizName().toUpperCase() + " — YOUR FLIGHT DETAILS*", "",
        "Dear " + td.customer.name + " 😊,", "",
        "Thank you for choosing " + bizName() + "! 💙", "",
        refLine(b),
        ...itineraryLines(td),
        paxLines(td), bagLine(td), cabinLine(td),
        "🎟️ Ticket Status: " + statusText(b),
        !b.is_confirmed && !b.is_cancelled ? "\nPlease note: this booking is not confirmed yet." : null,
        b.is_cancelled ? "\nPlease note: this booking has been cancelled and is not valid for travel." : null, "",
        "We wish you a safe, comfortable, and wonderful journey! 🌍💙", "",
        tagline(),
        biz().business_phone ? "📞 " + biz().business_phone : null,
      ]);
    },
    // message to send with the ticket PDF (the PDF itself is attached by hand in WhatsApp)
    ticketPdf(td, v, withPayment) {
      const b = td.booking, p = td.payment;
      const route = (j) => j.origin + " → " + j.destination + (j.legs[0].dep_date ? " · " + when(j.legs[0].dep_date, j.legs[0].dep_time) : "");
      return join([
        "✈️ *" + bizName().toUpperCase() + " — YOUR " + (b.is_ticketed ? "E-TICKET" : b.is_confirmed ? "BOOKING CONFIRMATION" : "ITINERARY") + "*", "",
        "Dear " + td.customer.name + " 😊,", "",
        v && v.pdf ? "Please find your " + (b.is_ticketed ? "flight ticket" : "booking details") + (withPayment ? " with payment details" : "") + " attached as a PDF 📎" : "Here are your " + (b.is_ticketed ? "flight ticket" : "booking") + " details.", "",
        refLine(b),
        ...td.journeys.map((j) => "🛫 " + route(j)),
        td.passengers.length ? "👤 Passenger" + (td.passengers.length > 1 ? "s: " + td.passengers.length : ": " + td.passengers[0].display_name) : null,
        "🎟️ Ticket Status: " + statusText(b),
        withPayment && p.committed ? "" : null,
        ...(withPayment && p.committed ? paymentSummary(td) : []),
        withPayment && p.pending > 0 && wa.hasPaymentDetails(td.payment_details) ? "\n" + wa.paymentBlock(td.payment_details) + "\n\n" + PAY_NOTE : null,
        withPayment && p.committed && p.pending <= 0 ? "✅ Fully paid — thank you!" : null, "",
        "Kindly check the passenger names and travel dates, and let us know if anything needs correcting.", "",
        "We wish you a safe and wonderful journey! 🌍💙", "",
        tagline(),
      ]);
    },
    // payment reminder with bank + UPI details; only for bookings with a real pending balance
    paymentDetails(td, v) {
      const b = td.booking;
      return join([
        "💙 *" + bizName().toUpperCase() + " — PAYMENT DETAILS*", "",
        "Dear " + td.customer.name + " 😊,", "",
        "Thank you for booking with " + bizName() + "! ✈️", "",
        refLine(b),
        td.journeys.length ? "🛫 " + td.journeys.map((j) => j.origin + " → " + j.destination).join(" · ") + (td.journeys[0].legs[0].dep_date ? " (" + td.journeys[0].legs[0].dep_date + ")" : "") : null,
        ...paymentSummary(td),
        v && v.due ? line("📅 Due Date", QT.date(v.due)) : null, "",
        wa.paymentBlock(td.payment_details), "",
        PAY_NOTE, "",
        "Thank you for trusting " + bizName().toUpperCase() + "! 💙✈️",
      ]);
    },
    bookingConfirmation(d) {
      const b = d.booking, f = bookingFigures(d);
      return join([
        "Dear " + b.customer_name + ",", "",
        "Your booking with " + bizName() + " has been recorded successfully.", "",
        line("Booking Reference", b.ref), line("PNR", b.pnr),
        line("Route", routeOf(d.segments, b.trip_type)),
        line("Travel Date", b.travel_date ? QT.date(b.travel_date) : null),
        line("Return Date", b.return_date ? QT.date(b.return_date) : null),
        line(d.passengers.length > 1 ? "Passengers" : "Passenger", paxNames(d.passengers)),
        line("Amount", b.committed ? QT.money(f.total) : null),
        line("Payment Status", b.committed ? payLabel(f.outstanding, f.paid) : null),
        b.committed && f.outstanding > 0 && f.paid > 0 ? line("Balance Due", QT.money(f.outstanding)) : null, "",
        "For any assistance, please contact " + bizName() + ".",
        "Thank you for choosing us!", "", signoff(),
      ]);
    },
    paymentReminder(d, v) {
      const b = d.booking, f = bookingFigures(d);
      return join([
        "Dear " + b.customer_name + ",", "",
        "Greetings from " + bizName() + "!", "",
        "This is a friendly reminder regarding your pending payment.", "",
        line("Booking Reference", b.ref + (b.pnr ? " (PNR " + b.pnr + ")" : "")),
        line("Route", routeOf(d.segments, b.trip_type)),
        line("Travel Date", b.travel_date ? QT.date(b.travel_date) : null),
        line("Total Amount", QT.money(f.total)),
        line("Amount Paid", QT.money(f.paid)),
        line("Outstanding Balance", QT.money(Math.max(f.outstanding, 0))),
        line("Due Date", v && v.due ? QT.date(v.due) : null), "",
        "Please arrange the pending payment at your convenience.", "",
        "Thank you for choosing " + bizName() + ".", "", signoff(),
      ]);
    },
    itinerary(d) {
      const b = d.booking;
      const segs = d.segments.map((s) => "• " + [[s.airline, s.flight_no].filter(Boolean).join(" "), (s.origin || "?") + " → " + (s.destination || "?")].filter(Boolean).join("  ")
        + (s.dep_at ? "\n   Departs " + dt(s.dep_at) : "") + (s.arr_at ? " · Arrives " + dt(s.arr_at) : "") + (s.baggage || b.baggage ? "\n   Baggage " + (s.baggage || b.baggage) : ""));
      const tickets = d.passengers.map((p) => "• " + (p.title ? p.title + " " : "") + p.name + (p.ticket_number ? " – Ticket " + p.ticket_number : ""));
      return join([
        "Dear " + b.customer_name + ",", "",
        "Here are your travel details from " + bizName() + ".", "",
        line("Booking Reference", b.ref), line("PNR", b.pnr), "",
        segs.length ? "*Flights*\n" + segs.join("\n") : null, "",
        tickets.length ? "*Passengers*\n" + tickets.join("\n") : null, "",
        "Please carry a valid passport / ID and reach the airport at least 3 hours before an international departure (2 hours for domestic). Airline rules apply.", "",
        "Wishing you a pleasant journey!", "", signoff(),
      ]);
    },
    invoice(inv, v) {
      const doc = inv.doc || {}, cust = doc.customer || {}, bk = doc.booking || {};
      const cn = inv.doc_type === "credit_note";
      const bc = inv.booking_calc;
      const base = bc && bc.currency_note;
      const outstanding = bc ? bc.outstanding : null;
      return join([
        "Dear " + (cust.name || "Customer") + ",", "",
        cn ? "Please find the details of your credit note from " + bizName() + "." : "Please find the details of your invoice from " + bizName() + ".", "",
        line(cn ? "Credit Note No" : "Invoice No", inv.number),
        line("Date", QT.date(inv.issue_date)),
        line("Booking Reference", bk.ref), line("PNR", bk.pnr),
        line("Route", routeOf(bk.segments, bk.trip_type)),
        line(cn ? "Credit Amount" : "Invoice Amount", QT.money(inv.total, inv.currency)),
        !cn && bc ? line("Amount Paid", QT.money(bc.received, base)) : null,
        !cn && bc ? line("Balance Due", QT.money(Math.max(outstanding, 0), base)) : null,
        !cn && bc ? line("Payment Status", payLabel(outstanding, bc.received)) : null,
        !cn && v && v.due && outstanding > 0 ? line("Due Date", QT.date(v.due)) : null,
        inv.status === "cancelled" ? "Note: this document has been cancelled." : null, "",
        !cn && bc && outstanding > 0 && inv.status !== "cancelled" && wa.hasPaymentDetails() ? wa.paymentBlock() + "\n\n" + PAY_NOTE : null, "",
        v && v.pdf ? "The " + (cn ? "credit note" : "invoice") + " PDF is attached with this message." : null, "",
        "Thank you for choosing " + bizName() + ".", "", signoff(),
      ]);
    },
    receipt(t, extra) {
      const refund = t.kind === "customer_refund";
      return join([
        "Dear " + (t.customer_name || "Customer") + ",", "",
        refund ? "Your refund has been processed by " + bizName() + "." : "Thank you for your payment. We have received the following:", "",
        line(refund ? "Refund Voucher No" : "Receipt No", t.txn_no),
        line("Date", QT.date(t.txn_date)),
        line(refund ? "Amount Refunded" : "Amount Received", QT.money(t.amount, t.currency)),
        line("Payment Method", (QT.labels.method[t.method] || t.method) + (t.reference ? " (Ref " + t.reference + ")" : "")),
        line("Against Booking", t.allocations && t.allocations.length ? t.allocations.map((a) => a.ref).join(", ") : t.booking_ref || null),
        extra && extra.outstanding !== undefined ? line("Balance Outstanding", QT.money(Math.max(extra.outstanding, 0))) : null,
        t.reversed_by ? "Note: this entry has been reversed." : null, "",
        "Thank you for choosing " + bizName() + ".", "", signoff(),
      ]);
    },
    customerReminder(cd, v) {
      const c = cd.customer, bal = cd.balance;
      const open = cd.bookings.filter((b) => b.customer_outstanding > 0);
      const list = open.map((b) => "• " + b.ref + (b.pnr ? " (PNR " + b.pnr + ")" : "") + (b.travel_date ? ", travel " + QT.date(b.travel_date) : "") + ": " + QT.money(b.customer_outstanding));
      return join([
        "Dear " + c.name + ",", "",
        "Greetings from " + bizName() + "!", "",
        "This is a friendly reminder regarding your pending payment" + (open.length > 1 ? "s" : "") + ".", "",
        list.length ? list.join("\n") : null, "",
        line("Total Outstanding", QT.money(Math.max(bal.outstanding, 0))),
        line("Due Date", v && v.due ? QT.date(v.due) : null), "",
        "Please arrange the pending payment at your convenience.", "",
        wa.hasPaymentDetails() ? wa.paymentBlock() + "\n\n" + PAY_NOTE : null, "",
        "Thank you for choosing " + bizName() + ".", "", signoff(),
      ]);
    },
    greeting(name) {
      return join(["Dear " + name + ",", "", "Greetings from " + bizName() + "!", "", "", "", signoff()]);
    },
  };

  // ---------------------------------------------------------------- composer
  // opts: { title, name, phone, templates: [{ key, label, build(vals), fields?: [{name,label,type,value}] }], pick, doc?: { label, onSave } }
  wa.compose = function (opts) {
    const tpls = opts.templates;
    let cur = tpls.find((t) => t.key === opts.pick) || tpls[0];
    let edited = false;
    const body = el(h`<div class="stack wa-compose" style="gap:14px">
      <div class="form-grid">
        <div class="f s6"><label>To</label><input class="inp" readonly value="${opts.name || ""}"></div>
        <div class="f s6"><label>WhatsApp number</label><input class="inp" name="wa_phone" value="${opts.phone || ""}" placeholder="+91 98470 11111" autocomplete="off"><span class="hint" data-phone-state></span></div>
        ${tpls.length > 1 ? QT.raw('<div class="f s6"><label>Message</label><select class="inp" name="wa_tpl">' + tpls.map((t) => QT.opt(t.key, t.label, cur.key)).join("") + "</select></div>") : ""}
        <div class="f s6" data-fields></div>
      </div>
      <div data-phone-warn></div>
      <div class="f"><label>Preview — edit before sending if you like</label><textarea class="inp wa-text" name="wa_text" rows="15" spellcheck="true"></textarea><span class="hint" data-count></span></div>
      ${opts.attachment ? QT.raw('<div class="wa-steps" data-attach></div><label class="check small"><input type="checkbox" name="wa_pdf" checked> Say “PDF attached” in the message — remember to attach it before you press Send</label>') : ""}
      ${opts.notice ? QT.raw(opts.notice) : ""}
      ${opts.doc ? QT.raw('<div class="alert info small">' + icon("file") + "<div><b>Attaching the PDF:</b> WhatsApp links can carry text only, so the PDF cannot be attached automatically. Click <b>Save PDF</b>, choose <i>Save as PDF</i>, then in the WhatsApp chat use 📎 → <i>Document</i> to attach the saved file before you press Send." + '<div class="row" style="margin-top:8px;gap:12px"><button type="button" class="btn xs" data-save-pdf>' + icon("print") + "Save PDF</button>" + '<label class="check small"><input type="checkbox" name="wa_pdf"> Add “PDF attached” line to the message</label></div></div></div>') : ""}
      <div class="small muted">${QT.raw(icon("lock").replace("<svg", '<svg style="width:13px;height:13px;vertical-align:-2px"'))} WhatsApp opens with this message filled in. Nothing is sent until you press <b>Send</b> in WhatsApp.</div>
    </div>`.html);

    const phoneIn = $("[name=wa_phone]", body);
    const text = $("[name=wa_text]", body);
    const fieldsBox = $("[data-fields]", body);
    let phone = wa.normalize(phoneIn.value);

    function vals() {
      const v = {};
      $$("[data-v]", fieldsBox).forEach((i) => { v[i.dataset.v] = i.value; });
      const pdf = $("[name=wa_pdf]", body);
      if (pdf) v.pdf = pdf.checked;
      return v;
    }
    function drawFields() {
      fieldsBox.innerHTML = (cur.fields || []).map((f) => '<label>' + esc(f.label) + '</label><input class="inp" data-v="' + esc(f.name) + '" type="' + (f.type || "text") + '" value="' + esc(f.value || "") + '">').join("");
      fieldsBox.classList.toggle("hidden", !(cur.fields || []).length);
      $$("[data-v]", fieldsBox).forEach((i) => i.addEventListener("input", () => regen(true)));
    }
    async function regen(fromField) {
      if (edited && !fromField) return;
      if (edited && fromField && !(await QT.confirm({ title: "Replace your edits?", message: "Changing this field rebuilds the message and discards the edits you made to the text.", ok: "Rebuild message" }))) return;
      edited = false;
      text.value = await cur.build(vals());
      count();
    }
    function count() {
      const n = text.value.length;
      const long = wa.url(phone.digits, text.value).length > 6000;
      $("[data-count]", body).textContent = n + " characters" + (long ? " — very long; WhatsApp may cut it off. Use Copy message instead." : "");
    }
    function phoneState() {
      phone = wa.normalize(phoneIn.value);
      $("[data-phone-state]", body).innerHTML = phone.ok ? '<span class="pos">✓ Opens chat with ' + esc(phone.display) + "</span>" : "";
      phoneIn.classList.toggle("err", !phone.ok && !!phoneIn.value.trim());
      $("[data-phone-warn]", body).innerHTML = phone.ok ? "" : '<div class="alert warn small">' + icon("alert") + "<div><b>No valid WhatsApp number.</b> " + esc(phone.reason) + " Type the number above with its country code (+91, +966, +971…), or use <b>Copy message</b> and paste it into the chat yourself.</div></div>";
    }
    phoneIn.addEventListener("input", phoneState);
    text.addEventListener("input", () => { edited = true; count(); });
    if ($("[name=wa_tpl]", body)) $("[name=wa_tpl]", body).addEventListener("change", async (e) => {
      if (edited && !(await QT.confirm({ title: "Switch message?", message: "Your edits to the current text will be replaced.", ok: "Switch" }))) { e.target.value = cur.key; return; }
      cur = tpls.find((t) => t.key === e.target.value);
      edited = false; drawFields(); regen();
    });
    if ($("[name=wa_pdf]", body)) $("[name=wa_pdf]", body).addEventListener("change", () => regen(true));
    if ($("[data-save-pdf]", body)) $("[data-save-pdf]", body).onclick = () => opts.doc.onSave();

    const att = opts.attachment;
    function drawAttachment(state) {
      const box = $("[data-attach]", body);
      if (!box) return;
      box.innerHTML = h`<div class="wa-step done"><span class="n">1</span><div class="grow"><b>PDF saved on this Mac</b>
          <div class="wa-file">${QT.raw(icon("file"))}<span>${att.name}</span></div><div class="small muted">Documents › Travel Agency CRM › Tickets</div>
          <div class="row" style="gap:6px;margin-top:6px"><button type="button" class="btn xs" data-reveal>${QT.raw(icon("search"))}Show in Finder</button><button type="button" class="btn xs" data-open-pdf>${QT.raw(icon("file"))}Open PDF</button></div></div></div>
        <div class="wa-step ${state === "opened" ? "done" : ""}"><span class="n">2</span><div class="grow"><b>Open WhatsApp</b> with the message below${state === "opened" ? " — opened" : ""}</div></div>
        <div class="wa-step"><span class="n">3</span><div class="grow"><b>Attach the PDF in WhatsApp</b>, then press Send. Drag the file from the Finder window into the chat, or click 📎 → Document and pick <b>${att.name}</b>.</div></div>`.html;
      $("[data-reveal]", box).onclick = () => att.reveal();
      $("[data-open-pdf]", box).onclick = () => att.open();
    }
    drawAttachment();
    const leftover = () => /\{\{[^}]*\}\}/.test(text.value);
    QT.modal({
      title: opts.title || "WhatsApp message", size: "lg", body, noFocus: true,
      actions: [
        { label: "Close" },
        { label: "Copy message", icon: "copy", onClick: async () => {
          if (!text.value.trim()) { QT.toast("The message is empty", "err"); return false; }
          if (await wa.copy(text.value)) QT.toast("Message copied — paste it into WhatsApp");
          else { text.focus(); text.select(); QT.toast("Could not copy automatically. The text is selected — press ⌘C to copy it.", "err"); }
          return false;
        } },
        { label: "Open WhatsApp", icon: "whatsapp", cls: "primary wa", onClick: () => {
          phoneState();
          if (!text.value.trim()) { QT.toast("The message is empty", "err"); return false; }
          if (leftover()) { QT.toast("The message still contains a {{placeholder}}. Replace it first.", "err"); return false; }
          if (!phone.ok) { QT.toast("Enter a valid WhatsApp number first, or use Copy message", "err"); phoneIn.focus(); return false; }
          window.open(wa.url(phone.digits, text.value), "_blank", "noopener");
          if (att) {
            drawAttachment("opened");
            setTimeout(() => att.reveal(true), 800); // put the PDF next to WhatsApp, ready to drag in
            QT.toast("WhatsApp opened for " + phone.display + ". Attach " + att.name + ", then press Send there.");
          } else QT.toast("WhatsApp opened for " + phone.display + ". Check the message, then press Send there.");
          return false;
        } },
      ],
    });
    drawFields();
    phoneState();
    regen();
  };

  // ---------------------------------------------------------------- entry points used by the screens
  const futureOr = (d) => (d && d >= QT.today() ? d : QT.today());

  wa.forBooking = function (d, pick) {
    const b = d.booking;
    const inv = d.invoices.find((i) => i.doc_type === "invoice" && i.status === "issued");
    const tpls = [
      { key: "confirmation", label: "Booking confirmation", build: () => wa.tpl.bookingConfirmation(d) },
      { key: "reminder", label: "Payment reminder", build: async (v) => wa.tpl.paymentDetails(await QT.get("/api/bookings/" + b.id + "/ticket"), v), fields: [{ name: "due", label: "Due date (optional)", type: "date", value: "" }] },
      { key: "itinerary", label: "Itinerary & ticket details", build: () => wa.tpl.itinerary(d) },
      { key: "greeting", label: "Blank message", build: () => wa.tpl.greeting(b.customer_name) },
    ];
    const owes = d.calc.customer_outstanding > 0 && b.committed;
    if (!owes) tpls.splice(1, 1); // never offer a pending-payment reminder for a booking with nothing to pay
    if (inv) tpls.splice(owes ? 2 : 1, 0, { key: "invoice", label: "Invoice " + inv.number, build: async (v) => wa.tpl.invoice(await QT.get("/api/invoices/" + inv.id), v), fields: [{ name: "due", label: "Due date", type: "date", value: futureOr(b.travel_date) }] });
    wa.compose({ title: "WhatsApp · " + b.ref, name: b.customer_name, phone: d.customer && (d.customer.phone || d.customer.alt_phone), templates: tpls,
      pick: pick || (owes ? "reminder" : "confirmation") });
  };

  // ---------------------------------------------------------------- booking WhatsApp menu
  const ticketOf = (id) => QT.get("/api/bookings/" + id + "/ticket");
  const phoneOf = (td) => td.customer.phone || td.customer.alt_phone;
  const pendingOK = (td) => {
    if (!td.payment.committed) { QT.toast("This booking is not confirmed or billed yet, so there is no payment to request.", "err"); return false; }
    if (td.payment.pending <= 0) { QT.toast("This booking is fully paid — no payment reminder is needed.", "err"); return false; }
    return true;
  };
  // save the PDF on this Mac (Documents › Travel Agency CRM › Tickets); action "open" = Preview, "reveal" = Finder
  wa.savePdf = async function (bookingId, variant, action) {
    const r = await QT.post("/api/bookings/" + bookingId + "/ticket-file", { variant, action: action || "" });
    if (action && !r.opened) await QT.download("/api/bookings/" + bookingId + "/ticket.pdf?variant=" + variant, r.name);
    return r;
  };
  wa.printTicket = async function (bookingId, variant) {
    try {
      const r = await wa.savePdf(bookingId, variant, "open");
      QT.toast(r.opened ? "PDF saved as " + r.name + " and opened in Preview — press ⌘P to print." : "PDF downloaded: " + r.name);
    } catch (e) { QT.fail(e); }
  };
  wa.ticketText = async function (id) {
    const td = await ticketOf(id);
    wa.compose({ title: "WhatsApp ticket details · " + (td.booking.pnr || td.booking.ref), name: td.customer.name, phone: phoneOf(td),
      templates: [{ key: "ticket", label: "Ticket details", build: () => wa.tpl.ticketDetails(td) }] });
  };
  wa.paymentReminder = async function (id) {
    const td = await ticketOf(id);
    if (!pendingOK(td)) return;
    const missing = wa.hasPaymentDetails(td.payment_details) ? "" : '<div class="alert warn small">' + icon("alert") + "<div>Bank and UPI details are not set up. Add them in Settings → Payment details.</div></div>";
    wa.compose({ title: "WhatsApp payment reminder · " + (td.booking.pnr || td.booking.ref), name: td.customer.name, phone: phoneOf(td), notice: missing,
      templates: [{ key: "pay", label: "Payment details", build: (v) => wa.tpl.paymentDetails(td, v), fields: [{ name: "due", label: "Due date (optional)", type: "date", value: "" }] }] });
  };
  wa.ticketPdf = async function (id, withPayment) {
    const variant = withPayment ? "payment" : "ticket";
    let td, file;
    try {
      td = await ticketOf(id);
      file = await wa.savePdf(id, variant, "");
    } catch (e) { QT.fail(e); return; }
    QT.toast("PDF generated and saved: " + file.name);
    const attachment = { name: file.name,
      reveal: async (quiet) => { try { const r = await wa.savePdf(id, variant, "reveal"); if (!quiet) QT.toast(r.opened ? "Showing " + r.name + " in Finder" : "PDF downloaded: " + r.name); } catch (e) { QT.fail(e); } },
      open: async () => { try { const r = await wa.savePdf(id, variant, "open"); QT.toast(r.opened ? "Opened " + r.name + " in Preview" : "PDF downloaded: " + r.name); } catch (e) { QT.fail(e); } } };
    wa.compose({ title: "WhatsApp " + (withPayment ? "ticket + payment PDF" : "flight ticket PDF") + " · " + (td.booking.pnr || td.booking.ref),
      name: td.customer.name, phone: phoneOf(td), attachment,
      templates: [{ key: "pdf", label: "Message with the PDF", build: (v) => wa.tpl.ticketPdf(td, v, withPayment) }] });
  };
  wa.bookingMenu = function (btn, d) {
    const id = d.booking.id;
    const run = (fn) => () => Promise.resolve(fn()).catch(QT.fail);
    QT.dropdown(btn, [
      { label: "WhatsApp ticket details — text", icon: "whatsapp", onClick: run(() => wa.ticketText(id)) },
      { label: "WhatsApp flight ticket — PDF", icon: "file", onClick: run(() => wa.ticketPdf(id, false)) },
      { label: "WhatsApp payment reminder", icon: "coins", onClick: run(() => wa.paymentReminder(id)) },
      { label: "WhatsApp ticket + payment details", icon: "invoice", onClick: run(() => wa.ticketPdf(id, true)) },
      "-",
      { label: "Download / print ticket PDF", icon: "print", onClick: run(() => wa.printTicket(id, "ticket")) },
      { label: "Download ticket + payment PDF", icon: "download", onClick: run(() => wa.printTicket(id, "payment")) },
      "-",
      { label: "Other messages (confirmation, invoice…)", icon: "more", onClick: () => wa.forBooking(d) },
    ]);
  };

  wa.forInvoice = async function (inv, onSavePdf) {
    const cust = (inv.doc && inv.doc.customer) || {};
    let phone = cust.phone;
    try { if (inv.customer_id) { const c = await QT.get("/api/customers/" + inv.customer_id); phone = c.customer.phone || c.customer.alt_phone || phone; } } catch (e) { /* use the copy on the invoice */ }
    wa.compose({ title: "WhatsApp · " + inv.number, name: cust.name, phone,
      templates: [{ key: "invoice", label: inv.doc_type === "credit_note" ? "Credit note" : "Invoice", build: (v) => wa.tpl.invoice(inv, v),
        fields: inv.doc_type === "invoice" ? [{ name: "due", label: "Due date", type: "date", value: futureOr((inv.doc && inv.doc.booking && inv.doc.booking.travel_date) || "") }] : [] }],
      doc: { onSave: onSavePdf } });
  };

  wa.forTransaction = async function (t, onSavePdf) {
    let cd = null;
    try { cd = await QT.get("/api/customers/" + t.customer_id); } catch (e) { QT.fail(e); return; }
    wa.compose({ title: "WhatsApp · " + t.txn_no, name: t.customer_name, phone: cd.customer.phone || cd.customer.alt_phone,
      templates: [{ key: "receipt", label: t.kind === "customer_refund" ? "Refund confirmation" : "Payment receipt", build: () => wa.tpl.receipt(t, { outstanding: cd.balance.outstanding }) }],
      doc: onSavePdf ? { onSave: onSavePdf } : null });
  };

  wa.forCustomer = function (cd) {
    const c = cd.customer;
    const tpls = [{ key: "greeting", label: "Blank message", build: () => wa.tpl.greeting(c.name) }];
    if (cd.balance.outstanding > 0) tpls.unshift({ key: "reminder", label: "Payment reminder (all bookings)", build: (v) => wa.tpl.customerReminder(cd, v), fields: [{ name: "due", label: "Due date", type: "date", value: QT.today() }] });
    wa.compose({ title: "WhatsApp · " + c.name, name: c.name, phone: c.phone || c.alt_phone, templates: tpls });
  };
})();
