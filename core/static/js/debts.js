/* AirZelo - debts & loans: loan money received and repaid through the cash / bank ledger */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  const TYPES = { bank: "Bank loan", nbfc: "NBFC / finance company", person: "Personal loan (person)", supplier: "Supplier / portal credit", credit_card: "Credit card", other: "Other" };

  function kpi(label, value, ic, cls, sub) {
    return h`<div class="kpi ${cls || ""}"><div class="k-top"><span class="k-ico">${QT.raw(icon(ic))}</span>${label}</div>
      <div class="k-val">${value}</div>${sub ? QT.raw('<div class="k-sub">' + String(sub) + "</div>") : ""}</div>`;
  }
  const statusBadge = (d) => (d.status === "closed" ? QT.badge("Closed", "b-gray") : d.overdue ? QT.badge("Overdue", "b-red") : QT.badge("Open", "b-blue"));
  const rate = (d) => (d.interest_rate && d.interest_rate !== "0" ? d.interest_rate + "% a year" : "");
  const activeAccounts = () => QT.state.meta.accounts.filter((a) => a.active);
  const accountField = (name, label, pref) => {
    const accs = activeAccounts();
    const last = accs.find((a) => String(a.id) === QT.pref.get(pref, "")) || accs[0];
    return QT.field({ name, label, type: "select", span: 6, opts: QT.options(accs.map((a) => [a.id, QT.accLabel(a)]), last ? last.id : ""), req: true });
  };
  // a likely duplicate needs the user's confirmation, as everywhere else in the app
  async function postConfirm(path, data) {
    try { return await QT.post(path, data); } catch (e) {
      if (e.code !== "possible_duplicate") throw e;
      if (!(await QT.confirm({ title: "Possible duplicate", message: e.message, ok: "Record anyway" }))) return null;
      return QT.post(path, Object.assign({}, data, { force_duplicate: true }));
    }
  }

  // ---------------------------------------------------------------- list + dashboard
  QT.pages.debts = async function (view) {
    const state = JSON.parse(QT.pref.get("debt_range", "null") || "null") || { preset: "fy" };
    if (state.preset !== "custom") { const [f, t] = QT.preset(state.preset); state.from = f; state.to = t; }
    view.innerHTML = h`<div class="page-head"><div><h1>Debts & loans</h1>
        <div class="sub">What the company owes, what has been repaid and what is left. Loan money and principal repayments go through your Cash & bank accounts as financing — never as sales, income or expenses.</div></div>
      <div class="actions"><button class="btn" data-old>${QT.raw(icon("plus"))}Existing debt</button><button class="btn primary" data-receive>${QT.raw(icon("coins"))}Loan received</button></div></div>
      <div data-body>${QT.loading()}</div>`.html;
    $("[data-receive]", view).onclick = () => QT.loanReceiveForm(null, (r) => QT.go("#/debts/" + r.debt_id));
    $("[data-old]", view).onclick = () => QT.debtForm(null, (id) => QT.go("#/debts/" + id));
    const body = $("[data-body]", view);

    async function load() {
      const [s, list] = await Promise.all([QT.get("/api/debts/summary" + QT.qs({ from: state.from, to: state.to })), QT.get("/api/debts")]);
      const t = s.totals, c = s.combined;
      const periodLabel = state.preset === "custom" ? QT.date(state.from) + " – " + QT.date(state.to) : { today: "Today", week: "This week", month: "This month", year: "This year", fy: "This financial year" }[state.preset];
      body.innerHTML = h`
        <div class="kpis" style="margin-bottom:16px">
          ${kpi("Total debt outstanding", QT.money(t.total_outstanding), "coins", "hero", s.open_count + " open debt" + (s.open_count === 1 ? "" : "s") + (s.overdue_count ? " · " + s.overdue_count + " overdue" : ""))}
          ${kpi("Principal outstanding", QT.money(t.principal_outstanding), "bank", t.principal_outstanding ? "c-red" : "", "of " + QT.money(t.borrowed) + " borrowed")}
          ${kpi("Interest due", QT.money(t.interest_due), "percent", t.interest_due ? "c-warn" : "", "charged but not yet paid")}
          ${kpi("Repaid so far", QT.money(t.total_paid), "arrowOut", "c-green", QT.money(t.principal_repaid) + " principal · " + QT.money(t.interest_paid) + " interest")}
        </div>
        <div class="grid g-side" style="margin-bottom:16px">
          <div class="card"><div class="card-h"><h3>Debts</h3><div class="actions"><select class="inp inp-sm" data-status style="width:auto"><option value="open">Open</option><option value="closed">Closed</option><option value="">All</option></select></div></div><div data-table></div></div>
          <div class="stack" style="gap:16px">
            <div class="card sum-card"><div class="card-h"><h3>Combined view</h3><div class="sub">${periodLabel}</div></div><div class="card-b stack" style="gap:8px">
              <div class="sum-row"><span class="muted">Business net profit</span><span class="v ${QT.moneyCls(c.business_net_profit)}">${QT.money(c.business_net_profit)}</span></div>
              <div class="small muted">Same figure as the Dashboard. It already includes ${QT.money(c.loan_interest_in_expenses)} loan interest paid (an expense). Loan money and principal are not part of profit.</div>
              <div class="sum-row"><span class="muted">Loan money received</span><span class="v">${QT.money(c.loan_received)}</span></div>
              <div class="sum-row"><span class="muted">Loan principal repaid</span><span class="v">${QT.money(c.principal_repaid)}</span></div>
              <div class="sum-row"><span class="muted">Cash & bank balances</span><span class="v ${QT.moneyCls(c.cash_and_bank)}">${QT.money(c.cash_and_bank)}</span></div>
              <div class="sum-row"><span class="muted">− Debt outstanding</span><span class="v">${QT.money(c.debt_outstanding)}</span></div>
              <div class="sum-row big"><span>Balances less debt</span><span class="v ${QT.moneyCls(c.net_position)}">${QT.money(c.net_position)}</span></div>
              <div class="small muted">Cash & bank is the total of your active ${esc(s.base_currency)} accounts as recorded in this app today (not a live bank feed). Customer and supplier balances are not included.</div>
            </div></div>
            <div class="card"><div class="card-h"><h3>Coming due</h3></div><div class="card-b flush">${QT.raw(s.upcoming.length ? s.upcoming.map((d) => '<a class="li" href="#/debts/' + d.id + '" style="color:inherit;text-decoration:none"><div class="grow"><div class="t1">' + esc(d.creditor) + '</div><div class="t2">' + esc(QT.date(d.due_date)) + (d.overdue ? " · overdue" : "") + '</div></div><span class="' + (d.overdue ? "neg" : "") + '">' + QT.money(d.total_outstanding) + "</span></a>").join("") : '<div class="empty-state small">No due dates set</div>')}</div></div>
          </div>
        </div>`.html;
      const sel = $("[data-status]", body);
      sel.value = state.status === undefined ? "open" : state.status;
      const cfg = { rows: [], emptyTitle: "No debts recorded", empty: "Use Loan received when loan money comes into an account, or Existing debt for money already owed.", emptyIcon: "coins",
        onRow: (r) => QT.go("#/debts/" + r.id), columns: [
          { label: "Lender", key: "creditor", render: (r) => h`<span class="cell-main">${r.creditor}</span><div class="cell-sub">${r.debt_no} · ${TYPES[r.creditor_type] || r.creditor_type}${r.reference ? " · " + r.reference : ""}</div>` },
          { label: "Borrowed", key: "borrowed", type: "money" },
          { label: "Repaid", key: "total_paid", type: "money" },
          { label: "Outstanding", key: "total_outstanding", type: "money" },
          { label: "Due", key: "due_date", type: "date" },
          { label: "Status", key: "status", render: (r) => statusBadge(r) },
        ] };
      const draw = QT.renderTable($("[data-table]", body), cfg);
      const filter = () => {
        state.status = sel.value;
        cfg.rows = list.filter((d) => !sel.value || d.status === sel.value);
        draw();
      };
      sel.onchange = filter;
      filter();
    }
    $(".page-head .actions", view).prepend(QT.rangeControl(state, () => { QT.pref.set("debt_range", JSON.stringify({ preset: state.preset, from: state.from, to: state.to })); load(); }));
    await load();
  };

  // ---------------------------------------------------------------- one debt
  QT.pages.debt = async function (view, params, id) {
    const d = await QT.get("/api/debts/" + id);
    const showRev = params.show === "all";
    const txns = d.transactions.filter((t) => showRev || (t.sign > 0 && !t.reversed_by));
    const entries = d.entries.filter((e) => showRev || (e.sign > 0 && !e.reversed_by));
    view.innerHTML = h`<div class="page-head"><div><div class="crumbs"><a href="#/debts">Debts & loans</a> / ${d.debt_no}</div>
        <h1>${d.creditor} ${QT.raw(String(statusBadge(d)))}</h1>
        <div class="sub">${TYPES[d.creditor_type] || d.creditor_type}${d.reference ? " · " + d.reference : ""}${d.creditor_phone ? " · " + d.creditor_phone : ""} · since ${QT.date(d.start_date)}${d.due_date ? " · due " + QT.date(d.due_date) : ""}${rate(d) ? " · " + rate(d) : ""}</div></div>
      <div class="actions">
        ${d.status === "open" ? QT.raw('<button class="btn primary" data-pay>' + icon("arrowOut") + "Repay loan</button><button class=\"btn\" data-more>" + icon("more") + "More</button>") : QT.raw('<button class="btn" data-reopen>Reopen</button>')}
        <button class="btn ghost" data-edit>${QT.raw(icon("edit"))}Edit details</button></div></div>
      <div class="kpis" style="margin-bottom:16px">
        ${kpi("Total outstanding", QT.money(d.total_outstanding), "coins", "hero", d.interest_due ? "includes " + QT.money(d.interest_due) + " interest due" : "principal only")}
        ${kpi("Principal outstanding", QT.money(d.principal_outstanding), "bank", "", "of " + QT.money(d.borrowed) + " borrowed" + (d.opening_amount ? " (" + QT.money(d.opening_amount) + " owed before)" : ""))}
        ${kpi("Principal repaid", QT.money(d.principal_repaid), "arrowOut", "c-green", d.last_payment_date ? "last payment " + QT.date(d.last_payment_date) : "no repayments yet")}
        ${kpi("Interest paid", QT.money(d.interest_paid), "percent", "c-warn", "recorded as expense · " + QT.money(d.interest_charged) + " charged")}
      </div>
      ${d.interest_terms || d.notes ? QT.raw('<div class="card" style="margin-bottom:16px"><div class="card-b">' + (d.interest_terms ? "<div><b>Terms:</b> " + esc(d.interest_terms) + "</div>" : "") + (d.notes ? '<div style="white-space:pre-wrap;margin-top:6px">' + esc(d.notes) + "</div>" : "") + "</div></div>") : ""}
      <div class="card"><div class="card-h"><h3>Money in & out</h3><div class="sub">Loan received, principal repaid and interest paid — each is one entry in your Cash & bank ledger. Open a repayment to reverse it (principal and interest together).</div>
        <div class="actions"><label class="check small"><input type="checkbox" data-showrev ${showRev ? "checked" : ""}> Show deleted</label></div></div>
        <div class="card-b flush" data-txns>${QT.raw(txns.length ? txns.map((t) => QT.txnLi(t)).join("") : '<div class="empty-state small">No money recorded against this debt yet</div>')}</div></div>
      ${entries.length || d.interest_charged ? QT.raw('<div class="card" style="margin-top:16px"><div class="card-h"><h3>Interest charged (not yet paid)</h3><div class="sub">No money moved, so these are not in your accounts or profit</div></div><div data-entries></div></div>') : ""}`.html;
    QT.bindTxnLis($("[data-txns]", view), () => QT.reload());
    if ($("[data-entries]", view)) {
      QT.renderTable($("[data-entries]", view), { rows: entries, sortable: false, rowClass: (e) => (e.sign < 0 || e.reversed_by ? "reversed" : ""), columns: [
        { label: "Date", key: "entry_date", type: "date" }, { label: "No.", key: "entry_no" },
        { label: "Interest charged", type: "money", render: (e) => QT.money(e.sign * e.amount) },
        { label: "Notes", render: (e) => esc((e.sign < 0 ? "reverses " + (e.reference || "") + " " : e.reference ? e.reference + " " : "") + (e.notes || "")) },
        { label: "", render: (e) => (e.sign > 0 && !e.reversed_by ? '<button class="btn sm ghost" data-rev="' + e.id + '">Reverse</button>' : "") },
      ] });
    }
    $("[data-showrev]", view).onchange = (e) => QT.go("#/debts/" + id + (e.target.checked ? "?show=all" : ""));
    $("[data-edit]", view).onclick = () => QT.debtForm(d, () => QT.reload());
    $$("[data-rev]", view).forEach((b) => { b.onclick = async () => {
      const e = d.entries.find((x) => String(x.id) === b.dataset.rev);
      const reason = await QT.ask({ title: "Reverse " + e.entry_no + "?", message: "The entry stays in the history with a matching reversal.", label: "Reason", ok: "Reverse", danger: true });
      if (!reason) return;
      try { await QT.post("/api/debt-entries/" + e.id + "/reverse", { reason }); QT.toast(e.entry_no + " reversed"); QT.reload(); } catch (err) { QT.fail(err); }
    }; });
    const pay = $("[data-pay]", view);
    if (pay) pay.onclick = () => QT.loanRepayForm(d, () => QT.reload());
    const more = $("[data-more]", view);
    if (more) QT.dropdown(more, [
      { label: "More loan money received", icon: "coins", onClick: () => QT.loanReceiveForm(d, () => QT.reload()) },
      { label: "Interest charged (not paid yet)", icon: "percent", onClick: () => QT.interestChargeForm(d) },
      { label: "Close debt (fully repaid)", icon: "check", onClick: async () => {
        try { await QT.post("/api/debts/" + d.id + "/status", { status: "closed" }); QT.toast(d.creditor + " closed"); QT.reload(); } catch (err) { QT.fail(err); }
      } },
      "-",
      { label: "Delete debt", icon: "trash", onClick: async () => {
        if (!(await QT.confirm({ title: "Delete " + d.debt_no + "?", message: "Only a debt with no money or interest recorded against it can be deleted.", ok: "Delete", danger: true }))) return;
        try { await QT.del("/api/debts/" + d.id); QT.toast(d.debt_no + " deleted"); QT.go("#/debts"); } catch (err) { QT.fail(err); }
      } },
    ]);
    const reopen = $("[data-reopen]", view);
    if (reopen) reopen.onclick = async () => { try { await QT.post("/api/debts/" + d.id + "/status", { status: "open" }); QT.reload(); } catch (err) { QT.fail(err); } };
  };

  // ---------------------------------------------------------------- forms
  const lenderFields = (d) => h`
      ${QT.field({ name: "creditor", label: "Lender name", span: 8, value: d.creditor, req: true, placeholder: "e.g. Federal Bank, Mr. Rahman" })}
      ${QT.field({ name: "creditor_type", label: "Lender type", type: "select", span: 4, opts: QT.options(Object.entries(TYPES), d.creditor_type || "bank") })}
      ${QT.field({ name: "due_date", label: "Due date", type: "date", span: 4, value: d.due_date })}
      ${QT.field({ name: "interest_rate", label: "Interest rate (% a year)", span: 4, value: d.interest_rate && d.interest_rate !== "0" ? d.interest_rate : "", placeholder: "e.g. 12", attrs: 'inputmode="decimal"' })}
      ${QT.field({ name: "creditor_phone", label: "Lender phone", span: 4, value: d.creditor_phone })}
      ${QT.field({ name: "interest_terms", label: "Interest / EMI terms", span: 6, value: d.interest_terms, placeholder: "e.g. EMI ₹25,000 on the 5th, 24 months" })}
      ${QT.field({ name: "reference", label: "Loan / account reference", span: 6, value: d.reference })}
      ${QT.field({ name: "notes", label: "Notes", type: "textarea", span: 12, value: d.notes, rows: 2 })}`;

  // money already owed before it was recorded here (no money moves now), or edit a debt's details
  QT.debtForm = function (d, done) {
    d = d || {};
    const body = el(h`<form class="form-grid" onsubmit="return false">
      ${d.id ? "" : QT.raw('<div class="f s12"><div class="alert info small">' + icon("info") + "<div>For money the company <b>already owed</b> before you started recording it here. No account balance changes. If loan money is coming into an account now, use <b>Loan received</b> instead.</div></div></div>")}
      ${QT.field({ name: "opening_amount", label: "Amount owed when recorded (" + QT.base() + ")", type: "money", span: 6, value: d.id ? QT.plain(d.opening_amount) : "", req: !d.id, hint: d.id ? "Loan money received through your accounts is added on top of this" : "" })}
      ${QT.field({ name: "start_date", label: "Date borrowed", type: "date", span: 6, value: d.start_date || QT.today(), req: true })}
      ${lenderFields(d)}
    </form>`.html);
    const idem = QT.uuid();
    QT.modal({ title: d.id ? "Edit " + d.debt_no : "Existing debt", body, actions: [{ label: "Cancel" }, { label: "Save", cls: "primary", icon: "check", onClick: async () => {
      const data = QT.formData(body);
      const r = d.id ? await QT.put("/api/debts/" + d.id, data) : await QT.post("/api/debts", Object.assign(data, { idem_key: idem }));
      QT.toast(d.id ? "Debt updated" : "Debt recorded");
      done && done(r.id);
    } }] });
  };

  // loan money received into an account: one financing transaction, linked to a new or existing debt
  QT.loanReceiveForm = function (d, done) {
    if (!activeAccounts().length) { QT.toast("Create a cash or bank account first", "err"); return; }
    const body = el(h`<form class="form-grid" onsubmit="return false">
      ${QT.raw(d ? '<div class="f s12 small muted">Adding to ' + esc(d.creditor) + " · " + esc(d.debt_no) + " · principal outstanding " + QT.money(d.principal_outstanding) + "</div>" : "")}
      ${accountField("account_id", "Received into", "acc.loan_received")}
      ${QT.field({ name: "txn_date", label: "Date received", type: "date", span: 6, value: QT.today(), req: true })}
      ${QT.field({ name: "amount", label: "Amount received", type: "money", span: 6, req: true, hint: "In the account's currency" })}
      ${QT.field({ name: "method", label: "Method", type: "select", span: 6, opts: QT.options(Object.entries(QT.labels.method), "bank_transfer") })}
      ${QT.field({ name: "txn_reference", label: "Bank reference / UTR", span: 12 })}
      ${d ? "" : lenderFields({})}
      <div class="f s12"><div class="alert info small">${QT.raw(icon("info"))}<div>Recorded once: the account balance goes up and the same amount is added to the debt. It is financing — never counted as sales, income or profit.</div></div></div>
    </form>`.html);
    const idem = QT.uuid();
    QT.modal({ title: d ? "More loan money received" : "Loan received", body, actions: [{ label: "Cancel" }, { label: "Save", cls: "primary", icon: "check", onClick: async () => {
      const data = Object.assign(QT.formData(body), { idem_key: idem });
      if (d) data.debt_id = d.id;
      const r = await postConfirm("/api/debts/receive", data);
      if (!r) return false;
      QT.pref.set("acc.loan_received", data.account_id);
      QT.toast("Loan received recorded");
      done && done(r);
    } }] });
  };

  // repayment from an account: principal (financing, not an expense) + interest (an expense), posted together
  QT.loanRepayForm = function (d, done) {
    if (!activeAccounts().length) { QT.toast("Create a cash or bank account first", "err"); return; }
    const body = el(h`<form class="form-grid" onsubmit="return false">
      <div class="f s12 small muted">${d.creditor} · principal outstanding ${QT.money(d.principal_outstanding)}${d.interest_due ? " · interest due " + QT.money(d.interest_due) : ""}</div>
      ${accountField("account_id", "Paid from", "acc.loan_repayment")}
      ${QT.field({ name: "txn_date", label: "Date paid", type: "date", span: 6, value: QT.today(), req: true })}
      ${QT.field({ name: "principal", label: "Principal repaid", type: "money", span: 6 })}
      ${QT.field({ name: "interest", label: "Interest paid", type: "money", span: 6 })}
      ${QT.field({ name: "method", label: "Method", type: "select", span: 6, opts: QT.options(Object.entries(QT.labels.method), "bank_transfer") })}
      ${QT.field({ name: "reference", label: "Reference / UTR", span: 6 })}
      <div class="f s12" data-total></div>
      <div class="f s12"><div class="alert info small">${QT.raw(icon("info"))}<div>The principal lowers the debt and the account balance — it is not an expense. The interest is recorded as an expense (“Loan interest”), so it reduces profit like any other expense.</div></div></div>
    </form>`.html);
    const total = () => {
      const p = QT.parseMinor($("[name=principal]", body).value) || 0;
      const i = QT.parseMinor($("[name=interest]", body).value) || 0;
      $("[data-total]", body).innerHTML = h`<div class="sum-row big"><span>Total paid</span><span class="v">${QT.money(p + i)}</span></div>
        <div class="small muted">Principal outstanding after: ${QT.money(d.principal_outstanding - p)}</div>`.html;
    };
    body.addEventListener("input", total);
    total();
    const idem = QT.uuid();
    QT.modal({ title: "Repay loan · " + d.creditor, body, actions: [{ label: "Cancel" }, { label: "Save", cls: "primary", icon: "check", onClick: async () => {
      const data = Object.assign(QT.formData(body), { idem_key: idem });
      const r = await postConfirm("/api/debts/" + d.id + "/repay", data);
      if (!r) return false;
      QT.pref.set("acc.loan_repayment", data.account_id);
      QT.toast("Repayment recorded");
      done && done(r);
    } }] });
  };

  // undo a whole repayment: its principal and interest entries are reversed together (nothing is erased)
  QT.reverseRepayment = async function (txnId) {
    let g;
    try { g = await QT.get("/api/debts/repayments/" + txnId); } catch (e) { QT.fail(e); return false; }
    if (!g.live.length) { QT.toast("This repayment has already been reversed", "err"); return false; }
    const lines = g.live.map((p) => p.txn_no + " · " + (p.part === "principal" ? "principal" : "interest") + " " + QT.money(p.amount, p.currency) + " · " + p.account_name + " · " + QT.date(p.txn_date) + (p.reconciled ? " · reconciled" : "")).join("\n");
    const reason = await QT.ask({ title: "Reverse this repayment?", danger: true, ok: "Reverse repayment", label: "Reason (optional)", required: false, multiline: true,
      message: "Repayment to " + g.creditor + " (" + g.debt_no + "). These entries will be reversed together:\n" + lines + "\n\nThe account balance and the amount owed go back to what they were, and the interest is removed from expenses. The originals stay in the history marked as reversed. To fix a wrong amount or account, record the repayment again afterwards." });
    if (reason === null) return false;
    const send = (extra) => QT.post("/api/debts/repayments/" + txnId + "/reverse", Object.assign({ reason: reason || "Repayment reversed" }, extra || {}));
    try {
      await send();
    } catch (e) {
      if (e.code !== "confirm_reconciled") { QT.fail(e); return false; }
      if (!(await QT.confirm({ title: "Reconciled entry", message: e.message, ok: "Reverse anyway", danger: true }))) return false;
      try { await send({ confirm_reconciled: true }); } catch (e2) { QT.fail(e2); return false; }
    }
    QT.toast("Repayment reversed");
    return true;
  };

  QT.interestChargeForm = function (d) {
    const body = el(h`<form class="form-grid" onsubmit="return false">
      ${QT.field({ name: "entry_date", label: "Date", type: "date", span: 6, value: QT.today(), req: true })}
      ${QT.field({ name: "amount", label: "Interest charged", type: "money", span: 6, req: true })}
      ${QT.field({ name: "notes", label: "Notes", span: 12 })}
      <div class="f s12 small muted">Use this only when the lender adds interest that you have not paid yet. When you pay it, use Repay loan → Interest paid.</div>
    </form>`.html);
    const idem = QT.uuid();
    QT.modal({ title: "Interest charged · " + d.creditor, body, actions: [{ label: "Cancel" }, { label: "Save", cls: "primary", icon: "check", onClick: async () => {
      await QT.post("/api/debts/" + d.id + "/interest", Object.assign(QT.formData(body), { idem_key: idem }));
      QT.toast("Interest charged recorded");
      QT.reload();
    } }] });
  };
})();
