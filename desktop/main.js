// AirZelo - desktop shell (Electron).
// Shows the agency (company file) chooser, starts the bundled Python core for the chosen agency on a free
// localhost port, and shows the CRM in the window. Each agency is a separate folder with its own database,
// attachments and backups, so agencies never share data.
'use strict';
const { app, BrowserWindow, ipcMain, dialog, shell, Menu } = require('electron');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PRODUCT = 'AirZelo'; // product name (also the data folder name: keep stable)
const isWin = process.platform === 'win32';

// TACRM_USER_DATA: separate data folder for automated tests (never set for customers)
if (process.env.TACRM_USER_DATA) app.setPath('userData', process.env.TACRM_USER_DATA);
if (!app.requestSingleInstanceLock()) { app.quit(); }

const dataRoot = () => path.join(app.getPath('userData'), 'agencies');
const logFile = () => path.join(app.getPath('userData'), 'logs', 'desktop.log');
function log(...a) {
  try {
    fs.mkdirSync(path.dirname(logFile()), { recursive: true });
    fs.appendFileSync(logFile(), `[${new Date().toISOString()}] ${a.join(' ')}\n`);
  } catch (_) { /* logging must never break the app */ }
}

// bundled runtime when packaged; the build/runtime copy (or python3 on PATH) during development
function pythonPath() {
  const exe = isWin ? 'python.exe' : path.join('bin', 'python3');
  if (app.isPackaged) return path.join(process.resourcesPath, 'python', exe);
  const target = `${isWin ? 'win' : 'mac'}-${process.arch}`;
  const dev = path.join(__dirname, '..', 'build', 'runtime', target, 'python', exe);
  return fs.existsSync(dev) ? dev : (isWin ? 'python' : 'python3');
}
const corePath = () => (app.isPackaged ? path.join(process.resourcesPath, 'core', 'run.py') : path.join(__dirname, '..', 'core', 'run.py'));
const ticketDir = (name) => path.join(app.getPath('documents'), PRODUCT, name.replace(/[\\/:*?"<>|]/g, '-').trim() || 'Agency', 'Tickets');

function coreEnv(extra) {
  return { ...process.env, PYTHONUNBUFFERED: '1', PYTHONDONTWRITEBYTECODE: '1', PYTHONNOUSERSITE: '1', PYTHONUTF8: '1', ...extra };
}

// ------------------------------------------------------------ agencies
function readAgencies() {
  fs.mkdirSync(dataRoot(), { recursive: true });
  return fs.readdirSync(dataRoot(), { withFileTypes: true })
    .filter((d) => d.isDirectory())
    .map((d) => {
      try { return JSON.parse(fs.readFileSync(path.join(dataRoot(), d.name, 'agency.json'), 'utf8')); } catch (_) { return null; }
    })
    .filter((a) => a && a.id && fs.existsSync(path.join(dataRoot(), a.id, 'agency.db')))
    .sort((a, b) => (b.last_opened || '').localeCompare(a.last_opened || ''));
}

function runCore(args, input) {
  return new Promise((resolve) => {
    const p = spawn(pythonPath(), ['-I', '-u', '-B', corePath(), ...args], { env: coreEnv(), windowsHide: true });
    let out = '';
    let err = '';
    p.stdout.on('data', (d) => { out += d; });
    p.stderr.on('data', (d) => { err += d; });
    p.on('error', (e) => resolve({ code: -1, out, err: String(e) }));
    p.on('close', (code) => resolve({ code, out, err }));
    if (input !== undefined) p.stdin.end(input); else p.stdin.end();
  });
}

async function createAgency(req) {
  const id = crypto.randomUUID();
  const dir = path.join(dataRoot(), id);
  fs.mkdirSync(dir, { recursive: true });
  const r = await runCore(['--data', dir, '--create-agency'], JSON.stringify({
    agency_name: String(req.agency_name || ''), username: String(req.username || ''), password: String(req.password || ''),
  }));
  let res = null;
  try { res = JSON.parse(r.out.trim().split('\n').pop()); } catch (_) { /* reported below */ }
  if (!res || !res.ok) {
    fs.rmSync(dir, { recursive: true, force: true }); // nothing half-created is left behind
    log('create agency failed', r.code, r.err.slice(-500));
    return { ok: false, error: (res && res.error) || 'The company file could not be created. See the log for details.', field: res && res.field };
  }
  const meta = { id, name: res.agency_name, created_at: new Date().toISOString(), last_opened: null };
  fs.writeFileSync(path.join(dir, 'agency.json'), JSON.stringify(meta, null, 2));
  log('created agency', id);
  return { ok: true, agency: meta };
}

// ------------------------------------------------------------ core process for the open agency
let core = null; // { proc, port, agency }
let win = null;

function stopCore() {
  return new Promise((resolve) => {
    if (!core) return resolve();
    const { proc } = core;
    core = null;
    const timer = setTimeout(() => { try { proc.kill(); } catch (_) { /* already gone */ } resolve(); }, 15000);
    proc.once('close', () => { clearTimeout(timer); resolve(); });
    try { proc.stdin.end(); } catch (_) { proc.kill(); } // closing stdin = clean shutdown (WAL checkpoint) on every OS
  });
}

function startCore(agency) {
  return new Promise((resolve, reject) => {
    const dir = path.join(dataRoot(), agency.id);
    const proc = spawn(pythonPath(), ['-I', '-u', '-B', corePath(), '--data', dir, '--port', '0', '--no-browser'], {
      env: coreEnv({ TACRM_STDIN_SHUTDOWN: '1', TACRM_TICKET_DIR: ticketDir(agency.name), TACRM_NO_OPEN: '' }), windowsHide: true,
    });
    let buf = '';
    let done = false;
    const fail = (msg) => { if (!done) { done = true; reject(new Error(msg)); } };
    const timer = setTimeout(() => { fail('The CRM did not start within 60 seconds.'); try { proc.kill(); } catch (_) { /* ignore */ } }, 60000);
    proc.stdout.on('data', (d) => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 1);
        if (line.startsWith('TACRM_READY port=') && !done) {
          done = true;
          clearTimeout(timer);
          resolve({ proc, port: Number(line.split('=')[1]), agency });
        } else if (line.startsWith('TACRM_ERROR ')) {
          clearTimeout(timer);
          fail(line.slice('TACRM_ERROR '.length));
        } else if (line) {
          log('core:', line);
        }
      }
    });
    proc.stderr.on('data', (d) => log('core!', String(d).trim()));
    proc.on('error', (e) => { clearTimeout(timer); fail('The CRM engine could not start: ' + e.message); });
    proc.on('close', (code) => {
      clearTimeout(timer);
      fail(`The CRM engine stopped (code ${code}). See the log for details.`);
      if (core && core.proc === proc) {
        core = null;
        if (win && !win.isDestroyed()) {
          dialog.showMessageBox(win, { type: 'error', message: 'The CRM engine stopped unexpectedly.', detail: 'Your data is saved. Open the agency again to continue.' });
          showLauncher();
        }
      }
    });
  });
}

