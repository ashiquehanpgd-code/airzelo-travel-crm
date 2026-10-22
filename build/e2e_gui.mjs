// End-to-end test of a PACKAGED desktop app through its real window (Chrome DevTools Protocol):
// agency chooser -> create agency -> open -> CRM page served by the bundled core -> sign in -> quit cleanly.
//   node build/e2e_gui.mjs "<path to app executable>"
// Uses a temporary data folder (TACRM_USER_DATA) so no real agency data is touched.
import { spawn, execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const exe = process.argv[2];
const userData = fs.mkdtempSync(path.join(os.tmpdir(), 'ta-e2e-'));
const port = 9333;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const app = spawn(exe, [`--remote-debugging-port=${port}`], { env: { ...process.env, TACRM_USER_DATA: userData }, stdio: 'ignore' });
let exited = false;
app.on('exit', () => { exited = true; });

async function page(match) {
  for (let i = 0; i < 120; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
      const p = list.find((t) => t.type === 'page' && match(t.url));
      if (p) return p;
    } catch (_) { /* not up yet */ }
    await sleep(500);
  }
  throw new Error('page not found');
}
function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl);
  let id = 0;
  const pending = new Map();
  ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } };
  const ready = new Promise((r) => { ws.onopen = r; });
  const send = async (method, params = {}) => { await ready; const i = ++id; ws.send(JSON.stringify({ id: i, method, params })); return new Promise((r) => pending.set(i, r)); };
  const evaluate = async (expression) => {
    const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
    if (r.result.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description || 'evaluate failed');
    return r.result.result.value;
  };
  return { send, evaluate, close: () => ws.close() };
}

