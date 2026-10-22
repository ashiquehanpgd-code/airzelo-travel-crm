/* Dashboard */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  function kpi(label, value, ic, cls, sub, href) {
    return h`<${QT.raw(href ? 'a href="' + href + '"' : "div")} class="kpi ${cls || ""}" style="${href ? "color:inherit;text-decoration:none" : ""}">
      <div class="k-top"><span class="k-ico">${QT.raw(icon(ic))}</span>${label}</div>
      <div class="k-val">${value}</div>
      ${sub ? QT.raw('<div class="k-sub">' + String(sub) + "</div>") : ""}
    </${QT.raw(href ? "a" : "div")}>`;
  }

  function greeting() {
    const hr = new Date().getHours();
    return hr < 12 ? "Good morning" : hr < 17 ? "Good afternoon" : "Good evening";
  }

  QT.pages.dashboard = async function (view) {
    const state = JSON.parse(QT.pref.get("dash_range", "null") || "null") || { preset: "month" };
    if (state.preset !== "custom") { const [f, t] = QT.preset(state.preset); state.from = f; state.to = t; }

    view.innerHTML = h`<div class="page-head">
      <div><h1>${greeting()}</h1><div class="sub">${QT.state.meta.settings.business_name} · ${QT.date(QT.today())}</div></div>
      <div class="actions" data-range></div>
    </div><div data-body>${QT.loading()}</div>`.html;
    const body = $("[data-body]", view);
    $("[data-range]", view).appendChild(QT.rangeControl(state, () => { QT.pref.set("dash_range", JSON.stringify(state)); load(); }));

    async function load() {
      const d = await QT.get("/api/dashboard" + QT.qs({ from: state.from, to: state.to }));
      render(d);
    }

    function render(d) {
      const s = d.summary;
      const c = s.cash;
      const periodLabel = state.preset === "custom" ? QT.date(state.from) + " – " + QT.date(state.to) : { today: "Today", week: "This week", month: "This month", year: "This year", fy: "This financial year" }[state.preset];
      const margin = s.sales ? ((s.gross_profit / s.sales) * 100).toFixed(1) + "% margin" : "No sales yet";
      body.innerHTML = h`
        <div class="kpis" style="margin-bottom:16px">
          ${kpi("Sales · " + periodLabel, QT.money(s.sales), "trend", "hero", s.bookings + " bookings · ex tax")}
          ${kpi("Gross profit", QT.money(s.gross_profit), "coins", "c-green", margin)}
          ${kpi("Net profit", QT.money(s.net_profit), "percent", s.net_profit < 0 ? "c-red" : "c-green", "after " + QT.money(s.operating_expenses) + " expenses")}
          ${kpi("Payments received", QT.money(c.customer_receipts), "arrowIn", "c-cyan", "actual cash in", "#/finance/receipts")}
          ${kpi("Customer outstanding", QT.money(d.customer_outstanding), "users", d.customer_outstanding ? "c-warn" : "", d.pending_count + " bookings pending", "#/reports/customer_outstanding")}
          ${kpi("Supplier outstanding", QT.money(d.supplier_outstanding), "building", d.supplier_outstanding ? "c-red" : "", d.supplier_credit_balances ? QT.money(d.supplier_credit_balances) + " due from suppliers" : "owed to portals", "#/reports/supplier_outstanding")}
          ${kpi("Ticket purchase cost", QT.money(s.cost), "ticket", "", "net of supplier credits")}
          ${kpi("Ticket markup", QT.money(s.markup), "trend", "c-violet", "selling − purchase")}
          ${kpi("Commission earned", QT.money(s.commission), "percent", "c-cyan", s.commission_reversed ? QT.money(s.commission_reversed) + " reversed" : "net of reversals", "#/reports/commission")}
          ${kpi("Operating expenses", QT.money(s.operating_expenses), "expense", "c-red", QT.money(s.direct_expenses) + " direct booking costs", "#/finance/expenses")}
          ${kpi("Refunds & cancellations", String(d.cancellations_count), "refund", "c-warn", QT.money(c.customer_refunds) + " refunded to customers", "#/reports/refunds")}
          ${kpi("Net cash flow", QT.money(c.net_cash_flow), "swap", c.net_cash_flow < 0 ? "c-red" : "c-green", QT.money(c.cash_in) + " in · " + QT.money(c.cash_out) + " out", "#/reports/cashbook")}
        </div>

        <div class="grid g3 period-cards" style="margin-bottom:16px">
          ${QT.raw([["Today", "calendar", "", d.today], ["This month", "chart", "cyan", d.month], ["This year", "trend", "green", d.year]].map(([label, ic, cls, p]) => h`<div class="card card-b"><div class="li" style="padding:0;border:0"><div class="ic ${cls}">${QT.raw(icon(ic))}</div><div class="grow"><div class="t2">${label}</div><div class="t1">${p.bookings} booking${p.bookings === 1 ? "" : "s"} · ${QT.money(p.sales)}</div>${p.gross_profit !== undefined ? QT.raw('<div class="gp ' + (p.gross_profit < 0 ? "neg" : "pos") + '">Gross profit ' + QT.money(p.gross_profit) + "</div>") : ""}</div></div></div>`.html).join(""))}
        </div>

        <div class="grid g-main" style="margin-bottom:16px">
          <div class="card">
            <div class="card-h"><h3>Revenue & profit</h3><span class="sub">${periodLabel}</span>
              <div class="actions legend"><span><i style="background:var(--royal)"></i>Net sales</span><span><i style="background:var(--success)"></i>Gross profit</span></div></div>
            <div class="card-b" data-chart></div>
          </div>
          <div class="card">
            <div class="card-h"><h3>Profit breakdown</h3><span class="sub">${periodLabel}</span></div>
            <div class="card-b" data-breakdown></div>
          </div>
        </div>

        <div class="grid g-main dash-recent" style="margin-bottom:16px">
          <div class="card">
            <div class="card-h"><h3>Recent bookings</h3><div class="actions"><a class="btn sm" href="#/bookings">View all</a><a class="btn sm primary" href="#/bookings/new">${QT.raw(icon("plus"))}New booking</a></div></div>
            <div class="card-b flush" data-recent></div>
          </div>
          <div class="card">
            <div class="card-h"><h3>Cash & bank</h3><div class="actions"><a class="btn sm" href="#/accounts">Manage</a></div></div>
            <div class="card-b flush" data-accounts></div>
          </div>
        </div>

        <div class="grid g3">
          <div class="card"><div class="card-h"><h3>Upcoming departures</h3><span class="sub">next 14 days</span></div><div class="card-b flush" data-upcoming></div></div>
          <div class="card"><div class="card-h"><h3>Pending customer payments</h3><div class="actions"><a class="btn xs" href="#/reports/customer_outstanding">All</a></div></div><div class="card-b flush" data-pending></div></div>
          <div class="card"><div class="card-h"><h3>Follow-ups</h3><span class="sub">due & upcoming</span></div><div class="card-b flush" data-follow></div></div>
        </div>
        <div class="card" style="margin-top:16px"><div class="card-h"><h3>Recent transactions</h3><div class="actions"><a class="btn sm" href="#/finance/all">View all</a></div></div><div class="card-b flush" data-txns></div></div>
      `.html;

      // chart (after layout so width is known)
      requestAnimationFrame(() => {
        const ch = $("[data-chart]", body);
        if (!ch) return;
        if (!d.chart.some((x) => x.sales || x.gross_profit)) {
          ch.innerHTML = '<div class="empty-state">' + icon("chart") + "<h4>No sales in this period</h4><div>Bookings you confirm or ticket will appear here.</div></div>";
        } else {
          QT.chart(ch, d.chart, { bars: [{ key: "sales", label: "Net sales", cls: "bar" }], line: { key: "gross_profit", label: "Gross profit" } });
        }
      });

      // breakdown bars
      const parts = [
        ["Markup", s.markup], ["Commission", s.commission], ["Service charges", s.service], ["Other booking income", s.other_booking_income],
        ["Credits & adjustments", s.sales - (s.selling + s.service + s.other_booking_income) - (s.cost - s.purchase)],
        ["Direct booking costs", -s.direct_expenses], ["Other income", s.other_income], ["Operating expenses", -s.operating_expenses],
      ];
      const maxv = Math.max(1, ...parts.map((p) => Math.abs(p[1])));
      let bd = "";
      parts.forEach(([l, v]) => {
        bd += h`<div class="hbar"><span class="muted">${l}</span><div class="track"><div class="fill ${v < 0 ? "neg" : ""}" style="width:${Math.round((Math.abs(v) / maxv) * 100)}%"></div></div><span class="num ${v < 0 ? "neg" : ""}">${QT.money(v)}</span></div>`.html;
      });
      bd += h`<div class="sum-row total big" style="margin-top:10px"><span>Net profit</span><span class="v ${s.net_profit < 0 ? "neg" : "pos"}">${QT.money(s.net_profit)}</span></div>
        <div class="sum-row"><span class="muted">Tax collected (liability, not profit)</span><span class="v">${QT.money(s.tax)}</span></div>`.html;
      $("[data-breakdown]", body).innerHTML = bd;

      // recent bookings
      const rb = $("[data-recent]", body);
      QT.renderTable(rb, {
        rows: d.recent_bookings, sortable: false, emptyTitle: "No bookings yet", empty: "Create your first booking to get started.", emptyIcon: "ticket",
        onRow: (r) => QT.go("#/bookings/" + r.id),
        columns: [
          { label: "Booking", render: (r) => h`<div class="cell-main">${r.ref}</div><div class="cell-sub">${QT.date(r.booking_date)}</div>` },
          { label: "PNR", render: (r) => r.pnr ? h`<span class="pnr">${r.pnr}</span>` : "" },
          { label: "Passenger", render: (r) => h`<div class="cell-main">${r.lead_pax}</div><div class="cell-sub">${r.customer_name}${r.pax_count > 1 ? " · " + r.pax_count + " pax" : ""}</div>` },
          { label: "Route", render: (r) => h`<div>${r.route}</div><div class="cell-sub">${r.airlines.join(", ")}</div>` },
          { label: "Status", render: (r) => QT.statusBadge(r.status) },
          { label: "Outstanding", type: "money", key: "customer_outstanding" },
        ],
      });

      // accounts
      const acc = d.accounts.filter((a) => a.active);
      $("[data-accounts]", body).innerHTML = acc.length ? acc.map((a) => h`<div class="li click" data-acc="${a.account_id}"><div class="ic ${a.kind === "cash" ? "green" : "cyan"}">${QT.raw(icon(a.kind === "cash" ? "wallet" : "bank"))}</div>
        <div class="grow"><div class="t1">${a.name}</div><div class="t2">${QT.labels.acc[a.kind]} · ${a.currency}</div></div><div class="amt ${a.balance < 0 ? "neg" : ""}">${QT.money(a.balance, a.currency)}</div></div>`.html).join("")
        : '<div class="empty-state">No accounts</div>';
      $$("[data-acc]", body).forEach((x) => { x.onclick = () => QT.go("#/reports/cashbook?account_id=" + x.dataset.acc); });

      // upcoming
      $("[data-upcoming]", body).innerHTML = d.upcoming.length ? d.upcoming.map((u) => h`<div class="li click" data-b="${u.booking_id}">
        <div class="ic">${QT.raw(icon("plane"))}</div>
        <div class="grow"><div class="t1">${u.origin && u.destination ? u.origin + " → " + u.destination : u.ref} <span class="muted small">${u.flight_no || ""}</span></div>
        <div class="t2">${u.lead_pax}${u.pax_count > 1 ? " +" + (u.pax_count - 1) : ""} · ${u.pnr || u.ref}</div></div>
        <div class="right"><div class="small" style="font-weight:600">${QT.dateShort(u.dep_at)}</div><div class="t2">${QT.time(u.dep_at)}</div>${u.outstanding > 0 ? QT.raw('<span class="badge b-warn plain">Due ' + QT.money(u.outstanding) + "</span>") : ""}</div></div>`.html).join("")
        : '<div class="empty-state">' + icon("plane") + "<div>No departures in the next 14 days</div></div>";

      $("[data-pending]", body).innerHTML = d.pending.length ? d.pending.map((p) => h`<div class="li click" data-b="${p.id}">
        <div class="ic warn">${QT.initials(p.customer_name)}</div>
        <div class="grow"><div class="t1">${p.customer_name}</div><div class="t2">${p.ref}${p.pnr ? " · " + p.pnr : ""}${p.travel_date ? " · travels " + QT.dateShort(p.travel_date) : ""}</div></div>
        <div class="amt neg">${QT.money(p.customer_outstanding)}</div></div>`.html).join("")
        : '<div class="empty-state">' + icon("check") + "<div>All customer payments are up to date</div></div>";

      $("[data-follow]", body).innerHTML = d.follow_ups.length ? d.follow_ups.map((f) => h`<div class="li click" data-c="${f.customer_id}">
        <div class="ic ${f.overdue ? "red" : "cyan"}">${QT.raw(icon("bell"))}</div>
        <div class="grow"><div class="t1">${f.name}</div><div class="t2">${f.follow_up_note || f.phone || ""}</div></div>
        <div class="right small ${f.overdue ? "neg" : ""}" style="font-weight:600">${f.overdue ? "Overdue · " : ""}${QT.dateShort(f.follow_up_date)}</div></div>`.html).join("")
        : '<div class="empty-state">' + icon("bell") + "<div>No follow-ups scheduled. Add one from a customer profile.</div></div>";

      $("[data-txns]", body).innerHTML = d.recent_transactions.length ? d.recent_transactions.map((t) => QT.txnLi(t)).join("")
        : '<div class="empty-state">' + icon("receipt") + "<div>No transactions recorded yet</div></div>";
      QT.bindTxnLis($("[data-txns]", body));

      $$("[data-b]", body).forEach((x) => { x.onclick = () => QT.go("#/bookings/" + x.dataset.b); });
      $$("[data-c]", body).forEach((x) => { x.onclick = () => QT.go("#/customers/" + x.dataset.c); });
    }

    await load();
  };
})();
