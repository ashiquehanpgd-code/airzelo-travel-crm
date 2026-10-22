/* Money: transactions, accounts, reconciliation, invoices */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  const KIND = {
    customer_receipt: { title: "Customer receipt", icon: "arrowIn", cls: "green", party: "customer", inflow: true },
    supplier_payment: { title: "Supplier payment", icon: "arrowOut", cls: "red", party: "supplier" },
    customer_refund: { title: "Refund to customer", icon: "refund", cls: "warn", party: "customer" },
    supplier_receipt: { title: "Supplier refund / receipt", icon: "arrowIn", cls: "cyan", party: "supplier", inflow: true },
    expense: { title: "Expense", icon: "expense", cls: "red" },
    other_income: { title: "Other income", icon: "coins", cls: "green", inflow: true },
    transfer: { title: "Transfer", icon: "swap", cls: "" },
    loan_received: { title: "Loan received (financing)", icon: "coins", cls: "cyan", inflow: true, loan: true },
    loan_repayment: { title: "Loan repayment (principal)", icon: "arrowOut", cls: "warn", loan: true },
  };
  QT.KIND = KIND;

  // ---------------------------------------------------------------- list item
  QT.txnLi = function (t, allocatedHere) {
    const k = KIND[t.kind] || {};
    const sign = t.kind === "transfer" ? "" : (k.inflow ? 1 : -1) * t.sign > 0 ? "+" : "−";
    const party = t.customer_name || t.supplier_name || t.payee || (t.kind === "transfer" ? t.account_name + " → " + t.to_account_name : t.category);
    return h`<div class="li click" data-txn="${t.id}">
      <div class="ic ${k.cls || ""}">${QT.raw(icon(k.icon || "receipt"))}</div>
      <div class="grow"><div class="t1">${party} ${t.reversed_by ? QT.raw('<span class="badge b-gray plain">Reversed</span>') : ""}${t.sign < 0 ? QT.raw('<span class="badge b-warn plain">Reversal</span>') : ""}</div>
        <div class="t2">${t.txn_no} · ${QT.date(t.txn_date)} · ${QT.labels.method[t.method] || t.method}${t.reference ? " · " + t.reference : ""}${t.booking_ref ? " · " + t.booking_ref : ""}${t.allocations && t.allocations.length > 1 ? " · " + t.allocations.length + " bookings" : ""}</div></div>
      <div class="right"><div class="amt ${t.reversed_by || t.sign < 0 ? "muted" : sign === "+" ? "pos" : sign === "−" ? "neg" : ""}">${sign}${QT.money(t.amount, t.currency)}</div>
        <div class="t2">${allocatedHere !== undefined && allocatedHere !== t.base_amount && allocatedHere ? QT.money(allocatedHere) + " on this booking" : k.title || ""}</div></div>
    </div>`.html;
  };
  QT.bindTxnLis = function (container, onChange) {
    container.addEventListener("click", (e) => {
      const li = e.target.closest("[data-txn]");
      if (li && container.contains(li)) QT.txnDetail(+li.dataset.txn, onChange);
    });
  };

  // ---------------------------------------------------------------- transaction form
  QT.txnForm = async function (opts, done) {
    const meta = QT.state.meta;
    const kind = opts.kind;
    const K = KIND[kind];
    const idem = QT.uuid();
    const E = opts.edit || null; // editing a posted entry (saved through /correct: in place, or reversed + re-posted)
    const b = E && E.is_direct && E.booking_id ? { id: E.booking_id, ref: E.booking_ref, pnr: "" } : opts.booking;
    const accounts = meta.accounts.filter((a) => a.active || (E && (a.id === E.account_id || a.id === E.to_account_id)));
    if (!accounts.length) { QT.toast("Create a cash or bank account first", "err"); QT.go("#/accounts"); return; }
    let party = null;
    if (E && K.party) party = K.party === "customer" ? { id: E.customer_id, name: E.customer_name } : { id: E.supplier_id, name: E.supplier_name };
    else if (K.party === "customer") party = opts.customer ? { id: opts.customer.id, name: opts.customer.name } : b ? { id: b.customer_id, name: b.customer_name } : null;
    if (!E && K.party === "supplier") party = opts.supplier ? { id: opts.supplier.id, name: opts.supplier.name } : b && b.supplier_id ? { id: b.supplier_id, name: b.supplier_name } : null;
    const customers = K.party === "customer" && !party ? await QT.get("/api/customers") : null;
    const lastAcc = accounts.find((a) => String(a.id) === QT.pref.get("acc." + kind, ""));
    const defAcc = lastAcc || accounts[0];
    const cats = meta.expense_categories.filter((c) => c.active).map((c) => c.name);
    const suggested = opts.suggest ? QT.plain(opts.suggest) : "";

    const body = el(h`<form class="stack" style="gap:14px" onsubmit="return false">
      <div class="form-grid">
        ${K.party ? QT.raw(K.party === "customer"
          ? '<div class="f s6"><label>Customer <span class="req">*</span></label>' + (party ? '<input class="inp" readonly value="' + esc(party.name) + '"><input type="hidden" name="customer_id" value="' + party.id + '">' : '<input class="inp" data-party placeholder="Search customer"><input type="hidden" name="customer_id">') + "</div>"
          : '<div class="f s6"><label>Supplier <span class="req">*</span></label><select class="inp" name="supplier_id">' + String(QT.options(meta.suppliers.filter((s) => s.active || (party && s.id === party.id)).map((s) => [s.id, s.name]), party ? party.id : "", "— Select —")) + "</select></div>") : ""}
        ${kind === "expense" ? QT.field({ name: "category", label: "Category", type: "select", span: 6, opts: QT.options(cats, opts.category || (b ? "Direct booking cost" : ""), "— Select —"), req: true }) : ""}
        ${kind === "other_income" ? QT.field({ name: "category", label: "Income type", span: 6, value: "", placeholder: "e.g. Bank interest, incentive", req: true }) : ""}
        ${QT.field({ name: "txn_date", label: "Date", type: "date", span: kind === "transfer" ? 4 : 3, value: QT.today(), req: true })}
        ${QT.field({ name: "account_id", label: kind === "transfer" ? "From account" : K.inflow ? "Received into" : "Paid from", type: "select", span: kind === "transfer" ? 4 : 3, opts: QT.options(accounts.map((a) => [a.id, QT.accLabel(a)]), defAcc.id), req: true })}
        ${kind === "transfer" ? QT.field({ name: "to_account_id", label: "To account", type: "select", span: 4, opts: QT.options(accounts.map((a) => [a.id, QT.accLabel(a)]), (accounts.find((a) => a.id !== defAcc.id) || accounts[0]).id) }) : ""}
        <div class="f s4"><label>Amount <span class="req">*</span> <span class="muted" data-cur></span></label><input class="inp money" name="amount" inputmode="decimal" value="${suggested}" placeholder="0.00"></div>
        <div class="f s4 hidden" data-rate><label>Rate to ${QT.base()}</label><input class="inp money" name="exchange_rate" inputmode="decimal" placeholder="e.g. 22.15"><span class="hint" data-base-eq></span></div>
        ${kind === "transfer" ? QT.raw('<div class="f s4 hidden" data-to-amt><label>Amount received <span class="muted" data-to-cur></span></label><input class="inp money" name="to_amount" inputmode="decimal"></div>') : ""}
        ${kind !== "transfer" ? QT.field({ name: "method", label: "Payment method", type: "select", span: 4, opts: QT.options(Object.entries(QT.labels.method), kind === "supplier_payment" ? "portal_wallet" : "upi") }) : ""}
        ${QT.field({ name: "reference", label: "Reference / UTR / cheque no.", span: kind === "transfer" ? 8 : 4, value: "" })}
        ${kind === "expense" ? QT.field({ name: "payee", label: "Paid to", span: 4, value: "", placeholder: "Vendor / person" }) : ""}
        ${QT.field({ name: "notes", label: "Notes", span: 12, value: "" })}
        ${kind === "expense" ? QT.raw('<div class="f s6"><label>Attach bill / receipt (optional)</label><input class="inp" type="file" data-file accept=".pdf,.png,.jpg,.jpeg,.webp,.heic"></div>') : ""}
        ${kind === "expense" && b ? QT.raw('<input type="hidden" name="booking_id" value="' + b.id + '"><div class="f s6"><label>Linked booking</label><input class="inp" readonly value="' + esc(b.ref + (b.pnr ? " · " + b.pnr : "")) + '"><span class="hint">Counted once as a direct cost of this booking</span></div>') : ""}
      </div>
      ${K.party ? QT.raw('<div data-alloc></div>') : ""}
      ${["supplier_payment", "customer_receipt"].includes(kind) ? QT.raw('<div class="card"><div class="card-b" data-pay-sum></div></div>') : ""}
    </form>`.html);

    if (E) {
      const set = (n, val) => { const f = $("[name=" + n + "]", body); if (f && val !== undefined && val !== null) f.value = val; };
      set("txn_date", E.txn_date); set("account_id", E.account_id); set("to_account_id", E.to_account_id); set("amount", QT.plain(E.amount));
      if (E.exchange_rate && E.exchange_rate !== "1") set("exchange_rate", E.exchange_rate);
      if (E.to_amount && E.to_amount !== E.amount) set("to_amount", QT.plain(E.to_amount));
      set("method", E.method); set("reference", E.reference || ""); set("notes", E.notes || ""); set("payee", E.payee || "");
      if (["expense", "other_income"].includes(kind)) set("category", E.category);
    }
    const accSel = $("[name=account_id]", body);
    const amtIn = $("[name=amount]", body);
    const rateIn = $("[name=exchange_rate]", body);
    const accOf = (id) => QT.state.meta.accounts.find((a) => String(a.id) === String(id));
    // add a bank / cash account without leaving this form
    $("label", accSel.closest(".f")).insertAdjacentHTML("beforeend", ' <button type="button" class="lnk" data-new-acc>+ Add account</button>');
    $("[data-new-acc]", body).onclick = () => QT.accountForm({}, async (newId) => {
      const fresh = QT.state.meta.accounts.filter((a) => a.active);
      [accSel, $("[name=to_account_id]", body)].filter(Boolean).forEach((sel) => {
        const keep = sel === accSel ? newId : sel.value;
        sel.innerHTML = String(QT.options(fresh.map((a) => [a.id, QT.accLabel(a)]), keep));
      });
      curState(); renderAlloc(); autoAlloc();
    });
    function curState() {
      const a = accOf(accSel.value);
      const cur = a ? a.currency : QT.base();
      $("[data-cur]", body).textContent = "(" + cur + ")";
      $("[data-rate]", body).classList.toggle("hidden", cur === QT.base());
      if (kind === "transfer") {
        const t = accOf($("[name=to_account_id]", body).value);
        const diff = t && t.currency !== cur;
        $("[data-to-amt]", body).classList.toggle("hidden", !diff);
        $("[data-to-cur]", body).textContent = t ? "(" + t.currency + ")" : "";
      }
      const amt = QT.parseMinor(amtIn.value);
      if (cur !== QT.base() && rateIn.value) $("[data-base-eq]", body).textContent = "= " + QT.money(QT.convert(amt, rateIn.value.trim()));
      return { cur, amt, base: cur === QT.base() ? amt : QT.convert(amt, (rateIn.value || "0").trim()) };
    }
    accSel.addEventListener("change", () => { curState(); renderAlloc(); autoAlloc(); });
    if ($("[name=to_account_id]", body)) $("[name=to_account_id]", body).addEventListener("change", curState);
    curState();

    // ----- allocation
    let openBookings = [];
    let partyBal = null;
    const allocBox = $("[data-alloc]", body);
    const sumBox = $("[data-pay-sum]", body);
    async function loadParty(pid) {
      if (!allocBox) return;
      if (!pid) { openBookings = []; partyBal = null; renderAlloc(); return; }
      const d = await QT.get("/api/" + K.party + "s/" + pid);
      partyBal = d.balance;
      const own = {};
      if (E) E.allocations.forEach((a) => { own[a.booking_id] = (own[a.booking_id] || 0) + a.amount; });
      const outKey = K.party === "customer" ? "customer_outstanding" : "supplier_outstanding";
      const wantNeg = kind === "customer_refund" || kind === "supplier_receipt";
      openBookings = d.bookings.filter((x) => (wantNeg ? x[outKey] < 0 : x[outKey] > 0) || (b && x.id === b.id) || own[x.id] || (!wantNeg && x.payment_status === "advance" && K.party === "customer"))
        .map((x) => ({ id: x.id, ref: x.ref, pnr: x.pnr, date: x.travel_date || x.booking_date, due: Math.max(wantNeg ? -x[outKey] : x[outKey], 0) + (own[x.id] || 0), own: own[x.id] || 0, lead: x.lead_pax, committed: x.committed }))
        .sort((x, y) => (x.date || "").localeCompare(y.date || ""));
      if (b) openBookings.sort((x, y) => (x.id === b.id ? -1 : y.id === b.id ? 1 : 0));
      renderAlloc();
      if (E) { $$("[data-alloc-id]", allocBox).forEach((i) => { const x = openBookings.find((o) => String(o.id) === i.dataset.allocId); i.value = x && x.own ? QT.plain(x.own) : ""; }); updateAllocSum(); }
      autoAlloc();
    }
    // until the user edits allocations by hand, keep them in step with the amount (oldest / this booking first)
    let allocTouched = !!E;
    function autoAlloc() {
      if (!allocBox || allocTouched) return;
      let left = curState().base;
      if (isNaN(left)) left = 0;
      $$("[data-alloc-id]", allocBox).forEach((inp) => {
        const x = openBookings.find((o) => String(o.id) === inp.dataset.allocId);
        const take = Math.max(0, Math.min(left, x.due));
        inp.value = take ? QT.plain(take) : "";
        left -= take;
      });
      updateAllocSum();
    }
    function renderAlloc() {
      if (!allocBox) return;
      const st = curState();
      if (!openBookings.length && (kind === "customer_refund" || kind === "supplier_receipt")) {
        updateAllocSum();
        allocBox.innerHTML = '<div class="alert warn small">' + icon("alert") + "<div><b>Nothing is due back yet.</b> To refund a ticket, open the booking and use <b>Refund / cancel</b>: it records what the " + (kind === "customer_refund" ? "customer gets back and what the airline refunds" : "supplier refunds") + ", so the profit is adjusted, and pays the refund in the same step.</div></div>";
        return;
      }
      if (!openBookings.length) {
        updateAllocSum();
        allocBox.innerHTML = '<div class="alert info small">' + icon("info") + "<div>" + (K.party === "customer" ? "No open bookings for this customer. The amount will be held as an <b>advance</b> and can be allocated later." : "No open bookings for this supplier. The amount stays <b>unallocated</b> (e.g. a wallet top-up) and can be allocated later.") + "</div></div>";
        return;
      }
      allocBox.innerHTML = h`<div class="card"><div class="card-h"><h3>Allocate to bookings</h3><span class="sub">amounts in ${QT.base()}${st.cur !== QT.base() ? " (converted)" : ""}</span><div class="actions"><button type="button" class="btn xs" data-auto>Auto allocate</button><button type="button" class="btn xs ghost" data-clear>Clear</button></div></div>
        <div class="card-b flush">${QT.raw(openBookings.map((x) => h`<div class="li"><div class="grow"><div class="t1">${x.ref} ${x.pnr ? QT.raw('<span class="pnr">' + esc(x.pnr) + "</span>") : ""}</div><div class="t2">${x.lead} · ${QT.date(x.date)} · ${kind === "customer_refund" || kind === "supplier_receipt" ? "due back" : "due"} ${QT.money(x.due)}${x.committed ? "" : " · not yet confirmed"}</div></div>
          <input class="inp money inp-sm" style="width:140px" data-alloc-id="${x.id}" inputmode="decimal" placeholder="0.00"></div>`.html).join(""))}</div>
        <div class="card-b small" data-alloc-sum style="border-top:1px solid var(--border)"></div></div>`.html;
      $("[data-auto]", allocBox).onclick = () => { allocTouched = false; autoAlloc(); };
      $("[data-clear]", allocBox).onclick = () => { allocTouched = true; $$("[data-alloc-id]", allocBox).forEach((i) => { i.value = ""; }); updateAllocSum(); };
      $$("[data-alloc-id]", allocBox).forEach((i) => i.addEventListener("input", () => { allocTouched = true; updateAllocSum(); }));
      updateAllocSum();
    }
    // returns a message when the allocation cannot be saved, else ""
    function allocProblem() {
      if (!allocBox) return "";
      const base = curState().base || 0;
      let total = 0, msg = "";
      $$("[data-alloc-id]", allocBox).forEach((i) => {
        const v = QT.parseMinor(i.value);
        const x = openBookings.find((o) => String(o.id) === i.dataset.allocId);
        const bad = isNaN(v) || v < 0 || (x && x.committed && v > x.due && ["supplier_payment", "customer_receipt"].includes(kind));
        i.classList.toggle("err", !!bad);
        if (bad && !msg) msg = isNaN(v) || v < 0 ? "Enter a valid amount for " + x.ref : x.ref + " has only " + QT.money(x.due) + " outstanding";
        total += isNaN(v) ? 0 : v;
      });
      if (!msg && total > base) msg = "Allocated " + QT.money(total) + " is more than the payment amount " + QT.money(base);
      return msg;
    }
    function updateAllocSum() {
      const total = allocBox ? $$("[data-alloc-id]", allocBox).reduce((s, i) => s + (Math.max(QT.parseMinor(i.value), 0) || 0), 0) : 0;
      const base = curState().base || 0;
      const left = base - total;
      const problem = allocProblem();
      const box = allocBox && $("[data-alloc-sum]", allocBox);
      if (box) box.innerHTML = "Allocated <b>" + QT.money(total) + "</b> of " + QT.money(base) + " · " + (left > 0 ? "<b>" + QT.money(left) + "</b> " + (K.party === "customer" ? "held as advance" : "unallocated") : left < 0 ? '<b class="neg">over-allocated by ' + QT.money(-left) + "</b>" : "fully allocated")
        + (problem ? '<div class="neg" style="margin-top:4px">' + esc(problem) + "</div>" : "");
      if (!sumBox) return;
      const who = K.party === "customer" ? "Customer" : "Supplier";
      const owed = partyBal ? partyBal.outstanding : null;
      sumBox.innerHTML = '<div class="pay-sum">'
        + '<div><span class="muted">Payment amount</span><b>' + QT.money(base) + "</b></div>"
        + '<div><span class="muted">Allocated to bookings</span><b>' + QT.money(total) + "</b></div>"
        + '<div><span class="muted">Unallocated' + (K.party === "customer" ? " (advance)" : " (on account)") + '</span><b class="' + (left < 0 ? "neg" : "") + '">' + QT.money(left) + "</b></div>"
        + (owed === null ? '<div><span class="muted">' + who + ' balance</span><b class="muted">select ' + K.party + "</b></div>"
          : '<div><span class="muted">' + who + " balance now → after</span><b>" + QT.money(owed) + " → " + QT.money(owed - base + (E ? E.base_amount : 0)) + "</b></div>")
        + "</div>" + (owed !== null && owed - base + (E ? E.base_amount : 0) < 0 ? '<div class="small muted" style="margin-top:6px">A negative balance means ' + (K.party === "customer" ? "the customer has paid in advance / is in credit." : "you have paid the supplier in advance (e.g. a wallet top-up).") + "</div>" : "");
    }
    amtIn.addEventListener("input", () => { curState(); autoAlloc(); updateAllocSum(); });
    if (rateIn) rateIn.addEventListener("input", () => { curState(); autoAlloc(); updateAllocSum(); });
    if (K.party === "customer" && !party) {
      const inp = $("[data-party]", body);
      QT.combo(inp, {
        items: () => customers, match: (c, q) => c.name.toLowerCase().includes(q) || (c.phone || "").includes(q),
        label: (c) => c.name, sub: (c) => (c.outstanding ? "due " + QT.money(c.outstanding) : c.phone || ""),
        onType: () => { $("[name=customer_id]", body).value = ""; },
        onSelect: (c) => { inp.value = c.name; $("[name=customer_id]", body).value = c.id; loadParty(c.id); },
      });
    } else if (K.party === "supplier") {
      $("[name=supplier_id]", body).addEventListener("change", (e) => loadParty(e.target.value));
    }
    if (party) loadParty(party.id);
    updateAllocSum();

    const titleSuffix = b ? " · " + b.ref : party ? " · " + party.name : "";
    async function submit(force) {
      const problem = allocProblem();
      if (problem) { QT.toast(problem, "err"); return false; }
      if (!(curState().amt > 0)) { QT.showErr(body, { message: "Enter an amount greater than zero", field: "amount" }); return false; }
      const d = QT.formData(body);
      d.kind = kind;
      d.idem_key = idem;
      if (force) d.force_duplicate = true;
      if (b && kind === "expense") d.booking_id = b.id;
      if (allocBox) {
        d.allocations = $$("[data-alloc-id]", allocBox).map((i) => ({ booking_id: +i.dataset.allocId, amount: i.value.trim() })).filter((a) => QT.parseMinor(a.amount) > 0);
        delete d.undefined;
      }
      try {
        const r = E ? await QT.post("/api/transactions/" + E.id + "/correct", d) : await QT.post("/api/transactions", d);
        if (E) {
          QT.toast(r.edit_result === "updated" ? E.txn_no + " updated" : r.edit_result === "replay" ? "Already saved" : E.txn_no + " corrected — now " + r.txn_no + " (the old entry is kept as reversed in the audit trail)");
          await QT.loadMeta();
          done ? done(r) : QT.reload();
          return true;
        }
        const file = $("[data-file]", body) && $("[data-file]", body).files[0];
        if (file) {
          try { await QT.post("/api/transactions/" + r.id + "/documents", { name: file.name, data: await QT.fileToBase64(file) }); }
          catch (e) { QT.toast("Saved, but the attachment failed: " + e.message, "err"); }
        }
        QT.pref.set("acc." + kind, d.account_id);
        const acc = accOf(d.account_id);
        QT.toast(r.idempotent_replay ? "Already saved as " + r.txn_no + " — not recorded twice" : K.title + " " + r.txn_no + " recorded" + (acc ? " from " + acc.name : ""));
        await QT.loadMeta();
        done ? done(r) : QT.reload();
        return true;
      } catch (e) {
        if (e.code === "possible_duplicate") {
          const ok = await QT.confirm({ title: "Possible duplicate", message: e.message, ok: "Record anyway" });
          if (ok) return submit(true);
          return false;
        }
        if (e.code === "refund_not_due") {
          const bid = (e.data && e.data.booking_id) || (b && b.id) || (d.allocations && d.allocations[0] && d.allocations[0].booking_id);
          if (bid && QT.refundForm) {
            const ok = await QT.confirm({ title: "Record the refund on the booking", message: e.message, ok: "Open Refund / cancel" });
            if (ok) { QT.refundForm(await QT.get("/api/bookings/" + bid), () => (done ? done() : QT.reload())); return true; }
            return false;
          }
        }
        throw e;
      }
    }
    QT.modal({ title: E ? "Edit " + K.title.toLowerCase() + " " + E.txn_no : (opts.title || K.title) + titleSuffix, size: K.party ? "lg" : "", body, sticky: true,
      actions: [{ label: "Cancel" }, { label: E ? "Save changes" : "Save " + K.title.toLowerCase(), cls: "primary", icon: "check", onClick: async () => (await submit(false)) }] });
    if (E) body.insertAdjacentHTML("afterbegin", '<div class="alert info small">' + icon("info") + "<div>Changing the amount, date, account or allocation replaces this entry: the original is reversed and the corrected entry is posted in one step, so every balance and report updates and the audit trail shows both.</div></div>");
  };

  // ---------------------------------------------------------------- transaction detail
  QT.txnDetail = async function (id, onChange) {
    let t;
    try { t = await QT.get("/api/transactions/" + id); } catch (e) { QT.fail(e); return; }
    const K = KIND[t.kind];
    const body = el(h`<div class="stack" style="gap:14px">
      ${t.reversed_by ? QT.raw('<div class="alert warn">' + icon("refund") + "<div>This transaction has been reversed. It no longer affects balances.</div></div>") : ""}
      ${t.sign < 0 ? QT.raw('<div class="alert warn">' + icon("refund") + "<div>This is a reversing entry.</div></div>") : ""}
      <div class="row" style="align-items:flex-start"><div class="li" style="padding:0;border:0;flex:1"><div class="ic ${K.cls}">${QT.raw(icon(K.icon))}</div><div class="grow"><div class="t1" style="font-size:16px">${t.kind_label}</div><div class="t2">${t.txn_no} · ID ${t.uid}</div></div></div>
        <div class="right"><div style="font-size:22px;font-weight:800">${QT.money(t.amount, t.currency)}</div>${t.currency !== QT.base() ? QT.raw('<div class="small muted">@ ' + esc(t.exchange_rate) + " = " + QT.money(t.base_amount) + "</div>") : ""}</div></div>
      <dl class="kv">
        <dt>Date</dt><dd>${QT.date(t.txn_date)}</dd>
        <dt>${t.kind === "transfer" ? "From → to" : "Account"}</dt><dd>${t.account_name}${t.kind === "transfer" ? " → " + t.to_account_name + (t.to_amount && t.to_amount !== t.amount ? " (" + QT.plain(t.to_amount) + ")" : "") : ""}</dd>
        ${t.customer_name ? QT.raw("<dt>Customer</dt><dd><a href=\"#/customers/" + t.customer_id + '">' + esc(t.customer_name) + "</a></dd>") : ""}
        ${t.supplier_name ? QT.raw("<dt>Supplier</dt><dd><a href=\"#/suppliers/" + t.supplier_id + '">' + esc(t.supplier_name) + "</a></dd>") : ""}
        ${t.booking_ref ? QT.raw("<dt>Booking</dt><dd><a href=\"#/bookings/" + t.booking_id + '">' + esc(t.booking_ref) + "</a>" + (t.is_direct ? " (direct cost)" : "") + "</dd>") : ""}
        ${t.debt_id ? QT.raw("<dt>Loan / debt</dt><dd><a href=\"#/debts/" + t.debt_id + '">' + esc(t.debt_name + " · " + t.debt_no) + "</a>" + (t.kind === "loan_received" ? " (financing — not income)" : t.kind === "loan_repayment" ? " (principal — not an expense)" : "") + "</dd>") : ""}
        <dt>Category</dt><dd>${t.category}</dd>
        <dt>Method</dt><dd>${QT.labels.method[t.method] || t.method}</dd>
        <dt>Reference</dt><dd>${t.reference || "—"}</dd>
        ${t.payee ? QT.raw("<dt>Paid to</dt><dd>" + esc(t.payee) + "</dd>") : ""}
        <dt>Notes</dt><dd style="white-space:pre-wrap">${t.notes || "—"}</dd>
        <dt>Reconciled</dt><dd>${t.reconciled ? "Yes, " + QT.date(t.reconciled_on) : "No"}</dd>
        <dt>Recorded</dt><dd>${QT.dateTime(t.created_at)}</dd>
      </dl>
      ${t.allocation_options ? QT.raw('<div data-allocs></div>') : ""}
      <div data-docs></div>
      <details><summary class="small muted" style="cursor:pointer">Audit trail (${t.audit.length})</summary><ul class="timeline" style="margin-top:10px">${QT.raw(t.audit.map((a) => "<li><div><b>" + esc(a.action) + "</b> " + esc(a.after_json ? a.after_json.slice(0, 180) : "") + '</div><div class="when">' + esc(QT.dateTime(a.at)) + "</div></li>").join(""))}</ul></details>
    </div>`.html);

    // allocations
    const allocBox = $("[data-allocs]", body);
    function drawAlloc(editing) {
      if (!allocBox) return;
      const map = {};
      t.allocations.forEach((a) => { map[a.booking_id] = a.amount; });
      if (!editing) {
        allocBox.innerHTML = h`<div class="card"><div class="card-h"><h3>Allocation</h3><span class="sub">${QT.money(t.allocated)} allocated · ${QT.money(t.unallocated)} ${t.kind === "customer_receipt" ? "advance" : "unallocated"}</span>
          ${!t.reversed_by && t.sign > 0 ? QT.raw('<div class="actions"><button class="btn xs" data-edit-alloc>' + icon("edit") + "Change</button></div>") : ""}</div>
          <div class="card-b flush">${QT.raw(t.allocations.length ? t.allocations.map((a) => '<div class="li"><div class="grow"><a href="#/bookings/' + a.booking_id + '">' + esc(a.ref) + '</a></div><div class="amt">' + QT.money(a.amount) + "</div></div>").join("") : '<div class="empty-state small">Not allocated to any booking</div>')}</div></div>`.html;
        const eb = $("[data-edit-alloc]", allocBox);
        if (eb) eb.onclick = () => drawAlloc(true);
        $$("a", allocBox).forEach((a) => a.addEventListener("click", () => m.close()));
        return;
      }
      allocBox.innerHTML = h`<div class="card"><div class="card-h"><h3>Change allocation</h3><span class="sub">total ${QT.money(t.base_amount)} (${QT.base()})</span></div><div class="card-b flush">
        ${QT.raw(t.allocation_options.map((o) => h`<div class="li"><div class="grow"><div class="t1">${o.ref} ${o.pnr ? QT.raw('<span class="pnr">' + esc(o.pnr) + "</span>") : ""}</div><div class="t2">${QT.date(o.booking_date)} · balance ${QT.money(o.outstanding)}</div></div><input class="inp money inp-sm" style="width:140px" data-a="${o.id}" value="${map[o.id] ? QT.plain(map[o.id]) : ""}"></div>`.html).join("") || '<div class="empty-state small">No bookings for this party</div>')}
        </div><div class="card-b row" style="justify-content:flex-end;border-top:1px solid var(--border)"><button class="btn sm" data-cancel>Cancel</button><button class="btn sm primary" data-save>Save allocation</button></div></div>`.html;
      $("[data-cancel]", allocBox).onclick = () => drawAlloc(false);
      $("[data-save]", allocBox).onclick = async () => {
        const allocations = $$("[data-a]", allocBox).map((i) => ({ booking_id: +i.dataset.a, amount: i.value.trim() })).filter((a) => QT.parseMinor(a.amount) > 0);
        try {
          t = Object.assign(t, await QT.put("/api/transactions/" + t.id + "/allocations", { allocations }));
          QT.toast("Allocation updated"); drawAlloc(false); onChange && onChange();
        } catch (e) { QT.fail(e); }
      };
    }
    drawAlloc(false);

    // documents
    const docBox = $("[data-docs]", body);
    function drawDocs() {
      docBox.innerHTML = h`<div class="row"><span class="lbl">Attachments</span>${QT.raw(t.documents.map((d) => '<button class="btn xs" data-doc="' + d.id + '">' + icon("file") + esc(d.original_name) + "</button>").join(""))}
        <label class="btn xs">${QT.raw(icon("upload"))}Attach<input type="file" hidden data-up accept=".pdf,.png,.jpg,.jpeg,.webp,.heic,.txt,.csv,.doc,.docx,.xls,.xlsx"></label></div>`.html;
      $$("[data-doc]", docBox).forEach((x) => { x.onclick = () => QT.openDoc(+x.dataset.doc); });
      $("[data-up]", docBox).onchange = async (e) => {
        const f = e.target.files[0];
        if (!f) return;
        try { await QT.post("/api/transactions/" + t.id + "/documents", { name: f.name, data: await QT.fileToBase64(f) }); t = await QT.get("/api/transactions/" + t.id); drawDocs(); QT.toast("Attached " + f.name); }
        catch (err) { QT.fail(err); }
      };
    }
    drawDocs();

    const actions = [];
    const live = !t.reversed_by && t.sign > 0;
    if (live) actions.push({ label: "Delete", cls: "danger", icon: "trash", onClick: async () => {
      const r = await QT.ask({ title: "Delete " + t.txn_no + "?", message: "It is removed from all balances, reports and bookings. For your audit trail it stays listed as deleted (a reversing entry is posted; nothing is erased). This cannot be undone." + (t.reconciled ? " This entry is ticked as reconciled: the reversing entry will be unreconciled, so tick it at your next reconciliation." : "") + (t.debt_id && (t.kind === "loan_repayment" || t.kind === "expense") ? " To undo a whole loan repayment (principal and interest together), use Reverse repayment instead." : ""), label: "Reason (optional)", required: false, danger: true, ok: "Delete entry" });
      if (r === null) return false;
      await QT.post("/api/transactions/" + t.id + "/reverse", { reason: r || "Deleted", date: t.txn_date });
      QT.toast(t.txn_no + " deleted");
      await QT.loadMeta();
      onChange ? onChange() : QT.reload();
    } });
    if (live && !K.loan) actions.push({ label: "Edit", icon: "edit", onClick: () => { QT.txnForm({ kind: t.kind, edit: t }, () => (onChange ? onChange() : QT.reload())); return true; } });
    if (live && t.debt_id && (t.kind === "loan_repayment" || t.kind === "expense"))
      actions.push({ label: "Reverse repayment", cls: "danger", icon: "refund", onClick: async () => {
        const done = await QT.reverseRepayment(t.id);
        if (done) { await QT.loadMeta(); onChange ? onChange() : QT.reload(); }
        return done;
      } });
    if (t.debt_id) actions.push({ label: "Open debt", icon: "coins", onClick: () => { QT.go("#/debts/" + t.debt_id); return true; } });
    if (["customer_receipt", "customer_refund", "supplier_payment", "supplier_receipt", "expense", "other_income"].includes(t.kind))
      actions.push({ label: "Print voucher", icon: "print", onClick: () => { QT.print(voucherHtml(t), t.txn_no); return false; } });
    if (["customer_receipt", "customer_refund"].includes(t.kind) && t.customer_id && t.sign > 0)
      actions.push({ label: "WhatsApp", icon: "whatsapp", cls: "wa", onClick: () => { QT.wa.forTransaction(t, () => QT.print(voucherHtml(t), t.txn_no)); return false; } });
    actions.push({ label: "Close", cls: "primary" });
    const m = QT.modal({ title: t.txn_no, size: "lg", body, actions, noFocus: true });
  };

  function voucherHtml(t) {
    const titles = { customer_receipt: "Payment receipt", customer_refund: "Refund voucher", supplier_payment: "Payment voucher", supplier_receipt: "Receipt voucher", expense: "Expense voucher", other_income: "Receipt voucher" };
    const party = t.customer_name || t.supplier_name || t.payee || "";
    return h`<div class="doc">${QT.docHead(titles[t.kind], "<div><b>" + esc(t.txn_no) + "</b></div><div>Date: " + esc(QT.date(t.txn_date)) + "</div>")}
      ${t.reversed_by ? QT.raw('<div style="margin-bottom:12px"><span class="stamp">REVERSED</span></div>') : ""}
      <div class="doc-meta"><div class="box"><div class="lbl">${t.kind === "customer_receipt" ? "Received from" : t.kind === "supplier_receipt" || t.kind === "other_income" ? "Received from" : "Paid to"}</div><b>${party || "—"}</b></div>
        <div class="box"><div class="lbl">Payment</div><div>${QT.labels.method[t.method] || t.method}${t.reference ? " · Ref " + t.reference : ""}</div><div>Account: ${t.account_name}</div></div></div>
      <table><thead><tr><th>Description</th><th class="r">Amount</th></tr></thead><tbody>
        ${QT.raw(t.allocations.length ? t.allocations.map((a) => "<tr><td>Against booking " + esc(a.ref) + '</td><td class="r">' + QT.money(a.amount) + "</td></tr>").join("") : "<tr><td>" + esc(t.category + (t.booking_ref ? " · " + t.booking_ref : "")) + '</td><td class="r">' + QT.money(t.amount, t.currency) + "</td></tr>")}
        ${t.unallocated > 0 ? QT.raw("<tr><td>Advance / on account</td><td class=\"r\">" + QT.money(t.unallocated) + "</td></tr>") : ""}
      </tbody></table>
      <div class="totals"><div class="grand"><span>Total</span><span>${QT.money(t.amount, t.currency)}</span></div>${t.currency !== QT.base() ? QT.raw("<div><span>Equivalent @ " + esc(t.exchange_rate) + "</span><span>" + QT.money(t.base_amount) + "</span></div>") : ""}</div>
      ${t.notes ? QT.raw('<div style="margin-top:16px;color:#64738F">Notes: ' + esc(t.notes) + "</div>") : ""}
      <div style="margin-top:50px;display:flex;justify-content:space-between;color:#64738F"><div>Received by</div><div>Authorised signatory</div></div>
      ${QT.docFoot(QT.state.meta.settings.receipt_footer || "")}</div>`;
  }

  // ---------------------------------------------------------------- finance pages
  const TABS = [
    ["receipts", "Customer receipts", "customer_receipt"], ["payments", "Supplier payments", "supplier_payment"],
    ["refunds", "Refunds", "customer_refund,supplier_receipt"], ["expenses", "Expenses", "expense"],
    ["income", "Other income", "other_income"], ["transfers", "Transfers", "transfer"], ["loans", "Loans (financing)", "loan_received,loan_repayment"],
    ["all", "All transactions", ""],
  ];
  QT.pages.finance = async function (view, params, tab) {
    tab = tab || "receipts";
    const t = TABS.find((x) => x[0] === tab) || TABS[0];
    const meta = QT.state.meta;
    const state = { preset: params.preset || "month" };
    if (params.from || params.to) { state.preset = "custom"; state.from = params.from; state.to = params.to; } else { const [f, to] = QT.preset(state.preset); state.from = f; state.to = to; }
    const newKind = { receipts: "customer_receipt", payments: "supplier_payment", refunds: "customer_refund", expenses: "expense", income: "other_income", transfers: "transfer", loans: "loan_received", all: "customer_receipt" }[t[0]];
    view.innerHTML = h`<div class="page-head"><div><h1>${t[1]}</h1><div class="sub">Open any entry to edit or delete it — balances update everywhere and the audit trail keeps every change</div></div>
      <div class="actions">${t[0] === "refunds" ? QT.raw('<button class="btn" data-new2>' + icon("arrowIn") + "Supplier refund received</button>") : ""}<button class="btn primary" data-new>${QT.raw(icon("plus"))}${t[0] === "refunds" ? "Refund to customer" : t[0] === "loans" ? "Loan received" : "New " + KIND[newKind].title.toLowerCase()}</button></div></div>
      <div class="tabs">${QT.raw(TABS.map((x) => '<button data-tab="' + x[0] + '" class="' + (x[0] === t[0] ? "on" : "") + '">' + esc(x[1]) + "</button>").join(""))}</div>
      <div data-sum class="kpis" style="margin-bottom:14px"></div>
      <div class="card"><div class="filters"><span data-range></span>
        <select class="inp" name="account_id">${QT.options(meta.accounts.map((a) => [a.id, QT.accLabel(a)]), params.account_id || "", "All accounts")}</select>
        ${["all", "payments", "refunds", "expenses"].includes(t[0]) ? QT.raw('<select class="inp" name="supplier_id">' + String(QT.options(meta.suppliers.map((x) => [x.id, x.name]), params.supplier_id || "", "All suppliers")) + "</select>") : ""}
        ${t[0] === "all" ? QT.raw('<select class="inp" name="kind">' + String(QT.options(Object.entries(meta.kinds), params.kind || "", "All types")) + "</select>") : ""}
        <select class="inp" name="method">${QT.options(Object.entries(QT.labels.method), params.method || "", "Any method")}</select>
        ${t[0] === "expenses" ? QT.raw('<select class="inp" name="category">' + String(QT.options(meta.expense_categories.map((c) => c.name), params.category || "", "All categories")) + '</select><select class="inp" name="direct"><option value="">Operating + direct</option><option value="0">Operating only</option></select>') : ""}
        <input class="inp w-search" name="q" placeholder="Search no., reference, party" value="${params.q || ""}">
        <label class="check small" data-skip><input type="checkbox" data-show-deleted> Show deleted</label>
        <span class="spacer"></span><button class="btn sm" data-csv>${QT.raw(icon("download"))}CSV</button></div><div data-table></div></div>`.html;
    $$("[data-tab]", view).forEach((b) => { b.onclick = () => QT.go("#/finance/" + b.dataset.tab); });
    $("[data-new]", view).onclick = () => (t[0] === "loans" ? QT.loanReceiveForm(null, () => load()) : QT.txnForm({ kind: newKind }, () => load()));
    if ($("[data-new2]", view)) $("[data-new2]", view).onclick = () => QT.txnForm({ kind: "supplier_receipt" }, () => load());
    $("[data-range]", view).appendChild(QT.rangeControl(state, () => load()));
    const cols = [
      { label: "Date", key: "txn_date", type: "date" },
      { label: "No.", key: "txn_no", render: (r) => h`<span class="cell-main">${r.txn_no}</span>${r.sign < 0 ? QT.raw('<div class="cell-sub">reversal</div>') : r.reversed_by ? QT.raw('<div class="cell-sub">reversed</div>') : ""}` },
    ];
    if (t[0] === "all" || t[0] === "refunds" || t[0] === "loans") cols.push({ label: "Type", key: "kind_label" });
    if (t[0] === "loans") cols.push({ label: "Lender", key: "debt_name", render: (r) => h`<div class="cell-main">${r.debt_name}</div><div class="cell-sub">${r.debt_no}</div>` });
    if (t[0] === "expenses" || t[0] === "income") cols.push({ label: "Category", key: "category", render: (r) => h`${r.category}${r.is_direct ? QT.raw('<div class="cell-sub">direct · ' + esc(r.booking_ref) + "</div>") : ""}` });
    if (t[0] !== "transfers" && t[0] !== "income" && t[0] !== "loans") cols.push({ label: t[0] === "expenses" ? "Paid to" : "Party", key: "party", sortKey: (r) => r.customer_name || r.supplier_name || r.payee, render: (r) => h`<div class="cell-main">${r.customer_name || r.supplier_name || r.payee || ""}</div><div class="cell-sub">${r.allocations.map((a) => a.ref).join(", ")}</div>` });
    if (t[0] === "transfers") cols.push({ label: "To account", key: "to_account_name" });
    cols.push({ label: t[0] === "transfers" ? "From account" : "Account", key: "account_name" }, { label: "Method", key: "method", render: (r) => esc(QT.labels.method[r.method] || r.method) },
      { label: "Reference", key: "reference" }, { label: "Amount", key: "amount", type: "money_raw", render: (r) => '<span class="' + (r.sign < 0 ? "neg" : "") + '">' + QT.money(r.sign * r.amount, r.currency) + "</span>" },
      { label: QT.base() + " value", key: "signed_base", type: "money" });
    if (["receipts", "payments"].includes(t[0])) cols.push({ label: t[0] === "receipts" ? "Advance" : "Unallocated", key: "unallocated", type: "money", render: (r) => (r.unallocated && !r.reversed_by && r.sign > 0 ? QT.money(r.unallocated) : "") });
    const cfg = { rows: [], columns: cols, emptyTitle: "No transactions in this period", empty: "Change the dates or record a new entry.", emptyIcon: "receipt",
      rowClass: (r) => (r.reversed_by ? "reversed" : r.sign < 0 ? "reversal" : ""), onRow: (r) => QT.txnDetail(r.id, () => load()) };
    const draw = QT.renderTable($("[data-table]", view), cfg);
    function filters() {
      const f = QT.formData($(".filters", view));
      return { kind: f.kind || t[2], from: state.from, to: state.to, account_id: f.account_id, supplier_id: f.supplier_id, method: f.method, category: f.category, direct: f.direct, q: f.q };
    }
    async function load() {
      const p = filters();
      const all = await QT.get("/api/transactions" + QT.qs(p));
      cfg.rows = $("[data-show-deleted]", view).checked ? all : all.filter((r) => !r.reversed_by && r.sign > 0);
      // loans tab: net financing = loan money received - principal repaid
      cfg.totals = { signed_base: cfg.rows.reduce((s, r) => s + (t[0] === "loans" && r.kind === "loan_repayment" ? -r.signed_base : r.signed_base), 0) };
      draw();
      const sum = $("[data-sum]", view);
      const live = cfg.rows.filter((r) => !r.reversed_by && r.sign > 0);
      let extra = "";
      if (t[0] === "expenses") {
        const byCat = {};
        cfg.rows.forEach((r) => { byCat[r.category] = (byCat[r.category] || 0) + r.signed_base; });
        extra = Object.entries(byCat).sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, v]) => h`<div class="kpi"><div class="k-top"><span class="k-ico">${QT.raw(icon("expense"))}</span>${k}</div><div class="k-val">${QT.money(v)}</div></div>`.html).join("");
      }
      sum.innerHTML = h`<div class="kpi hero"><div class="k-top"><span class="k-ico">${QT.raw(icon(KIND[newKind].icon))}</span>Total (${QT.base()})</div><div class="k-val">${QT.money(cfg.totals.signed_base)}</div><div class="k-sub">${live.length} entries · net of reversals</div></div>`.html + extra;
    }
    $("[data-show-deleted]", view).addEventListener("change", () => load());
    $$(".filters select, .filters [name=q]", view).forEach((i) => i.addEventListener(i.name === "q" ? "input" : "change", QT.debounce(load, 250)));
    $("[data-csv]", view).onclick = () => {
      const rows = cfg.rows;
      const head = ["Date", "No.", "Type", "Party", "Category", "Account", "Method", "Reference", "Currency", "Amount", "Rate", QT.base() + " value", "Notes"];
      const q = (v) => { v = v === null || v === undefined ? "" : String(v); if (/^[=+\-@]/.test(v)) v = "'" + v; return '"' + v.replace(/"/g, '""') + '"'; };
      const lines = [head.map(q).join(",")].concat(rows.map((r) => [r.txn_date, r.txn_no, r.kind_label + (r.sign < 0 ? " (reversal)" : ""), r.customer_name || r.supplier_name || r.payee || "", r.category, r.account_name, r.method, r.reference, r.currency, QT.plain(r.sign * r.amount), r.exchange_rate, QT.plain(r.signed_base), r.notes].map(q).join(",")));
      const blob = new Blob(["﻿" + lines.join("\n")], { type: "text/csv" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = t[0] + "-" + (state.from || "all") + "-" + (state.to || QT.today()) + ".csv";
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 3000);
    };
    await load();
  };

  // ---------------------------------------------------------------- accounts
  QT.accountForm = function (a, done) {
    a = a || {};
    const type = a.account_type || a.kind || "bank";
    const body = el(h`<form class="form-grid" onsubmit="return false">
      ${QT.field({ name: "name", label: "Account name", span: 7, value: a.name, req: true, placeholder: "e.g. HDFC Current A/c" })}
      ${QT.field({ name: "account_type", label: "Account type", type: "select", span: 5, opts: QT.options(Object.entries(QT.labels.accType), type) })}
      <div class="f s5" data-bankish><label>Bank / provider</label><input class="inp" name="bank_name" value="${a.bank_name || ""}" placeholder="e.g. HDFC Bank, Google Pay"></div>
      <div class="f s3" data-bankish><label>Last 4 digits</label><input class="inp" name="account_last4" inputmode="numeric" autocomplete="off" value="${a.account_last4 || ""}" placeholder="1234"><span class="hint">Only the last 4 digits are stored</span></div>
      ${QT.field({ name: "currency", label: "Currency", type: "select", span: 4, opts: QT.options(QT.state.meta.currencies.filter((c) => c.active).map((c) => [c.code, c.code]), a.currency || QT.base()), attrs: a.txn_count ? "disabled" : "", hint: a.txn_count ? "Locked: transactions exist" : "" })}
      <div class="f s6"><label>Opening balance</label><input class="inp money" inputmode="decimal" name="opening_balance" value="${a.id ? QT.plain(a.opening_balance) : ""}" placeholder="0.00"><span class="hint" data-ob-hint></span></div>
      ${QT.field({ name: "opening_date", label: "Opening balance date", type: "date", span: 6, value: a.opening_date })}
      ${QT.field({ name: "details", label: "Notes (branch, IFSC, UPI ID…)", span: 12, value: a.details, placeholder: "Do not enter full account numbers or passwords" })}
      ${a.id ? QT.field({ name: "active", label: "Active — show this account in payment forms", type: "checkbox", span: 12, value: !!a.active }) : ""}
    </form>`.html);
    const typeSel = $("[name=account_type]", body);
    const syncType = () => {
      const t = typeSel.value;
      $$("[data-bankish]", body).forEach((x) => x.classList.toggle("hidden", t === "cash"));
      $("[data-ob-hint]", body).textContent = t === "credit_card" ? "Amount owed on the card as a negative number, e.g. -15000" : "Balance on the opening date. Negative for an overdraft";
    };
    typeSel.addEventListener("change", syncType);
    syncType();
    const l4 = $("[name=account_last4]", body);
    l4.addEventListener("input", () => { l4.value = l4.value.replace(/\D/g, "").slice(-4); });
    QT.modal({ title: a.id ? "Edit account" : "New cash / bank account", body, actions: [{ label: "Cancel" }, { label: "Save account", cls: "primary", icon: "check", onClick: async () => {
      const d = QT.formData(body);
      if (a.txn_count) d.currency = a.currency;
      if (!a.id) d.active = true;
      if (d.account_type === "cash") { d.bank_name = ""; d.account_last4 = ""; }
      let id = a.id;
      try { if (a.id) await QT.put("/api/accounts/" + a.id, d); else id = (await QT.post("/api/accounts", d)).id; }
      catch (e) {
        if (e.code === "confirm_opening_change") {
          const ok = await QT.confirm({ title: "Change opening balance?", message: e.message, danger: true, ok: "Change opening balance", typeText: "CHANGE" });
          if (!ok) return false;
          d.confirm_opening_change = true;
          await QT.put("/api/accounts/" + a.id, d);
        } else throw e;
      }
      await QT.loadMeta();
      QT.toast("Account " + d.name + " saved");
      done ? done(id) : QT.reload();
    } }] });
  };

  QT.pages.accounts = async function (view) {
    const accs = await QT.get("/api/accounts");
    const totals = {};
    accs.filter((a) => a.active).forEach((a) => { totals[a.currency] = (totals[a.currency] || 0) + a.balance; });
    const card = (a) => h`<div class="card ${a.active ? "" : "inactive"}"><div class="acc-card">
        <div class="row"><div class="li" style="padding:0;border:0;flex:1"><div class="ic ${a.kind === "cash" ? "green" : a.account_type === "credit_card" ? "warn" : "cyan"}">${QT.raw(icon(QT.accIcon(a)))}</div>
          <div class="grow"><div class="t1">${a.name}</div><div class="t2">${QT.labels.accType[a.account_type || a.kind] || QT.labels.acc[a.kind]}${a.bank_name ? " · " + a.bank_name : ""}${a.account_last4 ? " · ••" + a.account_last4 : ""} · ${a.currency}</div></div></div>
          ${a.active ? "" : QT.raw('<span class="badge b-gray">Inactive</span>')}</div>
        <div class="bal ${a.balance < 0 ? "neg" : ""}">${QT.money(a.balance, a.currency)}</div>
        ${a.account_type === "credit_card" ? QT.raw('<div class="small muted">' + (a.balance < 0 ? "Amount owed on card" : "Card in credit") + "</div>") : ""}
        <div class="small muted">Opening ${QT.money(a.opening_balance, a.currency)} · in ${QT.money(a.inflow, a.currency)} · out ${QT.money(a.outflow, a.currency)}</div>
        <div class="small muted">Reconciled balance ${QT.money(a.reconciled_balance, a.currency)} · ${a.txn_count} entr${a.txn_count === 1 ? "y" : "ies"}</div>
        <div class="row" style="margin-top:8px;flex-wrap:wrap"><a class="btn sm" href="#/reports/cashbook?account_id=${a.id}&preset=all">${QT.raw(icon("book"))}Ledger</a><a class="btn sm" href="#/accounts/${a.id}/reconcile">${QT.raw(icon("check"))}Reconcile</a><button class="btn sm ghost" data-edit="${a.id}">${QT.raw(icon("edit"))}Edit</button>
          <button class="btn sm ghost" data-active="${a.id}" data-on="${a.active ? 0 : 1}">${a.active ? "Deactivate" : "Activate"}</button></div>
      </div></div>`.html;
    view.innerHTML = h`<div class="page-head"><div><h1>Cash & bank accounts</h1><div class="sub">Your cash, bank, UPI and card accounts. Balances are calculated from recorded transactions, so a payment can never be counted twice. Bank feeds are not connected — reconcile against statements.</div></div>
      <div class="actions"><button class="btn" data-loan>${QT.raw(icon("coins"))}Loan received</button><button class="btn" data-transfer>${QT.raw(icon("swap"))}Transfer</button><button class="btn primary" data-new>${QT.raw(icon("plus"))}New account</button></div></div>
      <div class="kpis" style="margin-bottom:16px">${QT.raw(Object.entries(totals).map(([cur, v], i) => '<div class="kpi ' + (i === 0 ? "hero" : "") + '"><div class="k-top"><span class="k-ico">' + icon("wallet") + "</span>Total " + esc(cur) + " (active accounts)</div><div class=\"k-val\">" + QT.money(v, cur) + "</div></div>").join(""))}</div>
      <div class="grid g3">${QT.raw(accs.filter((a) => a.active).map(card).join(""))}</div>
      ${accs.some((a) => !a.active) ? QT.raw('<h3 class="muted" style="margin:22px 0 10px;font-size:14px">Inactive accounts</h3><div class="grid g3">' + accs.filter((a) => !a.active).map(card).join("") + "</div>") : ""}`.html;
    $("[data-new]", view).onclick = () => QT.accountForm();
    $("[data-transfer]", view).onclick = () => QT.txnForm({ kind: "transfer" });
    $("[data-loan]", view).onclick = () => QT.loanReceiveForm(null, () => QT.reload());
    $$("[data-edit]", view).forEach((b) => { b.onclick = () => QT.accountForm(accs.find((a) => String(a.id) === b.dataset.edit)); });
    $$("[data-active]", view).forEach((b) => { b.onclick = async () => {
      const a = accs.find((x) => String(x.id) === b.dataset.active);
      const on = b.dataset.on === "1";
      if (!on && !(await QT.confirm({ title: "Deactivate " + a.name + "?", message: "It will no longer appear in payment forms. Its transactions and balance are kept, and you can activate it again at any time.", ok: "Deactivate" }))) return;
      try { await QT.post("/api/accounts/" + a.id + "/active", { active: on }); await QT.loadMeta(); QT.toast(a.name + (on ? " activated" : " deactivated")); QT.reload(); } catch (e) { QT.fail(e); }
    }; });
  };

  QT.pages.reconcile = async function (view, params, id) {
    const asOf = params.as_of || QT.today();
    const d = await QT.get("/api/accounts/" + id + "/reconcile" + QT.qs({ as_of: asOf }));
    const a = d.account, cur = a.currency;
    view.innerHTML = h`<div class="page-head"><div><div class="crumbs"><a href="#/accounts">Cash & bank</a> / ${a.name}</div><h1>Reconcile ${a.name}</h1>
      <div class="sub">Tick each entry that appears on your bank statement, then enter the statement's closing balance.</div></div></div>
      <div class="grid g-side"><div class="card"><div class="filters"><span class="lbl">Show up to</span><input type="date" class="inp" data-asof value="${asOf}"><span class="spacer"></span>
        <button class="btn sm" data-all>Tick all</button><button class="btn sm ghost" data-none>Untick all</button></div><div data-list></div></div>
      <div><div class="card sum-card"><div class="card-h"><h3>Statement</h3></div><div class="card-b stack" style="gap:12px">
        ${QT.field({ name: "statement_date", label: "Statement date", type: "date", span: 12, value: asOf })}
        ${QT.field({ name: "statement_balance", label: "Statement closing balance (" + cur + ")", type: "money", span: 12 })}
        <div data-calc></div>
        <button class="btn primary" data-save>${QT.raw(icon("check"))}Save reconciliation</button></div></div>
        <div class="card" style="margin-top:16px"><div class="card-h"><h3>Previous reconciliations</h3></div><div class="card-b flush">${QT.raw(d.history.length ? d.history.map((r) => '<div class="li"><div class="grow"><div class="t1">' + esc(QT.date(r.statement_date)) + '</div><div class="t2">statement ' + QT.money(r.statement_balance, cur) + "</div></div>" + (r.difference ? '<span class="badge b-red">Diff ' + QT.money(r.difference, cur) + "</span>" : '<span class="badge b-green">Balanced</span>') + "</div>").join("") : '<div class="empty-state small">None yet</div>')}</div></div></div></div>`.html;
    const list = $("[data-list]", view);
    list.innerHTML = d.items.length ? String(QT.table({ sortable: false, rows: d.items, rowClass: (r) => (r.reversed_by ? "reversed" : ""), columns: [
      { label: "", render: (r) => '<input type="checkbox" class="check" data-c="' + r.id + '" data-eff="' + r.effect + '"' + (r.reconciled ? " checked" : "") + ">" },
      { label: "Date", key: "txn_date", type: "date" }, { label: "No.", key: "txn_no" }, { label: "Type", key: "kind_label" },
      { label: "Party / ref", render: (r) => h`${r.customer_name || r.supplier_name || r.payee || r.category}<div class="cell-sub">${r.reference || ""}</div>` },
      { label: "In", type: "money", render: (r) => (r.effect > 0 ? QT.money(r.effect, cur) : "") },
      { label: "Out", type: "money", render: (r) => (r.effect < 0 ? QT.money(-r.effect, cur) : "") },
    ] })) : '<div class="empty-state">No transactions for this account yet.</div>';
    const calc = () => {
      let cleared = a.opening_balance;
      $$("[data-c]", list).forEach((c) => { if (c.checked) cleared += +c.dataset.eff; });
      const sb = QT.parseMinor($("[name=statement_balance]", view).value);
      const diff = isNaN(sb) ? 0 : sb - cleared;
      $("[data-calc]", view).innerHTML = h`<div class="sum-row"><span class="muted">Opening balance</span><span class="v">${QT.money(a.opening_balance, cur)}</span></div>
        <div class="sum-row"><span class="muted">Cleared balance</span><span class="v">${QT.money(cleared, cur)}</span></div>
        <div class="sum-row"><span class="muted">Book balance</span><span class="v">${QT.money(d.balance, cur)}</span></div>
        <div class="sum-row big"><span>Difference</span><span class="v ${diff ? "neg" : "pos"}">${QT.money(diff, cur)}</span></div>
        ${diff && $("[name=statement_balance]", view).value ? QT.raw('<div class="alert warn small">' + icon("alert") + "<div>Find missing entries (bank charges, interest, unrecorded receipts) and record them, or untick entries not yet on the statement.</div></div>") : ""}`.html;
    };
    list.addEventListener("change", calc);
    $("[name=statement_balance]", view).addEventListener("input", calc);
    $("[data-all]", view).onclick = () => { $$("[data-c]", list).forEach((c) => { c.checked = true; }); calc(); };
    $("[data-none]", view).onclick = () => { $$("[data-c]", list).forEach((c) => { c.checked = false; }); calc(); };
    $("[data-asof]", view).onchange = (e) => QT.go("#/accounts/" + id + "/reconcile?as_of=" + e.target.value);
    $("[data-save]", view).onclick = async () => {
      try {
        const r = await QT.post("/api/accounts/" + id + "/reconcile", {
          statement_date: $("[name=statement_date]", view).value, statement_balance: $("[name=statement_balance]", view).value,
          cleared_ids: $$("[data-c]", list).filter((c) => c.checked).map((c) => +c.dataset.c),
        });
        QT.toast(r.difference ? "Saved with a difference of " + QT.money(r.difference, cur) : "Reconciled — balances match");
        QT.reload();
      } catch (e) { QT.showErr(view, e); }
    };
    calc();
  };

  // ---------------------------------------------------------------- invoices
  QT.pages.invoices = async function (view, params) {
    view.innerHTML = h`<div class="page-head"><div><h1>Invoices & credit notes</h1><div class="sub">Issued documents are numbered by Indian financial year and cannot be edited — cancel and reissue instead</div></div></div>
      <div class="card"><div class="filters"><select class="inp" name="doc_type"><option value="">Invoices & credit notes</option><option value="invoice">Invoices</option><option value="credit_note">Credit notes</option></select>
        <input type="date" class="inp" name="from"><input type="date" class="inp" name="to"><input class="inp w-search" name="q" placeholder="Number, booking, PNR, customer"><span class="spacer"></span><span class="muted small" data-count></span></div>
      <div data-table></div></div>`.html;
    const cfg = { rows: [], emptyTitle: "No invoices yet", empty: "Open a confirmed booking and choose Create invoice.", emptyIcon: "invoice", onRow: (r) => QT.invoiceView(r.id),
      rowClass: (r) => (r.status === "cancelled" ? "reversed" : ""), columns: [
        { label: "Date", key: "issue_date", type: "date" }, { label: "Number", key: "number", render: (r) => h`<span class="cell-main">${r.number}</span>` },
        { label: "Type", key: "doc_type", render: (r) => QT.badge(r.doc_type === "invoice" ? "Invoice" : "Credit note", r.doc_type === "invoice" ? "b-blue" : "b-warn") },
        { label: "Customer", key: "customer_name" }, { label: "Booking", key: "booking_ref" }, { label: "PNR", key: "pnr", render: (r) => (r.pnr ? h`<span class="pnr">${r.pnr}</span>` : "") },
        { label: "Status", key: "status", render: (r) => QT.badge(r.status === "issued" ? "Issued" : "Cancelled", r.status === "issued" ? "b-green" : "b-red") },
        { label: "Taxable", key: "subtotal", type: "money_raw" }, { label: "Tax", key: "tax_total", type: "money_raw" }, { label: "Total", key: "total", type: "money_raw" },
      ] };
    const draw = QT.renderTable($("[data-table]", view), cfg);
    async function load() {
      cfg.rows = await QT.get("/api/invoices" + QT.qs(QT.formData($(".filters", view))));
      $("[data-count]", view).textContent = cfg.rows.length + " documents";
      draw();
    }
    $$(".filters .inp", view).forEach((i) => i.addEventListener(i.name === "q" ? "input" : "change", QT.debounce(load, 250)));
    await load();
    if (params.open) QT.invoiceView(+params.open);
  };
})();