let ok = false;
try {
  const launcher = cdp((await page((u) => u.includes('/launcher/index.html'))).webSocketDebuggerUrl);
  for (let i = 0; i < 40 && !(await launcher.evaluate('document.readyState === "complete" && !!window.desktop')); i++) await sleep(250);
  const info = await launcher.evaluate('window.desktop.info()');
  console.log('app:', info.product, info.version, info.platform, info.arch, '| data:', info.dataRoot);
  if (!info.dataRoot.startsWith(userData)) throw new Error('not using the temporary data folder');
  const empty = await launcher.evaluate('document.querySelector("#agencies").innerText');
  console.log('chooser (empty):', empty.trim().slice(0, 60));
  // real form: fill and submit like a user
  const created = await launcher.evaluate(`(async () => {
    document.querySelector('#new-btn').click();
    const set = (n, v) => { document.querySelector('[name=' + n + ']').value = v; };
    set('agency_name', 'GUI Test Travels'); set('username', 'owner'); set('password', 'Gui-Test-Pass-123'); set('password2', 'Gui-Test-Pass-123');
    document.querySelector('#create-btn').click();
    for (let i = 0; i < 120; i++) { await new Promise((r) => setTimeout(r, 250)); if (document.querySelector('[data-open]')) break; }
    return document.querySelector('#agencies').innerText;
  })()`);
  console.log('after create:', created.replace(/\s+/g, ' ').trim());
  await launcher.evaluate('document.querySelector("[data-open]").click(), true');
  const crm = cdp((await page((u) => /^http:\/\/127\.0\.0\.1:\d+\//.test(u))).webSocketDebuggerUrl);
  for (let i = 0; i < 80 && !(await crm.evaluate('document.readyState === "complete" && !!window.QT')); i++) await sleep(250);
  const login = await crm.evaluate(`fetch('/api/auth/login', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-QT-CSRF': '1'},
    body: JSON.stringify({username: 'owner', password: 'Gui-Test-Pass-123'})}).then((r) => r.status)`);
  const meta = await crm.evaluate(`fetch('/api/meta', {headers: {'X-QT-CSRF': '1'}}).then((r) => r.json()).then((m) => ({name: m.settings.business_name, pay: m.settings.pay_account_number}))`);
  const title = await crm.evaluate('document.title');
  console.log('CRM page:', title, '| login', login, '| agency', meta.name, '| payment details built in:', !!meta.pay);
  if (login !== 200 || meta.name !== 'GUI Test Travels' || meta.pay) throw new Error('unexpected CRM state');
  // ---- first-run setup wizard, driven through the real page (synthetic company)
  await crm.evaluate('location.reload(), true');
  await sleep(1500);
  const until = async (expr, what) => { for (let i = 0; i < 80; i++) { try { if (await crm.evaluate(expr)) return; } catch (_) { /* page loading */ } await sleep(250); } throw new Error('timeout: ' + what); };
  await until('location.hash.startsWith("#/setup") && !!document.querySelector(".wizard-steps")', 'wizard shown on first run');
  const setv = (n, v) => `(() => { const e = document.querySelector('[name=${n}]'); e.value = ${JSON.stringify(v)}; e.dispatchEvent(new Event('input', {bubbles: true})); return true; })()`;
  await crm.evaluate(setv('legal_name', 'GUI Test Travels Private Limited'));
  await crm.evaluate(setv('business_email', 'hello@guitest.example'));
  await crm.evaluate(setv('business_website', 'www.guitest.example'));
  const next = async (n) => { await crm.evaluate('document.querySelector("[data-next]").click(), true'); await until(`location.hash === "#/setup?step=${n}" && !!document.querySelector("[data-next]")`, 'step ' + n); };
  await next(1);
  // logo upload uses the same endpoint as the page's file picker (a synthetic 32x32 PNG)
  const zlib = await import('node:zlib');
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (t, d) => { const len = Buffer.alloc(4); len.writeUInt32BE(d.length); const td = Buffer.concat([Buffer.from(t), d]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(32, 0); ihdr.writeUInt32BE(32, 4); ihdr[8] = 8; ihdr[9] = 2;
  const rows = Buffer.concat(Array.from({ length: 32 }, (_, y) => Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: 96 }, (_, i) => (i * 5 + y * 3) % 256))])));
  const logo = Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]).toString('base64');
  const up = await crm.evaluate(`fetch('/api/settings/logo', {method: 'POST', headers: {'Content-Type': 'application/json', 'X-QT-CSRF': '1'}, body: JSON.stringify({data: '${logo}'})}).then((r) => r.status)`);
  if (up !== 200) throw new Error('logo upload ' + up);
  await crm.evaluate(setv('brand_color', '#0E7C66'));
  await next(2);
  await next(3);
  await crm.evaluate(setv('invoice_prefix', 'GUI/'));
  await crm.evaluate(`(() => { const e = document.querySelector('[name=date_format]'); e.value = 'DD/MM/YYYY'; return true; })()`);
  await next(4);
  const sample = await crm.evaluate('document.querySelector("[data-body]").innerText');
  const sampleLogo = await crm.evaluate('!!document.querySelector("[data-body] .doc-brand img")');
  console.log('wizard preview: prefix GUI/', sample.includes('GUI/'), '| legal name', sample.includes('GUI Test Travels Private Limited'), '| logo', sampleLogo, '| DD/MM/YYYY date', /\d\d\/\d\d\/\d{4}/.test(sample));
  if (!sample.includes('GUI/') || !sample.includes('GUI Test Travels Private Limited') || !sampleLogo) throw new Error('sample invoice does not show the configured settings');
  await next(5);
  await crm.evaluate('document.querySelector("[data-next]").click(), true');
  await until('location.hash === "#/dashboard" && !!document.querySelector(".sidebar")', 'dashboard after setup');
  const shell = await crm.evaluate(`({ logo: !!document.querySelector('.brand-mark.logo img'), accent: getComputedStyle(document.documentElement).getPropertyValue('--royal').trim(),
    name: document.querySelector('.brand-name').textContent })`);
  const saved = await crm.evaluate(`fetch('/api/meta', {headers: {'X-QT-CSRF': '1'}}).then((r) => r.json()).then((m) => [m.settings.setup_completed, m.settings.legal_name, m.settings.invoice_prefix, m.settings.brand_color, !!m.settings.logo_file].join(' | '))`);
  console.log('after setup: sidebar logo', shell.logo, '| accent', shell.accent, '| name', shell.name, '| saved:', saved);
  if (!shell.logo || shell.accent.toLowerCase() !== '#0e7c66' || !saved.startsWith('1 | GUI Test Travels Private Limited | GUI/ | #0E7C66 | true')) throw new Error('settings not applied');
  await crm.evaluate('location.hash = "#/settings/business", true');
  await until('!!document.querySelector("[data-logo-box] img") && document.querySelector("[name=legal_name]").value === "GUI Test Travels Private Limited"', 'settings page shows saved profile');
  console.log('settings page: profile and logo shown for editing');

  // the bridge must refuse calls from a CRM page
  const refused = await crm.evaluate('typeof window.desktop === "undefined" ? "no bridge" : window.desktop.listAgencies().then(() => "ALLOWED", (e) => "refused")');
  console.log('desktop bridge from CRM page:', refused);
  if (refused === 'ALLOWED') throw new Error('bridge reachable from CRM page');
  crm.close(); launcher.close();
  // quit like a user (Cmd+Q / close): the shell must stop the core cleanly
  const browser = cdp((await (await fetch(`http://127.0.0.1:${port}/json/version`)).json()).webSocketDebuggerUrl);
  browser.send('Browser.close').catch(() => {});
  for (let i = 0; i < 80 && !exited; i++) await sleep(250);
  if (!exited) throw new Error('app did not quit');
  await sleep(1000);
  const procs = process.platform === 'win32'
    ? execFileSync('powershell', ['-NoProfile', '-Command', 'Get-CimInstance Win32_Process | ForEach-Object { $_.CommandLine }']).toString()
    : execFileSync('ps', ['-axo', 'command']).toString();
  const left = procs.split(/\r?\n/).filter((l) => l.includes(userData));
  const dir = fs.readdirSync(path.join(userData, 'agencies'))[0];
  const db = path.join(userData, 'agencies', dir, 'agency.db');
  const wal = fs.existsSync(db + '-wal') ? fs.statSync(db + '-wal').size : 0;
  console.log('after quit: engine processes left', left.length, '| WAL bytes', wal);
  if (left.length || wal) throw new Error('core not shut down cleanly');
  ok = true;
  console.log('GUI E2E PASSED');
} catch (e) {
  console.error('GUI E2E FAILED:', e.message);
} finally {
  if (!exited) app.kill();
  fs.rmSync(userData, { recursive: true, force: true });
  process.exit(ok ? 0 : 1);
}
