/* AirZelo - core utilities */
(function () {
  "use strict";
  const QT = (window.QT = { pages: {}, state: { meta: null } });

  // ---------------------------------------------------------------- icons
  const P = {
    dashboard: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.5-3.5"/>',
    plane: '<path d="M17.8 19.2L16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"/>',
    ticket: '<path d="M3 9V6a1 1 0 011-1h16a1 1 0 011 1v3a3 3 0 000 6v3a1 1 0 01-1 1H4a1 1 0 01-1-1v-3a3 3 0 000-6z"/><path d="M13 5v2M13 11v2M13 17v2"/>',
    users: '<path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87M16 3.13a4 4 0 010 7.75"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-1a6 6 0 0112 0v1"/>',
    building: '<rect x="4" y="3" width="16" height="18" rx="2"/><path d="M9 7h1M14 7h1M9 11h1M14 11h1M9 15h1M14 15h1M10 21v-3h4v3"/>',
    wallet: '<path d="M20 7V5a2 2 0 00-2-2H5a2 2 0 00-2 2v14a2 2 0 002 2h13a2 2 0 002-2v-2"/><path d="M22 11h-6a2 2 0 000 4h6v-4z"/>',
    receipt: '<path d="M5 3h14v18l-3-2-2 2-2-2-2 2-2-2-3 2z"/><path d="M9 8h6M9 12h6"/>',
    arrowIn: '<path d="M12 4v12M6 10l6 6 6-6"/><path d="M4 20h16"/>',
    arrowOut: '<path d="M12 20V8M6 14l6-6 6 6"/><path d="M4 4h16"/>',
    refund: '<path d="M3 12a9 9 0 109-9 9.7 9.7 0 00-6.7 2.7L3 8"/><path d="M3 3v5h5"/>',
    expense: '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20M6 15h4"/>',
    bank: '<path d="M3 21h18M5 21V10M19 21V10M9 21V10M15 21V10M2 10l10-6 10 6"/>',
    book: '<path d="M4 19.5A2.5 2.5 0 016.5 17H20V3H6.5A2.5 2.5 0 004 5.5z"/><path d="M4 19.5A2.5 2.5 0 006.5 22H20v-5"/>',
    invoice: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
    chart: '<path d="M3 3v18h18"/><path d="M7 15l4-4 3 3 5-6"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 11-2.83 2.83l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 11-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 11-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 110-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 112.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 114 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 112.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 110 4h-.09a1.65 1.65 0 00-1.51 1z"/>',
    shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    x: '<path d="M18 6L6 18M6 6l12 12"/>',
    edit: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 013 3L7 19l-4 1 1-4z"/>',
    trash: '<path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2M19 6l-1 14a2 2 0 01-2 2H8a2 2 0 01-2-2L5 6"/>',
    print: '<path d="M6 9V2h12v7"/><rect x="6" y="14" width="12" height="8"/><path d="M6 18H4a2 2 0 01-2-2v-5a2 2 0 012-2h16a2 2 0 012 2v5a2 2 0 01-2 2h-2"/>',
    download: '<path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3"/>',
    upload: '<path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M17 8l-5-5-5 5M12 3v12"/>',
    file: '<path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/><path d="M14 2v6h6"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    alert: '<path d="M10.3 3.9L1.8 18a2 2 0 001.7 3h17a2 2 0 001.7-3L13.7 3.9a2 2 0 00-3.4 0z"/><path d="M12 9v4M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
    calendar: '<rect x="3" y="4" width="18" height="18" rx="2"/><path d="M16 2v4M8 2v4M3 10h18"/>',
    clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
    bell: '<path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 01-3.4 0"/>',
    moon: '<path d="M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z"/>',
    sun: '<circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4"/>',
    logout: '<path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9"/>',
    menu: '<path d="M3 12h18M3 6h18M3 18h18"/>',
    chevron: '<path d="M9 18l6-6-6-6"/>',
    chevronDown: '<path d="M6 9l6 6 6-6"/>',
    back: '<path d="M19 12H5M12 19l-7-7 7-7"/>',
    trend: '<path d="M23 6l-9.5 9.5-5-5L1 18"/><path d="M17 6h6v6"/>',
    coins: '<circle cx="8" cy="8" r="6"/><path d="M18.1 10.4A6 6 0 1110.4 18.1M7 6h1v4M16.7 13.9l.7.7-2.8 2.8"/>',
    percent: '<path d="M19 5L5 19"/><circle cx="6.5" cy="6.5" r="2.5"/><circle cx="17.5" cy="17.5" r="2.5"/>',
    swap: '<path d="M16 3l4 4-4 4M20 7H4M8 21l-4-4 4-4M4 17h16"/>',
    history: '<path d="M3 3v5h5"/><path d="M3.05 13A9 9 0 106 5.3L3 8"/><path d="M12 7v5l4 2"/>',
    database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>',
    lock: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0110 0v4"/>',
    phone: '<path d="M22 16.9v3a2 2 0 01-2.2 2 19.8 19.8 0 01-8.6-3.1 19.5 19.5 0 01-6-6A19.8 19.8 0 012.1 4.2 2 2 0 014.1 2h3a2 2 0 012 1.7c.1 1 .4 1.9.7 2.8a2 2 0 01-.5 2.1L8 9.9a16 16 0 006 6l1.3-1.3a2 2 0 012.1-.4c.9.3 1.8.6 2.8.7a2 2 0 011.7 2z"/>',
    mail: '<rect x="2" y="4" width="20" height="16" rx="2"/><path d="M22 6l-10 7L2 6"/>',
    link: '<path d="M10 13a5 5 0 007.5.5l3-3a5 5 0 00-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 00-7.5-.5l-3 3a5 5 0 007 7l1.7-1.7"/>',
    more: '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
    seat: '<path d="M6 3v10a3 3 0 003 3h8M6 16v5M17 16v5M10 8h6a2 2 0 012 2v6"/>',
    bag: '<rect x="4" y="7" width="16" height="13" rx="2"/><path d="M9 7V4h6v3M9 11v5M15 11v5"/>',
    globe: '<circle cx="12" cy="12" r="10"/><path d="M2 12h20M12 2a15.3 15.3 0 014 10 15.3 15.3 0 01-4 10 15.3 15.3 0 01-4-10 15.3 15.3 0 014-10z"/>',
    filter: '<path d="M22 3H2l8 9.5V19l4 2v-8.5z"/>',
    whatsapp: '<path d="M3 21l1.7-5A8.5 8.5 0 1112 20.5a8.4 8.4 0 01-4-1z"/><path d="M9 8.5c0 3 2.5 6 6 6.5l1-1.6-2-1-1 .8c-1-.4-2.2-1.6-2.6-2.6l.8-1-1-2z"/>',
    copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 01-1-1V4a1 1 0 011-1h10a1 1 0 011 1v1"/>',
  };
  QT.icon = function (name, cls) {
    return '<svg class="' + (cls || "") + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (P[name] || P.info) + "</svg>";
  };

  // ---------------------------------------------------------------- escaping
  const ESC = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  QT.esc = function (v) {
    if (v === null || v === undefined) return "";
    return String(v).replace(/[&<>"']/g, (c) => ESC[c]);
  };
  QT.h = function (strings, ...vals) {
    // tagged template: values are escaped unless wrapped with QT.raw()
    let out = "";
    strings.forEach((s, i) => {
      out += s;
      if (i < vals.length) {
        const v = vals[i];
        if (v && v.__raw) out += v.html;
        else if (Array.isArray(v)) out += v.map((x) => (x && x.__raw ? x.html : QT.esc(x))).join("");
        else out += QT.esc(v);
      }
    });
    return { __raw: true, html: out, toString() { return out; } };
  };
  QT.raw = (html) => ({ __raw: true, html: String(html), toString() { return String(html); } });
  QT.el = function (html) {
    const t = document.createElement("template");
    t.innerHTML = String(html).trim();
    return t.content.firstElementChild;
  };
  QT.$ = (sel, root) => (root || document).querySelector(sel);
  QT.$$ = (sel, root) => Array.from((root || document).querySelectorAll(sel));

  // ---------------------------------------------------------------- money
  const fmtCache = {};
  function grouping(cur) {
    const key = cur === "INR" ? "en-IN" : "en-US";
    if (!fmtCache[key]) fmtCache[key] = new Intl.NumberFormat(key, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    return fmtCache[key];
  }
  QT.symbol = function (cur) {
    cur = cur || QT.base();
    if (cur === "INR") return "₹";
    return cur + " ";
  };
  QT.base = () => (QT.state.meta && QT.state.meta.settings.base_currency) || "INR";
  // minor units (integer) -> "₹12,000.00"
  QT.money = function (minor, cur, opts) {
    opts = opts || {};
    cur = cur || QT.base();
    const n = Number(minor || 0);
    const neg = n < 0;
    const abs = Math.abs(n);
    const major = Math.trunc(abs / 100);
    const frac = abs % 100;
    // build from integers to avoid float rounding in display
    const s = grouping(cur).format(major).split(".")[0] + "." + String(frac).padStart(2, "0");
    const sign = neg ? "-" : opts.plus && n > 0 ? "+" : "";
    const asCode = QT.state.meta && QT.state.meta.settings && QT.state.meta.settings.currency_display === "code";
    return sign + (opts.nosym ? "" : asCode ? cur + " " : QT.symbol(cur)) + s;
  };
  QT.moneyCls = (minor) => (minor < 0 ? "neg" : "");
  QT.plain = function (minor) {
    // minor -> "12000.50" for input fields
    const n = Number(minor || 0);
    const neg = n < 0 ? "-" : "";
    const a = Math.abs(n);
    return neg + Math.trunc(a / 100) + "." + String(a % 100).padStart(2, "0");
  };
  // string -> integer minor units without floating point; returns NaN if invalid
  QT.parseMinor = function (str) {
    if (str === null || str === undefined) return 0;
    let s = String(str).replace(/[,\s₹]/g, "").trim();
    if (s === "") return 0;
    const neg = s.startsWith("-");
    if (neg) s = s.slice(1);
    if (!/^\d*(\.\d{0,2})?$/.test(s) || s === ".") return NaN;
    const [i, f = ""] = s.split(".");
    const v = parseInt(i || "0", 10) * 100 + parseInt((f + "00").slice(0, 2), 10);
    return neg ? -v : v;
  };
  // minor * decimal rate string -> minor, half-up (BigInt for exactness)
  QT.convert = function (minor, rate) {
    if (!minor) return 0;
    rate = String(rate || "1");
    if (!/^\d+(\.\d+)?$/.test(rate)) return NaN;
    const [ri, rf = ""] = rate.split(".");
    const scale = BigInt(10) ** BigInt(rf.length);
    const r = BigInt(ri + rf);
    const m = BigInt(Math.abs(minor));
    let q = (m * r) / scale;
    const rem = (m * r) % scale;
    if (rem * BigInt(2) >= scale) q += BigInt(1);
    return Number(q) * (minor < 0 ? -1 : 1);
  };
  QT.percentOf = function (minor, pct) {
    if (!minor) return 0;
    pct = String(pct || "0");
    const [pi, pf = ""] = pct.split(".");
    const scale = BigInt(10) ** BigInt(pf.length) * BigInt(100);
    const p = BigInt(pi + pf);
    const m = BigInt(Math.abs(minor));
    let q = (m * p) / scale;
    if (((m * p) % scale) * BigInt(2) >= scale) q += BigInt(1);
    return Number(q) * (minor < 0 ? -1 : 1);
  };

  // ---------------------------------------------------------------- dates
  const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
  QT.iso = function (d) {
    const z = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + z(d.getMonth() + 1) + "-" + z(d.getDate());
  };
  QT.today = () => QT.iso(new Date());
  // dates follow the agency's Settings -> Documents & formats -> Date format
  QT.date = function (s) {
    if (!s) return "";
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
    if (!m) return s;
    const f = (QT.state.meta && QT.state.meta.settings && QT.state.meta.settings.date_format) || "DD MMM YYYY";
    if (f === "DD/MM/YYYY") return m[3] + "/" + m[2] + "/" + m[1];
    if (f === "MM/DD/YYYY") return m[2] + "/" + m[3] + "/" + m[1];
    if (f === "YYYY-MM-DD") return m[1] + "-" + m[2] + "-" + m[3];
    return parseInt(m[3], 10) + " " + MON[parseInt(m[2], 10) - 1] + " " + m[1];
  };
  QT.logoUrl = (file) => (file ? "/api/branding/logo?f=" + encodeURIComponent(file) : "");
  // the agency's brand colour drives the accent colour of the app
  QT.applyBranding = function () {
    const s = (QT.state.meta && QT.state.meta.settings) || {};
    const c = /^#[0-9A-Fa-f]{6}$/.test(s.brand_color || "") ? s.brand_color : "";
    const root = document.documentElement.style;
    if (c) {
      root.setProperty("--royal", c);
      root.setProperty("--royal-600", "color-mix(in srgb, " + c + " 82%, black)");
      root.setProperty("--royal-50", "color-mix(in srgb, " + c + " 9%, white)");
      root.setProperty("--royal-100", "color-mix(in srgb, " + c + " 18%, white)");
    } else {
      ["--royal", "--royal-600", "--royal-50", "--royal-100"].forEach((v) => root.removeProperty(v));
    }
  };
  QT.dateShort = function (s) {
    if (!s) return "";
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(s);
    return m ? parseInt(m[3], 10) + " " + MON[parseInt(m[2], 10) - 1] : s;
  };
  QT.time = function (s) {
    if (!s || s.length < 16) return "";
    return s.slice(11, 16);
  };
  QT.dateTime = function (s) {
    if (!s) return "";
    return QT.date(s) + (s.length >= 16 ? " " + s.slice(11, 16) : "");
  };
  QT.preset = function (name) {
    const t = new Date();
    const today = QT.iso(t);
    if (name === "today") return [today, today];
    if (name === "week") {
      const d = new Date(t);
      const dow = (d.getDay() + 6) % 7;
      d.setDate(d.getDate() - dow);
      return [QT.iso(d), today];
    }
    if (name === "month") return [today.slice(0, 8) + "01", today];
    if (name === "last_month") {
      const d = new Date(t.getFullYear(), t.getMonth() - 1, 1);
      const e = new Date(t.getFullYear(), t.getMonth(), 0);
      return [QT.iso(d), QT.iso(e)];
    }
    if (name === "year") return [today.slice(0, 4) + "-01-01", today];
    if (name === "fy") {
      const y = t.getMonth() >= 3 ? t.getFullYear() : t.getFullYear() - 1;
      return [y + "-04-01", today];
    }
    if (name === "all") return ["", ""];
    return [today.slice(0, 8) + "01", today];
  };

  // ---------------------------------------------------------------- labels
  QT.labels = {
    status: { enquiry: "Enquiry", on_hold: "On hold", confirmed: "Confirmed", ticketed: "Ticketed", cancelled: "Cancelled", refunded: "Refunded" },
    statusCls: { enquiry: "b-gray", on_hold: "b-warn", confirmed: "b-blue", ticketed: "b-green", cancelled: "b-red", refunded: "b-violet" },
    pay: { paid: "Paid", partial: "Part paid", unpaid: "Unpaid", overpaid: "Overpaid / credit", settled: "Settled", refund_due: "Refund due", advance: "Advance held", not_billed: "Not billed" },
    payCls: { paid: "b-green", partial: "b-warn", unpaid: "b-red", overpaid: "b-violet", settled: "b-gray", refund_due: "b-violet", advance: "b-cyan", not_billed: "b-gray" },
    service: { flight: "Flight", visa: "Visa", hotel: "Hotel", package: "Tour package", umrah: "Umrah", other: "Other" },
    method: { cash: "Cash", bank_transfer: "Bank transfer", upi: "UPI", card: "Card", cheque: "Cheque", payment_gateway: "Payment gateway", portal_wallet: "Portal wallet", adjustment: "Adjustment", other: "Other" },
    supplierKind: { b2b_portal: "B2B portal", airline: "Airline", consolidator: "Consolidator", hotel: "Hotel", visa: "Visa agency", other: "Other" },
    adj: { cancellation: "Cancellation", refund: "Refund", reissue: "Reissue", date_change: "Date change", other: "Other" },
    acc: { cash: "Cash", bank: "Bank", wallet: "Wallet", gateway: "Gateway" },
    accType: { cash: "Cash in hand", bank: "Bank account", current: "Current account", savings: "Savings account", upi: "UPI / payment account", credit_card: "Credit card", wallet: "Wallet / portal balance", gateway: "Payment gateway" },
  };
  // "HDFC Current (INR) ••1234" for account pickers
  QT.accLabel = function (a) {
    return a.name + " (" + a.currency + ")" + (a.account_last4 ? " ••" + a.account_last4 : "");
  };
  QT.accIcon = (a) => ({ cash: "wallet", upi: "phone", credit_card: "expense", wallet: "wallet", gateway: "link" })[a.account_type || a.kind] || "bank";
  QT.badge = function (text, cls) {
    return QT.h`<span class="badge ${cls || "b-gray"}">${text}</span>`;
  };
  QT.statusBadge = (s) => QT.badge(QT.labels.status[s] || s, QT.labels.statusCls[s]);
  QT.payBadge = (s) => QT.badge(QT.labels.pay[s] || s, QT.labels.payCls[s]);

  // ---------------------------------------------------------------- api
  QT.api = async function (method, path, body, opts) {
    opts = opts || {};
    const init = { method, headers: { "X-QT-CSRF": "1" }, credentials: "same-origin" };
    if (body !== undefined && body !== null) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(path, init);
    } catch (e) {
      const err = new Error("Cannot reach AirZelo. Is the app still running?");
      err.code = "network";
      throw err;
    }
    if (opts.raw) {
      if (!res.ok) {
        let msg = "Request failed";
        try { msg = (await res.json()).error || msg; } catch (e) { /* ignore */ }
        throw Object.assign(new Error(msg), { status: res.status });
      }
      return res;
    }
    let data = null;
    const ct = res.headers.get("Content-Type") || "";
    if (ct.includes("json")) data = await res.json();
    if (res.status === 401 && !path.startsWith("/api/auth/")) {
      QT.onUnauthorized && QT.onUnauthorized();
    }
    if (!res.ok) {
      const err = new Error((data && data.error) || "Request failed (" + res.status + ")");
      err.status = res.status;
      err.code = data && data.code;
      err.field = data && data.field;
      err.details = data && data.details;
      throw err;
    }
    return data;
  };
  QT.get = (p) => QT.api("GET", p);
  QT.post = (p, b) => QT.api("POST", p, b || {});
  QT.put = (p, b) => QT.api("PUT", p, b || {});
  QT.patch = (p, b) => QT.api("PATCH", p, b || {});
  QT.del = (p) => QT.api("DELETE", p);
  QT.qs = function (obj) {
    const parts = [];
    Object.keys(obj || {}).forEach((k) => {
      const v = obj[k];
      if (v !== undefined && v !== null && v !== "") parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(v));
    });
    return parts.length ? "?" + parts.join("&") : "";
  };
  QT.uuid = function () {
    if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
    const a = new Uint8Array(16);
    crypto.getRandomValues(a);
    return Array.from(a, (b) => b.toString(16).padStart(2, "0")).join("");
  };
  QT.loadMeta = async function () {
    QT.state.meta = await QT.get("/api/meta");
    QT.applyBranding();
    return QT.state.meta;
  };
  QT.download = async function (path, fallbackName) {
    const res = await QT.api("GET", path, null, { raw: true });
    const blob = await res.blob();
    let name = fallbackName || "download";
    const cd = res.headers.get("Content-Disposition") || "";
    const m = /filename\*=UTF-8''([^;]+)|filename="([^"]+)"/.exec(cd);
    if (m) name = decodeURIComponent(m[1] || m[2]);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 4000);
  };
  QT.fileToBase64 = function (file) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result).split(",")[1] || "");
      r.onerror = () => reject(new Error("Could not read the file"));
      r.readAsDataURL(file);
    });
  };

  // ---------------------------------------------------------------- storage (per-viewer conveniences only)
  QT.pref = {
    get(k, d) { try { const v = localStorage.getItem("qt." + k); return v === null ? d : v; } catch (e) { return d; } },
    set(k, v) { try { localStorage.setItem("qt." + k, v); } catch (e) { /* ignore */ } },
  };

  // ---------------------------------------------------------------- airlines reference (for autocomplete)
  QT.AIRLINES = ["Air India", "Air India Express", "IndiGo", "Akasa Air", "SpiceJet", "Vistara", "Alliance Air", "Air Arabia", "Air Arabia Abu Dhabi",
    "flydubai", "Emirates", "Etihad Airways", "Qatar Airways", "Saudia", "flynas", "flyadeal", "Gulf Air", "Oman Air", "SalamAir", "Kuwait Airways",
    "Jazeera Airways", "Riyadh Air", "Royal Jordanian", "EgyptAir", "Turkish Airlines", "Pegasus", "SriLankan Airlines", "Singapore Airlines",
    "Scoot", "Malaysia Airlines", "AirAsia", "Thai Airways", "Cathay Pacific", "British Airways", "Lufthansa", "Air France", "KLM",
    "Virgin Atlantic", "United Airlines", "American Airlines", "Delta", "Air Canada", "Ethiopian Airlines", "Kenya Airways", "Biman Bangladesh",
    "US-Bangla", "Nepal Airlines", "Himalaya Airlines", "Maldivian", "Air Seychelles", "Qantas", "Air New Zealand", "Japan Airlines", "ANA",
    "Korean Air", "Vietnam Airlines", "Philippine Airlines", "Garuda Indonesia"];
})();
