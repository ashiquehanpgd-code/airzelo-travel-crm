// Only the agency chooser uses this bridge; the CRM pages talk to their local core over HTTP.
'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('desktop', {
  listAgencies: () => ipcRenderer.invoke('agencies:list'),
  createAgency: (req) => ipcRenderer.invoke('agencies:create', req),
  openAgency: (id) => ipcRenderer.invoke('agencies:open', id),
  info: () => ipcRenderer.invoke('app:info'),
  showData: () => ipcRenderer.invoke('app:showData'),
});
