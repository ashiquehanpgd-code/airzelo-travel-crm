/* AI Ticket Scanner - upload an e-ticket (PDF / JPG / PNG), read it on this computer, and hand the extracted fields
   to the booking form. Nothing leaves the computer: macOS uses Apple PDFKit + Vision, Windows uses the bundled
   PDF reader + Windows text recognition. */
(function () {
  "use strict";
  const QT = window.QT;
  const { h, esc, icon, el, $, $$ } = QT;
  const TYPES = [".pdf", ".jpg", ".jpeg", ".png", ".heic"];
  const CONF = { high: ["ok", "Read clearly"], medium: ["check", "Please check"], low: ["check", "Uncertain - please check"], missing: ["missing", "Not found on the ticket - enter manually"] };
  QT.scanConf = CONF;

  // mount(container, { onResult(result), onClear() }) -> { scan, clear, current() }
  QT.ticketScanner = function (box, hooks) {
    let current = null;
    box.innerHTML = h`<div class="scan-card">
      <div class="scan-head"><div class="scan-title">${QT.raw(icon("file"))}<div><b>AI Ticket Scanner — Upload Ticket</b><div class="small muted">PDF e-tickets, scanned tickets, JPG / PNG screenshots · read offline on ${QT.os.computer}</div></div></div>
        <span class="badge b-green plain" data-engine>Offline</span></div>
      <label class="scan-drop" data-drop tabindex="0">
        <input type="file" hidden data-file accept=".pdf,.jpg,.jpeg,.png,.heic,application/pdf,image/jpeg,image/png">
        <span class="scan-drop-ic">${QT.raw(icon("upload"))}</span>
        <span><b>Drop the ticket here</b> or <u>choose a file</u></span>
        <span class="small muted">The ticket fills the form for you to review. Nothing is saved until you click Save booking.</span>
      </label>
      <div class="scan-result hidden" data-result></div>
    </div>`.html;
    const drop = $("[data-drop]", box), input = $("[data-file]", box), res = $("[data-result]", box);
    QT.get("/api/ticket-scan/status").then((s) => {
      const b = $("[data-engine]", box);
      if (!s.ready) { b.className = "badge b-red plain"; b.textContent = "Reader not installed"; b.title = s.reason; }
      else { b.title = s.note; if (s.ocr === false) b.textContent = "Offline · PDF only"; }
    }).catch(() => {});

    async function scan(file) {
      if (!file) return;
      const ext = (file.name.match(/\.[^.]+$/) || [""])[0].toLowerCase();
      if (!TYPES.includes(ext)) { QT.toast("Upload a PDF, JPG or PNG ticket", "err"); return; }
      if (file.size > 15 * 1024 * 1024) { QT.toast("Ticket files must be 15 MB or smaller", "err"); return; }
      if (current) discard();
      drop.classList.add("hidden");
      res.classList.remove("hidden");
      res.innerHTML = h`<div class="scan-file"><span class="scan-spin"></span><div class="grow"><b>${file.name}</b><div class="small muted">Reading the ticket on ${QT.os.computer}… ${QT.os.mac ? "(the very first scan can take a minute while macOS prepares its text recogniser)" : ""}</div></div></div>`.html;
      try {
        const r = await QT.post("/api/ticket-scan", { name: file.name, data: await QT.fileToBase64(file) });
        current = r;
        render(r, file);
        hooks.onResult && hooks.onResult(r);
      } catch (e) {
        current = null;
        res.innerHTML = h`<div class="alert danger small">${QT.raw(icon("alert"))}<div><b>${file.name}</b> could not be read: ${e.message}<div style="margin-top:6px"><button type="button" class="btn xs" data-again>Choose another file</button></div></div></div>`.html;
        $("[data-again]", res).onclick = () => input.click();
      }
    }
    function counts(r) {
      const all = [r.booking.pnr, ...r.passengers.flatMap((p) => [p.name, p.ticket_number]), ...r.segments.flatMap((s) => [s.flight_no, s.origin, s.destination, s.dep_at, s.arr_at])];
      return { ok: all.filter((f) => f.confidence === "high").length, check: all.filter((f) => f.confidence === "medium" || f.confidence === "low").length, missing: r.missing.length };
    }
    function render(r, file) {
      const c = counts(r);
      const fare = r.fare || {};
      const fareTxt = fare.total && fare.total.value ? (fare.currency ? fare.currency + " " : "") + Number(fare.total.value).toLocaleString("en-IN", { minimumFractionDigits: 2 }) : "";
      const how = { "pdf-text": "PDF text", ocr: "OCR (image)", mixed: "PDF text + OCR" }[r.method] || r.method;
      res.innerHTML = h`<div class="scan-file">
          ${r.preview ? QT.raw('<a class="scan-thumb" href="#" data-zoom title="Show larger"><img alt="Ticket preview" src="/api/ticket-scan/' + esc(r.scan_id) + '/preview"></a>') : QT.raw('<span class="scan-thumb ic">' + icon("file") + "</span>")}
          <div class="grow"><b>${r.file_name}</b>
            <div class="small muted">${Math.round(r.size / 1024)} KB · ${r.page_count} page${r.page_count === 1 ? "" : "s"} · read with ${how} in ${r.seconds}s${r.ocr_confidence ? " · OCR confidence " + Math.round(r.ocr_confidence * 100) + "%" : ""}</div>
            <div class="scan-counts"><span class="sc ok">${c.ok} read</span><span class="sc check">${c.check} to check</span><span class="sc missing">${c.missing} missing</span></div></div>
          <div class="scan-actions"><button type="button" class="btn xs" data-replace>${QT.raw(icon("upload"))}Replace</button><button type="button" class="btn xs ghost" data-remove>${QT.raw(icon("x"))}Remove</button></div>
        </div>
        ${QT.raw(r.duplicate_pnr.length ? '<div class="alert warn small">' + icon("alert") + "<div>PNR <b>" + esc(r.booking.pnr.value) + "</b> is already saved in " + r.duplicate_pnr.map((d) => '<a href="#/bookings/' + d.id + '" target="_blank">' + esc(d.ref) + "</a>").join(", ") + ". Check you are not entering the same ticket twice.</div></div>" : "")}
        ${QT.raw(r.duplicate_tickets.length ? '<div class="alert warn small">' + icon("alert") + "<div>Ticket number " + r.duplicate_tickets.map((d) => esc(d.ticket_number) + " (" + esc(d.ref) + ")").join(", ") + " is already recorded.</div></div>" : "")}
        ${QT.raw(r.warnings.map((w) => '<div class="alert warn small">' + icon("alert") + "<div>" + esc(w) + "</div></div>").join(""))}
        ${QT.raw(r.missing.length ? '<div class="small"><b class="neg">Enter manually:</b> ' + r.missing.map(esc).join(" · ") + "</div>" : '<div class="small pos">Every main field was found. Still review the highlighted fields before saving.</div>')}
        ${fareTxt ? QT.raw('<div class="scan-fare"><div><span class="muted small">Fare printed on the ticket</span><div><b>' + esc(fareTxt) + "</b>" + (fare.base.value ? ' <span class="muted small">base ' + esc(fare.base.value) + "</span>" : "") + (fare.taxes.value ? ' <span class="muted small">taxes ' + esc(fare.taxes.value) + "</span>" : "") + '</div><div class="small muted">Not filled in automatically - your purchase cost and selling price may differ from the ticket fare.</div></div><div class="row" style="gap:6px"><button type="button" class="btn xs" data-fare="purchase_cost">Use as purchase cost</button><button type="button" class="btn xs" data-fare="selling_price">Use as selling price</button></div></div>') : ""}
        <div class="scan-legend small"><span class="sc ok">Read clearly</span><span class="sc check">Please check</span><span class="sc missing">Missing - enter manually</span><span class="muted">Fields are highlighted in the form below. Edit anything before saving.</span></div>`.html;
      $("[data-replace]", res).onclick = () => input.click();
      $("[data-remove]", res).onclick = () => { discard(); hooks.onClear && hooks.onClear(); };
      $$("[data-fare]", res).forEach((b) => { b.onclick = () => hooks.onFare && hooks.onFare(b.dataset.fare, fare); });
      const z = $("[data-zoom]", res);
      if (z) z.onclick = (e) => { e.preventDefault(); QT.modal({ title: r.file_name, size: "xl", body: '<div style="text-align:center"><img style="max-width:100%;border:1px solid var(--border);border-radius:8px" src="/api/ticket-scan/' + esc(r.scan_id) + '/preview"></div>', actions: [{ label: "Close", cls: "primary" }] }); };
    }
    function discard() {
      if (current) QT.del("/api/ticket-scan/" + current.scan_id).catch(() => {});
      current = null;
      res.classList.add("hidden");
      res.innerHTML = "";
      drop.classList.remove("hidden");
      input.value = "";
    }
    input.addEventListener("change", () => scan(input.files[0]));
    ["dragenter", "dragover"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.add("over"); }));
    ["dragleave", "drop"].forEach((ev) => drop.addEventListener(ev, (e) => { e.preventDefault(); drop.classList.remove("over"); }));
    drop.addEventListener("drop", (e) => scan(e.dataTransfer.files[0]));
    drop.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); input.click(); } });
    return { scan, clear: discard, current: () => current };
  };

  // mark a form control with the scanner's confidence; the mark clears as soon as the user edits the field
  QT.markScan = function (inp, f) {
    if (!inp) return;
    inp.classList.remove("scan-ok", "scan-check", "scan-missing");
    const c = CONF[f && f.value !== "" && f.value !== null ? f.confidence : "missing"] || CONF.missing;
    inp.classList.add("scan-" + c[0]);
    inp.title = c[1] + (f && f.note ? " - " + f.note : "");
    const clear = () => { inp.classList.remove("scan-ok", "scan-check", "scan-missing"); inp.title = ""; inp.removeEventListener("input", clear); inp.removeEventListener("change", clear); };
    inp.addEventListener("input", clear);
    inp.addEventListener("change", clear);
  };
})();
