import { contextBridge, ipcRenderer } from 'electron';
contextBridge.exposeInMainWorld('petBridge', {
  desktop: true,
  invoke: (action: string, payload?: unknown) => ipcRenderer.invoke('pet:invoke', action, payload),
  onInput: (listener: (state: unknown) => void) => {
    const receive = (_event: Electron.IpcRendererEvent, state: unknown) => listener(state);
    ipcRenderer.on('pet:input', receive);
    return () => ipcRenderer.removeListener('pet:input', receive);
  },
});
