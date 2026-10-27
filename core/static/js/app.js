/* AirZelo - shell, auth and router */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  // ---------------------------------------------------------------- theme
  function applyTheme(t) {
    if (t === "dark" || t === "light") document.documentElement.setAttribute("data-theme", t);
    else document.documentElement.removeAttribute("data-theme");
  }
  applyTheme(QT.pref.get("theme", ""));
  QT.isDark = () => {
    const t = document.documentElement.getAttribute("data-theme");
    if (t) return t === "dark";
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  };

  // ---------------------------------------------------------------- routes
  const ROUTES = [
    [/^\/?$/, "dashboard"], [/^\/dashboard$/, "dashboard"],
    [/^\/pnr$/, "pnr"],
    [/^\/bookings$/, "bookings"], [/^\/bookings\/new$/, "bookingForm"], [/^\/bookings\/(\d+)$/, "booking"], [/^\/bookings\/(\d+)\/edit$/, "bookingForm"],
    [/^\/customers$/, "customers"], [/^\/customers\/(\d+)$/, "customer"],
    [/^\/suppliers$/, "suppliers"], [/^\/suppliers\/(\d+)$/, "supplier"],
    [/^\/finance\/([a-z_]+)$/, "finance"], [/^\/finance$/, "finance"],
    [/^\/accounts$/, "accounts"], [/^\/accounts\/(\d+)\/reconcile$/, "reconcile"],
    [/^\/invoices$/, "invoices"],
    [/^\/debts$/, "debts"], [/^\/debts\/(\d+)$/, "debt"],
    [/^\/reports$/, "reports"], [/^\/reports\/([a-z_]+)$/, "reports"],
    [/^\/settings$/, "settings"], [/^\/settings\/([a-z_]+)$/, "settings"],
    [/^\/audit$/, "audit"],
    [/^\/setup$/, "setup"],
  ];
  // [href, icon, label, permission] - items the signed-in role cannot use are not shown (the server refuses them anyway)
  const NAV = [
    ["Overview", [["#/dashboard", "dashboard", "Dashboard", "reports"], ["#/pnr", "search", "PNR search", "bookings.view"]]],
    ["Operations", [["#/bookings", "ticket", "Bookings", "bookings.view"], ["#/customers", "users", "Customers", "bookings.view"], ["#/suppliers", "building", "Suppliers", "bookings.view"]]],
    ["Money", [["#/finance/receipts", "arrowIn", "Receipts", "money.view"], ["#/finance/payments", "arrowOut", "Supplier payments", "money.view"], ["#/finance/refunds", "refund", "Refunds", "money.view"],
      ["#/finance/expenses", "expense", "Expenses", "money.view"], ["#/accounts", "bank", "Cash & bank", "money.view"], ["#/reports/cashbook", "book", "Cashbook", "reports"], ["#/invoices", "invoice", "Invoices", "money.view"], ["#/debts", "coins", "Debts & loans", "money.view"]]],
    ["Insights", [["#/reports", "chart", "Reports", "reports"], ["#/audit", "history", "Audit trail", "reports"], ["#/settings/users", "users", "Staff & roles", "users"], ["#/settings", "settings", "Settings", "signed_in"]]],
  ];
  // pages a role may open (the rest show "not available for your role")
  const PAGE_PERM = { dashboard: "reports", pnr: "bookings.view", bookings: "bookings.view", bookingForm: "bookings", booking: "bookings.view",
    customers: "bookings.view", customer: "bookings.view", suppliers: "bookings.view", supplier: "bookings.view", finance: "money.view",
    accounts: "money.view", reconcile: "money", invoices: "money.view", debts: "money.view", debt: "money.view", reports: "reports",
    audit: "reports", settings: "signed_in", setup: "settings" };

  function parseHash() {
    const raw = (location.hash || "#/").slice(1);
    const [path, query] = raw.split("?");
    const params = {};
    (query || "").split("&").filter(Boolean).forEach((kv) => {
      const [k, v] = kv.split("=");
      params[decodeURIComponent(k)] = decodeURIComponent(v || "");
    });
    return { path: path || "/", params };
  }
  QT.go = (hash) => { if (location.hash === hash) route(); else location.hash = hash; };
  QT.reload = () => route();

  let navToken = 0;
  async function route() {
    if (!QT.state.authed) return;
    const { path, params } = parseHash();
    // a new company workspace starts in the setup wizard until it is completed or skipped
    if (QT.state.meta && QT.state.meta.settings.setup_completed !== "1" && path !== "/setup") { location.hash = "#/setup"; return; }
    let page = null;
    let args = [];
    for (const [rx, name] of ROUTES) {
      const m = rx.exec(path);
      if (m) { page = name; args = m.slice(1); break; }
    }
    if (page === "dashboard" && !QT.can("reports")) { location.hash = "#/bookings"; return; }   // booking staff start at bookings
    $$(".dd-menu").forEach((m) => m.remove());
    $$(".chart-tip").forEach((m) => m.remove());
    $(".shell") && $(".shell").classList.remove("nav-open");
    // highlight nav
    $$(".nav a").forEach((a) => {
      const href = a.getAttribute("href").slice(1);
      const on = path === href || (href !== "/dashboard" && path.startsWith(href + "/")) || (href === "/dashboard" && (path === "/" || path === ""));
      a.classList.toggle("active", on && !(href === "/reports" && path === "/reports/cashbook"));
    });
    const content = $("#content");
    if (!page || !QT.pages[page]) {
      content.innerHTML = '<div class="empty-state">' + icon("alert") + "<h4>Page not found</h4><a href=\"#/dashboard\">Go to dashboard</a></div>";
      return;
    }
    if (PAGE_PERM[page] && !QT.can(PAGE_PERM[page])) {
      const me = QT.me();
      content.innerHTML = h`<div class="empty-state">${QT.raw(icon("lock"))}<h4>Not available for your role</h4><div>${me.display_name || me.username} is signed in as ${QT.state.meta.roles[me.role]}. Ask the owner or an admin if you need this page.</div></div>`.html;
      return;
    }
    const token = ++navToken;
    content.innerHTML = String(QT.loading());
    window.scrollTo(0, 0);
    try {
      const view = el('<div class="page"></div>');
      await QT.pages[page](view, params, ...args);
      if (token !== navToken) return;
      content.innerHTML = "";
      content.appendChild(view);
      view.dispatchEvent(new Event("mounted"));
    } catch (e) {
      if (token !== navToken) return;
      if (e.status === 401) return;
      content.innerHTML = h`<div class="empty-state">${QT.raw(icon("alert"))}<h4>Could not load this page</h4><div>${e.message}</div></div>`.html;
    }
  }
  window.addEventListener("hashchange", route);

  // ---------------------------------------------------------------- shell
  function renderShell() {
    const s = QT.state.meta.settings;
    const me = QT.me();
    let nav = "";
    NAV.forEach(([label, items]) => {
      nav += '<div class="nav-label">' + esc(label) + '</div><nav class="nav">';
      const allowed = items.filter((it) => QT.can(it[3]));
      if (!allowed.length) { nav = nav.slice(0, nav.lastIndexOf('<div class="nav-label">')); return; }
      allowed.forEach(([href, ic, text]) => { nav += '<a href="' + href + '">' + icon(ic) + "<span>" + esc(text) + "</span></a>"; });
      nav += "</nav>";
    });
    $("#app").innerHTML = h`<div class="shell">
      <aside class="sidebar">
        <div class="brand">${QT.raw(s.logo_file ? '<div class="brand-mark logo"><img src="' + esc(QT.logoUrl(s.logo_file)) + '" alt=""></div>' : '<div class="brand-mark">' + icon("plane") + "</div>")}<div><div class="brand-name">${s.business_name || "Travel Agency"}</div><div class="brand-sub">Travel CRM</div></div></div>
        ${QT.raw(nav)}
        <div class="sidebar-foot">
          <a class="whoami" href="#/settings/security" title="My account: change your password"><b>${me.display_name || me.username}</b><span>${QT.state.meta.roles[me.role]} · <span class="mode-chip ${QT.cloud() ? "cloud" : ""}">${QT.cloud() ? "Cloud · shared office" : "Local · " + QT.os.computer}</span></span></a>
          <button type="button" data-theme-toggle>${QT.raw(icon(QT.isDark() ? "sun" : "moon"))}<span>${QT.isDark() ? "Light mode" : "Dark mode"}</span></button>
          <button type="button" data-logout>${QT.raw(icon("logout"))}<span>Sign out</span></button>
          <div>v${QT.state.meta.version} · ${QT.cloud() ? location.host : "Private · " + QT.os.computer + " only"}</div>
        </div>
      </aside>
      <div class="main">
        <header class="topbar">
          <button class="btn icon ghost menu-btn" data-menu aria-label="Menu">${QT.raw(icon("menu"))}</button>
          <div class="gsearch">${QT.raw(icon("search"))}<input id="gsearch" placeholder="Search PNR, passenger, ticket no., phone, invoice…" autocomplete="off"><span class="kbd">${QT.os.mod}K</span></div>
          <div class="top-actions">
            <div class="dd"><button class="btn primary" data-quick>${QT.raw(icon("plus"))}<span class="lbl-txt">New</span></button></div>
          </div>
        </header>
        <main class="content" id="content"></main>
      </div>
    </div>`.html;
    $("[data-menu]").onclick = (e) => { e.stopPropagation(); $(".shell").classList.toggle("nav-open"); };
    $(".shell").addEventListener("click", (e) => { if ($(".shell").classList.contains("nav-open") && !e.target.closest(".sidebar") && !e.target.closest("[data-menu]")) $(".shell").classList.remove("nav-open"); });
    $("[data-theme-toggle]").onclick = () => {
      const next = QT.isDark() ? "light" : "dark";
      QT.pref.set("theme", next);
      applyTheme(next);
      const b = $("[data-theme-toggle]");
      b.innerHTML = icon(QT.isDark() ? "sun" : "moon") + "<span>" + (QT.isDark() ? "Light mode" : "Dark mode") + "</span>";
      route();
    };
    $("[data-logout]").onclick = async () => {
      try { await QT.post("/api/auth/logout"); } catch (e) { /* ignore */ }
      QT.state.authed = false;
      showLogin();
    };
    const quick = [
      QT.can("bookings") && { label: "New booking", icon: "ticket", onClick: () => QT.go("#/bookings/new") },
      QT.can("receipts") && { label: "Customer receipt", icon: "arrowIn", onClick: () => QT.txnForm({ kind: "customer_receipt" }) },
      QT.can("money") && { label: "Supplier payment", icon: "arrowOut", onClick: () => QT.txnForm({ kind: "supplier_payment" }) },
      QT.can("money") && { label: "Expense", icon: "expense", onClick: () => QT.txnForm({ kind: "expense" }) },
      "-",
      QT.can("bookings") && { label: "New customer", icon: "user", onClick: () => QT.customerForm() },
      QT.can("bookings") && { label: "New supplier", icon: "building", onClick: () => QT.supplierForm() },
      QT.can("money") && { label: "Transfer between accounts", icon: "swap", onClick: () => QT.txnForm({ kind: "transfer" }) },
    ].filter(Boolean);
    if (quick[0] === "-") quick.shift();
    if (quick[quick.length - 1] === "-") quick.pop();
    if (quick.length) QT.dropdown($("[data-quick]"), quick);
    else $("[data-quick]").remove();
    setupSearch();
  }

  // ---------------------------------------------------------------- global search
  function setupSearch() {
    const input = $("#gsearch");
    const wrap = input.parentElement;
    let box = null;
    let links = [];
    let sel = -1;
    const close = () => { if (box) { box.remove(); box = null; } sel = -1; };
    const run = QT.debounce(async () => {
      const q = input.value.trim();
      if (q.length < 2) { close(); return; }
      let r;
      try { r = await QT.get("/api/search" + QT.qs({ q })); } catch (e) { return; }
      if (input.value.trim() !== q) return;
      close();
      box = el('<div class="gresults"></div>');
      let html = "";
      if (r.bookings.length) {
        html += '<div class="grp">Bookings</div>';
        r.bookings.forEach((b) => {
          html += h`<a href="#/bookings/${b.id}"><span class="pnr">${b.pnr || "—"}</span><span class="grow" style="flex:1"><b>${b.ref}</b> · ${b.lead_pax || b.customer_name} <span class="muted small">${b.route} ${QT.dateShort(b.travel_date)}</span></span>${QT.statusBadge(b.status)}</a>`;
        });
      }
      if (r.customers.length) {
        html += '<div class="grp">Customers</div>';
        r.customers.forEach((c) => { html += h`<a href="#/customers/${c.id}">${QT.raw(icon("user"))}<span style="flex:1">${c.name}</span><span class="muted small">${c.phone || ""}</span></a>`; });
      }
      if (r.suppliers.length) {
        html += '<div class="grp">Suppliers</div>';
        r.suppliers.forEach((c) => { html += h`<a href="#/suppliers/${c.id}">${QT.raw(icon("building"))}<span style="flex:1">${c.name}</span></a>`; });
      }
      if (r.invoices.length) {
        html += '<div class="grp">Invoices</div>';
        r.invoices.forEach((i) => { html += h`<a href="#/invoices?open=${i.id}">${QT.raw(icon("invoice"))}<span style="flex:1">${i.number}</span><span class="muted small">${i.ref}</span></a>`; });
      }
      if (r.transactions.length) {
        html += '<div class="grp">Transactions</div>';
        r.transactions.forEach((t) => { html += h`<a href="#" data-txn="${t.id}">${QT.raw(icon("receipt"))}<span style="flex:1">${t.txn_no} <span class="muted small">${t.reference || ""}</span></span><span class="small">${QT.money(t.amount, t.currency)}</span></a>`; });
      }
      if (!html) html = h`<div class="empty">No saved records match “${q}”. <a href="#/bookings/new?pnr=${encodeURIComponent(q.toUpperCase())}">Create a booking</a></div>`.html;
      box.innerHTML = html;
      wrap.appendChild(box);
      links = $$("a", box);
      $$("[data-txn]", box).forEach((a) => { a.onclick = (e) => { e.preventDefault(); close(); input.value = ""; QT.txnDetail(+a.dataset.txn); }; });
      links.forEach((a) => a.addEventListener("click", () => { close(); input.value = ""; }));
    }, 220);
    input.addEventListener("input", run);
    input.addEventListener("focus", run);
    input.addEventListener("blur", () => setTimeout(close, 180));
    input.addEventListener("keydown", (e) => {
      if (e.key === "Escape") { close(); input.blur(); return; }
      if (!box) { if (e.key === "Enter" && input.value.trim()) QT.go("#/pnr?q=" + encodeURIComponent(input.value.trim())); return; }
      if (e.key === "ArrowDown") { sel = Math.min(sel + 1, links.length - 1); e.preventDefault(); }
      else if (e.key === "ArrowUp") { sel = Math.max(sel - 1, 0); e.preventDefault(); }
      else if (e.key === "Enter") {
        e.preventDefault();
        if (sel >= 0 && links[sel]) links[sel].click();
        else { const q = input.value.trim(); close(); input.value = ""; QT.go("#/pnr?q=" + encodeURIComponent(q)); }
        return;
      }
      links.forEach((a, i) => a.classList.toggle("sel", i === sel));
    });
  }
  document.addEventListener("keydown", (e) => {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
      const i = $("#gsearch");
      if (i) { e.preventDefault(); i.focus(); i.select(); }
    }
  });

  // ---------------------------------------------------------------- auth screens
  function authLayout(formHtml) {
    $("#app").innerHTML = h`<div class="auth">
      <div class="auth-art">
        <div class="brand" style="padding:0"><div class="brand-mark">${QT.raw(icon("plane"))}</div><div><div class="brand-name">AirZelo</div><div class="brand-sub">Travel agency desk</div></div></div>
        ${QT.raw('<svg class="plane" viewBox="0 0 24 24" fill="#fff">' + '<path d="M17.8 19.2L16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"/></svg>')}
        <div><h2>Every PNR, payment and rupee of margin — in one place.</h2>
        <p style="margin-top:12px">${QT.state.mode === "cloud" ? "Bookings, customers, supplier balances, refunds and profit, shared securely by your office team." : "Bookings, customers, supplier balances, refunds and profit, stored privately on " + QT.os.computer + "."}</p></div>
        <div class="small" style="color:#9FB3EA">${QT.raw(icon("lock", "").replace("<svg", '<svg style="width:14px;height:14px;vertical-align:-2px"'))} ${QT.state.mode === "cloud" ? "Cloud workspace · " + location.host + " · encrypted connection (HTTPS)" : "Runs locally · not reachable from other devices"}</div>
      </div>
      <div class="auth-form">${QT.raw(formHtml)}</div>
    </div>`.html;
  }
  function showLogin() {
    authLayout(h`<form id="login" autocomplete="on">
      <div><h1>Welcome back</h1><div class="muted">Sign in to AirZelo</div></div>
      <div class="f s12"><label>Username</label><input class="inp" name="username" autocomplete="username" required></div>
      <div class="f s12"><label>Password</label><input class="inp" type="password" name="password" autocomplete="current-password" required></div>
      <button class="btn primary" type="submit">Sign in</button>
      <div class="muted small">${QT.raw(QT.state.mode === "cloud" ? "Forgot your password? Ask the owner or an admin to set a temporary one for you." : "Forgot your password? Stop the app and run <span class=\"mono\">python3 run.py --reset-password</span> on " + esc(QT.os.computer) + ".")}</div>
    </form>`.html);
    const f = $("#login");
    $("[name=username]", f).focus();
    f.onsubmit = async (e) => {
      e.preventDefault();
      const btn = $("button", f);
      btn.disabled = true;
      try {
        await QT.post("/api/auth/login", QT.formData(f));
        await start();
      } catch (err) { QT.showErr(f, err); } finally { btn.disabled = false; }
    };
  }
  function showSetup() {
    authLayout(h`<form id="setup" autocomplete="off">
      <div><h1>Set up your owner login</h1><div class="muted">This is a one-time step. Only you will be able to sign in.</div></div>
      <div class="alert info">${QT.raw(icon("info"))}<div>Enter the 6-digit <b>setup code</b> shown in the Terminal window where AirZelo is running.</div></div>
      <div class="f s12"><label>Setup code</label><input class="inp mono" name="setup_code" inputmode="numeric" maxlength="6" required></div>
      <div class="f s12"><label>Username</label><input class="inp" name="username" autocomplete="username" required></div>
      <div class="f s12"><label>Password</label><input class="inp" type="password" name="password" autocomplete="new-password" required><span class="hint">At least 10 characters, mixing letters, numbers or symbols.</span></div>
      <div class="f s12"><label>Repeat password</label><input class="inp" type="password" name="password2" autocomplete="new-password" required></div>
      <button class="btn primary" type="submit">Create owner login</button>
    </form>`.html);
    const f = $("#setup");
    $("[name=setup_code]", f).focus();
    f.onsubmit = async (e) => {
      e.preventDefault();
      const d = QT.formData(f);
      if (d.password !== d.password2) { QT.showErr(f, { message: "Passwords do not match", field: "password2" }); return; }
      const btn = $("button", f);
      btn.disabled = true;
      try { await QT.post("/api/auth/setup", d); QT.toast("Owner login created"); await start(); }
      catch (err) { QT.showErr(f, err); } finally { btn.disabled = false; }
    };
  }
  // a temporary password set by the owner or an admin must be replaced before anything else
  function showNewPassword(user) {
    authLayout(h`<form id="newpw" autocomplete="off">
      <div><h1>Choose your own password</h1><div class="muted">${user.display_name || user.username}, your account has a temporary password. Choose a new one to continue.</div></div>
      <div class="f s12"><label>Temporary password</label><input class="inp" type="password" name="current_password" autocomplete="current-password" required></div>
      <div class="f s12"><label>New password</label><input class="inp" type="password" name="new_password" autocomplete="new-password" required><span class="hint">At least 10 characters, mixing letters, numbers or symbols.</span></div>
      <div class="f s12"><label>Repeat new password</label><input class="inp" type="password" name="new_password2" autocomplete="new-password" required></div>
      <button class="btn primary" type="submit">Save and sign in again</button>
    </form>`.html);
    const f = $("#newpw");
    $("[name=current_password]", f).focus();
    f.onsubmit = async (e) => {
      e.preventDefault();
      const d = QT.formData(f);
      if (d.new_password !== d.new_password2) { QT.showErr(f, { message: "New passwords do not match", field: "new_password2" }); return; }
      try { await QT.post("/api/auth/password", d); QT.toast("Password changed. Please sign in with your new password."); showLogin(); }
      catch (err) { QT.showErr(f, err); }
    };
  }
  QT.onUnauthorized = function () {
    if (!QT.state.authed) return;
    QT.state.authed = false;
    $("#modal-root").innerHTML = "";
    QT.toast("Your session ended. Please sign in again.", "err");
    showLogin();
  };

  async function start() {
    let st;
    try { st = await QT.get("/api/auth/status"); }
    catch (e) {
      $("#app").innerHTML = h`<div class="empty-state" style="padding-top:20vh">${QT.raw(icon("alert"))}<h4>Cannot reach AirZelo</h4><div>Start the app, then reload this page.</div></div>`.html;
      return;
    }
    QT.state.mode = st.mode;
    if (st.setup_needed) return showSetup();
    if (!st.logged_in) return showLogin();
    if (st.user && st.user.must_change_password) return showNewPassword(st.user);
    QT.state.authed = true;
    await QT.loadMeta();
    renderShell();
    route();
  }
  start();
})();
