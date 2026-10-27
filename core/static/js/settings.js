/* Settings, backups, audit trail */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;
  const TABS = [["business", "Company & branding", "building"], ["payment", "Payment details", "bank"], ["invoice", "Documents & formats", "invoice"], ["tax", "Tax / GST", "percent"], ["currencies", "Currencies", "globe"],
    ["categories", "Expense categories", "expense"], ["backups", "Backup & restore", "database"], ["security", "Security", "shield"], ["fares", "Fare search", "plane"]];

  QT.pages.settings = async function (view, params, tab) {
    tab = tab || "business";
    await QT.loadMeta();
    const meta = QT.state.meta;
    const s = meta.settings;
    view.innerHTML = h`<div class="page-head"><div><h1>Settings</h1><div class="sub">Changes are recorded in the audit trail</div></div></div>
      <div class="tabs">${QT.raw(TABS.map((t) => '<button data-tab="' + t[0] + '" class="' + (t[0] === tab ? "on" : "") + '">' + icon(t[2]).replace("<svg", '<svg style="width:15px;height:15px"') + esc(t[1]) + "</button>").join(""))}</div>
      <div data-pane></div>`.html;
    $$("[data-tab]", view).forEach((b) => { b.onclick = () => QT.go("#/settings/" + b.dataset.tab); });
    const pane = $("[data-pane]", view);

    const saveSettings = async (form, msg) => {
      try { await QT.put("/api/settings", QT.formData(form)); await QT.loadMeta(); QT.toast(msg || "Settings saved"); if (form.dataset.reloadShell) location.reload(); }
      catch (e) { QT.showErr(form, e); }
    };

    if (tab === "business") {
      pane.innerHTML = h`<div class="grid g-side" style="max-width:1200px">
        <div class="card"><div class="card-h"><h3>Company profile</h3><span class="sub">Shown in the app and on invoices, receipts, statements and ticket PDFs</span></div>
          <form class="card-b form-grid" data-f data-reload-shell="1" onsubmit="return false">${QT.profileFields(s)}
            <div class="f s12"><button class="btn primary" type="button" data-save style="align-self:flex-start">Save company profile</button></div></form></div>
        <div class="stack" style="gap:16px"><div class="card"><div class="card-h"><h3>Logo</h3></div><div class="card-b" data-logo-box></div></div>
          <div class="card"><div class="card-h"><h3>Brand colour</h3></div><form class="card-b form-grid" data-brand onsubmit="return false">${QT.brandFields(s)}
            <div class="f s12"><button class="btn primary" type="button" data-save-brand style="align-self:flex-start">Save colour</button></div></form></div>
          <div class="card"><div class="card-b small muted">Product: AirZelo ${QT.state.meta.version || ""}. Your company branding appears on your documents; the AirZelo product name and licence notices stay with the software. <a href="#/setup">Run the setup wizard again</a></div></div></div></div>`.html;
      QT.logoBox($("[data-logo-box]", pane), () => location.reload());
      QT.bindBrandFields(pane);
      $("[data-save-brand]", pane).onclick = () => saveSettings($("[data-brand]", pane), "Brand colour saved");
    } else if (tab === "payment") {
      pane.innerHTML = h`<div class="grid g-side" style="max-width:1100px"><div class="card"><div class="card-h"><h3>Payment details for customers</h3><span class="sub">Used in WhatsApp payment reminders and the ticket + payment PDF</span></div>
        <form class="card-b form-grid" data-f onsubmit="return false">
          <div class="f s12"><div class="lbl" style="margin-bottom:-4px">BANK TRANSFER</div></div>
          ${QT.field({ name: "pay_account_name", label: "Account name", span: 6, value: s.pay_account_name, cls: "upper" })}
          ${QT.field({ name: "pay_account_number", label: "Account number", span: 6, value: s.pay_account_number, attrs: 'inputmode="numeric"' })}
          ${QT.field({ name: "pay_ifsc", label: "IFSC code", span: 4, value: s.pay_ifsc, cls: "upper" })}
          ${QT.field({ name: "pay_branch", label: "Branch", span: 4, value: s.pay_branch })}
          ${QT.field({ name: "pay_bank_name", label: "Bank name (optional)", span: 4, value: s.pay_bank_name, hint: "Leave empty to leave it off" })}
          <div class="f s12"><div class="lbl" style="margin:6px 0 -4px">UPI PAYMENT</div></div>
          ${QT.field({ name: "pay_upi_number", label: "UPI payment number", span: 6, value: s.pay_upi_number, attrs: 'inputmode="numeric"' })}
          ${QT.field({ name: "pay_upi_apps", label: "UPI apps to mention", span: 6, value: s.pay_upi_apps, hint: "Comma separated, e.g. Google Pay, PhonePe, BHIM UPI" })}
          <div class="f s12"><div class="alert info small">${QT.raw(icon("info"))}<div>Payment apps are shown as text labels. No QR code or payment link is created, because no verified UPI ID is set. Always double-check these details: customers will pay into this account.</div></div></div>
          <div class="f s12"><button class="btn primary" type="button" data-save style="align-self:flex-start">Save payment details</button></div>
        </form></div>
        <div class="card"><div class="card-h"><h3>Preview</h3><span class="sub">as it appears in WhatsApp</span></div><div class="card-b stack" style="gap:10px"><div class="pay-preview" data-prev></div><div class="pay-apps" data-apps></div></div></div></div>`.html;
      const f = $("[data-f]", pane);
      const prev = () => {
        const d = QT.formData(f);
        $("[data-prev]", pane).textContent = QT.wa.paymentBlock({ account_name: d.pay_account_name.toUpperCase(), account_number: d.pay_account_number, ifsc: d.pay_ifsc.toUpperCase(),
          branch: d.pay_branch, bank_name: d.pay_bank_name, upi_number: d.pay_upi_number, upi_apps: d.pay_upi_apps }).replace(/\*/g, "") || "No payment details set.";
        $("[data-apps]", pane).innerHTML = d.pay_upi_apps.split(",").map((a) => a.trim()).filter(Boolean).map((a) => "<span>" + esc(a) + "</span>").join("");
      };
      f.addEventListener("input", prev);
      prev();
    } else if (tab === "invoice") {
      pane.innerHTML = h`<div class="grid g-side" style="max-width:1200px"><div class="card"><div class="card-h"><h3>Documents & formats</h3><span class="sub">Numbering, footers, date and currency display</span></div>
        <form class="card-b form-grid" data-f data-reload-shell="1" onsubmit="return false">${QT.docFields(s)}
          <div class="f s12"><button class="btn primary" type="button" data-save style="align-self:flex-start">Save</button></div></form></div>
        <div class="card"><div class="card-h"><h3>Sample invoice</h3><span class="sub">uses your saved settings</span></div><div class="card-b"><div style="background:#fff;color:#0D1833;border-radius:10px;padding:12px;font-size:12px;overflow:auto" data-sample></div>
          <button class="btn sm" type="button" data-print-sample style="margin-top:10px">${QT.raw(icon("print"))}Print / Save PDF</button></div></div></div>`.html;
      $("[data-sample]", pane).innerHTML = String(QT.sampleInvoiceHtml());
      $("[data-print-sample]", pane).onclick = () => QT.print(QT.sampleInvoiceHtml(), "Sample invoice");
    } else if (tab === "tax") {
      const rows = meta.tax_profiles;
      pane.innerHTML = h`<div class="alert warn" style="margin-bottom:14px;max-width:1000px">${QT.raw(icon("alert"))}<div>GST on air tickets and agency services depends on how each transaction is structured (agent vs. principal, service charge vs. markup, place of supply). The templates below are <b>starting points only</b> — have your CA confirm the taxable value, rate, SAC code and CGST/SGST vs IGST before using them. Tax is always kept separate from revenue and profit.</div></div>
        <div class="card" style="max-width:1000px"><div class="card-h"><h3>Tax profiles</h3><div class="actions"><button class="btn sm primary" data-new>${QT.raw(icon("plus"))}New profile</button></div></div><div data-t></div></div>`.html;
      const basis = { none: "No tax", service_charge: "Service charge", markup: "Markup (selling − cost)", service_and_markup: "Service charge + markup", selling_price: "Full selling price", custom: "Enter on booking" };
      QT.renderTable($("[data-t]", pane), { rows, sortable: false, onRow: (r) => taxForm(r), columns: [
        { label: "Name", key: "name", render: (r) => h`<div class="cell-main">${r.name}</div><div class="cell-sub">${r.notes || ""}</div>` },
        { label: "Rate", render: (r) => esc(r.rate) + "%" }, { label: "Taxable value", render: (r) => esc(basis[r.basis]) },
        { label: "Split", render: (r) => esc({ none: "—", cgst_sgst: "CGST + SGST", igst: "IGST" }[r.split]) }, { label: "SAC", key: "sac_code" },
        { label: "Status", render: (r) => QT.badge(r.active ? "Active" : "Inactive", r.active ? "b-green" : "b-gray") },
      ] });
      $("[data-new]", pane).onclick = () => taxForm({ active: 1, basis: "service_charge", split: "cgst_sgst", rate: "18" });
      function taxForm(t) {
        const f = el(h`<form class="form-grid" onsubmit="return false">
          ${QT.field({ name: "name", label: "Name", span: 12, value: t.name, req: true })}
          ${QT.field({ name: "rate", label: "Rate %", span: 4, value: t.rate, attrs: 'inputmode="decimal"' })}
          ${QT.field({ name: "basis", label: "Taxable value", type: "select", span: 8, opts: QT.options(Object.entries(basis), t.basis) })}
          ${QT.field({ name: "split", label: "Split", type: "select", span: 6, opts: QT.options([["none", "Single tax line"], ["cgst_sgst", "CGST + SGST (intra-state)"], ["igst", "IGST (inter-state)"]], t.split) })}
          ${QT.field({ name: "sac_code", label: "SAC code", span: 6, value: t.sac_code })}
          ${QT.field({ name: "notes", label: "Notes", type: "textarea", span: 12, value: t.notes, rows: 2 })}
          ${QT.field({ name: "active", label: "Active", type: "checkbox", span: 12, value: !!t.active })}
        </form>`.html);
        QT.modal({ title: t.id ? "Edit tax profile" : "New tax profile", body: f, actions: [{ label: "Cancel" }, { label: "Save", cls: "primary", onClick: async () => {
          const d = QT.formData(f);
          t.id ? await QT.put("/api/tax-profiles/" + t.id, d) : await QT.post("/api/tax-profiles", d);
          QT.toast("Tax profile saved"); QT.reload();
        } }] });
      }
    } else if (tab === "currencies") {
      pane.innerHTML = h`<div class="card" style="max-width:860px"><div class="card-h"><h3>Currencies</h3><span class="sub">Reporting currency: ${QT.base()}</span><div class="actions"><button class="btn sm primary" data-new>${QT.raw(icon("plus"))}Add currency</button></div></div><div data-t></div>
        <div class="card-b small muted" style="border-top:1px solid var(--border)">Each booking and transaction stores its own exchange rate and conversion date. Historical records are never re-converted at today's rate.</div></div>`.html;
      QT.renderTable($("[data-t]", pane), { rows: meta.currencies, sortable: false, onRow: (c) => curForm(c), columns: [
        { label: "Code", key: "code", render: (c) => h`<b class="mono">${c.code}</b>` }, { label: "Name", key: "name" }, { label: "Symbol", key: "symbol" },
        { label: "Status", render: (c) => QT.badge(c.code === QT.base() ? "Reporting" : c.active ? "Active" : "Inactive", c.code === QT.base() ? "b-blue" : c.active ? "b-green" : "b-gray") },
      ] });
      $("[data-new]", pane).onclick = () => curForm({ active: 1 });
      function curForm(c) {
        const f = el(h`<form class="form-grid" onsubmit="return false">
          ${QT.field({ name: "code", label: "ISO code", span: 4, value: c.code, cls: "upper", attrs: (c.code ? "readonly " : "") + 'maxlength="3"' })}
          ${QT.field({ name: "name", label: "Name", span: 8, value: c.name, req: true })}
          ${QT.field({ name: "symbol", label: "Symbol", span: 4, value: c.symbol })}
          ${QT.field({ name: "active", label: "Active", type: "checkbox", span: 8, value: !!c.active })}
        </form>`.html);
        QT.modal({ title: c.code ? "Edit " + c.code : "Add currency", body: f, actions: [{ label: "Cancel" }, { label: "Save", cls: "primary", onClick: async () => {
          await QT.post("/api/currencies", QT.formData(f)); QT.toast("Currency saved"); QT.reload();
        } }] });
      }
    } else if (tab === "categories") {
      pane.innerHTML = h`<div class="card" style="max-width:700px"><div class="card-h"><h3>Expense categories</h3></div>
        <div class="card-b row"><input class="inp" style="max-width:320px" data-name placeholder="New category name"><button class="btn primary" data-add>${QT.raw(icon("plus"))}Add</button></div>
        <div class="card-b flush">${QT.raw(meta.expense_categories.map((c) => h`<div class="li"><div class="grow"><div class="t1">${c.name}</div></div>${QT.badge(c.active ? "Active" : "Hidden", c.active ? "b-green" : "b-gray")}<button class="btn xs" data-toggle="${c.name}" data-active="${c.active}">${c.active ? "Hide" : "Show"}</button></div>`.html).join(""))}</div></div>`.html;
      $("[data-add]", pane).onclick = async () => {
        const n = $("[data-name]", pane).value.trim();
        if (!n) return;
        try { await QT.post("/api/expense-categories", { name: n, active: true }); QT.toast("Category added"); QT.reload(); } catch (e) { QT.fail(e); }
      };
      $$("[data-toggle]", pane).forEach((b) => { b.onclick = async () => { await QT.post("/api/expense-categories", { name: b.dataset.toggle, active: b.dataset.active !== "1" }); QT.reload(); }; });
    } else if (tab === "fares") {
      await QT.fareSettings(pane, s);
    } else if (tab === "security") {
      pane.innerHTML = h`<div class="grid g2" style="max-width:1000px">
        <div class="card"><div class="card-h"><h3>Change password</h3></div><form class="card-b form-grid" data-pw onsubmit="return false">
          ${QT.field({ name: "current_password", label: "Current password", type: "password", span: 12, attrs: 'autocomplete="current-password"' })}
          ${QT.field({ name: "new_password", label: "New password", type: "password", span: 12, hint: "At least 10 characters", attrs: 'autocomplete="new-password"' })}
          ${QT.field({ name: "new_password2", label: "Repeat new password", type: "password", span: 12, attrs: 'autocomplete="new-password"' })}
          <div class="f s12"><button class="btn primary" type="button" data-cp style="align-self:flex-start">Change password</button></div></form></div>
        <div class="card"><div class="card-h"><h3>Session</h3></div><form class="card-b form-grid" data-f onsubmit="return false">
          ${QT.field({ name: "session_hours", label: "Sign out after inactivity (hours)", type: "number", span: 12, value: s.session_hours, attrs: 'min="1" max="72"' })}
          <div class="f s12"><button class="btn primary" type="button" data-save style="align-self:flex-start">Save</button></div>
          <div class="f s12"><div class="alert info small">${QT.raw(icon("shield"))}<div>AirZelo listens only on <b>127.0.0.1</b> (this computer). Passwords are stored as salted PBKDF2 hashes. Customer documents are only served to a signed-in session. Data folder permissions are restricted to your macOS user.</div></div></div></form></div></div>`.html;
      $("[data-cp]", pane).onclick = async () => {
        const f = $("[data-pw]", pane);
        const d = QT.formData(f);
        if (d.new_password !== d.new_password2) { QT.showErr(f, { message: "New passwords do not match", field: "new_password2" }); return; }
        try { await QT.post("/api/auth/password", d); QT.toast("Password changed. Please sign in again."); setTimeout(() => location.reload(), 1200); }
        catch (e) { QT.showErr(f, e); }
      };
    } else if (tab === "backups") {
      const data = await QT.get("/api/backups");
      pane.innerHTML = h`<div class="grid g-side">
        <div class="card"><div class="card-h"><h3>Backups</h3><span class="sub">${data.backups.length} saved</span><div class="actions"><button class="btn sm primary" data-create>${QT.raw(icon("database"))}Back up now</button></div></div><div data-list></div>
          <div class="card-b small muted" style="border-top:1px solid var(--border)">Folder on this Mac: <span class="mono">${data.folder}</span>. For extra safety, copy downloaded backups to an external drive or iCloud Drive.</div></div>
        <div class="stack">
          <div class="card"><div class="card-h"><h3>Automatic backups</h3></div><form class="card-b form-grid" data-f onsubmit="return false">
            ${QT.field({ name: "auto_backup_hours", label: "Back up every (hours, 0 = off)", type: "number", span: 12, value: s.auto_backup_hours, attrs: 'min="0" max="720"' })}
            ${QT.field({ name: "backup_keep", label: "Keep the latest N automatic backups", type: "number", span: 12, value: s.backup_keep, attrs: 'min="3" max="1000"', hint: "Manual and pre-restore backups are never removed automatically" })}
            <div class="f s12"><button class="btn primary" type="button" data-save style="align-self:flex-start">Save</button></div></form></div>
          <div class="card"><div class="card-h"><h3>Restore from a file</h3></div><div class="card-b stack" style="gap:10px">
            <div class="small muted">Upload a AirZelo backup (.zip). It is verified first, and a safety backup of your current data is taken before anything is replaced.</div>
            <input type="file" class="inp" accept=".zip" data-upfile><button class="btn danger" data-uprestore>${QT.raw(icon("upload"))}Verify & restore upload</button></div></div>
        </div></div>`.html;
      QT.renderTable($("[data-list]", pane), { rows: data.backups, sortable: false, emptyTitle: "No backups yet", empty: "Click “Back up now” to create the first one.", emptyIcon: "database", columns: [
        { label: "Backup", render: (b) => h`<div class="cell-main mono" style="font-size:12px">${b.name}</div><div class="cell-sub">${QT.dateTime(b.created_at)}</div>` },
        { label: "Type", render: (b) => QT.badge({ manual: "Manual", auto: "Automatic", "pre": "Safety copy", imported: "Imported" }[b.kind] || b.kind, b.kind === "auto" ? "b-cyan" : b.kind === "pre" ? "b-warn" : "b-blue") },
        { label: "Size", type: "number", render: (b) => (b.size / 1024 / 1024).toFixed(2) + " MB" },
        { label: "", render: (b) => '<div class="row" style="justify-content:flex-end;gap:6px;flex-wrap:nowrap"><button class="btn xs" data-verify="' + esc(b.name) + '">Verify</button><button class="btn xs" data-dl="' + esc(b.name) + '">' + icon("download") + '</button><button class="btn xs danger" data-restore="' + esc(b.name) + '">Restore</button></div>' },
      ] });
      $("[data-create]", pane).onclick = async (e) => {
        e.target.disabled = true;
        try { const r = await QT.post("/api/backups", {}); QT.toast("Verified backup saved: " + r.name); QT.reload(); } catch (err) { QT.fail(err); } finally { e.target.disabled = false; }
      };
      $$("[data-verify]", pane).forEach((b) => { b.onclick = async () => {
        try { const r = await QT.post("/api/backups/verify", { name: b.dataset.verify }); r.ok ? QT.toast("Backup verified: checksums and database integrity OK") : QT.toast("Problems: " + r.problems.join("; "), "err"); } catch (e) { QT.fail(e); }
      }; });
      $$("[data-dl]", pane).forEach((b) => { b.onclick = () => QT.download("/api/backups/download?name=" + encodeURIComponent(b.dataset.dl), b.dataset.dl).catch(QT.fail); });
      const doRestore = async (payload, label) => {
        const ok = await QT.confirm({ title: "Restore " + label + "?", message: "All current data will be replaced by this backup.", detail: "A safety backup of your current data is taken first, so this can be undone by restoring that safety copy.", danger: true, ok: "Restore", typeText: "RESTORE" });
        if (!ok) return;
        try {
          const r = await QT.post("/api/backups/restore", Object.assign({ confirm: "RESTORE" }, payload));
          alert("Restore complete. Safety copy of your previous data: " + r.safety_backup + "\n\nPlease sign in again.");
          location.reload();
        } catch (e) { QT.fail(e); }
      };
      $$("[data-restore]", pane).forEach((b) => { b.onclick = () => doRestore({ name: b.dataset.restore }, b.dataset.restore); });
      $("[data-uprestore]", pane).onclick = async () => {
        const f = $("[data-upfile]", pane).files[0];
        if (!f) { QT.toast("Choose a backup .zip file first", "err"); return; }
        if (f.size > 18 * 1024 * 1024) { QT.toast("For backups over 18 MB, copy the file into the backups folder and restore it from the list.", "err"); return; }
        doRestore({ upload: await QT.fileToBase64(f) }, f.name);
      };
    }
    const sf = $("[data-f]", pane);
    if (sf && $("[data-save]", sf)) $("[data-save]", sf).onclick = () => saveSettings(sf);
  };

  // ---------------------------------------------------------------- audit trail
  QT.pages.audit = async function (view, params) {
    view.innerHTML = h`<div class="page-head"><div><h1>Audit trail</h1><div class="sub">Every create, change, reversal, login and backup — entries cannot be edited or deleted</div></div></div>
      <div class="card"><div class="filters"><select class="inp" name="entity"><option value="">All records</option>${QT.raw(["booking", "transaction", "adjustment", "allocation", "invoice", "customer", "supplier", "account", "settings", "tax_profile", "document", "backup", "owner", "reconciliation"].map((e) => QT.opt(e, e.replace("_", " "), params.entity)).join(""))}</select>
        <span class="spacer"></span><span class="muted small" data-count></span></div><div data-t></div></div>`.html;
    const cfg = { rows: [], sortable: false, emptyTitle: "No audit entries", columns: [
      { label: "When", render: (r) => esc(QT.dateTime(r.at)) }, { label: "Record", render: (r) => h`<b>${r.entity}</b> ${r.entity_id ? "#" + r.entity_id : ""}` },
      { label: "Action", render: (r) => QT.badge(r.action, /reverse|cancel|delete|remove|failed|restore/.test(r.action) ? "b-red" : r.action === "create" ? "b-green" : "b-blue") },
      { label: "Details", render: (r) => {
        let s = "";
        try {
          const a = r.after_json ? JSON.parse(r.after_json) : null;
          const b = r.before_json ? JSON.parse(r.before_json) : null;
          if (a && b && r.action === "update") {
            const ch = Object.keys(a).filter((k) => typeof a[k] !== "object" && String(a[k]) !== String(b[k]));
            s = ch.slice(0, 6).map((k) => esc(k) + ": <span class=\"muted\">" + esc(String(b[k] === null ? "" : b[k]).slice(0, 40)) + "</span> → " + esc(String(a[k] === null ? "" : a[k]).slice(0, 40))).join("<br>") || "no field changes";
          } else if (a) s = esc(JSON.stringify(a).slice(0, 220));
        } catch (e) { s = ""; }
        return '<div class="small" style="max-width:640px;word-break:break-word">' + s + "</div>";
      } },
    ] };
    const draw = QT.renderTable($("[data-t]", view), cfg);
    async function load() {
      cfg.rows = await QT.get("/api/audit" + QT.qs({ entity: $("[name=entity]", view).value, limit: 500 }));
      $("[data-count]", view).textContent = cfg.rows.length + " entries (latest 500)";
      draw();
    }
    $("[name=entity]", view).onchange = load;
    await load();
  };
})();
