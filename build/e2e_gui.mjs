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
