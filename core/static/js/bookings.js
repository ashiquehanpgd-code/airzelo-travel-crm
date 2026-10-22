/* PNR search, bookings list, booking form, booking detail */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  // ================================================================ PNR SEARCH
  QT.pages.pnr = async function (view, params) {
    view.innerHTML = h`
      <div class="search-hero">
        <h1>PNR search</h1>
        <p>Find any saved booking by PNR, booking ref, passenger, ticket number or customer phone.</p>
        <form class="big-search" data-form>
          ${QT.raw(icon("search"))}
          <input name="q" placeholder="Enter PNR e.g. X7K9QL, ticket number, passenger name or phone" value="${params.q || ""}" autocomplete="off" autofocus>
          <button class="btn" type="submit">Search</button>
        </form>
        <div class="adv">
          <input class="inp" name="airline" placeholder="Airline" list="airlines-dl" value="${params.airline || ""}">
          <input class="inp upper" name="origin" placeholder="From (e.g. COK)" maxlength="40" value="${params.origin || ""}">
          <input class="inp upper" name="destination" placeholder="To (e.g. DXB)" maxlength="40" value="${params.destination || ""}">
          <input class="inp" type="date" name="travel_date" value="${params.travel_date || ""}" title="Travel date">
          <select class="inp" name="status">${QT.options(Object.entries(QT.labels.status), params.status || "", "Any status")}</select>
        </div>
        <datalist id="airlines-dl">${QT.raw(QT.AIRLINES.map((a) => '<option value="' + esc(a) + '">').join(""))}</datalist>
      </div>
      <div class="alert info" style="margin:14px 0">${QT.raw(icon("info"))}<div>This searches <b>bookings saved in AirZelo</b>. It does not connect to airline or GDS systems, so live reservation status is not retrieved.</div></div>
      <div data-results></div>`.html;
    const form = $("[data-form]", view);
    const res = $("[data-results]", view);
    const fields = () => {
      const p = { q: $("[name=q]", view).value.trim() };
      ["airline", "origin", "destination", "travel_date", "status"].forEach((k) => { p[k] = $("[name=" + k + "]", view).value.trim(); });
      return p;
    };
    async function search() {
      const p = fields();
      const any = Object.values(p).some(Boolean);
      history.replaceState(null, "", "#/pnr" + QT.qs(p));
      if (!any) {
        res.innerHTML = '<div class="card"><div class="empty-state">' + icon("search") + "<h4>Search your bookings</h4><div>Type a PNR and press Enter. Results show instantly from your saved records.</div></div></div>";
        return;
      }
      res.innerHTML = String(QT.loading());
      const rows = await QT.get("/api/bookings" + QT.qs(p));
      if (!rows.length) {
        const pnr = /^[A-Za-z0-9]{5,8}$/.test(p.q) ? p.q.toUpperCase() : "";
        res.innerHTML = h`<div class="card"><div class="empty-state">${QT.raw(icon("ticket"))}
          <h4>No saved booking found${p.q ? " for “" + p.q + "”" : ""}</h4>
          <div style="margin-bottom:14px">Nothing in your records matches. You can create the booking manually.</div>
          <a class="btn primary" href="#/bookings/new${pnr ? "?pnr=" + encodeURIComponent(pnr) : ""}">${QT.raw(icon("plus"))}Create booking${pnr ? " with PNR " + pnr : ""}</a></div></div>`.html;
        return;
      }
      res.innerHTML = h`<div class="card"><div class="card-h"><h3>${rows.length} saved booking${rows.length > 1 ? "s" : ""}</h3>
        ${rows.length && rows[0].exact_pnr ? QT.raw('<span class="badge b-green">Exact PNR match</span>') : ""}
        <div class="actions"><a class="btn sm" href="#/bookings/new${p.q && /^[A-Za-z0-9]{5,8}$/.test(p.q) ? "?pnr=" + encodeURIComponent(p.q.toUpperCase()) : ""}">${QT.raw(icon("plus"))}New booking</a></div></div>
        <div data-list></div></div>`.html;
      const list = $("[data-list]", res);
      list.innerHTML = rows.map((r, i) => h`<div class="result-card ${r.exact_pnr ? "exact" : ""}" data-i="${i}">
        <div><div class="rc-pnr">${r.pnr || "—"}</div><div class="small muted">${r.ref}</div></div>
        <div><div style="font-weight:650">${r.passenger_names.slice(0, 3).join(", ")}${r.passenger_names.length > 3 ? " +" + (r.passenger_names.length - 3) : ""}</div>
          <div class="small muted">${r.route || QT.labels.service[r.service_type]} · ${r.airlines.join(", ") || "—"} · ${r.travel_date ? "Travel " + QT.date(r.travel_date) : "No travel date"}</div>
          <div class="small muted">${r.customer_name}${r.customer_phone ? " · " + r.customer_phone : ""}${r.tickets.length ? " · Tkt " + r.tickets.join(", ") : ""}</div></div>
        <div class="right"><div class="row" style="justify-content:flex-end;gap:6px">${QT.statusBadge(r.status)}${QT.payBadge(r.payment_status)}</div>
          <div class="small" style="margin-top:6px">${r.customer_outstanding ? QT.raw('Due <b class="' + (r.customer_outstanding > 0 ? "neg" : "pos") + '">' + QT.money(r.customer_outstanding) + "</b>") : QT.money(r.customer_payable)}</div></div>
      </div>`.html).join("");
      $$(".result-card", list).forEach((c) => { c.onclick = () => QT.go("#/bookings/" + rows[+c.dataset.i].id); });
      if (rows.length === 1 && rows[0].exact_pnr && params._auto !== "0") { /* show result; user clicks to open */ }
    }
    form.onsubmit = (e) => { e.preventDefault(); search(); };
    $$(".adv .inp", view).forEach((i) => i.addEventListener("change", search));
    await search();
  };

  // ================================================================ BOOKINGS LIST
  QT.pages.bookings = async function (view, params) {
    const f = Object.assign({ q: "", status: "", payment_status: "", supplier_id: "", from: "", to: "" }, params);
    const sup = QT.state.meta.suppliers;
    view.innerHTML = h`<div class="page-head"><div><h1>Bookings</h1><div class="sub">All flights, visas, hotels and packages</div></div>
      <div class="actions"><a class="btn primary" href="#/bookings/new">${QT.raw(icon("plus"))}New booking</a></div></div>
      <div class="card"><div class="filters">
        <input class="inp w-search" name="q" placeholder="Search PNR, name, ticket, phone" value="${f.q}">
        <select class="inp" name="status">${QT.options(Object.entries(QT.labels.status), f.status, "All statuses")}</select>
        <select class="inp" name="payment_status">${QT.options(Object.entries(QT.labels.pay), f.payment_status, "Any payment")}</select>
        <select class="inp" name="supplier_id">${QT.options(sup.map((s) => [s.id, s.name]), f.supplier_id, "All suppliers")}</select>
        <input class="inp" type="date" name="from" value="${f.from}" title="Booked from"><input class="inp" type="date" name="to" value="${f.to}" title="Booked to">
        <span class="spacer"></span><span class="muted small" data-count></span>
      </div><div data-table></div></div>`.html;
    const tbl = $("[data-table]", view);
    const cfg = {
      rows: [], emptyTitle: "No bookings found", empty: "Adjust the filters or create a new booking.", emptyIcon: "ticket",
      onRow: (r) => QT.go("#/bookings/" + r.id),
      columns: [
        { label: "Booked", key: "booking_date", type: "date" },
        { label: "Ref", key: "ref", render: (r) => h`<span class="cell-main">${r.ref}</span>` },
        { label: "PNR", key: "pnr", render: (r) => r.pnr ? h`<span class="pnr">${r.pnr}</span>` : "" },
        { label: "Passenger / customer", key: "lead_pax", render: (r) => h`<div class="cell-main">${r.lead_pax}${r.pax_count > 1 ? " +" + (r.pax_count - 1) : ""}</div><div class="cell-sub">${r.customer_name}</div>` },
        { label: "Route", key: "route", render: (r) => h`<div>${r.route || QT.labels.service[r.service_type]}</div><div class="cell-sub">${r.airlines.join(", ")}</div>` },
        { label: "Travel", key: "travel_date", type: "date" },
        { label: "Supplier", key: "supplier_name" },
        { label: "Status", key: "status", render: (r) => QT.statusBadge(r.status) },
        { label: "Payment", key: "payment_status", render: (r) => QT.payBadge(r.payment_status) },
        { label: "Selling", key: "customer_payable", type: "money" },
        { label: "Profit", key: "gross_profit", type: "money" },
        { label: "Due", key: "customer_outstanding", type: "money" },
      ],
    };
    const draw = QT.renderTable(tbl, cfg);
    async function load() {
      const p = QT.formData($(".filters", view));
      history.replaceState(null, "", "#/bookings" + QT.qs(p));
      cfg.rows = await QT.get("/api/bookings" + QT.qs(p));
      $("[data-count]", view).textContent = cfg.rows.length + " bookings";
      draw();
    }
    $$(".filters .inp", view).forEach((i) => i.addEventListener(i.name === "q" ? "input" : "change", QT.debounce(load, 250)));
    await load();
  };

  // ================================================================ BOOKING FORM
  QT.pages.bookingForm = async function (view, params, id) {
    const meta = QT.state.meta;
    const editing = !!id;
    const [customers, existing] = await Promise.all([QT.get("/api/customers"), editing ? QT.get("/api/bookings/" + id) : null]);
    const b = existing ? existing.booking : {
      status: "confirmed", service_type: "flight", trip_type: "one_way", booking_date: QT.today(), currency: QT.base(), exchange_rate: "1",
      pnr: (params.pnr || "").toUpperCase(), customer_id: params.customer_id ? +params.customer_id : null,
    };
    const pax = existing ? existing.passengers : [{ name: "", pax_type: "ADT" }];
    const segs = existing ? existing.segments : [{ direction: "outbound" }];
    const m = (k) => (existing ? QT.plain(b[k]) : "");
    const cust = b.customer_id ? customers.find((c) => c.id === b.customer_id) : null;
    const curOpts = meta.currencies.filter((c) => c.active || c.code === b.currency).map((c) => [c.code, c.code + " · " + c.name]);
    const taxOpts = meta.tax_profiles.filter((t) => t.active || t.id === b.tax_profile_id).map((t) => [t.id, t.name]);
    const supOpts = meta.suppliers.filter((s) => s.active || s.id === b.supplier_id).map((s) => [s.id, s.name]);
    const locked = existing && existing.invoices.some((i) => i.doc_type === "invoice" && i.status === "issued");

    view.innerHTML = h`<div class="page-head"><div>
        <div class="crumbs"><a href="#/bookings">Bookings</a>${editing ? QT.raw(' / <a href="#/bookings/' + b.id + '">' + esc(b.ref) + "</a>") : ""}</div>
        <h1>${editing ? "Edit booking " + b.ref : "New booking"}</h1></div>
        <div class="actions"><a class="btn" href="${editing ? "#/bookings/" + b.id : "#/bookings"}">Cancel</a><button class="btn primary" data-save>${QT.raw(icon("check"))}Save booking</button></div></div>
      ${locked ? QT.raw('<div class="alert warn" style="margin-bottom:14px">' + icon("lock") + "<div>An invoice is issued for this booking, so prices are locked. Cancel the invoice first, or record a reissue / date change adjustment instead.</div></div>") : ""}
      <form class="grid g-side" data-form autocomplete="off" onsubmit="return false">
        <div class="card">
          <div class="form-section" data-skip><div data-scanner></div></div>
          <div class="form-section"><h4><span class="n">1</span>Customer & supplier</h4>
            <div class="form-grid">
              <div class="f s6"><label>Customer <span class="req">*</span></label><input class="inp" data-cust placeholder="Search name or phone, or type a new name" value="${cust ? cust.name : ""}"><input type="hidden" name="customer_id" value="${b.customer_id || ""}"><span class="hint" data-cust-info>${cust ? (cust.phone || "") + (cust.email ? " · " + cust.email : "") : ""}</span></div>
              <div class="f s6"><label>Supplier / B2B portal</label><select class="inp" name="supplier_id">${QT.options(supOpts, b.supplier_id || "", "— Select supplier —")}</select><span class="hint"><a href="#" data-new-sup>+ Add supplier</a></span></div>
              <div class="form-grid hidden" data-new-cust style="grid-column:span 12;background:var(--surface-2);padding:12px;border-radius:10px;border:1px dashed var(--border-strong)">
                <div class="f s4"><label>New customer name</label><input class="inp" data-nc="name"></div>
                <div class="f s4"><label>Phone</label><input class="inp" data-nc="phone"></div>
                <div class="f s4"><label>Email</label><input class="inp" data-nc="email"></div>
              </div>
            </div>
          </div>
          <div class="form-section"><h4><span class="n">2</span>Booking details</h4>
            <div class="form-grid">
              <div class="f s3"><label>PNR / booking reference</label><input class="inp upper mono" name="pnr" maxlength="20" value="${b.pnr || ""}" placeholder="e.g. X7K9QL"><span class="hint" data-pnr-hint></span></div>
              ${QT.field({ name: "service_type", label: "Service", type: "select", span: 3, opts: QT.options(Object.entries(QT.labels.service), b.service_type) })}
              ${QT.field({ name: "status", label: "Booking status", type: "select", span: 3, opts: QT.options(Object.entries(QT.labels.status).filter(([k]) => editing || !["cancelled", "refunded"].includes(k)), b.status), hint: "Revenue counts once Confirmed or Ticketed" })}
              ${QT.field({ name: "trip_type", label: "Trip type", type: "select", span: 3, opts: QT.options([["one_way", "One way"], ["round_trip", "Round trip"], ["multi_city", "Multi-city"]], b.trip_type) })}
              ${QT.field({ name: "booking_date", label: "Booking date", type: "date", span: 3, value: b.booking_date, req: true })}
              ${QT.field({ name: "travel_date", label: "Travel date", type: "date", span: 3, value: b.travel_date, hint: "Auto from first segment if blank" })}
              ${QT.field({ name: "return_date", label: "Return date", type: "date", span: 3, value: b.return_date })}
              ${QT.field({ name: "cabin", label: "Cabin class", type: "select", span: 3, opts: QT.options(["Economy", "Premium Economy", "Business", "First"], b.cabin || "", "—") })}
              ${QT.field({ name: "baggage", label: "Baggage allowance", span: 4, value: b.baggage, placeholder: "e.g. 30 kg + 7 kg cabin" })}
              ${QT.field({ name: "place_of_supply", label: "Place of supply (state)", span: 4, value: b.place_of_supply, placeholder: "e.g. Kerala" })}
            </div>
          </div>
          <div class="form-section"><h4><span class="n">3</span>Passengers & tickets <span class="spacer"></span><button class="btn sm" type="button" data-add-pax>${QT.raw(icon("plus"))}Add passenger</button></h4>
            <div class="rep" data-pax></div></div>
          <div class="form-section"><h4><span class="n">4</span>Flight segments <span class="spacer"></span><button class="btn sm" type="button" data-add-seg>${QT.raw(icon("plus"))}Add segment</button><button class="btn sm" type="button" data-add-ret>${QT.raw(icon("swap"))}Add return</button></h4>
            <div class="rep" data-segs></div></div>
          <div class="form-section"><h4><span class="n">5</span>Pricing</h4>
            <div class="form-grid">
              ${QT.field({ name: "currency", label: "Currency", type: "select", span: 3, opts: QT.options(curOpts, b.currency) })}
              <div class="f s3" data-rate-wrap><label>Rate to ${QT.base()}</label><input class="inp money" name="exchange_rate" inputmode="decimal" value="${b.exchange_rate || "1"}"><span class="hint">Saved with this booking</span></div>
              <div class="f s3" data-rate-wrap>${QT.raw('<label>Rate date</label><input type="date" class="inp" name="rate_date" value="' + esc(b.rate_date || "") + '">')}</div>
              <div class="f s3"></div>
              ${QT.field({ name: "purchase_cost", label: "Supplier purchase cost", type: "money", span: 3, value: m("purchase_cost"), req: true })}
              ${QT.field({ name: "selling_price", label: "Customer selling price", type: "money", span: 3, value: m("selling_price"), req: true })}
              ${QT.field({ name: "commission", label: "Commission earned", type: "money", span: 3, value: m("commission"), hint: "From airline / portal" })}
              ${QT.field({ name: "service_charge", label: "Service charge", type: "money", span: 3, value: m("service_charge") })}
              ${QT.field({ name: "other_income", label: "Other booking income", type: "money", span: 3, value: m("other_income"), hint: "Seats, meals, insurance margin…" })}
              ${QT.field({ name: "tax_profile_id", label: "Tax treatment", type: "select", span: 6, opts: QT.options(taxOpts, b.tax_profile_id || "", "No tax"), hint: "Confirm GST treatment with your CA" })}
              ${QT.field({ name: "tax_override", label: "Enter tax manually", type: "checkbox", span: 3, value: !!b.tax_override })}
              <div class="form-grid ${b.tax_override ? "" : "hidden"}" data-tax-manual style="grid-column:span 12">
                ${QT.field({ name: "taxable_value", label: "Taxable value", type: "money", span: 4, value: m("taxable_value") })}
                ${QT.field({ name: "tax_rate", label: "Tax rate %", span: 4, value: b.tax_rate && b.tax_rate !== "0" ? b.tax_rate : "", attrs: 'inputmode="decimal"' })}
                ${QT.field({ name: "tax_amount", label: "Tax amount", type: "money", span: 4, value: m("tax_amount"), hint: "Leave blank to calculate" })}
              </div>
              <div class="f s12" style="margin-top:4px"><div class="alert info small">${QT.raw(icon("info"))}<div>Direct booking expenses (e.g. courier, visa fees paid on behalf) are recorded as <b>Expenses linked to this booking</b> after saving, so they are counted once.</div></div></div>
            </div>
          </div>
          <div class="form-section"><h4><span class="n">6</span>Notes</h4>
            ${QT.field({ name: "notes", label: "Internal notes", type: "textarea", span: 12, value: b.notes, rows: 3 })}</div>
        </div>
        <div class="sum-col"><div class="card sum-card" data-sum-card>
          <div class="card-h sum-head"><h3>Booking summary</h3><span class="sub" data-sum-cur></span>
            <span class="sum-mini" data-sum-mini aria-hidden="true"></span>
            <button class="btn xs sum-toggle" type="button" data-sum-toggle aria-expanded="true" aria-controls="booking-sum-body" title="Minimize summary">${QT.raw(icon("chevronDown"))}<span data-sum-toggle-lbl>Minimize</span></button></div>
          <div class="sum-body" id="booking-sum-body"><div class="card-b" data-summary></div>
          <div class="card-b" style="border-top:1px solid var(--border)"><button class="btn primary" style="width:100%" type="button" data-save2>${QT.raw(icon("check"))}Save booking</button></div></div></div></div>
      </form>`.html;

    const form = $("[data-form]", view);
    const paxBox = $("[data-pax]", view);
    const segBox = $("[data-segs]", view);
    if (locked) $$("[name=purchase_cost],[name=selling_price],[name=commission],[name=service_charge],[name=other_income],[name=currency],[name=exchange_rate],[name=tax_profile_id],[name=tax_override],[name=taxable_value],[name=tax_rate],[name=tax_amount]", form).forEach((x) => { x.disabled = true; });

    // ---------- customer combobox
    const custInput = $("[data-cust]", view);
    const custId = $("[name=customer_id]", view);
    const newCust = $("[data-new-cust]", view);
    QT.combo(custInput, {
      items: () => customers,
      match: (c, q) => c.name.toLowerCase().includes(q) || (c.phone || "").replace(/\D/g, "").includes(q.replace(/\D/g, "") || "~"),
      label: (c) => c.name, sub: (c) => c.phone || "",
      onType: () => { custId.value = ""; $("[data-cust-info]", view).textContent = ""; },
      onSelect: (c) => {
        custInput.value = c.name;
        custId.value = c.id;
        newCust.classList.add("hidden");
        $("[data-cust-info]", view).textContent = [c.phone, c.email, c.outstanding ? "Outstanding " + QT.money(c.outstanding) : ""].filter(Boolean).join(" · ");
        const first = $("[data-pax] [data-p=name]", view);
        if (first && !first.value) first.value = c.name.toUpperCase();
      },
      newLabel: "New customer", onNew: (name) => {
        custId.value = "";
        newCust.classList.remove("hidden");
        $("[data-nc=name]", view).value = name;
        $("[data-cust-info]", view).textContent = "A new customer will be created";
        $("[data-nc=phone]", view).focus();
        const first = $("[data-pax] [data-p=name]", view);
        if (first && !first.value) first.value = name.toUpperCase();
      },
    });
    $("[data-new-sup]", view).onclick = (e) => {
      e.preventDefault();
      QT.supplierForm(null, async (s) => {
        await QT.loadMeta();
        const sel = $("[name=supplier_id]", view);
        sel.insertAdjacentHTML("beforeend", QT.opt(s.supplier.id, s.supplier.name, s.supplier.id));
        sel.value = s.supplier.id;
      });
    };

    // ---------- PNR duplicate hint
    const pnrIn = $("[name=pnr]", view);
    const pnrHint = $("[data-pnr-hint]", view);
    const checkPnr = QT.debounce(async () => {
      const v = pnrIn.value.trim().toUpperCase();
      if (v.length < 3) { pnrHint.innerHTML = ""; return; }
      const d = await QT.get("/api/bookings/check-pnr" + QT.qs({ pnr: v, exclude: b.id }));
      pnrHint.innerHTML = d.length ? h`<span class="neg">Already saved in ${QT.raw(d.map((x) => '<a href="#/bookings/' + x.id + '">' + esc(x.ref) + "</a>").join(", "))}</span>`.html : '<span class="pos">New PNR</span>';
    }, 350);
    pnrIn.addEventListener("input", checkPnr);
    if (pnrIn.value) checkPnr();

    // ---------- passengers
    function paxRow(p, i) {
      const r = el(h`<div class="rep-row pax-row"><span class="idx">PAX ${i + 1}</span>
        <div class="f"><label>Title</label><select class="inp" data-p="title">${QT.options(["MR", "MRS", "MS", "MSTR", "MISS"], p.title || "", "—")}</select></div>
        <div class="f"><label>Full name (as on passport)</label><input class="inp upper" data-p="name" value="${p.name || ""}"></div>
        <div class="f"><label>Type</label><select class="inp" data-p="pax_type">${QT.options([["ADT", "Adult"], ["CHD", "Child"], ["INF", "Infant"]], p.pax_type || "ADT")}</select></div>
        <div class="f"><label>Ticket number</label><input class="inp mono" data-p="ticket_number" value="${p.ticket_number || ""}" placeholder="e.g. 098-2412345678"></div>
        <div class="f"><label>Passport no.</label><input class="inp upper" data-p="passport_no" value="${p.passport_no || ""}"></div>
        <button class="btn icon ghost" type="button" title="Remove passenger" data-rm>${QT.raw(icon("trash"))}</button></div>`.html);
      $("[data-rm]", r).onclick = () => { if ($$(".pax-row", paxBox).length > 1) { r.remove(); renumber(paxBox, "PAX"); } else QT.toast("A booking needs at least one passenger", "err"); };
      return r;
    }
    function segRow(s, i) {
      const r = el(h`<div class="rep-row seg-row"><span class="idx">SEG ${i + 1}</span>
        <div class="f"><label>Direction</label><select class="inp" data-s="direction">${QT.options([["outbound", "Outbound"], ["return", "Return"], ["other", "Other"]], s.direction || "outbound")}</select></div>
        <div class="f"><label>Airline</label><input class="inp" data-s="airline" list="airlines-dl2" value="${s.airline || ""}"></div>
        <div class="f"><label>Flight no.</label><input class="inp upper" data-s="flight_no" value="${s.flight_no || ""}" placeholder="6E 1407"></div>
        <div class="f"><label>From</label><input class="inp upper" data-s="origin" value="${s.origin || ""}" placeholder="COK"></div>
        <div class="f"><label>To</label><input class="inp upper" data-s="destination" value="${s.destination || ""}" placeholder="DXB"></div>
        <div class="f"><label>Departure</label><input class="inp" type="datetime-local" data-s="dep_at" value="${s.dep_at || ""}"></div>
        <div class="f"><label>Arrival</label><input class="inp" type="datetime-local" data-s="arr_at" value="${s.arr_at || ""}"></div>
        <button class="btn icon ghost" type="button" title="Remove segment" data-rm>${QT.raw(icon("trash"))}</button>
        <div class="seg-extra">
          <div class="f"><label>Cabin</label><input class="inp" data-s="cabin" value="${s.cabin || ""}" placeholder="Economy"></div>
          <div class="f"><label>Baggage</label><input class="inp" data-s="baggage" value="${s.baggage || ""}" placeholder="30 kg"></div>
          <div class="f"><label>Airline PNR (if different)</label><input class="inp upper mono" data-s="airline_pnr" value="${s.airline_pnr || ""}"></div>
        </div></div>`.html);
      $("[data-rm]", r).onclick = () => { r.remove(); renumber(segBox, "SEG"); };
      return r;
    }
    function renumber(box, label) { $$(".rep-row", box).forEach((r, i) => { $(".idx", r).textContent = label + " " + (i + 1); }); }
    pax.forEach((p, i) => paxBox.appendChild(paxRow(p, i)));
    segs.forEach((s, i) => segBox.appendChild(segRow(s, i)));

    // ---------- AI ticket scanner: fills the form for review; nothing is saved automatically
    const fld = (n) => $("[name=" + n + "]", form);
    const setVal = (inp, v) => { if (inp) { inp.value = v; inp.dispatchEvent(new Event("input", { bubbles: true })); } };
    function applyScan(r) {
      const hasRows = $$("[data-p=name]", paxBox).some((x) => x.value.trim()) || $$("[data-s=flight_no],[data-s=origin]", segBox).some((x) => x.value.trim());
      const go = hasRows ? QT.confirm({ title: "Replace passengers and flights?", message: "The form already has passengers or flights. Replace them with the details read from the ticket?", ok: "Replace with ticket" }) : Promise.resolve(true);
      return go.then((ok) => {
        const sb = r.booking;
        if (sb.pnr.value && (ok || !pnrIn.value.trim())) { setVal(pnrIn, sb.pnr.value); checkPnr(); }
        QT.markScan(pnrIn, sb.pnr);
        if (!ok) return;
        if (sb.status.value && ["confirmed", "ticketed"].includes(sb.status.value)) { setVal(fld("status"), sb.status.value); QT.markScan(fld("status"), sb.status); }
        if (r.segments.length) setVal(fld("trip_type"), sb.trip_type);
        // passengers
        paxBox.innerHTML = "";
        const plist = r.passengers.length ? r.passengers : [{ title: { value: "" }, name: { value: "", confidence: "missing" }, pax_type: { value: "ADT" }, ticket_number: { value: "", confidence: "missing" } }];
        plist.forEach((p, i) => {
          const row = paxRow({ title: p.title.value, name: p.name.value, pax_type: p.pax_type.value || "ADT", ticket_number: p.ticket_number.value }, i);
          paxBox.appendChild(row);
          QT.markScan($("[data-p=name]", row), p.name);
          QT.markScan($("[data-p=ticket_number]", row), p.ticket_number);
          if (p.pax_type.value) QT.markScan($("[data-p=pax_type]", row), p.pax_type);
        });
        // flights
        segBox.innerHTML = "";
        const slist = r.segments.length ? r.segments : [{ direction: "outbound", airline: { value: "" }, flight_no: { value: "", confidence: "missing" }, origin: { value: "", confidence: "missing" }, destination: { value: "", confidence: "missing" },
          dep_at: { value: "", confidence: "missing" }, arr_at: { value: "", confidence: "missing" }, cabin: { value: "" }, baggage: { value: "" }, airline_pnr: { value: "" }, notes: [] }];
        slist.forEach((s, i) => {
          const row = segRow({ direction: s.direction, airline: s.airline.value, flight_no: s.flight_no.value, origin: s.origin.value, destination: s.destination.value,
            dep_at: s.dep_at.value, arr_at: s.arr_at.value, cabin: s.cabin.value, baggage: s.baggage.value, airline_pnr: s.airline_pnr.value }, i);
          segBox.appendChild(row);
          ["airline", "flight_no", "origin", "destination", "dep_at", "arr_at"].forEach((k) => QT.markScan($("[data-s=" + k + "]", row), s[k]));
          ["cabin", "baggage", "airline_pnr"].forEach((k) => { if (s[k].value) QT.markScan($("[data-s=" + k + "]", row), s[k]); });
          if (s.notes && s.notes.length) $(".idx", row).title = s.notes.join(" · ");
        });
        const first = r.segments[0], ret = r.segments.find((s) => s.direction === "return");
        if (first && first.dep_at.value) { setVal(fld("travel_date"), first.dep_at.value.slice(0, 10)); QT.markScan(fld("travel_date"), first.dep_at); }
        if (ret && ret.dep_at.value) { setVal(fld("return_date"), ret.dep_at.value.slice(0, 10)); QT.markScan(fld("return_date"), ret.dep_at); }
        if (first && first.cabin.value && [...fld("cabin").options].some((o) => o.value === first.cabin.value)) { setVal(fld("cabin"), first.cabin.value); QT.markScan(fld("cabin"), first.cabin); }
        if (first && first.baggage.value) { setVal(fld("baggage"), first.baggage.value); QT.markScan(fld("baggage"), first.baggage); }
        if (r.extra_notes) { const n = fld("notes"); setVal(n, (n.value.trim() ? n.value.trim() + "\n" : "") + "From ticket: " + r.extra_notes.replace(/\n/g, "; ")); }
        // customer: pick an existing customer with the lead passenger's name, otherwise leave it for the owner to choose
        const lead = r.passengers[0] && r.passengers[0].name.value;
        if (!custId.value && lead) {
          const hit = customers.find((c) => c.name.trim().toUpperCase() === lead.toUpperCase());
          if (hit) { custInput.value = hit.name; custId.value = hit.id; $("[data-cust-info]", view).textContent = "Matched to existing customer by the lead passenger's name - change if needed"; QT.markScan(custInput, { value: hit.name, confidence: "medium" }); }
          else { $("[data-cust-info]", view).innerHTML = 'Lead passenger: <b>' + esc(lead) + '</b> - search for the customer or type a new name'; QT.markScan(custInput, { value: "", confidence: "missing" }); }
        }
        form.dispatchEvent(new Event("input", { bubbles: true }));
        QT.toast("Ticket read - review the highlighted fields, then Save booking");
      });
    }
    const scanner = QT.ticketScanner($("[data-scanner]", view), {
      onResult: (r) => { applyScan(r); },
      onClear: () => { $$(".scan-ok,.scan-check,.scan-missing", form).forEach((x) => { x.classList.remove("scan-ok", "scan-check", "scan-missing"); x.title = ""; }); },
      onFare: (name, fare) => {
        const cur = fld("currency").value;
        if (fare.currency && fare.currency !== cur) { QT.toast("The ticket fare is in " + fare.currency + " but this booking is in " + cur + ". Change the currency first, or enter the amount yourself.", "err"); return; }
        setVal(fld(name), String(fare.total.value));
        QT.markScan(fld(name), { value: fare.total.value, confidence: "medium", note: "Total fare printed on the ticket" });
      },
    });
    view.insertAdjacentHTML("beforeend", '<datalist id="airlines-dl2">' + QT.AIRLINES.map((a) => '<option value="' + esc(a) + '">').join("") + "</datalist>");
    $("[data-add-pax]", view).onclick = () => { paxBox.appendChild(paxRow({}, $$(".pax-row", paxBox).length)); $$("[data-p=name]", paxBox).pop().focus(); };
    $("[data-add-seg]", view).onclick = () => segBox.appendChild(segRow({}, $$(".seg-row", segBox).length));
    $("[data-add-ret]", view).onclick = () => {
      const rows = $$(".seg-row", segBox);
      const last = rows[rows.length - 1];
      const g = (k) => (last ? $("[data-s=" + k + "]", last).value : "");
      segBox.appendChild(segRow({ direction: "return", airline: g("airline"), origin: g("destination"), destination: g("origin") }, rows.length));
      $("[name=trip_type]", view).value = "round_trip";
    };

    // ---------- live summary
    const sumEl = $("[data-summary]", view);
    // ---------- collapsible summary panel (UI only; remembered per browser)
    const sumCard = $("[data-sum-card]", view);
    const sumToggle = $("[data-sum-toggle]", view);
    function setCollapsed(c) {
      sumCard.classList.toggle("collapsed", c);
      form.classList.toggle("sum-collapsed", c);
      sumToggle.setAttribute("aria-expanded", String(!c));
      sumToggle.title = c ? "Expand summary" : "Minimize summary";
      $("[data-sum-toggle-lbl]", sumToggle).textContent = c ? "Expand" : "Minimize";
      sumToggle.setAttribute("aria-label", sumToggle.title);
    }
    setCollapsed(QT.pref.get("booking_sum_collapsed", "0") === "1");
    sumToggle.onclick = () => {
      const c = !sumCard.classList.contains("collapsed");
      setCollapsed(c);
      QT.pref.set("booking_sum_collapsed", c ? "1" : "0");
    };
    $(".sum-head", view).addEventListener("click", (e) => { if (sumCard.classList.contains("collapsed") && !e.target.closest("[data-sum-toggle]")) sumToggle.click(); });
    function toggleRate() {
      const base = $("[name=currency]", view).value === QT.base();
      $$("[data-rate-wrap]", view).forEach((w) => w.classList.toggle("hidden", base));
    }
    function summary() {
      const v = (n) => QT.parseMinor($("[name=" + n + "]", view).value);
      const cur = $("[name=currency]", view).value;
      const rate = cur === QT.base() ? "1" : ($("[name=exchange_rate]", view).value || "").trim();
      const pc = v("purchase_cost"), sp = v("selling_price"), cm = v("commission"), sc = v("service_charge"), oi = v("other_income");
      const bad = [pc, sp, cm, sc, oi].some((x) => isNaN(x));
      const prof = meta.tax_profiles.find((t) => String(t.id) === $("[name=tax_profile_id]", view).value);
      const manual = $("[name=tax_override]", view).checked;
      $("[data-tax-manual]", view).classList.toggle("hidden", !manual);
      let taxable = 0, tax = 0;
      const markup = sp - pc;
      if (manual) {
        taxable = v("taxable_value");
        const tr = $("[name=tax_rate]", view).value.trim() || (prof ? prof.rate : "0");
        const ta = $("[name=tax_amount]", view).value.trim();
        tax = ta ? QT.parseMinor(ta) : QT.percentOf(taxable, tr);
      } else if (prof) {
        taxable = { none: 0, service_charge: sc, markup: Math.max(markup, 0), service_and_markup: sc + Math.max(markup, 0), selling_price: sp, custom: v("taxable_value") }[prof.basis] || 0;
        tax = QT.percentOf(taxable, prof.rate);
      }
      const gross = markup + cm + sc + oi;
      const payable = sp + sc + oi + tax;
      const supplier = pc - cm;
      const st = $("[name=status]", view).value;
      const committed = ["confirmed", "ticketed"].includes(st) || (existing && existing.booking.committed);
      const conv = (x) => (cur === QT.base() ? null : QT.convert(x, rate));
      const line = (label, val, cls, strong) => h`<div class="sum-row ${strong ? "big" : ""}"><span class="${strong ? "" : "muted"}">${label}</span><span class="v ${cls || ""}">${QT.money(val, cur)}${conv(val) !== null && !isNaN(conv(val)) ? QT.raw('<div class="small muted" style="font-weight:500">' + QT.money(conv(val)) + "</div>") : ""}</span></div>`.html;
      $("[data-sum-cur]", view).textContent = cur;
      $("[data-sum-mini]", view).innerHTML = bad ? "" : h`<span>Profit <b class="${gross < 0 ? "neg" : "pos"}">${QT.money(gross, cur)}</b></span><span>Pays <b>${QT.money(payable, cur)}</b></span>`.html;
      sumEl.innerHTML = bad ? '<div class="alert danger">' + icon("alert") + "<div>Amounts can have at most 2 decimal places.</div></div>" :
        line("Selling price", sp) + line("Purchase cost", pc) + line("Markup", markup, markup < 0 ? "neg" : "") +
        line("Commission", cm) + line("Service charge", sc) + line("Other income", oi) +
        line("Gross booking profit", gross, gross < 0 ? "neg" : "pos", true) +
        '<div style="height:8px"></div>' +
        line("Tax" + (prof || manual ? "" : " (none)") + (taxable ? " on " + QT.money(taxable, cur) : ""), tax) +
        line("Customer pays", payable, "", true) +
        line("Payable to supplier", supplier) +
        (committed ? "" : '<div class="alert warn small" style="margin-top:10px">' + icon("info") + "<div>" + esc(QT.labels.status[st]) + " bookings are not counted as sales or payables until confirmed or ticketed. Any payment taken is held as a customer advance.</div></div>") +
        (markup < 0 ? '<div class="alert danger small" style="margin-top:10px">' + icon("alert") + "<div>Selling price is below purchase cost.</div></div>" : "");
    }
    form.addEventListener("input", summary);
    form.addEventListener("change", () => { toggleRate(); summary(); });
    toggleRate();
    summary();

    // ---------- save
    async function save(force, reviewed) {
      const pending = $$(".scan-check,.scan-missing", form).length;
      if (scanner.current() && pending && !reviewed) {
        const ok = await QT.confirm({ title: "Review & save booking", message: pending + " field" + (pending === 1 ? " is" : "s are") + " still marked “check” or “missing” from the scanned ticket (highlighted in amber / red). Save the booking anyway?", ok: "Save booking" });
        if (!ok) { const f = $(".scan-missing,.scan-check", form); if (f) { f.scrollIntoView({ block: "center" }); f.focus(); } return; }
        reviewed = true;
      }
      const d = QT.formData(form);
      delete d.undefined;
      d.passengers = $$(".pax-row", paxBox).map((r) => {
        const o = {};
        $$("[data-p]", r).forEach((x) => { o[x.dataset.p] = x.value.trim(); });
        return o;
      });
      d.segments = $$(".seg-row", segBox).map((r) => {
        const o = {};
        $$("[data-s]", r).forEach((x) => { o[x.dataset.s] = x.value.trim(); });
        return o;
      });
      if (!d.customer_id) {
        const nm = $("[data-nc=name]", view).value.trim() || (!newCust.classList.contains("hidden") ? "" : custInput.value.trim());
        if (!nm) { QT.showErr(form, { message: "Select a customer or create a new one", field: "customer_id" }); custInput.focus(); return; }
        d.new_customer = { name: nm, phone: $("[data-nc=phone]", view).value.trim(), email: $("[data-nc=email]", view).value.trim() };
      }
      if (locked) {
        ["purchase_cost", "selling_price", "commission", "service_charge", "other_income", "taxable_value", "tax_amount"].forEach((k) => { d[k] = QT.plain(b[k]); });
        d.currency = b.currency; d.exchange_rate = b.exchange_rate; d.tax_profile_id = b.tax_profile_id || ""; d.tax_override = !!b.tax_override; d.tax_rate = b.tax_rate;
      }
      if (force) d.force_duplicate = true;
      const btns = $$("[data-save],[data-save2]", view);
      btns.forEach((x) => { x.disabled = true; });
      try {
        const r = editing ? await QT.put("/api/bookings/" + id, d) : await QT.post("/api/bookings", d);
        QT.toast(editing ? "Booking updated" : "Booking " + r.booking.ref + " created");
        const sc = scanner.current();
        if (sc) {   // keep the original ticket with the booking
          try { await QT.post("/api/bookings/" + r.booking.id + "/attach-scan", { scan_id: sc.scan_id }); QT.toast("Ticket file " + sc.file_name + " attached to " + r.booking.ref); }
          catch (e) { QT.toast("Booking saved, but the ticket file could not be attached: " + e.message + " - upload it on the booking page.", "err"); }
        }
        await QT.loadMeta();
        QT.go("#/bookings/" + r.booking.id);
      } catch (e) {
        if (e.code === "duplicate_pnr") {
          const ok = await QT.confirm({ title: "PNR already saved", message: e.message, ok: "Save as separate booking" });
          if (ok) return save(true, reviewed);
          return;
        }
        if (e.code === "duplicate_customer") {
          QT.toast(e.message + " — select them from the list instead.", "err");
          return;
        }
        QT.showErr(form, e);
      } finally { btns.forEach((x) => { x.disabled = false; }); }
    }
    $("[data-save]", view).onclick = () => save(false);
    $("[data-save2]", view).onclick = () => save(false);
    if (!cust) setTimeout(() => custInput.focus(), 50);
  };

  // ================================================================ BOOKING DETAIL
  QT.pages.booking = async function (view, params, id) {
    const d = await QT.get("/api/bookings/" + id);
    const b = d.booking, c = d.calc, base = d.base_currency;
    const cur = b.currency;
    const segs = d.segments;
    const first = segs[0], last = segs.filter((s) => s.direction !== "return").pop() || first;
    const activeInv = d.invoices.find((i) => i.doc_type === "invoice" && i.status === "issued");
    const fx = cur !== base;

    view.innerHTML = h`<div class="page-head"><div><div class="crumbs"><a href="#/bookings">Bookings</a> / ${b.ref}</div>
        <h1>${b.lead_pax || b.customer_name}${b.pax_count > 1 ? " + " + (b.pax_count - 1) : ""}</h1>
        <div class="sub">${QT.labels.service[b.service_type]} · booked ${QT.date(b.booking_date)} · ${b.customer_name}</div></div>
      <div class="actions">
        <a class="btn" href="#/bookings/${b.id}/edit">${QT.raw(icon("edit"))}Edit</a>
        <button class="btn" data-receipt>${QT.raw(icon("arrowIn"))}Receive payment</button>
        <button class="btn" data-paysup>${QT.raw(icon("arrowOut"))}Pay supplier</button>
        <div class="dd"><button class="btn wa" data-wa>${QT.raw(icon("whatsapp"))}WhatsApp${QT.raw(icon("chevronDown").replace("<svg", '<svg style="width:14px;height:14px;margin:0 -3px 0 2px"'))}</button></div>
        <div class="dd"><button class="btn" data-more>${QT.raw(icon("more"))}More</button></div>
        ${activeInv ? QT.raw('<button class="btn primary" data-inv-view>' + icon("invoice") + "View invoice</button>") : QT.raw('<button class="btn primary" data-inv>' + icon("invoice") + "Create invoice</button>")}
      </div></div>
      ${d.duplicates.length ? QT.raw('<div class="alert warn" style="margin-bottom:14px">' + icon("alert") + "<div>This PNR is also saved in " + d.duplicates.map((x) => '<a href="#/bookings/' + x.id + '">' + esc(x.ref) + "</a>").join(", ") + ".</div></div>") : ""}
      <div class="card bk-hero" style="margin-bottom:16px">
        <div>
          <div class="ref">${b.ref} · PNR</div>
          <div class="big-pnr">${b.pnr || "—"}</div>
          ${first ? QT.raw(h`<div class="bk-route"><span class="ap">${first.origin || "?"}</span><span class="line">${QT.raw(icon("plane"))}</span><span class="ap">${last.destination || "?"}</span>
            <span class="muted">${b.trip_type === "round_trip" ? "Round trip" : b.trip_type === "multi_city" ? "Multi-city" : "One way"} · ${b.airlines.join(", ")}</span></div>`.html) : ""}
        </div>
        <div class="right">
          <div class="row" style="justify-content:flex-end;gap:6px;margin-bottom:10px">${QT.statusBadge(b.status)}${QT.payBadge(c.payment_status)}</div>
          <div class="muted small">Customer outstanding</div>
          <div style="font-size:24px;font-weight:800" class="${c.customer_outstanding > 0 ? "neg" : c.customer_outstanding < 0 ? "pos" : ""}">${QT.money(c.customer_outstanding)}</div>
          <div class="small muted">Supplier outstanding ${QT.money(c.supplier_outstanding)}</div>
        </div>
      </div>
      <div class="grid g-main">
        <div class="stack">
          <div class="card"><div class="card-h"><h3>Itinerary</h3><span class="sub">${segs.length} segment${segs.length === 1 ? "" : "s"}</span></div><div class="card-b flush" data-segs></div></div>
          <div class="card"><div class="card-h"><h3>Passengers</h3><span class="sub">${d.passengers.length}</span></div><div class="card-b flush" data-pax></div></div>
          <div class="card"><div class="card-h"><h3>Payments & refunds</h3><div class="actions"><button class="btn sm" data-receipt2>${QT.raw(icon("plus"))}Receipt</button><button class="btn sm" data-refund>${QT.raw(icon("refund"))}Refund</button><button class="btn sm" data-exp>${QT.raw(icon("expense"))}Direct expense</button></div></div><div class="card-b flush" data-txns></div></div>
          <div class="card"><div class="card-h"><h3>Cancellations & adjustments</h3><div class="actions"><button class="btn sm" data-adj>${QT.raw(icon("plus"))}Adjustment</button></div></div><div class="card-b flush" data-adjs></div></div>
          <div class="card"><div class="card-h"><h3>Documents</h3><div class="actions"><label class="btn sm">${QT.raw(icon("upload"))}Upload<input type="file" hidden data-upload accept=".pdf,.png,.jpg,.jpeg,.webp,.gif,.heic,.txt,.csv,.doc,.docx,.xls,.xlsx"></label></div></div><div class="card-b flush" data-docs></div></div>
        </div>
        <div class="stack">
          <div class="card"><div class="card-h"><h3>Profit & balances</h3><span class="sub">${fx ? cur + " @ " + b.exchange_rate + " → " + base : base}</span></div><div class="card-b" data-fin></div></div>
          <div class="card"><div class="card-h"><h3>Details</h3></div><div class="card-b" data-details></div></div>
          <div class="card"><div class="card-h"><h3>Invoices & credit notes</h3></div><div class="card-b flush" data-invs></div></div>
          <div class="card"><div class="card-h"><h3>History</h3></div><div class="card-b" data-hist></div></div>
        </div>
      </div>`.html;

    // itinerary
    $("[data-segs]", view).innerHTML = segs.length ? segs.map((s) => h`<div class="seg-card">
        <div><div class="al">${s.airline || "—"}</div><div class="small muted mono">${s.flight_no || ""}</div>${s.direction === "return" ? QT.raw('<span class="badge b-cyan plain">Return</span>') : ""}</div>
        <div><div class="t">${s.origin || "—"} <span class="muted small">${QT.time(s.dep_at)}</span></div><div class="small muted">${QT.date(s.dep_at)}</div></div>
        <div><div class="t">${s.destination || "—"} <span class="muted small">${QT.time(s.arr_at)}</span></div><div class="small muted">${QT.date(s.arr_at)}</div></div>
        <div class="small muted right">${s.cabin || b.cabin || ""}${QT.raw(s.baggage || b.baggage ? "<br>" + esc(s.baggage || b.baggage) : "")}${s.airline_pnr ? QT.raw('<br><span class="mono">' + esc(s.airline_pnr) + "</span>") : ""}</div>
      </div>`.html).join("") : '<div class="empty-state">No flight segments recorded</div>';

    $("[data-pax]", view).innerHTML = String(QT.table({ sortable: false, rows: d.passengers, columns: [
      { label: "#", render: (p) => String(p.seq + 1) },
      { label: "Passenger", render: (p) => h`<span class="cell-main">${(p.title ? p.title + " " : "") + p.name}</span>` },
      { label: "Type", key: "pax_type" },
      { label: "Ticket number", render: (p) => p.ticket_number ? h`<span class="mono">${p.ticket_number}</span>` : QT.raw('<span class="muted">Not ticketed</span>') },
      { label: "Passport", key: "passport_no" },
    ] }));

    // transactions
    const tx = $("[data-txns]", view);
    tx.innerHTML = d.transactions.length ? d.transactions.map((t) => QT.txnLi(t, t.allocated_here)).join("") : '<div class="empty-state">' + icon("receipt") + "<div>No payments recorded. Ticketed does not mean paid — record each receipt here.</div></div>";
    QT.bindTxnLis(tx, () => QT.reload());

    // adjustments
    const adjBox = $("[data-adjs]", view);
    if (!d.adjustments.length) adjBox.innerHTML = '<div class="empty-state"><div>No cancellations, refunds or reissues.</div></div>';
    else {
      adjBox.innerHTML = String(QT.table({ sortable: false, rows: d.adjustments, rowClass: (a) => (a.reversed_by ? "reversed" : a.sign < 0 ? "reversal" : ""), columns: [
        { label: "Date", key: "adj_date", type: "date" },
        { label: "No.", render: (a) => h`<b>${a.adj_no}</b><div class="cell-sub">${QT.labels.adj[a.kind]}${a.sign < 0 ? " · reversal" : ""}</div>` },
        { label: "Customer credit", type: "money", render: (a) => QT.money(a.sign * a.customer_credit, cur) },
        { label: "Supplier credit", type: "money", render: (a) => QT.money(a.sign * a.supplier_credit, cur) },
        { label: "Charges", type: "money", render: (a) => QT.money(a.sign * a.extra_customer_charge, cur) + (a.extra_supplier_charge ? '<div class="cell-sub">supplier ' + QT.money(a.sign * a.extra_supplier_charge, cur) + "</div>" : "") },
        { label: "", render: (a) => {
          if (a.sign < 0 || a.reversed_by) return "";
          const hasCn = d.invoices.some((i) => i.adjustment_id === a.id && i.status === "issued");
          return (a.customer_credit && !hasCn ? '<button class="btn xs" data-cn="' + a.id + '">Credit note</button> ' : "") + (hasCn ? "" : '<button class="btn xs" data-edit-adj="' + a.id + '">Edit</button> ') + '<button class="btn xs danger" data-rev-adj="' + a.id + '">Reverse</button>';
        } },
      ] }));
      $$("[data-cn]", adjBox).forEach((x) => { x.onclick = async () => { try { const inv = await QT.post("/api/adjustments/" + x.dataset.cn + "/credit-note", { issue_date: QT.today() }); QT.toast("Credit note " + inv.number + " issued"); QT.invoiceView(inv.id); QT.reload(); } catch (e) { QT.fail(e); } }; });
      $$("[data-edit-adj]", adjBox).forEach((x) => { x.onclick = () => QT.adjustmentForm(d, null, () => QT.reload(), d.adjustments.find((a) => String(a.id) === x.dataset.editAdj)); });
      $$("[data-rev-adj]", adjBox).forEach((x) => { x.onclick = async () => {
        const r = await QT.ask({ title: "Reverse adjustment", message: "A reversing entry will be posted. The original stays in the history.", label: "Reason", danger: true, ok: "Reverse" });
        if (!r) return;
        try { await QT.post("/api/adjustments/" + x.dataset.revAdj + "/reverse", { reason: r }); QT.toast("Adjustment reversed"); QT.reload(); } catch (e) { QT.fail(e); }
      }; });
    }

    // documents
    const docs = $("[data-docs]", view);
    docs.innerHTML = d.documents.length ? d.documents.map((x) => h`<div class="li"><div class="ic">${QT.raw(icon("file"))}</div><div class="grow"><div class="t1">${x.original_name}</div><div class="t2">${(x.size / 1024).toFixed(0)} KB · ${QT.dateTime(x.uploaded_at)}</div></div>
      <button class="btn xs" data-open="${x.id}">Open</button><button class="btn xs" data-dl="${x.id}">${QT.raw(icon("download"))}</button><button class="btn xs danger" data-rm="${x.id}">${QT.raw(icon("trash"))}</button></div>`.html).join("")
      : '<div class="empty-state"><div>Upload e-tickets, passport copies or visa documents (PDF, images, Office files · max 15 MB).</div></div>';
    $$("[data-open]", docs).forEach((x) => { x.onclick = () => QT.openDoc(+x.dataset.open); });
    $$("[data-dl]", docs).forEach((x) => { x.onclick = () => QT.download("/api/documents/" + x.dataset.dl).catch(QT.fail); });
    $$("[data-rm]", docs).forEach((x) => { x.onclick = async () => {
      if (!(await QT.confirm({ message: "Remove this document from the booking?", detail: "The file is archived (kept in backups) and the removal is logged.", danger: true, ok: "Remove" }))) return;
      try { await QT.del("/api/documents/" + x.dataset.rm); QT.toast("Document removed"); QT.reload(); } catch (e) { QT.fail(e); }
    }; });
    $("[data-upload]", view).onchange = async (e) => {
      const file = e.target.files[0];
      if (!file) return;
      if (file.size > 15 * 1024 * 1024) { QT.toast("Files must be 15 MB or smaller", "err"); return; }
      try { await QT.post("/api/bookings/" + b.id + "/documents", { name: file.name, data: await QT.fileToBase64(file) }); QT.toast("Uploaded " + file.name); QT.reload(); }
      catch (err) { QT.fail(err); }
    };

    // finance panel
    const L = (label, v, cls, strong) => h`<div class="sum-row ${strong ? "big" : ""}"><span class="${strong ? "" : "muted"}">${label}</span><span class="v ${cls || ""}">${QT.money(v, base)}</span></div>`.html;
    const B = c.base;
    let fin = "";
    if (fx) fin += h`<div class="small muted" style="margin-bottom:8px">Original ${cur}: selling ${QT.money(b.selling_price, cur)} · cost ${QT.money(b.purchase_cost, cur)} · rate ${b.exchange_rate}${b.rate_date ? " on " + QT.date(b.rate_date) : ""}</div>`.html;
    if (!b.committed) fin += '<div class="alert warn small" style="margin-bottom:10px">' + icon("info") + "<div>Not yet confirmed — no revenue or payables recorded. Payments are held as advance.</div></div>";
    fin += L("Selling price", B.selling_price) + L("Purchase cost", B.purchase_cost) + L("Markup", c.markup, c.markup < 0 ? "neg" : "") +
      L("Commission (net)", c.commission_net) + L("Service charge", B.service_charge) + L("Other income", B.other_income);
    if (c.revenue !== B.selling_price + B.service_charge + B.other_income || c.cost !== B.purchase_cost) {
      fin += L("Net sales after credits", c.revenue) + L("Net cost after credits", c.cost);
    }
    fin += L("Direct expenses", -c.direct_expenses, c.direct_expenses ? "neg" : "") + L("Gross profit", c.gross_profit, c.gross_profit < 0 ? "neg" : "pos", true);
    fin += '<div style="height:10px"></div>' + L("Tax collected" + (b.tax_amount ? " (" + b.tax_rate + "%)" : ""), c.tax_net);
    fin += '<div class="lbl" style="margin:14px 0 4px">Customer</div>' + L("Payable", c.customer_payable) + L("Credits / credit notes", -c.customer_credits) + L("Received", -c.received) + L("Refunded", c.refunded) + L("Outstanding", c.customer_outstanding, c.customer_outstanding > 0 ? "neg" : "", true);
    fin += '<div class="lbl" style="margin:14px 0 4px">Supplier · ' + esc(b.supplier_name || "none") + "</div>" + L("Payable", c.supplier_payable) + L("Credits & commission", -c.supplier_credits) + L("Paid", -c.paid_supplier) + L("Refunds received", c.supplier_refunds) + L("Outstanding", c.supplier_outstanding, c.supplier_outstanding > 0 ? "neg" : "", true);
    $("[data-fin]", view).innerHTML = fin;

    $("[data-details]", view).innerHTML = h`<dl class="kv">
      <dt>Customer</dt><dd><a href="#/customers/${b.customer_id}">${b.customer_name}</a>${d.customer && d.customer.phone ? QT.raw('<div class="small muted">' + esc(d.customer.phone) + "</div>") : ""}</dd>
      <dt>Supplier</dt><dd>${b.supplier_id ? QT.raw('<a href="#/suppliers/' + b.supplier_id + '">' + esc(b.supplier_name) + "</a>") : "—"}</dd>
      <dt>Booking date</dt><dd>${QT.date(b.booking_date)}</dd>
      <dt>Travel</dt><dd>${QT.date(b.travel_date) || "—"}${b.return_date ? " → " + QT.date(b.return_date) : ""}</dd>
      <dt>Cabin / baggage</dt><dd>${b.cabin || "—"} · ${b.baggage || "—"}</dd>
      <dt>Tax treatment</dt><dd>${d.tax_profile ? d.tax_profile.name : b.tax_override ? "Manual" : "No tax"}</dd>
      <dt>Place of supply</dt><dd>${b.place_of_supply || "—"}</dd>
      <dt>Notes</dt><dd style="white-space:pre-wrap">${b.notes || "—"}</dd>
    </dl>`.html;

    const invs = $("[data-invs]", view);
    invs.innerHTML = d.invoices.length ? d.invoices.map((i) => h`<div class="li click" data-iv="${i.id}"><div class="ic ${i.doc_type === "credit_note" ? "warn" : ""}">${QT.raw(icon("invoice"))}</div>
      <div class="grow"><div class="t1">${i.number}</div><div class="t2">${i.doc_type === "credit_note" ? "Credit note" : "Invoice"} · ${QT.date(i.issue_date)}</div></div>
      <div class="right"><div class="amt">${QT.money(i.total, i.currency)}</div>${i.status === "cancelled" ? QT.raw('<span class="badge b-red plain">Cancelled</span>') : ""}</div></div>`.html).join("")
      : '<div class="empty-state"><div>No invoice yet</div></div>';
    $$("[data-iv]", invs).forEach((x) => { x.onclick = () => QT.invoiceView(+x.dataset.iv); });

    $("[data-hist]", view).innerHTML = '<ul class="timeline">' + d.history.map((x) => h`<li><div>${x.detail || x.action}</div><div class="when">${QT.dateTime(x.at)}</div></li>`.html).join("") + "</ul>";

    // actions
    const recv = () => QT.txnForm({ kind: "customer_receipt", booking: b, suggest: Math.max(c.customer_outstanding, 0) }, () => QT.reload());
    $("[data-receipt]", view).onclick = recv;
    QT.wa.bookingMenu($("[data-wa]", view), d);
    $("[data-receipt2]", view).onclick = recv;
    $("[data-paysup]", view).onclick = () => {
      if (!b.supplier_id) { QT.toast("Set a supplier on this booking first", "err"); return; }
      QT.txnForm({ kind: "supplier_payment", booking: b, suggest: Math.max(c.supplier_outstanding, 0) }, () => QT.reload());
    };
    $("[data-refund]", view).onclick = () => (c.customer_outstanding < 0 || !b.committed
      ? QT.txnForm({ kind: "customer_refund", booking: b, suggest: Math.max(-c.customer_outstanding, 0) }, () => QT.reload())
      : QT.refundForm(d, () => QT.reload()));
    $("[data-exp]", view).onclick = () => QT.txnForm({ kind: "expense", booking: b, category: "Direct booking cost" }, () => QT.reload());
    $("[data-adj]", view).onclick = () => QT.adjustmentForm(d, null, () => QT.reload());
    if ($("[data-inv]", view)) $("[data-inv]", view).onclick = async () => {
      if (!b.committed) { QT.toast("Confirm or ticket the booking before invoicing", "err"); return; }
      const ok = await QT.confirm({ title: "Issue invoice", message: "Issue an invoice for " + QT.money(c.customer_payable, base) + "?", detail: "Prices on this booking are locked while the invoice is active.", ok: "Issue invoice" });
      if (!ok) return;
      try { const inv = await QT.post("/api/bookings/" + b.id + "/invoice", { issue_date: QT.today() }); QT.toast("Invoice " + inv.number + " issued"); QT.reload(); QT.invoiceView(inv.id); } catch (e) { QT.fail(e); }
    };
    if ($("[data-inv-view]", view)) $("[data-inv-view]", view).onclick = () => QT.invoiceView(activeInv.id);

    const statusItems = Object.entries(QT.labels.status).filter(([k]) => k !== b.status && (!b.committed || !["cancelled", "refunded"].includes(k))).map(([k, l]) => ({
      label: "Mark as " + l, icon: "check", onClick: async () => {
        try { await QT.post("/api/bookings/" + b.id + "/status", { status: k }); QT.toast("Status changed to " + l); QT.reload(); } catch (e) { QT.fail(e); }
      },
    }));
    QT.dropdown($("[data-more]", view), [
      { label: b.committed ? "Refund / cancel booking" : "Cancel booking", icon: "x", onClick: () => b.committed ? QT.refundForm(d, () => QT.reload()) : QT.post("/api/bookings/" + b.id + "/status", { status: "cancelled" }).then(() => { QT.toast("Booking cancelled"); QT.reload(); }).catch(QT.fail) },
      { label: "Reissue / date change", icon: "swap", onClick: () => QT.adjustmentForm(d, "date_change", () => QT.reload()) },
      "-",
      ...statusItems,
      "-",
      { label: "Supplier refund received", icon: "arrowIn", onClick: () => b.supplier_id ? QT.txnForm({ kind: "supplier_receipt", booking: b, suggest: Math.max(-c.supplier_outstanding, 0) }, () => QT.reload()) : QT.toast("No supplier on this booking", "err") },
      { label: "Print booking summary", icon: "print", onClick: () => printBooking(d) },
      { label: "Duplicate as new booking", icon: "plus", onClick: () => QT.go("#/bookings/new?customer_id=" + b.customer_id) },
      "-",
      { label: "Delete booking", icon: "trash", onClick: async () => {
        if (d.transactions.length || d.adjustments.length || d.invoices.length) {
          QT.toast(b.ref + " has payments, refunds or invoices, so it is kept for your accounts. Use Refund / cancel instead.", "err");
          return;
        }
        if (d.documents.length) { QT.toast("Remove the documents attached to " + b.ref + " first.", "err"); return; }
        const ok = await QT.confirm({ title: "Delete " + b.ref + "?", message: "The booking, its passengers and flights are deleted permanently. A record of the deletion stays in the audit trail.", danger: true, ok: "Delete booking", typeText: "DELETE" });
        if (!ok) return;
        try { await QT.del("/api/bookings/" + b.id); QT.toast(b.ref + " deleted"); QT.go("#/bookings"); } catch (e) { QT.fail(e); }
      } },
    ]);
  };

  function printBooking(d) {
    const b = d.booking;
    const html = h`<div class="doc">${QT.docHead("Booking", '<div><b>' + esc(b.ref) + "</b></div><div>PNR " + esc(b.pnr || "—") + "</div><div>" + esc(QT.date(b.booking_date)) + "</div>")}
      <div class="doc-meta"><div class="box"><div class="lbl">Customer</div><b>${b.customer_name}</b><div>${d.customer && d.customer.phone || ""}</div></div>
      <div class="box"><div class="lbl">Status</div><b>${QT.labels.status[b.status]}</b><div>Travel ${QT.date(b.travel_date) || "—"}</div></div></div>
      <table><thead><tr><th>Flight</th><th>From</th><th>To</th><th>Departure</th><th>Arrival</th></tr></thead><tbody>
      ${QT.raw(d.segments.map((s) => "<tr><td>" + esc((s.airline || "") + " " + (s.flight_no || "")) + "</td><td>" + esc(s.origin || "") + "</td><td>" + esc(s.destination || "") + "</td><td>" + esc(QT.dateTime(s.dep_at)) + "</td><td>" + esc(QT.dateTime(s.arr_at)) + "</td></tr>").join(""))}
      </tbody></table><div style="height:14px"></div>
      <table><thead><tr><th>Passenger</th><th>Type</th><th>Ticket</th></tr></thead><tbody>
      ${QT.raw(d.passengers.map((p) => "<tr><td>" + esc((p.title ? p.title + " " : "") + p.name) + "</td><td>" + esc(p.pax_type) + "</td><td>" + esc(p.ticket_number || "") + "</td></tr>").join(""))}
      </tbody></table>${QT.docFoot()}</div>`;
    QT.print(html, b.ref);
  }

  // ================================================================ ADJUSTMENT FORM
  QT.adjustmentForm = function (d, kind, done, edit) {
    const b = d.booking, cur = b.currency;
    let c = d.calc;
    if (edit) { // judge the edited values against the booking without this adjustment
      const rate = b.exchange_rate, cv = (x) => QT.convert(x, rate);
      c = Object.assign({}, c, { customer_outstanding: c.customer_outstanding + cv(edit.customer_credit) - cv(edit.extra_customer_charge),
        customer_credits: c.customer_credits - cv(edit.customer_credit),
        supplier_outstanding: c.supplier_outstanding + cv(edit.supplier_credit) - cv(edit.commission_reversal) - cv(edit.extra_supplier_charge),
        commission_net: c.commission_net + cv(edit.commission_reversal),
        gross_profit: c.gross_profit + (cv(edit.customer_credit) - cv(edit.customer_credit_tax)) - cv(edit.extra_customer_charge) - cv(edit.supplier_credit) + cv(edit.extra_supplier_charge) + cv(edit.commission_reversal) });
      kind = edit.kind;
    }
    const cancel = kind === "cancellation";
    const body = el(h`<div class="stack" style="gap:14px">
      <div class="alert info">${QT.raw(icon("info"))}<div>Enter the <b>credits</b> (amounts no longer owed) and any <b>charges</b>. Cash refunds are recorded separately as transactions, so nothing is double counted. Amounts in ${cur}.</div></div>
      <div class="form-grid">
        ${QT.field({ name: "kind", label: "Type", type: "select", span: 4, opts: QT.options(Object.entries(QT.labels.adj), kind || "refund") })}
        ${QT.field({ name: "adj_date", label: "Date", type: "date", span: 4, value: QT.today() })}
        ${QT.field({ name: "new_status", label: "Set booking status", type: "select", span: 4, opts: QT.options([["", "Keep " + QT.labels.status[b.status]], ["cancelled", "Cancelled"], ["refunded", "Refunded"], ["ticketed", "Ticketed (reissued)"]], cancel ? "cancelled" : "") })}
        <div class="f s12 lbl" style="margin-top:4px">Customer side</div>
        ${QT.field({ name: "customer_credit", label: "Credit to customer (incl. tax)", type: "money", span: 4, hint: "Billed " + QT.money(c.customer_payable - c.customer_credits) })}
        ${QT.field({ name: "customer_credit_tax", label: "of which tax", type: "money", span: 4 })}
        ${QT.field({ name: "extra_customer_charge", label: "Extra charge to customer", type: "money", span: 4, hint: "e.g. reissue / date change fee" })}
        <div class="f s12 lbl" style="margin-top:4px">Supplier side</div>
        ${QT.field({ name: "supplier_credit", label: "Credit from supplier", type: "money", span: 4, hint: "Refund the portal owes you" })}
        ${QT.field({ name: "commission_reversal", label: "Commission reversed", type: "money", span: 4, hint: "Earned " + QT.money(c.commission_net) })}
        ${QT.field({ name: "extra_supplier_charge", label: "Extra charge from supplier", type: "money", span: 4, hint: "Airline penalty / fare difference" })}
        ${QT.field({ name: "notes", label: "Notes", type: "textarea", span: 12, rows: 2 })}
      </div>
      <div class="card card-b" data-preview style="background:var(--surface-2)"></div>
    </div>`.html);
    if (edit) {
      ["customer_credit", "customer_credit_tax", "extra_customer_charge", "supplier_credit", "commission_reversal", "extra_supplier_charge"].forEach((n) => { $("[name=" + n + "]", body).value = edit[n] ? QT.plain(edit[n]) : ""; });
      $("[name=adj_date]", body).value = edit.adj_date;
      $("[name=notes]", body).value = edit.notes || "";
      $("[name=new_status]", body).value = "";
    }
    const preview = () => {
      const v = (n) => QT.parseMinor($("[name=" + n + "]", body).value) || 0;
      const rate = b.exchange_rate;
      const cv = (x) => QT.convert(x, rate);
      const custAfter = c.customer_outstanding - cv(v("customer_credit")) + cv(v("extra_customer_charge"));
      const supAfter = c.supplier_outstanding - cv(v("supplier_credit")) + cv(v("commission_reversal")) + cv(v("extra_supplier_charge"));
      const gpAfter = c.gross_profit - (cv(v("customer_credit")) - cv(v("customer_credit_tax"))) + cv(v("extra_customer_charge")) + cv(v("supplier_credit")) - cv(v("extra_supplier_charge")) - cv(v("commission_reversal"));
      $("[data-preview]", body).innerHTML = h`<div class="lbl" style="margin-bottom:6px">After this adjustment</div>
        <div class="sum-row"><span class="muted">Customer outstanding</span><span class="v ${custAfter > 0 ? "neg" : custAfter < 0 ? "pos" : ""}">${QT.money(custAfter)}${custAfter < 0 ? " (refund due)" : ""}</span></div>
        <div class="sum-row"><span class="muted">Supplier outstanding</span><span class="v">${QT.money(supAfter)}${supAfter < 0 ? " (supplier owes you)" : ""}</span></div>
        <div class="sum-row"><span class="muted">Gross profit on booking</span><span class="v ${gpAfter < 0 ? "neg" : "pos"}">${QT.money(gpAfter)}</span></div>`.html;
    };
    body.addEventListener("input", preview);
    preview();
    QT.modal({
      title: edit ? "Edit " + edit.adj_no + " · " + b.ref : (cancel ? "Cancel / refund " : "Adjustment · ") + b.ref, size: "lg", body,
      actions: [{ label: "Cancel" }, { label: edit ? "Save changes" : "Post adjustment", cls: "primary", icon: "check", onClick: async () => {
        if (edit) await QT.post("/api/adjustments/" + edit.id + "/correct", Object.assign(QT.formData(body), { reason: "Edited" }));
        else await QT.post("/api/bookings/" + b.id + "/adjustments", QT.formData(body));
        QT.toast(edit ? edit.adj_no + " corrected (the original is kept as reversed)" : "Adjustment posted");
        done && done();
      } }],
    });
  };

  // ================================================================ REFUND / CANCEL
  // One step: what the customer gets back, what the airline / supplier refunds and any commission lost.
  // Profit moves by what the agency keeps; the cash refund itself only moves money out of the account.
  QT.refundForm = function (d, done) {
    const b = d.booking, c = d.calc, cur = b.currency, base = QT.base(), rate = b.exchange_rate;
    const cv = (x) => QT.convert(x, rate);
    const meta = QT.state.meta;
    const accounts = meta.accounts.filter((a) => a.active && a.currency === base);
    const billed = c.customer_payable - c.customer_credits;           // base
    const paid = c.received - c.refunded;                             // base
    const supNet = c.supplier_payable - (c.supplier_credits - c.commission_net);
    const idem = QT.uuid();
    const accOpts = (sel) => QT.options(accounts.map((a) => [a.id, QT.accLabel(a)]), sel);
    const lastAcc = (accounts.find((a) => String(a.id) === QT.pref.get("acc.customer_refund", "")) || accounts[0] || {}).id;
    const body = el(h`<form class="stack" style="gap:14px" onsubmit="return false">
      <div class="alert info small">${QT.raw(icon("info"))}<div>Profit changes by what your agency <b>keeps</b>: the refund to the customer is taken off the sale, and the refund you get from the airline / portal is taken off the cost. Amounts in ${cur}.</div></div>
      <div class="form-grid">
        ${QT.field({ name: "date", label: "Refund date", type: "date", span: 4, value: QT.today() })}
        ${QT.field({ name: "new_status", label: "Booking status", type: "select", span: 8, opts: QT.options([["cancelled", "Cancelled (full refund)"], ["refunded", "Refunded"], ["", "Keep " + QT.labels.status[b.status] + " (partial refund)"]], "cancelled") })}
        ${QT.field({ name: "customer_refund", label: "Refund to customer", type: "money", span: 4, value: cur === base ? QT.plain(Math.min(Math.max(paid, 0), billed) || billed) : "", hint: "Billed " + QT.money(billed) + " · paid " + QT.money(paid) })}
        ${c.tax_net > 0 ? QT.field({ name: "customer_refund_tax", label: "of which tax", type: "money", span: 4, hint: "Tax billed " + QT.money(c.tax_net) }) : ""}
        ${QT.field({ name: "supplier_refund", label: "Refund from airline / supplier", type: "money", span: 4, value: cur === base ? QT.plain(supNet) : "", hint: "Ticket cost " + QT.money(supNet) + " · enter less if the airline keeps a penalty" })}
        ${c.commission_net > 0 ? QT.field({ name: "commission_reversal", label: "Commission given back", type: "money", span: 4, hint: "Earned " + QT.money(c.commission_net) }) : ""}
        ${QT.field({ name: "notes", label: "Notes", span: 12, placeholder: "e.g. cancelled by passenger, airline penalty 300" })}
      </div>
      <div class="card card-b" data-preview style="background:var(--surface-2)"></div>
      <div class="card"><div class="card-b form-grid">
        <div class="f s12"><label class="check"><input type="checkbox" data-pay ${paid > 0 && accounts.length ? "checked" : ""}> <b>Pay the refund to the customer now</b></label></div>
        <div class="f s4" data-paybox><label>Paid from</label><select class="inp" data-pay-acc>${accOpts(lastAcc)}</select></div>
        <div class="f s3" data-paybox><label>Amount (${base})</label><input class="inp money" data-pay-amt inputmode="decimal"></div>
        <div class="f s2" data-paybox><label>Method</label><select class="inp" data-pay-method>${QT.options(Object.entries(QT.labels.method), "bank_transfer")}</select></div>
        <div class="f s3" data-paybox><label>Reference / UTR</label><input class="inp" data-pay-ref></div>
        ${b.supplier_id ? QT.raw('<div class="f s12" style="margin-top:6px"><label class="check"><input type="checkbox" data-sup> <b>Supplier / airline refund already received</b> <span class="muted small">(e.g. back in the portal wallet)</span></label></div>'
          + '<div class="f s4" data-supbox><label>Received into</label><select class="inp" data-sup-acc>' + String(accOpts(lastAcc)) + '</select></div><div class="f s3" data-supbox><label>Amount (' + base + ')</label><input class="inp money" data-sup-amt inputmode="decimal"></div>'
          + '<div class="f s2" data-supbox><label>Method</label><select class="inp" data-sup-method>' + String(QT.options(Object.entries(QT.labels.method), "portal_wallet")) + '</select></div><div class="f s3" data-supbox><label>Reference</label><input class="inp" data-sup-ref></div>') : ""}
      </div></div>
    </form>`.html);
    const v = (n) => { const f = $("[name=" + n + "]", body); return f ? Math.max(QT.parseMinor(f.value) || 0, 0) : 0; };
    let payTouched = false, supTouched = false;
    function preview() {
      const r = cv(v("customer_refund")), rt = cv(v("customer_refund_tax")), s = cv(v("supplier_refund")), cm = cv(v("commission_reversal"));
      const after = c.gross_profit - (r - rt) + s - cm;
      const custDue = Math.max(-(c.customer_outstanding - r), 0);           // what we now owe the customer
      const supDue = Math.max(-(c.supplier_outstanding - s + cm), 0);       // what the supplier now owes us
      if (!payTouched) $("[data-pay-amt]", body).value = custDue ? QT.plain(custDue) : "";
      if ($("[data-sup-amt]", body) && !supTouched) $("[data-sup-amt]", body).value = supDue ? QT.plain(supDue) : "";
      $$("[data-paybox]", body).forEach((x) => x.classList.toggle("hidden", !$("[data-pay]", body).checked));
      $$("[data-supbox]", body).forEach((x) => x.classList.toggle("hidden", !($("[data-sup]", body) && $("[data-sup]", body).checked)));
      $("[data-preview]", body).innerHTML = h`<div class="pay-sum">
          <div><span class="muted">Profit now</span><b>${QT.money(c.gross_profit)}</b></div>
          <div><span class="muted">Profit after refund</span><b class="${after < 0 ? "neg" : ""}">${QT.money(after)}</b></div>
          <div><span class="muted">You keep from the customer</span><b>${QT.money(billed - r)}</b></div>
          <div><span class="muted">Airline / supplier keeps</span><b>${QT.money(Math.max(supNet - s, 0))}</b></div>
        </div>
        <div class="small muted" style="margin-top:8px">After this: refund due to customer ${QT.money(custDue)} · refund due from supplier ${QT.money(supDue)}</div>
        ${r > billed ? QT.raw('<div class="neg small" style="margin-top:4px">The refund is more than the customer was billed (' + QT.money(billed) + ").</div>") : ""}`.html;
    }
    body.addEventListener("input", (e) => { if (e.target.matches("[data-pay-amt]")) payTouched = true; if (e.target.matches("[data-sup-amt]")) supTouched = true; preview(); });
    body.addEventListener("change", preview);
    preview();
    QT.modal({ title: "Refund / cancel " + b.ref + (b.pnr ? " · " + b.pnr : ""), size: "lg", body, sticky: true,
      actions: [{ label: "Close" }, { label: "Record refund", cls: "primary", icon: "check", onClick: async () => {
        const f = QT.formData(body);
        const data = { idem_key: idem, date: f.date, new_status: f.new_status, notes: f.notes, customer_refund: f.customer_refund,
          customer_refund_tax: f.customer_refund_tax || "", supplier_refund: f.supplier_refund, commission_reversal: f.commission_reversal || "" };
        if ($("[data-pay]", body).checked) data.pay_customer = { account_id: $("[data-pay-acc]", body).value, amount: $("[data-pay-amt]", body).value, method: $("[data-pay-method]", body).value, reference: $("[data-pay-ref]", body).value };
        if ($("[data-sup]", body) && $("[data-sup]", body).checked) data.supplier_received = { account_id: $("[data-sup-acc]", body).value, amount: $("[data-sup-amt]", body).value, method: $("[data-sup-method]", body).value, reference: $("[data-sup-ref]", body).value };
        const r = await QT.post("/api/bookings/" + b.id + "/refund", data);
        if (data.pay_customer) QT.pref.set("acc.customer_refund", data.pay_customer.account_id);
        QT.toast(r.idempotent_replay ? "This refund was already recorded" : "Refund recorded · profit " + QT.money(r.profit_before) + " → " + QT.money(r.gross_profit));
        await QT.loadMeta();
        done && done(r);
      } }] });
  };

  // ================================================================ DOCUMENT VIEWER
  QT.openDoc = async function (id) {
    try {
      const res = await QT.api("GET", "/api/documents/" + id + "?inline=1", null, { raw: true });
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const type = blob.type;
      let inner;
      if (type.startsWith("image/")) inner = '<img src="' + url + '" style="max-width:100%;border-radius:10px">';
      else if (type === "application/pdf") inner = '<object data="' + url + '" type="application/pdf" style="width:100%;height:70vh;border:0"></object>';
      else { QT.download("/api/documents/" + id); URL.revokeObjectURL(url); return; }
      QT.modal({ title: "Document", size: "xl", body: inner, onClose: () => URL.revokeObjectURL(url), actions: [{ label: "Download", icon: "download", onClick: () => { QT.download("/api/documents/" + id); return false; } }, { label: "Close", cls: "primary" }] });
    } catch (e) { QT.fail(e); }
  };

  // ================================================================ INVOICE VIEW / PRINT
  QT.invoiceHtml = function (inv) {
    const d = inv.doc, cur = inv.currency, cn = inv.doc_type === "credit_note";
    const cust = d.customer || {};
    const bk = d.booking || {};
    const meta = '<div><b>' + esc(inv.number) + "</b></div><div>Date: " + esc(QT.date(inv.issue_date)) + "</div><div>Booking: " + esc(bk.ref || "") + "</div>" + (bk.pnr ? "<div>PNR: " + esc(bk.pnr) + "</div>" : "") + (d.against ? "<div>Against invoice: " + esc(d.against) + "</div>" : "");
    return h`<div class="doc">
      ${QT.docHead(cn ? "Credit note" : "Invoice", meta, d.business)}
      ${inv.status === "cancelled" ? QT.raw('<div style="margin-bottom:12px"><span class="stamp">CANCELLED</span> <span style="color:#64738F">' + esc(inv.cancel_reason || "") + "</span></div>") : ""}
      <div class="doc-meta">
        <div class="box"><div class="lbl">Billed to</div><b>${cust.name}</b><div style="white-space:pre-line">${[cust.address, [cust.city, cust.state].filter(Boolean).join(", "), cust.phone, cust.email].filter(Boolean).join("\n")}</div>${cust.gstin ? QT.raw("<div>GSTIN: " + esc(cust.gstin) + "</div>") : ""}</div>
        <div class="box"><div class="lbl">Travel</div>${QT.raw((bk.segments || []).map((s) => esc((s.airline || "") + " " + (s.flight_no || "") + "  " + (s.origin || "") + " → " + (s.destination || "") + "  " + QT.dateTime(s.dep_at))).join("<br>") || "—")}
          ${bk.place_of_supply ? QT.raw('<div style="margin-top:6px">Place of supply: ' + esc(bk.place_of_supply) + "</div>") : ""}</div>
      </div>
      <table><thead><tr><th>Description</th><th>SAC</th><th class="r">Amount (${cur})</th></tr></thead><tbody>
        ${QT.raw(d.lines.map((l) => "<tr><td><b>" + esc(l.description) + "</b>" + (l.detail ? '<div style="color:#64738F;font-size:11px;margin-top:2px">' + esc(l.detail) + "</div>" : "") + "</td><td>" + esc(l.sac || "") + '</td><td class="r">' + QT.money(l.amount, cur, { nosym: true }) + "</td></tr>").join(""))}
      </tbody></table>
      <div class="totals">
        <div><span>Subtotal</span><span>${QT.money(inv.subtotal, cur)}</span></div>
        ${QT.raw(inv.taxes.map((t) => "<div><span>" + esc(t.label) + "</span><span>" + QT.money(t.amount, cur) + "</span></div>").join(""))}
        <div class="grand"><span>${cn ? "Total credit" : "Total"}</span><span>${QT.money(inv.total, cur)}</span></div>
      </div>
      ${d.business && d.business.invoice_terms ? QT.raw('<div style="margin-top:22px;color:#64738F;font-size:11px"><b>Terms:</b> ' + esc(d.business.invoice_terms) + "</div>") : ""}
      ${QT.docFoot(d.business ? d.business.invoice_footer || "" : undefined)}
    </div>`;
  };
  QT.invoiceView = async function (id) {
    try {
      const inv = await QT.get("/api/invoices/" + id);
      const html = QT.invoiceHtml(inv);
      const body = el('<div style="background:#E9EDF5;padding:16px;border-radius:12px;overflow:auto"><div style="background:#fff;border-radius:8px;box-shadow:0 2px 12px rgba(0,0,0,.08)">' + html + "</div></div>");
      const actions = [];
      if (inv.status === "issued") actions.push({ label: "Cancel " + (inv.doc_type === "credit_note" ? "credit note" : "invoice"), cls: "danger", onClick: async () => {
        const r = await QT.ask({ title: "Cancel " + inv.number, message: "The document is kept and marked cancelled. You can then edit prices and reissue.", label: "Reason", danger: true, ok: "Cancel document" });
        if (!r) return false;
        await QT.post("/api/invoices/" + id + "/cancel", { reason: r });
        QT.toast(inv.number + " cancelled");
        QT.reload();
      } });
      const savePdf = () => QT.print(html, inv.number.replace(/\//g, "-"));
      actions.push({ label: "WhatsApp", icon: "whatsapp", cls: "wa", onClick: () => { QT.wa.forInvoice(inv, savePdf); return false; } });
      actions.push({ label: "Print / Save PDF", icon: "print", cls: "primary", onClick: () => { savePdf(); return false; } });
      QT.modal({ title: (inv.doc_type === "credit_note" ? "Credit note " : "Invoice ") + inv.number, size: "xl", body, actions, noFocus: true });
    } catch (e) { QT.fail(e); }
  };
})();
