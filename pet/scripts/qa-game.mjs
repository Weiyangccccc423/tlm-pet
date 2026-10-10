import { _electron as electron } from 'playwright';
import { mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { uIOhook, UiohookKey } from 'uiohook-napi';

const profile = await mkdtemp(path.join(tmpdir(), 'winefox-game-'));
const packaged = process.argv.includes('--packaged');
const root = packaged ? path.resolve('release/WineFoxPet') : process.cwd();
const env = { ...process.env, WINEFOX_DATA_DIR: profile }; delete env.ELECTRON_RUN_AS_NODE;
const mouse = (flags, x = 180, y = 160, data = 0) => {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public static class WineFoxQAInput { [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y); [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint x,uint y,uint data,UIntPtr extra); }'; [WineFoxQAInput]::SetCursorPos(${x},${y}); [WineFoxQAInput]::mouse_event(${flags},0,0,${data},[UIntPtr]::Zero)`], { windowsHide: true, encoding: 'utf8' });
  if (result.status !== 0) throw new Error(result.stderr);
};
const moveMouse = (x, y) => mouse(1, x, y);
await mkdir('qa-artifacts', { recursive: true });
let application;
try {
  application = await electron.launch(packaged ? { executablePath: path.join(root, '酒狐.exe'), args: [], cwd: profile, env }
    : { args: [path.resolve('dist-electron/electron/main.js')], cwd: profile, env });
  const page = await application.firstWindow();
  const errors = []; page.on('pageerror', error => errors.push(error.message));
  await page.waitForFunction(() => window.petQA?.inspect().ready && typeof window.petQA?.inspect().memories === 'number');
  await page.getByRole('button', { name: '游戏模式', exact: true }).click();
  await page.waitForFunction(() => window.petQA.inspect().mode === 'game' && window.petQA.inspect().inputStatus === 'active');
  await page.evaluate(() => { document.body.style.background = '#eff1e9'; });
  await page.waitForTimeout(200);
  await page.screenshot({ path: `qa-artifacts/game-${packaged ? 'packaged' : 'idle'}.png` });
  await application.evaluate(async ({ BrowserWindow }) => {
    const receiver = new BrowserWindow({ x: 100, y: 100, width: 280, height: 220, title: 'WineFox QA input receiver' });
    await receiver.loadURL('data:text/html,<h2>WineFox input test</h2><p>Local test window</p>'); receiver.focus();
  });
  uIOhook.keyToggle(UiohookKey.W, 'down'); uIOhook.keyToggle(UiohookKey.Shift, 'down');
  await page.waitForFunction(() => { const input = window.petQA.inspect().gameInput; return input.keys.includes('W') && input.keys.includes('Shift'); });
  mouse(2); // A real Windows left-button press in the separate test window.
  await page.waitForFunction(() => window.petQA.inspect().gameInput.buttons.includes('left'));
  await page.screenshot({ path: 'qa-artifacts/game-synced.png' });
  mouse(4);
  mouse(8, 190, 165);
  await page.waitForFunction(() => window.petQA.inspect().gameInput.buttons.includes('right'));
  mouse(16, 190, 165);
  moveMouse(420, 330);
  if (!packaged) await page.waitForFunction(() => window.petQA.inspect().gameInput.mouseOffset.some(value => Math.abs(value) > 0));
  mouse(32, 210, 185);
  await page.waitForFunction(() => window.petQA.inspect().gameInput.buttons.includes('middle'));
  mouse(64, 210, 185);
  mouse(2048, 210, 185, 120);
  await page.waitForFunction(() => window.petQA.inspect().gameInput.wheelActive);
  uIOhook.keyToggle(UiohookKey.W, 'up'); uIOhook.keyToggle(UiohookKey.Shift, 'up');
  await page.waitForFunction(() => { const input = window.petQA.inspect().gameInput; return !input.keys.length && !input.buttons.length; });
  const keyboard = await page.evaluate(() => window.petQA.inspect().gameInput);
  assert.equal(keyboard.keyboardFacing, 'pet');
  assert.ok(keyboard.keyPositions.Q[1] < keyboard.keyPositions.Space[1], 'Space row should be closest to Wine Fox');
  assert.ok(keyboard.keyPositions.Q[0] < keyboard.keyPositions.E[0], 'Columns should face Wine Fox');
  for (const key of ['Q', 'W', 'E', 'A', 'S', 'D', 'Shift', 'Space', 'Ctrl']) {
    uIOhook.keyToggle(UiohookKey[key], 'down');
    await page.waitForFunction(key => {
      const input = window.petQA.inspect().gameInput;
      const target = input.keyPositions[key];
      return input.handKey === key && Math.hypot(input.handPosition[0] - target[0], input.handPosition[2] - target[1]) < 0.08;
    }, key);
    await page.waitForTimeout(70);
    const pose = await page.evaluate(() => window.petQA.inspect());
    assert.ok(Math.hypot(...pose.keyboardHand.map((value, i) => value - pose.gameInput.handPosition[i])) < 0.15, `Palm should reach ${key}`);
    if (['Q', 'E', 'Space'].includes(key)) await page.screenshot({ path: `qa-artifacts/game-hand-${key.toLowerCase()}.png` });
    uIOhook.keyToggle(UiohookKey[key], 'up');
  }
  await page.getByRole('button', { name: '聊天', exact: true }).click();
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'off');
  await page.getByRole('button', { name: '关闭面板' }).click();
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'active');
  await page.getByRole('button', { name: '暂停或开启键鼠同步' }).click();
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'off');
  assert.equal((await page.evaluate(() => window.petBridge.invoke('snapshot'))).settings.inputSync, false);
  await page.getByRole('button', { name: '暂停或开启键鼠同步' }).click();
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'active');
  await page.getByRole('button', { name: '坐下或休息' }).click();
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'off');
  await page.getByRole('button', { name: '普通模式', exact: true }).click();
  await page.waitForFunction(() => window.petQA.inspect().mode === 'normal' && window.petQA.inspect().inputStatus === 'off');
  await page.getByRole('button', { name: '游戏模式', exact: true }).click();
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'active');
  await page.evaluate(() => window.petBridge.invoke('window:hide'));
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'off');
  await application.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows().find(window => window.webContents.getURL().startsWith('file:')).showInactive());
  await page.waitForFunction(() => window.petQA.inspect().inputStatus === 'active');
  assert.deepEqual(errors, []);
  const stored = JSON.parse(await readFile(path.join(profile, 'data/state.json'), 'utf8'));
  assert.equal(stored.settings.mode, 'game');
  assert.equal(stored.messages.length, 0);
  assert.ok(!('keys' in stored) && !('gameInput' in stored));
  console.log(JSON.stringify({ status: 'passed', packaged, realGlobalKeyboard: true, realGlobalMouse: true, handReachesAllKeys: true, keyboardFacesPet: true, pauseAndResume: true, modeSaved: true }, null, 2));
} finally {
  mouse(4 | 16 | 64);
  for (const key of ['Q', 'W', 'E', 'A', 'S', 'D', 'Shift', 'Space', 'Ctrl']) uIOhook.keyToggle(UiohookKey[key], 'up');
  if (application) {
    await application.close();
  }
  await rm(profile, { recursive: true, force: true });
}
