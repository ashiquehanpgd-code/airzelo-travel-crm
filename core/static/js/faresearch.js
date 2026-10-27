/* AirZelo - fare search: one search, opened on each supplier's own portal in the user's browser, quotes typed in
   by hand and compared with estimated profit. AirZelo never signs in, searches, books or pays on a portal. */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  const CABINS = ["Economy", "Premium Economy", "Business", "First"];
  const SUP_STATUS = { pending: ["Not opened", "b-gray"], opened: ["Opened", "b-blue"], searched: ["Searched", "b-blue"],
    quoted: ["Quoted", "b-green"], no_fare: ["No fare", "b-warn"], failed: ["Failed", "b-red"] };
  const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  QT.fareSearchOn = () => !!(QT.state.meta && QT.state.meta.settings.fare_search_enabled === "1");

  // amounts the user did not enter are unavailable, never zero
  const amt = (minor) => (minor === null || minor === undefined ? '<span class="muted small" title="Not entered">unavailable</span>' : QT.money(minor));
  const paxText = (f) => [f.adults + " adult" + (f.adults > 1 ? "s" : ""), f.children ? f.children + " child" + (f.children > 1 ? "ren" : "") : "",
    f.infants ? f.infants + " infant" + (f.infants > 1 ? "s" : "") : ""].filter(Boolean).join(", ");
  const routeText = (f) => f.origin + (f.trip_type === "round_trip" ? " ⇄ " : " → ") + f.destination;
  function dateForms(iso) {
    if (!iso) return [];
    const [y, m, d] = iso.split("-");
    return [d + " " + MON[+m - 1] + " " + y, d + "-" + MON[+m - 1] + "-" + y, d + "/" + m + "/" + y, iso];
  }
  function ageText(sec) {
    if (sec === null || sec === undefined) return "time unknown";
    if (sec < 60) return "just now";
    const m = Math.floor(sec / 60);
    if (m < 60) return m + " min ago";
    const hrs = Math.floor(m / 60);
    return hrs < 24 ? hrs + " h " + (m % 60) + " min ago" : Math.floor(hrs / 24) + " day(s) ago";
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); } catch (e) {
      const ta = el('<textarea style="position:fixed;top:-1000px;opacity:0"></textarea>');
      ta.value = text; document.body.appendChild(ta); ta.select(); document.execCommand("copy"); ta.remove();
    }
    QT.toast("Copied: " + (text.length > 40 ? text.slice(0, 40) + "…" : text));
  }
  // the official portal page opens in the user's normal browser (the desktop app hands https links to it);
  // no referrer and no window handle, so the portal and AirZelo cannot reach each other
  function openPortal(url) {
    window.open(url, "_blank", "noopener,noreferrer");
  }

  // ---------------------------------------------------------------- list + new search
  QT.pages.fares = async function (view, params) {
    if (!QT.fareSearchOn()) { view.innerHTML = String(offState()); return; }
    const [portals, searches] = await Promise.all([QT.get("/api/fare/portals"), QT.get("/api/fare/searches")]);
    const usable = portals.filter((p) => p.login_url && p.active && p.supplier_active);
    view.innerHTML = h`<div class="page-head"><div><h1>Fare search</h1>
        <div class="sub">One search, opened on each supplier's own portal in your browser. You sign in and search there, then type the fares you see here to compare them. AirZelo never signs in, searches, books or pays on a portal.</div></div>
      <div class="actions"><a class="btn" href="#/settings/fares">${QT.raw(icon("settings"))}Portals</a></div></div>
      <div class="grid g-side">
        <div class="card"><div class="card-h"><h3>New search</h3></div>
          <form class="card-b form-grid" data-form autocomplete="off" onsubmit="return false">
            ${QT.field({ name: "origin", label: "From", span: 3, req: true, cls: "upper mono", placeholder: "COK", value: params.origin || "", attrs: 'maxlength="3"' })}
            ${QT.field({ name: "destination", label: "To", span: 3, req: true, cls: "upper mono", placeholder: "DXB", value: params.destination || "", attrs: 'maxlength="3"' })}
            ${QT.field({ name: "trip_type", label: "Trip", type: "select", span: 3, opts: QT.options([["one_way", "One way"], ["round_trip", "Return"]], "one_way") })}
            ${QT.field({ name: "cabin", label: "Cabin", type: "select", span: 3, opts: QT.options(CABINS, "Economy") })}
            ${QT.field({ name: "depart_date", label: "Departure", type: "date", span: 3, req: true })}
            <div class="f s3" data-ret>${QT.raw('<label>Return</label><input class="inp" type="date" name="return_date" disabled>')}</div>
            ${QT.field({ name: "adults", label: "Adults", type: "number", span: 2, value: 1, attrs: 'min="1" max="9"' })}
            ${QT.field({ name: "children", label: "Children", type: "number", span: 2, value: 0, attrs: 'min="0" max="8"' })}
            ${QT.field({ name: "infants", label: "Infants", type: "number", span: 2, value: 0, attrs: 'min="0" max="4"' })}
            ${QT.field({ name: "baggage", label: "Baggage needed", span: 6, placeholder: "e.g. 30 kg check-in + 7 kg cabin" })}
            ${QT.field({ name: "selling_price", label: "Your selling price (optional)", type: "money", span: 3, hint: "Total for all passengers, for profit" })}
            ${QT.field({ name: "direct_only", label: "Direct flights only", type: "checkbox", span: 3 })}
            <div class="f s12"><label>Open these portals</label>
              ${QT.raw(usable.length ? '<div class="row" style="flex-wrap:wrap;gap:6px 16px">' + usable.map((p) => '<label class="check"><input type="checkbox" data-sup value="' + p.supplier_id + '" checked> ' + esc(p.name) + "</label>").join("") + "</div>"
                : '<div class="alert warn small">' + icon("alert") + '<div>No portal addresses yet. Add each supplier\'s official sign-in address in <a href="#/settings/fares">Settings → Fare search</a>.</div></div>')}</div>
            <div class="f s12"><div class="row" style="gap:10px;align-items:center"><button class="btn primary" type="button" data-go ${usable.length ? "" : "disabled"}>${QT.raw(icon("search"))}Search &amp; open portals</button>
              <span class="muted small">Your browser may ask to allow several tabs. You can also open each portal from the search page.</span></div></div>
          </form></div>
        <div class="card"><div class="card-h"><h3>Recent searches</h3></div><div data-list></div></div>
      </div>`.html;
    const form = $("[data-form]", view);
    const trip = $("[name=trip_type]", form), ret = $("[name=return_date]", form);
    trip.onchange = () => { ret.disabled = trip.value !== "round_trip"; if (ret.disabled) ret.value = ""; };
    const idem = QT.uuid();
    $("[data-go]", view).onclick = async () => {
      const d = QT.formData(form);
      d.origin = d.origin.trim().toUpperCase(); d.destination = d.destination.trim().toUpperCase();
      d.supplier_ids = $$("[data-sup]", form).filter((c) => c.checked).map((c) => +c.value);
      d.idem_key = idem;
      // quick checks first, so tabs only open for a search that will be saved
      if (!/^[A-Z]{3}$/.test(d.origin)) return QT.showErr(form, { message: "From must be a 3-letter airport code", field: "origin" });
      if (!/^[A-Z]{3}$/.test(d.destination)) return QT.showErr(form, { message: "To must be a 3-letter airport code", field: "destination" });
      if (!d.depart_date) return QT.showErr(form, { message: "Departure date is required", field: "depart_date" });
      if (d.trip_type === "round_trip" && !d.return_date) return QT.showErr(form, { message: "Return date is required", field: "return_date" });
      if (!d.supplier_ids.length) return QT.toast("Choose at least one portal", "err");
      // tabs must open inside the click, before any network wait, or the browser blocks them
      usable.filter((p) => d.supplier_ids.includes(p.supplier_id)).forEach((p) => openPortal(p.login_url));
      try {
        const r = await QT.post("/api/fare/searches", d);
        await Promise.all(r.suppliers.map((s) => QT.post("/api/fare/searches/" + r.search.id + "/suppliers/" + s.supplier_id, { status: "opened" })));
        QT.go("#/fares/" + r.search.id);
      } catch (e) { QT.showErr(form, e); }
    };
    QT.renderTable($("[data-list]", view), { rows: searches, emptyTitle: "No searches yet", empty: "Your fare searches will be listed here.", emptyIcon: "search",
      onRow: (r) => QT.go("#/fares/" + r.id), columns: [
        { label: "Search", key: "id", render: (r) => h`<span class="cell-main">${routeText(r)}</span><div class="cell-sub">${r.search_no} · ${QT.date(r.depart_date)}${r.return_date ? " – " + QT.date(r.return_date) : ""} · ${paxText(r)}</div>` },
        { label: "Quotes", key: "quotes", type: "number" },
        { label: "Lowest payable", key: "lowest_payable", render: (r) => (r.lowest_payable ? QT.money(r.lowest_payable) : "—") },
        { label: "Status", key: "status", render: (r) => QT.badge(r.status === "open" ? "Open" : "Closed", r.status === "open" ? "b-blue" : "b-gray") },
      ] });
  };

  function offState() {
    return h`<div class="empty-state">${QT.raw(icon("search"))}<h4>Fare search is switched off</h4>
      <div>Switch it on in <a href="#/settings/fares">Settings → Fare search</a>.</div></div>`;
  }

  // ---------------------------------------------------------------- one search: copy details, portals, quotes
  QT.pages.fare = async function (view, params, id) {
    if (!QT.fareSearchOn()) { view.innerHTML = String(offState()); return; }
    const data = await QT.get("/api/fare/searches/" + id);
    const f = data.search;
    const loadedAt = Date.now();
    const copyItems = [["From", f.origin], ["To", f.destination]]
      .concat(dateForms(f.depart_date).map((x, i) => [i ? "" : "Departure", x]))
      .concat(f.return_date ? dateForms(f.return_date).map((x, i) => [i ? "" : "Return", x]) : [])
      .concat([["Passengers", paxText(f)], ["Cabin", f.cabin]])
      .concat(f.baggage ? [["Baggage", f.baggage]] : []);
    const summary = routeText(f) + " · " + (f.trip_type === "round_trip" ? "Return" : "One way") + " · " + dateForms(f.depart_date)[0] +
      (f.return_date ? " – " + dateForms(f.return_date)[0] : "") + " · " + paxText(f) + " · " + f.cabin + (f.direct_only ? " · direct only" : "") + (f.baggage ? " · baggage: " + f.baggage : "");

    view.innerHTML = h`<div class="page-head"><div><div class="crumbs"><a href="#/fares">Fare search</a> / ${f.search_no}</div>
        <h1>${routeText(f)} ${QT.raw(String(QT.badge(f.status === "open" ? "Open" : "Closed", f.status === "open" ? "b-blue" : "b-gray")))}</h1>
        <div class="sub">${summary}</div></div>
      <div class="actions"><button class="btn" data-openall>${QT.raw(icon("link"))}Open all portals</button><button class="btn ghost" data-more>${QT.raw(icon("more"))}More</button></div></div>
      <div class="alert info small" style="margin-bottom:14px">${QT.raw(icon("shield"))}<div>Sign in on each portal yourself. AirZelo never sees your supplier passwords or OTPs and never books, issues or pays. Fares below are what you typed in, with the time you saw them. A fare older than ${data.stale_minutes} minutes is marked <b>stale</b>; re-check it in the portal before booking.</div></div>
      <div class="grid g-side" style="margin-bottom:16px">
        <div class="card"><div class="card-h"><h3>Suppliers</h3><div class="sub">Open the portal, search, then add the fare you see</div></div><div class="card-b flush" data-sups></div></div>
        <div class="stack" style="gap:16px">
          <div class="card"><div class="card-h"><h3>Search details</h3><div class="actions"><button class="btn sm" data-copyall>${QT.raw(icon("copy"))}Copy all</button></div></div>
            <div class="card-b" style="display:flex;flex-wrap:wrap;gap:6px" data-copy>${QT.raw(copyItems.map((c, i) => '<button class="btn sm" type="button" data-c="' + i + '" title="Copy">' + (c[0] ? '<span class="muted">' + esc(c[0]) + ":</span>&nbsp;" : "") + '<span class="mono">' + esc(c[1]) + "</span></button>").join(""))}</div></div>
          <div class="card"><div class="card-h"><h3>Your selling price</h3></div><div class="card-b form-grid" data-sp>
            ${QT.field({ name: "selling_price", label: "Total for all passengers", type: "money", span: 8, value: f.selling_price === null ? "" : QT.plain(f.selling_price) })}
            <div class="f s4" style="justify-content:flex-end"><button class="btn" type="button" data-savesp>Save</button></div>
            <div class="f s12 muted small">Estimated profit = selling price − final payable − payment charges + confirmed commission. GST on your service and conditional incentives are not included.</div></div></div>
        </div>
      </div>
      <div class="card"><div class="card-h"><h3>Comparison</h3><div class="sub" data-best></div></div><div data-quotes></div></div>`.html;

    $$("[data-c]", view).forEach((b) => { b.onclick = () => copy(copyItems[+b.dataset.c][1]); });
    $("[data-copyall]", view).onclick = () => copy(summary);
    $("[data-savesp]", view).onclick = async () => {
      try { await QT.put("/api/fare/searches/" + f.id, { selling_price: $("[name=selling_price]", view).value }); QT.toast("Selling price saved"); QT.reload(); }
      catch (e) { QT.showErr($("[data-sp]", view), e); }
    };
    const setStatus = async (s, status, note) => {
      try { await QT.post("/api/fare/searches/" + f.id + "/suppliers/" + s.supplier_id, { status, note: note || "" }); } catch (e) { QT.fail(e); }
    };
    $("[data-openall]", view).onclick = async () => {
      const list = data.suppliers.filter((s) => s.login_url && s.portal_active);
      list.forEach((s) => openPortal(s.login_url));
      await Promise.all(list.filter((s) => s.status === "pending").map((s) => setStatus(s, "opened")));
      QT.reload();
    };
    QT.dropdown($("[data-more]", view), [
      f.status === "open" ? { label: "Close this search", icon: "check", onClick: async () => { await QT.put("/api/fare/searches/" + f.id, { status: "closed" }); QT.reload(); } }
        : { label: "Reopen this search", icon: "refund", onClick: async () => { await QT.put("/api/fare/searches/" + f.id, { status: "open" }); QT.reload(); } },
      { label: "Delete search and its quotes", icon: "trash", onClick: async () => {
        if (!(await QT.confirm({ title: "Delete " + f.search_no + "?", message: "The search and its " + data.quotes.length + " quote(s) are removed. Bookings are not affected.", ok: "Delete", danger: true }))) return;
        try { await QT.del("/api/fare/searches/" + f.id); QT.go("#/fares"); } catch (e) { QT.fail(e); }
      } },
    ]);

    // suppliers
    const supBox = $("[data-sups]", view);
    supBox.innerHTML = data.suppliers.map((s) => {
      const st = SUP_STATUS[s.status] || [s.status, "b-gray"];
      return h`<div class="li"><div class="grow"><div class="t1">${s.name} ${QT.badge(st[0], st[1])}</div>
        <div class="t2">${s.search_hint || ""}${s.note ? " · " + s.note : ""}${!s.login_url || !s.portal_active ? " · portal address missing or switched off" : ""}</div></div>
        <div class="row" style="gap:6px;flex-wrap:wrap;justify-content:flex-end">
          ${QT.raw(s.login_url && s.portal_active ? '<button class="btn sm" data-open="' + s.supplier_id + '">' + icon("link") + "Open portal</button>" : "")}
          <button class="btn sm primary" data-add="${s.supplier_id}">${QT.raw(icon("plus"))}Add fare</button>
          <button class="btn sm ghost" data-st="${s.supplier_id}" title="Mark result">${QT.raw(icon("more"))}</button></div></div>`.html;
    }).join("") || '<div class="empty-state small">No suppliers in this search</div>';
    const bySid = (sid) => data.suppliers.find((s) => String(s.supplier_id) === String(sid));
    $$("[data-open]", supBox).forEach((b) => { b.onclick = async () => {
      const s = bySid(b.dataset.open);
      openPortal(s.login_url);
      if (s.status === "pending") { await setStatus(s, "opened"); QT.reload(); }
    }; });
    $$("[data-add]", supBox).forEach((b) => { b.onclick = () => quoteForm(f, bySid(b.dataset.add), () => QT.reload()); });
    $$("[data-st]", supBox).forEach((b) => { QT.dropdown(b, [
      { label: "Searched, adding fares", icon: "search", onClick: async () => { await setStatus(bySid(b.dataset.st), "searched"); QT.reload(); } },
      { label: "No suitable fare", icon: "x", onClick: async () => { await setStatus(bySid(b.dataset.st), "no_fare"); QT.reload(); } },
      { label: "Search failed / session expired…", icon: "alert", onClick: async () => {
        const note = await QT.ask({ title: "What went wrong?", label: "Note", ok: "Save", required: false, message: "For example: signed out, OTP not received, portal error. This supplier is skipped; the others are not affected." });
        if (note === null) return;
        await setStatus(bySid(b.dataset.st), "failed", note); QT.reload();
      } },
    ]); });

    // comparison table
    const quotes = data.quotes;
    const ageNow = (q) => (q.age_seconds === null ? null : q.age_seconds + Math.floor((Date.now() - loadedAt) / 1000));
    const isStale = (q) => q.stale || (ageNow(q) !== null && ageNow(q) >= data.stale_minutes * 60);
    const current = () => quotes.filter((q) => !q.superseded && !isStale(q));
    const qBox = $("[data-quotes]", view);
    const cfg = { rows: quotes, emptyTitle: "No fares entered yet", empty: "Open a portal, search, then use Add fare.", emptyIcon: "ticket",
      rowClass: (q) => (q.superseded ? "reversed" : ""), columns: [
        { label: "Supplier", key: "supplier_name", render: (q) => h`<span class="cell-main">${q.supplier_name}</span><div class="cell-sub">${q.quote_no}${q.source === "recheck" ? " · re-checked" : ""}</div>` },
        { label: "Flight", key: "dep_at", render: (q) => h`<div style="min-width:150px"><span class="cell-main">${q.airline || "—"}</span> <span class="mono nowrap">${q.flight_nos || ""}</span><div class="cell-sub">${q.dep_at ? QT.dateTime(q.dep_at) : "time not entered"}${q.arr_at ? " → " + QT.time(q.arr_at) : ""} · ${q.stops === null ? "stops ?" : q.stops === 0 ? "direct" : q.stops + " stop" + (q.stops > 1 ? "s" : "")}${q.duration ? " · " + q.duration : ""}</div></div>` },
        { label: "Fare & conditions", key: "fare_family", render: (q) => h`<div style="min-width:130px"><span>${q.fare_family || "fare family ?"}</span><div class="cell-sub">${q.baggage || "baggage ?"}${q.refund_terms ? " · " + q.refund_terms : " · refund terms ?"}</div></div>` },
        { label: "Base · taxes · fees", key: "base_fare", cls: "money", render: (q) => '<div class="small nowrap">' + amt(q.base_fare) + "</div><div class=\"small nowrap\">" + amt(q.taxes) + "</div><div class=\"small nowrap\">" + amt(q.fees) + "</div>" },
        { label: "Final payable", key: "final_payable", render: (q) => '<b class="nowrap">' + QT.money(q.final_payable) + "</b>", cls: "money" },
        { label: "Payment chg. · commission", key: "payment_charges", cls: "money", render: (q) => '<div class="small nowrap">' + amt(q.payment_charges) + '</div><div class="small nowrap">' + amt(q.commission) + "</div>" },
        { label: "Est. profit", key: "est_profit", render: (q) => (q.est_profit === null ? '<span class="muted small">' + esc(q.profit_note) + "</span>"
          : '<span class="' + QT.moneyCls(q.est_profit) + '" title="' + esc(q.profit_note || "") + '">' + QT.money(q.est_profit) + "</span>" + (q.profit_note ? ' <span class="muted small" title="' + esc(q.profit_note) + '">*</span>' : "")), cls: "money" },
        { label: "Seen", key: "seen_at", render: (q) => h`<span class="nowrap">${QT.time(q.seen_at)}</span><div class="cell-sub" data-age="${q.id}">${ageText(ageNow(q))}</div>${isStale(q) ? QT.badge("Stale", "b-warn") : QT.badge("Fresh", "b-green")}` },
        { label: "", sortable: false, render: (q) => (q.superseded ? '<span class="muted small">replaced by re-check</span>'
          : '<div class="row" style="gap:4px"><button class="btn xs primary" data-use="' + q.id + '">Use</button><button class="btn xs ghost" data-delq="' + q.id + '" title="Delete">' + icon("trash") + "</button></div>") },
      ] };
    const draw = QT.renderTable(qBox, cfg);
    function bind() {
      const live = current();
      const best = live.slice().sort((a, b) => a.final_payable - b.final_payable)[0];
      $("[data-best]", view).textContent = !quotes.length ? "" : best ? "Lowest current payable: " + QT.plain(best.final_payable) + " at " + best.supplier_name + " (" + best.quote_no + ")"
        : "Every fare is stale. Re-check in the portals.";
      $$("[data-use]", qBox).forEach((b) => { b.onclick = () => useQuote(f, quotes.find((q) => String(q.id) === b.dataset.use), isStale); });
      $$("[data-delq]", qBox).forEach((b) => { b.onclick = async () => {
        const q = quotes.find((x) => String(x.id) === b.dataset.delq);
        if (!(await QT.confirm({ title: "Delete " + q.quote_no + "?", message: "The fare you entered from " + q.supplier_name + " is removed.", ok: "Delete", danger: true }))) return;
        try { await QT.del("/api/fare/quotes/" + q.id); QT.reload(); } catch (e) { QT.fail(e); }
      }; });
    }
    const redraw = () => { draw(); bind(); };
    cfg._draw = redraw;
    redraw();
    // ages and the stale mark keep moving while the page is open
    const timer = setInterval(() => {
      if (!document.body.contains(qBox)) { clearInterval(timer); return; }
      redraw();
    }, 30000);
  };

  // ---------------------------------------------------------------- add a fare seen in a portal
  function quoteForm(f, s, done) {
    const now = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16);
    const idem = QT.uuid();
    const body = h`<form class="form-grid" onsubmit="return false" autocomplete="off">
      <div class="f s12"><div class="alert info small">${QT.raw(icon("info"))}<div>Type what <b>${s.name}</b> shows for ${routeText(f)}, ${paxText(f)}. Leave a box empty if the portal does not show it: it will be marked unavailable, not zero.</div></div></div>
      ${QT.field({ name: "airline", label: "Airline", span: 4, attrs: 'list="airlines-dl"' })}
      ${QT.field({ name: "flight_nos", label: "Flight number(s)", span: 4, cls: "upper mono", placeholder: "G9 452 / G9 455" })}
      ${QT.field({ name: "stops", label: "Stops", type: "select", span: 4, opts: QT.options([["0", "Direct"], ["1", "1 stop"], ["2", "2 stops"], ["3", "3+ stops"]], f.direct_only ? "0" : "", "—") })}
      ${QT.field({ name: "dep_at", label: "Departs", type: "datetime-local", span: 4 })}
      ${QT.field({ name: "arr_at", label: "Arrives", type: "datetime-local", span: 4 })}
      ${QT.field({ name: "duration", label: "Duration", span: 4, placeholder: "e.g. 4h 25m" })}
      ${QT.field({ name: "fare_family", label: "Fare family / brand", span: 4, placeholder: "e.g. Value, Saver, Flex" })}
      ${QT.field({ name: "baggage", label: "Baggage", span: 4, placeholder: "e.g. 30 kg + 7 kg" })}
      ${QT.field({ name: "refund_terms", label: "Refund / change conditions", span: 4, placeholder: "e.g. Non-refundable; change ₹2,500 + diff" })}
      ${QT.field({ name: "base_fare", label: "Base fare", type: "money", span: 3, placeholder: "unavailable" })}
      ${QT.field({ name: "taxes", label: "Taxes", type: "money", span: 3, placeholder: "unavailable" })}
      ${QT.field({ name: "fees", label: "Supplier fees", type: "money", span: 3, placeholder: "unavailable" })}
      ${QT.field({ name: "final_payable", label: "Final payable to supplier", type: "money", span: 3, req: true, hint: "All passengers" })}
      ${QT.field({ name: "payment_charges", label: "Payment / convenience charge", type: "money", span: 4, placeholder: "unavailable" })}
      ${QT.field({ name: "commission", label: "Confirmed commission (not already deducted)", type: "money", span: 4, placeholder: "unavailable" })}
      ${QT.field({ name: "seen_at", label: "Time seen in portal", type: "datetime-local", span: 4, value: now, hint: "Change only if you saw it earlier" })}
      ${QT.field({ name: "notes", label: "Notes", type: "textarea", span: 12, rows: 2 })}
    </form>`;
    QT.modal({ title: "Add fare from " + s.name, size: "lg", body, sticky: true, actions: [
      { label: "Cancel" },
      { label: "Save fare", cls: "primary", onClick: async (m) => {
        const d = QT.formData($("form", m.body));
        d.supplier_id = s.supplier_id; d.idem_key = idem;
        await QT.post("/api/fare/searches/" + f.id + "/quotes", d);
        QT.toast("Fare saved");
        done && done();
      } },
    ] });
    if (!$("#airlines-dl")) document.body.appendChild(el('<datalist id="airlines-dl">' + QT.AIRLINES.map((a) => '<option value="' + esc(a) + '">').join("") + "</datalist>"));
  }

  // ---------------------------------------------------------------- re-check, duplicate check, then a draft booking
  function useQuote(f, q, isStale) {
    const idem = QT.uuid();
    const body = h`<div class="stack" style="gap:12px">
      ${isStale(q) ? QT.raw('<div class="alert warn small">' + icon("alert") + "<div>This fare is <b>stale</b>. Prices and seats change; it may no longer be available.</div></div>") : ""}
      <div>Open <b>${q.supplier_name}</b> and look at this fare again: ${q.airline || ""} ${q.flight_nos || ""}, ${q.fare_family || "fare family not entered"}, last seen ${QT.time(q.seen_at)} at ${QT.money(q.final_payable)}.</div>
      <form class="form-grid" onsubmit="return false">
        ${QT.field({ name: "final_payable", label: "Final payable shown now", type: "money", span: 6, req: true })}
        ${QT.field({ name: "payment_charges", label: "Payment / convenience charge now", type: "money", span: 6, value: q.payment_charges === null ? "" : QT.plain(q.payment_charges), placeholder: "unavailable" })}
        ${QT.field({ name: "confirmed", label: "I re-checked this fare in " + q.supplier_name + " just now", type: "checkbox", span: 12 })}
      </form>
      <div class="muted small">Next, AirZelo checks for an existing booking on this route and day, then opens a <b>draft booking</b> (status Enquiry) for you to complete. Booking, ticketing and payment happen in the supplier's portal, by you.</div></div>`;
    QT.modal({ title: "Use fare " + q.quote_no, body, sticky: true, actions: [
      { label: "Cancel" },
      { label: "Open portal", onClick: () => { const s = q.login_url; return s ? (openPortal(s), false) : false; } },
      { label: "Continue", cls: "primary", onClick: async (m) => {
        const d = QT.formData($("form", m.body));
        d.idem_key = idem;
        const fresh = await QT.post("/api/fare/quotes/" + q.id + "/recheck", d);
        if (fresh.changed_from !== null && fresh.changed_from !== undefined) {
          QT.toast("Fare changed from " + QT.plain(fresh.changed_from) + " to " + QT.plain(fresh.final_payable), "err");
        }
        const dups = await QT.get("/api/fare/quotes/" + fresh.id + "/duplicates");
        if (dups.length) {
          const ok = await QT.confirm({ title: "Possible duplicate booking", ok: "Continue anyway",
            message: "Bookings already exist on " + f.origin + " → " + f.destination + " on " + QT.date(f.depart_date) + ":",
            detail: dups.map((b) => b.ref + (b.pnr ? " (" + b.pnr + ")" : "") + " · " + b.customer_name + " · " + (QT.labels.status[b.status] || b.status)).join("\n") });
          if (!ok) { QT.reload(); return true; }
        }
        QT.state.bookingDraft = draftFrom(f, fresh);
        QT.go("#/bookings/new?from_quote=" + fresh.id);
      } },
    ] });
    // the Open portal button needs the portal address of this quote's supplier
    QT.get("/api/fare/searches/" + f.id).then((r) => { const s = r.suppliers.find((x) => x.supplier_id === q.supplier_id); q.login_url = s && s.portal_active ? s.login_url : null; }).catch(() => {});
  }

  function draftFrom(f, q) {
    const flights = (q.flight_nos || "").split(/\s*[\/,]\s*/).filter(Boolean);
    const segments = [{ direction: "outbound", airline: q.airline || "", flight_no: flights[0] || "", origin: f.origin, destination: f.destination,
      dep_at: q.dep_at || (f.depart_date + "T00:00"), arr_at: q.arr_at || "", cabin: f.cabin, baggage: q.baggage || f.baggage || "" }];
    if (f.trip_type === "round_trip") segments.push({ direction: "return", airline: q.airline || "", flight_no: flights[1] || "", origin: f.destination,
      destination: f.origin, dep_at: f.return_date + "T00:00", arr_at: "", cabin: f.cabin, baggage: q.baggage || f.baggage || "" });
    return {
      quote_id: q.id,
      booking: { status: "enquiry", supplier_id: q.supplier_id, trip_type: f.trip_type, travel_date: f.depart_date, return_date: f.return_date || "",
        cabin: f.cabin, baggage: q.baggage || f.baggage || "",
        notes: "From fare quote " + q.quote_no + " (" + q.supplier_name + ", re-checked " + QT.dateTime(q.seen_at) + ", " + (q.fare_family || "fare family not entered") +
          (q.refund_terms ? "; " + q.refund_terms : "") + "). Check times and fill in passengers before saving." },
      segments,
      passengers: [].concat(Array(f.adults).fill("ADT"), Array(f.children).fill("CHD"), Array(f.infants).fill("INF")).map((t) => ({ name: "", pax_type: t })),
      money:{ purchase_cost: q.final_payable, selling_price: f.selling_price, commission: q.commission },
    };
  }

  // ---------------------------------------------------------------- settings tab
  // public addresses checked on 28 Oct 2026; shown only as a suggestion the user must confirm
  const SUGGEST = [[/riya/i, "https://riyaconnect.com/"], [/gosama/i, "https://gosama.in/"], [/akbar/i, "https://agents.akbartravelsonline.com/b2bplus/login"],
    [/my ?partner|makemytrip/i, "https://mypartner.makemytrip.com/"]];
  QT.fareSettings = async function (pane, s) {
    const portals = await QT.get("/api/fare/portals");
    pane.innerHTML = h`<div class="stack" style="gap:16px;max-width:1100px">
      <div class="card"><div class="card-h"><h3>Fare search</h3></div><form class="card-b form-grid" data-f data-reload-shell="1" onsubmit="return false">
        ${QT.field({ name: "fare_search_enabled", label: "Fare search", type: "select", span: 4, opts: QT.options([["0", "Off"], ["1", "On"]], s.fare_search_enabled || "0") })}
        ${QT.field({ name: "fare_stale_minutes", label: "Mark a fare stale after (minutes)", type: "number", span: 4, value: s.fare_stale_minutes || "15", attrs: 'min="1" max="240"' })}
        <div class="f s4" style="justify-content:flex-end"><button class="btn primary" type="button" data-save>Save</button></div>
        <div class="f s12"><div class="alert info small">${QT.raw(icon("shield"))}<div>AirZelo opens each supplier's official page in your normal browser, where you sign in yourself. It never stores or asks for supplier passwords, OTPs or cookies, never reads or copies portal pages, and never searches, books, issues or pays for you. Automated searching is only added for a supplier that allows it in writing.</div></div></div>
      </form></div>
      <div class="card"><div class="card-h"><h3>Supplier portals</h3><div class="sub">Paste the sign-in page address you already use for each supplier (https only, without passwords or session codes)</div></div>
        <div class="card-b flush" data-portals></div></div></div>`.html;
    $("[data-save]", pane).onclick = async () => {
      const form = $("[data-f]", pane);
      try { await QT.put("/api/settings", QT.formData(form)); QT.toast("Saved"); location.reload(); } catch (e) { QT.showErr(form, e); }
    };
    const box = $("[data-portals]", pane);
    box.innerHTML = portals.map((p) => {
      const sug = (SUGGEST.find(([rx]) => rx.test(p.name)) || [])[1];
      return h`<div class="li" data-row="${p.supplier_id}" style="align-items:flex-start"><div class="grow form-grid">
        <div class="f s3"><label>${p.name}${p.supplier_active ? "" : " (inactive supplier)"}</label><label class="check small"><input type="checkbox" name="active" ${p.login_url ? (p.active ? "checked" : "") : "checked"}> Use in searches</label></div>
        <div class="f s5"><label>Official sign-in address</label><input class="inp" name="login_url" value="${p.login_url || ""}" placeholder="https://…" spellcheck="false">
          ${QT.raw(sug && !p.login_url ? '<span class="hint">Public site: <a href="#" data-sug="' + esc(sug) + '">' + esc(sug) + "</a> (checked 28 Oct 2026; confirm it is the one you use)</span>" : "")}</div>
        <div class="f s4"><label>Search tip (optional)</label><input class="inp" name="search_hint" value="${p.search_hint || ""}" placeholder="e.g. dates as DD-MMM-YYYY"></div></div>
        <div class="row" style="gap:6px;margin-top:20px"><button class="btn sm primary" data-psave>Save</button>${QT.raw(p.login_url ? '<button class="btn sm ghost" data-pdel title="Remove">' + icon("trash") + "</button>" : "")}</div></div>`.html;
    }).join("") || '<div class="empty-state small">Add your suppliers first (Operations → Suppliers).</div>';
    $$("[data-sug]", box).forEach((a) => { a.onclick = (e) => { e.preventDefault(); $("[name=login_url]", a.closest("[data-row]")).value = a.dataset.sug; }; });
    $$("[data-row]", box).forEach((row) => {
      const sid = row.dataset.row;
      $("[data-psave]", row).onclick = async () => {
        try { await QT.put("/api/fare/portals/" + sid, QT.formData(row)); QT.toast("Portal saved"); QT.reload(); } catch (e) { QT.showErr(row, e); }
      };
      const del = $("[data-pdel]", row);
      if (del) del.onclick = async () => { try { await QT.del("/api/fare/portals/" + sid); QT.toast("Portal removed"); QT.reload(); } catch (e) { QT.fail(e); } };
    });
  };
})();