async function openAgency(id) {
  const agency = readAgencies().find((a) => a.id === id);
  if (!agency) return { ok: false, error: 'Agency not found' };
  await stopCore();
  try {
    core = await startCore(agency);
  } catch (e) {
    log('open failed', id, e.message);
    return { ok: false, error: e.message };
  }
  agency.last_opened = new Date().toISOString();
  fs.writeFileSync(path.join(dataRoot(), id, 'agency.json'), JSON.stringify(agency, null, 2));
  win.setTitle(`${agency.name} — ${PRODUCT}`);
  await win.loadURL(`http://127.0.0.1:${core.port}/`);
  return { ok: true };
}

// ------------------------------------------------------------ windows
function showLauncher() {
  win.setTitle(PRODUCT);
  win.loadFile(path.join(__dirname, 'launcher', 'index.html'));
}

function allowedUrl(url) {
  if (url.startsWith('file://')) return url.includes('/launcher/');
  return core ? url.startsWith(`http://127.0.0.1:${core.port}/`) : false;
}

function createWindow() {
  win = new BrowserWindow({
    width: 1360, height: 860, minWidth: 980, minHeight: 640, title: PRODUCT, show: false,
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, sandbox: true, nodeIntegration: false },
  });
  win.once('ready-to-show', () => win.show());
  // the CRM page may only navigate inside itself; WhatsApp and other links open in the default browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    if (!allowedUrl(url)) { e.preventDefault(); if (/^https:\/\//.test(url)) shell.openExternal(url); }
  });
  showLauncher();
}

function buildMenu() {
  const template = [
    ...(process.platform === 'darwin' ? [{ role: 'appMenu' }] : []),
    {
      label: 'File',
      submenu: [
        { label: 'Switch agency…', accelerator: 'CmdOrCtrl+Shift+A', click: async () => { await stopCore(); showLauncher(); } },
        { label: 'Show data folder', click: () => shell.openPath(dataRoot()) },
        { type: 'separator' },
        process.platform === 'darwin' ? { role: 'close' } : { role: 'quit' },
      ],
    },
    { role: 'editMenu' },
    { label: 'View', submenu: [{ role: 'reload' }, { role: 'togglefullscreen' }, { type: 'separator' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { role: 'resetZoom' },
      ...(app.isPackaged ? [] : [{ role: 'toggleDevTools' }])] },
    { role: 'windowMenu' },
    { role: 'help', submenu: [{ label: 'Open log folder', click: () => shell.openPath(path.dirname(logFile())) }] },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// the bridge only answers the local agency chooser page, never a page served by the core
const fromLauncher = (e) => !!(e.senderFrame && e.senderFrame.url.startsWith('file://') && e.senderFrame.url.includes('/launcher/'));
function handle(name, fn) {
  ipcMain.handle(name, (e, ...args) => (fromLauncher(e) ? fn(...args) : Promise.reject(new Error('Not allowed'))));
}
handle('agencies:list', () => readAgencies().map((a) => ({ id: a.id, name: a.name, created_at: a.created_at, last_opened: a.last_opened })));
handle('agencies:create', (req) => createAgency(req || {}));
handle('agencies:open', (id) => openAgency(String(id)));
handle('app:info', () => ({ product: PRODUCT, version: app.getVersion(), platform: process.platform, arch: process.arch, dataRoot: dataRoot() }));
handle('app:showData', () => shell.openPath(dataRoot()));

app.on('second-instance', () => { if (win) { if (win.isMinimized()) win.restore(); win.focus(); } });
app.whenReady().then(() => { log('start', app.getVersion(), process.platform, process.arch); buildMenu(); createWindow(); });
app.on('window-all-closed', () => app.quit());
let quitting = false;
app.on('before-quit', (e) => {
  if (quitting || !core) return;
  e.preventDefault();
  quitting = true;
  stopCore().then(() => app.quit());
});
