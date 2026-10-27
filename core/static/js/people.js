/* Customers & suppliers */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  // ---------------------------------------------------------------- forms
  QT.customerForm = function (c, done) {
    c = c || {};
    const body = el(h`<form class="form-grid" onsubmit="return false">
      ${QT.field({ name: "name", label: "Full name", span: 12, value: c.name, req: true })}
      ${QT.field({ name: "phone", label: "Phone", span: 6, value: c.phone, attrs: 'inputmode="tel"' })}
      ${QT.field({ name: "alt_phone", label: "Alternate phone", span: 6, value: c.alt_phone })}
      ${QT.field({ name: "email", label: "Email", span: 6, value: c.email, type: "email" })}
      ${QT.field({ name: "passport_no", label: "Passport no.", span: 6, value: c.passport_no, cls: "upper" })}
      ${QT.field({ name: "address", label: "Address", span: 12, value: c.address })}
      ${QT.field({ name: "city", label: "City", span: 4, value: c.city })}
      ${QT.field({ name: "state", label: "State", span: 4, value: c.state })}
      ${QT.field({ name: "gstin", label: "GSTIN (business customers)", span: 4, value: c.gstin, cls: "upper" })}
      ${QT.field({ name: "follow_up_date", label: "Follow-up date", span: 4, value: c.follow_up_date, type: "date" })}
      ${QT.field({ name: "follow_up_note", label: "Follow-up note", span: 8, value: c.follow_up_note })}
      ${QT.field({ name: "notes", label: "Notes", span: 12, value: c.notes, type: "textarea" })}
    </form>`.html);
    QT.modal({
      title: c.id ? "Edit customer" : "New customer", size: "lg", body,
      actions: [{ label: "Cancel" }, { label: c.id ? "Save changes" : "Create customer", cls: "primary", onClick: async () => {
        const d = QT.formData(body);
        let r;
        try {
          r = c.id ? await QT.put("/api/customers/" + c.id, Object.assign(d, { row_version: c.row_version })) : await QT.post("/api/customers", d);
        } catch (e) {
          if (e.code === "duplicate_customer") {
            const ok = await QT.confirm({ title: "Possible duplicate", message: e.message, ok: "Create anyway" });
            if (!ok) return false;
            r = await QT.post("/api/customers", Object.assign(d, { force_duplicate: true }));
          } else throw e;
        }
        QT.toast(c.id ? "Customer updated" : "Customer created");
        if (done) done(r);
        else if (!c.id) QT.go("#/customers/" + r.customer.id);
        else QT.reload();
      } }],
    });
  };

  QT.supplierForm = function (s, done) {
    s = s || {};
    const body = el(h`<form class="form-grid" onsubmit="return false">
      ${QT.field({ name: "name", label: "Supplier / portal name", span: 8, value: s.name, req: true, placeholder: "The company that invoices you" })}
      ${QT.field({ name: "kind", label: "Type", type: "select", span: 4, opts: QT.options(Object.entries(QT.labels.supplierKind), s.kind || "b2b_portal") })}
      ${QT.field({ name: "contact_person", label: "Contact person", span: 6, value: s.contact_person })}
      ${QT.field({ name: "phone", label: "Phone", span: 6, value: s.phone })}
      ${QT.field({ name: "email", label: "Email", span: 6, value: s.email })}
      ${QT.field({ name: "gstin", label: "GSTIN", span: 6, value: s.gstin, cls: "upper" })}
      ${QT.field({ name: "notes", label: "Notes (login ID, wallet details, credit terms…)", span: 12, value: s.notes, type: "textarea" })}
    </form>`.html);
    QT.modal({
      title: s.id ? "Edit supplier" : "New supplier", size: "lg", body,
      actions: [{ label: "Cancel" }, { label: s.id ? "Save changes" : "Create supplier", cls: "primary", onClick: async () => {
        const d = QT.formData(body);
        const r = s.id ? await QT.put("/api/suppliers/" + s.id, Object.assign(d, { row_version: s.row_version })) : await QT.post("/api/suppliers", d);
        await QT.loadMeta();
        QT.toast(s.id ? "Supplier updated" : "Supplier created");
        if (done) done(r);
        else if (!s.id) QT.go("#/suppliers/" + r.supplier.id);
        else QT.reload();
      } }],
    });
  };

  // ---------------------------------------------------------------- customers list
  QT.pages.customers = async function (view, params) {
    view.innerHTML = h`<div class="page-head"><div><h1>Customers</h1><div class="sub">Profiles, balances and booking history</div></div>
      <div class="actions"><button class="btn primary" data-new>${QT.raw(icon("plus"))}New customer</button></div></div>
      <div class="card"><div class="filters"><input class="inp w-search" name="q" placeholder="Search name, phone or email" value="${params.q || ""}">
        <label class="check small"><input type="checkbox" name="inactive"> Show inactive</label>
        <label class="check small"><input type="checkbox" name="due"> Only with balance due</label><span class="spacer"></span><span class="muted small" data-count></span></div>
      <div data-table></div></div>`.html;
    $("[data-new]", view).onclick = () => QT.customerForm();
    const cfg = {
      rows: [], emptyTitle: "No customers", empty: "Customers are added here or directly from a new booking.", emptyIcon: "users",
      onRow: (r) => QT.go("#/customers/" + r.id),
      columns: [
        { label: "Customer", key: "name", render: (r) => h`<div class="row" style="gap:10px;flex-wrap:nowrap"><div class="avatar" style="width:32px;height:32px;font-size:12px;border-radius:9px">${QT.initials(r.name)}</div><div><div class="cell-main">${r.name}</div><div class="cell-sub">${r.email || ""}</div></div></div>` },
        { label: "Phone", key: "phone" },
        { label: "Bookings", key: "bookings", type: "number" },
        { label: "Billed", key: "billed", type: "money" },
        { label: "Received", key: "received", type: "money" },
        { label: "Outstanding", key: "outstanding", type: "money" },
        { label: "Follow-up", key: "follow_up_date", type: "date" },
      ],
    };
    const draw = QT.renderTable($("[data-table]", view), cfg);
    async function load() {
      const q = $("[name=q]", view).value;
      let rows = await QT.get("/api/customers" + QT.qs({ q, inactive: $("[name=inactive]", view).checked ? "1" : "" }));
      if ($("[name=due]", view).checked) rows = rows.filter((r) => r.outstanding > 0);
      cfg.rows = rows;
      $("[data-count]", view).textContent = rows.length + " customers";
      draw();
    }
    $("[name=q]", view).addEventListener("input", QT.debounce(load, 250));
    $$("[type=checkbox]", view).forEach((x) => x.addEventListener("change", load));
    await load();
  };

  // ---------------------------------------------------------------- customer profile
  QT.pages.customer = async function (view, params, id) {
    const d = await QT.get("/api/customers/" + id);
    const c = d.customer, bal = d.balance;
    const tab = params.tab || "bookings";
    view.innerHTML = h`<div class="page-head"><div class="row" style="gap:14px;flex-wrap:nowrap"><div class="avatar">${QT.initials(c.name)}</div><div>
        <div class="crumbs"><a href="#/customers">Customers</a></div><h1>${c.name} ${c.active ? "" : QT.raw('<span class="badge b-gray">Inactive</span>')}</h1>
        <div class="sub">${[c.phone, c.email, c.city].filter(Boolean).join(" · ") || "No contact details"}</div></div></div>
      <div class="actions"><button class="btn" data-edit>${QT.raw(icon("edit"))}Edit</button>
        <button class="btn" data-stmt>${QT.raw(icon("print"))}Statement</button>
        <button class="btn" data-receipt>${QT.raw(icon("arrowIn"))}Receive payment</button>
        <button class="btn wa" data-wa>${QT.raw(icon("whatsapp"))}WhatsApp</button>
        <a class="btn primary" href="#/bookings/new?customer_id=${c.id}">${QT.raw(icon("plus"))}New booking</a>
        <div class="dd"><button class="btn icon" data-more>${QT.raw(icon("more"))}</button></div></div></div>
      <div class="kpis" style="margin-bottom:16px">
        <div class="kpi"><div class="k-top"><span class="k-ico">${QT.raw(icon("ticket"))}</span>Bookings</div><div class="k-val">${bal.bookings}</div></div>
        <div class="kpi c-cyan"><div class="k-top"><span class="k-ico">${QT.raw(icon("receipt"))}</span>Total billed</div><div class="k-val">${QT.money(bal.billed - bal.credits)}</div><div class="k-sub">after ${QT.money(bal.credits)} credits</div></div>
        <div class="kpi c-green"><div class="k-top"><span class="k-ico">${QT.raw(icon("arrowIn"))}</span>Received</div><div class="k-val">${QT.money(bal.received - bal.refunded)}</div><div class="k-sub">net of ${QT.money(bal.refunded)} refunds</div></div>
        <div class="kpi ${bal.outstanding > 0 ? "c-red" : "c-green"}"><div class="k-top"><span class="k-ico">${QT.raw(icon("wallet"))}</span>${bal.outstanding < 0 ? "Credit balance" : "Outstanding"}</div><div class="k-val ${bal.outstanding > 0 ? "neg" : ""}">${QT.money(Math.abs(bal.outstanding))}</div><div class="k-sub">${bal.unallocated ? QT.money(bal.unallocated) + " unallocated advance" : bal.outstanding < 0 ? "you owe the customer" : "&nbsp;"}</div></div>
      </div>
      ${c.follow_up_date ? QT.raw('<div class="alert ' + (c.follow_up_date < QT.today() ? "warn" : "info") + '" style="margin-bottom:14px">' + icon("bell") + "<div><b>Follow up " + esc(QT.date(c.follow_up_date)) + "</b>" + (c.follow_up_note ? " — " + esc(c.follow_up_note) : "") + "</div></div>") : ""}
      <div class="tabs" data-tabs>
        ${QT.raw([["bookings", "Bookings", d.bookings.length], ["ledger", "Ledger", null], ["payments", "Payments", d.transactions.length], ["tickets", "Tickets", d.tickets.length], ["refunds", "Refunds & credits", d.adjustments.length + d.refunds.length], ["profile", "Profile & notes", null]]
          .map(([k, l, n]) => '<button data-t="' + k + '" class="' + (k === tab ? "on" : "") + '">' + l + (n !== null ? ' <span class="count">' + n + "</span>" : "") + "</button>").join(""))}
      </div><div data-pane></div>`.html;
    let pane = $("[data-pane]", view);
    $$("[data-t]", view).forEach((b) => { b.onclick = () => { $$("[data-t]", view).forEach((x) => x.classList.remove("on")); b.classList.add("on"); history.replaceState(null, "", "#/customers/" + id + "?tab=" + b.dataset.t); pane = QT.fresh(pane, "card"); show(b.dataset.t); }; });
    $("[data-edit]", view).onclick = () => QT.customerForm(c);
    $("[data-receipt]", view).onclick = () => QT.txnForm({ kind: "customer_receipt", customer: c }, () => QT.reload());
    $("[data-stmt]", view).onclick = () => QT.statementModal("customer", c);
    $("[data-wa]", view).onclick = () => QT.wa.forCustomer(d);
    QT.dropdown($("[data-more]", view), [
      { label: "Refund to customer", icon: "refund", onClick: () => QT.txnForm({ kind: "customer_refund", customer: c }, () => QT.reload()) },
      { label: "Set follow-up", icon: "bell", onClick: () => QT.customerForm(c) },
      "-",
      { label: c.active ? "Deactivate / delete" : "Reactivate", icon: c.active ? "trash" : "check", onClick: async () => {
        if (!c.active) { await QT.put("/api/customers/" + c.id, { active: true }); QT.reload(); return; }
        if (!(await QT.confirm({ message: "Remove " + c.name + "?", detail: "Customers with bookings or payments are deactivated (kept for history), not deleted.", danger: true, ok: "Remove" }))) return;
        try { const r = await QT.del("/api/customers/" + c.id); QT.toast(r.deleted ? "Customer deleted" : "Customer deactivated"); QT.go("#/customers"); } catch (e) { QT.fail(e); }
      } },
    ]);

    async function show(t) {
      if (t === "bookings") {
        QT.renderTable(pane, { rows: d.bookings, emptyTitle: "No bookings yet", emptyIcon: "ticket", onRow: (r) => QT.go("#/bookings/" + r.id), columns: [
          { label: "Booked", key: "booking_date", type: "date" }, { label: "Ref", key: "ref" },
          { label: "PNR", key: "pnr", render: (r) => (r.pnr ? h`<span class="pnr">${r.pnr}</span>` : "") },
          { label: "Route", key: "route" }, { label: "Travel", key: "travel_date", type: "date" },
          { label: "Status", key: "status", render: (r) => QT.statusBadge(r.status) }, { label: "Payment", key: "payment_status", render: (r) => QT.payBadge(r.payment_status) },
          { label: "Billed", key: "customer_payable", type: "money" }, { label: "Due", key: "customer_outstanding", type: "money" },
        ] });
      } else if (t === "ledger") {
        await QT.ledgerPane(pane, "customer", c);
      } else if (t === "payments") {
        pane.className = "card";
        pane.innerHTML = d.transactions.length ? d.transactions.map((x) => QT.txnLi(x)).join("") : '<div class="empty-state">No payments yet</div>';
        QT.bindTxnLis(pane, () => QT.reload());
      } else if (t === "tickets") {
        pane.className = "card";
        QT.renderTable(pane, { rows: d.tickets, emptyTitle: "No tickets recorded", onRow: (r) => QT.go("#/bookings/" + r.booking_id), columns: [
          { label: "Passenger", key: "name" }, { label: "Ticket number", key: "ticket_number", render: (r) => h`<span class="mono">${r.ticket_number || "—"}</span>` },
          { label: "PNR", key: "pnr", render: (r) => (r.pnr ? h`<span class="pnr">${r.pnr}</span>` : "") }, { label: "Booking", key: "ref" }, { label: "Status", key: "status", render: (r) => QT.statusBadge(r.status) },
        ] });
      } else if (t === "refunds") {
        pane.className = "stack";
        pane.innerHTML = '<div class="card"><div class="card-h"><h3>Credits (cancellations & refunds)</h3></div><div data-a></div></div><div class="card"><div class="card-h"><h3>Refund payments</h3></div><div data-r></div></div>';
        QT.renderTable($("[data-a]", pane), { rows: d.adjustments, emptyTitle: "No credits", onRow: (r) => QT.go("#/bookings/" + r.booking_id), rowClass: (a) => (a.reversed_by ? "reversed" : a.sign < 0 ? "reversal" : ""), columns: [
          { label: "Date", key: "adj_date", type: "date" }, { label: "No.", key: "adj_no" }, { label: "Booking", key: "ref" }, { label: "Type", key: "kind", render: (a) => esc(QT.labels.adj[a.kind]) },
          { label: "Customer credit", type: "money", render: (a) => QT.money(a.sign * a.customer_credit) }, { label: "Notes", key: "notes" },
        ] });
        $("[data-r]", pane).innerHTML = d.refunds.length ? d.refunds.map((x) => QT.txnLi(x)).join("") : '<div class="empty-state">No refunds paid</div>';
        QT.bindTxnLis($("[data-r]", pane), () => QT.reload());
      } else if (t === "profile") {
        pane.className = "grid g2";
        pane.innerHTML = h`<div class="card"><div class="card-h"><h3>Contact</h3><div class="actions"><button class="btn sm" data-e>${QT.raw(icon("edit"))}Edit</button></div></div><div class="card-b"><dl class="kv">
          <dt>Phone</dt><dd>${c.phone || "—"}${c.alt_phone ? " · " + c.alt_phone : ""}</dd><dt>Email</dt><dd>${c.email || "—"}</dd>
          <dt>Address</dt><dd>${[c.address, c.city, c.state].filter(Boolean).join(", ") || "—"}</dd><dt>Passport</dt><dd>${c.passport_no || "—"}</dd>
          <dt>GSTIN</dt><dd>${c.gstin || "—"}</dd><dt>Customer since</dt><dd>${QT.date(c.created_at)}</dd></dl></div></div>
          <div class="card"><div class="card-h"><h3>Notes & follow-up</h3></div><div class="card-b"><form data-nf class="form-grid" onsubmit="return false">
            ${QT.field({ name: "follow_up_date", label: "Follow-up date", type: "date", span: 6, value: c.follow_up_date })}
            ${QT.field({ name: "follow_up_note", label: "Follow-up note", span: 6, value: c.follow_up_note })}
            ${QT.field({ name: "notes", label: "Notes", type: "textarea", span: 12, value: c.notes, rows: 5 })}
            <div class="f s12"><button class="btn primary" type="button" data-sn style="align-self:flex-start">Save notes</button></div></form></div></div>`.html;
        $("[data-e]", pane).onclick = () => QT.customerForm(c);
        $("[data-sn]", pane).onclick = async () => {
          try { await QT.put("/api/customers/" + c.id, Object.assign(QT.formData($("[data-nf]", pane)), { row_version: c.row_version })); QT.toast("Notes saved"); QT.reload(); } catch (e) { QT.showErr(pane, e); }
        };
      }
    }
    pane.className = "card";
    await show(tab);
  };

  // ---------------------------------------------------------------- ledger / statements
  QT.ledgerPane = async function (pane, kind, party) {
    pane.className = "card";
    const st = { from: "", to: "" };
    pane.innerHTML = h`<div class="filters"><span class="lbl">Period</span><input type="date" class="inp" data-f><span class="muted">to</span><input type="date" class="inp" data-t>
      <span class="spacer"></span><button class="btn sm" data-csv>${QT.raw(icon("download"))}CSV</button><button class="btn sm" data-print>${QT.raw(icon("print"))}Print / PDF</button></div><div data-l></div>`.html;
    let data = null;
    async function load() {
      st.from = $("[data-f]", pane).value; st.to = $("[data-t]", pane).value;
      data = await QT.get("/api/" + kind + "s/" + party.id + "/statement" + QT.qs(st));
      const rows = [{ date: data.from || "", type: "", ref: "", description: "Opening balance", debit: null, credit: null, balance: data.opening }].concat(data.lines);
      QT.renderTable($("[data-l]", pane), { sortable: false, rows, emptyTitle: "No entries", onRow: (r) => { if (r.booking_id) QT.go("#/bookings/" + r.booking_id); else if (r.txn_id) QT.txnDetail(r.txn_id); }, columns: [
        { label: "Date", key: "date", type: "date" }, { label: "Type", key: "type" }, { label: "Ref", key: "ref" }, { label: "Description", key: "description" },
        { label: kind === "customer" ? "Debit (billed)" : "Payable +", type: "money", render: (r) => (r.debit ? QT.money(r.debit) : "") },
        { label: kind === "customer" ? "Credit (paid)" : "Paid / credit", type: "money", render: (r) => (r.credit ? QT.money(r.credit) : "") },
        { label: "Balance", type: "money", render: (r) => '<b class="' + (r.balance > 0 ? "neg" : "") + '">' + QT.money(r.balance) + "</b>" },
      ], totals: { } });
      $("[data-l]", pane).insertAdjacentHTML("beforeend", h`<div class="card-b row" style="justify-content:flex-end;gap:24px;border-top:1px solid var(--border)"><span class="muted">Closing balance</span><b style="font-size:16px" class="${data.closing > 0 ? "neg" : ""}">${QT.money(data.closing)}</b>
        <span class="muted small">${kind === "customer" ? (data.closing > 0 ? "customer owes you" : data.closing < 0 ? "you owe the customer" : "settled") : (data.closing > 0 ? "you owe the supplier" : data.closing < 0 ? "supplier owes you" : "settled")}</span></div>`.html);
    }
    $("[data-f]", pane).onchange = load;
    $("[data-t]", pane).onchange = load;
    $("[data-csv]", pane).onclick = () => QT.download("/api/" + kind + "s/" + party.id + "/statement" + QT.qs(Object.assign({ format: "csv" }, st))).catch(QT.fail);
    $("[data-print]", pane).onclick = () => QT.print(QT.statementHtml(kind, party, data), "Statement " + party.name);
    await load();
  };

  QT.statementHtml = function (kind, party, st) {
    const rows = st.lines.map((l) => "<tr><td>" + esc(QT.date(l.date)) + "</td><td>" + esc(l.ref) + "</td><td>" + esc(l.description) + '</td><td class="r">' + (l.debit ? QT.money(l.debit, null, { nosym: true }) : "") + '</td><td class="r">' + (l.credit ? QT.money(l.credit, null, { nosym: true }) : "") + '</td><td class="r"><b>' + QT.money(l.balance, null, { nosym: true }) + "</b></td></tr>").join("");
    return h`<div class="doc">${QT.docHead("Statement of account", "<div>" + esc(st.from ? QT.date(st.from) : "From start") + " – " + esc(st.to ? QT.date(st.to) : QT.date(QT.today())) + "</div><div>Currency " + esc(st.base) + "</div>")}
      <div class="doc-meta"><div class="box"><div class="lbl">${kind === "customer" ? "Customer" : "Supplier"}</div><b>${party.name}</b><div>${party.phone || ""}</div><div>${party.email || ""}</div></div>
      <div class="box"><div class="lbl">Summary</div><div>Opening balance: <b>${QT.money(st.opening)}</b></div><div>${kind === "customer" ? "Billed" : "Payable"}: ${QT.money(st.total_debit)}</div><div>${kind === "customer" ? "Paid / credited" : "Paid / credited"}: ${QT.money(st.total_credit)}</div>
        <div style="font-size:14px;margin-top:4px">Closing balance: <b>${QT.money(st.closing)}</b></div></div></div>
      <table><thead><tr><th>Date</th><th>Ref</th><th>Description</th><th class="r">Debit</th><th class="r">Credit</th><th class="r">Balance</th></tr></thead>
      <tbody><tr><td></td><td></td><td><i>Opening balance</i></td><td></td><td></td><td class="r"><b>${QT.money(st.opening, null, { nosym: true })}</b></td></tr>${QT.raw(rows)}</tbody></table>
      ${QT.docFoot()}</div>`;
  };

  QT.statementModal = async function (kind, party) {
    const body = el("<div></div>");
    QT.modal({ title: "Statement · " + party.name, size: "xl", body, actions: [{ label: "Close", cls: "primary" }], noFocus: true });
    await QT.ledgerPane(body, kind, party);
  };

  // ---------------------------------------------------------------- suppliers
  QT.pages.suppliers = async function (view) {
    view.innerHTML = h`<div class="page-head"><div><h1>Suppliers</h1><div class="sub">B2B portals, airlines and other vendors that invoice you</div></div>
      <div class="actions"><button class="btn primary" data-new>${QT.raw(icon("plus"))}New supplier</button></div></div>
      <div class="card"><div class="filters"><label class="check small"><input type="checkbox" name="inactive"> Show inactive</label><span class="spacer"></span><span class="muted small" data-count></span></div><div data-table></div></div>`.html;
    $("[data-new]", view).onclick = () => QT.supplierForm();
    const cfg = { rows: [], emptyTitle: "No suppliers yet", empty: "Add the B2B portals and airlines you buy tickets from.", emptyIcon: "building", onRow: (r) => QT.go("#/suppliers/" + r.id), columns: [
      { label: "Supplier", key: "name", render: (r) => h`<div class="cell-main">${r.name}</div><div class="cell-sub">${r.contact_person || ""}${r.active ? "" : " · inactive"}</div>` },
      { label: "Type", key: "kind", render: (r) => QT.badge(QT.labels.supplierKind[r.kind], "b-blue") },
      { label: "Bookings", key: "bookings", type: "number" }, { label: "Payable", key: "payable", type: "money" },
      { label: "Credits & commission", key: "credits", type: "money" }, { label: "Paid", key: "paid", type: "money" }, { label: "Outstanding", key: "outstanding", type: "money" },
    ] };
    const draw = QT.renderTable($("[data-table]", view), cfg);
    async function load() {
      cfg.rows = await QT.get("/api/suppliers" + QT.qs({ inactive: $("[name=inactive]", view).checked ? "1" : "" }));
      $("[data-count]", view).textContent = cfg.rows.length + " suppliers";
      draw();
    }
    $("[name=inactive]", view).onchange = load;
    await load();
  };

  QT.pages.supplier = async function (view, params, id) {
    const d = await QT.get("/api/suppliers/" + id);
    const s = d.supplier, bal = d.balance;
    view.innerHTML = h`<div class="page-head"><div class="row" style="gap:14px;flex-wrap:nowrap"><div class="avatar" style="background:linear-gradient(135deg,#0A1A3F,#2350E6)">${QT.raw(icon("building").replace("<svg", '<svg style="width:22px;height:22px"'))}</div><div>
        <div class="crumbs"><a href="#/suppliers">Suppliers</a></div><h1>${s.name}</h1><div class="sub">${QT.labels.supplierKind[s.kind]}${s.contact_person ? " · " + s.contact_person : ""}${s.phone ? " · " + s.phone : ""}</div></div></div>
      <div class="actions"><button class="btn" data-edit>${QT.raw(icon("edit"))}Edit</button><button class="btn" data-stmt>${QT.raw(icon("print"))}Statement</button>
        <button class="btn" data-refund>${QT.raw(icon("arrowIn"))}Refund received</button><button class="btn primary" data-pay>${QT.raw(icon("arrowOut"))}Pay supplier</button>
        <div class="dd"><button class="btn icon" data-more>${QT.raw(icon("more"))}</button></div></div></div>
      <div class="kpis" style="margin-bottom:16px">
        <div class="kpi"><div class="k-top"><span class="k-ico">${QT.raw(icon("ticket"))}</span>Bookings</div><div class="k-val">${bal.bookings}</div></div>
        <div class="kpi c-violet"><div class="k-top"><span class="k-ico">${QT.raw(icon("receipt"))}</span>Net purchases</div><div class="k-val">${QT.money(bal.payable - bal.credits)}</div><div class="k-sub">after credits & commission</div></div>
        <div class="kpi c-green"><div class="k-top"><span class="k-ico">${QT.raw(icon("arrowOut"))}</span>Paid</div><div class="k-val">${QT.money(bal.paid - bal.refunds)}</div><div class="k-sub">net of ${QT.money(bal.refunds)} refunds</div></div>
        <div class="kpi ${bal.outstanding > 0 ? "c-red" : "c-green"}"><div class="k-top"><span class="k-ico">${QT.raw(icon("wallet"))}</span>${bal.outstanding < 0 ? "Due from supplier" : "Outstanding"}</div><div class="k-val">${QT.money(Math.abs(bal.outstanding))}</div><div class="k-sub">${bal.unallocated ? QT.money(bal.unallocated) + " unallocated (e.g. wallet top-ups)" : "&nbsp;"}</div></div>
      </div>
      ${s.notes ? QT.raw('<div class="alert info" style="margin-bottom:14px">' + icon("info") + '<div style="white-space:pre-wrap">' + esc(s.notes) + "</div></div>") : ""}
      <div class="tabs"><button data-t="bookings" class="on">Bookings <span class="count">${d.bookings.length}</span></button><button data-t="ledger">Ledger</button><button data-t="payments">Payments <span class="count">${d.transactions.length}</span></button></div>
      <div data-pane class="card"></div>`.html;
    let paneEl = $("[data-pane]", view);
    $("[data-edit]", view).onclick = () => QT.supplierForm(s);
    $("[data-stmt]", view).onclick = () => QT.statementModal("supplier", s);
    $("[data-pay]", view).onclick = () => QT.txnForm({ kind: "supplier_payment", supplier: s }, () => QT.reload());
    $("[data-refund]", view).onclick = () => QT.txnForm({ kind: "supplier_receipt", supplier: s }, () => QT.reload());
    QT.dropdown($("[data-more]", view), [
      { label: s.active ? "Deactivate / delete" : "Reactivate", icon: s.active ? "trash" : "check", onClick: async () => {
        if (!s.active) { await QT.put("/api/suppliers/" + s.id, { active: true }); await QT.loadMeta(); QT.reload(); return; }
        if (!(await QT.confirm({ message: "Remove " + s.name + "?", detail: "Suppliers with bookings or payments are deactivated (kept for history), not deleted.", danger: true, ok: "Remove" }))) return;
        try { const r = await QT.del("/api/suppliers/" + s.id); await QT.loadMeta(); QT.toast(r.deleted ? "Supplier deleted" : "Supplier deactivated"); QT.go("#/suppliers"); } catch (e) { QT.fail(e); }
      } },
    ]);
    const tabShow = async (t, p) => {
      if (t === "bookings") QT.renderTable(p, { rows: d.bookings, emptyTitle: "No bookings with this supplier", onRow: (r) => QT.go("#/bookings/" + r.id), columns: [
        { label: "Booked", key: "booking_date", type: "date" }, { label: "Ref", key: "ref" }, { label: "PNR", key: "pnr", render: (r) => (r.pnr ? h`<span class="pnr">${r.pnr}</span>` : "") },
        { label: "Passenger", key: "lead_pax" }, { label: "Route", key: "route" }, { label: "Status", key: "status", render: (r) => QT.statusBadge(r.status) },
        { label: "Cost", key: "cost", type: "money" }, { label: "Supplier due", key: "supplier_outstanding", type: "money" },
      ] });
      else if (t === "ledger") await QT.ledgerPane(p, "supplier", s);
      else { p.className = "card"; p.innerHTML = d.transactions.length ? d.transactions.map((x) => QT.txnLi(x)).join("") : '<div class="empty-state">No payments yet</div>'; QT.bindTxnLis(p, () => QT.reload()); }
    };
    $$("[data-t]", view).forEach((b) => { b.onclick = () => {
      $$("[data-t]", view).forEach((x) => x.classList.remove("on")); b.classList.add("on");
      paneEl = QT.fresh(paneEl, "card");
      tabShow(b.dataset.t, paneEl);
    }; });
    await tabShow("bookings", paneEl);
  };
})();
