/* AirZelo - company profile, white-label branding, document formats and the first-run setup wizard */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  const SWATCHES = ["#1B3FCC", "#0E7C66", "#7A2E8E", "#C2410C", "#B91C1C", "#0F766E", "#334155", "#A16207"];
  const COUNTRIES = ["India", "United Arab Emirates", "Saudi Arabia", "Qatar", "Oman", "Kuwait", "Bahrain", "United Kingdom", "United States", "Other"];

  // ---------------------------------------------------------------- shared form pieces
  QT.profileFields = (s) => h`
    ${QT.field({ name: "business_name", label: "Display / trading name", span: 6, value: s.business_name, req: true, hint: "Shown in the app and on documents" })}
    ${QT.field({ name: "legal_name", label: "Legal company name", span: 6, value: s.legal_name, placeholder: "e.g. Sunrise Travels Private Limited" })}
    ${QT.field({ name: "business_tagline", label: "Tagline (optional)", span: 12, value: s.business_tagline })}
    ${QT.field({ name: "business_address", label: "Business address", type: "textarea", span: 12, value: s.business_address, rows: 2 })}
    ${QT.field({ name: "business_country", label: "Country", type: "select", span: 4, opts: QT.options(COUNTRIES.includes(s.business_country) || !s.business_country ? COUNTRIES : [s.business_country].concat(COUNTRIES), s.business_country || "India") })}
    ${QT.field({ name: "business_state", label: "State / region (place of supply)", span: 4, value: s.business_state })}
    ${QT.field({ name: "business_phone", label: "Phone", span: 4, value: s.business_phone, placeholder: "+91 90000 00000" })}
    ${QT.field({ name: "business_email", label: "Email", type: "email", span: 6, value: s.business_email })}
    ${QT.field({ name: "business_website", label: "Website", span: 6, value: s.business_website, placeholder: "www.example.com" })}
    ${QT.field({ name: "business_gstin", label: "GSTIN / tax registration no.", span: 6, value: s.business_gstin, cls: "upper", hint: "India: 15-character GSTIN. Leave empty if not registered." })}
    ${QT.field({ name: "business_registration", label: "Other registrations (IATA, licence, CIN…)", type: "textarea", span: 6, value: s.business_registration, rows: 2 })}`;

  QT.brandFields = (s) => h`
    <div class="f s12"><label>Brand colour</label><div class="swatch-row">
      ${QT.raw(SWATCHES.map((c) => '<button type="button" class="swatch' + (c.toLowerCase() === String(s.brand_color || "").toLowerCase() ? " on" : "") + '" data-swatch="' + c + '" style="background:' + c + '" aria-label="' + c + '"></button>').join(""))}
      <input class="inp" name="brand_color" value="${s.brand_color || "#1B3FCC"}" style="width:120px" maxlength="7"><span class="hint">Used for the app accent, ticket PDFs and documents</span></div></div>`;

  QT.docFields = (s) => h`
    ${QT.field({ name: "invoice_prefix", label: "Invoice prefix", span: 3, value: s.invoice_prefix, hint: "e.g. " + (s.invoice_prefix || "INV/") + "2026-27/0001" })}
    ${QT.field({ name: "credit_note_prefix", label: "Credit-note prefix", span: 3, value: s.credit_note_prefix })}
    ${QT.field({ name: "receipt_prefix", label: "Receipt prefix", span: 3, value: s.receipt_prefix, hint: "e.g. " + (s.receipt_prefix || "RCT") + "-000001" })}
    ${QT.field({ name: "booking_prefix", label: "Booking ref prefix", span: 3, value: s.booking_prefix, hint: "e.g. " + (s.booking_prefix || "BK-") + "26-00001" })}
    ${QT.field({ name: "date_format", label: "Date format", type: "select", span: 4, opts: QT.options([["DD MMM YYYY", "22 Oct 2026"], ["DD/MM/YYYY", "22/10/2026"], ["MM/DD/YYYY", "10/22/2026"], ["YYYY-MM-DD", "2026-10-22"]], s.date_format) })}
    ${QT.field({ name: "currency_display", label: "Currency display", type: "select", span: 4, opts: QT.options([["symbol", "Symbol (₹ 1,000.00)"], ["code", "Code (INR 1,000.00)"]], s.currency_display) })}
    ${QT.field({ name: "report_default_period", label: "Default report period", type: "select", span: 4, opts: QT.options([["month", "This month"], ["fy", "Financial year"], ["year", "Calendar year"], ["week", "This week"], ["today", "Today"]], s.report_default_period) })}
    ${QT.field({ name: "invoice_terms", label: "Invoice terms", type: "textarea", span: 12, value: s.invoice_terms, rows: 2 })}
    ${QT.field({ name: "invoice_footer", label: "Invoice footer", span: 6, value: s.invoice_footer })}
    ${QT.field({ name: "receipt_footer", label: "Receipt footer", span: 6, value: s.receipt_footer })}
    <div class="f s12"><div class="alert info small">${QT.raw(icon("info"))}<div>Changes apply to <b>new</b> documents. Issued invoices and credit notes keep the company details, numbers and terms they were issued with, and recorded money is never changed by these settings.</div></div></div>`;

  QT.bindBrandFields = function (root) {
    const inp = $("[name=brand_color]", root);
    if (!inp) return;
    $$("[data-swatch]", root).forEach((b) => { b.onclick = () => { inp.value = b.dataset.swatch; $$("[data-swatch]", root).forEach((x) => x.classList.toggle("on", x === b)); }; });
  };

  // logo picker: uploads immediately (PNG / JPEG, up to 1 MB); earlier logos stay stored for issued documents
  QT.logoBox = function (container, onChange) {
    const draw = () => {
      const s = QT.state.meta.settings;
      container.innerHTML = h`<div class="row" style="gap:14px;align-items:center">
        <div class="logo-preview">${QT.raw(s.logo_file ? '<img src="' + esc(QT.logoUrl(s.logo_file)) + '" alt="Company logo">' : '<span class="muted small">No logo</span>')}</div>
        <div class="stack" style="gap:6px"><label class="btn sm">${QT.raw(icon("upload"))}${s.logo_file ? "Replace logo" : "Upload logo"}<input type="file" accept="image/png,image/jpeg" data-logo hidden></label>
          ${s.logo_file ? QT.raw('<button type="button" class="btn sm ghost" data-logo-rm>Remove logo</button>') : ""}
          <span class="hint">PNG or JPEG, up to 1 MB. A square or wide logo on a light background works best.</span></div></div>`.html;
      $("[data-logo]", container).onchange = async (e) => {
        const f = e.target.files[0];
        if (!f) return;
        try {
          await QT.post("/api/settings/logo", { data: await QT.fileToBase64(f), name: f.name });
          await QT.loadMeta();
          QT.toast("Logo saved");
          draw();
          onChange && onChange();
        } catch (err) { QT.fail(err); }
      };
      const rm = $("[data-logo-rm]", container);
      if (rm) rm.onclick = async () => {
        try { await QT.post("/api/settings/logo", { remove: true }); await QT.loadMeta(); QT.toast("Logo removed"); draw(); onChange && onChange(); } catch (err) { QT.fail(err); }
      };
    };
    draw();
  };

  // synthetic sample invoice built from the current settings (never saved, no real customer)
  QT.sampleInvoiceHtml = function () {
    const s = QT.state.meta.settings;
    const fy = (() => { const d = new Date(); const y = d.getMonth() >= 3 ? d.getFullYear() : d.getFullYear() - 1; return y + "-" + String(y + 1).slice(2); })();
    const number = (s.invoice_prefix || "INV/") + fy + "/0001";
    const meta = "<div><b>" + esc(number) + "</b></div><div>Date: " + esc(QT.date(QT.today())) + "</div><div>Booking: " + esc((s.booking_prefix || "BK-") + "26-00001") + "</div>";
    return h`<div class="doc">${QT.docHead("Invoice (sample)", meta)}
      <div class="doc-meta"><div class="box"><div class="lbl">Billed to</div><b>Sample Customer</b><div>Demo City</div></div>
        <div class="box"><div class="lbl">Travel</div><div>Demo Air DA 101  COK → DXB</div></div></div>
      <table><thead><tr><th>Description</th><th class="r">Amount</th></tr></thead><tbody>
        <tr><td>Air ticket — SAMPLE PASSENGER (synthetic example)</td><td class="r">${QT.money(1250000)}</td></tr></tbody></table>
      <div class="totals"><div class="grand"><span>Total</span><span>${QT.money(1250000)}</span></div></div>
      ${s.invoice_terms ? QT.raw('<div style="margin-top:22px;color:#64738F;font-size:11px"><b>Terms:</b> ' + esc(s.invoice_terms) + "</div>") : ""}
      ${QT.docFoot()}</div>`;
  };

  // ---------------------------------------------------------------- first-run setup wizard
  const STEPS = ["Company", "Logo & colour", "Bank & payments", "Invoices & tax", "Preview", "Finish"];
  QT.pages.setup = async function (view, params) {
    await QT.loadMeta();
    let step = Math.max(0, Math.min(STEPS.length - 1, parseInt(params.step || "0", 10) || 0));
    const s = () => QT.state.meta.settings;
    const go = (n) => QT.go("#/setup?step=" + n);
    const save = async (form) => { await QT.put("/api/settings", QT.formData(form)); await QT.loadMeta(); };
    const finish = async (skipped) => {
      await QT.put("/api/settings", { setup_completed: "1" });
      await QT.loadMeta();
      QT.toast(skipped ? "Setup skipped — finish it any time in Settings" : "Setup complete");
      location.hash = "#/dashboard";
      location.reload();
    };
    view.innerHTML = h`<div class="page-head"><div><h1>Set up your company</h1><div class="sub">A few steps to put your agency's name, logo, bank details and invoice style on everything. Optional fields can be left empty and completed later in Settings.</div></div>
        <div class="actions"><button class="btn ghost" data-skip>Skip for now</button></div></div>
      <div class="wizard-steps">${QT.raw(STEPS.map((t, i) => '<span class="' + (i === step ? "on" : i < step ? "done" : "") + '">' + (i + 1) + ". " + esc(t) + "</span>").join(""))}</div>
      <div class="card" style="max-width:900px"><div class="card-b" data-body></div>
        <div class="card-b row" style="justify-content:space-between;border-top:1px solid var(--border)">
          <button class="btn" data-back ${step === 0 ? "disabled" : ""}>Back</button><button class="btn primary" data-next>${step === STEPS.length - 1 ? "Finish and open dashboard" : "Save and continue"}</button></div></div>`.html;
    const body = $("[data-body]", view);
    $("[data-skip]", view).onclick = () => finish(true);
    $("[data-back]", view).onclick = () => go(step - 1);
    let next = async () => go(step + 1);
    if (step === 0) {
      body.innerHTML = '<form class="form-grid" onsubmit="return false">' + QT.profileFields(s()) + "</form>";
      next = async () => { await save($("form", body)); go(1); };
    } else if (step === 1) {
      body.innerHTML = h`<div class="stack" style="gap:16px"><div data-logo-box></div><form class="form-grid" onsubmit="return false">${QT.brandFields(s())}</form></div>`.html;
      QT.logoBox($("[data-logo-box]", body));
      QT.bindBrandFields(body);
      next = async () => { await save($("form", body)); go(2); };
    } else if (step === 2) {
      const accs = await QT.get("/api/accounts");
      body.innerHTML = h`<div class="stack" style="gap:14px">
        <div><b>Cash & bank accounts</b><div class="muted small">Where money is received and paid from. Add each bank, UPI, card or cash account; you can deactivate one later without losing its history.</div></div>
        <div>${QT.raw(accs.map((a) => '<div class="li"><div class="grow"><div class="t1">' + esc(a.name) + '</div><div class="t2">' + esc((QT.labels.accType[a.account_type || a.kind] || a.kind) + (a.bank_name ? " · " + a.bank_name : "") + (a.account_last4 ? " · ••" + a.account_last4 : "")) + "</div></div></div>").join(""))}</div>
        <div><button class="btn sm" type="button" data-add-acc>${QT.raw(icon("plus"))}Add account</button></div>
        <div style="margin-top:6px"><b>Payment details shown to customers</b><div class="muted small">Printed on payment reminders and the ticket + payment PDF. Leave empty if you do not want them shown.</div></div>
        <form class="form-grid" onsubmit="return false">
          ${QT.field({ name: "pay_account_name", label: "Account name", span: 6, value: s().pay_account_name, cls: "upper" })}
          ${QT.field({ name: "pay_account_number", label: "Account number", span: 6, value: s().pay_account_number, attrs: 'inputmode="numeric"' })}
          ${QT.field({ name: "pay_ifsc", label: "IFSC code", span: 4, value: s().pay_ifsc, cls: "upper" })}
          ${QT.field({ name: "pay_branch", label: "Branch", span: 4, value: s().pay_branch })}
          ${QT.field({ name: "pay_bank_name", label: "Bank name", span: 4, value: s().pay_bank_name })}
          ${QT.field({ name: "pay_upi_number", label: "UPI payment number", span: 6, value: s().pay_upi_number, attrs: 'inputmode="numeric"' })}
          ${QT.field({ name: "pay_upi_apps", label: "UPI apps to mention", span: 6, value: s().pay_upi_apps })}
        </form></div>`.html;
      $("[data-add-acc]", body).onclick = () => QT.accountForm(null, async () => { await QT.loadMeta(); QT.reload(); });
      next = async () => { await save($("form", body)); go(3); };
    } else if (step === 3) {
      body.innerHTML = '<form class="form-grid" onsubmit="return false">' + QT.docFields(s()) + "</form>" +
        h`<div class="muted small" style="margin-top:10px">Base currency: <b>${QT.base()}</b>. GST / tax profiles: Settings → Tax / GST (templates — confirm with your accountant).</div>`.html;
      next = async () => { await save($("form", body)); go(4); };
    } else if (step === 4) {
      body.innerHTML = '<div class="muted small" style="margin-bottom:10px">This is how a new invoice will look (sample data, nothing is saved). Go back to change anything.</div>' +
        '<div style="background:#fff;color:#0D1833;border:1px solid var(--border);border-radius:12px;padding:18px;overflow:auto">' + QT.sampleInvoiceHtml() + "</div>";
    } else {
      body.innerHTML = h`<div class="stack" style="gap:10px">
        <div class="alert success">${QT.raw(icon("check"))}<div><b>${s().business_name}</b> is ready. Your owner login was created when the company workspace was made.</div></div>
        <div class="muted">You can change everything later in <b>Settings</b>: Company profile, Branding, Documents & formats, Payment details, Tax / GST, Expense categories and Backups.</div></div>`.html;
      next = async () => finish(false);
    }
    $("[data-next]", view).onclick = async (e) => {
      e.target.disabled = true;
      try { await next(); } catch (err) { QT.showErr(body, err); } finally { e.target.disabled = false; }
    };
  };
})();
