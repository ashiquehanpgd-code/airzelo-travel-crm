/* AirZelo - UI components */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;

  // ---------------------------------------------------------------- toast
  QT.toast = function (msg, type) {
    const t = el(h`<div class="toast ${type === "err" ? "err" : "ok"}">${QT.raw(icon(type === "err" ? "alert" : "check"))}<span>${msg}</span></div>`);
    $("#toast-root").appendChild(t);
    setTimeout(() => { t.style.opacity = "0"; t.style.transition = "opacity .3s"; }, type === "err" ? 5200 : 2800);
    setTimeout(() => t.remove(), type === "err" ? 5600 : 3200);
  };
  QT.fail = function (e) {
    console.error(e);
    QT.toast(e && e.message ? e.message : String(e), "err");
  };

  // ---------------------------------------------------------------- modal
  QT.modal = function (opts) {
    const root = $("#modal-root");
    const back = el(h`<div class="modal-back"><div class="modal ${opts.size || ""}" role="dialog" aria-modal="true">
      <div class="modal-h"><h3>${opts.title || ""}</h3><button class="btn ghost sm icon x" data-x aria-label="Close">${QT.raw(icon("x"))}</button></div>
      <div class="modal-b"></div>
      ${QT.raw(opts.actions === false ? "" : '<div class="modal-f"></div>')}
    </div></div>`);
    const body = $(".modal-b", back);
    if (typeof opts.body === "string" || (opts.body && opts.body.__raw)) body.innerHTML = String(opts.body);
    else if (opts.body) body.appendChild(opts.body);
    let closed = false;
    const api = {
      el: back, body,
      close() {
        if (closed) return;
        closed = true;
        back.remove();
        document.removeEventListener("keydown", onKey);
        opts.onClose && opts.onClose();
      },
    };
    const onKey = (e) => { if (e.key === "Escape" && root.lastElementChild === back) api.close(); };
    document.addEventListener("keydown", onKey);
    $("[data-x]", back).onclick = api.close;
    body.addEventListener("click", (e) => { const a = e.target.closest('a[href^="#/"]'); if (a) api.close(); });
    back.addEventListener("mousedown", (e) => { if (e.target === back && !opts.sticky) api.close(); });
    const foot = $(".modal-f", back);
    if (foot) {
      const actions = opts.actions || [{ label: "Close", cls: "", onClick: () => true }];
      actions.forEach((a) => {
        const b = el(h`<button class="btn ${a.cls || ""}" type="button">${a.icon ? QT.raw(icon(a.icon)) : ""}${a.label}</button>`);
        b.onclick = async () => {
          if (b.disabled) return;
          b.disabled = true;
          try {
            const r = a.onClick ? await a.onClick(api) : true;
            if (r !== false) api.close();
          } catch (e) {
            QT.showErr(body, e);
          } finally {
            b.disabled = false;
          }
        };
        foot.appendChild(b);
      });
    }
    root.appendChild(back);
    const first = $("input:not([type=hidden]):not([readonly]), select, textarea", body);
    if (first && !opts.noFocus) setTimeout(() => { if (!back.contains(document.activeElement)) first.focus(); }, 30);
    opts.onOpen && opts.onOpen(api);
    return api;
  };

  QT.confirm = function (opts) {
    if (typeof opts === "string") opts = { message: opts };
    return new Promise((resolve) => {
      let done = false;
      const body = h`<div class="stack" style="gap:12px">
        <div>${opts.message}</div>
        ${opts.detail ? QT.raw('<div class="muted small">' + esc(opts.detail) + "</div>") : ""}
        ${opts.typeText ? QT.raw('<div class="f s12"><label>Type <b>' + esc(opts.typeText) + '</b> to confirm</label><input class="inp" name="confirm_text" autocomplete="off"></div>') : ""}
      </div>`;
      QT.modal({
        title: opts.title || "Please confirm", body,
        onClose: () => { if (!done) resolve(false); },
        actions: [
          { label: "Cancel", onClick: () => true },
          { label: opts.ok || "Confirm", cls: opts.danger ? "danger solid" : "primary", onClick: (m) => {
            if (opts.typeText && $("[name=confirm_text]", m.body).value.trim() !== opts.typeText) {
              QT.toast("Type " + opts.typeText + " to confirm", "err");
              return false;
            }
            done = true; resolve(true); return true;
          } },
        ],
      });
    });
  };

  // ask for a short text (e.g. reason for reversal)
  QT.ask = function (opts) {
    return new Promise((resolve) => {
      let done = false;
      QT.modal({
        title: opts.title,
        body: h`<div class="stack" style="gap:12px">${opts.message ? QT.raw("<div style=\"white-space:pre-line\">" + esc(opts.message) + "</div>") : ""}
          <div class="f s12"><label>${opts.label || "Reason"}</label>${QT.raw(opts.multiline ? '<textarea class="inp" name="v" rows="3"></textarea>' : '<input class="inp" name="v">')}</div>
          ${opts.withDate ? QT.raw('<div class="f s12"><label>Date</label><input type="date" class="inp" name="d" value="' + QT.today() + '"></div>') : ""}</div>`,
        onClose: () => { if (!done) resolve(null); },
        actions: [
          { label: "Cancel" },
          { label: opts.ok || "Save", cls: opts.danger ? "danger solid" : "primary", onClick: (m) => {
            const v = $("[name=v]", m.body).value.trim();
            if (opts.required !== false && !v) { QT.toast((opts.label || "Reason") + " is required", "err"); return false; }
            done = true;
            resolve(opts.withDate ? { value: v, date: $("[name=d]", m.body).value } : v);
          } },
        ],
      });
    });
  };

  // ---------------------------------------------------------------- forms
  QT.formData = function (root) {
    const out = {};
    $$("[name]", root).forEach((f) => {
      if (f.closest("[data-skip]")) return;
      const n = f.name;
      if (f.type === "checkbox") out[n] = f.checked;
      else if (f.type === "radio") { if (f.checked) out[n] = f.value; }
      else out[n] = f.value;
    });
    return out;
  };
  QT.showErr = function (root, e) {
    $$(".err", root).forEach((x) => x.classList.remove("err"));
    if (e && e.field && root) {
      const f = $("[name='" + e.field + "']", root);
      if (f) {
        f.classList.add("err");
        const wrap = f.closest(".f");
        if (wrap) wrap.classList.add("err");
        f.focus();
      }
    }
    QT.fail(e);
  };
  QT.opt = function (value, label, selected) {
    return '<option value="' + esc(value) + '"' + (String(selected) === String(value) ? " selected" : "") + ">" + esc(label) + "</option>";
  };
  QT.options = function (list, selected, blank) {
    let s = blank !== undefined ? QT.opt("", blank, selected) : "";
    list.forEach((o) => { s += Array.isArray(o) ? QT.opt(o[0], o[1], selected) : QT.opt(o, o, selected); });
    return QT.raw(s);
  };
  QT.field = function (o) {
    // o: {name, label, type, value, span, req, hint, opts (select options html), attrs, cls, placeholder}
    const span = o.span ? " s" + o.span : "";
    let inner;
    const attrs = o.attrs || "";
    const ph = o.placeholder ? ' placeholder="' + esc(o.placeholder) + '"' : "";
    if (o.type === "select") inner = '<select class="inp ' + (o.cls || "") + '" name="' + o.name + '" ' + attrs + ">" + (o.opts || "") + "</select>";
    else if (o.type === "textarea") inner = '<textarea class="inp ' + (o.cls || "") + '" name="' + o.name + '" rows="' + (o.rows || 3) + '"' + ph + " " + attrs + ">" + esc(o.value || "") + "</textarea>";
    else if (o.type === "money") inner = '<input class="inp money ' + (o.cls || "") + '" inputmode="decimal" name="' + o.name + '" value="' + esc(o.value === undefined || o.value === null ? "" : o.value) + '"' + (ph || ' placeholder="0.00"') + " " + attrs + ">";
    else if (o.type === "checkbox") return QT.raw('<div class="f' + span + '"><label class="check"><input type="checkbox" name="' + o.name + '"' + (o.value ? " checked" : "") + " " + attrs + "> " + esc(o.label) + "</label>" + (o.hint ? '<span class="hint">' + esc(o.hint) + "</span>" : "") + "</div>");
    else inner = '<input class="inp ' + (o.cls || "") + '" type="' + (o.type || "text") + '" name="' + o.name + '" value="' + esc(o.value === undefined || o.value === null ? "" : o.value) + '"' + ph + " " + attrs + ">";
    return QT.raw('<div class="f' + span + '"><label>' + esc(o.label) + (o.req ? ' <span class="req">*</span>' : "") + "</label>" + inner + (o.hint ? '<span class="hint">' + esc(o.hint) + "</span>" : "") + "</div>");
  };

  // ---------------------------------------------------------------- table
  QT.table = function (cfg) {
    const cols = cfg.columns;
    const rows = cfg.rows || [];
    if (!rows.length) {
      return QT.raw('<div class="empty-state">' + icon(cfg.emptyIcon || "file") + "<h4>" + esc(cfg.emptyTitle || "Nothing here yet") + "</h4><div>" + esc(cfg.empty || "") + "</div></div>");
    }
    let s = '<div class="table-wrap"><table class="t"><thead><tr>';
    cols.forEach((c, i) => {
      const cls = (c.type === "money" || c.type === "money_raw" || c.type === "number" ? c.type === "number" ? "number" : "money" : "") + (cfg.sortable !== false && c.sortable !== false ? " sortable" : "");
      s += '<th class="' + cls + '" data-col="' + i + '">' + esc(c.label) + "</th>";
    });
    s += "</tr></thead><tbody>";
    rows.forEach((r, idx) => {
      const rc = cfg.rowClass ? cfg.rowClass(r) : "";
      s += '<tr data-idx="' + idx + '" class="' + (cfg.onRow ? "click " : "") + rc + '">';
      cols.forEach((c) => {
        let v;
        if (c.render) v = String(c.render(r));
        else {
          const raw = r[c.key];
          if (c.type === "money") v = '<span class="' + QT.moneyCls(raw) + '">' + QT.money(raw, c.cur ? c.cur(r) : undefined) + "</span>";
          else if (c.type === "money_raw") v = '<span class="' + QT.moneyCls(raw) + '">' + QT.money(raw, r.currency || undefined) + "</span>";
          else if (c.type === "date") v = esc(QT.date(raw));
          else if (c.type === "status") v = String(QT.labels.status[raw] ? QT.statusBadge(raw) : QT.labels.pay[raw] ? QT.payBadge(raw) : esc(raw));
          else v = esc(raw === null || raw === undefined ? "" : raw);
        }
        const cls = c.type === "money" || c.type === "money_raw" ? "money" : c.type === "number" ? "number" : c.cls || "";
        s += '<td class="' + cls + '">' + v + "</td>";
      });
      s += "</tr>";
    });
    s += "</tbody>";
    if (cfg.totals) {
      s += "<tfoot><tr>";
      cols.forEach((c, i) => {
        const has = cfg.totals[c.key] !== undefined;
        let v = "";
        if (has) v = c.type === "money" || c.type === "money_raw" ? QT.money(cfg.totals[c.key], c.type === "money_raw" ? cfg.totalsCur : undefined) : esc(cfg.totals[c.key]);
        else if (i === 0) v = "Total";
        s += '<td class="' + (c.type === "money" || c.type === "money_raw" || c.type === "number" ? "money" : "") + '">' + v + "</td>";
      });
      s += "</tr></tfoot>";
    }
    s += "</table></div>";
    return QT.raw(s);
  };
  // attach row clicks + sorting. The handler always reads the container's current cfg.
  QT.bindTable = function (container) {
    container.addEventListener("click", (e) => {
      const cfg = container._cfg;
      if (!cfg) return;
      const th = e.target.closest("th.sortable");
      if (th && container.contains(th)) {
        const c = cfg.columns[+th.dataset.col];
        const dir = cfg._sortCol === th.dataset.col && cfg._sortDir === 1 ? -1 : 1;
        cfg._sortCol = th.dataset.col;
        cfg._sortDir = dir;
        const key = c.sortKey || c.key;
        if (!key) return;
        cfg.rows = cfg.rows.slice().sort((a, b) => {
          const x = typeof key === "function" ? key(a) : a[key];
          const y = typeof key === "function" ? key(b) : b[key];
          if (x === y) return 0;
          if (x === null || x === undefined || x === "") return 1;
          if (y === null || y === undefined || y === "") return -1;
          return (typeof x === "number" && typeof y === "number" ? x - y : String(x).localeCompare(String(y), undefined, { numeric: true })) * dir;
        });
        cfg._draw();
        return;
      }
      if (e.target.closest("a, button, input, select, label")) return;
      const tr = e.target.closest("tbody tr[data-idx]");
      if (tr && cfg.onRow && container.contains(tr)) cfg.onRow(cfg.rows[+tr.dataset.idx], e);
    });
  };
  QT.renderTable = function (container, cfg) {
    container._cfg = cfg;
    const draw = () => { container.innerHTML = String(QT.table(cfg)); };
    cfg._draw = draw;
    draw();
    if (!container._bound) {
      container._bound = true;
      QT.bindTable(container);
    }
    return draw;
  };
  // replace an element with a fresh empty clone (drops old listeners)
  QT.fresh = function (node, cls) {
    const n = node.cloneNode(false);
    if (cls !== undefined) n.className = cls;
    node.replaceWith(n);
    return n;
  };

  // ---------------------------------------------------------------- dropdown
  QT.dropdown = function (btn, items) {
    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      const existing = btn.parentElement.querySelector(".dd-menu");
      $$(".dd-menu").forEach((m) => m.remove());
      if (existing) return;
      const menu = document.createElement("div");
      menu.className = "dd-menu";
      items.forEach((it) => {
        if (it === "-") { menu.appendChild(document.createElement("hr")); return; }
        const b = el(h`<button type="button">${QT.raw(icon(it.icon || "chevron"))}<span>${it.label}</span></button>`);
        b.onclick = (ev) => { ev.stopPropagation(); menu.remove(); it.onClick(); };
        menu.appendChild(b);
      });
      btn.parentElement.appendChild(menu);
    });
  };
  document.addEventListener("click", () => $$(".dd-menu").forEach((m) => m.remove()));

  // ---------------------------------------------------------------- combobox
  QT.combo = function (input, cfg) {
    const wrap = input.parentElement;
    wrap.classList.add("combo");
    let list = null;
    let sel = -1;
    let items = [];
    const close = () => { if (list) { list.remove(); list = null; } sel = -1; };
    const open = () => {
      const q = input.value.trim().toLowerCase();
      items = cfg.items().filter((it) => !q || cfg.match(it, q)).slice(0, 30);
      close();
      list = document.createElement("div");
      list.className = "combo-list";
      items.forEach((it, i) => {
        const d = el(h`<div data-i="${i}"><span>${cfg.label(it)}</span><span class="muted small">${cfg.sub ? cfg.sub(it) : ""}</span></div>`);
        d.onmousedown = (e) => { e.preventDefault(); pick(i); };
        list.appendChild(d);
      });
      if (cfg.onNew && input.value.trim()) {
        const d = el(h`<div class="new">+ ${cfg.newLabel || "Create"} "${input.value.trim()}"</div>`);
        d.onmousedown = (e) => { e.preventDefault(); const v = input.value.trim(); close(); cfg.onNew(v); };
        list.appendChild(d);
      }
      if (!list.children.length) { list.appendChild(el('<div class="muted">No matches</div>')); }
      wrap.appendChild(list);
    };
    const pick = (i) => { const it = items[i]; close(); if (it) cfg.onSelect(it); };
    input.addEventListener("input", () => { cfg.onType && cfg.onType(); open(); });
    input.addEventListener("focus", open);
    input.addEventListener("blur", () => setTimeout(close, 120));
    input.addEventListener("keydown", (e) => {
      if (!list) return;
      const rowsEl = $$("[data-i]", list);
      if (e.key === "ArrowDown") { sel = Math.min(sel + 1, rowsEl.length - 1); e.preventDefault(); }
      else if (e.key === "ArrowUp") { sel = Math.max(sel - 1, 0); e.preventDefault(); }
      else if (e.key === "Enter") { if (sel >= 0) { e.preventDefault(); pick(sel); } return; }
      else if (e.key === "Escape") { close(); return; }
      rowsEl.forEach((r, i) => r.classList.toggle("sel", i === sel));
    });
  };

  // ---------------------------------------------------------------- date range
  QT.rangeControl = function (state, onChange) {
    // state: {preset, from, to}
    const presets = [["today", "Today"], ["week", "This week"], ["month", "This month"], ["year", "This year"], ["fy", "FY"], ["custom", "Custom"]];
    const wrap = el('<div class="row" style="gap:8px"></div>');
    const seg = el('<div class="seg-ctl"></div>');
    presets.forEach(([k, l]) => {
      const b = el(h`<button type="button" class="${state.preset === k ? "on" : ""}">${l}</button>`);
      b.onclick = () => {
        state.preset = k;
        if (k !== "custom") { const [f, t] = QT.preset(k); state.from = f; state.to = t; }
        $$("button", seg).forEach((x) => x.classList.remove("on"));
        b.classList.add("on");
        custom.classList.toggle("hidden", k !== "custom");
        if (k !== "custom") onChange(state);
      };
      seg.appendChild(b);
    });
    const custom = el(h`<div class="row ${state.preset === "custom" ? "" : "hidden"}" style="gap:6px">
      <input type="date" class="inp inp-sm" style="width:auto" value="${state.from || ""}" data-f>
      <span class="muted">to</span>
      <input type="date" class="inp inp-sm" style="width:auto" value="${state.to || ""}" data-t>
      <button class="btn sm primary" type="button">Apply</button></div>`);
    $("button", custom).onclick = () => {
      state.from = $("[data-f]", custom).value;
      state.to = $("[data-t]", custom).value;
      if (state.from && state.to && state.from > state.to) { QT.toast("Start date is after end date", "err"); return; }
      onChange(state);
    };
    wrap.appendChild(seg);
    wrap.appendChild(custom);
    return wrap;
  };

  // ---------------------------------------------------------------- chart
  QT.chart = function (container, data, cfg) {
    const W = Math.max(container.clientWidth || 600, 320);
    const H = cfg.height || 260;
    const pad = { l: 64, r: 16, t: 14, b: 30 };
    const iw = W - pad.l - pad.r;
    const ih = H - pad.t - pad.b;
    const vals = [];
    data.forEach((d) => { cfg.bars.forEach((b) => vals.push(d[b.key])); if (cfg.line) vals.push(d[cfg.line.key]); });
    let max = Math.max(0, ...vals);
    let min = Math.min(0, ...vals);
    if (max === min) max = min + 100;
    const niceStep = (range) => {
      const raw = range / 4;
      const mag = Math.pow(10, Math.floor(Math.log10(raw)));
      const n = raw / mag;
      return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * mag;
    };
    const step = niceStep(max - min);
    max = Math.ceil(max / step) * step;
    min = Math.floor(min / step) * step;
    const y = (v) => pad.t + ih - ((v - min) / (max - min)) * ih;
    const n = data.length || 1;
    const bw = iw / n;
    const barW = Math.max(2, Math.min(28, (bw * 0.7) / cfg.bars.length));
    const compact = (minor) => {
      const v = minor / 100;
      const a = Math.abs(v);
      if (a >= 1e7) return (v / 1e7).toFixed(1).replace(/\.0$/, "") + "Cr";
      if (a >= 1e5) return (v / 1e5).toFixed(1).replace(/\.0$/, "") + "L";
      if (a >= 1e3) return (v / 1e3).toFixed(1).replace(/\.0$/, "") + "k";
      return String(Math.round(v));
    };
    let s = '<svg class="chart" viewBox="0 0 ' + W + " " + H + '" preserveAspectRatio="none" style="height:' + H + 'px">';
    for (let v = min; v <= max + 1e-9; v += step) {
      s += '<line class="grid-line" x1="' + pad.l + '" x2="' + (W - pad.r) + '" y1="' + y(v) + '" y2="' + y(v) + '"/>';
      s += '<text x="' + (pad.l - 8) + '" y="' + (y(v) + 4) + '" text-anchor="end">' + (QT.base() === "INR" ? "₹" : "") + compact(v) + "</text>";
    }
    const every = Math.ceil(n / Math.floor(iw / 56));
    data.forEach((d, i) => {
      const cx = pad.l + bw * i + bw / 2;
      cfg.bars.forEach((b, j) => {
        const v = d[b.key] || 0;
        const x = cx - (barW * cfg.bars.length) / 2 + j * barW;
        const y0 = y(Math.max(0, v));
        const hgt = Math.max(Math.abs(y(v) - y(0)), v ? 1.5 : 0);
        s += '<rect class="' + (b.cls || "bar") + '" data-i="' + i + '" x="' + x.toFixed(1) + '" y="' + y0.toFixed(1) + '" width="' + (barW - 2).toFixed(1) + '" height="' + hgt.toFixed(1) + '" rx="3"/>';
      });
      if (i % every === 0) s += '<text x="' + cx + '" y="' + (H - 9) + '" text-anchor="middle">' + esc(d.label) + "</text>";
    });
    if (cfg.line) {
      const pts = data.map((d, i) => (pad.l + bw * i + bw / 2).toFixed(1) + "," + y(d[cfg.line.key] || 0).toFixed(1));
      s += '<polyline class="line" points="' + pts.join(" ") + '"/>';
      if (n <= 40) data.forEach((d, i) => { s += '<circle class="dot" data-i="' + i + '" cx="' + (pad.l + bw * i + bw / 2) + '" cy="' + y(d[cfg.line.key] || 0) + '" r="3.5"/>'; });
    }
    s += '<rect x="' + pad.l + '" y="' + pad.t + '" width="' + iw + '" height="' + ih + '" fill="transparent" data-hit="1"/>';
    s += "</svg>";
    container.innerHTML = s;
    const svg = $("svg", container);
    let tip = null;
    svg.addEventListener("mousemove", (e) => {
      const rect = svg.getBoundingClientRect();
      const px = ((e.clientX - rect.left) / rect.width) * W;
      const i = Math.floor((px - pad.l) / bw);
      if (i < 0 || i >= n) { if (tip) { tip.remove(); tip = null; } return; }
      const d = data[i];
      if (!tip) { tip = document.createElement("div"); tip.className = "chart-tip"; document.body.appendChild(tip); }
      let html = "<b>" + esc(d.label) + "</b>";
      cfg.bars.forEach((b) => { html += "<br>" + esc(b.label) + ": " + QT.money(d[b.key]); });
      if (cfg.line) html += "<br>" + esc(cfg.line.label) + ": " + QT.money(d[cfg.line.key]);
      tip.innerHTML = html;
      tip.style.left = Math.min(e.clientX + 14, window.innerWidth - 220) + "px";
      tip.style.top = e.clientY + 14 + "px";
    });
    svg.addEventListener("mouseleave", () => { if (tip) { tip.remove(); tip = null; } });
  };

  // ---------------------------------------------------------------- print
  QT.print = function (html, title) {
    const root = $("#print-root");
    root.innerHTML = String(html);
    document.body.classList.add("printing");
    const old = document.title;
    if (title) document.title = title;
    const done = () => {
      document.body.classList.remove("printing");
      document.title = old;
      root.innerHTML = "";
      window.removeEventListener("afterprint", done);
    };
    window.addEventListener("afterprint", done);
    setTimeout(() => window.print(), 60);
  };
  QT.docHead = function (title, metaHtml) {
    const s = QT.state.meta.settings;
    return h`<div class="doc-head">
      <div class="doc-brand"><div class="mk">${QT.raw(icon("plane"))}</div><div>
        <div class="nm">${s.business_name || "Travel Agency"}</div>
        <div class="tg">${s.business_tagline || ""}</div>
        <div class="tg" style="margin-top:4px;white-space:pre-line">${[s.business_address, s.business_phone, s.business_email].filter(Boolean).join("\n")}</div>
        ${s.business_gstin ? QT.raw('<div class="tg">GSTIN: ' + esc(s.business_gstin) + "</div>") : ""}
      </div></div>
      <div class="doc-title"><h2>${title}</h2>${QT.raw(metaHtml || "")}</div>
    </div>`;
  };
  QT.docFoot = function () {
    const s = QT.state.meta.settings;
    return h`<div class="foot"><div>${s.invoice_footer || ""}</div><div>Generated ${QT.date(QT.today())} · AirZelo</div></div>`;
  };

  // ---------------------------------------------------------------- misc
  QT.debounce = function (fn, ms) {
    let t;
    return function (...a) { clearTimeout(t); t = setTimeout(() => fn.apply(this, a), ms || 250); };
  };
  QT.initials = (name) => (name || "?").split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join("");
  QT.loading = () => QT.raw('<div class="empty-state"><div class="boot-logo" style="margin:0 auto"></div></div>');
})();
