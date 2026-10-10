import { app, BrowserWindow, dialog, ipcMain, Menu, nativeImage, safeStorage, screen, shell, Tray } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AgentService } from '../src/agent/service.js';
import { GameInput } from './game-input.js';
import type { Snapshot } from '../src/shared/types.js';
const directory = path.dirname(fileURLToPath(import.meta.url));
const promptRoot = app.isPackaged ? path.dirname(process.execPath) : path.resolve(directory, '../..');
let pet: BrowserWindow;
let tray: Tray;
let quitting = false;
let input: GameInput | undefined;
function restartPet(): void { app.relaunch(); app.quit(); }
app.setName('WineFoxPet');
app.setPath('userData', process.env.WINEFOX_DATA_DIR ? path.resolve(process.env.WINEFOX_DATA_DIR) : path.join(app.getPath('appData'), 'WineFoxPet'));
app.setAppUserModelId('local.winefox.pet');

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { pet?.show(); pet?.focus(); });
  app.whenReady().then(async () => {
    const service = new AgentService(path.join(app.getPath('userData'), 'data'), {
      encrypt: value => {
        if (!safeStorage.isEncryptionAvailable()) throw new Error('系统密钥存储不可用');
        return safeStorage.encryptString(value).toString('base64');
      },
      decrypt: value => safeStorage.decryptString(Buffer.from(value, 'base64')),
    }, promptRoot);
    let state = await service.invoke('snapshot') as Snapshot;
    let inputPaused = false;
    const workArea = screen.getPrimaryDisplay().workArea;
    pet = new BrowserWindow({
      width: 480, height: 650, x: workArea.x + workArea.width - 510, y: workArea.y + workArea.height - 670,
      frame: false, transparent: true, resizable: false, hasShadow: false,
      alwaysOnTop: state.settings.alwaysOnTop, skipTaskbar: true, show: false,
      title: '酒狐', backgroundColor: '#00000000',
      webPreferences: { preload: path.join(directory, 'preload.cjs'), contextIsolation: true, nodeIntegration: false, sandbox: true },
    });
    pet.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
    input = new GameInput(event => { if (!pet.isDestroyed() && !quitting) pet.webContents.send('pet:input', event); });
    const updateInput = () => input!.setEnabled(state.settings.mode === 'game' && state.settings.inputSync && !inputPaused && pet.isVisible());
    pet.on('show', () => { void updateInput(); });
    pet.on('hide', () => { void updateInput(); });
    pet.webContents.on('will-navigate', event => event.preventDefault());
    ipcMain.handle('pet:invoke', async (event, action: string, payload: unknown) => {
      if (event.sender !== pet.webContents) throw new Error('未知窗口');
      if (action === 'window:hide') { pet.hide(); return; }
      if (action === 'window:quit') { app.quit(); return; }
      if (action === 'window:restart') { restartPet(); return; }
      if (action === 'window:source') { await shell.openExternal('https://github.com/TartaricAcid/WineFoxModel#readme'); return; }
      if (action === 'window:interactive') { pet.setIgnoreMouseEvents(payload !== true, { forward: true }); return; }
      if (action === 'window:move') {
        const point = payload as { x: number; y: number };
        if (!Number.isFinite(point.x) || !Number.isFinite(point.y)) return;
        const bounds = pet.getBounds();
        const area = screen.getDisplayNearestPoint(screen.getCursorScreenPoint()).workArea;
        pet.setPosition(Math.round(Math.max(area.x - bounds.width + 100, Math.min(area.x + area.width - 100, point.x))),
          Math.round(Math.max(area.y, Math.min(area.y + area.height - 80, point.y))));
        return;
      }
      if (action === 'window:bounds') return pet.getBounds();
      if (action === 'input:state') return input!.snapshot();
      if (action === 'input:pause') { inputPaused = payload === true; await updateInput(); return input!.snapshot(); }
      if (action === 'saves:choose') {
        const selection = await dialog.showOpenDialog(pet, { title: '选择 Minecraft 存档目录', properties: ['openDirectory'], buttonLabel: '读取存档' });
        if (selection.canceled) return null;
        return service.invoke('saves:add', { path: selection.filePaths[0] });
      }
      const result = await service.invoke(action, payload);
      if (['settings', 'mode', 'input-sync'].includes(action)) {
        state = result as Snapshot;
        pet.setAlwaysOnTop(state.settings.alwaysOnTop);
        await updateInput();
      }
      return result;
    });
    const icon = nativeImage.createFromPath(path.join(directory, '../../dist/assets/tray.png'));
    tray = new Tray(icon);
    tray.setToolTip('酒狐 · 桌面陪伴');
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: '显示酒狐', click: () => { pet.show(); pet.focus(); } },
      { label: '隐藏酒狐', click: () => pet.hide() },
      { label: '重启酒狐', click: restartPet },
      { type: 'separator' }, { label: '退出', click: () => app.quit() },
    ]));
    tray.on('double-click', () => { pet.show(); pet.focus(); });
    pet.on('close', event => { if (!quitting) { event.preventDefault(); pet.hide(); } });
    pet.once('ready-to-show', () => pet.showInactive());
    await pet.loadFile(path.join(directory, '../../dist/index.html'));
  }).catch(error => { console.error(error); app.quit(); });
  app.on('before-quit', () => { quitting = true; input?.dispose(); });
  app.on('window-all-closed', () => app.quit());
}
