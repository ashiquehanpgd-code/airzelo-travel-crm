'use strict';
const $ = (s) => document.querySelector(s);
const esc = (v) => String(v == null ? '' : v).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const status = (t) => { $('#status').textContent = t || ''; };

async function render() {
  const list = await window.desktop.listAgencies();
  const box = $('#agencies');
  if (!list.length) {
    box.innerHTML = '<div class="empty">No agencies yet. Click <b>New agency</b> to create your company file.</div>';
    return;
  }
  box.innerHTML = list.map((a) => `<div class="card"><div class="grow"><div class="name">${esc(a.name)}</div>
    <div class="muted">${a.last_opened ? 'Last opened ' + esc(new Date(a.last_opened).toLocaleString()) : 'Created ' + esc(new Date(a.created_at).toLocaleDateString())}</div></div>
    <button class="btn primary" type="button" data-open="${esc(a.id)}">Open</button></div>`).join('');
  box.querySelectorAll('[data-open]').forEach((b) => {
    b.onclick = async () => {
      document.querySelectorAll('button').forEach((x) => { x.disabled = true; });
      status('Opening… (checking and, if needed, safely upgrading the company file)');
      const r = await window.desktop.openAgency(b.dataset.open);
      if (!r.ok) {
        status('');
        alert(r.error);
        document.querySelectorAll('button').forEach((x) => { x.disabled = false; });
      }
    };
  });
}

async function renderWorkspaces() {
  const list = await window.desktop.listWorkspaces();
  const box = $('#workspaces');
  box.innerHTML = list.length ? list.map((w) => `<div class="card"><div class="grow"><div class="name">${esc(w.name)}</div>
    <div class="muted">${esc(w.host)}${w.last_opened ? ' · last opened ' + esc(new Date(w.last_opened).toLocaleString()) : ''}</div></div>
    <button class="btn" type="button" data-remove-ws="${esc(w.id)}">Remove</button><button class="btn primary" type="button" data-open-ws="${esc(w.id)}">Open</button></div>`).join('')
    : '<div class="empty">No cloud workspace yet. Use <b>Connect to a cloud workspace</b> if your office has a shared server.</div>';
  box.querySelectorAll('[data-open-ws]').forEach((b) => {
    b.onclick = async () => {
      document.querySelectorAll('button').forEach((x) => { x.disabled = true; });
      status('Connecting…');
      const r = await window.desktop.openWorkspace(b.dataset.openWs);
      if (!r.ok) { status(''); document.querySelectorAll('button').forEach((x) => { x.disabled = false; }); if (r.error !== 'unreachable') alert(r.error); }
    };
  });
  box.querySelectorAll('[data-remove-ws]').forEach((b) => {
    b.onclick = async () => {
      if (!confirm('Remove this workspace from the list on this computer? Nothing on the server is changed.')) return;
      await window.desktop.removeWorkspace(b.dataset.removeWs);
      await renderWorkspaces();
    };
  });
}

function show(view) {
  $('#list-view').hidden = view !== 'list';
  $('#new-view').hidden = view !== 'new';
  $('#new-err').textContent = '';
}

$('#add-ws-btn').onclick = () => { $('#ws-form').hidden = false; $('#ws-err').textContent = ''; $('#ws-form').reset(); $('[name=url]').focus(); };
$('#ws-cancel').onclick = () => { $('#ws-form').hidden = true; };
$('#ws-form').onsubmit = async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const r = await window.desktop.addWorkspace({ name: f.get('name'), url: f.get('url') });
  if (!r.ok) { $('#ws-err').textContent = r.error; return; }
  $('#ws-form').hidden = true;
  await renderWorkspaces();
  status(`“${r.workspace.name}” added. Click Open, then sign in with your own login.`);
};
$('#new-btn').onclick = () => { show('new'); $('#new-form').reset(); $('[name=agency_name]').focus(); };
$('#cancel-btn').onclick = () => show('list');
$('#data-btn').onclick = () => window.desktop.showData();
$('#new-form').onsubmit = async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  if (f.get('password') !== f.get('password2')) { $('#new-err').textContent = 'The passwords do not match.'; return; }
  $('#create-btn').disabled = true;
  status('Creating the company file…');
  const r = await window.desktop.createAgency({ agency_name: f.get('agency_name'), username: f.get('username'), password: f.get('password') });
  $('#create-btn').disabled = false;
  status('');
  if (!r.ok) { $('#new-err').textContent = r.error; return; }
  show('list');
  await render();
  status(`“${r.agency.name}” is ready. Click Open to sign in.`);
};

(async () => {
  const info = await window.desktop.info();
  $('#product').textContent = info.product;
  $('#version').textContent = `Version ${info.version} · ${info.platform === 'darwin' ? 'macOS' : info.platform === 'win32' ? 'Windows' : info.platform} ${info.arch}`;
  $('#data-path').textContent = info.dataRoot;
  const notice = new URLSearchParams(location.search).get('notice');
  if (notice) { $('#notice').textContent = notice; $('#notice').hidden = false; }
  await render();
  await renderWorkspaces();
})();
