/* Reports */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  const REPORTS = [
    ["Sales & profit", [
      ["sales", "Sales report", "trend", "Bookings billed in the period"],
      ["profit", "Profit report", "coins", "Ticket-wise profit, gross & net"],
      ["monthly", "Monthly summary", "calendar", "Month by month P&L and cash"],
      ["yearly", "Yearly summary", "chart", "Calendar or financial year"],
      ["commission", "Commission income", "percent", "Commission earned and reversed"],
      ["airline", "Airline-wise sales", "plane", "Sales and profit per airline"],
      ["supplier_purchases", "Supplier-wise purchases", "building", "Purchases per portal"],
    ]],
    ["Operations", [
      ["bookings", "Booking report", "ticket", "All bookings with status"],
      ["refunds", "Refunds & cancellations", "refund", "Credits, charges and refunds"],
    ]],
    ["Balances & cash", [
      ["customer_outstanding", "Customer outstanding", "users", "Who owes you"],
      ["supplier_outstanding", "Supplier outstanding", "building", "What you owe portals"],
      ["cashbook", "Cashbook", "book", "Daily cash and bank movements"],
      ["expenses", "Expense report", "expense", "Expenses by category"],
    ]],
  ];
  const ALL = {};
  REPORTS.forEach(([, items]) => items.forEach((r) => { ALL[r[0]] = r; }));
  const DATED = ["sales", "profit", "commission", "airline", "supplier_purchases", "bookings", "refunds", "cashbook", "expenses"];
  const BOOKING_FILTERS = ["sales", "profit", "commission", "airline", "supplier_purchases", "bookings", "refunds"];

  QT.pages.reports = async function (view, params, name) {
    if (!name) {
      view.innerHTML = h`<div class="page-head"><div><h1>Reports</h1><div class="sub">Every report is calculated from the same records as the dashboard, so totals always agree</div></div></div>
        ${QT.raw(REPORTS.map(([grp, items]) => '<div class="nav-label" style="color:var(--muted);padding-left:2px">' + esc(grp) + '</div><div class="grid g3" style="margin-bottom:10px">' + items.map((r) => h`<a class="card li click" href="#/reports/${r[0]}" style="color:inherit;text-decoration:none;border-radius:14px"><div class="ic">${QT.raw(icon(r[2]))}</div><div class="grow"><div class="t1">${r[1]}</div><div class="t2">${r[3]}</div></div>${QT.raw(icon("chevron", "muted").replace("<svg", '<svg style="width:16px;height:16px"'))}</a>`.html).join("") + "</div>").join(""))}`.html;
      return;
    }
    const def = ALL[name];
    if (!def) throw new Error("Unknown report");
    const meta = QT.state.meta;
    const state = { preset: params.from || params.to ? "custom" : (params.preset || (name === "cashbook" ? "month" : "month")), from: params.from, to: params.to };
    if (state.preset !== "custom") { const [f, t] = QT.preset(state.preset); state.from = f; state.to = t; }
    const f = Object.assign({}, params);
    const customers = BOOKING_FILTERS.includes(name) || name === "customer_outstanding" ? await QT.get("/api/customers") : [];

    view.innerHTML = h`<div class="page-head"><div><div class="crumbs"><a href="#/reports">Reports</a></div><h1>${def[1]}</h1><div class="sub">${def[3]}</div></div>
      <div class="actions"><button class="btn" data-csv>${QT.raw(icon("download"))}Export CSV</button><button class="btn primary" data-print>${QT.raw(icon("print"))}Print / Save PDF</button></div></div>
      <div class="card" style="margin-bottom:16px"><div class="filters" data-filters>
        ${DATED.includes(name) ? QT.raw('<span data-range></span>') : ""}
        ${name === "monthly" ? QT.raw('<select class="inp" name="year">' + String(QT.options(Array.from({ length: 8 }, (_, i) => String(new Date().getFullYear() - i)), f.year || String(new Date().getFullYear()))) + "</select>") : ""}
        ${name === "yearly" ? QT.raw('<select class="inp" name="basis"><option value="">Calendar year</option><option value="fy"' + (f.basis === "fy" ? " selected" : "") + ">Financial year (Apr–Mar)</option></select>") : ""}
        ${name === "cashbook" ? QT.raw('<select class="inp" name="account_id">' + String(QT.options(meta.accounts.map((a) => [a.id, QT.accLabel(a)]), f.account_id || "", "All accounts")) + "</select>"
          + '<select class="inp" name="supplier_id">' + String(QT.options(meta.suppliers.map((s) => [s.id, s.name]), f.supplier_id || "", "All suppliers")) + "</select>"
          + '<select class="inp" name="kind">' + String(QT.options(Object.entries(meta.kinds), f.kind || "", "All types")) + "</select>") : ""}
        ${BOOKING_FILTERS.includes(name) ? QT.raw('<input class="inp" name="airline" placeholder="Airline" list="rep-airlines" value="' + esc(f.airline || "") + '" style="width:140px"><datalist id="rep-airlines">' + QT.AIRLINES.map((a) => '<option value="' + esc(a) + '">').join("") + "</datalist>"
          + '<select class="inp" name="customer_id">' + String(QT.options(customers.map((c) => [c.id, c.name]), f.customer_id || "", "All customers")) + "</select>"
          + '<select class="inp" name="supplier_id">' + String(QT.options(meta.suppliers.map((s) => [s.id, s.name]), f.supplier_id || "", "All suppliers")) + "</select>"
          + '<select class="inp" name="status">' + String(QT.options(Object.entries(QT.labels.status), f.status || "", "Any status")) + "</select>") : ""}
        ${BOOKING_FILTERS.includes(name) || name === "expenses" ? QT.raw('<select class="inp" name="currency">' + String(QT.options(meta.currencies.map((c) => c.code), f.currency || "", "Any currency")) + "</select>") : ""}
        ${name === "expenses" ? QT.raw('<select class="inp" name="category">' + String(QT.options(meta.expense_categories.map((c) => c.name), f.category || "", "All categories")) + "</select>") : ""}
        ${name === "customer_outstanding" || name === "supplier_outstanding" ? QT.raw('<select class="inp" name="show"><option value="">With a balance</option><option value="all"' + (f.show === "all" ? " selected" : "") + ">All " + (name === "customer_outstanding" ? "customers" : "suppliers") + "</option></select>") : ""}
      </div></div>
      <div data-summary></div>
      <div class="card"><div class="card-h"><h3 data-title>${def[1]}</h3><span class="sub" data-count></span></div><div data-table></div></div>
      <div class="muted small" style="margin-top:10px" data-note></div>`.html;
    if ($("[data-range]", view)) $("[data-range]", view).appendChild(QT.rangeControl(state, () => load()));
    let rep = null;
    const cfg = { rows: [], columns: [], emptyTitle: "No data for these filters", emptyIcon: "chart" };
    const draw = QT.renderTable($("[data-table]", view), cfg);
    function query() {
      const p = QT.formData($("[data-filters]", view));
      if (DATED.includes(name)) { p.from = state.from; p.to = state.to; }
      return p;
    }
    async function load() {
      const p = query();
      history.replaceState(null, "", "#/reports/" + name + QT.qs(p));
      rep = await QT.get("/api/reports/" + name + QT.qs(p));
      cfg.columns = rep.columns.map((c) => Object.assign({}, c, { type: c.type === "status" ? "status" : c.type }));
      cfg.rows = rep.rows;
      const cur = new Set(rep.rows.map((r) => r.currency).filter(Boolean));
      cfg.totalsCur = cur.size === 1 ? [...cur][0] : undefined;
      cfg.totals = rep.totals && Object.keys(rep.totals).length ? rep.totals : null;
      cfg.onRow = (r) => { if (r._booking_id) QT.go("#/bookings/" + r._booking_id); else if (r._customer_id) QT.go("#/customers/" + r._customer_id); else if (r._supplier_id) QT.go("#/suppliers/" + r._supplier_id); else if (r._txn_id) QT.txnDetail(r._txn_id); };
      draw();
      $("[data-title]", view).textContent = rep.title;
      $("[data-count]", view).textContent = rep.rows.length + " rows · " + rep.base_currency;
      $("[data-note]", view).textContent = rep.note || "";
      const sum = $("[data-summary]", view);
      sum.innerHTML = rep.summary && rep.summary.length ? '<div class="kpis" style="margin-bottom:16px">' + rep.summary.map((s) => h`<div class="kpi ${s.strong ? "hero" : ""}"><div class="k-top">${s.label}</div><div class="k-val ${!s.strong && s.value < 0 ? "neg" : ""}">${QT.money(s.value, s.raw && cfg.rows[0] ? cfg.rows[0].currency : undefined)}</div></div>`.html).join("") + "</div>" : "";
    }
    $$("[data-filters] select, [data-filters] input[name]", view).forEach((i) => i.addEventListener(i.tagName === "INPUT" ? "change" : "change", () => load()));
    $("[data-csv]", view).onclick = () => QT.download("/api/reports/" + name + QT.qs(Object.assign(query(), { format: "csv" }))).catch(QT.fail);
    $("[data-print]", view).onclick = () => QT.print(reportHtml(rep, state, DATED.includes(name)), rep.title);
    await load();
  };

  function reportHtml(rep, state, dated) {
    const cols = rep.columns;
    const money = (c) => c.type === "money" || c.type === "money_raw";
    const fmtCell = (c, r) => {
      const v = r[c.key];
      if (money(c)) return QT.money(v, c.type === "money_raw" ? r.currency : undefined, { nosym: true });
      if (c.type === "date") return esc(QT.date(v));
      if (c.type === "status") return esc(QT.labels.status[v] || QT.labels.pay[v] || v);
      return esc(v === null || v === undefined ? "" : v);
    };
    const filt = Object.entries(rep.params || {}).filter(([k]) => !["from", "to", "format"].includes(k)).map(([k, v]) => esc(k.replace(/_/g, " ") + ": " + v)).join(" · ");
    return h`<div class="doc" style="max-width:none">${QT.docHead(rep.title, (dated ? "<div>" + esc(state.from ? QT.date(state.from) : "Start") + " – " + esc(state.to ? QT.date(state.to) : QT.date(QT.today())) + "</div>" : "") + "<div>Amounts in " + esc(rep.base_currency) + "</div>" + (filt ? "<div>" + filt + "</div>" : ""))}
      ${rep.summary && rep.summary.length ? QT.raw('<div class="doc-meta" style="grid-template-columns:repeat(4,1fr)">' + rep.summary.map((s) => '<div class="box"><div class="lbl">' + esc(s.label) + '</div><b style="font-size:14px">' + QT.money(s.value) + "</b></div>").join("") + "</div>") : ""}
      <table style="font-size:10.5px"><thead><tr>${QT.raw(cols.map((c) => '<th class="' + (money(c) || c.type === "number" ? "r" : "") + '">' + esc(c.label) + "</th>").join(""))}</tr></thead>
      <tbody>${QT.raw(rep.rows.map((r) => "<tr>" + cols.map((c) => '<td class="' + (money(c) || c.type === "number" ? "r" : "") + '">' + fmtCell(c, r) + "</td>").join("") + "</tr>").join(""))}</tbody>
      ${rep.totals && Object.keys(rep.totals).length ? QT.raw("<tfoot><tr>" + cols.map((c, i) => '<td class="' + (money(c) || c.type === "number" ? "r" : "") + '" style="font-weight:800;border-top:2px solid #0A1A3F">' + (rep.totals[c.key] !== undefined ? (money(c) ? QT.money(rep.totals[c.key], null, { nosym: true }) : esc(rep.totals[c.key])) : i === 0 ? "TOTAL" : "") + "</td>").join("") + "</tr></tfoot>") : ""}
      </table>${rep.note ? QT.raw('<div style="margin-top:10px;color:#64738F">' + esc(rep.note) + "</div>") : ""}${QT.docFoot()}</div>`;
  }
})();
