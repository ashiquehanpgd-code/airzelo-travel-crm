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

function show(view) {
  $('#list-view').hidden = view !== 'list';
  $('#new-view').hidden = view !== 'new';
  $('#new-err').textContent = '';
}

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
  await render();
})();
